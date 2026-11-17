package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Prepares and stages optimistic-CAS immutable text content replacements. */
@Service
@RequiredArgsConstructor
public class TextReplacementService {
    private final LocalFileInventory context;
    private final MetadataRegistry metadataRegistry;
    private final ResourceSearchService resourceSearch;
    private final ResourceWriteLock writeLock;
    private final ResourceDeletionService deletionService;
    private final ResourcePublicationService publicationService;

    /** Text edits switch immutable content generations while keeping the resource UUID and URLs. */
    public PublishedResource replaceText(String originalId, String expectedVersion, byte[] content) throws IOException {
        return replaceText(originalId, expectedVersion, content, null, false);
    }

    public PublishedResource replaceText(String originalId, String expectedVersion, byte[] content,
                                         String encoding, boolean encodingChanged) throws IOException {
        synchronized (writeLock) {
            String hash = ContentHash.sha256(new ByteArrayInputStream(content));
            FileObject currentResource = context.query(originalId);
            String currentMetaRaw = metadataRegistry.getOne("/files/" + originalId + "/meta");
            JSONObject currentMeta = currentMetaRaw == null ? null : JSONObject.parseObject(currentMetaRaw);
            String currentVersion = currentMeta == null ? null : currentMeta.getString("textVersion");
            String sourceHash = currentResource != null && currentResource.getPath() != null && Files.isRegularFile(Path.of(currentResource.getPath()))
                    ? ContentHash.sha256(Path.of(currentResource.getPath())) : null;
            if (currentVersion == null) currentVersion = sourceHash;
            String receiptKey = "/text-replacements/" + originalId + "/" + expectedVersion;
            String receiptRaw = metadataRegistry.getOne(receiptKey);
            if (receiptRaw != null) {
                JSONObject receipt = JSONObject.parseObject(receiptRaw);
                if (receipt.getBooleanValue("stableUuid")) {
                    if (currentResource != null && !deletionService.isDeleted(currentResource)
                            && Objects.equals(currentVersion, receipt.getString("textVersion"))
                            && hash.equals(receipt.getString("sha256"))
                            && (encoding == null || encoding.equals(receipt.getString("textEncoding")))) {
                        String pending = metadataRegistry.getOne(publicationPrefix() + receipt.getString("contentObjectId"));
                        if (pending != null) return publicationService.finishPublication(JSONObject.parseObject(pending));
                        return new PublishedResource(currentResource, currentMeta.getString("etag"));
                    }
                    throw new ResourceWriteConflictException("文件已被更新，请重新加载后再保存");
                }
                String receiptEncoding = receipt.getString("textEncoding");
                if (encoding != null && !encoding.equals(receiptEncoding == null ? TextEncoding.UTF8 : receiptEncoding)) {
                    throw new ResourceWriteConflictException("文件已使用不同编码保存，请刷新后重试");
                }
                String pending = metadataRegistry.getOne(publicationPrefix() + receipt.getString("uuid"));
                if (hash.equals(receipt.getString("sha256")) && pending != null) {
                    return publicationService.finishPublication(JSONObject.parseObject(pending));
                }
                FileObject replacement = context.query(receipt.getString("uuid"));
                if (hash.equals(receipt.getString("sha256")) && replacement != null && !deletionService.isDeleted(replacement)) {
                    JSONObject metadata = JSONObject.parseObject(metadataRegistry.getOne("/files/" + replacement.getUuid() + "/meta"));
                    return new PublishedResource(replacement, metadata.getString("etag"));
                }
                throw new ResourceWriteConflictException("文件已保存为新版本，请刷新后重试");
            }
            FileObject original = context.query(originalId);
            if (original == null || deletionService.isDeleted(original)) {
                throw new ResourceWriteConflictException("文件已被修改或删除，请刷新后重试");
            }
            Path source = Path.of(original.getPath());
            if (!Files.isRegularFile(source) || !expectedVersion.equals(currentVersion)
                    || (currentMeta != null && currentMeta.getString("sha256") != null && !currentMeta.getString("sha256").equals(sourceHash))) {
                throw new ResourceWriteConflictException("文件内容已变化，请重新加载后再保存");
            }
            if (hash.equals(sourceHash) && !encodingChanged) {
                JSONObject metadata = JSONObject.parseObject(metadataRegistry.getOne("/files/" + originalId + "/meta"));
                return new PublishedResource(original, metadata == null ? null : metadata.getString("etag"));
            }
            String documentRaw = metadataRegistry.getOne("/documents/full-text/" + originalId);
            if (documentRaw == null) throw new IOException("缺少文件元数据，无法保存");
            JSONObject document = JSONObject.parseObject(documentRaw);
            FileObject resource = JSONObject.from(original).toJavaObject(FileObject.class);
            String currentName = document.getString("fileName");
            if (currentName == null) currentName = document.getString("title");
            if (currentName != null) resource.setFileName(currentName);
            if (!TextEncoding.editableName(resource.getFileName())) {
                throw new ResourceWriteConflictException("文件类型已变化，请重新加载后再保存");
            }
            resource.setTags(resourceSearch.getDocumentTags("full-text", originalId));
            resource.setIsPublicAccess(document.getString("isPublicAccess"));
            if (document.containsKey("nsfw")) resource.setNsfw(document.getString("nsfw"));
            if (document.containsKey("hlsAvailable")) resource.setHlsAvailable(document.getString("hlsAvailable"));
            if (document.containsKey("thumbAvailable")) resource.setThumbAvailable(document.getString("thumbAvailable"));
            if (document.containsKey("albumAvailable")) resource.setAlbumAvailable(document.getString("albumAvailable"));
            if (document.containsKey("parent")) resource.setParent(document.getString("parent"));
            if (document.containsKey("copyOf")) resource.setCopyOf(document.getString("copyOf"));
            if (document.containsKey("fileCreatedAt")) resource.setCreatedAt(document.getLong("fileCreatedAt"));
            JSONObject replacement = new JSONObject();
            replacement.put("originalId", originalId);
            replacement.put("version", expectedVersion);
            replacement.put("stableUuid", true);
            replacement.put("document", documentRaw);
            replacement.put("metadata", metadataRegistry.getOne("/files/" + originalId + "/meta"));
            replacement.put("resource", JSONObject.from(resource));
            if (encoding != null) replacement.put("textEncoding", encoding);
            // Deterministic identity makes retry after an uncertain publication resume the same generation.
            String baseId = UUID.nameUUIDFromBytes(("text:" + originalId + ":" + expectedVersion + ":" + hash
                    + (TextEncoding.GBK.equals(encoding) ? ":GBK" : ""))
                    .getBytes(StandardCharsets.UTF_8)).toString();
            String id = publicationService.resolveUploadId(baseId);
            while (metadataRegistry.getOne("/tombstones/" + id) != null) {
                // A definite CAS conflict retires its attempted generation. A later retry gets a fresh
                // identity while uncertain attempts still resolve to their original durable journal.
                String aliasKey = "/upload-aliases/" + context.uuid + "/" + baseId;
                String alias = metadataRegistry.getOne(aliasKey);
                metadataRegistry.compareAndSet(aliasKey, alias, IdGenerator.getUniqueId());
                id = publicationService.resolveUploadId(baseId);
            }
            return publicationService.publishWithIntent(new ByteArrayInputStream(content), resource.getFileName(), id,
                    resource.getParent(), false, false, null, replacement);
        }
    }

    private String publicationPrefix() {
        return "/pending-publications/" + context.uuid + "/";
    }
}
