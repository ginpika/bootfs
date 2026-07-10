package cc.ginpika.bootfs.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cc.ginpika.bootfs.sso.SsoInterceptor;


@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private SsoInterceptor ssoInterceptor;

    public WebConfig(@NonNull SsoInterceptor ssoInterceptor) {
        this.ssoInterceptor = ssoInterceptor;
    }
    
    @Override
    @SuppressWarnings({ "null" })
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(ssoInterceptor)
                .addPathPatterns(
                        "/",
                        "/queryPageOffset",
                        "/removeFileByUuid",
                        "/removeFileBatch",
                        "/downloadFileBatch",
                        "/publicAccess/**", 
                        "/old-admin",
                        "/virtual-file",
                        "/file/*",
                        "/hls/*/segments",
                        "/hls/*/finalize",
                        "/etcd",
                        "/api/etcd/**",
                        "/meilisearch",
                        "/api/meilisearch/**",
                        "/dashboard",
                        "/api/dashboard/**"
                )
                .excludePathPatterns(
                        "/error",
                        "/public/**",
                        "/video-player",
                        "/album/**",
                        "/gallery",
                        "/setu",
                        "/setu-url",
                        "/f/**",
                        "/p/**",
                        "/t",
                        "/hls/**",
                        "/g/**",
                        "/static/**",
                        "/*.css",
                        "/*.js",
                        "/*.ico",
                        "/s3/**"
                );
    }
}
