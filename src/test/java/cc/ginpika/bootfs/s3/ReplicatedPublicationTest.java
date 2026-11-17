package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.*;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;

import cc.ginpika.bootfs.file.FileTransferService;
import cc.ginpika.bootfs.file.FileService;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.object.*;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReplicatedPublicationTest {
    @TempDir Path root;
    RocksMetadataStore store;
    LocalFileInventory owner;
    TfsConfig config;
    MetadataRegistry routes;
    FileTransferService transport;
    ObjectWritePolicy policy;
    ResourceReplicationService replications;
    ResourceRecoveryService recovery;
    FileService files;
    ObjectKeyRepository keys;
    Map<String,FileTransferService> receivers = new HashMap<>();
    Map<String,LocalFileInventory> contexts = new HashMap<>();
    AtomicBoolean unavailable = new AtomicBoolean();
    AtomicBoolean loseReply = new AtomicBoolean();

    @BeforeEach void setup() throws Exception {
        store = new RocksMetadataStore(root.resolve("metadata"));
        for (String node : List.of("owner", "b", "c")) {
            TfsConfig cfg = new TfsConfig(); cfg.setRootPath(root.resolve(node).toString()); cfg.setUniqueId(node);
            cfg.setWebEntrypoint("http://" + node); cfg.setCopies(3);
            NodeIdentity identity = new NodeIdentity(cfg);
            LocalFileInventory context = new LocalFileInventory(cfg, new FileCatalogRepository(store, cfg, identity), identity); context.load(); contexts.put(node, context);
            MetadataRegistry metadata = new MetadataRegistry(store, cfg, context); metadata.registry();
            FileTransferService receiver = new FileTransferService();
            ReflectionTestUtils.setField(receiver, "context", context); ReflectionTestUtils.setField(receiver, "tfsConfig", cfg);
            ReflectionTestUtils.setField(receiver, "metadataRegistry", metadata); ReflectionTestUtils.setField(receiver, "resourceSearch", mock(ResourceSearchService.class));
            receivers.put("http://" + node, receiver);
            if (node.equals("owner")) { owner = context; config = cfg; routes = metadata; }
        }
        transport = mock(FileTransferService.class);
        when(transport.prepareRemote(any(), anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(call -> {
            String url = ((cc.ginpika.bootfs.domain.dto.NodeObject)call.getArgument(4)).getUrl();
            if (unavailable.get()) throw new IOException("isolated");
            File file = call.getArgument(0);
            JSONObject receipt = receivers.get(url).prepare(new MockMultipartFile("file", Files.readAllBytes(file.toPath())),
                    call.getArgument(2), call.getArgument(1), call.getArgument(3));
            if (loseReply.getAndSet(false)) throw new IOException("durable receipt response lost");
            return receipt;
        });
        config.setCopies(1); // Keep optional asynchronous repair out of this protocol test.
        ThumbnailService thumbs = mock(ThumbnailService.class);
        when(thumbs.thumbPath(anyString())).thenAnswer(call -> root.resolve("owner/data/thumb/" + call.getArgument(0) + ".webp"));
        ResourceSearchService search = mock(ResourceSearchService.class);
        policy = new ObjectWritePolicy(config);
        ReflectionTestUtils.setField(policy, "writePolicy", "replicated");
        ReflectionTestUtils.setField(policy, "requiredCopies", 3);
        policy.validateWritePolicy();
        keys = new ObjectKeyRepository(routes);
        ResourceWriteLock lock = new ResourceWriteLock();
        ResourceDeletionService deletions = new ResourceDeletionService(owner, config, routes, search, thumbs, keys);
        replications = new ResourceReplicationService(owner, config, routes, transport, deletions);
        TextReplacementCommitter textCommitter = new TextReplacementCommitter(owner, routes, search, transport, deletions, replications);
        ResourcePublicationService publications = new ResourcePublicationService(
                owner, config, routes, transport, search, thumbs, policy, lock, deletions, replications, textCommitter, keys);
        AlbumPublicationService albums = new AlbumPublicationService(owner, routes, search, policy, lock, deletions, publications, keys);
        files = new FileService(owner, policy, publications, albums);
        recovery = new ResourceRecoveryService(publications, albums, transport, replications, deletions,
                new ObjectKeyBootstrap(owner, routes, policy, keys));
    }
    @AfterEach void close() { replications.close(); store.close(); }

    @Test void unavailableNodesCannotPublishAnyViewAndRestartResumesTheSameImmutableUpload() throws Exception {
        unavailable.set(true);
        PublicationPendingException failure = assertThrows(PublicationPendingException.class,
                () -> files.publish(new ByteArrayInputStream(new byte[]{1,2,3}), "safe.bin", true));
        String uuid = failure.getUploadId();
        assertTrue(owner.cachedFiles().isEmpty()); assertTrue(store.scan("/files/").isEmpty()); assertTrue(store.scan("/s3/").isEmpty());
        assertEquals(1, store.scan("/pending-publications/").size()); assertTrue(Files.exists(root.resolve("owner/data/" + uuid)));
        // Recovery uses the persisted policy even after the configured policy is lowered.
        ReflectionTestUtils.setField(policy, "writePolicy", "single-local");
        recovery.resumePendingPublications(); assertNull(keys.find("bootfs", "safe.bin"));
        unavailable.set(false); recovery.resumePendingPublications();
        assertEquals(uuid, keys.find("bootfs", "safe.bin").getUuid()); assertEquals(Set.of(uuid), owner.cachedFiles().stream().map(FileObject::getUuid).collect(java.util.stream.Collectors.toSet()));
        assertTrue(store.scan("/pending-publications/").isEmpty());
        JSONObject meta = JSONObject.parseObject(store.get("/files/" + uuid + "/meta"));
        assertEquals(3, meta.getIntValue("requiredCopies")); assertEquals(Set.of("owner", "b", "c"), meta.getJSONObject("durableNodes").keySet());
        assertTrue(contexts.get("b").cachedFiles().isEmpty(), "Prepared bytes must not be publicly registered before promotion");
        receivers.get("http://b").resumePreparedReplicas(); receivers.get("http://c").resumePreparedReplicas();
        assertEquals(1, contexts.get("b").cachedCount()); assertEquals(1, contexts.get("c").cachedCount());
        assertTrue(store.scan("/prepared-replicas/").isEmpty());
    }

    @Test void lostDurableReceiptIsRetriedWithoutAnotherReplicaIdentity() throws Exception {
        loseReply.set(true);
        assertThrows(PublicationPendingException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{4,5}), "retry", true));
        assertEquals(2, store.scan("/prepared-replicas/").size()); assertTrue(owner.cachedFiles().isEmpty());
        recovery.resumePendingPublications();
        assertEquals(1, owner.cachedCount()); assertEquals(2, store.scan("/prepared-replicas/").size());
        for (String node : List.of("b", "c")) {
            try (var paths = Files.list(root.resolve(node + "/data"))) { assertEquals(1, paths.count()); }
        }
    }

    @Test void aBlackholedFirstCandidateCannotConsumeEveryFallbackAttempt() throws Exception {
        ReflectionTestUtils.setField(policy,"requiredCopies",2); ReflectionTestUtils.setField(policy,"prepareTimeoutMs",3000);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) { Thread.sleep((int)call.getArgument(5)); throw new IOException("blackholed until transport deadline"); }
            String url = ((cc.ginpika.bootfs.domain.dto.NodeObject)call.getArgument(4)).getUrl(); File content = call.getArgument(0);
            return receivers.get(url).prepare(new MockMultipartFile("file",Files.readAllBytes(content.toPath())),call.getArgument(2),call.getArgument(1),call.getArgument(3));
        }).when(transport).prepareRemote(any(),anyString(),anyString(),anyString(),any(),anyInt());
        var published = files.publish(new ByteArrayInputStream(new byte[]{1}),"fallback",true);
        assertEquals(2,attempts.get()); assertNotNull(keys.find("bootfs","fallback"));
    }

    @Test void aliasOrWrongContentAcknowledgementsCannotSatisfyDistinctNodePolicy() throws Exception {
        doAnswer(call -> {
            JSONObject receipt = new JSONObject(); receipt.put("nodeId", "owner"); receipt.put("objectId", call.getArgument(1));
            receipt.put("sha256", call.getArgument(3)); receipt.put("size", ((File)call.getArgument(0)).length()); return receipt;
        }).when(transport).prepareRemote(any(), anyString(), anyString(), anyString(), any(), anyInt());
        assertThrows(PublicationPendingException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{1}), "alias", true));
        assertTrue(store.scan("/files/").isEmpty());
    }

    @Test void failedReplacementPreservesOldS3BindingUntilBytePolicyIsMet() throws Exception {
        ReflectionTestUtils.setField(policy, "writePolicy", "single-local");
        var first = files.publishBoundObject(new ByteArrayInputStream(new byte[]{1}), "key", "bootfs", "application/octet-stream", null, null);
        String expected = routes.getOne("/s3/bootfs/key");
        ReflectionTestUtils.setField(policy, "writePolicy", "replicated"); unavailable.set(true);
        PublicationPendingException pending = assertThrows(PublicationPendingException.class,
                () -> files.publishBoundObject(new ByteArrayInputStream(new byte[]{2}), "key", "bootfs", "application/octet-stream", expected, null));
        assertEquals(first.file().getUuid(), keys.find("bootfs", "key").getUuid());
        assertNull(store.get("/files/" + pending.getUploadId())); assertNotNull(owner.query(first.file().getUuid()));
        unavailable.set(false); recovery.resumePendingPublications();
        assertEquals(pending.getUploadId(), keys.find("bootfs", "key").getUuid()); assertNull(owner.query(first.file().getUuid()));
    }

    @Test void receiverRejectsCorruptionAndNeverExposesAPreparedObject() throws Exception {
        var receiver = receivers.get("http://b");
        MockMultipartFile content = new MockMultipartFile("file", new byte[]{8,9});
        assertThrows(IOException.class, () -> receiver.prepare(content, "test", "original", "0".repeat(64)));
        assertTrue(store.scan("/prepared-replicas/").isEmpty());
        String hash = ContentHash.sha256(new ByteArrayInputStream(new byte[]{8,9}));
        JSONObject receipt = receiver.prepare(content, "test", "original", hash);
        assertEquals(receipt, receiver.prepare(content, "test", "original", hash));
        assertTrue(store.scan("/files/").isEmpty()); assertTrue(contexts.get("b").cachedFiles().isEmpty());
        JSONObject prepared = JSONObject.parseObject(store.scan("/prepared-replicas/").values().iterator().next());
        // The replica UUID identifies the resource; its bytes use a separate content object ID.
        Path contentPath = root.resolve("b/data/" + prepared.getJSONObject("file").getString("contentObjectId"));
        assertArrayEquals(content.getBytes(), Files.readAllBytes(contentPath));
        Files.write(contentPath, new byte[]{0,0}, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IOException.class, () -> receiver.prepare(content, "test", "original", hash));
        assertTrue(store.scan("/files/").isEmpty()); assertTrue(contexts.get("b").cachedFiles().isEmpty());
    }

    @Test void callerRetryUsesTheSameUploadAndRejectsReusingTheTokenForDifferentBytes() throws Exception {
        unavailable.set(true);
        MockMultipartFile upload = new MockMultipartFile("file", "same.bin", "application/octet-stream", new byte[]{1,2});
        String id = assertThrows(PublicationPendingException.class, () -> files.upload(upload, "request-1")).getUploadId();
        assertEquals(id, assertThrows(PublicationPendingException.class, () -> files.upload(upload, "request-1")).getUploadId());
        assertEquals(1, store.scan("/pending-publications/").size());
        unavailable.set(false);
        assertEquals("http://owner/f/" + id, files.upload(upload, "request-1"));
        assertEquals("http://owner/f/" + id, files.upload(upload, "request-1"));
        assertEquals(1, owner.cachedCount());
        assertThrows(ResourceWriteConflictException.class, () -> files.upload(new MockMultipartFile("file", "same.bin", null, new byte[]{9}), "request-1"));
    }

    @Test void albumArchiveAndEveryPageRequireDurableConfirmation() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("1.png")); zip.write(new byte[]{1,2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("2.png")); zip.write(new byte[]{3,4}); zip.closeEntry();
        }
        files.uploadComic(new MockMultipartFile("file", "[Comic]pages.zip", "application/zip", archive.toByteArray()));
        assertEquals(3, owner.cachedCount());
        for (String uuid : owner.cachedFiles().stream().map(FileObject::getUuid).collect(java.util.stream.Collectors.toSet())) assertEquals(3, JSONObject.parseObject(store.get("/files/" + uuid + "/meta")).getIntValue("requiredCopies"));
        assertEquals(6, store.scan("/prepared-replicas/").size());
    }
}
