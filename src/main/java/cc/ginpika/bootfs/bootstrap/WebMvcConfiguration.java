package cc.ginpika.bootfs.bootstrap;

import cc.ginpika.bootfs.sso.SsoInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {
    private final SsoInterceptor sso;
    public WebMvcConfiguration(SsoInterceptor sso) { this.sso=sso; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        // New controller mappings must inherit authentication without updating a URL allowlist.
        registry.addInterceptor(sso).addPathPatterns("/**");
    }
}
