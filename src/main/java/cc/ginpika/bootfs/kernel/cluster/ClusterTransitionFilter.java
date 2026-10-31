package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

/** A donor never serves authority from its old standalone history after a merge has started. */
@Component
public class ClusterTransitionFilter extends OncePerRequestFilter {
    private final MetadataStore metadata;
    public ClusterTransitionFilter(MetadataStore metadata) { this.metadata = metadata; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (metadata instanceof DynamicMetadataStore && !((DynamicMetadataStore)metadata).available() && !path.startsWith("/api/cluster/") && !path.equals("/cluster.html") && !path.equals("/cluster.js")) {
            response.setStatus(503); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Cluster transition is pending; node is fenced\"}"); return;
        }
        chain.doFilter(request,response);
    }
}
