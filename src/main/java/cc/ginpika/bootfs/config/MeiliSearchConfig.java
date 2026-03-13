package cc.ginpika.bootfs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "meili-search")
@Data
public class MeiliSearchConfig {
    private String masterKey;
    private String url;
    private String webUi;
}
