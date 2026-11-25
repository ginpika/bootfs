package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.ResourceUrls;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import cc.ginpika.bootfs.service.hls.HlsFileService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

@Service
@RequiredArgsConstructor
public class DatabaseRebuildService {
    private static final String DOCUMENTS = "/documents/full-text/";
    private final TfsConfig config;
    private final LocalFileInventory inventory;
    private final FileCatalogRepository catalogRepository;
    private final MetadataStore metadata;
    private final EmbeddedSearchEngine search;
    private final HlsFileService hls;
    private final ObjectKeyBootstrap objectKeys;
    private final ResourceWriteLock writeLock;

    public int rebuild() throws IOException {
        synchronized (writeLock) {
            catalogRepository.finishRebuild();
            Path db = Path.of(config.getConfig()).toAbsolutePath();
            Map<String, JSONObject> source = FileCatalogRepository.readSource(db);
            String node = inventory.uuid;
            String catalog = "/nodes/" + node + "/catalog/";
            long revision = metadata.revision();
            NavigableMap<String, String> existing = metadata.scan("");
            if (existing.keySet().stream().anyMatch(key -> key.startsWith("/pending-publications/" + node + "/")
                    || key.startsWith("/pending-albums/" + node + "/") || key.startsWith("/prepared-replicas/" + node + "/"))) {
                throw new IllegalStateException("存在未完成上传或复制任务，请完成或恢复任务后重试");
            }
            Map<String, Set<String>> generations = contentGenerations(source, existing);
            Map<String, FileObject> files = readFiles(source, existing, catalog, generations);
            Map<String, List<FileObject>> children = new HashMap<>();
            files.values().forEach(file -> {
                if (file.getParent() != null) children.computeIfAbsent(file.getParent(), ignored -> new ArrayList<>()).add(file);
            });
            Map<String, String> puts = new TreeMap<>();
            Set<String> deletes = new TreeSet<>();
            JSONObject portable = new JSONObject();
            for (FileObject file : files.values()) {
                String id = file.getUuid();
                JSONObject saved = source.getOrDefault(id, new JSONObject());
                JSONObject document = document(file, saved.getJSONObject("_document"),
                        existing.get(DOCUMENTS + id), children.getOrDefault(id, List.of()));
                search.validateDocument(document);
                JSONObject meta = resourceMetadata(file, saved.getJSONObject("_metadata"), existing.get("/files/" + id + "/meta"));
                JSONObject record = JSONObject.from(file);
                record.remove("path");
                puts.put(catalog + id, record.toJSONString());
                puts.put(DOCUMENTS + id, document.toJSONString());
                putRoutes(puts, file, meta);
                Set<String> contentIds = generations.getOrDefault(id, Set.of());
                for (String contentId : contentIds) {
                    puts.put("/resource-content/" + node + "/" + id + "/" + contentId, "content");
                }

                JSONObject portableMeta = JSONObject.parseObject(meta.toJSONString());
                portableMeta.remove("durableNodes");
                portableMeta.remove("requiredCopies");
                record.put("_document", document);
                record.put("_metadata", portableMeta);
                if (!contentIds.isEmpty()) record.put("_contentIds", contentIds);
                portable.put(id, record);
            }
            Set<String> missing = new HashSet<>();
            entriesUnder(existing, catalog).forEach(entry -> {
                String key = entry.getKey();
                String id = key.substring(catalog.length());
                if (files.containsKey(id)) return;
                missing.add(id);
                deletes.addAll(List.of(key, DOCUMENTS + id, "/documents/image-host/" + id, "/files/" + id, "/files/" + id + "/meta"));
                String copyOf = JSONObject.parseObject(entry.getValue()).getString("copyOf");
                if (copyOf != null) {
                    String replica = "/files/" + copyOf + "/replicas/" + id;
                    deletes.addAll(List.of(replica, replica + "/meta"));
                }
            });
            updateBindings(source, existing, catalog, portable, puts, deletes, missing);
            puts.put("/nodes/" + node + "/migration/db-json-v1", "complete");
            Path staged = Files.createTempFile(db.getParent(), ".db-rebuild-", ".json");
            boolean submitted = false;
            try {
                Files.writeString(staged, portable.toJSONString(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
                DurableFiles.syncDirectory(db.getParent());
                puts.put(catalogRepository.rebuildSnapshotKey(), staged.getFileName().toString());
                submitted = true;
                if (!metadata.compareAndWrite(revision, puts, deletes)) {
                    submitted = false;
                    throw new IllegalStateException("资源已变更，请重试重建");
                }
                inventory.reload();
                search.rebuild();
                objectKeys.bootstrapExistingBindings();
                return files.size();
            } finally {
                if (!submitted) Files.deleteIfExists(staged);
            }
        }
    }

    private Map<String, Set<String>> contentGenerations(Map<String, JSONObject> source, NavigableMap<String, String> existing) {
        Map<String, Set<String>> generations = new HashMap<>();
        source.forEach((id, record) -> {
            JSONArray saved = record.getJSONArray("_contentIds");
            if (saved != null) generations.put(id, new TreeSet<>(saved.toJavaList(String.class)));
        });
        String prefix = "/resource-content/" + inventory.uuid + "/";
        entriesUnder(existing, prefix).forEach(entry -> {
            String[] ids = entry.getKey().substring(prefix.length()).split("/", -1);
            if (ids.length == 2 && validId(ids[0]) && validId(ids[1])) {
                generations.computeIfAbsent(ids[0], ignored -> new TreeSet<>()).add(ids[1]);
            }
        });
        return generations;
    }

    private Map<String, FileObject> readFiles(Map<String, JSONObject> source, NavigableMap<String, String> existing,
                                              String catalog, Map<String, Set<String>> generations) throws IOException {
        Map<String, JSONObject> candidates = new TreeMap<>(source);
        Set<String> foreign = new HashSet<>();
        Set<String> contentIds = new HashSet<>();
        generations.values().forEach(contentIds::addAll);
        source.values().forEach(record -> contentIds.add(record.getString("contentObjectId")));
        entriesUnder(existing, "/nodes/").forEach(entry -> {
            String key = entry.getKey();
            if (key.startsWith(catalog)) candidates.put(key.substring(catalog.length()), JSONObject.parseObject(entry.getValue()));
            else if (key.contains("/catalog/")) foreign.add(key.substring(key.lastIndexOf('/') + 1));
        });
        candidates.values().forEach(record -> contentIds.add(record.getString("contentObjectId")));

        Path data = Path.of(config.getPathPrefix());
        try (var paths = Files.list(data)) {
            paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(id -> validId(id) && !contentIds.contains(id))
                    .forEach(id -> candidates.putIfAbsent(id, JSONObject.of("uuid", id)));
        }
        for (String id : hls.localFolders()) {
            if (!contentIds.contains(id)) candidates.putIfAbsent(id, JSONObject.of("uuid", id));
        }
        Map<String, FileObject> files = new TreeMap<>();
        for (var entry : candidates.entrySet()) {
            String id = entry.getKey();
            if (foreign.contains(id) && !existing.containsKey(catalog + id)) continue;
            FileObject file = entry.getValue().toJavaObject(FileObject.class);
            String contentId = file.getContentObjectId() == null ? id : file.getContentObjectId();
            if (!validId(id) || !id.equals(file.getUuid()) || !validId(contentId)) throw new IOException("Invalid catalog identity");
            if (Arrays.asList(id, file.getParent(), file.getCopyOf(), contentId).stream().filter(Objects::nonNull)
                    .anyMatch(key -> existing.containsKey("/tombstones/" + key))) continue;
            Path path = data.resolve(contentId);
            BasicFileAttributes payload = sourceAttributes(path);
            BasicFileAttributes playlist = sourceAttributes(hls.playlistPath(id));
            if (payload == null && playlist == null) continue;
            if (!contentId.equals(id)) generations.computeIfAbsent(id, ignored -> new TreeSet<>()).add(contentId);
            BasicFileAttributes attributes = payload == null ? playlist : payload;
            if (file.getFileName() == null || file.getFileName().isBlank()) file.setFileName(id);
            if (file.getCreatedAt() == null) file.setCreatedAt(attributes.creationTime().toMillis());
            file.setPath(payload == null ? null : path.toString());
            file.setSize(payload == null ? 0L : payload.size());
            file.setHlsAvailable(playlist == null ? null : "1");
            Path thumb = data.resolve("thumb");
            file.setThumbAvailable(Files.isDirectory(thumb, NOFOLLOW_LINKS)
                    && Files.isRegularFile(thumb.resolve(id + ".webp"), NOFOLLOW_LINKS) ? "1" : null);
            files.put(id, file);
        }
        return files;
    }

    private static BasicFileAttributes sourceAttributes(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return attributes.isRegularFile() ? attributes : null;
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private JSONObject document(FileObject file, JSONObject saved, String live, List<FileObject> children) {
        JSONObject doc = JSONObject.from(InventoryReconciler.catalogDocument(file, inventory.uuid));
        if (saved != null) doc.putAll(saved);
        JSONObject existing = live == null ? null : JSONObject.parseObject(live);
        if (existing != null && !existing.getBooleanValue("_bootfsDeleted")) doc.putAll(existing);
        doc.remove("_bootfsDeleted");
        doc.put("uuid", file.getUuid());
        doc.put("contextUuid", inventory.uuid);
        doc.put("size", file.getSize());
        doc.put("fileName", file.getFileName());
        doc.put("fileCreatedAt", file.getCreatedAt());
        doc.put("parent", file.getParent());
        doc.put("copyOf", file.getCopyOf());
        doc.put("contentObjectId", file.getContentObjectId());
        doc.put("thumbAvailable", file.getThumbAvailable());
        doc.put("hlsAvailable", file.getHlsAvailable());
        if ("1".equals(file.getAlbumAvailable())) {
            JSONArray pages = doc.getJSONArray("resources");
            if (pages == null || pages.isEmpty()) {
                pages = albumPages(file.getUuid(), children);
                doc.put("resources", pages);
            }
            if (!pages.isEmpty()) {
                doc.put("poster", pages.get(0));
                doc.put("thumbUrl", pages.get(0));
            }
        }
        return ResourceUrls.normalizeDocument(doc);
    }

    private static JSONArray albumPages(String albumId, List<FileObject> children) {
        Map<String, FileObject> remaining = new TreeMap<>();
        children.forEach(child -> remaining.put(child.getUuid(), child));
        JSONArray pages = new JSONArray();
        // The album publisher derives each child UUID from its position in the archive.
        for (int page = 1; page <= children.size(); page++) {
            String id = UUID.nameUUIDFromBytes((albumId + ":" + page).getBytes(StandardCharsets.UTF_8)).toString();
            if (remaining.remove(id) != null) pages.add(ResourceUrls.thumb(id));
        }
        remaining.values().stream().sorted(Comparator.comparing(FileObject::getFileName).thenComparing(FileObject::getUuid))
                .map(child -> ResourceUrls.thumb(child.getUuid())).forEach(pages::add);
        return pages;
    }

    private JSONObject resourceMetadata(FileObject file, JSONObject saved, String live) throws IOException {
        JSONObject meta = saved == null ? new JSONObject() : saved;
        if (live != null) meta.putAll(JSONObject.parseObject(live));
        meta.put("size", file.getSize());
        meta.put("ext", ResourcePublicationService.extension(file.getFileName()));
        meta.put("contentObjectId", file.getContentObjectId());
        if (file.getPath() != null) {
            MessageDigest md5 = digest("MD5"), sha256 = digest("SHA-256");
            try (var input = Files.newInputStream(Path.of(file.getPath()), NOFOLLOW_LINKS);
                 var md5Stream = new DigestInputStream(input, md5);
                 var stream = new DigestInputStream(md5Stream, sha256)) {
                if (stream.transferTo(OutputStream.nullOutputStream()) != file.getSize()) throw new IOException("Source changed during rebuild");
            }
            String hash = HexFormat.of().formatHex(sha256.digest());
            if (!hash.equals(meta.getString("sha256")) || !meta.containsKey("durableNodes")) {
                meta.put("requiredCopies", 1);
                meta.put("durableNodes", Map.of(inventory.uuid, hash));
            }
            meta.put("etag", HexFormat.of().formatHex(md5.digest()));
            meta.put("sha256", hash);
        }
        return meta;
    }

    private static void putRoutes(Map<String, String> puts, FileObject file, JSONObject meta) {
        String id = file.getUuid();
        puts.put("/files/" + id, ResourceUrls.file(id));
        puts.put("/files/" + id + "/meta", meta.toJSONString());
        if (file.getCopyOf() != null && !file.getCopyOf().isBlank()) {
            String replica = "/files/" + file.getCopyOf() + "/replicas/" + id;
            puts.put(replica, ResourceUrls.file(id));
            puts.put(replica + "/meta", meta.toJSONString());
        }
    }

    private static void updateBindings(Map<String, JSONObject> source, NavigableMap<String, String> existing, String catalog,
                                       JSONObject portable, Map<String, String> puts, Set<String> deletes, Set<String> missing) {
        Map<String, JSONObject> bindings = new TreeMap<>();
        entriesUnder(existing, "/s3/").forEach(entry -> {
            JSONObject binding = JSONObject.parseObject(entry.getValue());
            String id = binding.getString("uuid");
            if (missing.contains(id)) deletes.add(entry.getKey());
            else if (portable.containsKey(id)) bindings.put(entry.getKey(), binding);
        });
        source.forEach((id, record) -> {
            if (existing.containsKey(catalog + id) || !portable.containsKey(id)) return;
            JSONObject saved = record.getJSONObject("_bindings");
            if (saved == null) return;
            saved.forEach((key, value) -> {
                String[] parts = key.split("/", 4);
                if (parts.length == 4 && "s3".equals(parts[1]) && !parts[2].isBlank() && !parts[3].isBlank()
                        && !existing.containsKey(key) && value instanceof JSONObject binding && id.equals(binding.getString("uuid"))
                        && parts[3].equals(binding.getString("key"))) bindings.put(key, binding);
            });
        });
        bindings.forEach((key, binding) -> {
            JSONObject record = portable.getJSONObject(binding.getString("uuid"));
            JSONObject meta = record.getJSONObject("_metadata");
            if (meta.getString("etag") != null) {
                binding.put("etag", meta.getString("etag"));
                binding.put("size", meta.getLongValue("size"));
            }
            String value = binding.toJSONString();
            if (!Objects.equals(value, existing.get(key))) puts.put(key, value);
            JSONObject saved = record.getJSONObject("_bindings");
            if (saved == null) {
                saved = new JSONObject();
                record.put("_bindings", saved);
            }
            saved.put(key, binding);
        });
    }

    private static Stream<Map.Entry<String,String>> entriesUnder(NavigableMap<String,String> entries, String prefix) {
        return entries.tailMap(prefix, true).entrySet().stream().takeWhile(entry -> entry.getKey().startsWith(prefix));
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]+"); }

    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
