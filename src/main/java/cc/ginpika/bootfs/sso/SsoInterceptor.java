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
    @org.springframework.beans.factory.annotation.Value("${bootfs.cluster.token:}")
    private String clusterToken = "";

    @Autowired
    private SsoService ssoService;

    public SsoInterceptor(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        if (!ssoService.isEnabled()) {
            return true;
        }

        // HLS playlists and segments remain shareable; every mutation requires authentication.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String supplied = request.getHeader("X-BootFS-Cluster-Token");
        if (("/t".equals(path) || "/t/prepare".equals(path) || path.startsWith("/api/cluster/")) && !clusterToken.isBlank() && supplied != null &&
                java.security.MessageDigest.isEqual(clusterToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return true;
        }
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
