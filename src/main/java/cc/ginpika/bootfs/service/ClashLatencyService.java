package cc.ginpika.bootfs.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded TCP handshakes to endpoints contained in an authenticated Clash resource. */
@Service
public class ClashLatencyService {
    public static final int MAX_BATCH_SIZE = 16;
    private static final long REQUEST_BUDGET_MS = 3000;
    private final ThreadPoolExecutor workers;
    private final ConcurrentMap<Endpoint, ProbeJob> running = new ConcurrentHashMap<>();
    private final Map<Endpoint, ProbeResult> cache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Endpoint, ProbeResult> eldest) { return size() > 2048; }
    });
    private final EndpointProbe probe;
    private java.util.function.BooleanSupplier privatePolicy = () -> true;
    private boolean realProbe;
    private final int timeoutMillis;
    private final long cacheMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public ClashLatencyService(cc.ginpika.bootfs.settings.NodeSettings settings) {
        this(null, 8, 1200, 5000);
        privatePolicy = () -> settings.current().bool("allowPrivateProbes"); realProbe = true;
    }
    public ClashLatencyService(boolean allowPrivate) {
        this(null, 8, 1200, 5000);
        privatePolicy = () -> allowPrivate; realProbe = true;
    }

    ClashLatencyService(EndpointProbe probe, int concurrency, int timeoutMillis, long cacheMillis) {
        this.probe = probe;
        this.timeoutMillis = timeoutMillis;
        this.cacheMillis = cacheMillis;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    Thread thread = new Thread(task, "clash-latency-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public LatencyBatch measure(ClashFileService.ClashDetails details, int offset, int limit) {
        if (offset < 0 || offset > details.nodes().size() || limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "延迟检测分页无效，每批最多 16 个节点");
        }
        boolean allowPrivate = privatePolicy.getAsBoolean();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_BUDGET_MS);
        List<ClashFileService.ClashEndpoint> selected = details.nodes().subList(offset, Math.min(details.nodes().size(), offset + limit));
        Map<Endpoint, ProbeResult> results = new LinkedHashMap<>();
        Map<Endpoint, ProbeJob> pending = new LinkedHashMap<>();
        boolean allCached = true;
        for (ClashFileService.ClashEndpoint node : selected) {
            Endpoint endpoint = new Endpoint(node.server(), node.port(), allowPrivate);
            if (results.containsKey(endpoint) || pending.containsKey(endpoint)) continue;
            ProbeResult prior = cache.get(endpoint);
            if (prior != null && System.currentTimeMillis() - prior.checkedAt() < cacheMillis) {
                results.put(endpoint, prior);
                continue;
            }
            allCached = false;
            ProbeJob existing = running.get(endpoint);
            if (existing == null) {
                ProbeJob created = new ProbeJob(endpoint);
                existing = running.putIfAbsent(endpoint, created);
                if (existing == null) {
                    existing = created;
                    try { workers.execute(created); }
                    catch (RejectedExecutionException overloaded) {
                        running.remove(endpoint, created);
                        results.put(endpoint, ProbeResult.failure("busy"));
                        continue;
                    }
                }
            }
            pending.put(endpoint, existing);
        }
        for (Map.Entry<Endpoint, ProbeJob> entry : pending.entrySet()) {
            ProbeJob job = entry.getValue();
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                ProbeResult completed = job.get(remaining, TimeUnit.NANOSECONDS);
                cache.put(entry.getKey(), completed);
                results.put(entry.getKey(), completed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                results.put(entry.getKey(), ProbeResult.failure("busy"));
            } catch (TimeoutException timeout) {
                job.cancel(true);
                workers.remove(job);
                results.put(entry.getKey(), ProbeResult.failure("timeout"));
            } catch (CancellationException cancelled) {
                results.put(entry.getKey(), ProbeResult.failure("timeout"));
            } catch (ExecutionException failed) {
                results.put(entry.getKey(), ProbeResult.failure("unreachable"));
            }
        }
        List<NodeLatency> nodes = new ArrayList<>();
        for (ClashFileService.ClashEndpoint node : selected) {
            ProbeResult result = results.get(new Endpoint(node.server(), node.port(), allowPrivate));
            nodes.add(new NodeLatency(node.id(), node.name(), node.type(), node.server(), node.port(),
                    result.latencyMs(), result.status(), result.checkedAt()));
        }
        return new LatencyBatch(details.uuid(), details.version(), System.currentTimeMillis(), List.copyOf(nodes),
                allCached, offset, offset + selected.size(), details.nodes().size(), "tcp", "server");
    }

    private final class ProbeJob extends FutureTask<ProbeResult> {
        private final Endpoint endpoint;
        ProbeJob(Endpoint endpoint) {
            super(() -> realProbe ? tcpProbe(endpoint.server(), endpoint.port(), timeoutMillis, endpoint.allowPrivate())
                    : probe.probe(endpoint.server(), endpoint.port(), timeoutMillis));
            this.endpoint = endpoint;
        }
        @Override protected void done() {
            try {
                if (!isCancelled()) cache.put(endpoint, get());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | CancellationException ignored) { }
            finally { running.remove(endpoint, this); }
        }
    }

    private static ProbeResult tcpProbe(String server, int port, int timeoutMillis, boolean allowPrivate) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(server); }
        catch (UnknownHostException unresolved) { return ProbeResult.failure("unresolved"); }
        catch (RuntimeException failed) { return ProbeResult.failure("unreachable"); }
        // Connect to these exact validated addresses, never resolve the hostname a second time.
        if (!allowPrivate && java.util.Arrays.stream(addresses).anyMatch(address -> !publicAddress(address))) return ProbeResult.failure("blocked");
        long start = System.nanoTime();
        for (int index = 0; index < Math.min(addresses.length, 4); index++) {
            if (Thread.currentThread().isInterrupted()) return ProbeResult.failure("timeout");
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) return ProbeResult.failure("timeout");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(addresses[index], port), (int)Math.max(1, remaining));
                return new ProbeResult("ok", Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)), System.currentTimeMillis());
            } catch (SocketTimeoutException timeout) { return ProbeResult.failure("timeout"); }
            catch (IOException | RuntimeException unavailable) { /* Try the next resolved address within the same deadline. */ }
        }
        return ProbeResult.failure("unreachable");
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 16) return (a & 0xe0) == 0x20; // Global unicast only, excludes ULA and IPv4 translation.
        return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 192 && b == 0) && !(a == 198 && (b == 18 || b == 19));
    }

    @PreDestroy public void close() { workers.shutdownNow(); }

    record Endpoint(String server, int port, boolean allowPrivate) {}
    @FunctionalInterface interface EndpointProbe { ProbeResult probe(String server, int port, int timeoutMillis) throws Exception; }
    record ProbeResult(String status, Long latencyMs, long checkedAt) {
        static ProbeResult failure(String status) { return new ProbeResult(status, null, System.currentTimeMillis()); }
    }
    public record NodeLatency(String id, String name, String type, String server, int port,
                              Long latencyMs, String status, long checkedAt) {}
    public record LatencyBatch(String uuid, String version, long checkedAt, List<NodeLatency> nodes, boolean cached,
                               int offset, int nextOffset, int total, String measurement, String vantagePoint) {}
}
