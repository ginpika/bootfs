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
import java.io.Writer;
import java.nio.charset.Charset;
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
            // The container cannot finish a writer created by this wrapper. Do this only
            // after normal synchronous completion so errors and async dispatch retain control.
            if (!req.isAsyncStarted()) wrapped.finishResponse();
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
                        status, bytes, duration, req.getRemoteAddr());
            } catch (Exception rec) {
                log.debug("记录请求统计失败: {}", rec.getMessage());
            }
        }
    }

    private static class CountingResponseWrapper extends HttpServletResponseWrapper {
        private CountingServletOutputStream countingOut;
        private PrintWriter writer;
        private ResettableWriter encodedWriter;
        private boolean responseReplaced;

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
                Charset charset;
                try { charset = Charset.forName(encoding); }
                catch (IllegalArgumentException invalid) { charset = Charset.defaultCharset(); }
                encodedWriter = new ResettableWriter(getOutputStream(), charset);
                writer = new PrintWriter(encodedWriter);
            }
            return writer;
        }

        void finishResponse() {
            if (writer != null && !responseReplaced) writer.flush();
        }

        @Override public void flushBuffer() throws IOException {
            if (writer != null && !responseReplaced) writer.flush();
            super.flushBuffer();
        }

        @Override public void resetBuffer() {
            super.resetBuffer();
            if (encodedWriter != null) encodedWriter.reset();
            if (countingOut != null) countingOut.bytesWritten.set(0);
        }

        @Override public void reset() {
            super.reset();
            writer = null;
            encodedWriter = null;
            countingOut = null;
            responseReplaced = false;
        }

        @Override public void sendError(int status) throws IOException {
            super.sendError(status);
            responseReplaced = true;
        }

        @Override public void sendError(int status, String message) throws IOException {
            super.sendError(status, message);
            responseReplaced = true;
        }

        @Override public void sendRedirect(String location) throws IOException {
            super.sendRedirect(location);
            responseReplaced = true;
        }

        long getBytesWritten() {
            return countingOut != null ? countingOut.getBytesWritten() : 0L;
        }
    }

    /** Keeps an acquired PrintWriter usable when resetBuffer discards pending characters. */
    private static class ResettableWriter extends Writer {
        private final ServletOutputStream output;
        private final Charset charset;
        private OutputStreamWriter delegate;

        ResettableWriter(ServletOutputStream output, Charset charset) {
            this.output = output;
            this.charset = charset;
            reset();
        }

        void reset() { delegate = new OutputStreamWriter(output, charset); }
        @Override public void write(char[] chars, int offset, int length) throws IOException { delegate.write(chars, offset, length); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
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
