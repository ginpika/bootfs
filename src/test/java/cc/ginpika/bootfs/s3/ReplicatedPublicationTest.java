package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.object.*;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
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
    Context owner;
    TfsConfig config;
    EtcdService routes;
    FileTransferService transport;
    FileService files;
    S3KeyIndex keys;
    Map<String,FileTransferService> receivers = new HashMap<>();
    Map<String,Context> contexts = new HashMap<>();
    AtomicBoolean unavailable = new AtomicBoolean();
    AtomicBoolean loseReply = new AtomicBoolean();

    @BeforeEach void setup() throws Exception {
        store = new RocksMetadataStore(root.resolve("metadata"));
        for (String node : List.of("owner", "b", "c")) {
            TfsConfig cfg = new TfsConfig(); cfg.setRootPath(root.resolve(node).toString()); cfg.setUniqueId(node);
            cfg.setWebEntrypoint("http://" + node); cfg.setCopies(3);
            NodeIdentity identity = new NodeIdentity(cfg);
            Context context = new Context(cfg, new ContextIO(store, cfg, identity), identity); context.load(); contexts.put(node, context);
            EtcdService metadata = new EtcdService(store, cfg, context); metadata.registry();
            FileTransferService receiver = new FileTransferService();
            ReflectionTestUtils.setField(receiver, "context", context); ReflectionTestUtils.setField(receiver, "tfsConfig", cfg);
            ReflectionTestUtils.setField(receiver, "etcdService", metadata); ReflectionTestUtils.setField(receiver, "meiliSearchService", mock(MeiliSearchService.class));
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
        files = new FileService(owner, config, routes, transport, mock(MeiliSearchService.class), thumbs);
        keys = new S3KeyIndex(routes); ReflectionTestUtils.setField(files, "s3KeyIndex", keys);
        ReflectionTestUtils.setField(files, "writePolicy", "replicated"); ReflectionTestUtils.setField(files, "requiredCopies", 3);
        files.validateWritePolicy();
    }
    @AfterEach void close() { files.close(); store.close(); }

    @Test void unavailableNodesCannotPublishAnyViewAndRestartResumesTheSameImmutableUpload() throws Exception {
        unavailable.set(true);
        PublicationPendingException failure = assertThrows(PublicationPendingException.class,
                () -> files.publish(new ByteArrayInputStream(new byte[]{1,2,3}), "safe.bin", true));
        String uuid = failure.getUploadId();
        assertTrue(owner.STORAGE.isEmpty()); assertTrue(store.scan("/files/").isEmpty()); assertTrue(store.scan("/s3/").isEmpty());
        assertEquals(1, store.scan("/pending-publications/").size()); assertTrue(Files.exists(root.resolve("owner/data/" + uuid)));
        // Recovery uses the persisted policy even after the configured policy is lowered.
        ReflectionTestUtils.setField(files, "writePolicy", "single-local");
        files.resumePendingPublications(); assertNull(keys.getIndex("bootfs", uuid));
        unavailable.set(false); files.resumePendingPublications();
        assertEquals(uuid, keys.getIndex("bootfs", uuid).getUuid()); assertEquals(Set.of(uuid), owner.STORAGE.keySet());
        assertTrue(store.scan("/pending-publications/").isEmpty());
        JSONObject meta = JSONObject.parseObject(store.get("/files/" + uuid + "/meta"));
        assertEquals(3, meta.getIntValue("requiredCopies")); assertEquals(Set.of("owner", "b", "c"), meta.getJSONObject("durableNodes").keySet());
        assertTrue(contexts.get("b").STORAGE.isEmpty(), "Prepared bytes must not be publicly registered before promotion");
        receivers.get("http://b").resumePreparedReplicas(); receivers.get("http://c").resumePreparedReplicas();
        assertEquals(1, contexts.get("b").STORAGE.size()); assertEquals(1, contexts.get("c").STORAGE.size());
        assertTrue(store.scan("/prepared-replicas/").isEmpty());
    }

    @Test void lostDurableReceiptIsRetriedWithoutAnotherReplicaIdentity() throws Exception {
        loseReply.set(true);
        assertThrows(PublicationPendingException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{4,5}), "retry", true));
        assertEquals(2, store.scan("/prepared-replicas/").size()); assertTrue(owner.STORAGE.isEmpty());
        files.resumePendingPublications();
        assertEquals(1, owner.STORAGE.size()); assertEquals(2, store.scan("/prepared-replicas/").size());
        for (String node : List.of("b", "c")) {
            try (var paths = Files.list(root.resolve(node + "/data"))) { assertEquals(1, paths.count()); }
        }
    }

    @Test void aBlackholedFirstCandidateCannotConsumeEveryFallbackAttempt() throws Exception {
        ReflectionTestUtils.setField(files,"requiredCopies",2); ReflectionTestUtils.setField(files,"prepareTimeoutMs",3000);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) { Thread.sleep((int)call.getArgument(5)); throw new IOException("blackholed until transport deadline"); }
            String url = ((cc.ginpika.bootfs.domain.dto.NodeObject)call.getArgument(4)).getUrl(); File content = call.getArgument(0);
            return receivers.get(url).prepare(new MockMultipartFile("file",Files.readAllBytes(content.toPath())),call.getArgument(2),call.getArgument(1),call.getArgument(3));
        }).when(transport).prepareRemote(any(),anyString(),anyString(),anyString(),any(),anyInt());
        var published = files.publish(new ByteArrayInputStream(new byte[]{1}),"fallback",true);
        assertEquals(2,attempts.get()); assertNotNull(keys.getIndex("bootfs",published.file().getUuid()));
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
        ReflectionTestUtils.setField(files, "writePolicy", "single-local");
        var first = files.publishS3(new ByteArrayInputStream(new byte[]{1}), "key", "bootfs", "application/octet-stream", null, null);
        String expected = routes.getOne("/s3/bootfs/key");
        ReflectionTestUtils.setField(files, "writePolicy", "replicated"); unavailable.set(true);
        PublicationPendingException pending = assertThrows(PublicationPendingException.class,
                () -> files.publishS3(new ByteArrayInputStream(new byte[]{2}), "key", "bootfs", "application/octet-stream", expected, null));
        assertEquals(first.file().getUuid(), keys.getIndex("bootfs", "key").getUuid());
        assertNull(store.get("/files/" + pending.getUploadId())); assertNotNull(owner.query(first.file().getUuid()));
        unavailable.set(false); files.resumePendingPublications();
        assertEquals(pending.getUploadId(), keys.getIndex("bootfs", "key").getUuid()); assertNull(owner.query(first.file().getUuid()));
    }

    @Test void receiverRejectsCorruptionAndNeverExposesAPreparedObject() throws Exception {
        var receiver = receivers.get("http://b");
        MockMultipartFile content = new MockMultipartFile("file", new byte[]{8,9});
        assertThrows(IOException.class, () -> receiver.prepare(content, "test", "original", "0".repeat(64)));
        assertTrue(store.scan("/prepared-replicas/").isEmpty());
        String hash = ContentHash.sha256(new ByteArrayInputStream(new byte[]{8,9}));
        JSONObject receipt = receiver.prepare(content, "test", "original", hash);
        assertEquals(receipt, receiver.prepare(content, "test", "original", hash));
        assertTrue(store.scan("/files/").isEmpty()); assertTrue(contexts.get("b").STORAGE.isEmpty());
        Files.write(root.resolve("b/data/" + receipt.getString("replicaId")), new byte[]{0,0});
        assertThrows(IOException.class, () -> receiver.prepare(content, "test", "original", hash));
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
        assertEquals(1, owner.STORAGE.size());
        assertThrows(S3ConcurrentWriteException.class, () -> files.upload(new MockMultipartFile("file", "same.bin", null, new byte[]{9}), "request-1"));
    }

    @Test void albumArchiveAndEveryPageRequireDurableConfirmation() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("1.png")); zip.write(new byte[]{1,2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("2.png")); zip.write(new byte[]{3,4}); zip.closeEntry();
        }
        files.uploadComic(new MockMultipartFile("file", "[Comic]pages.zip", "application/zip", archive.toByteArray()));
        assertEquals(3, owner.STORAGE.size());
        for (String uuid : owner.STORAGE.keySet()) assertEquals(3, JSONObject.parseObject(store.get("/files/" + uuid + "/meta")).getIntValue("requiredCopies"));
        assertEquals(6, store.scan("/prepared-replicas/").size());
    }
}
