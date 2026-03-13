package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.service.SsoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@RestController
@CrossOrigin
public class AuthController {
    
    @Autowired
    private SsoService ssoService;
    
    @GetMapping("/api/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
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
        
        // 获取当前请求的完整 URL 作为重定向地址
        String requestUrl = request.getRequestURL().toString();
        // 提取基础路径（去掉 /api/logout 部分）
        String baseUrl = requestUrl.replace("/api/logout", "");
        // 使用基础路径作为重定向地址，确保登录后返回原页面
        String redirectUrl = ssoService.getLoginUrl(baseUrl);
        log.debug("重定向到 SSO 登录页：{}", redirectUrl);
        try {
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            log.error("重定向失败", e);
        }
    }
    
    @GetMapping("/api/auth/me")
    public SsoUser getCurrentUser(HttpServletRequest request) {
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
