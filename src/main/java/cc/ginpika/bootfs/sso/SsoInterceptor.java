package cc.ginpika.bootfs.sso;

import cc.ginpika.bootfs.dto.SsoSession;
import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.service.SsoService;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

@Slf4j
@Component
public class SsoInterceptor implements HandlerInterceptor {
    @Autowired
    private cc.ginpika.bootfs.kernel.cluster.ClusterCredentials credentials;
    @Autowired
    private cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata;

    @Autowired
    private SsoService ssoService;

    @Autowired
    private LocalAccessPolicy localAccess;

    @Autowired private SsoConfig config;
    @Autowired private cc.ginpika.bootfs.config.TfsConfig appConfig;

    public SsoInterceptor(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod method)) return true; // Packaged static assets.
        if (cc.ginpika.bootfs.s3.S3Controller.class.isAssignableFrom(method.getBeanType())) {
            // Authentication belongs to the S3 filter, including when MVC normalizes the URL.
            if (Boolean.TRUE.equals(request.getAttribute("bootfs.s3.authenticated"))) {
                privateResponse(request, response); return true;
            }
            return deny(response, 401, "S3 signature required");
        }
        // Match MVC's resolved pattern, never an unnormalized attacker-controlled URI.
        String path = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        String supplied = request.getHeader("X-BootFS-Cluster-Token");
        boolean bootstrapPath = path.equals("/api/cluster/status") || path.equals("/api/cluster/ready")
                || path.equals("/api/cluster/join") || path.equals("/api/cluster/connect");
        String peer = metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic ? dynamic.actor(supplied,bootstrapPath) : null;
        boolean peerPath = bootstrapPath || path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure")
                || path.startsWith("/api/cluster/data/")
                || path.equals("/t/prepare")
                || path.startsWith("/api/file/") || path.startsWith("/publicAccess/")
                || path.startsWith("/api/album/") || path.startsWith("/file/") || path.startsWith("/hls/")
                || (read && Set.of("/f/{uuid}", "/p/{uuid}", "/thumb/{uuid}").contains(path))
                || path.equals("/removeFileByUuid") || path.equals("/api/dashboard/node-stats");
        if (peer != null && peerPath && ((cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore)metadata).verifyPeerRequest(request,supplied,bootstrapPath)) {
            privateResponse(request, response);
            return true;
        }
        if (("/t".equals(path) || "/t/prepare".equals(path) || path.startsWith("/api/cluster/")) && credentials.accepts(supplied)) {
            privateResponse(request, response); return true;
        }
        // Bootstrap is authorized by its short-lived code, not an existing node credential or SSO session.
        if ("POST".equals(request.getMethod()) && "/api/cluster/pairing/redeem".equals(path)) {
            privateResponse(request, response);
            return BrowserRequestPolicy.allows(request, false) || deny(response, 403, "Cross-origin request denied");
        }
        if ("POST".equals(request.getMethod()) && (path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure"))
                && metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic && dynamic.verifyRevokedRequest(request,supplied)) {
            response.setStatus(410); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Membership authorization was revoked\"}"); return false;
        }
        boolean machine = path.equals("/api/cluster/join") || path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure") || path.startsWith("/api/cluster/data/");
        if (machine || "/api/cluster/pairing/adopt".equals(path) || supplied != null) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Pairing authorization required\"}"); return false;
        }
        boolean publicRead = Set.of("/f/{uuid}", "/p/{uuid}", "/thumb/{uuid}", "/setu", "/setu-url",
                "/video-player", "/album/**", "/gallery", "/tv", "/tv/", "/tv/status", "/tv/playlist.m3u8",
                "/tv/ts/{uuid}/{fileName}", "/hls/{folder}/{file}").contains(path);
        if (read && config.isPublicSharingEnabled() && publicRead) return true;
        privateResponse(request, response);
        if (!ssoService.isEnabled()) {
            return BrowserRequestPolicy.allows(request, false) || deny(response, 403, "Cross-origin request denied");
        }

        if (localAccess.authenticate(request, response)) return true;

        String token = getTokenFromCookie(request);

        if (token == null || token.isEmpty()) {
            redirectToLogin(request, response);
            return false;
        }

        try {
            SsoSession session = ssoService.checkSession(token).block(Duration.ofSeconds(5));

            if (session != null && session.isAuthenticated()) {
                SsoUser user = session.getUser();
                if (user == null || !config.permitsRole(user.getRole())) {
                    return deny(response, 403, "Application administrator role required");
                }
                boolean navigation = read && !path.equals("/api/logout")
                        && "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
                        && "document".equals(request.getHeader("Sec-Fetch-Dest"));
                if (!navigation && !BrowserRequestPolicy.allows(request, !read || path.equals("/api/logout"))) {
                    return deny(response, 403, "Same-origin request required");
                }
                request.setAttribute("ssoUser", user);
                request.setAttribute("ssoToken", token);
                return true;
            }
        } catch (Exception e) {
            log.warn("SSO session verification failed");
        }

        redirectToLogin(request, response);
        return false;
    }

    private void privateResponse(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(ResponseSecurityFilter.PRIVATE, true);
        response.setHeader("Cache-Control", "no-store");
    }

    private boolean deny(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}"); return false;
    }

    private String getTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ssoService.getCookieName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void redirectToLogin(HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        String currentUrl = appConfig.getWebEntrypoint().replaceAll("/+$", "")
                + request.getRequestURI().substring(request.getContextPath().length());
        String queryString = request.getQueryString();
        if (queryString != null) {
            currentUrl += "?" + queryString;
        }
        String loginUrl = ssoService.getLoginUrl(currentUrl);
        response.sendRedirect(loginUrl);
    }
}
