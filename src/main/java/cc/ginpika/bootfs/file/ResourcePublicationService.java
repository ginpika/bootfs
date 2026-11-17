package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import cc.ginpika.bootfs.kernel.object.PayloadDigestMismatchException;
import cc.ginpika.bootfs.kernel.object.PublicationPendingException;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Core immutable resource publication, durability verification, recovery, and download service. */
@Slf4j
@Service
public class ResourcePublicationService {
    private final LocalFileInventory context;
    private final TfsConfig tfsConfig;
    private final MetadataRegistry metadataRegistry;
    private final FileTransferService fileTransferService;
    private final ResourceSearchService resourceSearch;
    private final ThumbnailService thumbnailService;
    private final ObjectWritePolicy writePolicy;
    private final ResourceWriteLock writeLock;
    private final ResourceDeletionService deletionService;
    private final ResourceReplicationService replicationService;
    private final TextReplacementCommitter textReplacementCommitter;

    @Autowired(required = false)
    private ObjectKeyRepository objectKeys;

    @Autowired
    public ResourcePublicationService(LocalFileInventory context, TfsConfig tfsConfig,
                                      MetadataRegistry metadataRegistry, FileTransferService fileTransferService,
                                      ResourceSearchService resourceSearch, ThumbnailService thumbnailService,
                                      ObjectWritePolicy writePolicy, ResourceWriteLock writeLock,
                                      ResourceDeletionService deletionService,
                                      ResourceReplicationService replicationService,
                                      TextReplacementCommitter textReplacementCommitter) {
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.metadataRegistry = metadataRegistry;
        this.fileTransferService = fileTransferService;
        this.resourceSearch = resourceSearch;
        this.thumbnailService = thumbnailService;
        this.writePolicy = writePolicy;
        this.writeLock = writeLock;
        this.deletionService = deletionService;
        this.replicationService = replicationService;
        this.textReplacementCommitter = textReplacementCommitter;
    }

    public ResourcePublicationService(LocalFileInventory context, TfsConfig tfsConfig,
                                      MetadataRegistry metadataRegistry, FileTransferService fileTransferService,
                                      ResourceSearchService resourceSearch, ThumbnailService thumbnailService,
                                      ObjectWritePolicy writePolicy, ResourceWriteLock writeLock,
                                      ResourceDeletionService deletionService,
                                      ResourceReplicationService replicationService,
                                      TextReplacementCommitter textReplacementCommitter,
                                      ObjectKeyRepository objectKeys) {
        this(context, tfsConfig, metadataRegistry, fileTransferService, resourceSearch, thumbnailService,
                writePolicy, writeLock, deletionService, replicationService, textReplacementCommitter);
        this.objectKeys = objectKeys;
    }

    /** All uploads stage and fsync their contents before publishing a catalog entry. */
    public PublishedResource publish(InputStream input, String originalFilename, boolean defaultS3Binding) throws IOException {
        return publishWithIntent(input, filename(originalFilename), IdGenerator.getUniqueId(), null, false, defaultS3Binding, null, null);
    }

    public PublishedResource publishBoundObject(InputStream input, String key, String bucket, String contentType,
                                                String expectedBinding, String payloadHash) throws IOException {
        return publishBoundObject(input, key, bucket, contentType, expectedBinding, payloadHash, null);
    }

    public PublishedResource publishBoundObject(InputStream input, String key, String bucket, String contentType,
                                                String expectedBinding, String payloadHash, String requestId) throws IOException {
        JSONObject binding = s3Binding(key, bucket, contentType, expectedBinding, payloadHash);
        return publishWithIntent(input, key, uploadId(requestId, "s3:" + bucket + ":" + key), null, false, false, binding, null);
    }

    static JSONObject s3Binding(String key, String bucket, String contentType, String expectedBinding, String payloadHash) {
        JSONObject binding = new JSONObject();
        binding.put("key", key);
        binding.put("bucket", bucket);
        binding.put("contentType", contentType);
        binding.put("expected", expectedBinding);
        binding.put("payloadHash", payloadHash);
        return binding;
    }

    public String uploadId(String requestId, String scope) {
        if (requestId == null || requestId.isBlank()) return IdGenerator.getUniqueId();
        if (!requestId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid X-BootFS-Request-Id");
        return resolveUploadId(UUID.nameUUIDFromBytes((context.uuid + ":" + scope + ":" + requestId).getBytes(StandardCharsets.UTF_8)).toString());
    }

    PublishedResource publishWithIntent(InputStream input, String name, String uuid, String parent,
                                      boolean album, boolean defaultS3Binding, JSONObject s3Binding, JSONObject textReplacement) throws IOException {
        synchronized (writeLock) {
            ObjectWritePolicy.TaskPolicy taskPolicy = writePolicy.captureTaskPolicy();
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
                     OutputStream output = Files.newOutputStream(staged)) {
                    size = content.transferTo(output);
                }
                String sha256Hex = HexFormat.of().formatHex(sha256.digest());
                if (s3Binding != null && s3Binding.getString("payloadHash") != null
                        && !"UNSIGNED-PAYLOAD".equals(s3Binding.getString("payloadHash"))
                        && !sha256Hex.equalsIgnoreCase(s3Binding.getString("payloadHash"))) {
                    throw new PayloadDigestMismatchException();
                }
                String pending = metadataRegistry.getOne(publicationPrefix() + uuid);
                if (pending != null) {
                    JSONObject saved = JSONObject.parseObject(pending);
                    if (!sha256Hex.equals(saved.getString("sha256")) || !name.equals(saved.getJSONObject("file").getString("fileName"))) {
                        throw new ResourceWriteConflictException("Request ID was already used for different content");
                    }
                    return finishPublication(saved);
                }
                String published = metadataRegistry.getOne("/files/" + uuid + "/meta");
                FileObject previous = context.query(uuid);
                if (published != null && previous != null && !deletionService.isDeleted(previous)) {
                    JSONObject metadata = JSONObject.parseObject(published);
                    if (!sha256Hex.equals(metadata.getString("sha256")) || !name.equals(previous.getFileName())) {
                        throw new ResourceWriteConflictException("Request ID was already used for different content");
                    }
                    return new PublishedResource(previous, metadata.getString("etag"));
                }
                if (metadataRegistry.getOne("/tombstones/" + uuid) != null) {
                    throw new ResourceWriteConflictException("Request ID belongs to a deleted or superseded resource");
                }
                DurableFiles.syncFile(staged);
                DurableFiles.syncDirectory(data);
                String etag = HexFormat.of().formatHex(digest.digest());
                FileObject resource = new FileObject(data.resolve(uuid).toString(), uuid, name, size);
                resource.setCreatedAt(System.currentTimeMillis());
                resource.setParent(parent);
                if (album) resource.setAlbumAvailable("1");
                if (textReplacement != null) {
                    resource = textReplacement.getJSONObject("resource").toJavaObject(FileObject.class);
                    resource.setUuid(uuid);
                    resource.setSize(size);
                    resource.setPath(data.resolve(uuid).toString());
                }
                JSONObject intent = new JSONObject();
                JSONObject persisted = JSONObject.from(resource);
                persisted.remove("path");
                intent.put("file", persisted);
                intent.put("etag", etag);
                intent.put("sha256", sha256Hex);
                intent.put("stage", staged.getFileName().toString());
                intent.put("defaultS3Binding", defaultS3Binding);
                intent.put("requiredCopies", taskPolicy.requiredCopies());
                intent.put("prepareTimeoutMs", taskPolicy.prepareTimeoutMs());
                intent.put("copies", taskPolicy.copies());
                intent.put("receipts", new JSONObject());
                if (s3Binding != null) intent.put("s3Binding", s3Binding);
                if (textReplacement != null) intent.put("textReplacement", textReplacement);
                // Mark before the RPC: a timeout can mean this intent has already committed.
                intentAttempted = true;
                metadataRegistry.put(publicationPrefix() + uuid, intent.toJSONString());
                return finishPublication(intent);
            } catch (Exception failure) {
                // Never delete bytes following a potentially committed write with an uncertain reply.
                // A durable intent is resumed after restart; unregistered files remain safe orphan candidates.
                if (failure instanceof ResourceWriteConflictException || failure instanceof PayloadDigestMismatchException
                        || failure instanceof PublicationPendingException) {
                    throw (IOException) failure;
                }
                if (intentAttempted) {
                    throw new PublicationPendingException(uuid,
                            "Publication was not acknowledged; query the upload status or retry with the same request ID", failure);
                }
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("Resource publication failed before a durable intent was attempted", failure);
            } finally {
                if (!intentAttempted) Files.deleteIfExists(staged);
            }
        }
    }

    PublishedResource finishPublication(JSONObject intent) throws IOException {
        synchronized (writeLock) {
            FileObject requested = intent.getJSONObject("file").toJavaObject(FileObject.class);
            String uuid = requested.getUuid();
            if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid publication identity");
            Path data = Path.of(tfsConfig.getPathPrefix());
            Path target = data.resolve(uuid);
            requested.setPath(target.toString());
            JSONObject textReplacement = intent.getJSONObject("textReplacement");
            if (textReplacement != null && !textReplacement.getBooleanValue("stableUuid")
                    && metadataRegistry.getOne("/tombstones/" + textReplacement.getString("originalId")) == null
                    && metadataRegistry.getOne("/text-replacements/" + textReplacement.getString("originalId") + "/" + textReplacement.getString("version")) == null) {
                // Upgrade an uncommitted edit journal created by the older UUID-replacement flow.
                textReplacement.put("stableUuid", true);
                intent.put("receipts", new JSONObject());
                metadataRegistry.put(publicationPrefix() + uuid, intent.toJSONString());
            }
            if (textReplacement != null && textReplacement.getBooleanValue("stableUuid")
                    && metadataRegistry.getOne("/tombstones/" + textReplacement.getString("originalId")) != null) {
                deletionService.discardPublication(uuid, intent);
                throw new IOException("Resource was deleted before its text save completed");
            }
            if (deletionService.isDeleted(requested)) {
                deletionService.discardPublication(uuid, intent);
                throw new IOException("Resource was deleted before publication completed");
            }
            String stageName = intent.getString("stage");
            if (stageName == null || !stageName.matches("\\.upload-[A-Za-z0-9_-]+\\.part")) {
                throw new IOException("Invalid staged filename");
            }
            Path staged = data.resolve(stageName);
            if (!Files.isRegularFile(target)) {
                if (!Files.isRegularFile(staged)) throw new IOException("Pending publication has no staged content: " + uuid);
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                DurableFiles.syncDirectory(data);
            } else if (Files.isRegularFile(staged)) {
                if (Files.mismatch(staged, target) != -1) throw new IOException("Immutable object identity collision: " + uuid);
                Files.delete(staged);
                DurableFiles.syncDirectory(data);
            }
            // Retry after a lost reply or a failed rename fsync must re-establish the same durability boundary.
            DurableFiles.syncFile(target);
            DurableFiles.syncDirectory(data);
            // No public inventory, routing, search source or S3 key exists until the byte policy is met.
            ensureDurability(intent, requested, target);
            if (intent.getJSONObject("textReplacement") != null) {
                return textReplacementCommitter.finish(intent, requested, target);
            }
            FileObject resource = context.query(uuid);
            if (resource == null) {
                String previous = metadataRegistry.getOne("/nodes/" + context.uuid + "/catalog/" + uuid);
                resource = previous == null ? requested : JSONObject.parseObject(previous, FileObject.class);
                resource.setPath(target.toString());
                context.record(resource, uuid);
            }
            if (deletionService.isDeleted(resource)) {
                deletionService.discardPublication(uuid, intent);
                throw new IOException("Resource is deleted");
            }
            String etag = intent.getString("etag");
            if (metadataRegistry.getOne("/files/" + uuid) == null) {
                JSONObject metadata = new JSONObject();
                metadata.put("size", resource.getSize());
                metadata.put("ext", extension(resource.getFileName()));
                metadata.put("etag", etag);
                metadata.put("sha256", intent.getString("sha256"));
                metadata.put("requiredCopies", Math.max(1, intent.getIntValue("requiredCopies")));
                JSONObject confirmations = new JSONObject();
                confirmations.put(context.uuid, intent.getString("sha256"));
                JSONObject receipts = intent.getJSONObject("receipts");
                if (receipts != null) receipts.forEach((node, receipt) -> confirmations.put(node, receipt));
                metadata.put("durableNodes", confirmations);
                metadataRegistry.putFile(uuid, context.buildUrl(uuid), metadata.toJSONString());
            }
            JSONObject explicitBinding = intent.getJSONObject("s3Binding");
            if (explicitBinding != null) {
                if (objectKeys == null) throw new IOException("S3 must be enabled to resume this publication");
                String bucket = explicitBinding.getString("bucket"), key = explicitBinding.getString("key");
                String expected = explicitBinding.getString("expected");
                ObjectKeyBinding current = objectKeys.find(bucket, key);
                if (current == null || !uuid.equals(current.getUuid())) {
                    ObjectKeyBinding replacement = new ObjectKeyBinding(uuid, resource.getSize(), etag,
                            resource.getCreatedAt(), key, explicitBinding.getString("contentType"));
                    if (!objectKeys.compareAndSet(bucket, key, expected, replacement)) {
                        current = objectKeys.find(bucket, key);
                        if (current == null || !uuid.equals(current.getUuid())) {
                            deletionService.discardPublication(uuid, intent);
                            throw new ResourceWriteConflictException("The object changed while this upload was in progress");
                        }
                    }
                }
            }
            if (metadataRegistry.getOne("/documents/full-text/" + uuid) == null) indexResource(resource);
            if (intent.getBooleanValue("defaultS3Binding") && objectKeys != null && resource.getParent() == null) {
                objectKeys.bindFilename(writePolicy.bucket(), resource.getFileName(), new ObjectKeyBinding(uuid, resource.getSize(), etag,
                        resource.getCreatedAt() == null ? requested.getCreatedAt() : resource.getCreatedAt(), null,
                        contentType(resource.getFileName())));
            }
            if (deletionService.isDeleted(resource)) {
                deletionService.discardPublication(uuid, intent);
                throw new IOException("Resource is deleted");
            }
            if (explicitBinding != null && explicitBinding.getString("expected") != null) {
                ObjectKeyBinding previous = JSONObject.parseObject(explicitBinding.getString("expected"), ObjectKeyBinding.class);
                if (!uuid.equals(previous.getUuid()) && !deletionService.hasRetainedVersion(previous.getUuid())) {
                    deletionService.deleteResource(previous.getUuid());
                }
            }
            // Completion intent is cleared only after required metadata and projection sources are durable.
            fileTransferService.publicationCommitted(uuid);
            metadataRegistry.delete(publicationPrefix() + uuid);
            try {
                replicationService.scheduleReplication(uuid, resource.getFileName(),
                        intent.containsKey("copies") ? intent.getIntValue("copies") : writePolicy.defaultCopies());
            } catch (RuntimeException optional) {
                log.warn("Replication scheduling needs retry for {}", uuid, optional);
            }
            try {
                thumbnailService.generateAsync(uuid);
            } catch (RuntimeException optional) {
                log.warn("Thumbnail scheduling needs retry for {}", uuid, optional);
            }
            return new PublishedResource(resource, etag);
        }
    }

    private void ensureDurability(JSONObject intent, FileObject resource, Path target) throws IOException {
        int required = Math.max(1, intent.getIntValue("requiredCopies"));
        if (required == 1) return;
        String publicationId = resource.getUuid(), id = publicationId, hash = intent.getString("sha256");
        JSONObject textReplacement = intent.getJSONObject("textReplacement");
        FileObject pendingResource = null;
        if (textReplacement != null && textReplacement.getBooleanValue("stableUuid")) {
            id = textReplacement.getString("originalId");
            pendingResource = JSONObject.from(resource).toJavaObject(FileObject.class);
            pendingResource.setUuid(id);
            pendingResource.setContentObjectId(publicationId);
        }
        if (hash == null || Files.size(target) != resource.getSize()
                || !hash.equals(ContentHash.sha256(target))) {
            throw new IOException("Local immutable bytes failed verification before publication");
        }
        JSONObject receipts = intent.getJSONObject("receipts");
        if (receipts == null) {
            receipts = new JSONObject();
            intent.put("receipts", receipts);
        }
        receipts.remove(context.uuid);
        if (receipts.size() + 1 >= required) return;
        int taskTimeout = intent.getIntValue("prepareTimeoutMs") > 0 ? intent.getIntValue("prepareTimeoutMs") : writePolicy.timeout();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(taskTimeout);
        List<Map.Entry<String, String>> candidates = new ArrayList<>(metadataRegistry.getNodesWithKeys().entrySet());
        candidates.removeIf(node -> node.getKey().equals(context.uuid) || stripSlash(node.getValue()).equals(stripSlash(tfsConfig.getWebEntrypoint())));
        String placementId = id;
        candidates.sort(Comparator.comparing(node -> UUID.nameUUIDFromBytes((placementId + ":" + node.getKey()).getBytes(StandardCharsets.UTF_8)).toString()));
        Set<String> attemptedEndpoints = new HashSet<>();
        Exception last = null;
        for (Map.Entry<String, String> candidate : candidates) {
            if (receipts.size() + 1 >= required) break;
            if (receipts.containsKey(candidate.getKey()) || !attemptedEndpoints.add(stripSlash(candidate.getValue()))) continue;
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) break;
            try {
                NodeObject node = NodeObject.builder().url(candidate.getValue()).build();
                int timeout = (int) Math.min(remaining, Math.max(1, taskTimeout / Math.max(1, candidates.size())));
                JSONObject receipt = pendingResource == null
                        ? fileTransferService.prepareRemote(target.toFile(), id, resource.getFileName(), hash, node, timeout)
                        : fileTransferService.prepareRemote(target.toFile(), id, resource.getFileName(), hash, node, timeout, pendingResource);
                if (receipt == null || !candidate.getKey().equals(receipt.getString("nodeId"))
                        || !id.equals(receipt.getString("objectId")) || !hash.equals(receipt.getString("sha256"))
                        || resource.getSize() != receipt.getLongValue("size")) {
                    throw new IOException("Replica acknowledgement does not match node and content");
                }
                receipts.put(candidate.getKey(), receipt);
                // A lost reply here retains the same content identity and is safely retried.
                metadataRegistry.put(publicationPrefix() + publicationId, intent.toJSONString());
            } catch (Exception failure) {
                last = failure;
            }
        }
        if (receipts.size() + 1 < required) {
            throw new PublicationPendingException(id,
                    "Publication pending: " + (receipts.size() + 1) + "/" + required + " durable nodes confirmed; recovery will retry", last);
        }
    }

    public void resumePendingPublications() {
        for (String raw : metadataRegistry.scan(publicationPrefix()).values()) {
            try {
                finishPublication(JSONObject.parseObject(raw));
            } catch (Exception failure) {
                log.warn("Pending resource publication will retry: {}", failure.getMessage());
            }
        }
    }

    public String resolveUploadId(String uuid) {
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid upload ID");
        for (int hop = 0; hop < 16; hop++) {
            String mapped = metadataRegistry.getOne("/upload-aliases/" + context.uuid + "/" + uuid);
            if (mapped == null) return uuid;
            if (!mapped.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("Invalid upload alias");
            uuid = mapped;
        }
        throw new IllegalStateException("Upload alias chain is cyclic or too long");
    }

    public String publicationState(String uuid) {
        uuid = resolveUploadId(uuid);
        if (metadataRegistry.getOne("/tombstones/" + uuid) != null) return "DELETED";
        for (String key : metadataRegistry.scan("/pending-publications/").keySet()) {
            if (key.endsWith("/" + uuid)) return "PENDING";
        }
        return metadataRegistry.getOne("/files/" + uuid) != null ? "READY" : "UNKNOWN";
    }

    public FileDownload openDownload(String uuid) throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
            FileObject target = context.query(uuid);
            if (target == null || target.getPath() == null || deletionService.isDeleted(target)) return null;
            String raw = metadataRegistry.getOne("/files/" + uuid + "/meta");
            JSONObject meta = raw == null ? null : JSONObject.parseObject(raw);
            String contentId = target.getContentObjectId() == null ? uuid : target.getContentObjectId();
            String committedId = meta == null ? contentId : meta.getString("contentObjectId");
            if (committedId == null) committedId = uuid;
            if (!contentId.equals(committedId)) continue;
            Path path = Path.of(target.getPath());
            if (!Files.exists(path)) return null;
            return new FileDownload(path, target.getFileName(), Files.size(path),
                    meta == null ? null : meta.getString("etag"), meta != null && meta.getString("textVersion") != null);
        }
        throw new ResourceWriteConflictException("File is being updated; retry the download");
    }

    private void indexResource(FileObject resource) {
        LocalDateTime now = LocalDateTime.now();
        resourceSearch.addToFullText(FullTextDocument.builder().uuid(resource.getUuid()).title(resource.getFileName())
                .poster(context.buildThumbUrl(resource.getUuid())).thumbUrl(context.buildThumbUrl(resource.getUuid()))
                .tags(new JSONArray()).createdAt(now).updatedAt(now).fileName(resource.getFileName()).size(resource.getSize())
                .copyOf(resource.getCopyOf()).hlsAvailable(resource.getHlsAvailable()).thumbAvailable(resource.getThumbAvailable())
                .parent(resource.getParent()).albumAvailable(resource.getAlbumAvailable()).nsfw(resource.getNsfw())
                .isPublicAccess(resource.getIsPublicAccess()).fileCreatedAt(resource.getCreatedAt()).contextUuid(context.uuid).build());
    }

    private String publicationPrefix() {
        return "/pending-publications/" + context.uuid + "/";
    }

    static String filename(String name) {
        return name == null || name.isBlank() ? "unnamed" : name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String stripSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String contentType(String filename) {
        String type = URLConnection.guessContentTypeFromName(filename);
        return type == null ? "application/octet-stream" : type;
    }
}
