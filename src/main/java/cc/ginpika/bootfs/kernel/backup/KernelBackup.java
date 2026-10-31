package cc.ginpika.bootfs.kernel.backup;

import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline, checksum-verified portable backup. Source DB locks are held until final publication. */
public final class KernelBackup {
    private KernelBackup() {}
    private static void id(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid resource or node identity");
    }
    private static Path safe(Path root, String name) throws IOException {
        Path relative = Path.of(name);
        if (relative.isAbsolute() || name.isBlank() || name.contains("\\") || !relative.normalize().equals(relative)
                || relative.startsWith("..")) throw new IOException("Unsafe backup path: " + name);
        Path path = root.resolve(relative);
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symlinks are not supported in backup paths");
        }
        return path;
    }
    /** Resolve parent aliases before checking source/destination overlap, without creating anything. */
    private static Path destination(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (absolute.getFileName() == null) throw new IOException("Destination must be a new directory");
        Path ancestor = absolute.getParent(); Deque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) { missing.push(ancestor.getFileName()); ancestor = ancestor.getParent(); }
        Path resolved = ancestor.toRealPath();
        while (!missing.isEmpty()) resolved = resolved.resolve(missing.pop());
        return resolved.resolve(absolute.getFileName());
    }
    private static void write(Path path, String value) throws IOException {
        Files.writeString(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
    }
    private static boolean deleted(Map<String,String> entries, JSONObject resource) {
        for (String field : List.of("uuid", "parent", "copyOf")) {
            String related = resource.getString(field);
            if (related != null && entries.containsKey("/tombstones/" + related)) return true;
        }
        return false;
    }

    public static void export(List<Path> sources, Path output, boolean writesStopped) throws IOException {
        if (!writesStopped || sources.isEmpty()) throw new IOException("Stop all writers and supply every node root with --writes-stopped");
        output = destination(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup destination already exists");
        List<RocksMetadataStore> stores = new ArrayList<>();
        Path staging = null;
        try {
            Map<String,Path> roots = new TreeMap<>();
            NavigableMap<String,String> entries = null;
            long revision = -1;
            for (Path source : sources) {
                Path root = source.toRealPath();
                if (output.startsWith(root)) throw new IOException("Backup must be outside every source root");
                String node = Files.readString(root.resolve("uuid")).trim(); id(node);
                if (roots.putIfAbsent(node, root) != null) throw new IOException("Duplicate source node identity");
                Path metadata;
                Path dynamic = safe(root, "kernel/dynamic/node.json");
                if (Files.exists(dynamic)) {
                    JSONObject identity = JSON.parseObject(Files.readString(dynamic));
                    if (!"ACTIVE".equals(identity.getString("phase"))) throw new IOException("Complete the dynamic node transition before backup");
                    String generation = identity.getString("generation"); id(generation);
                    metadata = safe(root, "kernel/dynamic/" + generation + "/metadata");
                } else metadata = safe(root, "kernel/metadata");
                if (!Files.isRegularFile(metadata.resolve("CURRENT"))) throw new IOException("Source has no initialized kernel metadata");
                RocksMetadataStore store = new RocksMetadataStore(metadata); stores.add(store);
                NavigableMap<String,String> state = store.scan("");
                if (entries == null) { entries = state; revision = store.revision(); }
                else if (revision != store.revision() || !entries.equals(state)) {
                    throw new IOException("Node metadata differs: catch up all followers and stop all writers before export");
                }
            }
            for (String key : entries.keySet()) {
                if (key.startsWith("/pending-publications/") || key.startsWith("/pending-albums/") || key.startsWith("/pending-replicas/")) {
                    throw new IOException("Unfinished publication: recover or explicitly delete it before backup: " + key);
                }
                if (key.startsWith("/nodes/") && key.contains("/catalog/")) {
                    String node = key.split("/")[2];
                    if (!roots.containsKey(node)) throw new IOException("Missing source node root: " + node);
                }
            }
            Path parent = output.getParent(); Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, ".bootfs-backup-");
            Files.createDirectory(staging.resolve("blobs"));
            JSONObject metadata = new JSONObject(); metadata.put("formatVersion", 1); metadata.put("revision", revision); metadata.put("entries", entries);
            write(staging.resolve("metadata.json"), metadata.toJSONString());
            JSONArray files = new JSONArray();
            Map<String,JSONObject> inventory = new HashMap<>();
            for (Map.Entry<String,Path> source : roots.entrySet()) {
                Path data = safe(source.getValue(), "data");
                if (!Files.isDirectory(data)) throw new IOException("Missing data directory for node " + source.getKey());
                try (var walk = Files.walk(data)) {
                    for (Path file : walk.sorted().toList()) {
                        if (Files.isSymbolicLink(file)) throw new IOException("Data contains a symlink: " + file);
                        if (Files.isDirectory(file)) continue;
                        if (!Files.isRegularFile(file)) throw new IOException("Unsupported data entry: " + file);
                        String hash = ContentHash.sha256(file);
                        Path blob = staging.resolve("blobs").resolve(hash);
                        if (!Files.exists(blob)) { Files.copy(file, blob); DurableFiles.syncFile(blob); }
                        if (!hash.equals(ContentHash.sha256(blob))) throw new IOException("Source changed during backup");
                        JSONObject entry = new JSONObject(); entry.put("nodeId", source.getKey());
                        entry.put("path", data.relativize(file).toString()); entry.put("sha256", hash); entry.put("size", Files.size(file));
                        files.add(entry); inventory.put(source.getKey() + "/" + entry.getString("path"), entry);
                    }
                }
            }
            validateInventory(entries, inventory);
            JSONObject manifest = new JSONObject(); manifest.put("formatVersion", 1); manifest.put("sourceRevision", revision);
            manifest.put("nodes", roots.keySet()); manifest.put("metadataSha256", ContentHash.sha256(staging.resolve("metadata.json"))); manifest.put("files", files);
            write(staging.resolve("manifest.json"), manifest.toJSONString());
            write(staging.resolve("manifest.sha256"), ContentHash.sha256(staging.resolve("manifest.json")));
            DurableFiles.syncDirectory(staging.resolve("blobs")); DurableFiles.syncDirectory(staging);
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE); staging = null; DurableFiles.syncDirectory(parent);
        } finally {
            for (RocksMetadataStore store : stores) store.close();
            if (staging != null) DurableFiles.deleteTree(staging);
        }
    }

    private static void validateInventory(Map<String,String> entries, Map<String,JSONObject> inventory) throws IOException {
        Set<String> resources = new HashSet<>();
        for (Map.Entry<String,String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("/nodes/") || !key.contains("/catalog/")) continue;
            JSONObject resource = JSON.parseObject(entry.getValue());
            String uuid = resource.getString("uuid"), node = key.split("/")[2]; id(uuid); id(node);
            if (!key.equals("/nodes/" + node + "/catalog/" + uuid)) throw new IOException("Catalog key identity mismatch");
            if (deleted(entries, resource)) continue;
            JSONObject file = inventory.get(node + "/" + uuid);
            // Existing virtual HLS resources intentionally have no original byte object.
            if (file == null && resource.getLongValue("size") == 0 && "1".equals(resource.getString("hlsAvailable"))
                    && inventory.containsKey(node + "/hls/" + uuid + "/playlist.m3u8")) { resources.add(uuid); continue; }
            if (file == null || file.getLongValue("size") != resource.getLongValue("size")) throw new IOException("Missing or wrong-sized object: " + uuid);
            String rawMeta = entries.get("/files/" + uuid + "/meta");
            if (rawMeta != null) {
                String expected = JSON.parseObject(rawMeta).getString("sha256");
                if (expected != null && !expected.equals(file.getString("sha256"))) throw new IOException("Object checksum differs from authority: " + uuid);
            }
            resources.add(uuid);
        }
        for (Map.Entry<String,String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("/s3/")) {
                String uuid = JSON.parseObject(entry.getValue()).getString("uuid");
                if (!entries.containsKey("/tombstones/" + uuid) && !resources.contains(uuid)) throw new IOException("S3 resource has no backed-up catalog: " + uuid);
            }
            if (key.startsWith("/documents/full-text/")) {
                JSONObject doc = JSON.parseObject(entry.getValue());
                if (!doc.getBooleanValue("_bootfsDeleted") && !deleted(entries, doc) && !resources.contains(doc.getString("uuid"))) {
                    throw new IOException("Source document has no backed-up catalog: " + key);
                }
            }
        }
    }

    public static JSONObject verify(Path backup) throws IOException {
        backup = backup.toRealPath();
        Path manifestFile = safe(backup, "manifest.json");
        if (!ContentHash.sha256(manifestFile).equals(Files.readString(safe(backup, "manifest.sha256")).trim())) throw new IOException("Manifest checksum mismatch");
        JSONObject manifest = JSON.parseObject(Files.readString(manifestFile));
        if (manifest.getIntValue("formatVersion") != 1) throw new IOException("Unknown backup format");
        Path metadata = safe(backup, "metadata.json");
        if (!ContentHash.sha256(metadata).equals(manifest.getString("metadataSha256"))) throw new IOException("Metadata checksum mismatch");
        JSONObject source = JSON.parseObject(Files.readString(metadata));
        if (source.getIntValue("formatVersion") != 1 || source.getLongValue("revision") != manifest.getLongValue("sourceRevision")) throw new IOException("Metadata boundary mismatch");
        Map<String,JSONObject> inventory = new HashMap<>();
        for (Object raw : manifest.getJSONArray("files")) {
            JSONObject entry = (JSONObject)raw; String hash = entry.getString("sha256"), node = entry.getString("nodeId"); id(node);
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid blob checksum");
            safe(backup, entry.getString("path"));
            Path blob = safe(backup, "blobs/" + hash);
            if (Files.size(blob) != entry.getLongValue("size") || !hash.equals(ContentHash.sha256(blob))) throw new IOException("Corrupt or truncated blob: " + hash);
            if (inventory.putIfAbsent(node + "/" + entry.getString("path"), entry) != null) throw new IOException("Duplicate inventory path");
        }
        validateInventory(stringMap(source.getJSONObject("entries")), inventory);
        return manifest;
    }
    private static Map<String,String> stringMap(JSONObject json) throws IOException {
        Map<String,String> result = new TreeMap<>();
        for (String key : json.keySet()) {
            if (key.indexOf(0) >= 0 || !(json.get(key) instanceof String)) throw new IOException("Invalid metadata entry");
            result.put(key, json.getString(key));
        }
        return result;
    }

    /** Consolidate complete node inventories into one new seed. A new Raft cluster imports the same plan on all peers. */
    public static Path restore(Path backup, Path destination, String nodeId, String endpoint) throws IOException {
        id(nodeId);
        if (endpoint == null || !endpoint.matches("https?://[^/]+(?:/[^?#]*)?")) throw new IOException("Supply an absolute HTTP endpoint");
        endpoint = endpoint.replaceAll("/+$", "");
        backup = backup.toRealPath(); destination = destination(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Restore destination must be a new directory");
        JSONObject manifest = verify(backup);
        Map<String,String> original = stringMap(JSON.parseObject(Files.readString(backup.resolve("metadata.json"))).getJSONObject("entries"));
        Map<String,String> restored = remap(original, nodeId, endpoint);
        Path parent = destination.getParent(); Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".bootfs-restore-");
        try {
            Path data = staging.resolve("data"); Files.createDirectory(data);
            for (Object raw : manifest.getJSONArray("files")) {
                JSONObject entry = (JSONObject)raw;
                String relative = entry.getString("path"), owner = entry.getString("nodeId");
                boolean activePath = activePath(original, owner, relative);
                Path target = safe(data, activePath ? relative : ".recovered/" + owner + "/" + relative);
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    if (!entry.getString("sha256").equals(ContentHash.sha256(target))) throw new IOException("Node inventories conflict at " + entry.getString("path"));
                } else {
                    Files.copy(backup.resolve("blobs").resolve(entry.getString("sha256")), target);
                    DurableFiles.syncFile(target);
                    if (!entry.getString("sha256").equals(ContentHash.sha256(target))) throw new IOException("Content changed while restoring");
                }
            }
            write(staging.resolve("uuid"), nodeId);
            JSONObject plan = new JSONObject(); plan.put("formatVersion", 1); plan.put("sourceRevision", manifest.getLongValue("sourceRevision"));
            plan.put("backupSha256", Files.readString(backup.resolve("manifest.sha256")).trim()); plan.put("entries", restored);
            write(staging.resolve("restore-plan.json"), plan.toJSONString());
            try (var walk = Files.walk(staging)) {
                for (Path directory : walk.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) DurableFiles.syncDirectory(directory);
            }
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); staging = null; DurableFiles.syncDirectory(parent);
            return destination.resolve("restore-plan.json");
        } finally { if (staging != null) DurableFiles.deleteTree(staging); }
    }

    private static boolean activePath(Map<String,String> original, String node, String path) {
        String id;
        if (path.startsWith("thumb/")) {
            String name = path.substring("thumb/".length());
            id = name.endsWith(".webp") ? name.substring(0,name.length()-5) : name;
        } else if (path.startsWith("hls/")) {
            String[] parts = path.split("/"); if (parts.length < 3) return false; id = parts[1];
        } else if (path.indexOf('/') < 0) {
            if (path.equals("tv-playlist.json")) return true;
            id = path;
        } else return false;
        String raw = original.get("/nodes/" + node + "/catalog/" + id);
        return raw != null && !deleted(original, JSON.parseObject(raw));
    }

    private static Map<String,String> remap(Map<String,String> original, String node, String endpoint) throws IOException {
        Map<String,String> result = new TreeMap<>();
        Set<String> oldEndpoints = new HashSet<>();
        original.forEach((key,value) -> { if (key.startsWith("/cluster/node/")) oldEndpoints.add(value.replaceAll("/+$", "")); });
        for (Map.Entry<String,String> entry : original.entrySet()) {
            String key = entry.getKey();
            if (!(key.startsWith("/nodes/") || key.startsWith("/files/") || key.startsWith("/cluster/")
                    || key.startsWith("/pending-") || key.startsWith("/prepared-replicas/") || key.startsWith("/cleanup/")
                    || key.startsWith("/replication-jobs/") || key.startsWith("/migration/") || key.startsWith("/recovery/"))) {
                String value = entry.getValue();
                if (key.startsWith("/documents/")) {
                    value = remapDocument(JSON.parseObject(value), node, oldEndpoints, endpoint).toJSONString();
                }
                if (key.startsWith("/versions/")) {
                    JSONObject record = JSON.parseObject(value);
                    if ("resource".equals(record.getString("namespace"))) {
                        record.put("value", remapDocument(JSON.parseObject(record.getString("value")), node, oldEndpoints, endpoint).toJSONString());
                        value = record.toJSONString();
                    }
                }
                result.put(key, value);
            }
        }
        for (Map.Entry<String,String> entry : original.entrySet()) {
            if (!entry.getKey().startsWith("/nodes/") || !entry.getKey().contains("/catalog/")) continue;
            JSONObject file = JSON.parseObject(entry.getValue());
            if (deleted(original, file)) continue;
            String uuid = file.getString("uuid"); id(uuid); file.remove("path");
            String key = "/nodes/" + node + "/catalog/" + uuid;
            if (result.containsKey(key) && !JSON.parseObject(result.get(key)).equals(file)) throw new IOException("Conflicting catalogs for " + uuid);
            result.put(key, file.toJSONString()); result.put("/files/" + uuid, endpoint + "/f/" + uuid);
            JSONObject meta = original.containsKey("/files/" + uuid + "/meta") ? JSON.parseObject(original.get("/files/" + uuid + "/meta")) : new JSONObject();
            meta.put("size", file.getLongValue("size")); meta.remove("durableNodes"); meta.put("requiredCopies", 1);
            result.put("/files/" + uuid + "/meta", meta.toJSONString());
            String copyOf = file.getString("copyOf");
            if (copyOf != null) {
                result.put("/files/" + copyOf + "/replicas/" + uuid, endpoint + "/f/" + uuid);
                result.put("/files/" + copyOf + "/replicas/" + uuid + "/meta", meta.toJSONString());
            }
        }
        result.put("/cluster/node/" + node, endpoint);
        return result;
    }
    private static JSONObject remapDocument(JSONObject doc, String node, Set<String> oldEndpoints, String endpoint) {
        if (doc.containsKey("contextUuid")) doc.put("contextUuid", node);
        for (String field : List.of("poster", "thumbUrl", "url", "resources")) {
            Object content = doc.get(field);
            if (content instanceof String) doc.put(field, remapUrl((String)content, oldEndpoints, endpoint));
            else if (content instanceof JSONArray) {
                JSONArray urls = new JSONArray();
                for (Object url : (JSONArray)content) urls.add(url instanceof String ? remapUrl((String)url, oldEndpoints, endpoint) : url);
                doc.put(field, urls);
            }
        }
        return doc;
    }
    private static String remapUrl(String url, Set<String> oldEndpoints, String endpoint) {
        for (String old : oldEndpoints) {
            if (!old.isBlank() && url.startsWith(old + "/") && url.substring(old.length()).matches("/(?:f|p|thumb|hls)/.*")) return endpoint + url.substring(old.length());
        }
        return url;
    }
}
