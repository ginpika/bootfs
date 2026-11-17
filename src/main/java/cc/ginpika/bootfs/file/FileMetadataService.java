package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.file.binding.FolderPath;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileMetadataService {
    private static final String FILE_INDEX = "full-text";

    private final LocalFileInventory inventory;
    private final FileCatalogRepository catalog;
    private final MetadataRegistry metadata;
    private final ResourceSearchService search;
    private final ResourceWriteLock writeLock;

    public boolean rename(String uuid, String name) throws ResourceWriteConflictException {
        return edit(uuid, document -> {
            String oldName = document.getString("fileName");
            int slash = oldName == null ? -1 : oldName.lastIndexOf('/');
            FileObject local = inventory.query(uuid);
            String logicalName = name;
            // HLS-only entries use their logical filename as the folder listing key.
            if (slash >= 0 && local != null && "1".equals(local.getHlsAvailable())
                    && (local.getPath() == null || !Files.isRegularFile(Path.of(local.getPath())))) {
                logicalName = FolderPath.uploadKey(oldName.substring(0, slash + 1), name);
            }
            search.updateDocumentTitle(FILE_INDEX, uuid, logicalName);
        });
    }

    public boolean setPublicAccess(String uuid, boolean enabled) throws ResourceWriteConflictException {
        return edit(uuid, document -> search.setResourcePublic(uuid, enabled, inventory.buildProxyUrl(uuid)));
    }

    public boolean replaceTags(String uuid, List<Tag> tags) throws ResourceWriteConflictException {
        return edit(uuid, document -> search.mutateDocumentTags(FILE_INDEX, uuid, tags, List.of(), true));
    }

    public boolean addTags(String uuid, List<Tag> tags) throws ResourceWriteConflictException {
        return edit(uuid, document -> search.mutateDocumentTags(FILE_INDEX, uuid, tags, List.of(), false));
    }

    public boolean removeTags(String uuid, List<String> tags) throws ResourceWriteConflictException {
        return edit(uuid, document -> search.mutateDocumentTags(FILE_INDEX, uuid, List.of(), tags, false));
    }

    public List<Tag> tags(String uuid) {
        return search.findDocument(FILE_INDEX, uuid) == null ? null : search.getDocumentTags(FILE_INDEX, uuid);
    }

    public long visibleRevision() {
        return search.visibleRevision();
    }

    private boolean edit(String uuid, Consumer<JSONObject> mutation) throws ResourceWriteConflictException {
        // Use the same node-local lock as folder moves and content-pointer replacement.
        synchronized (writeLock) {
            forkReplica(uuid);
            JSONObject document = search.findDocument(FILE_INDEX, uuid);
            if (document == null) return false;
            mutation.accept(document);
            synchronizeLocalCatalog(uuid);
            return true;
        }
    }

    private void forkReplica(String uuid) throws ResourceWriteConflictException {
        String key = "/replica-sources/" + uuid;
        for (int attempt = 0; attempt < 8; attempt++) {
            String raw = metadata.getOne(key);
            if (raw == null) return;
            JSONObject source = JSONObject.parseObject(raw);
            if (source.getBooleanValue("forked")) return;
            source.put("forked", true);
            if (metadata.compareAndSet(key, raw, source.toJSONString())) return;
        }
        throw new ResourceWriteConflictException("Replica is changing; retry the local edit");
    }

    private void synchronizeLocalCatalog(String uuid) {
        FileObject local = inventory.query(uuid);
        if (local == null) return;
        JSONObject indexed = search.findDocument(FILE_INDEX, uuid);
        if (indexed == null) return;
        String name = indexed.getString("fileName");
        if (name == null) name = indexed.getString("title");
        FileObject refreshed = local.toBuilder()
                .fileName(name == null ? local.getFileName() : name)
                .tags(search.getDocumentTags(FILE_INDEX, uuid))
                .isPublicAccess(indexed.getString("isPublicAccess"))
                .build();
        try {
            catalog.update(uuid, refreshed);
            inventory.replaceCached(uuid, local, refreshed);
        } catch (RuntimeException failure) {
            log.warn("Authoritative resource metadata committed; local inventory update needs retry for {}", uuid, failure);
        }
    }
}
