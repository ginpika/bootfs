package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.cluster.ClusterHttp;
import cc.ginpika.bootfs.kernel.metadata.MetadataException;
import cc.ginpika.bootfs.s3.S3Config;
import cc.ginpika.bootfs.s3.S3ConcurrentWriteException;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.FolderService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** The browser and S3 share the same logical paths; slashes are never local disk paths. */
@RestController
@RequiredArgsConstructor
public class FolderController {
    private final Context context;
    private final FileService fileService;
    private final EtcdService etcdService;
    private final S3Config s3Config;
    @Autowired(required = false) private FederationService federation;
    @Autowired(required = false) private FolderService folderService;

    private static final Comparator<JSONObject> FILE_ORDER =
            Comparator.<JSONObject, Long>comparing(row -> row.getLong("createdAt"), Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(row -> row.getString("fileName"), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(row -> row.getString("uuid"))
                    .thenComparing(row -> row.getString("objectKey"));

    @GetMapping("/api/folders")
    public JSONObject list(@RequestParam(defaultValue = "") String folder,
                           @RequestParam(defaultValue = "0") int pageNumber,
                           @RequestParam(defaultValue = "10") int pageSize,
                           @RequestParam(defaultValue = "") String search) {
        if (pageNumber < 0 || pageSize < 1 || pageSize > 1000) throw badRequest("Invalid pagination");
        String directives = search.toLowerCase(Locale.ROOT);
        boolean includeAll = directives.contains("meta:all");
        boolean local = directives.contains("meta:local");
        long fileLimit = (long) pageNumber + pageSize;
        JSONObject listing = listLocal(folder, includeAll, local, fileLimit, true);
        TreeMap<String, JSONObject> folders = new TreeMap<>();
        for (Object raw : listing.getJSONArray("folders")) {
            JSONObject row = (JSONObject) raw;
            folders.put(row.getString("folderPath"), row);
        }
        List<JSONObject> files = new ArrayList<>(listing.getJSONArray("files").toJavaList(JSONObject.class));
        long fileCount = listing.getLongValue("fileCount");
        Set<String> unavailable = Set.of();
        if (!local && federation != null) {
            FederationService.FolderListings remote = federation.folderListings(folder, includeAll, fileLimit);
            unavailable = remote.unavailableNodes();
            for (JSONObject page : remote.pages().values()) {
                for (Object raw : page.getJSONArray("folders")) {
                    JSONObject row = (JSONObject) raw;
                    row.put("managed", false);
                    folders.merge(row.getString("folderPath"), row, (existing, peer) -> {
                        existing.put("managed", false);
                        return existing;
                    });
                }
                files.addAll(page.getJSONArray("files").toJavaList(JSONObject.class));
                fileCount += page.getLongValue("fileCount");
            }
        }
        files.sort(FILE_ORDER.thenComparing(row -> Objects.toString(row.getString("originNodeId"), context.uuid)));
        List<JSONObject> entries = new ArrayList<>(folders.values());
        entries.addAll(files);
        JSONObject result = new JSONObject();
        result.put("folder", folder); result.put("pageNumber", pageNumber); result.put("pageSize", pageSize);
        result.put("total", fileCount + folders.size());
        result.put("partial", !unavailable.isEmpty()); result.put("unavailableNodes", unavailable);
        result.put("rows", entries.stream().skip(pageNumber).limit(pageSize).toList());
        return result;
    }

    private JSONObject listLocal(String folder, boolean includeAll, boolean local, long fileLimit, boolean persistDescriptors) {
        FolderService.requireDirectoryPrefix(folder);
        if (fileLimit < 0 || fileLimit > (long) Integer.MAX_VALUE + 1000) throw badRequest("Invalid pagination");
        Set<String> localResourceIds = etcdService.localResourceIds();
        Map<String, JSONObject> resources = new LinkedHashMap<>();
        Set<String> archived = new HashSet<>();
        etcdService.scan("/documents/full-text/").forEach((key, raw) -> {
            JSONObject document = JSONObject.parseObject(raw);
            String uuid = document.getString("uuid");
            if (uuid == null || (localResourceIds != null && !localResourceIds.contains(uuid))) return;
            if (document.getBooleanValue("_bootfsDeleted")) { archived.add(uuid); return; }
            JSONObject row = new JSONObject(document);
            row.remove("originNodeId");
            row.remove("originEndpoint");
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
        bindings.forEach((key, uuid) -> addEntry(folder, key, resources.get(uuid), includeAll, local, persistDescriptors, deleted, folders, files));
        // Catalog-only resources and derived objects retain their existing root visibility rules.
        resources.forEach((uuid, resource) -> {
            if (!bound.contains(uuid)) addEntry(folder, resource.getString("fileName"), resource,
                    includeAll, local, persistDescriptors, deleted, folders, files);
        });
        files.sort(FILE_ORDER);
        return new JSONObject(Map.of("folders", new JSONArray(new ArrayList<>(folders.values())),
                "files", new JSONArray(files.stream().limit(fileLimit).toList()), "fileCount", files.size()));
    }

    @PostMapping("/api/cluster/data/folders")
    public JSONObject peerListing(@RequestBody JSONObject body) {
        return listLocal(body.getString("folder"), body.getBooleanValue("includeAll"), false, body.getLongValue("fileLimit"), false);
    }

    private void addEntry(String folder, String key, JSONObject resource, boolean includeAll, boolean local, boolean persistDescriptors,
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
                        : folderService.describe(path, resource, key.equals(path), persistDescriptors);
                descriptor.put("folder", true); descriptor.put("folderPath", path);
                descriptor.put("fileName", relative.substring(0, slash)); descriptor.put("size", 0);
                descriptor.put("managed", true);
                return descriptor;
            });
        } else {
            JSONObject row = new JSONObject(resource);
            String displayName = resource.getString("fileName");
            if (displayName == null || displayName.endsWith("/")) displayName = relative;
            else displayName = displayName.substring(displayName.lastIndexOf('/') + 1);
            row.put("fileName", displayName); row.put("objectKey", key); row.remove("path");
            row.put("url", context.buildUrl(uuid));
            files.add(row);
        }
    }

    @PostMapping("/api/folders")
    public JSONObject create(@RequestBody Map<String, String> body, HttpServletRequest request) throws IOException {
        String folder = body.getOrDefault("folder", "");
        String name = body.get("name");
        String key = FolderService.uploadKey(folder, name) + "/";
        FolderService.requireDirectoryPrefix(key);
        String existing = etcdService.getOne(bindingKey(key));
        if (existing == null) fileService.publishS3(new ByteArrayInputStream(new byte[0]), key, s3Config.getBucket(),
                "application/x-directory", null, null, request.getHeader("X-BootFS-Request-Id"));
        return new JSONObject(Map.of("folder", true, "folderPath", key, "fileName", name));
    }

    @PostMapping("/api/folders/upload")
    public JSONObject upload(@RequestParam(defaultValue = "") String folder, @RequestParam("file") MultipartFile file,
                             HttpServletRequest request) throws IOException {
        String name = file.getOriginalFilename();
        String key = FolderService.uploadKey(folder, name);
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

    @PostMapping("/api/folders/move")
    public ResponseEntity<JSONObject> move(@RequestBody JSONObject body) {
        return moveResponse(() -> moveSelection(body));
    }

    private JSONObject moveSelection(JSONObject body) {
        if (body == null) throw badRequest("Nothing to move");
        String destination = Objects.toString(body.getString("destination"), "");
        JSONArray folderPaths = body.getJSONArray("folders");
        List<String> folders = folderPaths == null ? List.of() : folderPaths.toJavaList(String.class);
        JSONArray files = body.getJSONArray("files");
        if ((long) folders.size() + (files == null ? 0 : files.size()) > 500)
            throw badRequest("Too many items");
        folders.forEach(FolderService::requireFolder);
        Map<String, List<FolderService.FileMove>> filesByNode = new LinkedHashMap<>();
        if (files != null) for (Object raw : files) {
            if (raw instanceof String key) {
                FolderService.requireObjectKey(key);
                filesByNode.computeIfAbsent(context.uuid, ignored -> new ArrayList<>()).add(new FolderService.FileMove(key, null));
            } else if (raw instanceof Map<?, ?>) {
                JSONObject file = JSONObject.from(raw);
                FolderService.FileMove source = readFileMove(file);
                String node = Objects.toString(file.getString("originNodeId"), context.uuid);
                filesByNode.computeIfAbsent(node, ignored -> new ArrayList<>()).add(source);
            } else throw badRequest("Invalid move");
        }
        if (!folders.isEmpty()) filesByNode.putIfAbsent(context.uuid, List.of());
        if (filesByNode.isEmpty()) throw badRequest("Nothing to move");
        Map<String, String> peers = federation == null ? Map.of() : federation.peers();
        for (String node : filesByNode.keySet()) {
            if (!context.uuid.equals(node) && !peers.containsKey(node))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The file owner is no longer reachable");
        }
        if (!destination.isEmpty()) requireDestination(destination);

        int moved = 0, completed = 0;
        JSONArray failures = new JSONArray();
        ResponseStatusException firstFailure = null;
        for (var batch : filesByNode.entrySet()) {
            String node = batch.getKey();
            try {
                JSONObject result = context.uuid.equals(node)
                        ? folderService.moveOwned(destination, batch.getValue(), folders)
                        : moveAtPeer(peers.get(node), destination, batch.getValue());
                moved += result.getIntValue("moved");
                completed++;
            } catch (ResponseStatusException | MetadataException error) {
                ResponseStatusException failure = error instanceof ResponseStatusException status ? status
                        : new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authoritative metadata is unavailable");
                if (firstFailure == null) firstFailure = failure;
                failures.add(new JSONObject(Map.of("nodeId", node, "status", failure.getRawStatusCode())));
            }
        }
        if (completed == 0 && firstFailure != null) throw firstFailure;
        return new JSONObject(Map.of("destination", destination, "moved", moved, "failures", failures));
    }

    private void requireDestination(String destination) {
        FolderService.requireFolder(destination);
        int slash = destination.lastIndexOf('/', destination.length() - 2);
        String parent = destination.substring(0, slash + 1);
        if (containsFolder(listLocal(parent, true, false, 0, false), destination)) return;
        if (federation != null) {
            FederationService.FolderListings remote = federation.folderListings(parent, true, 0);
            if (remote.pages().values().stream().anyMatch(page -> containsFolder(page, destination))) return;
            if (!remote.unavailableNodes().isEmpty())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Some folder owners are unavailable");
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder does not exist");
    }

    private boolean containsFolder(JSONObject listing, String folder) {
        return listing.getJSONArray("folders").stream()
                .anyMatch(raw -> folder.equals(((JSONObject) raw).getString("folderPath")));
    }

    private JSONObject moveAtPeer(String endpoint, String destination, List<FolderService.FileMove> sources) {
        JSONObject request = new JSONObject(Map.of("destination", destination, "files",
                sources.stream().map(file -> Map.of("objectKey", file.objectKey(), "uuid", file.uuid())).toList()));
        try {
            JSONObject response = federation.call(endpoint, "data/folders/move", request);
            if (response == null || !response.containsKey("moved")) throw new IOException("Invalid peer move response");
            return response;
        } catch (ClusterHttp.HttpFailure failure) {
            if (failure.status == 400 || failure.status == 404 || failure.status == 409) {
                HttpStatus status = HttpStatus.valueOf(failure.status);
                throw new ResponseStatusException(status, failure.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The file owner is unavailable");
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The file owner is unavailable");
        }
    }

    @PostMapping("/api/cluster/data/folders/move")
    public ResponseEntity<JSONObject> peerMove(@RequestBody JSONObject body) {
        return moveResponse(() -> movePeerSelection(body));
    }

    private JSONObject movePeerSelection(JSONObject body) {
        JSONArray files = body.getJSONArray("files");
        if (files == null || files.isEmpty() || files.size() > 500) throw badRequest("Invalid move selection");
        List<FolderService.FileMove> sources = new ArrayList<>();
        for (Object raw : files) {
            if (!(raw instanceof Map<?, ?>)) throw badRequest("Invalid move");
            sources.add(readFileMove(JSONObject.from(raw)));
        }
        return folderService.moveOwned(Objects.toString(body.getString("destination"), ""), sources, List.of());
    }

    private static ResponseEntity<JSONObject> moveResponse(Supplier<JSONObject> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (ResponseStatusException failure) {
            return ResponseEntity.status(failure.getRawStatusCode()).body(new JSONObject(Map.of("error",
                    Objects.toString(failure.getReason(), "Move failed"))));
        }
    }

    private static FolderService.FileMove readFileMove(JSONObject file) {
        String key = file.getString("objectKey"), uuid = file.getString("uuid");
        FolderService.requireObjectKey(key);
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw badRequest("Invalid resource identity");
        return new FolderService.FileMove(key, uuid);
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
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
