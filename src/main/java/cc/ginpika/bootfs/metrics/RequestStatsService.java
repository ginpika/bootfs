package cc.ginpika.bootfs.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RequestStatsService {

    private static final int MAX_RECENT = 50;
    private static final long HOUR_MS = 3600_000L;
    private static final long WINDOW_MS = 24 * HOUR_MS;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Map<RequestCategory, AtomicLong> bytesByCategory = new EnumMap<>(RequestCategory.class);
    private final Map<RequestCategory, AtomicLong> requestsByCategory = new EnumMap<>(RequestCategory.class);
    private final AtomicLong totalBytesOut = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    private final long sinceStartMillis = System.currentTimeMillis();

    private final ConcurrentHashMap<Long, HourBucket> hourlyBuckets = new ConcurrentHashMap<>();
    private final Deque<RequestLogEntry> recent = new ArrayDeque<>();

    public RequestStatsService() {
        for (RequestCategory c : RequestCategory.values()) {
            bytesByCategory.put(c, new AtomicLong());
            requestsByCategory.put(c, new AtomicLong());
        }
    }

    public void record(RequestCategory cat, String method, String uri, int status,
                       long bytes, long durationMs) {
        totalRequests.incrementAndGet();
        totalBytesOut.addAndGet(bytes);
        requestsByCategory.get(cat).incrementAndGet();
        bytesByCategory.get(cat).addAndGet(bytes);

        long now = System.currentTimeMillis();
        long hour = now - (now % HOUR_MS);
        hourlyBuckets.computeIfAbsent(hour, HourBucket::new).record(bytes);

        String truncatedUri = uri != null && uri.length() > 120 ? uri.substring(0, 120) + "..." : uri;
        RequestLogEntry entry = new RequestLogEntry(
                TIME_FMT.format(Instant.ofEpochMilli(now)),
                method, truncatedUri, status, bytes, durationMs, cat.name());
        synchronized (recent) {
            recent.addFirst(entry);
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
    }

    public Map<String, Object> buildStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("traffic", buildTraffic());
        stats.put("requests", buildRequests());
        return stats;
    }

    private Map<String, Object> buildTraffic() {
        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("totalBytesOut", totalBytesOut.get());
        traffic.put("totalRequests", totalRequests.get());
        traffic.put("sinceStartMillis", sinceStartMillis);

        long grandTotal = totalBytesOut.get();
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (RequestCategory c : RequestCategory.values()) {
            long bytes = bytesByCategory.get(c).get();
            long reqs = requestsByCategory.get(c).get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", c.name());
            item.put("label", c.getLabel());
            item.put("bytesOut", bytes);
            item.put("requests", reqs);
            item.put("percent", grandTotal > 0 ? Math.round(bytes * 10000.0 / grandTotal) / 100.0 : 0.0);
            byCategory.add(item);
        }
        traffic.put("byCategory", byCategory);
        return traffic;
    }

    private Map<String, Object> buildRequests() {
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;
        // 淘汰过期桶
        hourlyBuckets.keySet().removeIf(h -> h < cutoff - (cutoff % HOUR_MS));

        // 构建 24 小时窗口（从最早到最近），填充缺失小时为 0
        long currentHour = now - (now % HOUR_MS);
        long startHour = currentHour - (WINDOW_MS - HOUR_MS);
        List<Map<String, Object>> hourly = new ArrayList<>();
        long total24h = 0;
        long bytes24h = 0;
        for (long h = startHour; h <= currentHour; h += HOUR_MS) {
            HourBucket bucket = hourlyBuckets.get(h);
            long count = bucket != null ? bucket.count.get() : 0;
            long bytes = bucket != null ? bucket.bytes.get() : 0;
            total24h += count;
            bytes24h += bytes;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", HOUR_FMT.format(Instant.ofEpochMilli(h)));
            item.put("count", count);
            item.put("bytes", bytes);
            hourly.add(item);
        }

        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("total24h", total24h);
        requests.put("bytes24h", bytes24h);
        requests.put("hourly", hourly);

        List<RequestLogEntry> recentCopy;
        synchronized (recent) {
            recentCopy = new ArrayList<>(recent);
        }
        requests.put("recent", recentCopy);
        return requests;
    }

    private static class HourBucket {
        final long hourStart;
        final AtomicLong count = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();

        HourBucket(long hourStart) {
            this.hourStart = hourStart;
        }

        void record(long bytesOut) {
            count.incrementAndGet();
            bytes.addAndGet(bytesOut);
        }
    }
}
