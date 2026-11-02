package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.dto.SsoLoginResponse;
import cc.ginpika.bootfs.dto.SsoSession;
import cc.ginpika.bootfs.sso.SsoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
public class SsoService {
    
    private volatile WebClient webClient;
    private volatile String clientUrl;
    private final SsoConfig ssoConfig;
    
    public SsoService(WebClient ssoWebClient, SsoConfig ssoConfig) {
        this.webClient = ssoWebClient;
        this.ssoConfig = ssoConfig;
        this.clientUrl = ssoConfig.getServerUrl();
    }
    
    private synchronized WebClient client() {
        String url = ssoConfig.getServerUrl();
        if (!java.util.Objects.equals(url, clientUrl)) {
            webClient = WebClient.builder().baseUrl(url).build();
            clientUrl = url;
        }
        return webClient;
    }

    @SuppressWarnings("null")
    public Mono<SsoSession> checkSession(String token) {
        return client().get()
                .uri("/api/sso/session")
                .cookie(ssoConfig.getCookieName(), token)
                .retrieve()
                .bodyToMono(SsoSession.class);
    }
    
    @SuppressWarnings("null")
    public Mono<SsoLoginResponse> login(String username, String password) {
        return client().post()
                .uri("/api/sso/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .retrieve()
                .bodyToMono(SsoLoginResponse.class);
    }
    
    @SuppressWarnings("null")
    public Mono<Void> logout(String token) {
        return client().post()
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
