package cc.ginpika.bootfs.kernel.consensus;

import cc.ginpika.bootfs.kernel.metadata.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.*;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.util.SizeInBytes;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Embedded Raft cluster. Every public read is served through the leader's ReadIndex barrier. */
public final class RaftMetadataStore implements MetadataStore {
    private final RocksMetadataStore data;
    private final MetadataStateMachine stateMachine;
    private final RaftServer server;
    private final RaftClient client;
    private final RaftGroupId raftGroupId;

    public RaftMetadataStore(Path root, String nodeId, UUID groupId,
                             Map<String, String> peerAddresses, boolean bootstrap) {
        this(root, nodeId, groupId, peerAddresses, bootstrap, false);
    }

    public RaftMetadataStore(Path root, String nodeId, UUID groupId,
                             Map<String, String> peerAddresses, boolean bootstrap, boolean allowInsecureNetwork) {
        this(root, nodeId, groupId, peerAddresses, bootstrap, allowInsecureNetwork, false, false);
    }

    public RaftMetadataStore(Path root, String nodeId, UUID groupId, Map<String,String> peerAddresses,
                             boolean bootstrap, boolean allowInsecureNetwork, boolean dynamic, boolean joining) {
        if (nodeId == null || nodeId.isBlank() || !peerAddresses.containsKey(nodeId)) {
            throw new IllegalArgumentException("bootfs.raft.node-id must be a configured peer");
        }
        if (peerAddresses.isEmpty()) throw new IllegalArgumentException("Raft peers must be explicit");
        if (!allowInsecureNetwork) {
            for (String address : peerAddresses.values()) {
                try {
                    InetAddress[] addresses = InetAddress.getAllByName(host(address));
                    for (InetAddress resolved : addresses) if (!resolved.isLoopbackAddress()) {
                        throw new IllegalArgumentException("Non-loopback Raft transport requires explicit bootfs.raft.allow-insecure-network=true until mTLS is configured");
                    }
                } catch (IOException error) { throw new IllegalArgumentException("Cannot resolve Raft peer", error); }
            }
        }
        Path identity = root.resolve("raft-identity.json");
        Path initialized = root.resolve("raft-initialized");
        Path groupDirectory = root.resolve("raft").resolve(groupId.toString());
        JSONObject expectedIdentity = new JSONObject();
        expectedIdentity.put("nodeId", nodeId); expectedIdentity.put("groupId", groupId.toString());
        if (dynamic) expectedIdentity.put("dynamic", true);
        else expectedIdentity.put("peers", new TreeMap<>(peerAddresses));
        try {
            Files.createDirectories(root);
            if (Files.exists(initialized) && !Files.isDirectory(groupDirectory)) {
                throw new MetadataException("Persisted Raft storage is missing; explicit node recovery is required");
            }
            if (Files.exists(identity)) {
                if (!JSON.parseObject(Files.readString(identity)).equals(expectedIdentity)) {
                    throw new MetadataException("Raft identity or static peer configuration differs from persisted identity");
                }
            } else {
                if (!bootstrap) throw new MetadataException("First Raft start requires explicit bootfs.raft.bootstrap=true");
                Files.writeString(identity, expectedIdentity.toJSONString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
            }
        } catch (IOException error) { throw new MetadataException("Cannot persist Raft identity", error); }

        data = new RocksMetadataStore(root.resolve("metadata"));
        if (data.revision() > 0 && data.appliedIndex() < 0) {
            data.close();
            throw new MetadataException("Unreplicated metadata exists; migrate it before enabling Raft");
        }
        stateMachine = new MetadataStateMachine(data);
        RaftProperties properties = new RaftProperties();
        RaftServerConfigKeys.setStorageDir(properties, List.of(root.resolve("raft").toFile()));
        RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
        RaftServerConfigKeys.Read.setTimeout(properties, TimeDuration.valueOf(5, TimeUnit.SECONDS));
        RaftClientConfigKeys.Rpc.setRequestTimeout(properties, TimeDuration.valueOf(2, TimeUnit.SECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMin(properties, TimeDuration.valueOf(600, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(properties, TimeDuration.valueOf(1200, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(properties, 1000);
        RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(properties, true);
        RaftServerConfigKeys.Log.setSegmentSizeMax(properties, SizeInBytes.valueOf("8MB"));
        RaftServerConfigKeys.Log.setPurgePreservationLogNum(properties, 0);
        RaftServerConfigKeys.Log.setPurgeGap(properties, 1);
        if (dynamic) properties.setBoolean("raft.server.leaderelection.member.majority-add", true);
        String address = peerAddresses.get(nodeId);
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        GrpcConfigKeys.Server.setPort(properties, port);
        GrpcConfigKeys.Server.setHost(properties, allowInsecureNetwork ? "0.0.0.0" : host(address));
        List<RaftPeer> peers = new ArrayList<>();
        peerAddresses.forEach((id, endpoint) -> peers.add(RaftPeer.newBuilder().setId(id).setAddress(endpoint).build()));
        RaftGroup group = RaftGroup.valueOf(RaftGroupId.valueOf(groupId), peers);
        raftGroupId = group.getGroupId();
        RaftServer startedServer = null;
        try {
            startedServer = RaftServer.newBuilder().setServerId(RaftPeerId.valueOf(nodeId))
                    .setGroup(joining ? RaftGroup.valueOf(group.getGroupId(), List.of()) : group)
                    .setStateMachine(stateMachine).setProperties(properties)
                    .setOption(Files.exists(groupDirectory)
                            ? RaftStorage.StartupOption.RECOVER : RaftStorage.StartupOption.FORMAT).build();
            startedServer.start();
            Files.writeString(initialized, groupId.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            client = RaftClient.newBuilder().setRaftGroup(group).setProperties(properties)
                    .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(12,
                            TimeDuration.valueOf(250, TimeUnit.MILLISECONDS))).build();
            server = startedServer;
        } catch (IOException | RuntimeException error) {
            if (startedServer != null) try { startedServer.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            data.close();
            throw new MetadataException("Cannot start embedded Raft", error);
        }
    }

    private static String host(String address) {
        String host = address.substring(0, address.lastIndexOf(':'));
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private JSONObject request(String op) { JSONObject request = new JSONObject(); request.put("op", op); return request; }
    private JSONObject send(JSONObject request, boolean readOnly) {
        try {
            if (!readOnly && !request.containsKey("requestId")) request.put("requestId", UUID.randomUUID().toString());
            Message message = Message.valueOf(request.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
            RaftClientReply reply = readOnly ? client.io().sendReadOnly(message) : client.io().send(message);
            if (!reply.isSuccess()) throw new MetadataException("Raft metadata operation failed", reply.getException());
            JSONObject response = JSON.parseObject(reply.getMessage().getContent().toStringUtf8());
            if (response.containsKey("error")) throw new MetadataException(response.getString("error"));
            return response;
        } catch (IOException error) { throw new MetadataException("Raft quorum is unavailable; operation not acknowledged", error); }
    }
    @Override public String get(String key) {
        RocksMetadataStore.validateKey(key); JSONObject command = request("get"); command.put("key", key);
        return send(command, true).getString("value");
    }
    @Override public void put(String key, String value) { writeBatch(Map.of(key, value), Set.of()); }
    @Override public boolean delete(String key) {
        RocksMetadataStore.validateKey(key); JSONObject command = request("delete"); command.put("key", key);
        return send(command, false).getBooleanValue("matched");
    }
    @Override public NavigableMap<String, String> scan(String prefix) {
        RocksMetadataStore.validateKey(prefix);
        // Establish an authoritative barrier, then scan this full replica to avoid an unbounded RPC response.
        long barrier = send(request("progress"), true).getLongValue("appliedIndex");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (data.appliedIndex() < barrier) {
            if (System.nanoTime() >= deadline) throw new MetadataException("Local metadata replica has not reached read barrier");
            try { Thread.sleep(10); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new MetadataException("Interrupted waiting for read barrier", error);
            }
        }
        return data.scan(prefix);
    }
    @Override public void deletePrefix(String prefix) {
        RocksMetadataStore.validateKey(prefix); JSONObject command = request("deletePrefix"); command.put("prefix", prefix);
        send(command, false);
    }
    @Override public void writeBatch(Map<String, String> puts, Set<String> deletes) {
        puts.forEach((key, value) -> { RocksMetadataStore.validateKey(key); Objects.requireNonNull(value, "value"); });
        deletes.forEach(RocksMetadataStore::validateKey);
        JSONObject command = request("batch"); command.put("puts", new TreeMap<>(puts));
        command.put("deletes", new TreeSet<>(deletes)); send(command, false);
    }
    @Override public boolean compareAndSet(String key, String expected, String replacement) {
        return compareAndSet(key, expected, replacement, UUID.randomUUID());
    }
    /** Reuse the request ID only when retrying exactly the same conditional operation. */
    public boolean compareAndSet(String key, String expected, String replacement, UUID requestId) {
        RocksMetadataStore.validateKey(key); JSONObject command = request("cas"); command.put("key", key);
        command.put("expected", expected); command.put("replacement", replacement);
        command.put("requestId", requestId.toString());
        return send(command, false).getBooleanValue("matched");
    }
    @Override public boolean compareAndWrite(Map<String,String> expected, Map<String,String> puts, Set<String> deletes) {
        expected.keySet().forEach(RocksMetadataStore::validateKey);
        puts.forEach((key,value) -> { RocksMetadataStore.validateKey(key); Objects.requireNonNull(value); });
        deletes.forEach(RocksMetadataStore::validateKey);
        JSONObject command = request("conditionalBatch"); command.put("expected", new TreeMap<>(expected));
        command.put("puts", new TreeMap<>(puts)); command.put("deletes", new TreeSet<>(deletes));
        return send(command, false).getBooleanValue("matched");
    }
    public UUID groupId() { return raftGroupId.getUuid(); }
    public Map<String,String> currentPeers() {
        try {
            Map<String,String> peers = new TreeMap<>();
            server.getDivision(raftGroupId).getRaftConf().getCurrentPeers().forEach(peer -> peers.put(peer.getId().toString(), peer.getAddress()));
            return peers;
        } catch (IOException error) { throw new MetadataException("Cannot inspect membership", error); }
    }
    /** Joint consensus compares the prior configuration, so concurrent joins cannot remove each other. */
    public void changePeers(Map<String,String> expected, Map<String,String> replacement) {
        if (replacement.isEmpty()) throw new IllegalArgumentException("Cannot remove the last member");
        java.util.function.Function<Map<String,String>, List<RaftPeer>> build = values -> values.entrySet().stream()
                .map(entry -> RaftPeer.newBuilder().setId(entry.getKey()).setAddress(entry.getValue()).build()).toList();
        try {
            RaftClientReply reply = client.admin().setConfiguration(SetConfigurationRequest.Arguments.newBuilder()
                    .setMode(SetConfigurationRequest.Mode.COMPARE_AND_SET).setServersInCurrentConf(build.apply(expected))
                    .setServersInNewConf(build.apply(replacement)).build());
            if (!reply.isSuccess()) throw new MetadataException("Membership change was not acknowledged", reply.getException());
        } catch (IOException failure) { throw new MetadataException("Membership change is uncertain; inspect committed membership before retrying", failure); }
    }
    @Override public long revision() { return send(request("revision"), true).getLongValue("revision"); }
    public String nodeId() { return server.getId().toString(); }
    public boolean isLeader() {
        try { return server.getDivision(raftGroupId).getInfo().isLeader(); }
        catch (IOException error) { throw new MetadataException("Cannot inspect Raft state", error); }
    }
    public long takeSnapshot() {
        try {
            RaftClientReply reply = client.getSnapshotManagementApi(server.getId()).create(true, 10000);
            if (!reply.isSuccess()) throw new MetadataException("Snapshot request rejected", reply.getException());
            return localSnapshotIndex();
        }
        catch (IOException error) { throw new MetadataException("Cannot snapshot metadata", error); }
    }
    public long localAppliedIndex() { return data.appliedIndex(); }
    public long localSnapshotIndex() {
        return stateMachine.getLatestSnapshot() == null ? -1 : stateMachine.getLatestSnapshot().getTermIndex().getIndex();
    }
    public long firstRetainedLogIndex() {
        try { return server.getDivision(raftGroupId).getRaftLog().getStartIndex(); }
        catch (IOException error) { throw new MetadataException("Cannot inspect Raft log", error); }
    }
    @Override public void close() {
        RuntimeException failure = null;
        try { client.close(); } catch (IOException error) { failure = new MetadataException("Cannot close Raft client", error); }
        try { server.close(); } catch (IOException error) {
            if (failure == null) failure = new MetadataException("Cannot close Raft server", error); else failure.addSuppressed(error);
        } finally { data.close(); }
        if (failure != null) throw failure;
    }
}
