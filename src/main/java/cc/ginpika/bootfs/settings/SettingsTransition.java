package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import com.alibaba.fastjson2.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Offline, retryable transition, executed before the resource store and node identity are opened. */
public final class SettingsTransition {
    private SettingsTransition() {}
    public static void checkLocalStorage(Path root) {
        if (Files.exists(root.resolve("kernel/raft-identity.json")) || Files.exists(root.resolve("kernel/dynamic/node.json")))
            throw new IllegalArgumentException("旧 Raft 节点请先通过备份恢复迁移到本地节点");
    }
    public static void checkIndependent(Path root) throws IOException {
        checkLocalStorage(root);
        Path file = root.resolve("kernel/cluster/node.json");
        if (!Files.exists(file)) return;
        JSONObject m = JSON.parseObject(Files.readString(file));
        if (m.getJSONObject("members").size()!=1 || !m.getJSONObject("links").isEmpty()
                || !m.getJSONObject("pending").isEmpty() || !m.getJSONArray("outbox").isEmpty())
            throw new IllegalArgumentException("请先离开集群并完成所有成员通知及配对，再修改节点地址");
    }
    public static void checkNoTasks(Map<String,String> entries) {
        if (entries.keySet().stream().anyMatch(k -> k.startsWith("/pending-") || k.startsWith("/prepared-replicas/") || k.startsWith("/replication-jobs/")))
            throw new IllegalArgumentException("存在未完成上传或复制任务，请完成或恢复任务后重试");
    }
    public static JSONObject apply(Path root, JSONObject state, SettingsDatabase settings) throws IOException {
        JSONObject pending = state.getJSONObject("pending");
        if (pending==null || pending.isEmpty()) { Files.deleteIfExists(root.resolve("kernel/settings-transition.json")); return state; }
        SettingsValues old = new SettingsValues(state.getJSONObject("values"));
        Map<String,Object> target = new LinkedHashMap<>(old.map()); target.putAll(pending);
        SettingsValues next = new SettingsValues(target);
        Path journal = root.resolve("kernel/settings-transition.json");
        boolean started = Files.exists(journal);
        try {
            checkLocalStorage(root);
            boolean endpointChange = !old.string("webEntrypoint").equals(next.string("webEntrypoint"));
            if (endpointChange) checkIndependent(root);
            Path metadata = root.resolve("kernel/metadata");
            // A RocksDB lock proves no application still has the resource database open.
            try (RocksMetadataStore store = new RocksMetadataStore(metadata)) {
                var entries = store.scan(""); checkNoTasks(entries);
                Map<String,String> puts = new TreeMap<>(); Set<String> deletes = new HashSet<>();
                String oldBucket="/s3/"+old.string("s3Bucket")+"/", newBucket="/s3/"+next.string("s3Bucket")+"/";
                for (var entry : entries.entrySet()) {
                    String k=entry.getKey(), v=entry.getValue();
                    if (!oldBucket.equals(newBucket) && k.startsWith(oldBucket)) {
                        String newKey=newBucket+k.substring(oldBucket.length());
                        JSONObject binding=JSON.parseObject(v); binding.put("key",k.substring(oldBucket.length()));
                        if (entries.containsKey(newKey) && !JSON.parseObject(entries.get(newKey)).equals(binding)) throw new IllegalArgumentException("目标桶已有同名绑定，未执行变更");
                        puts.put(newKey,binding.toJSONString()); deletes.add(k);
                    }
                    if (!oldBucket.equals(newBucket) && k.startsWith("/versions/s3/")) {
                        String[] parts=k.split("/",5);
                        String sourceKey=new String(Base64.getUrlDecoder().decode(parts[3]),java.nio.charset.StandardCharsets.UTF_8);
                        if (sourceKey.startsWith(oldBucket)) {
                            String targetKey=newBucket+sourceKey.substring(oldBucket.length());
                            String versionKey="/versions/s3/"+Base64.getUrlEncoder().withoutPadding().encodeToString(targetKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+parts[4];
                            JSONObject version=JSON.parseObject(v);
                            if (sourceKey.equals(version.getString("key"))) version.put("key",targetKey);
                            if (entries.containsKey(versionKey) && !JSON.parseObject(entries.get(versionKey)).equals(version)) throw new IllegalArgumentException("目标桶已有冲突历史绑定，未执行变更");
                            puts.put(versionKey,version.toJSONString()); deletes.add(k);
                        }
                    }
                    if (endpointChange) {
                        String updated=remap(k,v,old.string("webEntrypoint"),next.string("webEntrypoint"));
                        if (!updated.equals(v)) puts.put(k,updated);
                    }
                }
                if (!started) {
                    Files.writeString(journal,pending.toJSONString(),StandardOpenOption.CREATE_NEW,StandardOpenOption.SYNC);
                    DurableFiles.syncDirectory(journal.getParent()); started=true;
                }
                store.writeBatch(puts,deletes);
                if (endpointChange) {
                    Path manifest=root.resolve("kernel/cluster/node.json");
                    if (Files.exists(manifest)) {
                        JSONObject m=JSON.parseObject(Files.readString(manifest));
                        m.put("endpoint",next.string("webEntrypoint"));
                        m.getJSONObject("members").getJSONObject(m.getString("nodeId")).put("url",next.string("webEntrypoint"));
                        Path stage=Files.createTempFile(manifest.getParent(),".address-",".json");
                        try {
                            Files.setPosixFilePermissions(stage,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                            Files.writeString(stage,m.toJSONString(),StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.SYNC);
                            Files.move(stage,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                            DurableFiles.syncDirectory(manifest.getParent());
                        } finally { Files.deleteIfExists(stage); }
                    }
                }
            }
            state.put("values",next.map()); state.remove("pending"); state.remove("transitionError");
            state.put("revision",state.getLongValue("revision")+1); settings.write(state);
            Files.deleteIfExists(journal); DurableFiles.syncDirectory(journal.getParent());
            return state;
        } catch (IllegalArgumentException failure) {
            if (started) throw new IOException("Settings transition interrupted; retain original files and retry startup",failure);
            state.remove("pending"); state.put("transitionError",failure.getMessage()); settings.write(state); return state;
        }
    }
    private static String remap(String key,String raw,String old,String next) {
        if (key.startsWith("/cluster/node/") && raw.equals(old)) return next;
        if (key.startsWith("/files/") && raw.startsWith(old+"/f/")) return next+raw.substring(old.length());
        if (key.startsWith("/versions/resource/")) {
            JSONObject version=JSON.parseObject(raw);
            if (version.getString("value")!=null) version.put("value",remap("/documents/history",version.getString("value"),old,next));
            return version.toJSONString();
        }
        if (key.startsWith("/documents/") || key.startsWith("/nodes/")) {
            JSONObject doc=JSON.parseObject(raw);
            for (String field:List.of("url","poster","thumbUrl","resources")) {
                Object value=doc.get(field);
                if (value instanceof String s) doc.put(field,url(s,old,next));
                else if (value instanceof JSONArray array) for (int i=0;i<array.size();i++) if (array.get(i) instanceof String s) array.set(i,url(s,old,next));
            }
            return doc.toJSONString();
        }
        return raw;
    }
    private static String url(String s,String old,String next) {
        return s.startsWith(old+"/") && s.substring(old.length()).matches("/(f|p|thumb|hls)/.*") ? next+s.substring(old.length()) : s;
    }
}
