package cc.ginpika.bootfs.config;

import cc.ginpika.bootfs.sso.SsoInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final SsoInterceptor sso;
    public WebConfig(SsoInterceptor sso) { this.sso=sso; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sso).addPathPatterns(
            "/", "/queryPageOffset", "/removeFileByUuid", "/removeFileBatch", "/downloadFileBatch",
            "/publicAccess/**", "/old-admin", "/index-old", "/f", "/comic", "/t", "/t/prepare",
            "/virtual-file", "/file/*", "/hls/**", "/api/file/**", "/api/album/**", "/api/cluster/**", "/api/tags/**",
            "/etcd", "/api/etcd/**", "/meilisearch", "/api/meilisearch/**", "/dashboard", "/api/dashboard/**",
            "/tv", "/tv/**");
    }
}
