package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.core.io.ComicArchiveProcessor;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.core.io.TransferThreadPool;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.s3.S3KeyIndex;
import cc.ginpika.bootfs.s3.S3ConcurrentWriteException;
import cc.ginpika.bootfs.s3.S3PayloadException;
import cc.ginpika.bootfs.s3.S3ObjectMeta;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.FullTextDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Shared resource lifecycle for browser uploads, albums and the S3 protocol adapter. */
@Slf4j
@Service
public class FileService {
    private final Context context;
    private final TfsConfig tfsConfig;
    private final EtcdService etcdService;
    private final FileTransferService fileTransferService;
    private final MeiliSearchService meiliSearchService;
    private final ThumbnailService thumbnailService;

    @Autowired(required = false)
    private S3KeyIndex s3KeyIndex;
    @Value("${s3.bucket:bootfs}")
    private String s3Bucket = "bootfs";

    @Value("${bootfs.objects.write-policy:single-local}")
    private String writePolicy = "single-local";
    @Value("${bootfs.objects.required-copies:0}")
    private int requiredCopies;
    @Value("${bootfs.objects.prepare-timeout-ms:30000}")
    private int prepareTimeoutMs = 30000;

    @javax.annotation.PostConstruct
    public void validateWritePolicy() {
        if (!Set.of("single-local", "replicated").contains(writePolicy) || requiredCopies < 0 || prepareTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid object write policy, copies or timeout");
        }
        if ("replicated".equals(writePolicy) && configuredCopies() < 2) {
            throw new IllegalArgumentException("replicated requires at least two copies including the local node");
        }
    }

    private int configuredCopies() {
        return requiredCopies > 0 ? requiredCopies : (tfsConfig.getCopies() == null ? 1 : tfsConfig.getCopies());
    }

    private final TransferThreadPool transferThreadPool = new TransferThreadPool();
    private final Set<String> replicationRunning = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FileService(Context context, TfsConfig tfsConfig, EtcdService etcdService,
                       FileTransferService fileTransferService,
                       MeiliSearchService meiliSearchService, ThumbnailService thumbnailService) {
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.etcdService = etcdService;
        this.fileTransferService = fileTransferService;
        this.meiliSearchService = meiliSearchService;
        this.thumbnailService = thumbnailService;
    }

    public record PublishedResource(FileObject file, String etag) {}

    public String upload(MultipartFile file) throws IOException { return upload(file, null); }

    public String upload(MultipartFile file, String requestId) throws IOException {
        String name = filename(file.getOriginalFilename());
        if ((name.startsWith("[Comic]") || name.startsWith("[Picture]")) && extension(name).equals(".zip")) {
            return uploadAlbum(file, requestId);
        }
        try (InputStream input = file.getInputStream()) {
            return context.buildUrl(publish(input, name, uploadId(requestId, "ordinary"), null, false, true).file().getUuid());
        }
    }

    public String uploadComic(MultipartFile file) throws IOException { return uploadComic(file, null); }

    public String uploadComic(MultipartFile file, String requestId) throws IOException {
        if (extension(filename(file.getOriginalFilename())).equals(".zip")) return uploadAlbum(file, requestId);
        return upload(file, requestId);
    }

    private String uploadId(String requestId, String scope) {
        if (requestId == null || requestId.isBlank()) return IdGenerator.getUniqueId();
        if (!requestId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid X-BootFS-Request-Id");
        return resolveUploadId(UUID.nameUUIDFromBytes((context.uuid + ":" + scope + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    }

    /** All uploads stage and fsync their contents before publishing a catalog entry. */
    public PublishedResource publish(InputStream input, String originalFilename, boolean defaultS3Binding) throws IOException {
        return publish(input, filename(originalFilename), IdGenerator.getUniqueId(), null, false, defaultS3Binding);
    }

    public PublishedResource publishS3(InputStream input, String key, String bucket, String contentType,
                                       String expectedBinding, String payloadHash) throws IOException {
        return publishS3(input, key, bucket, contentType, expectedBinding, payloadHash, null);
    }

    public PublishedResource publishS3(InputStream input, String key, String bucket, String contentType,
                                       String expectedBinding, String payloadHash, String requestId) throws IOException {
        JSONObject binding = new JSONObject();
        binding.put("key", key); binding.put("bucket", bucket); binding.put("contentType", contentType);
        binding.put("expected", expectedBinding); binding.put("payloadHash", payloadHash);
        return publish(input, key, uploadId(requestId, "s3:" + bucket + ":" + key), null, false, false, binding);
    }

    private PublishedResource publish(InputStream input, String name, String uuid, String parent,
                                      boolean album, boolean defaultS3Binding) throws IOException {
        return publish(input, name, uuid, parent, album, defaultS3Binding, null);
    }

    private synchronized PublishedResource publish(InputStream input, String name, String uuid, String parent,
                                      boolean album, boolean defaultS3Binding, JSONObject s3Binding) throws IOException {
        return publish(input, name, uuid, parent, album, defaultS3Binding, s3Binding, null);
    }

    private synchronized PublishedResource publish(InputStream input, String name, String uuid, String parent,
                                      boolean album, boolean defaultS3Binding, JSONObject s3Binding, JSONObject textReplacement) throws IOException {
        Path data = Path.of(tfsConfig.getPathPrefix());
        Files.createDirectories(data);
        Path staged = Files.createTempFile(data, ".upload-", ".part");
        MessageDigest digest = md5();
        MessageDigest sha256;
        try { sha256 = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        boolean intentAttempted = false;
        try {
            long size;
            try (DigestInputStream content = new DigestInputStream(new DigestInputStream(input, digest), sha256);
                 OutputStream output = Files.newOutputStream(staged)) { size = content.transferTo(output); }
            String sha256Hex = HexFormat.of().formatHex(sha256.digest());
            if (s3Binding != null && s3Binding.getString("payloadHash") != null
                    && !"UNSIGNED-PAYLOAD".equals(s3Binding.getString("payloadHash"))
                    && !sha256Hex.equalsIgnoreCase(s3Binding.getString("payloadHash"))) {
                throw new S3PayloadException("The provided x-amz-content-sha256 does not match the payload", true);
            }
            String pending = etcdService.getOne(publicationPrefix() + uuid);
            if (pending != null) {
                JSONObject saved = JSONObject.parseObject(pending);
                if (!sha256Hex.equals(saved.getString("sha256")) || !name.equals(saved.getJSONObject("file").getString("fileName"))) {
                    throw new S3ConcurrentWriteException("Request ID was already used for different content");
                }
                return finishPublication(saved);
            }
            String published = etcdService.getOne("/files/" + uuid + "/meta");
            FileObject previous = context.query(uuid);
            if (published != null && previous != null && !isDeleted(previous)) {
                JSONObject metadata = JSONObject.parseObject(published);
                if (!sha256Hex.equals(metadata.getString("sha256")) || !name.equals(previous.getFileName())) {
                    throw new S3ConcurrentWriteException("Request ID was already used for different content");
                }
                return new PublishedResource(previous, metadata.getString("etag"));
            }
            if (etcdService.getOne("/tombstones/" + uuid) != null) throw new S3ConcurrentWriteException("Request ID belongs to a deleted or superseded resource");
            DurableFiles.syncFile(staged);
            DurableFiles.syncDirectory(data);
            String etag = HexFormat.of().formatHex(digest.digest());
            FileObject resource = new FileObject(data.resolve(uuid).toString(), uuid, name, size);
            resource.setCreatedAt(System.currentTimeMillis());
            resource.setParent(parent);
            if (album) resource.setAlbumAvailable("1");
            if (textReplacement != null) {
                resource = textReplacement.getJSONObject("resource").toJavaObject(FileObject.class);
                resource.setUuid(uuid); resource.setSize(size); resource.setPath(data.resolve(uuid).toString());
            }
            JSONObject intent = new JSONObject();
            JSONObject persisted = JSONObject.from(resource); persisted.remove("path");
            intent.put("file", persisted);
            intent.put("etag", etag); intent.put("sha256", sha256Hex);
            intent.put("stage", staged.getFileName().toString());
            intent.put("defaultS3Binding", defaultS3Binding);
            intent.put("requiredCopies", "replicated".equals(writePolicy) ? configuredCopies() : 1);
            intent.put("receipts", new JSONObject());
            if (s3Binding != null) intent.put("s3Binding", s3Binding);
            if (textReplacement != null) intent.put("textReplacement", textReplacement);
            // Mark before the RPC: a timeout can mean this intent has already committed.
            intentAttempted = true;
            etcdService.put(publicationPrefix() + uuid, intent.toJSONString());
            return finishPublication(intent);
        } catch (Exception failure) {
            // Never delete bytes following a potentially committed write with an uncertain reply.
            // A durable intent is resumed after restart; unregistered files remain safe orphan candidates.
            if (failure instanceof S3ConcurrentWriteException || failure instanceof S3PayloadException
                    || failure instanceof cc.ginpika.bootfs.kernel.object.PublicationPendingException) throw (IOException)failure;
            if (intentAttempted) throw new cc.ginpika.bootfs.kernel.object.PublicationPendingException(uuid,
                    "Publication was not acknowledged; query the upload status or retry with the same request ID", failure);
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("Resource publication failed before a durable intent was attempted", failure);
        } finally {
            if (!intentAttempted) Files.deleteIfExists(staged);
        }
    }

    /** Text edits publish a new immutable generation, then switch metadata and protocol bindings together. */
    public synchronized PublishedResource replaceText(String originalId, String expectedVersion, byte[] content) throws IOException {
        String hash = cc.ginpika.bootfs.kernel.object.ContentHash.sha256(new ByteArrayInputStream(content));
        String receiptKey = "/text-replacements/" + originalId + "/" + expectedVersion;
        String receiptRaw = etcdService.getOne(receiptKey);
        if (receiptRaw != null) {
            JSONObject receipt = JSONObject.parseObject(receiptRaw);
            String pending = etcdService.getOne(publicationPrefix() + receipt.getString("uuid"));
            if (hash.equals(receipt.getString("sha256")) && pending != null) return finishPublication(JSONObject.parseObject(pending));
            FileObject replacement = context.query(receipt.getString("uuid"));
            if (hash.equals(receipt.getString("sha256")) && replacement != null && !isDeleted(replacement)) {
                JSONObject metadata = JSONObject.parseObject(etcdService.getOne("/files/" + replacement.getUuid() + "/meta"));
                return new PublishedResource(replacement, metadata.getString("etag"));
            }
            throw new S3ConcurrentWriteException("文件已保存为新版本，请刷新后重试");
        }
        FileObject original = context.query(originalId);
        if (original == null || isDeleted(original)) throw new S3ConcurrentWriteException("文件已被修改或删除，请刷新后重试");
        Path source = Path.of(original.getPath());
        if (!Files.isRegularFile(source) || !expectedVersion.equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(source))) {
            throw new S3ConcurrentWriteException("文件内容已变化，请重新加载后再保存");
        }
        if (hash.equals(expectedVersion)) {
            JSONObject metadata = JSONObject.parseObject(etcdService.getOne("/files/" + originalId + "/meta"));
            return new PublishedResource(original, metadata == null ? null : metadata.getString("etag"));
        }
        String documentRaw = etcdService.getOne("/documents/full-text/" + originalId);
        if (documentRaw == null) throw new IOException("缺少文件元数据，无法保存");
        JSONObject document = JSONObject.parseObject(documentRaw);
        FileObject resource = JSONObject.from(original).toJavaObject(FileObject.class);
        String currentName = document.getString("fileName");
        if (currentName == null) currentName = document.getString("title");
        if (currentName != null) resource.setFileName(currentName);
        if (!TextFileService.editableName(resource.getFileName())) throw new S3ConcurrentWriteException("文件类型已变化，请重新加载后再保存");
        resource.setTags(meiliSearchService.getDocumentTags("full-text", originalId));
        resource.setIsPublicAccess(document.getString("isPublicAccess"));
        if (document.containsKey("nsfw")) resource.setNsfw(document.getString("nsfw"));
        if (document.containsKey("hlsAvailable")) resource.setHlsAvailable(document.getString("hlsAvailable"));
        if (document.containsKey("thumbAvailable")) resource.setThumbAvailable(document.getString("thumbAvailable"));
        if (document.containsKey("albumAvailable")) resource.setAlbumAvailable(document.getString("albumAvailable"));
        if (document.containsKey("parent")) resource.setParent(document.getString("parent"));
        if (document.containsKey("copyOf")) resource.setCopyOf(document.getString("copyOf"));
        if (document.containsKey("fileCreatedAt")) resource.setCreatedAt(document.getLong("fileCreatedAt"));
        JSONObject replacement = new JSONObject();
        replacement.put("originalId", originalId); replacement.put("version", expectedVersion);
        replacement.put("document", documentRaw);
        replacement.put("metadata", etcdService.getOne("/files/" + originalId + "/meta"));
        replacement.put("resource", JSONObject.from(resource));
        // Deterministic identity makes retry after an uncertain publication resume the same generation.
        String baseId = UUID.nameUUIDFromBytes(("text:" + originalId + ":" + expectedVersion + ":" + hash)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String id = resolveUploadId(baseId);
        while (etcdService.getOne("/tombstones/" + id) != null) {
            // A definite CAS conflict retires its attempted generation. A later retry gets a fresh
            // identity while uncertain attempts still resolve to their original durable journal.
            String aliasKey = "/upload-aliases/" + context.uuid + "/" + baseId;
            String alias = etcdService.getOne(aliasKey);
            etcdService.compareAndSet(aliasKey, alias, IdGenerator.getUniqueId());
            id = resolveUploadId(baseId);
        }
        return publish(new ByteArrayInputStream(content), resource.getFileName(), id, resource.getParent(), false, false, null, replacement);
    }

    private PublishedResource finishTextReplacement(JSONObject intent, FileObject resource, Path target) throws IOException {
        JSONObject replacement = intent.getJSONObject("textReplacement");
        String originalId = replacement.getString("originalId"), id = resource.getUuid();
        String receiptKey = "/text-replacements/" + originalId + "/" + replacement.getString("version");
        JSONObject receipt = new JSONObject();
        receipt.put("uuid", id); receipt.put("sha256", intent.getString("sha256"));
        String committed = etcdService.getOne(receiptKey);
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
            document.put("uuid", id); document.put("size", resource.getSize());
            document.put("contextUuid", context.uuid); document.put("updatedAt", LocalDateTime.now());
            document.put("thumbUrl", context.buildThumbUrl(id)); document.put("poster", context.buildThumbUrl(id));
            if (document.containsKey("publicUrl")) document.put("publicUrl", context.buildProxyUrl(id));
            JSONObject catalog = JSONObject.from(resource);
            catalog.remove("path"); catalog.remove("url"); catalog.remove("thumbUrl");
            JSONObject metadata = new JSONObject();
            metadata.put("size", resource.getSize()); metadata.put("ext", extension(resource.getFileName()));
            metadata.put("etag", intent.getString("etag")); metadata.put("sha256", intent.getString("sha256"));
            metadata.put("requiredCopies", Math.max(1, intent.getIntValue("requiredCopies")));
            JSONObject durableNodes = new JSONObject(); durableNodes.put(context.uuid, intent.getString("sha256"));
            JSONObject receipts = intent.getJSONObject("receipts");
            if (receipts != null) receipts.forEach(durableNodes::put);
            metadata.put("durableNodes", durableNodes);
            Map<String, String> puts = new LinkedHashMap<>();
            puts.put(receiptKey, receipt.toJSONString());
            puts.put("/tombstones/" + originalId, Long.toString(System.currentTimeMillis()));
            puts.put("/files/" + id, context.buildUrl(id));
            puts.put("/files/" + id + "/meta", metadata.toJSONString());
            puts.put("/documents/full-text/" + id, document.toJSONString());
            puts.put("/nodes/" + context.uuid + "/catalog/" + id, catalog.toJSONString());
            // Move every S3 key to the new bytes before deletion retires the previous generation.
            etcdService.scan("/s3/").forEach((key, raw) -> {
                S3ObjectMeta binding = JSONObject.parseObject(raw, S3ObjectMeta.class);
                if (!originalId.equals(binding.getUuid())) return;
                expected.put(key, raw);
                puts.put(key, JSONObject.toJSONString(new S3ObjectMeta(id, resource.getSize(), intent.getString("etag"),
                        System.currentTimeMillis(), binding.getKey(), binding.getContentType())));
            });
            String replicaKey = "/replica-sources/" + originalId;
            String replicaRaw = etcdService.getOne(replicaKey);
            if (replicaRaw != null) {
                JSONObject fork = JSONObject.parseObject(replicaRaw); fork.put("forked", true);
                expected.put(replicaKey, replicaRaw); puts.put(replicaKey, fork.toJSONString());
            }
            if (!etcdService.compareAndWrite(expected, puts, Set.of())) {
                committed = etcdService.getOne(receiptKey);
                if (committed == null || !id.equals(JSONObject.parseObject(committed).getString("uuid"))) {
                    discardPublication(id, intent);
                    throw new S3ConcurrentWriteException("文件内容或元数据已变化，请重新加载后再保存");
                }
            }
        } else if (!id.equals(JSONObject.parseObject(committed).getString("uuid"))) {
            discardPublication(id, intent);
            throw new S3ConcurrentWriteException("文件已保存为其他版本，请刷新后重试");
        }
        context.STORAGE.put(id, resource);
        // The switch and tombstone are already durable. Cleanup and projection can safely retry.
        deleteResource(originalId);
        meiliSearchService.visibleRevision();
        fileTransferService.publicationCommitted(id);
        etcdService.delete(publicationPrefix() + id);
        try { replication(target.toFile(), id, resource.getFileName()); }
        catch (RuntimeException optional) { log.warn("Text replacement replication needs retry for {}", id, optional); }
        return new PublishedResource(resource, intent.getString("etag"));
    }

    private String publicationPrefix() { return "/pending-publications/" + context.uuid + "/"; }

    private synchronized PublishedResource finishPublication(JSONObject intent) throws IOException {
        FileObject requested = intent.getJSONObject("file").toJavaObject(FileObject.class);
        String uuid = requested.getUuid();
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid publication identity");
        Path data = Path.of(tfsConfig.getPathPrefix());
        Path target = data.resolve(uuid);
        requested.setPath(target.toString());
        if (isDeleted(requested)) {
            discardPublication(uuid, intent);
            throw new IOException("Resource was deleted before publication completed");
        }
        String stageName = intent.getString("stage");
        if (stageName == null || !stageName.matches("\\.upload-[A-Za-z0-9_-]+\\.part")) throw new IOException("Invalid staged filename");
        Path staged = data.resolve(stageName);
        if (!Files.isRegularFile(target)) {
            if (!Files.isRegularFile(staged)) throw new IOException("Pending publication has no staged content: " + uuid);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            DurableFiles.syncDirectory(data);
        } else if (Files.isRegularFile(staged)) {
            if (Files.mismatch(staged, target) != -1) throw new IOException("Immutable object identity collision: " + uuid);
            Files.delete(staged); DurableFiles.syncDirectory(data);
        }
        // Retry after a lost reply or a failed rename fsync must re-establish the same durability boundary.
        DurableFiles.syncFile(target); DurableFiles.syncDirectory(data);
        // No public inventory, routing, search source or S3 key exists until the byte policy is met.
        ensureDurability(intent, requested, target);
        if (intent.getJSONObject("textReplacement") != null) return finishTextReplacement(intent, requested, target);
        FileObject resource = context.query(uuid);
        if (resource == null) {
            String previous = etcdService.getOne("/nodes/" + context.uuid + "/catalog/" + uuid);
            resource = previous == null ? requested : JSONObject.parseObject(previous, FileObject.class);
            resource.setPath(target.toString());
            context.record(resource, uuid);
        }
        if (isDeleted(resource)) { discardPublication(uuid, intent); throw new IOException("Resource is deleted"); }
        String etag = intent.getString("etag");
        if (etcdService.getOne("/files/" + uuid) == null) {
            JSONObject metadata = new JSONObject();
            metadata.put("size", resource.getSize()); metadata.put("ext", extension(resource.getFileName())); metadata.put("etag", etag); metadata.put("sha256", intent.getString("sha256"));
            metadata.put("requiredCopies", Math.max(1, intent.getIntValue("requiredCopies")));
            JSONObject confirmations = new JSONObject();
            confirmations.put(context.uuid, intent.getString("sha256"));
            JSONObject receipts = intent.getJSONObject("receipts");
            if (receipts != null) receipts.forEach((node, receipt) -> confirmations.put(node, receipt));
            metadata.put("durableNodes", confirmations);
            etcdService.putFile(uuid, context.buildUrl(uuid), metadata.toJSONString());
        }
        JSONObject explicitBinding = intent.getJSONObject("s3Binding");
        if (explicitBinding != null) {
            if (s3KeyIndex == null) throw new IOException("S3 must be enabled to resume this publication");
            String bucket = explicitBinding.getString("bucket"), key = explicitBinding.getString("key");
            String expected = explicitBinding.getString("expected");
            S3ObjectMeta current = s3KeyIndex.getIndex(bucket, key);
            if (current == null || !uuid.equals(current.getUuid())) {
                S3ObjectMeta replacement = new S3ObjectMeta(uuid, resource.getSize(), etag,
                        resource.getCreatedAt(), key, explicitBinding.getString("contentType"));
                if (!s3KeyIndex.compareAndSet(bucket, key, expected, replacement)) {
                    current = s3KeyIndex.getIndex(bucket, key);
                    if (current == null || !uuid.equals(current.getUuid())) {
                        discardPublication(uuid, intent);
                        throw new S3ConcurrentWriteException("The object changed while this upload was in progress");
                    }
                }
            }
        }
        if (etcdService.getOne("/documents/full-text/" + uuid) == null) indexResource(resource);
        if (intent.getBooleanValue("defaultS3Binding") && s3KeyIndex != null && resource.getParent() == null) {
            S3ObjectMeta binding = new S3ObjectMeta(uuid, resource.getSize(), etag, resource.getCreatedAt() == null ? requested.getCreatedAt() : resource.getCreatedAt(), uuid, contentType(resource.getFileName()));
            S3ObjectMeta current = s3KeyIndex.getIndex(s3Bucket, uuid);
            if (current == null) {
                if (!s3KeyIndex.compareAndSet(s3Bucket, uuid, null, binding)) {
                    current = s3KeyIndex.getIndex(s3Bucket, uuid);
                    if (current == null) throw new IOException("S3 UUID binding changed; publication will retry");
                }
            }
            if (current != null && !uuid.equals(current.getUuid())) {
                discardPublication(uuid, intent);
                throw new IOException("S3 UUID binding belongs to another resource");
            }
        }
        if (isDeleted(resource)) { discardPublication(uuid, intent); throw new IOException("Resource is deleted"); }
        if (explicitBinding != null && explicitBinding.getString("expected") != null) {
            S3ObjectMeta previous = JSONObject.parseObject(explicitBinding.getString("expected"), S3ObjectMeta.class);
            if (!uuid.equals(previous.getUuid()) && !hasRetainedVersion(previous.getUuid())) deleteResource(previous.getUuid());
        }
        // Completion intent is cleared only after required metadata and projection sources are durable.
        fileTransferService.publicationCommitted(uuid);
        etcdService.delete(publicationPrefix() + uuid);
        try { replication(target.toFile(), uuid, resource.getFileName()); }
        catch (RuntimeException optional) { log.warn("Replication scheduling needs retry for {}", uuid, optional); }
        try { thumbnailService.generateAsync(uuid); }
        catch (RuntimeException optional) { log.warn("Thumbnail scheduling needs retry for {}", uuid, optional); }
        return new PublishedResource(resource, etag);
    }

    private void ensureDurability(JSONObject intent, FileObject resource, Path target) throws IOException {
        int required = Math.max(1, intent.getIntValue("requiredCopies"));
        if (required == 1) return;
        String id = resource.getUuid(), hash = intent.getString("sha256");
        if (hash == null || Files.size(target) != resource.getSize()
                || !hash.equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(target))) {
            throw new IOException("Local immutable bytes failed verification before publication");
        }
        JSONObject receipts = intent.getJSONObject("receipts");
        if (receipts == null) { receipts = new JSONObject(); intent.put("receipts", receipts); }
        receipts.remove(context.uuid);
        if (receipts.size() + 1 >= required) return;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(prepareTimeoutMs);
        List<Map.Entry<String, String>> candidates = new ArrayList<>(etcdService.getNodesWithKeys().entrySet());
        candidates.removeIf(node -> node.getKey().equals(context.uuid) || stripSlash(node.getValue()).equals(stripSlash(tfsConfig.getWebEntrypoint())));
        candidates.sort(Comparator.comparing(node -> UUID.nameUUIDFromBytes((id + ":" + node.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));
        Set<String> attemptedEndpoints = new HashSet<>();
        Exception last = null;
        for (Map.Entry<String, String> candidate : candidates) {
            if (receipts.size() + 1 >= required) break;
            if (receipts.containsKey(candidate.getKey()) || !attemptedEndpoints.add(stripSlash(candidate.getValue()))) continue;
            long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) break;
            try {
                JSONObject receipt = fileTransferService.prepareRemote(target.toFile(), id, resource.getFileName(), hash,
                        NodeObject.builder().url(candidate.getValue()).build(),
                        (int)Math.min(remaining, Math.max(1,prepareTimeoutMs / Math.max(1,candidates.size()))));
                if (receipt == null || !candidate.getKey().equals(receipt.getString("nodeId"))
                        || !id.equals(receipt.getString("objectId")) || !hash.equals(receipt.getString("sha256"))
                        || resource.getSize() != receipt.getLongValue("size")) throw new IOException("Replica acknowledgement does not match node and content");
                receipts.put(candidate.getKey(), receipt);
                // A lost reply here retains the same content identity and is safely retried.
                etcdService.put(publicationPrefix() + id, intent.toJSONString());
            } catch (Exception failure) { last = failure; }
        }
        if (receipts.size() + 1 < required) {
            throw new cc.ginpika.bootfs.kernel.object.PublicationPendingException(id,
                    "Publication pending: " + (receipts.size() + 1) + "/" + required + " durable nodes confirmed; recovery will retry", last);
        }
    }

    private void discardPublication(String uuid, JSONObject intent) throws IOException {
        Set<String> visited = new HashSet<>(); visited.add(uuid);
        retireSupersededPredecessor(intent, visited);
        deleteResource(uuid);
        String stage = intent.getString("stage");
        if (stage != null && stage.matches("\\.upload-[A-Za-z0-9_-]+\\.part")) Files.deleteIfExists(Path.of(tfsConfig.getPathPrefix(), stage));
        etcdService.delete(publicationPrefix() + uuid);
    }

    private boolean needsPredecessorRetirement(JSONObject intent) {
        JSONObject binding = intent.getJSONObject("s3Binding");
        if (binding == null || binding.getString("expected") == null || s3KeyIndex == null) return false;
        S3ObjectMeta previous = JSONObject.parseObject(binding.getString("expected"), S3ObjectMeta.class);
        if (previous.getUuid() == null || etcdService.getOne("/tombstones/" + previous.getUuid()) != null) return false;
        S3ObjectMeta current = s3KeyIndex.getIndex(binding.getString("bucket"), binding.getString("key"));
        // A losing publication must never retire the generation that still owns the key.
        return current == null || !previous.getUuid().equals(current.getUuid());
    }

    private void retireSupersededPredecessor(JSONObject intent, Set<String> visited) {
        if (!needsPredecessorRetirement(intent)) return;
        S3ObjectMeta previous = JSONObject.parseObject(intent.getJSONObject("s3Binding").getString("expected"), S3ObjectMeta.class);
        if (!hasRetainedVersion(previous.getUuid())) deleteResource(previous.getUuid(), visited);
    }

    private boolean hasRetainedVersion(String uuid) {
        for (String raw : etcdService.scan("/versions/").values()) {
            JSONObject record = JSONObject.parseObject(raw);
            if (!Set.of("s3", "resource").contains(record.getString("namespace"))) continue;
            JSONObject value = JSONObject.parseObject(record.getString("value"));
            if (uuid.equals(value.getString("uuid"))) return true;
        }
        return false;
    }

    /** Called by the inventory reconciler; retries are idempotent and preserve uncertain writes. */
    public void resumePendingPublications() {
        for (String raw : etcdService.scan(publicationPrefix()).values()) {
            try { finishPublication(JSONObject.parseObject(raw)); }
            catch (Exception failure) { log.warn("Pending resource publication will retry: {}", failure.getMessage()); }
        }
        for (String raw : etcdService.scan(albumPrefix()).values()) {
            try { finishAlbum(JSONObject.parseObject(raw)); }
            catch (Exception failure) { log.warn("Pending album assembly will retry: {}", failure.getMessage()); }
        }
        fileTransferService.resumePendingReplicas();
        retryReplicationJobs();
    }

    public String resolveUploadId(String uuid) {
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid upload ID");
        for (int hop = 0; hop < 16; hop++) {
            String mapped = etcdService.getOne("/upload-aliases/" + context.uuid + "/" + uuid);
            if (mapped == null) return uuid;
            if (!mapped.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("Invalid upload alias");
            uuid = mapped;
        }
        throw new IllegalStateException("Upload alias chain is cyclic or too long");
    }

    public String publicationState(String uuid) {
        uuid = resolveUploadId(uuid);
        if (etcdService.getOne("/tombstones/" + uuid) != null) return "DELETED";
        for (String key : etcdService.scan("/pending-publications/").keySet()) if (key.endsWith("/" + uuid)) return "PENDING";
        return etcdService.getOne("/files/" + uuid) != null ? "READY" : "UNKNOWN";
    }

    public boolean isDeleted(FileObject resource) {
        return etcdService.getOne("/tombstones/" + resource.getUuid()) != null
                || (resource.getParent() != null && etcdService.getOne("/tombstones/" + resource.getParent()) != null)
                || (resource.getCopyOf() != null && etcdService.getOne("/tombstones/" + resource.getCopyOf()) != null);
    }

    private String albumPrefix() { return "/pending-albums/" + context.uuid + "/"; }

    private String uploadAlbum(MultipartFile file, String requestId) throws IOException {
        String name = filename(file.getOriginalFilename());
        String albumId = uploadId(requestId, "album");
        JSONObject album = new JSONObject(); album.put("uuid", albumId); album.put("filename", name);
        // Persist the assembly intent first. The original archive is a normal durable resource
        // until every page and the ordered album source have completed publication.
        etcdService.put(albumPrefix() + albumId, album.toJSONString());
        try (InputStream input = file.getInputStream()) { publish(input, name, albumId, null, false, true); }
        FileObject existingAlbum = context.query(albumId);
        if (existingAlbum == null || !"1".equals(existingAlbum.getAlbumAvailable())) finishAlbum(album);
        else etcdService.delete(albumPrefix() + albumId);
        return context.buildUrl(albumId);
    }

    private synchronized void finishAlbum(JSONObject album) throws IOException {
        String albumId = album.getString("uuid");
        FileObject resource = context.query(albumId);
        if (resource == null) return; // Its durable publication intent is retried before album assembly.
        if (isDeleted(resource)) { etcdService.delete(albumPrefix() + albumId); return; }
        String name = resource.getFileName();
        int titleDot = name.lastIndexOf('.');
        String title = titleDot > 0 ? name.substring(0, titleDot) : name;
        if (title.startsWith("[Comic]")) title = title.substring(7);
        if (title.startsWith("[Picture]")) title = title.substring(9);
        List<Path> pages = List.of();
        try {
            // Extraction uses a .zip suffix to select the decoder; the archive itself is stored by UUID.
            Path extractionInput = Files.createTempFile("bootfs-album-", ".zip");
            try {
                Files.copy(Path.of(resource.getPath()), extractionInput, StandardCopyOption.REPLACE_EXISTING);
                pages = ComicArchiveProcessor.extraction(extractionInput);
            } finally { Files.deleteIfExists(extractionInput); }
            if (pages.isEmpty()) throw new IOException("Archive contains no readable pages");
            List<String> resources = new ArrayList<>();
            int pageNumber = 1;
            for (Path page : pages) {
                if (isDeleted(resource)) throw new IOException("Album has been deleted");
                String childId = UUID.nameUUIDFromBytes((albumId + ":" + pageNumber).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                FileObject existing = context.query(childId);
                if (existing == null || !Files.isRegularFile(Path.of(existing.getPath()))) {
                    try (InputStream input = Files.newInputStream(page)) {
                        publish(input, title + "_" + pageNumber + extension(page.getFileName().toString()), childId, albumId, false, false);
                    }
                }
                resources.add(context.buildThumbUrl(childId));
                pageNumber++;
            }
            if (isDeleted(resource)) throw new IOException("Album has been deleted");
            resource.setAlbumAvailable("1"); context.record(resource, albumId);
            meiliSearchService.addToFullText(FullTextDocument.builder().uuid(albumId).title(title)
                    .fileName(name).resources(JSONArray.from(resources)).poster(resources.get(0)).thumbUrl(resources.get(0))
                    .albumAvailable("1").updatedAt(LocalDateTime.now()).size(resource.getSize()).contextUuid(context.uuid).build());
            etcdService.delete(albumPrefix() + albumId);
        } finally {
            for (Path page : pages) Files.deleteIfExists(page);
        }
    }

    /** Tombstone first; deleting either protocol view removes every binding to the same resource. */
    public boolean deleteResource(String uuid) { return deleteResource(uuid, new HashSet<>()); }

    private boolean deleteResource(String uuid, Set<String> visited) {
        if (!visited.add(uuid)) return true;
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("uuid is invalid");
        if (etcdService.getOne("/tombstones/" + uuid) == null) {
            etcdService.compareAndSet("/tombstones/" + uuid, null, Long.toString(System.currentTimeMillis()));
        }
        // A later overwrite can delete this generation before its lost-reply recovery runs.
        // Carry forward the durable obligation to retire its own predecessor, even across owners.
        etcdService.scan("/pending-publications/").values().forEach(raw -> {
            JSONObject pending = JSONObject.parseObject(raw);
            JSONObject file = pending.getJSONObject("file");
            if (file != null && uuid.equals(file.getString("uuid"))) retireSupersededPredecessor(pending, visited);
        });
        List<String> children = new ArrayList<>();
        for (FileObject resource : context.STORAGE.values()) {
            if (uuid.equals(resource.getParent())) children.add(resource.getUuid());
        }
        // Resolve relationships from authoritative documents, including children on offline owners.
        etcdService.scan("/documents/full-text/").forEach((key, raw) -> {
            JSONObject document = JSONObject.parseObject(raw);
            if (uuid.equals(document.getString("parent")) || uuid.equals(document.getString("copyOf"))) {
                String child = document.getString("uuid");
                if (child != null && !children.contains(child)) children.add(child);
            }
        });
        for (String child : children) deleteResource(child, visited);
        etcdService.put("/cleanup/" + context.uuid + "/" + uuid, "object");
        meiliSearchService.deleteDocumentFromIndex("full-text", uuid);
        meiliSearchService.deleteDocumentFromIndex("image-host", uuid);
        if (s3KeyIndex != null) s3KeyIndex.deleteBindingsForResource(uuid);
        context.remove(uuid);
        etcdService.delFileAndReplicas(uuid);
        etcdService.delete(replicationPrefix() + uuid);
        etcdService.delete(albumPrefix() + uuid);
        try {
            cleanupLocalResource(uuid);
            etcdService.delete("/cleanup/" + context.uuid + "/" + uuid);
        } catch (IOException failure) {
            log.warn("Resource {} deleted; physical cleanup needs retry", uuid, failure);
        }
        return true;
    }

    /** Completes shared logical deletion even when the object's owning node is offline. */
    public void resumeGlobalDeletions() {
        Set<String> deleted = new HashSet<>();
        etcdService.scan("/tombstones/").keySet().forEach(key -> deleted.add(key.substring("/tombstones/".length())));
        Set<String> incomplete = new TreeSet<>();
        NavigableMap<String, String> documents = etcdService.scan("/documents/");
        documents.forEach((key, raw) -> {
            // Legacy gallery records are archival inputs; the canonical resource controls visibility.
            if (!key.startsWith("/documents/full-text/")) return;
            JSONObject doc = JSONObject.parseObject(raw);
            if (doc.getBooleanValue("_bootfsDeleted")) return;
            String id = doc.getString("uuid");
            if (deleted.contains(id) || deleted.contains(doc.getString("parent")) || deleted.contains(doc.getString("copyOf"))) incomplete.add(id);
        });
        etcdService.scan("/files/").keySet().forEach(key -> {
            String[] parts = key.substring("/files/".length()).split("/");
            if (parts.length > 0 && deleted.contains(parts[0])) incomplete.add(parts[0]);
            if (parts.length >= 3 && "replicas".equals(parts[1]) && deleted.contains(parts[2])) incomplete.add(parts[2]);
        });
        etcdService.scan("/s3/").values().forEach(raw -> {
            String id = JSONObject.parseObject(raw, S3ObjectMeta.class).getUuid();
            if (deleted.contains(id)) incomplete.add(id);
        });
        etcdService.scan("/pending-publications/").values().forEach(raw -> {
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

    /** Startup migration: existing normal resources become addressable by their immutable UUID. */
    public void bootstrapExistingBindings() {
        if (s3KeyIndex == null) return;
        Set<String> bound = s3KeyIndex.boundResourceIds();
        for (FileObject resource : context.STORAGE.values()) {
            if (resource.getParent() != null || resource.getCopyOf() != null || bound.contains(resource.getUuid())) continue;
            if (resource.getPath() == null || etcdService.getOne("/tombstones/" + resource.getUuid()) != null) continue;
            Path path = Path.of(resource.getPath());
            if (!Files.isRegularFile(path) || s3KeyIndex.getIndex(s3Bucket, resource.getUuid()) != null) continue;
            try (InputStream input = Files.newInputStream(path); DigestInputStream content = new DigestInputStream(input, md5())) {
                content.transferTo(OutputStream.nullOutputStream());
                String etag = HexFormat.of().formatHex(content.getMessageDigest().digest());
                s3KeyIndex.compareAndSet(s3Bucket, resource.getUuid(), null, new S3ObjectMeta(resource.getUuid(), Files.size(path),
                        etag, resource.getCreatedAt() == null ? Files.getLastModifiedTime(path).toMillis() : resource.getCreatedAt(),
                        resource.getUuid(), contentType(resource.getFileName())));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot expose existing resource through S3: " + resource.getUuid(), failure);
            }
        }
    }

    private void indexResource(FileObject resource) {
        LocalDateTime now = LocalDateTime.now();
        meiliSearchService.addToFullText(FullTextDocument.builder().uuid(resource.getUuid()).title(resource.getFileName())
                .poster(context.buildThumbUrl(resource.getUuid())).thumbUrl(context.buildThumbUrl(resource.getUuid()))
                .tags(new JSONArray()).createdAt(now).updatedAt(now).fileName(resource.getFileName()).size(resource.getSize())
                .copyOf(resource.getCopyOf()).hlsAvailable(resource.getHlsAvailable()).thumbAvailable(resource.getThumbAvailable())
                .parent(resource.getParent()).albumAvailable(resource.getAlbumAvailable()).nsfw(resource.getNsfw())
                .isPublicAccess(resource.getIsPublicAccess()).fileCreatedAt(resource.getCreatedAt()).contextUuid(context.uuid).build());
    }

    public void saveToLocal(String uuid, MultipartFile file) throws IOException {
        Path data = Path.of(tfsConfig.getPathPrefix());
        Files.createDirectories(data);
        Path target = data.resolve(uuid);
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            input.transferTo(output);
        } catch (IOException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    private String replicationPrefix() { return "/replication-jobs/" + context.uuid + "/"; }

    public void replication(File file, String fileId, String originFilename) {
        int requested = Math.max(0, (tfsConfig.getCopies() == null ? 1 : tfsConfig.getCopies()) - 1);
        if (requested == 0) return;
        JSONObject job = new JSONObject();
        job.put("uuid", fileId); job.put("filename", originFilename); job.put("replicas", requested);
        job.put("attempts", 0); job.put("nextAttempt", 0L);
        etcdService.compareAndSet(replicationPrefix() + fileId, null, job.toJSONString());
        scheduleReplication(fileId);
    }

    private void retryReplicationJobs() {
        etcdService.scan(replicationPrefix()).forEach((key, raw) -> {
            JSONObject job = JSONObject.parseObject(raw);
            if (job.getLongValue("nextAttempt") <= System.currentTimeMillis()) scheduleReplication(job.getString("uuid"));
        });
    }

    private void scheduleReplication(String fileId) {
        if (!replicationRunning.add(fileId)) return;
        try {
            CompletableFuture.runAsync(() -> {
                try { executeReplication(fileId); }
                catch (Exception failure) { log.warn("Replica repair will retry for {}", fileId, failure); }
                finally { replicationRunning.remove(fileId); }
            }, transferThreadPool);
        } catch (RuntimeException failure) { replicationRunning.remove(fileId); throw failure; }
    }

    private void executeReplication(String fileId) throws IOException {
        String raw = etcdService.getOne(replicationPrefix() + fileId);
        if (raw == null) return;
        JSONObject job = JSONObject.parseObject(raw);
        FileObject resource = context.query(fileId);
        if (resource == null || isDeleted(resource)) {
            etcdService.delete(replicationPrefix() + fileId);
            return;
        }
        String own = stripSlash(tfsConfig.getWebEntrypoint());
        Set<String> distinct = new TreeSet<>();
        for (String node : etcdService.getAllNodes()) if (node != null) distinct.add(node);
        List<String> nodes = new ArrayList<>(distinct);
        nodes.removeIf(node -> node == null || stripSlash(node).equals(own));
        // Stable rendezvous-style placement avoids choosing different receivers on every retry.
        nodes.sort(Comparator.comparing(node -> UUID.nameUUIDFromBytes((fileId + ":" + node).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));
        Set<String> confirmed = replicaNodes(fileId);
        int requested = job.getIntValue("replicas");
        for (String target : nodes) {
            if (confirmed.size() >= requested) break;
            if (confirmed.contains(stripSlash(target))) continue;
            if (isDeleted(resource)) break;
            try {
                fileTransferService.put(new File(resource.getPath()), fileId, job.getString("filename"), NodeObject.builder().url(target).build());
                confirmed = replicaNodes(fileId);
            } catch (Exception failure) { log.warn("Replica transfer {} to {} failed", fileId, target, failure); }
        }
        if (confirmed.size() >= requested) {
            etcdService.delete(replicationPrefix() + fileId);
        } else {
            int attempts = Math.min(30, job.getIntValue("attempts") + 1);
            job.put("attempts", attempts);
            job.put("nextAttempt", System.currentTimeMillis() + Math.min(60000L, 1000L << Math.min(6, attempts)));
            etcdService.compareAndSet(replicationPrefix() + fileId, raw, job.toJSONString());
        }
    }

    private Set<String> replicaNodes(String fileId) {
        Set<String> nodes = new HashSet<>();
        etcdService.scan("/replication-receipts/"+context.uuid+"/"+fileId+"/").values().forEach(raw -> nodes.add(stripSlash(JSONObject.parseObject(raw).getString("url"))));
        etcdService.scan("/files/" + fileId + "/replicas/").forEach((key, value) -> {
            if (!key.endsWith("/meta")) {
                int path = value.lastIndexOf("/f/");
                if (path > 0 && !stripSlash(value.substring(0, path)).equals(stripSlash(tfsConfig.getWebEntrypoint()))) {
                    nodes.add(stripSlash(value.substring(0, path)));
                }
            }
        });
        return nodes;
    }

    public Object[] download(String uuid, HttpServletRequest request) throws IOException {
        FileObject target = context.query(uuid);
        if (target == null || target.getPath() == null || isDeleted(target)) return null;
        Resource resource = new FileSystemResource(target.getPath());
        if (!resource.exists()) return null;
        return new Object[]{resource, target.getFileName(), Long.toString(resource.contentLength())};
    }

    private static String filename(String name) { return name == null || name.isBlank() ? "unnamed" : name; }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
    private static String stripSlash(String url) { return url == null ? "" : url.replaceAll("/+$", ""); }
    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String contentType(String filename) {
        String type = java.net.URLConnection.guessContentTypeFromName(filename);
        return type == null ? "application/octet-stream" : type;
    }

    @PreDestroy
    public void close() { transferThreadPool.shutdown(); }
}
