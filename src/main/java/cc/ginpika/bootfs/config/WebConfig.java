package cc.ginpika.bootfs.config;

import cc.ginpika.bootfs.sso.SsoInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final SsoInterceptor sso;
    public WebConfig(SsoInterceptor sso) { this.sso=sso; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        // New controller mappings must inherit authentication without updating a URL allowlist.
        registry.addInterceptor(sso).addPathPatterns("/**");
    }
}
