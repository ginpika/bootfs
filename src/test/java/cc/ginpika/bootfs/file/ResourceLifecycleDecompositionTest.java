package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceLifecycleDecompositionTest {
    @TempDir Path root;
    private RocksMetadataStore store;
    private TfsConfig config;
    private LocalFileInventory inventory;
    private MetadataRegistry registry;
    private ObjectKeyRepository keys;
    private ResourceSearchService search;
    private ThumbnailService thumbnails;
    private FileTransferService transfers;
    private ResourceDeletionService deletions;
    private ResourceReplicationService replications;
    private ResourcePublicationService publications;
    private ResourceRecoveryService recovery;

    @BeforeEach void setup() throws Exception {
        config = new TfsConfig();
        config.setRootPath(root.toString());
        config.setUniqueId("owner");
        config.setWebEntrypoint("http://owner");
        config.setCopies(1);
        store = new RocksMetadataStore(root.resolve("metadata"));
        NodeIdentity identity = new NodeIdentity(config);
        inventory = new LocalFileInventory(config, new FileCatalogRepository(store, config, identity), identity);
        inventory.load();
        registry = new MetadataRegistry(store, config, inventory);
        registry.registry();
        keys = new ObjectKeyRepository(registry);
        search = mock(ResourceSearchService.class);
        thumbnails = mock(ThumbnailService.class);
        when(thumbnails.thumbPath(anyString())).thenAnswer(call -> root.resolve("thumb").resolve(call.getArgument(0) + ".webp"));
        transfers = mock(FileTransferService.class);
        ObjectWritePolicy policy = new ObjectWritePolicy(config);
        ResourceWriteLock lock = new ResourceWriteLock();
        deletions = new ResourceDeletionService(inventory, config, registry, search, thumbnails, keys);
        replications = new ResourceReplicationService(inventory, config, registry, transfers, deletions);
        TextReplacementCommitter committer = new TextReplacementCommitter(inventory, registry, search, transfers, deletions, replications);
        publications = new ResourcePublicationService(
                inventory, config, registry, transfers, search, thumbnails, policy, lock, deletions, replications, committer, keys);
        AlbumPublicationService albums = new AlbumPublicationService(inventory, registry, search, policy, lock, deletions, publications, keys);
        recovery = new ResourceRecoveryService(publications, albums, transfers, replications, deletions,
                new ObjectKeyBootstrap(inventory, registry, policy, keys));
    }

    @AfterEach void close() {
        replications.close();
        store.close();
    }

    @Test void asyncReplicationRetriesWithBackoffAndClearsJobOnceReceiptsSatisfyTarget() throws Exception {
        registry.put("/cluster/node/peer-1", "http://peer-1");
        String uuid = publications.publish(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "note.txt", true)
                .file().getUuid();
        String hash = JSONObject.parseObject(registry.getOne("/files/" + uuid + "/meta")).getString("sha256");

        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) {
                firstAttempt.countDown();
                throw new IOException("peer temporarily down");
            }
            registry.put("/replication-receipts/owner/" + uuid + "/peer-1",
                    new JSONObject(java.util.Map.of("url", "http://peer-1",
                            "receipt", java.util.Map.of("sha256", hash))).toJSONString());
            return null;
        }).when(transfers).put(any(), eq(uuid), eq("note.txt"), any(NodeObject.class));

        replications.scheduleReplication(uuid, "note.txt", 2);
        assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));

        // Wait until backoff metadata is persisted after the failed transfer.
        long deadline = System.currentTimeMillis() + 5000;
        JSONObject job = null;
        while (System.currentTimeMillis() < deadline) {
            String raw = registry.getOne("/replication-jobs/owner/" + uuid);
            if (raw != null) {
                job = JSONObject.parseObject(raw);
                if (job.getIntValue("attempts") >= 1) break;
            }
            Thread.sleep(20);
        }
        assertNotNull(job);
        assertEquals(1, job.getIntValue("attempts"));
        assertTrue(job.getLongValue("nextAttempt") > 0);

        // Simulate retry window elapsed and trigger recovery.
        job.put("nextAttempt", 0L);
        registry.put("/replication-jobs/owner/" + uuid, job.toJSONString());
        recovery.resumePendingPublications();

        long clearDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < clearDeadline && registry.getOne("/replication-jobs/owner/" + uuid) != null) {
            Thread.sleep(20);
        }
        assertNull(registry.getOne("/replication-jobs/owner/" + uuid));
        verify(transfers, times(2)).put(any(), eq(uuid), eq("note.txt"), any(NodeObject.class));
    }

    @Test void failedPhysicalCleanupRetainsCleanupRecordUntilRecoverySucceeds() throws Exception {
        PublishedResource published = publications.publish(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), "clip.mp4", true);
        String uuid = published.file().getUuid();
        Path thumbDir = root.resolve("thumb");
        Files.createDirectories(thumbDir);

        ThumbnailService failingThumbs = mock(ThumbnailService.class);
        Path linkDir = root.resolve("symlink-thumb");
        Files.createDirectories(root.resolve("real-thumb"));
        Files.createSymbolicLink(linkDir, root.resolve("real-thumb"));
        when(failingThumbs.thumbPath(uuid)).thenReturn(linkDir.resolve(uuid + ".webp"));

        ResourceDeletionService failingCleanup = new ResourceDeletionService(inventory, config, registry, search, failingThumbs, keys);
        assertTrue(failingCleanup.deleteResource(uuid));
        assertNotNull(registry.getOne("/tombstones/" + uuid));
        assertNotNull(registry.getOne("/cleanup/owner/" + uuid));
        assertNull(inventory.query(uuid));

        // Recovery with valid thumbnail directory completes physical cleanup and clears /cleanup/.
        deletions.cleanupLocalResource(uuid);
        registry.delete("/cleanup/owner/" + uuid);
        assertFalse(Files.exists(root.resolve("data").resolve(uuid)));
        assertNull(registry.getOne("/cleanup/owner/" + uuid));
    }
}
