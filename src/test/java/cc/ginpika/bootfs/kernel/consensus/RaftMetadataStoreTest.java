package cc.ginpika.bootfs.kernel.consensus;

import cc.ginpika.bootfs.kernel.metadata.MetadataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RaftMetadataStoreTest {
    @TempDir Path temporary;

    @Test @Timeout(120) void threePeersCommitFailoverAndRecover() throws Exception {
        Map<String, String> peers = peers(3);
        UUID group = UUID.randomUUID();
        UUID conditionalRequest = UUID.randomUUID();
        Map<String, RaftMetadataStore> stores = new LinkedHashMap<>();
        try {
            for (String node : peers.keySet()) stores.put(node,
                    new RaftMetadataStore(temporary.resolve(node), node, group, peers, true));
            awaitLeader(stores.values());
            stores.get("n0").writeBatch(Map.of("/resource/a", "original", "/resource/b", "second"), Set.of());
            for (RaftMetadataStore store : stores.values()) assertEquals("original", store.get("/resource/a"));
            Map<String,String> absent = new TreeMap<>(); absent.put("/conditional/new", null);
            assertTrue(stores.get("n0").compareAndWrite(absent, Map.of("/conditional/new", "first"), Set.of()));
            assertFalse(stores.get("n1").compareAndWrite(absent, Map.of("/conditional/new", "wrong"), Set.of()));
            assertEquals("first", stores.get("n2").get("/conditional/new"));
            assertTrue(stores.get("n1").compareAndSet("/resource/a", "original", "updated", conditionalRequest));
            assertFalse(stores.get("n2").compareAndSet("/resource/a", "original", "stale"));
            String leaderId = awaitLeader(stores.values()).nodeId();
            stores.get(leaderId).takeSnapshot();
            stores.remove(leaderId).close();
            RaftMetadataStore survivingLeader = awaitLeader(stores.values());
            survivingLeader.put("/after-failover", "durable");
            long beforeReplay = survivingLeader.revision();
            assertTrue(survivingLeader.compareAndSet("/resource/a", "original", "updated", conditionalRequest));
            assertEquals(beforeReplay, survivingLeader.revision(), "Retry must return original result without applying twice");
            assertEquals("updated", survivingLeader.get("/resource/a"));
            assertEquals("durable", survivingLeader.get("/after-failover"));
            stores.put(leaderId, new RaftMetadataStore(temporary.resolve(leaderId), leaderId, group, peers, false));
            assertEquals("durable", stores.get(leaderId).get("/after-failover"));
            assertEquals(Map.of("/resource/a", "updated", "/resource/b", "second"),
                    stores.get(leaderId).scan("/resource/"));
        } finally { closeAll(stores); }
        // All-peer restart must retain committed state and original cluster identity.
        try {
            for (String node : peers.keySet()) stores.put(node,
                    new RaftMetadataStore(temporary.resolve(node), node, group, peers, false));
            awaitLeader(stores.values());
            assertEquals("durable", stores.get("n0").get("/after-failover"));
            assertEquals("updated", stores.get("n2").get("/resource/a"));
            assertTrue(stores.get("n2").compareAndSet("/resource/a", "original", "updated", conditionalRequest));
            assertThrows(MetadataException.class, () -> stores.get("n2")
                    .compareAndSet("/resource/a", "updated", "different-operation", conditionalRequest));
            stores.get("n2").put("/after-rejection", "healthy");
            assertEquals("healthy", stores.get("n1").get("/after-rejection"));
        } finally { closeAll(stores); }
    }

    @Test @Timeout(120) void dynamicPeersJoinAnIndependentNodeAndLeaveWithoutObjectRebalancing() throws Exception {
        Map<String,String> addresses = peers(3);
        UUID group = UUID.randomUUID();
        Map<String,RaftMetadataStore> stores = new LinkedHashMap<>();
        Map<String,String> membership = new TreeMap<>(); membership.put("n0", addresses.get("n0"));
        try {
            stores.put("n0", new RaftMetadataStore(temporary.resolve("dynamic0"), "n0", group, membership, true, false, true, false));
            stores.get("n0").put("/before-join", "preserved");
            for (int i = 1; i < 3; i++) {
                String id = "n" + i;
                Map<String,String> next = new TreeMap<>(membership); next.put(id, addresses.get(id));
                stores.put(id, new RaftMetadataStore(temporary.resolve("dynamic" + i), id, group, next, true, false, true, true));
                stores.get("n0").changePeers(membership, next); membership = next;
                assertEquals("preserved", stores.get(id).get("/before-join"));
            }
            stores.get("n2").put("/after-join", "replicated");
            Map<String,String> smaller = new TreeMap<>(membership); smaller.remove("n2");
            stores.get("n0").changePeers(membership, smaller);
            stores.remove("n2").close();
            stores.get("n1").put("/after-leave", "safe");
            assertEquals("safe", stores.get("n0").get("/after-leave"));
        } finally { closeAll(stores); }
    }

    @Test @Timeout(120) void portableEmbeddedBackupImportsIntoANewThreeMemberRaftHistory() throws Exception {
        Path source = temporary.resolve("source"); java.nio.file.Files.createDirectories(source.resolve("data"));
        java.nio.file.Files.writeString(source.resolve("uuid"), "source"); java.nio.file.Files.writeString(source.resolve("data/resource"), "content");
        try (var local = new cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore(source.resolve("kernel/metadata"))) {
            local.writeBatch(Map.of("/nodes/source/catalog/resource", "{\"uuid\":\"resource\",\"fileName\":\"book\",\"size\":7}",
                    "/files/resource", "http://source/f/resource", "/documents/full-text/resource", "{\"uuid\":\"resource\",\"author\":\"kept\"}"), Set.of());
        }
        Path backup = temporary.resolve("portable");
        cc.ginpika.bootfs.kernel.backup.KernelBackup.export(List.of(source), backup, true);
        Path seed = temporary.resolve("restored-n0");
        Path plan = cc.ginpika.bootfs.kernel.backup.KernelBackup.restore(backup, seed, "n0", "http://new-seed");
        Map<String,String> peers = peers(3); UUID group = UUID.randomUUID();
        Map<String,RaftMetadataStore> stores = new LinkedHashMap<>();
        try {
            stores.put("n0", new RaftMetadataStore(seed.resolve("kernel"), "n0", group, peers, true));
            for (String id : List.of("n1", "n2")) stores.put(id, new RaftMetadataStore(temporary.resolve(id), id, group, peers, true));
            awaitLeader(stores.values());
            for (RaftMetadataStore store : stores.values()) cc.ginpika.bootfs.kernel.backup.RestorePlan.apply(store, plan);
            assertEquals("http://new-seed/f/resource", stores.get("n2").get("/files/resource"));
            assertTrue(stores.get("n1").get("/documents/full-text/resource").contains("kept"));
            assertEquals("content", java.nio.file.Files.readString(seed.resolve("data/resource")));
            assertNull(stores.get("n1").get("/nodes/source/catalog/resource"));
        } finally { closeAll(stores); }
    }

    @Test void firstStartRequiresExplicitBootstrapAndStableIdentity() throws Exception {
        Map<String, String> peers = peers(1);
        UUID group = UUID.randomUUID();
        Path directory = temporary.resolve("single");
        assertThrows(MetadataException.class, () -> new RaftMetadataStore(directory, "n0", group, peers, false));
        try (RaftMetadataStore store = new RaftMetadataStore(directory, "n0", group, peers, true)) {
            awaitLeader(List.of(store));
            store.put("a", "persisted");
        }
        assertThrows(MetadataException.class,
                () -> new RaftMetadataStore(directory, "n0", UUID.randomUUID(), peers, true));
    }

    @Test @Timeout(120) void offlineFollowerInstallsSnapshotAndMinorityCannotAcknowledgeWrites() throws Exception {
        Map<String, String> peers = peers(3);
        UUID group = UUID.randomUUID();
        Map<String, RaftMetadataStore> stores = new LinkedHashMap<>();
        try {
            for (String node : peers.keySet()) stores.put(node,
                    new RaftMetadataStore(temporary.resolve(node), node, group, peers, true));
            RaftMetadataStore leader = awaitLeader(stores.values());
            leader.put("/seed", "seed");
            String offline = stores.keySet().stream().filter(node -> !node.equals(leader.nodeId())).findFirst().orElseThrow();
            long offlineIndex = stores.get(offline).localAppliedIndex();
            stores.remove(offline).close();
            // Cross the default 8 MB segment boundary so compaction removes a closed segment.
            String payload = "x".repeat(768 * 1024);
            for (int i = 0; i < 16; i++) leader.put("/offline/" + i, payload);
            long snapshot = leader.takeSnapshot();
            assertTrue(snapshot > 0);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (leader.firstRetainedLogIndex() <= offlineIndex && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(leader.firstRetainedLogIndex() > offlineIndex, "Leader log must be compacted beyond offline peer progress");
            RaftMetadataStore returning = new RaftMetadataStore(temporary.resolve(offline), offline, group, peers, false);
            stores.put(offline, returning);
            deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (returning.localSnapshotIndex() < snapshot && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(returning.localSnapshotIndex() >= snapshot, "Offline follower must install leader snapshot");
            assertTrue(returning.localAppliedIndex() >= snapshot);
            assertEquals(16, returning.scan("/offline/").size());
            // One remaining member cannot acknowledge a write or serve an authoritative read.
            for (String node : new ArrayList<>(stores.keySet())) {
                if (!node.equals(returning.nodeId())) stores.remove(node).close();
            }
            assertThrows(MetadataException.class, () -> returning.put("/minority", "unconfirmed"));
            assertThrows(MetadataException.class, () -> returning.get("/seed"));
        } finally { closeAll(stores); }
    }

    @Test void remoteTransportRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException.class, () -> new RaftMetadataStore(temporary.resolve("remote"),
                "n0", UUID.randomUUID(), Map.of("n0", "192.0.2.1:19090"), true));
    }

    private static Map<String, String> peers(int count) throws Exception {
        Map<String, String> result = new TreeMap<>();
        List<ServerSocket> reservations = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0); reservations.add(socket);
                result.put("n" + i, "127.0.0.1:" + socket.getLocalPort());
            }
        } finally { for (ServerSocket socket : reservations) socket.close(); }
        return result;
    }

    private static RaftMetadataStore awaitLeader(Collection<RaftMetadataStore> stores) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            for (RaftMetadataStore store : stores) if (store.isLeader()) return store;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No Raft leader elected");
    }

    private static void closeAll(Map<String, RaftMetadataStore> stores) {
        RuntimeException error = null;
        for (RaftMetadataStore store : stores.values()) {
            try { store.close(); } catch (RuntimeException closeError) { error = closeError; }
        }
        stores.clear();
        if (error != null) throw error;
    }
}
