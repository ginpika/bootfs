package cc.ginpika.bootfs.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.context.annotation.*;
import javax.servlet.http.HttpServletRequest;

@Configuration
public class HttpBodyConfiguration {
    /** S3 payload MIME types describe object bytes; they are never servlet form parameters. */
    @Bean
    @ConditionalOnProperty(prefix="spring.mvc.formcontent.filter",name="enabled",matchIfMissing=true)
    public OrderedFormContentFilter formContentFilter() {
        return new OrderedFormContentFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                String path=request.getRequestURI().substring(request.getContextPath().length());
                return path.equals("/s3") || path.startsWith("/s3/");
            }
        };
    }
}
