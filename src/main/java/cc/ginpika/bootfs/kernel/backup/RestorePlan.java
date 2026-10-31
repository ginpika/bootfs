package cc.ginpika.bootfs.kernel.backup;

import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** New-cluster-only logical import. Consensus positions and client sessions are never imported. */
public final class RestorePlan {
    private RestorePlan() {}
    public static void apply(MetadataStore store, Path file) throws IOException {
        String digest = ContentHash.sha256(file);
        String marker = "/recovery/plan";
        JSONObject plan = JSON.parseObject(Files.readString(file));
        if (plan.getIntValue("formatVersion") != 1) throw new IOException("Unknown restore plan version");
        Map<String,String> entries = new TreeMap<>();
        plan.getJSONObject("entries").forEach((key,value) -> {
            if (key.indexOf(0) >= 0 || key.startsWith("/recovery/") || !(value instanceof String)) {
                throw new IllegalArgumentException("Invalid restore metadata");
            }
            entries.put(key, (String)value);
        });
        String complete = digest + ":complete", claimed = digest + ":claimed";
        if (complete.equals(store.get(marker))) return;
        String previous = store.get(marker);
        if (previous != null && !claimed.equals(previous)) throw new IOException("Cluster belongs to another restore plan");
        if (previous == null && !store.scan("").isEmpty()) throw new IOException("Restore requires a new, empty metadata cluster");
        if (!store.compareAndSet(marker, null, claimed) && !claimed.equals(store.get(marker))) {
            if (complete.equals(store.get(marker))) return;
            throw new IOException("Restore claim conflicts");
        }
        // Reject another program's writes, including a peer launched without this same plan.
        for (Map.Entry<String,String> entry : store.scan("").entrySet()) {
            if (entry.getKey().equals(marker)) continue;
            if (!Objects.equals(entries.get(entry.getKey()), entry.getValue())) throw new IOException("Concurrent state conflicts with restore");
        }
        Map<String,String> expected = new TreeMap<>();
        for (String key : entries.keySet()) expected.put(key, store.get(key));
        expected.put(marker, claimed); entries.put(marker, complete);
        if (!store.compareAndWrite(expected, entries, Set.of()) && !complete.equals(store.get(marker))) {
            throw new IOException("Restore state changed during import; refusing to overwrite it");
        }
    }
}
