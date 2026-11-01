package cc.ginpika.bootfs.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClashLatencyServiceTest {
    @Test void duplicateEndpointsShareOneProbeAndFreshResultsAreCached() {
        AtomicInteger calls = new AtomicInteger();
        try (ManagedService service = service((host, port, timeout) -> {
            calls.incrementAndGet();
            return new ClashLatencyService.ProbeResult("ok", 23L, System.currentTimeMillis());
        }, 2)) {
            var details = details(List.of(endpoint("one", "edge.example", 443), endpoint("two", "edge.example", 443)));
            var first = service.value.measure(details, 0, 16);
            assertEquals(1, calls.get()); assertFalse(first.cached());
            assertEquals(2, first.nodes().size());
            assertEquals(23L, first.nodes().get(0).latencyMs());
            assertEquals(23L, first.nodes().get(1).latencyMs());
            assertEquals("one", first.nodes().get(0).id()); assertEquals("two", first.nodes().get(1).id());
            var second = service.value.measure(details, 0, 16);
            assertTrue(second.cached()); assertEquals(1, calls.get());
            assertEquals("tcp", second.measurement()); assertEquals("server", second.vantagePoint());
        }
    }

    @Test void batchesAreBoundedAndPreserveNodeOrderDespiteParallelCompletion() {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        try (ManagedService service = service((host, port, timeout) -> {
            int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
            try { Thread.sleep(host.equals("slow.example") ? 80 : 15); }
            finally { active.decrementAndGet(); }
            return new ClashLatencyService.ProbeResult("ok", (long)port, System.currentTimeMillis());
        }, 2)) {
            var details = details(List.of(endpoint("skip", "unused.example", 10), endpoint("first", "slow.example", 11),
                    endpoint("second", "fast.example", 12), endpoint("third", "third.example", 13)));
            var batch = service.value.measure(details, 1, 2);
            assertEquals(List.of("first", "second"), batch.nodes().stream().map(ClashLatencyService.NodeLatency::id).toList());
            assertEquals(List.of(11L, 12L), batch.nodes().stream().map(ClashLatencyService.NodeLatency::latencyMs).toList());
            assertEquals(2, peak.get()); assertEquals(3, batch.nextOffset()); assertEquals(4, batch.total());
            assertThrows(ResponseStatusException.class, () -> service.value.measure(details, -1, 16));
            assertThrows(ResponseStatusException.class, () -> service.value.measure(details, 0, 17));
            assertThrows(ResponseStatusException.class, () -> service.value.measure(details, 9, 1));
        }
    }

    @Test void unresolvedAndFailedEndpointsHaveNoInventedLatency() {
        try (ManagedService service = service((host, port, timeout) -> {
            if (host.equals("missing.example")) return ClashLatencyService.ProbeResult.failure("unresolved");
            throw new IllegalStateException("connection unavailable");
        }, 2)) {
            var batch = service.value.measure(details(List.of(endpoint("dns", "missing.example", 443), endpoint("failed", "failed.example", 443))), 0, 16);
            assertEquals(List.of("unresolved", "unreachable"), batch.nodes().stream().map(ClashLatencyService.NodeLatency::status).toList());
            assertTrue(batch.nodes().stream().allMatch(node -> node.latencyMs() == null));
        }
    }

    @Test void blockedDnsCannotHoldTheHttpRequestBeyondTheOverallBudget() {
        CountDownLatch release = new CountDownLatch(1);
        try (ManagedService service = service((host, port, timeout) -> {
            // DNS libraries may ignore interruption; the finite worker pool still bounds resources.
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return ClashLatencyService.ProbeResult.failure("unresolved");
        }, 1)) {
            long started = System.nanoTime();
            var result = service.value.measure(details(List.of(endpoint("blocked", "blocked.example", 443))), 0, 1);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4500, "Request must finish before the remote proxy's five-second timeout");
            assertEquals("timeout", result.nodes().get(0).status()); assertNull(result.nodes().get(0).latencyMs());
        } finally { release.countDown(); }
    }

    private ClashFileService.ClashEndpoint endpoint(String id, String host, int port) {
        return new ClashFileService.ClashEndpoint(id, id, "vless", host, port);
    }
    private ClashFileService.ClashDetails details(List<ClashFileService.ClashEndpoint> nodes) {
        return new ClashFileService.ClashDetails("resource", "version", nodes, List.of(), "tcp", "server");
    }
    private ManagedService service(ClashLatencyService.EndpointProbe probe, int concurrency) {
        return new ManagedService(new ClashLatencyService(probe, concurrency, 1200, 5000));
    }
    private record ManagedService(ClashLatencyService value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
