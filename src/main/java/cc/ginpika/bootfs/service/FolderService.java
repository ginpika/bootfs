package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.s3.S3Config;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Directory identity and actions operate on logical object keys, never filesystem paths. */
@Service
@RequiredArgsConstructor
public class FolderService {
    private final Context context;
    private final FileService files;
    private final EtcdService metadata;
    private final S3Config config;
    @Autowired(required = false) private FederationService federation;

    public JSONObject describe(String folder, JSONObject source, boolean marker) {
        JSONObject descriptor = initial(folder, source, marker, config.getBucket());
        if (source.getString("originEndpoint") != null) return descriptor;
        synchronized (files) {
            String key = folderKey(folder);
            String raw = metadata.getOne(key);
            if (raw != null) return JSONObject.parseObject(raw);
            // A list can still hold an older snapshot after a delete or rename completed.
            if (metadata.scan(bucketPrefix() + folder).isEmpty() && matchingDocuments(folder).isEmpty()) return descriptor;
            if (!metadata.compareAndSet(key, null, descriptor.toJSONString())) {
                raw = metadata.getOne(key);
                if (raw != null) return JSONObject.parseObject(raw);
            }
            return descriptor;
        }
    }

    public static JSONObject initial(String folder, JSONObject source, boolean marker, String bucket) {
        String uuid = marker ? source.getString("uuid") : UUID.nameUUIDFromBytes(
                ("bootfs-folder:" + bucket + ":" + folder).getBytes(StandardCharsets.UTF_8)).toString();
        Long createdAt = source.getLong("createdAt");
        JSONArray tags = marker ? source.getJSONArray("tags") : null;
        JSONObject result = new JSONObject();
        result.put("uuid", uuid); result.put("createdAt", createdAt == null ? System.currentTimeMillis() : createdAt);
        result.put("tags", tags == null ? new JSONArray() : JSONArray.from(tags));
        return result;
    }

    public JSONObject rename(String folder, String name) {
        requireFolder(folder); requireName(name);
        int slash = folder.lastIndexOf('/', folder.length() - 2);
        String target = folder.substring(0, slash + 1) + name + "/";
        requireFolder(target);
        synchronized (files) {
            requireLocal(folder);
            requireNoPending(folder);
            if (folder.equals(target)) return result(folder);
            requireLocal(target);
            requireNoPending(target);
            String bucket = bucketPrefix();
            NavigableMap<String, String> source = metadata.scan(bucket + folder);
            Map<String, String> documents = matchingDocuments(folder);
            source.values().forEach(raw -> {
                String key = "/documents/full-text/" + JSONObject.parseObject(raw).getString("uuid");
                String document = metadata.getOne(key);
                if (document != null) documents.putIfAbsent(key, document);
            });
            if (source.isEmpty() && documents.isEmpty()) throw missing();
            if (!metadata.scan(bucket + target).isEmpty() || !matchingDocuments(target).isEmpty())
                throw conflict("The destination folder already exists");
            materializeDirectories(folder, source, documents);
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            Set<String> deletes = new LinkedHashSet<>();
            // Directory metadata alone does not keep a deleted S3 prefix alive.
            metadata.scan(folderKey(target)).forEach((key, raw) -> { expected.put(key, raw); deletes.add(key); });
            Map<String, String> names = new HashMap<>();
            source.forEach((oldKey, raw) -> {
                String oldPath = oldKey.substring(bucket.length());
                String newPath = target + oldPath.substring(folder.length());
                JSONObject binding = JSONObject.parseObject(raw); binding.put("key", newPath);
                expected.put(oldKey, raw); expected.put(bucket + newPath, null);
                puts.put(bucket + newPath, binding.toJSONString()); deletes.add(oldKey);
                names.putIfAbsent(binding.getString("uuid"), newPath);
            });
            metadata.scan(folderKey(folder)).forEach((oldKey, raw) -> {
                String newKey = folderKey(target) + oldKey.substring(folderKey(folder).length());
                expected.put(oldKey, raw); expected.putIfAbsent(newKey, null); puts.put(newKey, raw); deletes.add(oldKey);
            });
            documents.forEach((key, raw) -> {
                JSONObject document = JSONObject.parseObject(raw);
                String oldName = document.getString("fileName");
                if (oldName == null) oldName = document.getString("title");
                String newName = oldName != null && oldName.startsWith(folder)
                        ? target + oldName.substring(folder.length()) : names.get(document.getString("uuid"));
                if (newName == null) return;
                document.put("fileName", newName); document.put("title", newName);
                expected.put(key, raw); puts.put(key, document.toJSONString());
                names.put(document.getString("uuid"), newName);
            });
            // Bindings may outlive a document projection; move every affected durable local inventory too.
            metadata.scan("/nodes/").forEach((key, raw) -> {
                if (!key.contains("/catalog/")) return;
                JSONObject catalog = JSONObject.parseObject(raw);
                String oldName = catalog.getString("fileName"), id = catalog.getString("uuid");
                String newName = oldName != null && oldName.startsWith(folder)
                        ? target + oldName.substring(folder.length()) : names.get(id);
                if (newName == null) return;
                catalog.put("fileName", newName); expected.put(key, raw); puts.put(key, catalog.toJSONString()); names.put(id, newName);
            });
            if (!metadata.compareAndWrite(expected, puts, deletes)) throw conflict("The folder changed; refresh and retry");
            names.forEach((id, nameAfterMove) -> {
                FileObject local = context.STORAGE.get(id);
                if (local != null) local.setFileName(nameAfterMove);
            });
            return result(target);
        }
    }

    public JSONObject delete(String folder) {
        requireFolder(folder);
        synchronized (files) {
            requireLocal(folder);
            requireNoPending(folder);
            String bucket = bucketPrefix();
            NavigableMap<String, String> source = metadata.scan(bucket + folder);
            Map<String, String> documents = matchingDocuments(folder);
            Set<String> ids = new LinkedHashSet<>();
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            Set<String> deletes = new LinkedHashSet<>();
            source.forEach((key, raw) -> { expected.put(key, raw); deletes.add(key); ids.add(JSONObject.parseObject(raw).getString("uuid")); });
            documents.values().forEach(raw -> ids.add(JSONObject.parseObject(raw).getString("uuid")));
            context.STORAGE.forEach((id, file) -> { if (file.getFileName() != null && file.getFileName().startsWith(folder)) ids.add(id); });
            ids.remove(null);
            for (String id : ids) {
                String key = "/tombstones/" + id, previous = metadata.getOne(key);
                expected.put(key, previous);
                if (previous == null) puts.put(key, Long.toString(System.currentTimeMillis()));
                // Cleanup obligations survive a process stopping after the namespace commit.
                puts.put("/cleanup/" + context.uuid + "/" + id, "object");
            }
            metadata.scan(folderKey(folder)).forEach((key, raw) -> { expected.put(key, raw); deletes.add(key); });
            if (!metadata.compareAndWrite(expected, puts, deletes)) throw conflict("The folder changed; refresh and retry");
            for (String id : ids) files.deleteResource(id);
            return new JSONObject(Map.of("deleted", true, "folderPath", folder, "deletedResources", ids.size()));
        }
    }

    public List<Tag> mutateTags(String folder, List<Tag> additions, List<String> removals) {
        requireFolder(folder);
        if ((additions != null && additions.size() > 10000) || (removals != null && removals.size() > 10000))
            throw badRequest("Too many tags");
        synchronized (files) {
            requireLocal(folder);
            JSONObject descriptor = result(folder);
            String key = folderKey(folder), raw = metadata.getOne(key);
            Map<String, Tag> tags = new LinkedHashMap<>();
            for (Tag tag : descriptor.getJSONArray("tags").toJavaList(Tag.class)) tags.put(tag.toIndexFormat(), tag);
            if (additions != null) for (Tag input : additions) {
                if (input == null || input.getName() == null || input.getName().isBlank()) throw badRequest("Tag name is required");
                Tag tag = JSONObject.from(input).toJavaObject(Tag.class);
                tag.setName(tag.getName().trim());
                if (tag.getNamespace() == null || tag.getNamespace().isBlank()) tag.setNamespace("manual");
                if (tag.getNamespace().contains(":")) throw badRequest("Invalid tag namespace");
                if (tag.getSource() == null) tag.setSource("manual");
                if (tag.getConfidence() == null) tag.setConfidence(1.0f);
                if (!Float.isFinite(tag.getConfidence()) || tag.getConfidence() < 0 || tag.getConfidence() > 1) throw badRequest("Invalid tag confidence");
                tags.putIfAbsent(tag.toIndexFormat(), tag);
            }
            if (removals != null) removals.forEach(tags::remove);
            descriptor.put("tags", JSONArray.from(tags.values()));
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            expected.put(key, raw); puts.put(key, metadataOnly(descriptor).toJSONString());
            String documentKey = "/documents/full-text/" + descriptor.getString("uuid");
            String documentRaw = metadata.getOne(documentKey);
            if (documentRaw != null) {
                JSONObject document = JSONObject.parseObject(documentRaw);
                document.put("tags", JSONArray.from(tags.keySet())); document.put("tagMetadata", JSONArray.from(tags.values()));
                expected.put(documentKey, documentRaw); puts.put(documentKey, document.toJSONString());
            }
            if (!metadata.compareAndWrite(expected, puts, Set.of())) throw conflict("The folder changed; refresh and retry");
            return new ArrayList<>(tags.values());
        }
    }

    public JSONObject result(String folder) {
        String bucket = bucketPrefix();
        NavigableMap<String, String> bindings = metadata.scan(bucket + folder);
        Map<String, String> documents = matchingDocuments(folder);
        if (bindings.isEmpty() && documents.isEmpty()) throw missing();
        String marker = bindings.get(bucket + folder);
        JSONObject source = new JSONObject();
        if (marker != null) {
            JSONObject binding = JSONObject.parseObject(marker);
            source.put("uuid", binding.getString("uuid")); source.put("createdAt", binding.getLong("lastModified"));
            String doc = metadata.getOne("/documents/full-text/" + binding.getString("uuid"));
            if (doc != null) source = resource(JSONObject.parseObject(doc));
        } else {
            long earliest = Long.MAX_VALUE;
            for (String raw : bindings.values()) earliest = Math.min(earliest, JSONObject.parseObject(raw).getLongValue("lastModified"));
            for (String raw : documents.values()) {
                Long timestamp = JSONObject.parseObject(raw).getLong("fileCreatedAt");
                if (timestamp != null) earliest = Math.min(earliest, timestamp);
            }
            source.put("createdAt", earliest == Long.MAX_VALUE ? System.currentTimeMillis() : earliest);
        }
        JSONObject result = describe(folder, source, marker != null);
        result.put("folder", true); result.put("folderPath", folder); result.put("managed", true);
        String path = folder.substring(0, folder.length() - 1);
        result.put("fileName", path.substring(path.lastIndexOf('/') + 1)); result.put("size", 0);
        return result;
    }

    private void materializeDirectories(String root, Map<String, String> bindings, Map<String, String> documents) {
        Set<String> paths = new TreeSet<>(); paths.add(root);
        bindings.keySet().forEach(key -> collectParents(key.substring(bucketPrefix().length()), root, paths));
        documents.values().forEach(raw -> {
            JSONObject doc = JSONObject.parseObject(raw);
            String name = doc.getString("fileName"); if (name == null) name = doc.getString("title");
            if (name != null) collectParents(name, root, paths);
        });
        for (String path : paths) result(path);
    }
    private static void collectParents(String key, String root, Set<String> paths) {
        for (int slash = key.indexOf('/', root.length()); slash >= 0; slash = key.indexOf('/', slash + 1)) paths.add(key.substring(0, slash + 1));
    }
    private Map<String, String> matchingDocuments(String folder) {
        Map<String, String> result = new LinkedHashMap<>();
        metadata.scan("/documents/full-text/").forEach((key, raw) -> {
            JSONObject doc = JSONObject.parseObject(raw);
            String name = doc.getString("fileName"); if (name == null) name = doc.getString("title");
            if (name != null && name.startsWith(folder) && !doc.getBooleanValue("_bootfsDeleted")
                    && metadata.getOne("/tombstones/" + doc.getString("uuid")) == null) result.put(key, raw);
        });
        return result;
    }
    private void requireLocal(String folder) {
        if (federation == null || federation.peers().isEmpty()) return;
        for (int offset = 0; ; offset += 1000) {
            JSONObject page = federation.search("", List.of(), offset, 1000, true);
            if (page.getBooleanValue("partial")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Some folder owners are unavailable; retry when they are reachable");
            JSONArray hits = page.getJSONArray("hits");
            if (hits == null || hits.isEmpty()) return;
            for (Object raw : hits) {
                JSONObject hit = JSONObject.from(raw);
                String name = hit.getString("fileName"); if (name == null) name = hit.getString("title");
                if (hit.getString("originEndpoint") != null && name != null && name.startsWith(folder))
                    throw conflict("Manage this folder on its source node");
            }
            if (offset + hits.size() >= page.getIntValue("total")) return;
        }
    }
    private void requireNoPending(String folder) {
        for (String raw : metadata.scan("/pending-publications/").values()) {
            JSONObject intent = JSONObject.parseObject(raw);
            JSONObject binding = intent.getJSONObject("s3Binding"), file = intent.getJSONObject("file");
            String key = binding == null ? null : binding.getString("key");
            String name = file == null ? null : file.getString("fileName");
            boolean sameBucket = binding == null || config.getBucket().equals(binding.getString("bucket"));
            if (sameBucket && ((key != null && key.startsWith(folder)) || (name != null && name.startsWith(folder))))
                throw conflict("Folder uploads are still awaiting acknowledgement; retry after they finish");
        }
    }
    private JSONObject metadataOnly(JSONObject value) {
        JSONObject result = new JSONObject(); result.put("uuid", value.getString("uuid"));
        result.put("createdAt", value.getLong("createdAt")); result.put("tags", value.getJSONArray("tags")); return result;
    }
    private static JSONObject resource(JSONObject document) {
        JSONObject result = new JSONObject(document); result.put("createdAt", document.getLong("fileCreatedAt"));
        JSONArray rawTags = document.getJSONArray("tags");
        if (rawTags != null) result.put("tags", rawTags.stream().map(tag -> tag instanceof String ? Tag.fromIndexFormat((String)tag) : tag).toList());
        return result;
    }
    private String bucketPrefix() { return "/s3/" + config.getBucket() + "/"; }
    private String folderKey(String folder) { return "/folders/" + config.getBucket() + "/" + folder; }
    public static void requireFolder(String folder) {
        if (folder == null || folder.isEmpty() || !folder.endsWith("/") || folder.startsWith("/")
                || folder.getBytes(StandardCharsets.UTF_8).length > 1024 || folder.chars().anyMatch(Character::isISOControl)) throw badRequest("Invalid folder path");
    }
    public static void requireName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw badRequest("Invalid folder name");
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder does not exist"); }
}
