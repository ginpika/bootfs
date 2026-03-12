package com.nihilx.tfs.interceptor;

import com.nihilx.tfs.dto.SsoSession;
import com.nihilx.tfs.dto.SsoUser;
import com.nihilx.tfs.service.SsoService;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class SsoInterceptor implements HandlerInterceptor {
    
    @Autowired
    private SsoService ssoService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) throws Exception {
        
        if (!ssoService.isEnabled()) {
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
