package cc.ginpika.bootfs.sso;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Data
@Configuration
public class SsoConfig {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cc.ginpika.bootfs.settings.NodeSettings settings;
    public String getServerUrl() { return settings == null ? serverUrl : settings.current().string("ssoServerUrl"); }
    public String getInfoUrl() { return settings == null ? infoUrl : settings.current().string("ssoInfoUrl"); }
    public String getAllowedRoles() { return settings == null ? allowedRoles : settings.current().string("allowedRoles"); }
    public boolean isEnabled() { return settings == null ? enabled : settings.current().bool("ssoEnabled"); }
    public boolean isLocalBypassEnabled() { return settings == null ? localBypassEnabled : settings.current().bool("localBypassEnabled"); }
    public boolean isPublicSharingEnabled() { return settings == null ? publicSharingEnabled : settings.current().bool("publicSharingEnabled"); }
    public boolean permitsRole(String role) {
        return getAllowedRoles().isBlank() || role != null && java.util.Arrays.stream(getAllowedRoles().split(",")).map(String::trim).anyMatch(role::equals);
    }
    @Value("${sso.server-url:}")
    private String serverUrl;

    @Value("${sso.info-url:}")
    private String infoUrl;

    @Value("${sso.cookie-name:sso_token}")
    private String cookieName;

    @Value("${sso.enabled:false}")
    private boolean enabled;

    @Value("${sso.local-bypass.enabled:true}")
    private boolean localBypassEnabled;

    @Value("${sso.allowed-roles:}")
    private String allowedRoles = "";

    @Value("${bootfs.security.public-sharing.enabled:true}")
    private boolean publicSharingEnabled;

    @Bean
    public WebClient ssoWebClient() {
        return WebClient.builder()
                .baseUrl(getServerUrl() == null ? "" : getServerUrl())
                .build();
    }

    public String getLoginUrl(String redirectUrl) {
        return getServerUrl() + "/sso/login?redirect=" +
                java.net.URLEncoder.encode(redirectUrl, java.nio.charset.StandardCharsets.UTF_8);
    }
}
