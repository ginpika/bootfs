package cc.ginpika.bootfs.s3;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class S3HttpConfiguration {

    @Bean
    public FilterRegistrationBean<S3AuthFilter> s3AuthFilterRegistration(S3AuthFilter s3AuthFilter) {
        FilterRegistrationBean<S3AuthFilter> reg = new FilterRegistrationBean<>(s3AuthFilter);
        reg.addUrlPatterns("/s3", "/s3/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("s3AuthFilter");
        return reg;
    }
}
