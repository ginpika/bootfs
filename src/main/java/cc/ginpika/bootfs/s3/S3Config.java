package cc.ginpika.bootfs.s3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * S3 兼容层始终加载；请求入口按节点数据库设置启停。
 */
@Configuration
@ConfigurationProperties(prefix = "s3")
@Data
@lombok.ToString(exclude={"accessKey","secretKey","settings"})
public class S3Config {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cc.ginpika.bootfs.settings.NodeSettings settings;
    public boolean isEnabled() { return settings == null ? enabled : settings.current().bool("s3Enabled"); }
    public String getAccessKey() { return settings == null ? accessKey : settings.current().string("s3AccessKey"); }
    public String getSecretKey() { return settings == null ? secretKey : settings.current().string("s3SecretKey"); }
    public String getBucket() { return settings == null ? bucket : settings.current().string("s3Bucket"); }
    public String[] credentials() {
        var snapshot = settings == null ? null : settings.current();
        return snapshot == null ? new String[]{accessKey,secretKey} : new String[]{snapshot.string("s3AccessKey"),snapshot.string("s3SecretKey")};
    }
    private boolean enabled = false;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private String bucket = "bootfs";

    @Bean
    public FilterRegistrationBean<S3AuthFilter> s3AuthFilterRegistration(S3AuthFilter s3AuthFilter) {
        FilterRegistrationBean<S3AuthFilter> reg = new FilterRegistrationBean<>(s3AuthFilter);
        reg.addUrlPatterns("/s3", "/s3/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("s3AuthFilter");
        return reg;
    }
}
