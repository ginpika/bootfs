package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.config.SsoConfig;
import cc.ginpika.bootfs.dto.SsoLoginResponse;
import cc.ginpika.bootfs.dto.SsoSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
public class SsoService {
    
    private final WebClient webClient;
    private final SsoConfig ssoConfig;
    
    public SsoService(WebClient ssoWebClient, SsoConfig ssoConfig) {
        this.webClient = ssoWebClient;
        this.ssoConfig = ssoConfig;
    }
    
    public Mono<SsoSession> checkSession(String token) {
        return webClient.get()
                .uri("/api/sso/session")
                .cookie(ssoConfig.getCookieName(), token)
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
                .cookie(ssoConfig.getCookieName(), token)
                .retrieve()
                .bodyToMono(Void.class);
    }
    
    public String getLoginUrl(String redirectUrl) {
        return ssoConfig.getLoginUrl(redirectUrl);
    }
    
    public String getCookieName() {
        return ssoConfig.getCookieName();
    }
    
    public boolean isEnabled() {
        return ssoConfig.isEnabled();
    }
}
