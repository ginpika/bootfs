package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.s3.S3Config;
import cc.ginpika.bootfs.s3.S3ConcurrentWriteException;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.FolderService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The browser and S3 share the same logical paths; slashes are never local disk paths. */
@RestController
@RequiredArgsConstructor
public class FolderController {
    private final Context context;
    private final FileService fileService;
    private final EtcdService etcdService;
    private final S3Config s3Config;
    @Autowired(required = false) private cc.ginpika.bootfs.service.FederationService federation;
    @Autowired(required = false) private FolderService folderService;

    @GetMapping("/api/folders")
    public JSONObject list(@RequestParam(defaultValue = "") String folder,
                           @RequestParam(defaultValue = "0") int pageNumber,
                           @RequestParam(defaultValue = "10") int pageSize,
                           @RequestParam(defaultValue = "") String search) {
        validateFolder(folder);
        if (pageNumber < 0 || pageSize < 1 || pageSize > 1000) throw badRequest("Invalid pagination");
        String directives = search.toLowerCase(Locale.ROOT);
        boolean includeAll = directives.contains("meta:all");
        boolean local = directives.contains("meta:local");
        Set<String> localResourceIds = etcdService.localResourceIds();
        Map<String, JSONObject> resources = new LinkedHashMap<>();
        Set<String> archived = new HashSet<>();
        etcdService.scan("/documents/full-text/").forEach((key, raw) -> {
            JSONObject document = JSONObject.parseObject(raw);
            String uuid = document.getString("uuid");
            if (uuid == null || (localResourceIds != null && !localResourceIds.contains(uuid))) return;
            if (document.getBooleanValue("_bootfsDeleted")) { archived.add(uuid); return; }
            JSONObject row = new JSONObject(document);
            row.put("fileName", document.getString("fileName") == null ? document.getString("title") : document.getString("fileName"));
            row.put("createdAt", document.getLong("fileCreatedAt"));
            JSONArray tags = document.getJSONArray("tags");
            if (tags != null) row.put("tags", tags.stream().map(tag -> tag instanceof String ? Tag.fromIndexFormat((String) tag) : tag).toList());
            row.put("url", context.buildUrl(uuid));
            resources.put(uuid, row);
        });
        context.STORAGE.forEach((uuid, file) -> {
            if (localResourceIds == null || localResourceIds.contains(uuid)) resources.putIfAbsent(uuid, JSONObject.from(file));
        });
        Map<String, String> bindings = new TreeMap<>();
        Set<String> legacyUuidBindings = new HashSet<>();
        Set<String> namedResources = new HashSet<>();
        String bucketPrefix = "/s3/" + s3Config.getBucket() + "/";
        etcdService.scan(bucketPrefix).forEach((key, raw) -> {
            JSONObject binding = JSONObject.parseObject(raw);
            String uuid = binding.getString("uuid");
            if (uuid == null || (localResourceIds != null && !localResourceIds.contains(uuid))) return;
            String objectKey = key.substring(bucketPrefix.length());
            bindings.put(objectKey, uuid);
            if (!objectKey.equals(uuid)) namedResources.add(uuid);
            else if (uuid.equals(binding.getString("key"))) legacyUuidBindings.add(uuid);
            resources.computeIfAbsent(uuid, id -> new JSONObject(Map.of("uuid", id, "fileName", objectKey,
                    "size", binding.getLongValue("size"), "createdAt", binding.getLongValue("lastModified"))));
        });
        // An old automatic UUID key and its replacement filename are one browser entry.
        // Filter before grouping and pagination without modifying either durable S3 binding.
        bindings.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue())
                && legacyUuidBindings.contains(entry.getValue()) && namedResources.contains(entry.getValue()));
        Set<String> deleted = new HashSet<>(archived);
        etcdService.scan("/tombstones/").keySet().forEach(key -> deleted.add(key.substring("/tombstones/".length())));
        Set<String> bound = new HashSet<>(bindings.values());
        TreeMap<String, JSONObject> folders = new TreeMap<>();
        List<JSONObject> files = new ArrayList<>();
        bindings.forEach((key, uuid) -> addEntry(folder, key, resources.get(uuid), includeAll, local, deleted, folders, files));
        // Catalog-only resources and derived objects retain their existing root visibility rules.
        resources.forEach((uuid, resource) -> {
            if (!bound.contains(uuid)) addEntry(folder, resource.getString("fileName"), resource,
                    includeAll, local, deleted, folders, files);
        });
        List<JSONObject> entries = new ArrayList<>(folders.values());
        Set<String> unavailable = new TreeSet<>();
        if (!local && federation != null && !federation.peers().isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (int offset = 0; ; offset += 1000) {
                JSONObject page = federation.search("", List.of(), offset, 1000, includeAll);
                JSONArray missing = page.getJSONArray("unavailableNodes");
                if (missing != null) unavailable.addAll(missing.toJavaList(String.class));
                JSONArray hits = page.getJSONArray("hits");
                if (hits == null || hits.isEmpty()) break;
                for (Object hit : hits) {
                    JSONObject resource = JSONObject.from(hit);
                    String endpoint = resource.getString("originEndpoint");
                    String uuid = resource.getString("uuid");
                    if (endpoint == null || uuid == null || resource.getBooleanValue("_bootfsDeleted")
                            || !seen.add(endpoint + "/" + uuid)) continue;
                    String key = resource.getString("fileName");
                    if (key == null) key = resource.getString("title");
                    resource.put("fileName", key);
                    resource.put("createdAt", resource.getLong("fileCreatedAt"));
                    resource.put("url", endpoint + "/f/" + uuid);
                    JSONArray tags = resource.getJSONArray("tags");
                    if (tags != null) resource.put("tags", tags.stream().map(tag -> tag instanceof String ? Tag.fromIndexFormat((String)tag) : tag).toList());
                    addEntry(folder, key, resource, includeAll, false, Collections.emptySet(), folders, files);
                }
                if (offset + hits.size() >= page.getIntValue("total")) break;
            }
            // Remote paths may introduce folders before local file pagination.
            entries = new ArrayList<>(folders.values());
        }
        files.sort(Comparator.<JSONObject, Long>comparing(row -> row.getLong("createdAt"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.getString("fileName"), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.getString("uuid")));
        entries.addAll(files);
        JSONObject result = new JSONObject();
        result.put("folder", folder); result.put("pageNumber", pageNumber); result.put("pageSize", pageSize);
        result.put("total", entries.size());
        result.put("partial", !unavailable.isEmpty()); result.put("unavailableNodes", unavailable);
        result.put("rows", entries.stream().skip(pageNumber).limit(pageSize).toList());
        return result;
    }

    private void addEntry(String folder, String key, JSONObject resource, boolean includeAll, boolean local,
                          Set<String> deleted, Map<String, JSONObject> folders, List<JSONObject> files) {
        if (key == null || resource == null || !key.startsWith(folder)) return;
        String uuid = resource.getString("uuid");
        if (deleted.contains(uuid) || deleted.contains(resource.getString("parent")) || deleted.contains(resource.getString("copyOf"))) return;
        if (!includeAll && (nonBlank(resource.getString("copyOf")) || nonBlank(resource.getString("parent")))) return;
        if (local && !context.STORAGE.containsKey(uuid)) return;
        String relative = key.substring(folder.length());
        if (relative.isEmpty()) return; // An explicit directory marker represents the directory itself.
        int slash = relative.indexOf('/');
        if (slash >= 0) {
            String path = folder + relative.substring(0, slash + 1);
            JSONObject row = folders.computeIfAbsent(path, ignored -> {
                JSONObject descriptor = folderService == null ? FolderService.initial(path, resource, key.equals(path), s3Config.getBucket())
                        : folderService.describe(path, resource, key.equals(path));
                descriptor.put("folder", true); descriptor.put("folderPath", path);
                descriptor.put("fileName", relative.substring(0, slash)); descriptor.put("size", 0);
                descriptor.put("managed", true);
                return descriptor;
            });
            if (resource.getString("originEndpoint") != null) {
                row.put("managed", false); row.put("originEndpoint", resource.getString("originEndpoint"));
            }
        } else {
            JSONObject row = new JSONObject(resource);
            String displayName = resource.getString("fileName");
            if (displayName == null || displayName.endsWith("/")) displayName = relative;
            else displayName = displayName.substring(displayName.lastIndexOf('/') + 1);
            row.put("fileName", displayName); row.put("objectKey", key); row.remove("path");
            files.add(row);
        }
    }

    @PostMapping("/api/folders")
    public JSONObject create(@RequestBody Map<String, String> body, HttpServletRequest request) throws IOException {
        String folder = body.getOrDefault("folder", "");
        validateFolder(folder);
        String name = body.get("name");
        validateSegment(name);
        String key = folder + name + "/";
        validateKey(key);
        String existing = etcdService.getOne(bindingKey(key));
        if (existing == null) fileService.publishS3(new ByteArrayInputStream(new byte[0]), key, s3Config.getBucket(),
                "application/x-directory", null, null, request.getHeader("X-BootFS-Request-Id"));
        return new JSONObject(Map.of("folder", true, "folderPath", key, "fileName", name));
    }

    @PostMapping("/api/folders/upload")
    public JSONObject upload(@RequestParam(defaultValue = "") String folder, @RequestParam("file") MultipartFile file,
                             HttpServletRequest request) throws IOException {
        validateFolder(folder);
        String name = file.getOriginalFilename();
        validateSegment(name);
        String key = folder + name;
        validateKey(key);
        if (etcdService.getOne(bindingKey(key)) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A file with this name already exists");
        FileService.PublishedResource result;
        try (var input = file.getInputStream()) {
            result = fileService.publishS3(input, key, s3Config.getBucket(), file.getContentType() == null
                    ? "application/octet-stream" : file.getContentType(), null, null, request.getHeader("X-BootFS-Request-Id"));
        }
        return new JSONObject(Map.of("uuid", result.file().getUuid(), "fileName", name, "objectKey", key));
    }

    @PutMapping("/api/folders/rename")
    public JSONObject rename(@RequestBody Map<String, String> body) {
        return folderService.rename(body.get("folder"), body.get("name"));
    }

    @DeleteMapping("/api/folders")
    public JSONObject delete(@RequestBody Map<String, String> body) {
        return folderService.delete(body.get("folder"));
    }

    @PostMapping("/api/folders/tags")
    public List<Tag> addTags(@RequestBody JSONObject body) {
        JSONArray tags = body.getJSONArray("tags");
        return folderService.mutateTags(body.getString("folder"), tags == null ? List.of() : tags.toJavaList(Tag.class), List.of());
    }

    @DeleteMapping("/api/folders/tags")
    public List<Tag> removeTags(@RequestBody JSONObject body) {
        JSONArray tags = body.getJSONArray("tags");
        return folderService.mutateTags(body.getString("folder"), List.of(), tags == null ? List.of() : tags.toJavaList(String.class));
    }

    @ExceptionHandler(S3ConcurrentWriteException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> concurrentWrite() { return Map.of("error", "The path changed; refresh and retry"); }

    @ExceptionHandler(cc.ginpika.bootfs.kernel.object.PublicationPendingException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> publicationPending(cc.ginpika.bootfs.kernel.object.PublicationPendingException failure) {
        return Map.of("error", "Upload is awaiting acknowledgement; retry with the same request ID", "uploadId", failure.getUploadId());
    }

    private String bindingKey(String key) { return "/s3/" + s3Config.getBucket() + "/" + key; }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static void validateFolder(String folder) {
        if (folder == null || (!folder.isEmpty() && !folder.endsWith("/"))) throw badRequest("Invalid folder path");
        validateKey(folder);
    }
    private static void validateKey(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.chars().anyMatch(Character::isISOControl))
            throw badRequest("Invalid object path");
    }
    private static void validateSegment(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl))
            throw badRequest("Invalid file or folder name");
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
