package cc.ginpika.bootfs.s3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * S3 兼容层配置。s3.enabled=false 时整层不加载。
 */
@Configuration
@ConfigurationProperties(prefix = "s3")
@ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
@Data
public class S3Config {

    private boolean enabled = false;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private String bucket = "bootfs";

    @Bean
    public FilterRegistrationBean<S3AuthFilter> s3AuthFilterRegistration(S3AuthFilter s3AuthFilter) {
        FilterRegistrationBean<S3AuthFilter> reg = new FilterRegistrationBean<>(s3AuthFilter);
        reg.addUrlPatterns("/s3/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("s3AuthFilter");
        return reg;
    }
}
