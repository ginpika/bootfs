package cc.ginpika.bootfs.metrics;

public class RequestLogEntry {
    private final String time;
    private final String method;
    private final String uri;
    private final int status;
    private final long bytes;
    private final long durationMs;
    private final String category;
    private final String clientIp;

    public RequestLogEntry(String time, String method, String uri, int status,
                           long bytes, long durationMs, String category) {
        this(time, method, uri, status, bytes, durationMs, category, null);
    }

    public RequestLogEntry(String time, String method, String uri, int status,
                           long bytes, long durationMs, String category, String clientIp) {
        this.time = time;
        this.method = method;
        this.uri = uri;
        this.status = status;
        this.bytes = bytes;
        this.durationMs = durationMs;
        this.category = category;
        this.clientIp = clientIp;
    }

    public String getTime() { return time; }
    public String getMethod() { return method; }
    public String getUri() { return uri; }
    public int getStatus() { return status; }
    public long getBytes() { return bytes; }
    public long getDurationMs() { return durationMs; }
    public String getCategory() { return category; }
    public String getClientIp() { return clientIp; }
}
