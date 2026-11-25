package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.core.ResourceUrls;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import com.alibaba.fastjson2.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Repairs legacy local inventory and applies shared deletion intents on each owning node. */
@Component
@Slf4j
public class InventoryReconciler {
    private final LocalFileInventory context;
    private final MetadataRegistry metadata;
    private final ResourceRecoveryService recovery;
    private final ResourceDeletionService deletions;
    private final ResourceSearchService search;
    private final cc.ginpika.bootfs.file.inventory.FileCatalogRepository inventory;
    private final ResourceWriteLock writeLock;
    public InventoryReconciler(LocalFileInventory context, MetadataRegistry metadata,
                               ResourceRecoveryService recovery, ResourceDeletionService deletions,
                               ResourceSearchService search,
                               cc.ginpika.bootfs.file.inventory.FileCatalogRepository inventory,
                               ResourceWriteLock writeLock) {
        this.context = context; this.metadata = metadata; this.recovery = recovery; this.deletions = deletions;
        this.search = search; this.inventory = inventory; this.writeLock = writeLock;
    }
    @EventListener @Order(20)
    public void initialize(ContextRefreshedEvent event) {
        if (!metadata.writable()) return;
        reconcile();
        for (FileObject file:new ArrayList<>(context.cachedFiles())) {
            String id=file.getUuid();
            if (deleted(file)) continue;
            String routeKey = "/files/" + id;
            String route = metadata.getOne(routeKey);
            if (route != null && !route.equals(ResourceUrls.file(id))) {
                String relative = ResourceUrls.relative(route, id);
                if (!relative.equals(route)) metadata.compareAndSet(routeKey, route, relative);
            }
            if (file.getCopyOf() != null) {
                String replicaKey = "/files/" + file.getCopyOf() + "/replicas/" + id;
                String replica = metadata.getOne(replicaKey);
                String relative = ResourceUrls.relative(replica, id);
                if (!Objects.equals(replica, relative)) metadata.compareAndSet(replicaKey, replica, relative);
            }
            boolean hasPayload = Files.isRegularFile(Path.of(file.getPath()));
            if (route==null && hasPayload) {
                JSONObject meta=new JSONObject();
                meta.put("size",file.getSize());
                if (file.getContentObjectId() != null) meta.put("contentObjectId", file.getContentObjectId());
                String name=file.getFileName();
                int dot=name==null ? -1 : name.lastIndexOf('.');
                meta.put("ext",dot<0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT));
                metadata.putFile(id,ResourceUrls.file(id),meta.toJSONString());
            }
            // Only bootstrap absent sources. Tombstones and richer imported documents win.
            if (metadata.getOne("/documents/full-text/"+id)==null) {
                if (hasPayload) search.addToFullText(catalogDocument(file, context.uuid));
            } else {
                String key="/documents/full-text/"+id;
                for (int attempt=0;attempt<16;attempt++) {
                    String raw=metadata.getOne(key);
                    if (raw==null) break;
                    JSONObject doc=JSONObject.parseObject(raw);
                    if (doc.getBooleanValue("_bootfsDeleted")) break;
                    JSONObject merged=JSONObject.parseObject(raw);
                    merged.putIfAbsent("contextUuid",context.uuid);
                    merged.putIfAbsent("fileName",file.getFileName());
                    merged.putIfAbsent("size",file.getSize());
                    merged.putIfAbsent("fileCreatedAt",file.getCreatedAt());
                    merged.putIfAbsent("thumbUrl",context.buildThumbUrl(id));
                    ResourceUrls.normalizeDocument(merged);
                    if (merged.equals(doc) || metadata.compareAndSet(key,raw,merged.toJSONString())) break;
                }
            }
        }
        recovery.bootstrapExistingBindings();
    }

    static FullTextDocument catalogDocument(FileObject file, String node) {
        LocalDateTime now = LocalDateTime.now();
        String thumbnail = ResourceUrls.thumb(file.getUuid());
        return FullTextDocument.builder().uuid(file.getUuid()).fileName(file.getFileName()).title(file.getFileName())
                .size(file.getSize()).tags(file.getTags() == null ? new JSONArray()
                        : JSONArray.from(file.getTags().stream().map(t -> t.toIndexFormat()).toArray()))
                .parent(file.getParent()).copyOf(file.getCopyOf()).albumAvailable(file.getAlbumAvailable())
                .hlsAvailable(file.getHlsAvailable()).thumbAvailable(file.getThumbAvailable()).nsfw(file.getNsfw())
                .isPublicAccess(file.getIsPublicAccess()).fileCreatedAt(file.getCreatedAt()).createdAt(now)
                .updatedAt(now).contextUuid(node).thumbUrl(thumbnail).poster(thumbnail).build();
    }
    private boolean deleted(FileObject file) {
        return metadata.getOne("/tombstones/"+file.getUuid())!=null ||
            (file.getCopyOf()!=null && metadata.getOne("/tombstones/"+file.getCopyOf())!=null) ||
            (file.getParent()!=null && metadata.getOne("/tombstones/"+file.getParent())!=null);
    }
    @Scheduled(fixedDelayString="${bootfs.cleanup.interval-ms:2000}")
    public synchronized void reconcile() {
        if (!metadata.writable()) return;
        try {
            recovery.resumePendingPublications();
            recovery.resumeGlobalDeletions();
            for (FileObject file:new ArrayList<>(context.cachedFiles())) {
                if (deleted(file)) deletions.deleteResource(file.getUuid());
                else refreshLocalView(file);
            }
            String prefix="/cleanup/"+context.uuid+"/";
            for (String key:metadata.scan(prefix).keySet()) {
                String id=key.substring(prefix.length());
                if (!id.matches("[A-Za-z0-9_-]+")) continue;
                // Inventory can already be gone after the logical delete; derive the local path.
                deletions.cleanupLocalResource(id);
                metadata.delete(key);
            }
        } catch (Exception error) { log.warn("Inventory cleanup will retry: {}",error.getMessage()); }
    }

    private void refreshLocalView(FileObject file) {
        synchronized (writeLock) {
        if (context.query(file.getUuid()) != file) return;
        String raw=metadata.getOne("/documents/full-text/"+file.getUuid());
        if (raw==null) return;
        JSONObject source=JSONObject.parseObject(raw);
        if (source.getBooleanValue("_bootfsDeleted")) return;
        FileObject refreshed = file.toBuilder().build();
        if (source.containsKey("fileName")) refreshed.setFileName(source.getString("fileName"));
        if (source.containsKey("isPublicAccess")) refreshed.setIsPublicAccess(source.getString("isPublicAccess"));
        if (source.containsKey("hlsAvailable")) refreshed.setHlsAvailable(source.getString("hlsAvailable"));
        if (source.containsKey("thumbAvailable")) refreshed.setThumbAvailable(source.getString("thumbAvailable"));
        if (source.containsKey("tags")) {
            JSONArray detailed=source.getJSONArray("tagMetadata");
            List<cc.ginpika.bootfs.domain.dto.Tag> tags=new ArrayList<>();
            if (detailed!=null) tags.addAll(detailed.toJavaList(cc.ginpika.bootfs.domain.dto.Tag.class));
            else for (Object tag:source.getJSONArray("tags")) tags.add(cc.ginpika.bootfs.domain.dto.Tag.fromIndexFormat(tag.toString()));
            refreshed.setTags(tags);
        }
        if (!refreshed.equals(file)) {
            inventory.update(file.getUuid(),refreshed);
            context.replaceCached(file.getUuid(),file,refreshed);
        }
        }
    }
}
