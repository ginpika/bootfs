package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.core.ResourceUrls;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Commits staged immutable text replacement generations once bytes and receipts are durable. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextReplacementCommitter {
    private final LocalFileInventory context;
    private final MetadataRegistry metadataRegistry;
    private final ResourceSearchService resourceSearch;
    private final FileTransferService fileTransferService;
    private final ResourceDeletionService deletionService;
    private final ResourceReplicationService replicationService;

    public PublishedResource finish(JSONObject intent, FileObject resource, Path target) throws IOException {
        JSONObject replacement = intent.getJSONObject("textReplacement");
        if (replacement.getBooleanValue("stableUuid")) {
            return finishStable(intent, resource, target);
        }
        String originalId = replacement.getString("originalId"), id = resource.getUuid();
        String receiptKey = "/text-replacements/" + originalId + "/" + replacement.getString("version");
        JSONObject receipt = new JSONObject();
        receipt.put("uuid", id);
        receipt.put("sha256", intent.getString("sha256"));
        String encoding = replacement.getString("textEncoding");
        if (encoding != null) receipt.put("textEncoding", encoding);
        String committed = metadataRegistry.getOne(receiptKey);
        if (committed == null) {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(receiptKey, null);
            expected.put("/tombstones/" + originalId, null);
            expected.put("/tombstones/" + id, null);
            expected.put("/documents/full-text/" + originalId, replacement.getString("document"));
            expected.put("/files/" + originalId + "/meta", replacement.getString("metadata"));
            for (String related : new String[]{resource.getParent(), resource.getCopyOf()}) {
                if (related != null) expected.put("/tombstones/" + related, null);
            }
            JSONObject document = JSONObject.parseObject(replacement.getString("document"));
            document.put("uuid", id);
            document.put("size", resource.getSize());
            if (encoding != null) document.put("textEncoding", encoding);
            ResourceUrls.normalizeDocument(document);
            document.put("contextUuid", context.uuid);
            document.put("updatedAt", LocalDateTime.now());
            document.put("thumbUrl", context.buildThumbUrl(id));
            document.put("poster", context.buildThumbUrl(id));
            if (document.containsKey("publicUrl")) document.put("publicUrl", context.buildProxyUrl(id));
            JSONObject catalog = JSONObject.from(resource);
            catalog.remove("path");
            catalog.remove("url");
            catalog.remove("thumbUrl");
            JSONObject metadata = new JSONObject();
            metadata.put("size", resource.getSize());
            metadata.put("ext", extension(resource.getFileName()));
            metadata.put("etag", intent.getString("etag"));
            metadata.put("sha256", intent.getString("sha256"));
            if (encoding != null) metadata.put("textEncoding", encoding);
            metadata.put("requiredCopies", Math.max(1, intent.getIntValue("requiredCopies")));
            metadata.put("durableNodes", durableNodes(intent));
            Map<String, String> puts = new LinkedHashMap<>();
            puts.put(receiptKey, receipt.toJSONString());
            puts.put("/tombstones/" + originalId, Long.toString(System.currentTimeMillis()));
            puts.put("/files/" + id, ResourceUrls.file(id));
            puts.put("/files/" + id + "/meta", metadata.toJSONString());
            puts.put("/documents/full-text/" + id, document.toJSONString());
            puts.put("/nodes/" + context.uuid + "/catalog/" + id, catalog.toJSONString());
            // Move every S3 key to the new bytes before deletion retires the previous generation.
            stageS3AliasUpdates(originalId, id, resource, intent.getString("etag"), expected, puts);
            stageReplicaFork(originalId, resource.getCopyOf(), expected, puts);
            if (!metadataRegistry.compareAndWrite(expected, puts, Set.of())) {
                committed = metadataRegistry.getOne(receiptKey);
                if (committed == null || !id.equals(JSONObject.parseObject(committed).getString("uuid"))) {
                    deletionService.discardPublication(id, intent);
                    throw new ResourceWriteConflictException("文件内容或元数据已变化，请重新加载后再保存");
                }
            }
        } else if (!id.equals(JSONObject.parseObject(committed).getString("uuid"))) {
            deletionService.discardPublication(id, intent);
            throw new ResourceWriteConflictException("文件已保存为其他版本，请刷新后重试");
        }
        context.cacheCommitted(id, resource);
        // The switch and tombstone are already durable. Cleanup and projection can safely retry.
        deletionService.deleteResource(originalId);
        resourceSearch.visibleRevision();
        fileTransferService.publicationCommitted(id);
        metadataRegistry.delete(publicationPrefix() + id);
        try {
            replicationService.scheduleReplication(id, resource.getFileName());
        } catch (RuntimeException optional) {
            log.warn("Text replacement replication needs retry for {}", id, optional);
        }
        return new PublishedResource(resource, intent.getString("etag"));
    }

    /** Commit a new immutable content pointer without changing the public resource identity. */
    private PublishedResource finishStable(JSONObject intent, FileObject stagedResource, Path target) throws IOException {
        JSONObject replacement = intent.getJSONObject("textReplacement");
        String id = replacement.getString("originalId"), contentId = stagedResource.getUuid();
        String receiptKey = "/text-replacements/" + id + "/" + replacement.getString("version");
        String encoding = replacement.getString("textEncoding");
        String version = ContentHash.sha256(new ByteArrayInputStream(
                (contentId + ":" + intent.getString("sha256") + ":" + encoding).getBytes(StandardCharsets.UTF_8)));
        FileObject resource = JSONObject.from(stagedResource).toJavaObject(FileObject.class);
        resource.setUuid(id);
        resource.setContentObjectId(contentId);
        resource.setPath(target.toString());
        JSONObject receipt = new JSONObject();
        receipt.put("uuid", id);
        receipt.put("contentObjectId", contentId);
        receipt.put("stableUuid", true);
        receipt.put("sha256", intent.getString("sha256"));
        receipt.put("textVersion", version);
        if (encoding != null) receipt.put("textEncoding", encoding);
        String committed = metadataRegistry.getOne(receiptKey);
        if (committed == null) {
            JSONObject document = JSONObject.parseObject(replacement.getString("document"));
            document.put("size", resource.getSize());
            document.put("updatedAt", LocalDateTime.now());
            document.put("contentObjectId", contentId);
            document.put("textVersion", version);
            if (encoding != null) document.put("textEncoding", encoding);
            JSONObject meta = JSONObject.parseObject(replacement.getString("metadata"));
            if (meta == null) meta = new JSONObject();
            meta.put("size", resource.getSize());
            meta.put("etag", intent.getString("etag"));
            meta.put("sha256", intent.getString("sha256"));
            meta.put("contentObjectId", contentId);
            meta.put("textVersion", version);
            if (encoding != null) meta.put("textEncoding", encoding);
            meta.put("requiredCopies", Math.max(1, intent.getIntValue("requiredCopies")));
            meta.put("durableNodes", durableNodes(intent));
            JSONObject catalog = JSONObject.from(resource);
            catalog.remove("path");
            catalog.remove("url");
            catalog.remove("thumbUrl");
            Map<String, String> expected = new LinkedHashMap<>(), puts = new LinkedHashMap<>();
            expected.put(receiptKey, null);
            expected.put("/tombstones/" + id, null);
            expected.put("/documents/full-text/" + id, replacement.getString("document"));
            expected.put("/files/" + id + "/meta", replacement.getString("metadata"));
            puts.put(receiptKey, receipt.toJSONString());
            puts.put("/files/" + id + "/meta", meta.toJSONString());
            puts.put("/documents/full-text/" + id, document.toJSONString());
            puts.put("/nodes/" + context.uuid + "/catalog/" + id, catalog.toJSONString());
            puts.put("/resource-content/" + context.uuid + "/" + id + "/" + contentId, "content");
            // All S3 aliases retain their logical identity while size, ETag and modification time change.
            stageS3AliasUpdates(id, id, resource, intent.getString("etag"), expected, puts);
            stageReplicaFork(id, resource.getCopyOf(), expected, puts);
            if (!metadataRegistry.compareAndWrite(expected, puts, Set.of())) {
                committed = metadataRegistry.getOne(receiptKey);
                if (committed == null || !contentId.equals(JSONObject.parseObject(committed).getString("contentObjectId"))) {
                    deletionService.discardPublication(contentId, intent);
                    throw new ResourceWriteConflictException("文件内容或元数据已变化，请重新加载后再保存");
                }
            }
        } else if (!contentId.equals(JSONObject.parseObject(committed).getString("contentObjectId"))) {
            deletionService.discardPublication(contentId, intent);
            throw new ResourceWriteConflictException("文件已被其他操作更新，请重新加载后再保存");
        }
        // A later edit may have committed before an uncertain earlier response is recovered.
        String live = metadataRegistry.getOne("/files/" + id + "/meta");
        if (live != null && contentId.equals(JSONObject.parseObject(live).getString("contentObjectId"))
                && metadataRegistry.getOne("/tombstones/" + id) == null) {
            context.cacheCommitted(id, resource);
            resourceSearch.visibleRevision();
            metadataRegistry.delete("/replication-jobs/" + context.uuid + "/" + id);
            fileTransferService.publicationCommitted(id);
            try {
                replicationService.scheduleReplication(id, resource.getFileName());
            } catch (RuntimeException optional) {
                log.warn("Text revision replication needs retry for {}", id, optional);
            }
        }
        metadataRegistry.delete(publicationPrefix() + contentId);
        FileObject current = context.query(id);
        if (current == null || deletionService.isDeleted(current)) {
            throw new IOException("Resource was deleted after its text save");
        }
        return new PublishedResource(current, JSONObject.parseObject(live).getString("etag"));
    }

    private JSONObject durableNodes(JSONObject intent) {
        JSONObject confirmations = new JSONObject();
        confirmations.put(context.uuid, intent.getString("sha256"));
        JSONObject receipts = intent.getJSONObject("receipts");
        if (receipts != null) receipts.forEach(confirmations::put);
        return confirmations;
    }

    private void stageS3AliasUpdates(String matchUuid, String targetUuid, FileObject resource, String etag,
                                     Map<String, String> expected, Map<String, String> puts) {
        metadataRegistry.scan("/s3/").forEach((key, raw) -> {
            ObjectKeyBinding binding = JSONObject.parseObject(raw, ObjectKeyBinding.class);
            if (!matchUuid.equals(binding.getUuid())) return;
            expected.put(key, raw);
            puts.put(key, JSONObject.toJSONString(new ObjectKeyBinding(targetUuid, resource.getSize(), etag,
                    System.currentTimeMillis(), binding.getKey(), binding.getContentType())));
        });
    }

    private void stageReplicaFork(String resourceId, String copyOf,
                                  Map<String, String> expected, Map<String, String> puts) {
        String replicaKey = "/replica-sources/" + resourceId;
        String replicaRaw = metadataRegistry.getOne(replicaKey);
        if (replicaRaw != null) {
            JSONObject fork = JSONObject.parseObject(replicaRaw);
            fork.put("forked", true);
            expected.put(replicaKey, replicaRaw);
            puts.put(replicaKey, fork.toJSONString());
        } else if (copyOf != null) {
            expected.put(replicaKey, null);
            puts.put(replicaKey, new JSONObject(Map.of("forked", true, "objectId", copyOf)).toJSONString());
        }
    }

    private String publicationPrefix() {
        return "/pending-publications/" + context.uuid + "/";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
