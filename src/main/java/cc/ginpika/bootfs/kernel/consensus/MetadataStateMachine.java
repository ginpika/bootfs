package cc.ginpika.bootfs.kernel.consensus;

import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.util.MD5FileUtil;
import org.apache.ratis.util.LifeCycle;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Applies deterministic commands; network and media processing never run on this thread. */
final class MetadataStateMachine extends BaseStateMachine {
    private final RocksMetadataStore data;
    private final SimpleStateMachineStorage snapshots = new SimpleStateMachineStorage();

    MetadataStateMachine(RocksMetadataStore data) { this.data = data; }

    @Override public synchronized void initialize(RaftServer server, RaftGroupId group, RaftStorage storage) throws IOException {
        super.initialize(server, group, storage);
        getLifeCycle().startAndTransition(() -> {
            snapshots.init(storage);
            loadState();
        }, IOException.class);
    }

    @Override public synchronized void reinitialize() throws IOException {
        getLifeCycle().startAndTransition(this::loadState, IOException.class);
    }

    @Override public synchronized void pause() {
        getLifeCycle().transition(LifeCycle.State.PAUSING);
        getLifeCycle().transition(LifeCycle.State.PAUSED);
    }

    @Override public synchronized void close() {
        getLifeCycle().checkStateAndClose();
    }

    private void loadState() throws IOException {
        SingleFileSnapshotInfo snapshot = snapshots.loadLatestSnapshot();
        if (snapshot != null && snapshot.getTermIndex().getIndex() > data.appliedIndex()) {
            Path file = snapshot.getFile().getPath();
            MD5FileUtil.verifySavedMD5(file.toFile(), snapshot.getFile().getFileDigest());
            data.importSnapshot(file);
        }
        if (data.appliedIndex() >= 0) {
            setLastAppliedTermIndex(TermIndex.valueOf(data.appliedTerm(), data.appliedIndex()));
        }
    }

    @Override public SimpleStateMachineStorage getStateMachineStorage() { return snapshots; }

    @Override public synchronized long takeSnapshot() throws IOException {
        TermIndex applied = getLastAppliedTermIndex();
        if (applied == null || applied.getIndex() < 0) return -1;
        // Ratis also advances over configuration/no-op entries outside applyTransaction.
        data.advance(applied.getTerm(), applied.getIndex());
        Path target = snapshots.getSnapshotFile(applied.getTerm(), applied.getIndex()).toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        data.exportSnapshot(temporary);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        FileInfo info = new FileInfo(target, MD5FileUtil.computeAndSaveMd5ForFile(target.toFile()));
        try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        snapshots.updateLatestSnapshot(new SingleFileSnapshotInfo(info, applied));
        return applied.getIndex();
    }

    @Override public synchronized CompletableFuture<Message> query(Message request) {
        try {
            JSONObject command = JSON.parseObject(request.getContent().toStringUtf8());
            JSONObject result = new JSONObject();
            switch (command.getString("op")) {
                case "get": result.put("value", data.get(command.getString("key"))); break;
                case "scan": result.put("entries", data.scan(command.getString("prefix"))); break;
                case "revision": result.put("revision", data.revision()); break;
                case "progress": result.put("appliedIndex", data.appliedIndex()); break;
                default: throw new IllegalArgumentException("Unsupported read command");
            }
            return CompletableFuture.completedFuture(Message.valueOf(result.toJSONString()));
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    @Override public synchronized CompletableFuture<Message> applyTransaction(TransactionContext transaction) {
        try {
            LogEntryProto entry = transaction.getLogEntry();
            JSONObject command = JSON.parseObject(entry.getStateMachineLogEntry().getLogData().toStringUtf8());
            long term = entry.getTerm(), index = entry.getIndex();
            String requestId = command.getString("requestId");
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(command.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8)));
            String cached = data.requestResult(requestId);
            if (cached != null) {
                JSONObject envelope = JSON.parseObject(cached);
                if (!fingerprint.equals(envelope.getString("fingerprint"))) {
                    data.advance(term, index);
                    if (index > getLastAppliedTermIndex().getIndex()) updateLastAppliedTermIndex(TermIndex.valueOf(term, index));
                    JSONObject rejected = new JSONObject();
                    rejected.put("error", "Request ID reused for a different command");
                    return CompletableFuture.completedFuture(Message.valueOf(rejected.toJSONString()));
                }
                data.advance(term, index);
                if (index > getLastAppliedTermIndex().getIndex()) updateLastAppliedTermIndex(TermIndex.valueOf(term, index));
                return CompletableFuture.completedFuture(Message.valueOf(envelope.getJSONObject("result").toJSONString()));
            }
            JSONObject result = new JSONObject();
            if (index <= data.appliedIndex()) {
                throw new IllegalStateException("Applied command has no durable result: " + requestId);
            }
            Map<String, String> puts = Map.of();
            Set<String> deletes = Set.of();
            boolean mutate = true;
            switch (command.getString("op")) {
                case "conditionalBatch": {
                    Map<String,String> expected = stringMap(command.getJSONObject("expected"));
                    boolean matched = true;
                    for (Map.Entry<String,String> condition : expected.entrySet()) {
                        if (!Objects.equals(data.get(condition.getKey()), condition.getValue())) { matched = false; break; }
                    }
                    result.put("matched", matched);
                    if (matched) {
                        puts = stringMap(command.getJSONObject("puts"));
                        deletes = new TreeSet<>(command.getJSONArray("deletes").toJavaList(String.class));
                    } else mutate = false;
                    break;
                }
                case "batch": {
                    puts = stringMap(command.getJSONObject("puts"));
                    deletes = new TreeSet<>(command.getJSONArray("deletes").toJavaList(String.class));
                    break;
                }
                case "delete": {
                    String key = command.getString("key");
                    result.put("matched", data.get(key) != null);
                    deletes = Set.of(key);
                    break;
                }
                case "deletePrefix": {
                    deletes = data.scan(command.getString("prefix")).keySet();
                    break;
                }
                case "cas": {
                    String key = command.getString("key");
                    boolean matched = Objects.equals(data.get(key), command.getString("expected"));
                    result.put("matched", matched);
                    if (matched) {
                        String replacement = command.getString("replacement");
                        puts = replacement == null ? Map.of() : Map.of(key, replacement);
                        deletes = replacement == null ? Set.of(key) : Set.of();
                    } else mutate = false;
                    break;
                }
                default: throw new IllegalArgumentException("Unsupported metadata command");
            }
            result.put("revision", data.revision() + (mutate ? 1 : 0));
            JSONObject envelope = new JSONObject();
            envelope.put("fingerprint", fingerprint); envelope.put("result", result);
            if (mutate) data.apply(puts, deletes, term, index, requestId, envelope.toJSONString());
            else data.advance(term, index, requestId, envelope.toJSONString());
            updateLastAppliedTermIndex(TermIndex.valueOf(term, index));
            return CompletableFuture.completedFuture(Message.valueOf(result.toJSONString()));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(MetadataStateMachine.class).error("Cannot apply committed metadata command at index {}", transaction.getLogEntry().getIndex(), error);
            return CompletableFuture.failedFuture(error);
        }
    }

    private static Map<String, String> stringMap(JSONObject source) {
        Map<String, String> result = new TreeMap<>();
        source.forEach((key, value) -> result.put(key, (String) value));
        return result;
    }
}
