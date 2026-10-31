package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.backup.*;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicClusterTest {
    @TempDir Path temporary;
    final List<Node> nodes = new ArrayList<>();
    final String token = "test-pairing-secret";
    final java.util.concurrent.atomic.AtomicBoolean failAdmission = new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean loseMergeReply = new java.util.concurrent.atomic.AtomicBoolean();
    class Node implements AutoCloseable {
        final TfsConfig config = new TfsConfig();
        final HttpServer http;
        DynamicMetadataStore store;
        Node(String name) throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            config.setRootPath(temporary.resolve(name).toString()); config.setUniqueId(name);
            config.setWebEntrypoint("http://127.0.0.1:" + http.getAddress().getPort());
            store = new DynamicMetadataStore(config, "", token, false);
            http.createContext("/api/cluster/", exchange -> {
                try {
                    if (!token.equals(exchange.getRequestHeaders().getFirst("X-BootFS-Cluster-Token"))) { exchange.sendResponseHeaders(403, -1); return; }
                    JSONObject request = "POST".equals(exchange.getRequestMethod()) ? JSON.parseObject(exchange.getRequestBody().readAllBytes()) : null;
                    JSONObject result = new JSONObject();
                    switch (exchange.getRequestURI().getPath()) {
                        case "/api/cluster/status" -> result = store.status();
                        case "/api/cluster/ready" -> result = store.ready();
                        case "/api/cluster/merge" -> {
                            result = store.merge(request);
                            if (loseMergeReply.getAndSet(false)) throw new IOException("merge committed but reply lost");
                        }
                        case "/api/cluster/admit" -> {
                            if (failAdmission.getAndSet(false)) throw new IOException("admission unavailable before commit");
                            store.admit(request.getString("nodeId"), request.getString("raftAddress"));
                        }
                        case "/api/cluster/remove" -> store.remove(request.getString("nodeId"));
                        default -> throw new IOException("Unsupported test endpoint");
                    }
                    byte[] body = result.toJSONString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Exception failure) {
                    byte[] body = failure.toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(409, body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            http.start(); nodes.add(this);
            store.put("/cluster/node/" + name, config.getWebEntrypoint());
        }
        void resource(String id, String body, String key) throws Exception {
            Files.createDirectories(Path.of(config.getPathPrefix())); Path file = Path.of(config.getPathPrefix(), id); Files.writeString(file, body);
            JSONObject catalog = new JSONObject(); catalog.put("uuid", id); catalog.put("fileName", body + ".bin"); catalog.put("size", Files.size(file));
            JSONObject meta = new JSONObject(); meta.put("sha256", ContentHash.sha256(file)); meta.put("size", Files.size(file));
            store.writeBatch(Map.of("/nodes/" + config.getUniqueId() + "/catalog/" + id, catalog.toJSONString(),
                    "/files/" + id, config.getWebEntrypoint() + "/f/" + id, "/files/" + id + "/meta", meta.toJSONString(),
                    "/documents/full-text/" + id, catalog.toJSONString(), "/s3/bootfs/" + key, "{\"uuid\":\"" + id + "\",\"key\":\"" + key + "\"}"), Set.of());
        }
        @Override public void close() { if (store != null) { store.close(); store = null; } http.stop(0); }
    }
    @AfterEach void close() { for (Node node : nodes) node.close(); }

    @Test @Timeout(120) void independentDataMergePreservesConflictVersionsAndCanJoinLeaveAndReturn() throws Exception {
        Node a = new Node("a"), b = new Node("b");
        a.resource("same", "first", "shared"); b.resource("same", "second", "shared");
        a.store.put("custom-key", "first custom"); b.store.put("custom-key", "second custom");
        a.store.put("same", "raw key A"); b.store.put("same", "raw key B"); b.store.put("/custom/meta", "plain text");
        long previousRevision = b.store.revision();
        String oldGroup = b.store.status().getString("groupId");
        b.store.connect(a.config.getWebEntrypoint());
        assertFalse(b.store.available()); assertThrows(RuntimeException.class, () -> b.store.put("/bad", "write"));
        assertTrue(b.store.advance()); assertTrue(b.store.available());
        assertEquals(a.store.status().getString("groupId"), b.store.status().getString("groupId"));
        assertNotEquals(oldGroup, b.store.status().getString("groupId")); assertTrue(b.store.revision() > previousRevision);
        assertEquals(2, a.store.status().getJSONObject("peers").size());
        String latest = JSON.parseObject(a.store.get("/s3/bootfs/shared")).getString("uuid");
        assertNotEquals("same", latest); assertEquals("first", Files.readString(Path.of(a.config.getPathPrefix(), "same")));
        assertEquals("second", Files.readString(Path.of(b.config.getPathPrefix(), latest)));
        assertEquals("second", Files.readString(Path.of(b.config.getPathPrefix(), "same")), "Original donor bytes remain intact");
        assertEquals("second custom", a.store.get("custom-key"));
        assertEquals("raw key B", a.store.get("same")); assertEquals("plain text", a.store.get("/custom/meta"));
        assertEquals(2, ClusterMerge.versions(a.store.scan("/versions/"), "metadata", "custom-key").size());
        var versions = ClusterMerge.versions(a.store.scan("/versions/"), "s3", "/s3/bootfs/shared"); assertEquals(2, versions.size());
        assertEquals("same", JSON.parseObject(JSON.parseObject(versions.firstEntry().getValue()).getString("value")).getString("uuid"));
        assertEquals(latest, JSON.parseObject(JSON.parseObject(versions.lastEntry().getValue()).getString("value")).getString("uuid"));
        // The initial leader leaves gracefully; the remaining member continues committing.
        a.store.leave(); assertFalse(a.store.available()); b.store.put("/after-leave", "durable");
        Node c = new Node("c"); c.resource("third", "third", "unconflicted");
        c.store.connect(b.config.getWebEntrypoint()); assertTrue(c.store.advance());
        a.store.close(); a.store = new DynamicMetadataStore(a.config, "", token, false);
        assertEquals("durable", a.store.get("/after-leave")); assertEquals(3, a.store.status().getJSONObject("peers").size());
        assertNotNull(a.store.get("/s3/bootfs/unconflicted"));
        // A complete portable backup now reads the active dynamic generations, not old standalone logs.
        for (Node node : List.of(a,b,c)) node.store.scan("");
        for (Node node : List.of(c,b,a)) { node.store.close(); node.store = null; }
        Path backup = temporary.resolve("backup");
        KernelBackup.export(List.of(Path.of(a.config.getRootPath()),Path.of(b.config.getRootPath()),Path.of(c.config.getRootPath())), backup, true);
        KernelBackup.verify(backup);
        Path restored = temporary.resolve("restored");
        Path plan = KernelBackup.restore(backup, restored, "seed", "http://restored");
        assertEquals("first", Files.readString(restored.resolve("data/same")));
        assertEquals("second", Files.readString(restored.resolve("data/" + latest)));
        assertEquals("second", Files.readString(restored.resolve("data/.recovered/b/same")));
        try (var recovered = new cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore(restored.resolve("kernel/metadata"))) {
            RestorePlan.apply(recovered, plan);
            assertEquals(2, ClusterMerge.versions(recovered.scan("/versions/"), "s3", "/s3/bootfs/shared").size());
        }
    }

    @Test @Timeout(120) void allDynamicPeersRestartWithTheirHistoryAndMinorityCannotAcknowledgeWrites() throws Exception {
        Node a = new Node("a"), b = new Node("b"), c = new Node("c");
        b.store.connect(a.config.getWebEntrypoint()); b.store.advance();
        c.store.connect(a.config.getWebEntrypoint()); c.store.advance();
        b.store.put("/confirmed", "survives");
        for (Node node : List.of(a,b,c)) node.store.scan("");
        for (Node node : List.of(c,b,a)) { node.store.close(); node.store = null; }
        for (Node node : List.of(a,b,c)) node.store = new DynamicMetadataStore(node.config, "", token, false);
        assertEquals("survives", c.store.get("/confirmed"));
        a.store.close(); a.store = null;
        b.store.put("/majority", "accepted");
        b.store.close(); b.store = null;
        assertThrows(RuntimeException.class, () -> c.store.put("/minority", "unacknowledged"));
        assertThrows(RuntimeException.class, () -> c.store.get("/confirmed"));
    }

    @Test @Timeout(90) void replicatedUploadPendingOnAnIndependentNodeCanJoinAndKeepItsPolicy() throws Exception {
        Node a = new Node("a"), b = new Node("b"); a.resource("pending","older","existing");
        Files.createDirectories(Path.of(b.config.getPathPrefix())); Path staged = Path.of(b.config.getPathPrefix(), ".upload-pending.part");
        Files.writeString(staged, "pending bytes");
        JSONObject pending = new JSONObject(); pending.put("file", JSON.parseObject("{\"uuid\":\"pending\",\"size\":13,\"fileName\":\"pending.txt\"}"));
        pending.put("stage", staged.getFileName().toString()); pending.put("sha256",ContentHash.sha256(staged)); pending.put("requiredCopies",2); pending.put("receipts",new JSONObject());
        b.store.put("/pending-publications/b/pending",pending.toJSONString());
        b.store.put("/replication-jobs/b/pending","{\"uuid\":\"pending\",\"replicas\":1,\"attempts\":2,\"nextAttempt\":0}");
        b.store.connect(a.config.getWebEntrypoint()); assertTrue(b.store.advance());
        String mapped = b.store.get("/upload-aliases/b/pending"); assertNotNull(mapped); assertNotEquals("pending",mapped);
        JSONObject imported = JSON.parseObject(b.store.get("/pending-publications/b/" + mapped));
        assertEquals(2,imported.getIntValue("requiredCopies")); assertEquals(mapped,imported.getJSONObject("file").getString("uuid"));
        assertNull(b.store.get("/files/" + mapped)); assertEquals("pending bytes",Files.readString(staged));
        assertEquals(mapped,JSON.parseObject(b.store.get("/replication-jobs/b/" + mapped)).getString("uuid"));
    }

    @Test @Timeout(90) void unadmittedEmptyRaftGenerationRestartsAndFinishesJoining() throws Exception {
        Node a = new Node("a"), b = new Node("b"); a.resource("a", "A", "key"); b.resource("b", "B", "other");
        b.store.connect(a.config.getWebEntrypoint()); failAdmission.set(true);
        assertThrows(IOException.class, () -> b.store.advance()); assertEquals("ATTACHING", b.store.status().getString("phase"));
        b.store.close(); b.store = new DynamicMetadataStore(b.config, "", token, false);
        assertTrue(b.store.available()); assertNotNull(b.store.get("/s3/bootfs/key")); assertNotNull(b.store.get("/s3/bootfs/other"));
    }

    @Test @Timeout(90) void removedOfflinePeerReturnsThroughAdmissionInsteadOfReusingAStaleVote() throws Exception {
        Node a = new Node("a"), b = new Node("b"), c = new Node("c");
        b.store.connect(a.config.getWebEntrypoint()); b.store.advance(); c.store.connect(a.config.getWebEntrypoint()); c.store.advance();
        b.store.advance(); // Persist reachable HTTP peers while the old configuration is still active.
        a.store.remove("b");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (b.store.available() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(b.store.available()); assertThrows(RuntimeException.class,() -> b.store.put("/removed-write","forbidden"));
        b.store.close(); b.store = null;
        c.store.put("/after-removal", "latest");
        b.store = new DynamicMetadataStore(b.config, "", token, false);
        assertEquals("latest", b.store.get("/after-removal")); assertEquals(3, b.store.status().getJSONObject("peers").size());
    }

    @Test @Timeout(90) void lostMergeReplyResumesTheDurableResultRatherThanCreatingMoreVersions() throws Exception {
        Node a = new Node("a"), b = new Node("b"); a.resource("a", "A", "key"); b.resource("b", "B", "key");
        b.store.connect(a.config.getWebEntrypoint()); loseMergeReply.set(true);
        assertThrows(IOException.class, () -> b.store.advance()); assertFalse(b.store.available());
        assertEquals(2, ClusterMerge.versions(a.store.scan("/versions/"), "s3", "/s3/bootfs/key").size());
        b.store.close(); b.store = new DynamicMetadataStore(b.config, "", token, false);
        assertTrue(b.store.advance());
        assertEquals(2, ClusterMerge.versions(b.store.scan("/versions/"), "s3", "/s3/bootfs/key").size());
    }

    @Test @Timeout(90) void frozenDonorRestartsAndResumesTheSameMergeWithoutDuplicatingVersions() throws Exception {
        Node a = new Node("a"), b = new Node("b"); a.resource("a-file", "A", "key"); b.resource("b-file", "B", "key");
        b.store.connect(a.config.getWebEntrypoint());
        b.store.close(); b.store = new DynamicMetadataStore(b.config, "", token, false);
        assertEquals("FROZEN", b.store.status().getString("phase"));
        assertTrue(b.store.advance()); assertFalse(b.store.advance());
        assertEquals(2, ClusterMerge.versions(a.store.scan("/versions/"), "s3", "/s3/bootfs/key").size());
    }
}
