package cc.ginpika.bootfs.s3;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 拦截 /s3/* 的 SigV4 鉴权 Filter。失败写 S3 Error XML。
 */
@Component
@ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3AuthFilter implements Filter {
    private final S3SigV4Verifier verifier;
    private final S3XmlWriter xmlWriter;

    @Override
    public void doFilter(ServletRequest rq, ServletResponse rs, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) rq;
        HttpServletResponse resp = (HttpServletResponse) rs;

        S3ErrorCode err = verifier.verify(req);
        if (err != null) {
            resp.setStatus(err.getHttpStatus());
            resp.setContentType("application/xml");
            resp.setCharacterEncoding("UTF-8");
            String requestId = Long.toHexString(System.nanoTime());
            resp.getWriter().write(xmlWriter.error(err, err.getDefaultMessage(), req.getRequestURI(), requestId));
            return;
        }
        chain.doFilter(rq, rs);
    }
}
