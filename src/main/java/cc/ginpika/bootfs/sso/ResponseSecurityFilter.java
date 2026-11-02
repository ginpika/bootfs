package cc.ginpika.bootfs.sso;

import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 5)
public class ResponseSecurityFilter extends OncePerRequestFilter {
    public static final String PRIVATE = ResponseSecurityFilter.class.getName() + ".private";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        HttpServletResponseWrapper guarded = new HttpServletResponseWrapper(response) {
            @Override public void setHeader(String name, String value) {
                super.setHeader(name, cache(name, value));
            }
            @Override public void addHeader(String name, String value) {
                if (privateCache(name)) super.setHeader(name, "no-store");
                else super.addHeader(name, value);
            }
            private boolean privateCache(String name) {
                return "Cache-Control".equalsIgnoreCase(name) && Boolean.TRUE.equals(request.getAttribute(PRIVATE));
            }
            private String cache(String name, String value) { return privateCache(name) ? "no-store" : value; }
        };
        chain.doFilter(request, guarded);
    }
}
