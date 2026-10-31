package cc.ginpika.bootfs.kernel.cluster;

import com.alibaba.fastjson2.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A deterministic, non-destructive merge plan for a frozen independent node. */
public final class ClusterMerge {
    public record Plan(Map<String,String> expected, Map<String,String> puts, Map<String,String> renamedObjects) {}
    private ClusterMerge() {}
    private static String versionPrefix(String namespace, String key) {
        return "/versions/" + namespace + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)) + "/";
    }
    public static NavigableMap<String,String> versions(Map<String,String> state, String namespace, String key) {
        String prefix = versionPrefix(namespace, key);
        NavigableMap<String,String> result = new TreeMap<>();
        state.forEach((k,v) -> { if (k.startsWith(prefix)) result.put(k,v); }); return result;
    }
    private static void append(Map<String,String> state, Map<String,String> puts, String namespace, String key, String value, String cluster) {
        String prefix = versionPrefix(namespace, key);
        long version = 1;
        for (String existing : state.keySet()) if (existing.startsWith(prefix)) version = Math.max(version, Long.parseLong(existing.substring(prefix.length())) + 1);
        for (String existing : puts.keySet()) if (existing.startsWith(prefix)) version = Math.max(version, Long.parseLong(existing.substring(prefix.length())) + 1);
        JSONObject record = new JSONObject(); record.put("version", version); record.put("key", key); record.put("namespace", namespace);
        record.put("sourceCluster", cluster); record.put("value", value);
        puts.put(prefix + String.format(Locale.ROOT, "%020d", version), record.toJSONString());
    }
    public static Plan plan(Map<String,String> target, Map<String,String> source, String sourceCluster, String mergeId) throws IOException {
        for (String key : source.keySet()) {
            if (key.startsWith("/pending-replicas/")) {
                throw new IOException("Finish legacy replica receipt recovery before connecting an independent node");
            }
        }
        Map<String,String> renamed = new TreeMap<>();
        Set<String> ids = new TreeSet<>();
        source.keySet().forEach(key -> {
            if (key.startsWith("/documents/")) ids.add(key.substring(key.lastIndexOf('/') + 1));
            if (key.startsWith("/nodes/") && key.contains("/catalog/")) ids.add(key.substring(key.lastIndexOf('/') + 1));
            if (key.startsWith("/tombstones/")) ids.add(key.substring("/tombstones/".length()));
            if (key.startsWith("/pending-publications/") || key.startsWith("/pending-albums/")) ids.add(key.substring(key.lastIndexOf('/')+1));
        });
        for (String id : ids) {
            boolean collision = target.containsKey("/files/" + id) || target.containsKey("/documents/full-text/" + id) || target.containsKey("/tombstones/" + id);
            if (collision) renamed.put(id, UUID.nameUUIDFromBytes((mergeId + ":" + id).getBytes(StandardCharsets.UTF_8)).toString());
        }
        Map<String,String> puts = new TreeMap<>();
        // Existing source histories precede the source's current head. The newest numeric version stays the head.
        for (Map.Entry<String,String> entry : source.entrySet()) if (entry.getKey().startsWith("/versions/")) {
            JSONObject record = JSON.parseObject(entry.getValue());
            String namespace = record.getString("namespace"), key = record.getString("key");
            if (versions(target, namespace, key).isEmpty() && versions(puts, namespace, key).isEmpty()) {
                String baseline = "resource".equals(namespace) ? target.get("/documents/full-text/" + key) : target.get(key);
                if (baseline != null) append(target, puts, namespace, key, baseline, "destination");
            }
            String value = record.getString("value");
            if (!"metadata".equals(namespace)) value = rewrite(JSON.parse(value), renamed).toString();
            append(target, puts, namespace, key, value, record.getString("sourceCluster"));
        }
        for (Map.Entry<String,String> entry : source.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key.startsWith("/cluster/node/")) {
                if (target.containsKey(key) && !target.get(key).equals(value)) throw new IOException("Node identity is already registered in the destination");
            } else if (key.startsWith("/cluster/") || key.startsWith("/recovery/") || key.startsWith("/migration/")
                    || (key.startsWith("/pending-") && !key.startsWith("/pending-publications/") && !key.startsWith("/pending-albums/")) || key.startsWith("/prepared-replicas/") || key.startsWith("/cleanup/")
                    || key.startsWith("/versions/")) continue;
            String mappedKey = key;
            if (key.startsWith("/documents/") || (key.startsWith("/nodes/") && key.contains("/catalog/")) || key.startsWith("/tombstones/") || key.startsWith("/pending-publications/") || key.startsWith("/pending-albums/") || key.startsWith("/replication-jobs/")) {
                int separator = key.lastIndexOf('/');
                mappedKey = key.substring(0,separator+1) + renamed.getOrDefault(key.substring(separator+1), key.substring(separator+1));
            } else if (key.startsWith("/files/")) {
                String[] segments = key.split("/", -1);
                if (segments.length > 2) segments[2] = renamed.getOrDefault(segments[2],segments[2]);
                if (segments.length > 4 && "replicas".equals(segments[3])) segments[4] = renamed.getOrDefault(segments[4],segments[4]);
                mappedKey = String.join("/", segments);
            }
            if (key.startsWith("/pending-publications/") || key.startsWith("/pending-albums/") || key.startsWith("/replication-jobs/")) {
                JSONObject intent = (JSONObject)rewrite(JSON.parseObject(value), renamed);
                if (intent.containsKey("file")) intent.put("file", rewrite(intent.getJSONObject("file"), renamed));
                if (intent.containsKey("receipts")) intent.put("receipts", new JSONObject());
                JSONObject binding = intent.getJSONObject("s3Binding");
                if (binding != null && binding.getString("expected") != null) binding.put("expected", rewrite(JSON.parseObject(binding.getString("expected")),renamed).toString());
                value = intent.toJSONString();
            } else if (key.startsWith("/documents/") || key.startsWith("/s3/") || (key.startsWith("/files/") && key.endsWith("/meta")) || (key.startsWith("/nodes/") && key.contains("/catalog/"))) {
                value = rewrite(JSON.parseObject(value), renamed).toString();
            } else if (key.startsWith("/files/")) value = rewriteUrl(value, renamed);
            if (key.startsWith("/s3/") && ((target.containsKey(key) && !target.get(key).equals(value)) || !versions(puts, "s3", key).isEmpty())) {
                if (target.containsKey(key) && versions(target, "s3", key).isEmpty() && versions(puts, "s3", key).isEmpty()) append(target, puts, "s3", key, target.get(key), "destination");
                append(target, puts, "s3", key, value, sourceCluster);
            }
            if (key.startsWith("/documents/full-text/") && renamed.containsKey(key.substring("/documents/full-text/".length()))) {
                String originalId = key.substring("/documents/full-text/".length());
                if (versions(target, "resource", originalId).isEmpty() && versions(puts, "resource", originalId).isEmpty() && target.containsKey(key)) append(target, puts, "resource", originalId, target.get(key), "destination");
                append(target, puts, "resource", originalId, value, sourceCluster);
            }
            if (!key.startsWith("/s3/") && target.containsKey(mappedKey) && !target.get(mappedKey).equals(value)) {
                if (versions(target, "metadata", mappedKey).isEmpty() && versions(puts, "metadata", mappedKey).isEmpty()) append(target, puts, "metadata", mappedKey, target.get(mappedKey), "destination");
                append(target, puts, "metadata", mappedKey, value, sourceCluster);
            } else if (!versions(puts, "metadata", mappedKey).isEmpty()) append(target, puts, "metadata", mappedKey, value, sourceCluster);
            puts.put(mappedKey, value);
        }
        Set<String> owners = new TreeSet<>();
        source.keySet().forEach(key -> { if (key.startsWith("/cluster/node/")) owners.add(key.substring("/cluster/node/".length())); });
        for (String owner : owners) for (Map.Entry<String,String> rename : renamed.entrySet()) {
            puts.put("/upload-aliases/" + owner + "/" + rename.getKey(),rename.getValue());
        }
        Map<String,String> expected = new TreeMap<>();
        for (String key : puts.keySet()) expected.put(key, target.get(key));
        for (String id : ids) expected.put("/tombstones/" + renamed.getOrDefault(id, id), target.get("/tombstones/" + renamed.getOrDefault(id, id)));
        return new Plan(expected, puts, renamed);
    }
    private static Object rewrite(Object value, Map<String,String> renamed) {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            ((JSONObject)value).forEach((key, item) -> {
                if (Set.of("uuid", "parent", "copyOf").contains(key) && item instanceof String) result.put(key, renamed.getOrDefault(item, (String)item));
                else if (Set.of("resources", "poster", "thumbUrl", "url", "legacyImageHost").contains(key)) result.put(key, rewrite(item, renamed));
                else result.put(key, item);
            }); return result;
        }
        if (value instanceof JSONArray) { JSONArray result = new JSONArray(); for (Object item : (JSONArray)value) result.add(rewrite(item, renamed)); return result; }
        return value instanceof String ? rewriteUrl((String)value, renamed) : value;
    }
    private static String rewriteUrl(String value, Map<String,String> renamed) {
        for (Map.Entry<String,String> entry : renamed.entrySet()) {
            for (String prefix : List.of("/f/", "/p/", "/thumb/", "/hls/")) {
                String match = prefix + entry.getKey(); int at = value.indexOf(match);
                if (at >= 0 && (at + match.length() == value.length() || "/?".indexOf(value.charAt(at + match.length())) >= 0)) {
                    return value.substring(0, at) + prefix + entry.getValue() + value.substring(at + match.length());
                }
            }
        }
        return value;
    }
}
