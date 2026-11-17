package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Tombstone-first cascading resource deletion, global convergence, and physical local cleanup. */
@Slf4j
@Service
public class ResourceDeletionService {
    private final LocalFileInventory context;
    private final TfsConfig tfsConfig;
    private final MetadataRegistry metadataRegistry;
    private final ResourceSearchService resourceSearch;
    private final ThumbnailService thumbnailService;

    @Autowired(required = false)
    private ObjectKeyRepository objectKeys;

    @Autowired
    public ResourceDeletionService(LocalFileInventory context, TfsConfig tfsConfig,
                                   MetadataRegistry metadataRegistry, ResourceSearchService resourceSearch,
                                   ThumbnailService thumbnailService) {
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.metadataRegistry = metadataRegistry;
        this.resourceSearch = resourceSearch;
        this.thumbnailService = thumbnailService;
    }

    public ResourceDeletionService(LocalFileInventory context, TfsConfig tfsConfig,
                                   MetadataRegistry metadataRegistry, ResourceSearchService resourceSearch,
                                   ThumbnailService thumbnailService, ObjectKeyRepository objectKeys) {
        this(context, tfsConfig, metadataRegistry, resourceSearch, thumbnailService);
        this.objectKeys = objectKeys;
    }

    public boolean isDeleted(FileObject resource) {
        return metadataRegistry.getOne("/tombstones/" + resource.getUuid()) != null
                || (resource.getParent() != null && metadataRegistry.getOne("/tombstones/" + resource.getParent()) != null)
                || (resource.getCopyOf() != null && metadataRegistry.getOne("/tombstones/" + resource.getCopyOf()) != null);
    }

    /** Tombstone first; deleting either protocol view removes every binding to the same resource. */
    public boolean deleteResource(String uuid) {
        return deleteResource(uuid, new HashSet<>());
    }

    private boolean deleteResource(String uuid, Set<String> visited) {
        if (!visited.add(uuid)) return true;
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("uuid is invalid");
        if (metadataRegistry.getOne("/tombstones/" + uuid) == null) {
            metadataRegistry.compareAndSet("/tombstones/" + uuid, null, Long.toString(System.currentTimeMillis()));
        }
        // A later overwrite can delete this generation before its lost-reply recovery runs.
        // Carry forward the durable obligation to retire its own predecessor, even across owners.
        metadataRegistry.scan("/pending-publications/").values().forEach(raw -> {
            JSONObject pending = JSONObject.parseObject(raw);
            JSONObject file = pending.getJSONObject("file");
            if (file != null && uuid.equals(file.getString("uuid"))) retireSupersededPredecessor(pending, visited);
        });
        List<String> children = new ArrayList<>();
        for (FileObject resource : context.cachedFiles()) {
            if (uuid.equals(resource.getParent())) children.add(resource.getUuid());
        }
        // Resolve relationships from authoritative documents, including children on offline owners.
        metadataRegistry.scan("/documents/full-text/").forEach((key, raw) -> {
            JSONObject document = JSONObject.parseObject(raw);
            if (uuid.equals(document.getString("parent")) || uuid.equals(document.getString("copyOf"))) {
                String child = document.getString("uuid");
                if (child != null && !children.contains(child)) children.add(child);
            }
        });
        for (String child : children) deleteResource(child, visited);
        metadataRegistry.put("/cleanup/" + context.uuid + "/" + uuid, "object");
        resourceSearch.deleteDocumentFromIndex("full-text", uuid);
        resourceSearch.deleteDocumentFromIndex("image-host", uuid);
        if (objectKeys != null) objectKeys.deleteBindingsForResource(uuid);
        context.remove(uuid);
        metadataRegistry.delFileAndReplicas(uuid);
        metadataRegistry.delete("/replication-jobs/" + context.uuid + "/" + uuid);
        metadataRegistry.delete("/pending-albums/" + context.uuid + "/" + uuid);
        try {
            cleanupLocalResource(uuid);
            metadataRegistry.delete("/cleanup/" + context.uuid + "/" + uuid);
        } catch (IOException failure) {
            log.warn("Resource {} deleted; physical cleanup needs retry", uuid, failure);
        }
        return true;
    }

    /** Completes shared logical deletion even when the object's owning node is offline. */
    public void resumeGlobalDeletions() {
        Set<String> deleted = new HashSet<>();
        metadataRegistry.scan("/tombstones/").keySet().forEach(key -> deleted.add(key.substring("/tombstones/".length())));
        Set<String> incomplete = new TreeSet<>();
        NavigableMap<String, String> documents = metadataRegistry.scan("/documents/");
        documents.forEach((key, raw) -> {
            // Legacy gallery records are archival inputs; the canonical resource controls visibility.
            if (!key.startsWith("/documents/full-text/")) return;
            JSONObject doc = JSONObject.parseObject(raw);
            if (doc.getBooleanValue("_bootfsDeleted")) return;
            String id = doc.getString("uuid");
            if (deleted.contains(id) || deleted.contains(doc.getString("parent")) || deleted.contains(doc.getString("copyOf"))) incomplete.add(id);
        });
        metadataRegistry.scan("/files/").keySet().forEach(key -> {
            String[] parts = key.substring("/files/".length()).split("/");
            if (parts.length > 0 && deleted.contains(parts[0])) incomplete.add(parts[0]);
            if (parts.length >= 3 && "replicas".equals(parts[1]) && deleted.contains(parts[2])) incomplete.add(parts[2]);
        });
        metadataRegistry.scan("/s3/").values().forEach(raw -> {
            String id = JSONObject.parseObject(raw, ObjectKeyBinding.class).getUuid();
            if (deleted.contains(id)) incomplete.add(id);
        });
        metadataRegistry.scan("/pending-publications/").values().forEach(raw -> {
            JSONObject intent = JSONObject.parseObject(raw);
            JSONObject file = intent.getJSONObject("file");
            if (file != null && deleted.contains(file.getString("uuid")) && needsPredecessorRetirement(intent)) incomplete.add(file.getString("uuid"));
        });
        // Completed tombstones do not cause mutations or repeated revision increments.
        for (String id : incomplete) {
            if (id != null && id.matches("[A-Za-z0-9_-]+")) deleteResource(id);
        }
    }

    /** Retryable deletion derives paths from the local root and never follows HLS subtree symlinks. */
    public void cleanupLocalResource(String uuid) throws IOException {
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid cleanup identity");
        Path data = Path.of(tfsConfig.getPathPrefix());
        Files.deleteIfExists(data.resolve(uuid));
        String contentPrefix = "/resource-content/" + context.uuid + "/" + uuid + "/";
        for (String key : metadataRegistry.scan(contentPrefix).keySet()) {
            String contentId = key.substring(contentPrefix.length());
            if (!contentId.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid content cleanup identity");
            Files.deleteIfExists(data.resolve(contentId));
            metadataRegistry.delete(key);
        }
        Path thumb = thumbnailService.thumbPath(uuid);
        if (Files.isSymbolicLink(thumb.getParent())) throw new IOException("Thumbnail root must not be a symlink");
        Files.deleteIfExists(thumb);
        Path hlsRoot = data.resolve("hls");
        if (Files.isSymbolicLink(hlsRoot)) throw new IOException("HLS root must not be a symlink");
        DurableFiles.deleteTree(hlsRoot.resolve(uuid));
        if (Files.isDirectory(hlsRoot)) DurableFiles.syncDirectory(hlsRoot);
        if (Files.isDirectory(thumb.getParent())) DurableFiles.syncDirectory(thumb.getParent());
        DurableFiles.syncDirectory(data);
    }

    public void discardPublication(String uuid, JSONObject intent) throws IOException {
        Set<String> visited = new HashSet<>();
        visited.add(uuid);
        retireSupersededPredecessor(intent, visited);
        deleteResource(uuid);
        String stage = intent.getString("stage");
        if (stage != null && stage.matches("\\.upload-[A-Za-z0-9_-]+\\.part")) {
            Files.deleteIfExists(Path.of(tfsConfig.getPathPrefix(), stage));
        }
        metadataRegistry.delete("/pending-publications/" + context.uuid + "/" + uuid);
    }

    public boolean needsPredecessorRetirement(JSONObject intent) {
        JSONObject binding = intent.getJSONObject("s3Binding");
        if (binding == null || binding.getString("expected") == null || objectKeys == null) return false;
        ObjectKeyBinding previous = JSONObject.parseObject(binding.getString("expected"), ObjectKeyBinding.class);
        if (previous.getUuid() == null || metadataRegistry.getOne("/tombstones/" + previous.getUuid()) != null) return false;
        ObjectKeyBinding current = objectKeys.find(binding.getString("bucket"), binding.getString("key"));
        // A losing publication must never retire the generation that still owns the key.
        return current == null || !previous.getUuid().equals(current.getUuid());
    }

    public void retireSupersededPredecessor(JSONObject intent, Set<String> visited) {
        if (!needsPredecessorRetirement(intent)) return;
        ObjectKeyBinding previous = JSONObject.parseObject(intent.getJSONObject("s3Binding").getString("expected"), ObjectKeyBinding.class);
        if (!hasRetainedVersion(previous.getUuid())) deleteResource(previous.getUuid(), visited);
    }

    public boolean hasRetainedVersion(String uuid) {
        for (String raw : metadataRegistry.scan("/versions/").values()) {
            JSONObject record = JSONObject.parseObject(raw);
            if (!Set.of("s3", "resource").contains(record.getString("namespace"))) continue;
            JSONObject value = JSONObject.parseObject(record.getString("value"));
            if (uuid.equals(value.getString("uuid"))) return true;
        }
        return false;
    }
}
