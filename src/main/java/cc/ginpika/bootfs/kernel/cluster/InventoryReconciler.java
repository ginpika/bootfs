package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.*;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
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
    private final Context context;
    private final EtcdService metadata;
    private final FileService files;
    private final MeiliSearchService search;
    private final ThumbnailService thumbnails;
    private final String dataRoot;
    private final cc.ginpika.bootfs.core.io.ContextIO inventory;
    public InventoryReconciler(Context context,EtcdService metadata,FileService files,
                               MeiliSearchService search,ThumbnailService thumbnails,cc.ginpika.bootfs.config.TfsConfig config,
                               cc.ginpika.bootfs.core.io.ContextIO inventory) {
        this.context=context; this.metadata=metadata; this.files=files; this.search=search; this.thumbnails=thumbnails;
        this.dataRoot=config.getPathPrefix();
        this.inventory=inventory;
    }
    @EventListener @Order(20)
    public void initialize(ContextRefreshedEvent event) {
        if (!metadata.writable()) return;
        reconcile();
        for (FileObject file:new ArrayList<>(context.STORAGE.values())) {
            String id=file.getUuid();
            if (deleted(file) || !Files.isRegularFile(Path.of(file.getPath()))) continue;
            if (metadata.getOne("/files/"+id)==null) {
                JSONObject meta=new JSONObject();
                meta.put("size",file.getSize());
                if (file.getContentObjectId() != null) meta.put("contentObjectId", file.getContentObjectId());
                String name=file.getFileName();
                int dot=name==null ? -1 : name.lastIndexOf('.');
                meta.put("ext",dot<0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT));
                metadata.putFile(id,context.buildUrl(id),meta.toJSONString());
            }
            // Only bootstrap absent sources. Tombstones and richer imported documents win.
            if (metadata.getOne("/documents/full-text/"+id)==null) {
                search.addToFullText(FullTextDocument.builder().uuid(id).fileName(file.getFileName()).title(file.getFileName())
                    .size(file.getSize()).tags(file.getTags()==null ? new JSONArray() : JSONArray.from(file.getTags().stream().map(t -> t.toIndexFormat()).toArray()))
                    .parent(file.getParent()).copyOf(file.getCopyOf()).albumAvailable(file.getAlbumAvailable())
                    .hlsAvailable(file.getHlsAvailable()).thumbAvailable(file.getThumbAvailable()).nsfw(file.getNsfw())
                    .isPublicAccess(file.getIsPublicAccess()).fileCreatedAt(file.getCreatedAt()).createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now()).contextUuid(context.uuid).thumbUrl(context.buildThumbUrl(id))
                    .poster(context.buildThumbUrl(id)).build());
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
                    if (merged.equals(doc) || metadata.compareAndSet(key,raw,merged.toJSONString())) break;
                }
            }
        }
        files.bootstrapExistingBindings();
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
            files.resumePendingPublications();
            files.resumeGlobalDeletions();
            for (FileObject file:new ArrayList<>(context.STORAGE.values())) {
                if (deleted(file)) files.deleteResource(file.getUuid());
                else refreshLocalView(file);
            }
            String prefix="/cleanup/"+context.uuid+"/";
            for (String key:metadata.scan(prefix).keySet()) {
                String id=key.substring(prefix.length());
                if (!id.matches("[A-Za-z0-9_-]+")) continue;
                // Inventory can already be gone after the logical delete; derive the local path.
                files.cleanupLocalResource(id);
                metadata.delete(key);
            }
        } catch (Exception error) { log.warn("Inventory cleanup will retry: {}",error.getMessage()); }
    }

    private void refreshLocalView(FileObject file) {
        synchronized (files) {
        if (context.query(file.getUuid()) != file) return;
        String raw=metadata.getOne("/documents/full-text/"+file.getUuid());
        if (raw==null) return;
        JSONObject source=JSONObject.parseObject(raw);
        if (source.getBooleanValue("_bootfsDeleted")) return;
        FileObject refreshed=JSONObject.from(file).toJavaObject(FileObject.class);
        refreshed.setUrl(file.getUrl());
        refreshed.setThumbUrl(file.getThumbUrl());
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
            context.STORAGE.replace(file.getUuid(),file,refreshed);
        }
        }
    }
}
