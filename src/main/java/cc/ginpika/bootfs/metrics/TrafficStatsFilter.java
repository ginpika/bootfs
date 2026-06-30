package cc.ginpika.bootfs.metrics;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class TrafficStatsFilter implements Filter {

    private final RequestStatsService requestStatsService;

    public TrafficStatsFilter(RequestStatsService requestStatsService) {
        this.requestStatsService = requestStatsService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        long start = System.currentTimeMillis();
        CountingResponseWrapper wrapped = new CountingResponseWrapper(resp);

        int status = 200;
        try {
            chain.doFilter(req, wrapped);
        } catch (IOException | ServletException e) {
            status = 500;
            throw e;
        } finally {
            try {
                if (wrapped.getStatus() > 0) {
                    status = wrapped.getStatus();
                }
                long bytes = wrapped.getBytesWritten();
                long duration = System.currentTimeMillis() - start;
                RequestCategory cat = RequestCategory.from(req.getRequestURI());
                requestStatsService.record(cat, req.getMethod(), req.getRequestURI(),
                        status, bytes, duration);
            } catch (Exception rec) {
                log.debug("记录请求统计失败: {}", rec.getMessage());
            }
        }
    }

    private static class CountingResponseWrapper extends HttpServletResponseWrapper {
        private CountingServletOutputStream countingOut;
        private PrintWriter writer;

        CountingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (countingOut == null) {
                countingOut = new CountingServletOutputStream(getResponse().getOutputStream());
            }
            return countingOut;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                if (encoding == null || encoding.isEmpty()) {
                    encoding = "UTF-8";
                }
                try {
                    writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), encoding));
                } catch (UnsupportedEncodingException e) {
                    writer = new PrintWriter(new OutputStreamWriter(getOutputStream()));
                }
            }
            return writer;
        }

        long getBytesWritten() {
            return countingOut != null ? countingOut.getBytesWritten() : 0L;
        }
    }

    private static class CountingServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final AtomicLong bytesWritten = new AtomicLong();

        CountingServletOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytesWritten.addAndGet(len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        long getBytesWritten() {
            return bytesWritten.get();
        }
    }
}
