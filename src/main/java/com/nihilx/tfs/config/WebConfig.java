package com.nihilx.tfs.config;

import com.nihilx.tfs.interceptor.SsoInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Autowired
    private SsoInterceptor ssoInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
                        "/hls/*/finalize"
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
                        "/*.ico"
                );
    }
}
