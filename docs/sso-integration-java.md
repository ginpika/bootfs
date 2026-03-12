# SelfHostMail SSO 集成指南 (Java)

本文档指导如何将 SelfHostMail 作为 SSO 服务端，接入 Java 客户端应用。

## 架构概述

```
┌─────────────────────┐                         ┌─────────────────────┐
│     SSO Server      │                         │     SSO Client      │
│     mail.a.com      │                         │     app.a.com       │
├─────────────────────┤                         ├─────────────────────┤
│ /api/sso/session    │◄──── Cookie ───────────►│   Check Session     │
│ /api/sso/login      │◄──── Redirect ─────────►│   Redirect Login    │
│ /api/sso/logout     │◄──── Cookie ───────────►│   User Logout       │
│ /sso/login          │◄──── User Login ───────►│   Login Page        │
└─────────────────────┘                         └─────────────────────┘
```

## 前置条件

1. 所有应用必须在同一主域名下（如 `*.a.com`）
2. SSO 服务端已部署并可访问
3. Java 11+ 环境

## 环境配置

### SSO 服务端配置

```env
# .env
SSO_COOKIE_DOMAIN=.a.com
SSO_COOKIE_NAME=sso_token
SSO_COOKIE_PATH=/
SSO_COOKIE_SECURE=true
SSO_COOKIE_SAMESITE=lax
SESSION_TTL=604800
```

## API 接口

### 1. 验证登录态

```http
GET /api/sso/session HTTP/1.1
Host: mail.a.com
Cookie: sso_token=<token>
```

**响应：**
```json
{
  "authenticated": true,
  "user": {
    "id": "clxxxx",
    "username": "user",
    "email": "user@a.com",
    "role": "user"
  }
}
```

### 2. 登录

```http
POST /api/sso/login HTTP/1.1
Host: mail.a.com
Content-Type: application/json

{
  "username": "user",
  "password": "password"
}
```

### 3. 登出

```http
POST /api/sso/logout HTTP/1.1
Host: mail.a.com
Cookie: sso_token=<token>
```

## Java 集成示例

### Spring Boot 配置

#### 1. 添加依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

#### 2. SSO 配置类

```java
// SsoConfig.java
@Configuration
public class SsoConfig {
    
    @Value("${sso.server-url}")
    private String ssoServerUrl;
    
    @Bean
    public WebClient ssoWebClient() {
        return WebClient.builder()
            .baseUrl(ssoServerUrl)
            .build();
    }
    
    public String getServerUrl() {
        return ssoServerUrl;
    }
}
```

#### 3. SSO 服务类

```java
// SsoService.java
@Service
public class SsoService {
    
    private final WebClient webClient;
    private final SsoConfig ssoConfig;
    
    public SsoService(WebClient webClient, SsoConfig ssoConfig) {
        this.webClient = webClient;
        this.ssoConfig = ssoConfig;
    }
    
    public Mono<SsoSession> checkSession(String token) {
        return webClient.get()
            .uri("/api/sso/session")
            .cookie("sso_token", token)
            .retrieve()
            .bodyToMono(SsoSession.class);
    }
    
    public Mono<SsoLoginResponse> login(String username, String password) {
        return webClient.post()
            .uri("/api/sso/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", username, "password", password))
            .retrieve()
            .bodyToMono(SsoLoginResponse.class);
    }
    
    public Mono<Void> logout(String token) {
        return webClient.post()
            .uri("/api/sso/logout")
            .cookie("sso_token", token)
            .retrieve()
            .bodyToMono(Void.class);
    }
    
    public String getLoginUrl(String redirectUrl) {
        return ssoConfig.getServerUrl() + "/sso/login?redirect=" + 
            URLEncoder.encode(redirectUrl, StandardCharsets.UTF_8);
    }
}
```

#### 4. DTO 类

```java
// SsoSession.java
@Data
public class SsoSession {
    private boolean authenticated;
    private SsoUser user;
}

// SsoUser.java
@Data
public class SsoUser {
    private String id;
    private String username;
    private String email;
    private String role;
}

// SsoLoginResponse.java
@Data
public class SsoLoginResponse {
    private boolean success;
    private SsoUser user;
}
```

#### 5. 拦截器（认证检查）

```java
// SsoInterceptor.java
@Component
public class SsoInterceptor implements HandlerInterceptor {
    
    private final SsoService ssoService;
    
    public SsoInterceptor(SsoService ssoService) {
        this.ssoService = ssoService;
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) throws Exception {
        
        String token = getTokenFromCookie(request);
        
        if (token == null) {
            redirectToLogin(request, response);
            return false;
        }
        
        try {
            SsoSession session = ssoService.checkSession(token).block();
            
            if (session != null && session.isAuthenticated()) {
                request.setAttribute("ssoUser", session.getUser());
                return true;
            }
        } catch (Exception e) {
            // Token invalid or expired
        }
        
        redirectToLogin(request, response);
        return false;
    }
    
    private String getTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("sso_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
    
    private void redirectToLogin(HttpServletRequest request, 
                                 HttpServletResponse response) throws IOException {
        String currentUrl = request.getRequestURL().toString();
        String loginUrl = ssoService.getLoginUrl(currentUrl);
        response.sendRedirect(loginUrl);
    }
}
```

#### 6. 注册拦截器

```java
// WebConfig.java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Autowired
    private SsoInterceptor ssoInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ssoInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/public/**", "/error");
    }
}
```

#### 7. 控制器示例

```java
// UserController.java
@RestController
@RequestMapping("/api")
public class UserController {
    
    @GetMapping("/me")
    public SsoUser getCurrentUser(HttpServletRequest request) {
        return (SsoUser) request.getAttribute("ssoUser");
    }
    
    @PostMapping("/logout")
    public void logout(HttpServletRequest request, 
                      HttpServletResponse response) throws IOException {
        String token = getTokenFromCookie(request);
        if (token != null) {
            ssoService.logout(token).block();
        }
        
        Cookie cookie = new Cookie("sso_token", null);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        
        response.sendRedirect(ssoService.getLoginUrl("/"));
    }
    
    private String getTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("sso_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
```

## application.yml 配置

```yaml
sso:
  server-url: https://mail.a.com

server:
  port: 8080
```

## 注意事项

### 1. Cookie 跨域

确保所有应用在同一主域名下，Cookie 的 `domain` 属性设置为 `.a.com`。

### 2. HTTPS 要求

生产环境必须使用 HTTPS。

### 3. 会话同步

```java
@Scheduled(fixedRate = 300000) // 5 minutes
public void syncSession() {
    String token = getCurrentToken();
    if (token != null) {
        ssoService.checkSession(token)
            .subscribe(session -> {
                if (!session.isAuthenticated()) {
                    // Force logout
                }
            });
    }
}
```

## 故障排查

| 问题 | 解决方案 |
|------|----------|
| Cookie 未设置 | 检查 `SSO_COOKIE_DOMAIN` 配置 |
| 跨域请求失败 | 确认 CORS 配置正确 |
| 会话丢失 | 检查会话是否过期 |

## 安全建议

1. 生产环境必须使用 HTTPS
2. 设置合理的会话过期时间
3. 敏感操作需要二次验证
4. 记录登录日志用于审计
