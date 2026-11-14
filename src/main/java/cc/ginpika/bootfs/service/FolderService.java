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
        return describe(folder, source, marker, true);
    }

    public JSONObject describe(String folder, JSONObject source, boolean marker, boolean persist) {
        JSONObject descriptor = initial(folder, source, marker, config.getBucket());
        String key = folderKey(folder);
        if (!persist) {
            String raw = metadata.getOne(key);
            return raw == null ? descriptor : JSONObject.parseObject(raw);
        }
        synchronized (files) {
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
            requireNoPendingPath(folder, true);
            if (folder.equals(target)) return result(folder);
            requireLocal(target);
            requireNoPendingPath(target, true);
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            Set<String> deletes = new LinkedHashSet<>();
            Map<String, String> names = new HashMap<>();
            stageDirectoryRelocation(folder, target, expected, puts, deletes, names);
            if (!metadata.compareAndWrite(expected, puts, deletes)) throw conflict("The folder changed; refresh and retry");
            applyLocalNames(names);
            return result(target);
        }
    }

    public record FileMove(String objectKey, String uuid) {}

    /** Apply one owner's batch under a path resolved by the requesting node. */
    public JSONObject moveOwned(String destination, List<FileMove> filesToMove, List<String> foldersToMove) {
        if (!destination.isEmpty()) requireFolder(destination);
        List<FileMove> fileKeys = distinct(filesToMove);
        List<String> folderPaths = distinct(foldersToMove);
        if (fileKeys.isEmpty() && folderPaths.isEmpty()) throw badRequest("Nothing to move");
        if (fileKeys.size() + folderPaths.size() > 500) throw badRequest("Too many items");
        synchronized (files) {
            if (!destination.isEmpty()) requireNoPendingPath(destination, true);
            List<Move> plan = planMove(destination, fileKeys, folderPaths);
            JSONObject response = new JSONObject();
            response.put("destination", destination);
            response.put("moved", plan.size());
            if (plan.isEmpty()) return response;
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            Set<String> deletes = new LinkedHashSet<>();
            Map<String, String> names = new HashMap<>();
            for (Move move : plan) {
                if (move.directory) stageDirectoryRelocation(move.source, move.target, expected, puts, deletes, names);
                else stageFileRelocation(move.source, move.target, move.uuid, expected, puts, deletes, names);
            }
            if (!metadata.compareAndWrite(expected, puts, deletes)) throw conflict("The folder changed; refresh and retry");
            applyLocalNames(names);
            return response;
        }
    }

    private List<Move> planMove(String destination, List<FileMove> fileKeys, List<String> folderPaths) {
        List<Move> plan = new ArrayList<>();
        Set<String> reserved = new HashSet<>();
        for (String folder : folderPaths) {
            requireFolder(folder);
            requireLocal(folder);
            requireNoPendingPath(folder, true);
            for (String other : folderPaths) {
                if (!folder.equals(other) && (folder.startsWith(other) || other.startsWith(folder)))
                    throw badRequest("Choose a folder or its contents, not both");
            }
            String name = folder.substring(0, folder.length() - 1);
            name = name.substring(name.lastIndexOf('/') + 1);
            String target = destination + name + "/";
            requireFolder(target);
            if (target.equals(folder)) continue;
            if (target.startsWith(folder)) throw conflict("Cannot move a folder into itself");
            reserve(reserved, target);
            String bucket = bucketPrefix();
            String sibling = target.substring(0, target.length() - 1);
            if (metadata.getOne(bucket + sibling) != null) throw conflict("A file with this name already exists");
            plan.add(new Move(folder, target, true, null));
        }
        for (FileMove file : fileKeys) {
            String key = file.objectKey();
            requireObjectKey(key);
            if (file.uuid() != null && !file.uuid().matches("[A-Za-z0-9_-]+")) throw badRequest("Invalid resource identity");
            for (String folder : folderPaths) {
                if (key.startsWith(folder)) throw badRequest("Choose a folder or its contents, not both");
            }
            requireNoPendingPath(key, false);
            String name = key.substring(key.lastIndexOf('/') + 1);
            String target = destination + name;
            requireObjectKey(target);
            if (target.equals(key)) {
                if (file.uuid() != null) fileSource(key, file.uuid());
                continue;
            }
            reserve(reserved, target);
            plan.add(new Move(key, target, false, file.uuid()));
        }
        return plan;
    }

    private void stageDirectoryRelocation(String folder, String target, Map<String, String> expected,
                                          Map<String, String> puts, Set<String> deletes, Map<String, String> names) {
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
        // Directory metadata alone does not keep a deleted S3 prefix alive.
        metadata.scan(folderKey(target)).forEach((key, raw) -> { expected.put(key, raw); deletes.add(key); });
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
    }

    private record FileSource(String uuid, String binding, String document) {}

    private FileSource fileSource(String source, String expectedUuid) {
        String raw = metadata.getOne(bucketPrefix() + source);
        if (raw != null) {
            String uuid = JSONObject.parseObject(raw).getString("uuid");
            if (uuid == null || (expectedUuid != null && !expectedUuid.equals(uuid)) || !isOwnedFile(uuid)) throw fileMissing();
            return new FileSource(uuid, raw, metadata.getOne("/documents/full-text/" + uuid));
        }
        if (expectedUuid != null) {
            String document = metadata.getOne("/documents/full-text/" + expectedUuid);
            if (isOwnedFile(expectedUuid) && matchesUnboundSource(source, expectedUuid, document))
                return new FileSource(expectedUuid, null, document);
            throw fileMissing();
        }
        for (String document : metadata.scan("/documents/full-text/").values()) {
            JSONObject row = JSONObject.parseObject(document);
            String uuid = row.getString("uuid");
            if (uuid != null && isOwnedFile(uuid) && matchesUnboundSource(source, uuid, document))
                return new FileSource(uuid, null, document);
        }
        throw fileMissing();
    }

    private boolean isOwnedFile(String uuid) {
        return uuid.matches("[A-Za-z0-9_-]+") && metadata.getOne("/tombstones/" + uuid) == null
                && metadata.getOne("/nodes/" + context.uuid + "/catalog/" + uuid) != null;
    }

    private boolean matchesUnboundSource(String source, String uuid, String rawDocument) {
        JSONObject document = rawDocument == null ? null : JSONObject.parseObject(rawDocument);
        if (document != null && document.getBooleanValue("_bootfsDeleted")) return false;
        FileObject file = context.STORAGE.get(uuid);
        String name = document == null ? (file == null ? null : file.getFileName())
                : documentName(document);
        return source.equals(name) && metadata.scan(bucketPrefix()).values().stream()
                .noneMatch(raw -> uuid.equals(JSONObject.parseObject(raw).getString("uuid")));
    }

    private void stageFileRelocation(String source, String target, String expectedUuid, Map<String, String> expected,
                                     Map<String, String> puts, Set<String> deletes, Map<String, String> names) {
        String bucket = bucketPrefix();
        FileSource original = fileSource(source, expectedUuid);
        if (puts.containsKey(bucket + target) || metadata.getOne(bucket + target) != null)
            throw conflict("A file with this name already exists");
        if (!metadata.scan(bucket + target + "/").isEmpty() || !matchingDocuments(target + "/").isEmpty())
            throw conflict("A folder with this name already exists");
        if (matchingDocuments(target).values().stream().map(JSONObject::parseObject)
                .anyMatch(document -> target.equals(documentName(document)))) throw conflict("A file with this name already exists");
        String uuid = original.uuid(), raw = original.binding(), documentRaw = original.document();
        expected.put("/tombstones/" + uuid, null);
        String replicaKey = "/replica-sources/" + uuid, replica = metadata.getOne(replicaKey);
        if (replica != null) {
            JSONObject branch = JSONObject.parseObject(replica);
            branch.put("forked", true);
            expected.put(replicaKey, replica);
            puts.put(replicaKey, branch.toJSONString());
        }
        names.put(uuid, target);
        if (raw != null) {
            JSONObject binding = JSONObject.parseObject(raw);
            binding.put("key", target);
            expected.put(bucket + source, raw);
            expected.put(bucket + target, null);
            puts.put(bucket + target, binding.toJSONString());
            deletes.add(bucket + source);
        }
        if (documentRaw != null) {
            String documentKey = "/documents/full-text/" + uuid;
            JSONObject document = JSONObject.parseObject(documentRaw);
            if (document.getBooleanValue("_bootfsDeleted")) throw fileMissing();
            String newName = rewriteFileName(documentName(document), source, target);
            document.put("fileName", newName);
            document.put("title", newName);
            expected.put(documentKey, documentRaw);
            puts.put(documentKey, document.toJSONString());
            names.put(uuid, newName);
        }
        String key = "/nodes/" + context.uuid + "/catalog/" + uuid;
        String rawCatalog = metadata.getOne(key);
        if (rawCatalog != null) {
            JSONObject catalog = JSONObject.parseObject(rawCatalog);
            String oldName = catalog.getString("fileName");
            String newName = rewriteFileName(oldName == null ? names.get(uuid) : oldName, source, target);
            if (!newName.equals(oldName)) {
                catalog.put("fileName", newName);
                expected.put(key, rawCatalog);
                puts.put(key, catalog.toJSONString());
            }
        }
    }

    /** A renamed display name without a slash stays visible; a stored path follows the file into the new folder. */
    static String rewriteFileName(String oldName, String source, String target) {
        if (oldName == null || oldName.isEmpty() || oldName.equals(source)) return target;
        int slash = source.lastIndexOf('/');
        String parent = slash < 0 ? "" : source.substring(0, slash + 1);
        String relocatedParent = target.substring(0, target.lastIndexOf('/') + 1);
        if (!parent.isEmpty() && oldName.startsWith(parent)) return relocatedParent + oldName.substring(parent.length());
        if (!oldName.contains("/")) return oldName;
        return target;
    }

    private void applyLocalNames(Map<String, String> names) {
        names.forEach((id, nameAfterMove) -> {
            FileObject local = context.STORAGE.get(id);
            if (local != null && nameAfterMove != null) local.setFileName(nameAfterMove);
        });
    }

    private static void reserve(Set<String> reserved, String path) {
        String sibling = path.endsWith("/") ? path.substring(0, path.length() - 1) : path + "/";
        if (!reserved.add(path) || reserved.contains(sibling)) throw conflict("Two items would have the same name");
    }

    private static <T> List<T> distinct(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.stream().anyMatch(Objects::isNull)) throw badRequest("Invalid move");
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private record Move(String source, String target, boolean directory, String uuid) {}

    public JSONObject delete(String folder) {
        requireFolder(folder);
        synchronized (files) {
            requireLocal(folder);
            requireNoPendingPath(folder, true);
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
            String name = documentName(doc);
            if (name != null && name.startsWith(folder) && !doc.getBooleanValue("_bootfsDeleted")
                    && metadata.getOne("/tombstones/" + doc.getString("uuid")) == null) result.put(key, raw);
        });
        return result;
    }
    private static String documentName(JSONObject document) {
        return Objects.toString(document.getString("fileName"), document.getString("title"));
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
    private void requireNoPendingPath(String path, boolean directory) {
        for (String raw : metadata.scan("/pending-publications/").values()) {
            JSONObject intent = JSONObject.parseObject(raw);
            JSONObject binding = intent.getJSONObject("s3Binding"), file = intent.getJSONObject("file");
            String key = binding == null ? null : binding.getString("key");
            String name = file == null ? null : file.getString("fileName");
            boolean sameBucket = binding == null || config.getBucket().equals(binding.getString("bucket"));
            boolean affected = directory
                    ? (key != null && key.startsWith(path)) || (name != null && name.startsWith(path))
                    : path.equals(key) || path.equals(name);
            if (sameBucket && affected)
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
    public static void requireObjectKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.endsWith("/") || key.contains("\\")
                || key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.chars().anyMatch(Character::isISOControl))
            throw badRequest("Invalid object path");
        requireName(key.substring(key.lastIndexOf('/') + 1));
    }
    public static void requireName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw badRequest("Invalid folder name");
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder does not exist"); }
    private static ResponseStatusException fileMissing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "File does not exist"); }
}
