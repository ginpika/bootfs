package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.sso.LocalAccessPolicy;
import cc.ginpika.bootfs.service.SsoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@RestController
public class AuthController {
    
    @Autowired
    private SsoService ssoService;

    @Autowired
    private LocalAccessPolicy localAccess;
    @Autowired private cc.ginpika.bootfs.config.TfsConfig appConfig;

    public AuthController(SsoService ssoService) {
        this.ssoService = ssoService;
    }
    
    @GetMapping("/api/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        if (localAccess.authenticate(request, response)) {
            response.setStatus(200);
            return;
        }
        if (!ssoService.isEnabled()) {
            response.setStatus(200);
            return;
        }
        
        try {
            String token = getTokenFromCookie(request);
            if (token != null) {
                ssoService.logout(token).subscribe(
                        success -> log.info("SSO 登出成功"),
                        error -> log.warn("SSO 登出失败：{}", error.getMessage())
                );
            }
        } catch (Exception e) {
            log.warn("登出时清理 SSO 会话失败：{}", e.getMessage());
        }
        
        Cookie cookie = new Cookie(ssoService.getCookieName(), null);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        
        // Only configured application origins enter SSO redirects, never a supplied Host header.
        String baseUrl = appConfig.getWebEntrypoint().replaceAll("/+$", "");
        String redirectUrl = ssoService.getLoginUrl(baseUrl);
        try {
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            log.error("重定向失败", e);
        }
    }
    
    @GetMapping("/api/auth/me")
    public SsoUser getCurrentUser(HttpServletRequest request, HttpServletResponse response) {
        localAccess.authenticate(request, response);
        SsoUser user = (SsoUser) request.getAttribute("ssoUser");
        return user;
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
}
