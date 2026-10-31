package cc.ginpika.bootfs.kernel.cluster;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bind signed JSON peer commands to their exact bytes without buffering object uploads. */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+10)
public class PeerBodyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String type=request.getContentType(),signature=request.getHeader("X-BootFS-Signature");
        if (signature==null || type==null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) { chain.doFilter(request,response); return; }
        byte[] body=request.getInputStream().readNBytes(16*1024*1024+1);
        if (body.length>16*1024*1024 || !ClusterHttp.digest(body).equals(request.getHeader("X-BootFS-Digest"))) {
            response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Invalid signed request body\"}"); return;
        }
        request.setAttribute("bootfs.peerBodyVerified",Boolean.TRUE);
        HttpServletRequestWrapper replay=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous peer command"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
        };
        chain.doFilter(replay,response);
    }
}
