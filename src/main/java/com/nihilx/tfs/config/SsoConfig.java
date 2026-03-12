package com.nihilx.tfs.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Data
@Configuration
public class SsoConfig {
    
    @Value("${sso.server-url:http://localhost:3001}")
    private String serverUrl;
    
    @Value("${sso.info-url:http://localhost:3000/sso/info}")
    private String infoUrl;
    
    @Value("${sso.cookie-name:sso_token}")
    private String cookieName;
    
    @Value("${sso.enabled:true}")
    private boolean enabled;
    
    @Bean
    public WebClient ssoWebClient() {
        return WebClient.builder()
                .baseUrl(serverUrl)
                .build();
    }
    
    public String getLoginUrl(String redirectUrl) {
        return serverUrl + "/sso/login?redirect=" + 
                java.net.URLEncoder.encode(redirectUrl, java.nio.charset.StandardCharsets.UTF_8);
    }
}
