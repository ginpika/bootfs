package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.kernel.consensus.RaftMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.*;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Stable API across a durable, explicitly fenced independent-node join. Old consensus directories are retained. */
public final class DynamicMetadataStore implements MetadataStore {
    private final Path root, manifestFile, data;
    private final String token, endpoint, nodeId;
    private final boolean allowInsecure;
    private JSONObject manifest;
    private RaftMetadataStore active;
    private Runnable attached = () -> {};
    public synchronized void onAttached(Runnable callback) { attached = callback; }

    public DynamicMetadataStore(TfsConfig config, String raftAddress, String token, boolean allowInsecure) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("Dynamic clustering requires a shared BOOTFS_CLUSTER_TOKEN");
        this.token = token; this.endpoint = config.getWebEntrypoint().replaceAll("/+$", "");
        this.allowInsecure = allowInsecure; this.nodeId = new NodeIdentity(config).id();
        root = Path.of(config.getRootPath(), "kernel", "dynamic"); data = Path.of(config.getPathPrefix()); manifestFile = root.resolve("node.json");
        Files.createDirectories(root);
        if (Files.exists(manifestFile)) manifest = JSON.parseObject(Files.readString(manifestFile));
        else {
            try (var existing = Files.list(root)) {
                if (existing.findAny().isPresent()) throw new IOException("Dynamic identity is missing but previous state remains; explicit recovery is required");
            }
            Path old = Path.of(config.getRootPath(), "kernel");
            if (Files.exists(old.resolve("metadata/CURRENT")) || Files.exists(old.resolve("raft-identity.json"))) throw new IOException("Use a portable backup and new directory to enter dynamic mode");
            if (raftAddress == null || raftAddress.isBlank()) {
                try (ServerSocket reservation = new ServerSocket(0)) { raftAddress = URI.create(endpoint).getHost() + ":" + reservation.getLocalPort(); }
            }
            manifest = new JSONObject(); manifest.put("nodeId", nodeId); manifest.put("groupId", UUID.randomUUID().toString());
            manifest.put("generation", UUID.randomUUID().toString()); manifest.put("phase", "ACTIVE");
            manifest.put("raftAddress", raftAddress); manifest.put("endpoint", endpoint);
            JSONObject peers = new JSONObject(); peers.put(nodeId, raftAddress); manifest.put("peers", peers); persist();
        }
        if (!nodeId.equals(manifest.getString("nodeId")) || !endpoint.equals(manifest.getString("endpoint"))) throw new IOException("Dynamic node identity or advertised HTTP endpoint differs from persisted identity");
        if (raftAddress != null && !raftAddress.isBlank() && !raftAddress.equals(manifest.getString("raftAddress"))) throw new IOException("Dynamic Raft endpoint differs from persisted identity");
        if ("LEAVING".equals(phase())) {
            JSONObject request = new JSONObject(); request.put("nodeId", nodeId); request.put("groupId",manifest.getString("groupId"));
            ClusterHttp.call(manifest.getString("target"), "remove", request, token);
            manifest.put("phase", "OFFLINE"); persist();
        }
        if ("OFFLINE".equals(phase()) && manifest.getJSONObject("peers").size() > 1) prepareReturning();
        else if ("OFFLINE".equals(phase())) { manifest.put("phase", "ACTIVE"); persist(); }
        if ("ACTIVE".equals(phase()) && manifest.getBooleanValue("everClustered")) {
            JSONObject known = manifest.getJSONObject("httpPeers");
            if (known != null) for (String peer : known.keySet()) {
                if (peer.equals(nodeId)) continue;
                try {
                    JSONObject remote = ClusterHttp.call(known.getString(peer), "status", null, token, 1000);
                    if (!manifest.getString("groupId").equals(remote.getString("groupId")) || !"ACTIVE".equals(remote.getString("phase"))) continue;
                    if (!remote.getJSONObject("peers").containsKey(nodeId)) {
                        manifest.put("target", known.getString(peer)); prepareReturning();
                    }
                    break;
                } catch (IOException unavailable) { /* Recover the existing history when no live peer can confirm removal. */ }
            }
        }
        open("ATTACHING".equals(phase()));
        if ("ATTACHING".equals(phase())) finishAttach();
    }
    private String phase() { return manifest.getString("phase"); }
    public synchronized boolean available() {
        if (active == null || !"ACTIVE".equals(phase())) return false;
        try { return active.currentPeers().containsKey(nodeId); }
        catch (MetadataException removed) { return false; }
    }
    private void writable() { if (!available()) throw new MetadataException("Node transition is " + phase() + "; writes are fenced"); }
    private RaftMetadataStore store() { if (active == null) throw new MetadataException("Node is offline"); return active; }
    private void persist() throws IOException {
        Path temporary = root.resolve("node.json.tmp");
        Files.writeString(temporary, manifest.toJSONString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
        Files.move(temporary, manifestFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); DurableFiles.syncDirectory(root);
    }
    private Map<String,String> seeds() {
        Map<String,String> peers = new TreeMap<>(); manifest.getJSONObject("peers").forEach((id,address) -> peers.put(id, (String)address));
        peers.put(nodeId, manifest.getString("raftAddress")); return peers;
    }
    private void open(boolean joining) {
        active = new RaftMetadataStore(root.resolve(manifest.getString("generation")), nodeId,
                UUID.fromString(manifest.getString("groupId")), seeds(), true, allowInsecure, true, joining);
    }
    public synchronized JSONObject status() {
        JSONObject status = new JSONObject(); status.put("nodeId", nodeId); status.put("groupId", manifest.getString("groupId"));
        status.put("phase", phase()); status.put("endpoint", endpoint); status.put("raftAddress", manifest.getString("raftAddress"));
        Map<String,String> peers = seeds();
        if (active != null) {
            try { peers = active.currentPeers(); }
            catch (MetadataException removed) { if ("ACTIVE".equals(phase())) status.put("phase","REMOVED"); }
            if ("ACTIVE".equals(phase()) && !peers.containsKey(nodeId)) status.put("phase","REMOVED");
        }
        status.put("peers",peers);
        status.put("independent", !manifest.getBooleanValue("everClustered"));
        status.put("lastError", manifest.getString("lastError")); return status;
    }
    public synchronized JSONObject ready() {
        writable(); JSONObject ready = status();
        ready.put("knownNodes", store().scan("/cluster/node/").keySet().stream().map(key -> key.substring("/cluster/node/".length())).toList());
        return ready;
    }
    public synchronized void connect(String target) throws IOException {
        if (target == null || target.isBlank()) throw new IOException("Supply a target node URL");
        if (!available()) throw new IOException("A durable node transition is already in progress");
        if (manifest.getBooleanValue("everClustered") || store().currentPeers().size() != 1) throw new IOException("Connect is for an independent node; use a standalone/new node to join an existing cluster");
        JSONObject remote = ClusterHttp.call(target, "ready", null, token);
        if (manifest.getString("groupId").equals(remote.getString("groupId"))) return;
        if (!"ACTIVE".equals(remote.getString("phase"))) throw new IOException("Target is not ready");
        if (remote.getJSONObject("peers").containsKey(nodeId) || (remote.getJSONArray("knownNodes") != null && remote.getJSONArray("knownNodes").contains(nodeId))) throw new IOException("Node identity is already registered in target membership");
        // Validate and freeze locally before sending any import request. The donor snapshot is stable thereafter.
        Map<String,String> snapshot = store().scan("");
        ClusterMerge.plan(Map.of(), snapshot, manifest.getString("groupId"), "preflight");
        for (Map.Entry<String,String> record : snapshot.entrySet()) {
            if (!record.getKey().startsWith("/nodes/") || !record.getKey().contains("/catalog/")) continue;
            JSONObject file = JSON.parseObject(record.getValue()); String id = file.getString("uuid");
            if (!record.getKey().startsWith("/nodes/" + nodeId + "/catalog/")) throw new IOException("Independent donor contains another node's inventory; reconnect it to its original cluster");
            if (snapshot.containsKey("/tombstones/" + id)) continue;
            Path content = data.resolve(id);
            if (!Files.isRegularFile(content) && file.getLongValue("size") == 0 && "1".equals(file.getString("hlsAvailable"))) continue;
            if (!Files.isRegularFile(content) || Files.size(content) != file.getLongValue("size")) throw new IOException("Missing donor object: " + id);
            String raw = snapshot.get("/files/" + id + "/meta");
            String hash = raw == null ? null : JSON.parseObject(raw).getString("sha256");
            if (hash != null && !hash.equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(content))) throw new IOException("Corrupt donor object: " + id);
        }
        for (Map.Entry<String,String> record : snapshot.entrySet()) {
            if (!record.getKey().startsWith("/pending-publications/")) continue;
            if (!record.getKey().startsWith("/pending-publications/" + nodeId + "/")) throw new IOException("Donor contains another node's pending upload");
            JSONObject intent = JSON.parseObject(record.getValue()); JSONObject file = intent.getJSONObject("file");
            String id = file.getString("uuid"), stage = intent.getString("stage");
            if (id == null || !id.matches("[A-Za-z0-9_-]+") || stage == null || !stage.matches("\\.upload-[A-Za-z0-9_-]+\\.part")) throw new IOException("Invalid pending content identity");
            Path bytes = Files.isRegularFile(data.resolve(id)) ? data.resolve(id) : data.resolve(stage);
            if (!Files.isRegularFile(bytes) || Files.size(bytes) != file.getLongValue("size")
                    || !Objects.equals(intent.getString("sha256"),cc.ginpika.bootfs.kernel.object.ContentHash.sha256(bytes))) throw new IOException("Pending donor bytes are missing or corrupt");
        }
        manifest.put("target", target.replaceAll("/+$", "")); manifest.put("mergeId", UUID.randomUUID().toString());
        manifest.put("phase", "FROZEN"); persist();
    }
    public synchronized boolean advance() throws IOException {
        try {
            if ("FROZEN".equals(phase())) {
                JSONObject request = new JSONObject(); request.put("mergeId", manifest.getString("mergeId"));
                request.put("sourceCluster", manifest.getString("groupId")); request.put("nodeId", nodeId);
                request.put("raftAddress", manifest.getString("raftAddress")); request.put("entries", store().scan(""));
                JSONObject imported = ClusterHttp.call(manifest.getString("target"), "merge", request, token);
                JSONObject renames = imported.getJSONObject("renamedObjects");
                if (renames != null) for (String old : renames.keySet()) cloneLocalIdentity(old, renames.getString(old));
                manifest.put("previousGeneration", manifest.getString("generation")); manifest.put("generation", UUID.randomUUID().toString());
                manifest.put("groupId", imported.getString("groupId")); manifest.put("peers", imported.getJSONObject("peers"));
                manifest.put("phase", "ATTACHING"); persist();
                active.close(); active = null; open(true);
            }
            if ("ATTACHING".equals(phase())) { finishAttach(); return true; }
            if (available()) refreshSeeds();
            return false;
        } catch (IOException | RuntimeException failure) {
            manifest.put("lastError", failure.getMessage()); persist();
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("Cluster transition will retry", failure);
        }
    }
    private void cloneLocalIdentity(String old, String replacement) throws IOException {
        if (!old.matches("[A-Za-z0-9_-]+") || !replacement.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid renamed identity");
        clonePath(data.resolve(old), data.resolve(replacement));
        clonePath(data.resolve("thumb/" + old + ".webp"), data.resolve("thumb/" + replacement + ".webp"));
        clonePath(data.resolve("hls/" + old), data.resolve("hls/" + replacement));
    }
    private static void clonePath(Path source, Path destination) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source)) throw new IOException("Cannot merge symlinked object data");
        if (Files.isDirectory(source)) {
            Files.createDirectories(destination);
            try (var paths = Files.list(source)) { for (Path child : paths.toList()) clonePath(child, destination.resolve(child.getFileName())); }
            DurableFiles.syncDirectory(destination);
        } else if (!Files.exists(destination)) {
            Files.copy(source, destination); DurableFiles.syncFile(destination); DurableFiles.syncDirectory(destination.getParent());
        } else if (Files.mismatch(source, destination) != -1) throw new IOException("Conflicting immutable bytes at " + destination);
    }
    private void prepareReturning() throws IOException {
        manifest.put("previousGeneration", manifest.getString("generation")); manifest.put("generation", UUID.randomUUID().toString());
        manifest.put("phase", "ATTACHING"); persist();
    }
    private void finishAttach() throws IOException {
        JSONObject request = new JSONObject(); request.put("nodeId", nodeId); request.put("groupId",manifest.getString("groupId")); request.put("raftAddress", manifest.getString("raftAddress"));
        request.put("groupId", manifest.getString("groupId"));
        JSONObject remote = ClusterHttp.call(manifest.getString("target"), "status", null, token);
        if (!manifest.getString("groupId").equals(remote.getString("groupId"))) throw new IOException("Target now belongs to a different cluster; refusing to fork or attach");
        ClusterHttp.call(manifest.getString("target"), "admit", request, token);
        store().scan(""); // Wait for the local snapshot/log before exposing inventory and search.
        attached.run(); // Refresh local paths and inventory before admitting any application request.
        manifest.put("phase", "ACTIVE"); manifest.put("everClustered", true); manifest.remove("lastError"); refreshSeeds(); persist();
    }
    public synchronized JSONObject merge(JSONObject request) throws IOException {
        writable();
        String mergeId = request.getString("mergeId");
        if (mergeId == null || !mergeId.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid merge identity");
        String marker = "/cluster/imports/" + mergeId;
        String existing = store().get(marker);
        if (existing != null) return JSON.parseObject(existing);
        String donor = request.getString("nodeId");
        if (donor == null || !donor.matches("[A-Za-z0-9_-]+") || store().currentPeers().containsKey(donor)) throw new IOException("Node identity is already in this cluster");
        Map<String,String> source = strings(request.getJSONObject("entries"));
        for (int attempt = 0; attempt < 8; attempt++) {
            Map<String,String> target = store().scan("");
            if (target.containsKey(marker)) return JSON.parseObject(target.get(marker));
            ClusterMerge.Plan plan = ClusterMerge.plan(target, source, request.getString("sourceCluster"), mergeId);
            JSONObject response = new JSONObject(); response.put("groupId", manifest.getString("groupId"));
            response.put("peers", store().currentPeers()); response.put("renamedObjects", plan.renamedObjects());
            Map<String,String> puts = new TreeMap<>(plan.puts()), expected = new TreeMap<>(plan.expected());
            long era = Math.max(number(target.get("/cluster/revision-era")), number(source.get("/cluster/revision-era"))) + 1;
            puts.put("/cluster/revision-era", Long.toString(era)); expected.put("/cluster/revision-era", target.get("/cluster/revision-era"));
            puts.put(marker, response.toJSONString()); expected.put(marker, null);
            if (store().compareAndWrite(expected, puts, Set.of())) return response;
        }
        throw new IOException("Destination is changing too quickly; retry this same merge");
    }
    private static long number(String value) { return value == null ? 0 : Long.parseLong(value); }
    private static Map<String,String> strings(JSONObject values) throws IOException {
        Map<String,String> entries = new TreeMap<>();
        for (String key : values.keySet()) {
            if (key.indexOf(0) >= 0 || !(values.get(key) instanceof String)) throw new IOException("Invalid imported metadata");
            entries.put(key, values.getString(key));
        }
        return entries;
    }
    public synchronized void admit(String id, String address) throws IOException {
        writable();
        if (id == null || !id.matches("[A-Za-z0-9_-]+") || address == null || address.isBlank()) throw new IOException("Invalid joining node");
        Map<String,String> peers = store().currentPeers();
        if (peers.containsKey(id)) { if (!peers.get(id).equals(address)) throw new IOException("Node endpoint changed"); return; }
        Map<String,String> next = new TreeMap<>(peers); next.put(id, address);
        store().changePeers(peers, next); manifest.put("everClustered", true); refreshSeeds(); persist();
    }
    public synchronized void remove(String id) throws IOException {
        writable(); Map<String,String> peers = store().currentPeers();
        if (!peers.containsKey(id)) return;
        if (peers.size() == 1) throw new IOException("The last member may stop, but cannot be removed");
        if (nodeId.equals(id)) throw new IOException("Ask another member to remove this node");
        Map<String,String> next = new TreeMap<>(peers); next.remove(id); store().changePeers(peers, next); refreshSeeds();
    }
    public synchronized void leave() throws IOException {
        writable(); Map<String,String> peers = store().currentPeers();
        if (peers.size() > 1) {
            String remote = null;
            for (String peer : peers.keySet()) if (!peer.equals(nodeId)) {
                remote = store().get("/cluster/node/" + peer); if (remote != null) break;
            }
            if (remote == null) throw new IOException("No remaining HTTP peer is known");
            // Persist intent first: an uncertain removal must never restart an independent authority.
            manifest.put("target", remote); manifest.put("phase", "LEAVING"); persist();
            JSONObject request = new JSONObject(); request.put("nodeId", nodeId); request.put("groupId",manifest.getString("groupId"));
            ClusterHttp.call(remote, "remove", request, token);
        }
        manifest.put("phase", "OFFLINE"); persist(); active.close(); active = null;
    }
    private void refreshSeeds() throws IOException {
        Map<String,String> peers = store().currentPeers();
        JSONObject http = new JSONObject();
        store().scan("/cluster/node/").forEach((key,value) -> {
            String id = key.substring("/cluster/node/".length()); if (peers.containsKey(id)) http.put(id,value);
        });
        if (!peers.isEmpty() && (!new JSONObject(peers).equals(manifest.getJSONObject("peers")) || !http.equals(manifest.getJSONObject("httpPeers")))) {
            manifest.put("peers", peers); manifest.put("httpPeers", http); persist();
        }
    }
    public synchronized void resumeLeave() throws IOException {
        if (!"LEAVING".equals(phase())) return;
        JSONObject request = new JSONObject(); request.put("nodeId", nodeId); request.put("groupId",manifest.getString("groupId"));
        ClusterHttp.call(manifest.getString("target"), "remove", request, token);
        manifest.put("phase", "OFFLINE"); persist(); if (active != null) { active.close(); active = null; }
    }
    @Override public synchronized String get(String key) { return store().get(key); }
    @Override public synchronized NavigableMap<String,String> scan(String prefix) { return store().scan(prefix); }
    @Override public synchronized long revision() { return Math.addExact(Math.multiplyExact(number(store().get("/cluster/revision-era")), 1000000000000L), store().revision()); }
    @Override public synchronized void put(String key,String value) { writable(); store().put(key,value); }
    @Override public synchronized boolean delete(String key) { writable(); return store().delete(key); }
    @Override public synchronized void deletePrefix(String prefix) { writable(); store().deletePrefix(prefix); }
    @Override public synchronized void writeBatch(Map<String,String> puts, Set<String> deletes) { writable(); store().writeBatch(puts,deletes); }
    @Override public synchronized boolean compareAndSet(String key,String expected,String value) { writable(); return store().compareAndSet(key,expected,value); }
    @Override public synchronized boolean compareAndWrite(Map<String,String> expected,Map<String,String> puts,Set<String> deletes) { writable(); return store().compareAndWrite(expected,puts,deletes); }
    @Override public synchronized void close() { if (active != null) { active.close(); active = null; } }
}
