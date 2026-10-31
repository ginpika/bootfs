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

@Slf4j
@Component
public class SsoInterceptor implements HandlerInterceptor {
    @Autowired
    private cc.ginpika.bootfs.kernel.cluster.ClusterCredentials credentials;
    @Autowired
    private cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata;

    @Autowired
    private SsoService ssoService;

    public SsoInterceptor(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        // HLS playlists and segments remain shareable; every mutation requires authentication.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String supplied = request.getHeader("X-BootFS-Cluster-Token");
        boolean bootstrapPath = path.equals("/api/cluster/status") || path.equals("/api/cluster/ready")
                || path.equals("/api/cluster/join") || path.equals("/api/cluster/connect");
        String peer = metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic ? dynamic.actor(supplied,bootstrapPath) : null;
        boolean peerPath = bootstrapPath || path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure")
                || path.equals("/api/cluster/remove") || path.startsWith("/api/cluster/data/")
                || path.equals("/t") || path.equals("/t/prepare") || path.equals("/t/commit")
                || path.startsWith("/api/file/") || path.startsWith("/publicAccess/")
                || path.startsWith("/api/album/") || path.startsWith("/file/") || path.startsWith("/hls/")
                || path.equals("/removeFileByUuid") || path.equals("/api/dashboard/node-stats");
        if (peer != null && peerPath && ((cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore)metadata).verifyPeerRequest(request,supplied,bootstrapPath)) return true;
        if (("/t".equals(path) || "/t/prepare".equals(path) || path.startsWith("/api/cluster/")) && credentials.accepts(supplied)) {
            return true;
        }
        // Bootstrap is authorized by its short-lived code, not an existing node credential or SSO session.
        if ("POST".equals(request.getMethod()) && "/api/cluster/pairing/redeem".equals(path)) return true;
        if ("POST".equals(request.getMethod()) && (path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure"))
                && metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic && dynamic.verifyRevokedRequest(request,supplied)) {
            response.setStatus(410); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Membership authorization was revoked\"}"); return false;
        }
        boolean machine = path.equals("/api/cluster/join") || path.equals("/api/cluster/membership") || path.equals("/api/cluster/departure") || path.startsWith("/api/cluster/data/");
        if (machine || "/api/cluster/pairing/adopt".equals(path) || supplied != null) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Pairing authorization required\"}"); return false;
        }
        if (!ssoService.isEnabled()) return true;
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        boolean publicTvRead = path.equals("/tv") || path.equals("/tv/") || path.equals("/tv/status")
                || path.equals("/tv/playlist.m3u8") || path.startsWith("/tv/ts/");
        if (read && (path.startsWith("/hls/") || publicTvRead)) {
            return true;
        }

        String token = getTokenFromCookie(request);

        if (token == null || token.isEmpty()) {
            redirectToLogin(request, response);
            return false;
        }

        try {
            SsoSession session = ssoService.checkSession(token).block();

            if (session != null && session.isAuthenticated()) {
                SsoUser user = session.getUser();
                request.setAttribute("ssoUser", user);
                request.setAttribute("ssoToken", token);
                log.debug("SSO 验证成功：user={}, role={}", user.getUsername(), user.getRole());
                return true;
            }
        } catch (Exception e) {
            log.warn("SSO token 验证失败：{}", e.getMessage());
        }

        redirectToLogin(request, response);
        return false;
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
        String currentUrl = request.getRequestURL().toString();
        String queryString = request.getQueryString();
        if (queryString != null) {
            currentUrl += "?" + queryString;
        }
        String loginUrl = ssoService.getLoginUrl(currentUrl);
        log.debug("重定向到 SSO 登录页：{}", loginUrl);
        response.sendRedirect(loginUrl);
    }
}
