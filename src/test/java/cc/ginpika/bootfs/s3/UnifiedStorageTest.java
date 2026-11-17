package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.kernel.object.PayloadDigestMismatchException;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;

import cc.ginpika.bootfs.file.*;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedStorageTest {
    @TempDir Path root;
    private RocksMetadataStore metadata;
    private LocalFileInventory context;
    private ResourceDeletionService deletions;
    private ResourceReplicationService replications;
    private ResourcePublicationService publications;
    private ObjectKeyBootstrap bootstrap;
    private ResourceRecoveryService recovery;
    private FileService files;
    private S3StorageService s3;
    private ObjectKeyRepository keys;
    private S3ObjectListing listing;
    private ResourceSearchService search;
    private ThumbnailService thumbnails;
    private TfsConfig config;
    private MetadataRegistry routes;

    @BeforeEach void setup() throws Exception {
        config = new TfsConfig();
        config.setRootPath(root.toString()); config.setWebEntrypoint("http://node"); config.setCopies(1);
        NodeIdentity identity = new NodeIdentity(config);
        metadata = new RocksMetadataStore(root.resolve("metadata"));
        context = new LocalFileInventory(config, new FileCatalogRepository(metadata, config, identity), identity);
        context.load();
        routes = new MetadataRegistry(metadata, config, context);
        keys = new ObjectKeyRepository(routes);
        listing = new S3ObjectListing(keys);
        search = mock(ResourceSearchService.class);
        thumbnails = mock(ThumbnailService.class);
        when(thumbnails.thumbPath(anyString())).thenAnswer(call -> root.resolve("thumb").resolve(call.getArgument(0) + ".webp"));
        FileTransferService transfers = mock(FileTransferService.class);
        ObjectWritePolicy policy = new ObjectWritePolicy(config);
        ResourceWriteLock lock = new ResourceWriteLock();
        deletions = new ResourceDeletionService(context, config, routes, search, thumbnails, keys);
        replications = new ResourceReplicationService(context, config, routes, transfers, deletions);
        TextReplacementCommitter textCommitter = new TextReplacementCommitter(context, routes, search, transfers, deletions, replications);
        publications = new ResourcePublicationService(
                context, config, routes, transfers, search, thumbnails, policy, lock, deletions, replications, textCommitter, keys);
        AlbumPublicationService albums = new AlbumPublicationService(context, routes, search, policy, lock, deletions, publications, keys);
        bootstrap = new ObjectKeyBootstrap(context, routes, policy, keys);
        recovery = new ResourceRecoveryService(publications, albums, transfers, replications, deletions, bootstrap);
        files = new FileService(context, policy, publications, albums);
        s3 = new S3StorageService(config, context, routes, files, deletions, mock(ReverseProxyService.class), keys, new S3XmlWriter());
    }

    @AfterEach void close() { replications.close(); metadata.close(); }

    @Test void ordinaryUploadIsReadableThroughS3AndSharesTheSearchLifecycle() throws Exception {
        String url = files.upload(new MockMultipartFile("file", "photo.png", "image/png", "pixels".getBytes(StandardCharsets.UTF_8)));
        String uuid = url.substring(url.lastIndexOf('/') + 1);
        assertNotNull(keys.find("bootfs", "photo.png"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", "photo.png", new MockHttpServletRequest(), response);
        assertEquals("pixels", response.getContentAsString());
        assertEquals("image/png", response.getContentType());
        verify(search).addToFullText(argThat((FullTextDocument doc) -> uuid.equals(doc.getUuid())));
        verify(thumbnails).generateAsync(uuid);
        deletions.deleteResource(uuid);
        assertNull(keys.find("bootfs", "photo.png"));
        assertNull(context.query(uuid));
        assertFalse(Files.exists(root.resolve("data").resolve(uuid)));
        verify(search).deleteDocumentFromIndex("full-text", uuid);
    }

    @Test void s3PutIsANormalResourceAndS3DeleteRemovesItsFileAndSearchEntry() throws Exception {
        ObjectKeyBinding object = s3.putObject("bootfs", "archive/data.bin", request("payload"));
        assertNotNull(files.openDownload(object.getUuid()));
        assertEquals("archive/data.bin", context.query(object.getUuid()).getFileName());
        assertEquals(1, listing.listV2("bootfs", "", null, 1000, null).getContents().size());
        verify(search).addToFullText(argThat((FullTextDocument doc) -> object.getUuid().equals(doc.getUuid())));
        s3.deleteObject("bootfs", "archive/data.bin");
        assertNull(context.query(object.getUuid()));
        assertNull(keys.find("bootfs", "archive/data.bin"));
        assertFalse(Files.exists(root.resolve("data").resolve(object.getUuid())));
    }

    @Test void duplicateBrowserFilenamesKeepBothContentsAndRetryKeepsTheAssignedKey() throws Exception {
        var first = files.publish(new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)), "报告.txt", true);
        var second = files.publish(new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)), "报告.txt", true);
        String secondKey = "报告 (" + second.file().getUuid() + ").txt";
        assertEquals(first.file().getUuid(), keys.find("bootfs", "报告.txt").getUuid());
        assertEquals(second.file().getUuid(), keys.find("bootfs", secondKey).getUuid());
        assertNull(keys.find("bootfs", first.file().getUuid()));
        deletions.deleteResource(first.file().getUuid());
        ObjectKeyBinding rebound = keys.bindFilename("bootfs", "报告.txt", keys.find("bootfs", secondKey));
        assertEquals(secondKey, rebound.getKey());
        assertNull(keys.find("bootfs", "报告.txt"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", secondKey, new MockHttpServletRequest(), response);
        assertEquals("second", response.getContentAsString());
    }

    @Test void bootstrapMigratesLegacyUuidBindingsAndPreservesExplicitPathsAndNameCollisions() throws Exception {
        ObjectKeyBinding explicit = s3.putObject("bootfs", "photo.png", request("existing"));
        var legacy = files.publish(new ByteArrayInputStream("legacy".getBytes(StandardCharsets.UTF_8)), "photo.png", false);
        String uuid = legacy.file().getUuid();
        keys.put("bootfs", uuid, new ObjectKeyBinding(uuid, legacy.file().getSize(), legacy.etag(),
                legacy.file().getCreatedAt(), uuid, "image/png"));
        ObjectKeyBinding nested = s3.putObject("bootfs", "目录/文件.txt", request("nested"));
        bootstrap.bootstrapExistingBindings();
        String namedKey = "photo (" + uuid + ").png";
        assertEquals(uuid, keys.find("bootfs", namedKey).getUuid());
        assertNull(keys.find("bootfs", uuid));
        assertEquals(explicit.getUuid(), keys.find("bootfs", "photo.png").getUuid());
        assertEquals(nested.getUuid(), keys.find("bootfs", "目录/文件.txt").getUuid());
        assertNotNull(files.openDownload(uuid));
        long revision = metadata.revision();
        bootstrap.bootstrapExistingBindings();
        assertEquals(revision, metadata.revision());
        assertEquals(3, listing.listV2("bootfs", "", null, 1000, null).getContents().size());
    }

    @Test void interruptedLegacyMigrationKeepsBothResourcesAndCanResumeRemovingOnlyTheOldAlias() throws Exception {
        var legacy = files.publish(new ByteArrayInputStream(new byte[]{1, 2}), "safe.txt", false);
        String uuid = legacy.file().getUuid();
        keys.put("bootfs", uuid, new ObjectKeyBinding(uuid, 2, legacy.etag(), legacy.file().getCreatedAt(), uuid, "text/plain"));
        ObjectKeyRepository uncertain = spy(keys);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("named key committed but reply lost"); })
                .when(uncertain).compareAndSet(eq("bootfs"), eq("safe.txt"), isNull(), any(ObjectKeyBinding.class));
        ReflectionTestUtils.setField(bootstrap, "objectKeys", uncertain);
        assertThrows(IllegalStateException.class, bootstrap::bootstrapExistingBindings);
        assertNotNull(keys.find("bootfs", uuid));
        assertEquals(uuid, keys.find("bootfs", "safe.txt").getUuid());
        ReflectionTestUtils.setField(bootstrap, "objectKeys", keys);
        bootstrap.bootstrapExistingBindings();
        assertNull(keys.find("bootfs", uuid));
        assertEquals(uuid, keys.find("bootfs", "safe.txt").getUuid());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(Path.of(context.query(uuid).getPath())));
    }

    @Test void overwritePublishesNewGenerationAndCleansTheOldGeneration() throws Exception {
        ObjectKeyBinding first = s3.putObject("bootfs", "same", request("first"));
        ObjectKeyBinding second = s3.putObject("bootfs", "same", request("second"));
        assertNotEquals(first.getUuid(), second.getUuid());
        assertNull(context.query(first.getUuid()));
        assertFalse(Files.exists(root.resolve("data").resolve(first.getUuid())));
        assertEquals(second.getUuid(), keys.find("bootfs", "same").getUuid());
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", "same", new MockHttpServletRequest(), response);
        assertEquals("second", response.getContentAsString());
    }

    @Test void failedUploadCannotReplacePreviouslyCommittedContent() throws Exception {
        ObjectKeyBinding first = s3.putObject("bootfs", "same", request("first"));
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("broken stream"); } };
        javax.servlet.http.HttpServletRequest brokenRequest = mock(javax.servlet.http.HttpServletRequest.class);
        when(brokenRequest.getInputStream()).thenReturn(new DelegatingServletInputStream(broken));
        assertThrows(IOException.class, () -> s3.putObject("bootfs", "same", brokenRequest));
        assertEquals(first.getUuid(), keys.find("bootfs", "same").getUuid());
        assertTrue(Files.exists(root.resolve("data").resolve(first.getUuid())));
        try (var paths = Files.list(root.resolve("data"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
    }

    @Test void losingConcurrentPutCleansOnlyItsOwnGeneration() throws Exception {
        ObjectKeyBinding first = s3.putObject("bootfs", "same", request("first"));
        ObjectKeyRepository losingKeys = spy(keys);
        doReturn(false).when(losingKeys).compareAndSet(eq("bootfs"), eq("same"), anyString(), any(ObjectKeyBinding.class));
        ReflectionTestUtils.setField(publications, "objectKeys", losingKeys);
        ReflectionTestUtils.setField(deletions, "objectKeys", losingKeys);
        S3StorageService loser = new S3StorageService(config, context, routes, files, deletions, mock(ReverseProxyService.class), losingKeys, new S3XmlWriter());
        assertThrows(ResourceWriteConflictException.class, () -> loser.putObject("bootfs", "same", request("loser")));
        assertEquals(first.getUuid(), keys.find("bootfs", "same").getUuid());
        assertEquals(Set.of(first.getUuid()), context.cachedFiles().stream().map(FileObject::getUuid).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void staleBindingRemovalCannotDeleteAConcurrentOverwrite() throws Exception {
        ObjectKeyBinding first = s3.putObject("bootfs", "same", request("first"));
        String stale = keys.findRaw("bootfs", "same");
        ObjectKeyBinding second = s3.putObject("bootfs", "same", request("second"));
        assertFalse(keys.compareAndSet("bootfs", "same", stale, null));
        keys.deleteBindingsForResource(first.getUuid());
        assertEquals(second.getUuid(), keys.find("bootfs", "same").getUuid());
    }

    @Test void prefixAndDelimiterPaginationDoNotSkipKeysOrRepeatGroups() {
        for (String key : List.of("foo/a", "foo/b", "foo/dir/a", "foo/dir/b", "foo/z", "foobar/c")) {
            keys.put("bootfs", key, new ObjectKeyBinding(key, 1, "etag", 1, key, "text/plain"));
        }
        List<String> all = new ArrayList<>();
        String token = null;
        do {
            S3ListPage page = listing.listV2("bootfs", "foo/", "/", 1, token);
            page.getContents().forEach(meta -> all.add(meta.getKey()));
            all.addAll(page.getCommonPrefixes());
            token = page.getNextContinuationToken();
        } while (token != null);
        assertEquals(List.of("foo/a", "foo/b", "foo/dir/", "foo/z"), all);
        S3ListPage first = listing.listV2("bootfs", "foo/", "/", 1, null);
        assertThrows(IllegalArgumentException.class,
                () -> listing.listV2("bootfs", "foobar/", "/", 1, first.getNextContinuationToken()));
    }

    @Test void invalidRangesAreRejectedAndSuffixReadsReturnTheCorrectBytes() throws Exception {
        s3.putObject("bootfs", "binary", request("012345"));
        for (String range : List.of("bytes=-0", "bytes=4-1", "bytes=9-", "bytes=1-2,4-5")) {
            MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("Range", range);
            MockHttpServletResponse response = new MockHttpServletResponse();
            s3.getObject("bootfs", "binary", request, response);
            assertEquals(416, response.getStatus(), range);
        }
        MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("Range", "bytes=-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", "binary", request, response);
        assertEquals(206, response.getStatus()); assertEquals("45", response.getContentAsString());
        assertEquals("bytes 4-5/6", response.getHeader("Content-Range"));
    }

    @Test void aShortAlbumNameWorksAndCannotLeakParentStateIntoTheNextUpload() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("one.jpg")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        String albumUrl = files.uploadComic(new MockMultipartFile("file", "a.zip", "application/zip", archive.toByteArray()));
        String albumId = albumUrl.substring(albumUrl.lastIndexOf('/') + 1);
        assertEquals("1", context.query(albumId).getAlbumAvailable());
        String plainUrl = files.upload(new MockMultipartFile("file", "plain", "text/plain", new byte[]{9}));
        String plainId = plainUrl.substring(plainUrl.lastIndexOf('/') + 1);
        assertNotEquals(albumId, plainId); assertNull(context.query(plainId).getParent());
        assertNull(context.query(plainId).getAlbumAvailable());
        deletions.deleteResource(albumId);
        assertTrue(context.cachedFiles().stream().noneMatch(file -> albumId.equals(file.getParent())));
        assertNotNull(context.query(plainId));
    }

    @Test void lostPublicationReplyPreservesBytesAndRecoveryCompletesTheSameResource() throws Exception {
        MetadataRegistry uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("reply lost after commit"); })
                .when(uncertain).putFile(anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(publications, "metadataRegistry", uncertain);
        assertThrows(IOException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{1,2,3}), "safe.bin", true));
        assertEquals(1, context.cachedCount());
        String uuid = context.cachedFiles().iterator().next().getUuid();
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(root.resolve("data").resolve(uuid)));
        assertNull(routes.getOne("/tombstones/" + uuid));
        assertEquals(1, routes.scan("/pending-publications/").size());
        doCallRealMethod().when(uncertain).putFile(anyString(), anyString(), anyString());
        recovery.resumePendingPublications();
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        assertEquals(uuid, keys.find("bootfs", "safe.bin").getUuid());
        assertEquals(Set.of(uuid), context.cachedFiles().stream().map(FileObject::getUuid).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void lostIntentReplyPreservesStagingAndCanResumeBeforeTheCatalogExists() throws Exception {
        MetadataRegistry uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("intent committed but reply lost"); })
                .when(uncertain).put(startsWith("/pending-publications/"), anyString());
        ReflectionTestUtils.setField(publications, "metadataRegistry", uncertain);
        assertThrows(IOException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{7,8}), "safe.bin", true));
        assertTrue(context.cachedFiles().isEmpty());
        assertEquals(1, routes.scan("/pending-publications/").size());
        doCallRealMethod().when(uncertain).put(anyString(), anyString());
        recovery.resumePendingPublications();
        assertEquals(1, context.cachedCount());
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        String uuid = context.cachedFiles().iterator().next().getUuid();
        assertArrayEquals(new byte[]{7,8}, Files.readAllBytes(root.resolve("data").resolve(uuid)));
    }

    @Test void lostS3CasReplyCannotDeleteTheNewlyCommittedGeneration() throws Exception {
        MetadataRegistry uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("CAS committed but reply lost"); })
                .when(uncertain).compareAndSet(eq("/s3/bootfs/safe"), isNull(), anyString());
        ObjectKeyRepository uncertainKeys = new ObjectKeyRepository(uncertain);
        ReflectionTestUtils.setField(publications, "metadataRegistry", uncertain);
        ReflectionTestUtils.setField(publications, "objectKeys", uncertainKeys);
        S3StorageService endpoint = new S3StorageService(config, context, uncertain, files, deletions, mock(ReverseProxyService.class), uncertainKeys, new S3XmlWriter());
        assertThrows(IOException.class, () -> endpoint.putObject("bootfs", "safe", request("durable")));
        ObjectKeyBinding committed = keys.find("bootfs", "safe");
        assertNotNull(committed);
        assertTrue(Files.exists(root.resolve("data").resolve(committed.getUuid())));
        assertNull(routes.getOne("/tombstones/" + committed.getUuid()));
        recovery.resumePendingPublications();
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        endpoint.getObject("bootfs", "safe", new MockHttpServletRequest(), response);
        assertEquals("durable", response.getContentAsString());
    }

    @Test void anOriginalTombstoneImmediatelyBlocksAStaleReplicaBeforeReconciliation() throws Exception {
        Path content = root.resolve("data").resolve("replica"); Files.writeString(content, "stale");
        FileObject replica = new FileObject(content.toString(), "replica", "copy.txt", 5L);
        replica.setCopyOf("original"); context.record(replica, "replica");
        routes.put("/tombstones/original", "deleted");
        assertNull(files.openDownload("replica"));
        assertTrue(Files.exists(content)); // Serving is blocked even before physical cleanup runs.
    }

    @Test void deletionRemovesHlsTreesWithoutFollowingSymlinks() throws Exception {
        PublishedResource published = files.publish(new ByteArrayInputStream(new byte[]{1}), "movie.mp4", true);
        String uuid = published.file().getUuid();
        Path hls = root.resolve("data/hls").resolve(uuid);
        Files.createDirectories(hls.resolve("nested")); Files.writeString(hls.resolve("playlist.m3u8"), "playlist");
        Files.writeString(hls.resolve("nested/segment.ts"), "segment");
        Path external = root.resolve("keep.txt"); Files.writeString(external, "keep");
        Files.createSymbolicLink(hls.resolve("external"), external);
        deletions.deleteResource(uuid);
        assertFalse(Files.exists(hls)); assertEquals("keep", Files.readString(external));
        assertTrue(routes.scan("/cleanup/").isEmpty());
    }

    @Test void repeatedReplicaReceiptHasOneStableIdentityAndReportsFailureTruthfully() throws Exception {
        FileTransferService transfers = new FileTransferService();
        ReflectionTestUtils.setField(transfers, "context", context);
        ReflectionTestUtils.setField(transfers, "tfsConfig", config);
        ReflectionTestUtils.setField(transfers, "metadataRegistry", routes);
        ReflectionTestUtils.setField(transfers, "resourceSearch", search);
        MockMultipartFile content = new MockMultipartFile("file", "a", "application/octet-stream", new byte[]{1,2,3});
        var first = (cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original");
        var second = (cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original");
        assertTrue(first.getSucceed()); assertTrue(second.getSucceed());
        assertEquals(first.getFileNode().getUuid(), second.getFileNode().getUuid());
        assertEquals(3L, first.getFileNode().getSize()); assertEquals(1, context.cachedCount());
        routes.put("/tombstones/original", "deleted");
        assertFalse(((cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original")).getSucceed());
    }

    @Test void signedPayloadDigestIsVerifiedBeforeAnyMetadataOrBindingIsPublished() throws Exception {
        ObjectKeyBinding original = s3.putObject("bootfs", "safe", request("original"));
        MockHttpServletRequest wrong = request("modified");
        wrong.addHeader("x-amz-content-sha256", "0".repeat(64));
        assertThrows(PayloadDigestMismatchException.class, () -> s3.putObject("bootfs", "safe", wrong));
        assertEquals(original.getUuid(), keys.find("bootfs", "safe").getUuid());
        assertEquals(Set.of(original.getUuid()), context.cachedFiles().stream().map(FileObject::getUuid).collect(java.util.stream.Collectors.toSet()));
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        MockHttpServletRequest valid = request("modified");
        valid.addHeader("x-amz-content-sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("modified".getBytes(StandardCharsets.UTF_8))));
        ObjectKeyBinding replacement = s3.putObject("bootfs", "safe", valid);
        assertNotEquals(original.getUuid(), replacement.getUuid());
        assertEquals(replacement.getUuid(), keys.find("bootfs", "safe").getUuid());
    }

    @Test void unsupportedStreamingSigningIsRejectedBeforeConsumingTheRequest() throws Exception {
        javax.servlet.http.HttpServletRequest chunked = mock(javax.servlet.http.HttpServletRequest.class);
        when(chunked.getHeader("x-amz-content-sha256")).thenReturn("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
        assertThrows(UnsupportedS3PayloadException.class, () -> s3.putObject("bootfs", "chunked", chunked));
        verify(chunked, never()).getInputStream();
        assertTrue(context.cachedFiles().isEmpty());
        MockHttpServletRequest unsigned = request("allowed"); unsigned.addHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
        assertNotNull(s3.putObject("bootfs", "unsigned", unsigned));
    }

    @Test void supersededLostReplyPublicationStillRetiresItsPredecessor() throws Exception {
        String key="chain";
        String first=s3.putObject("bootfs",key,request("A")).getUuid();
        ObjectKeyRepository uncertain=spy(keys);
        java.util.concurrent.atomic.AtomicBoolean lost=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            boolean committed=(boolean)call.callRealMethod();
            if (committed && !lost.getAndSet(true)) throw new IllegalStateException("CAS committed but reply lost");
            return committed;
        }).when(uncertain).compareAndSet(eq("bootfs"),eq(key),anyString(),any(ObjectKeyBinding.class));
        ReflectionTestUtils.setField(publications,"objectKeys",uncertain);
        ReflectionTestUtils.setField(deletions,"objectKeys",uncertain);
        assertThrows(IOException.class,() -> s3.putObject("bootfs",key,request("B")));
        String second=keys.find("bootfs",key).getUuid();
        ReflectionTestUtils.setField(publications,"objectKeys",keys);
        ReflectionTestUtils.setField(deletions,"objectKeys",keys);
        String current=s3.putObject("bootfs",key,request("C")).getUuid();
        recovery.resumePendingPublications();
        recovery.bootstrapExistingBindings();
        assertNull(context.query(first));
        assertNull(context.query(second));
        assertNull(keys.find("bootfs",first));
        assertEquals(current,keys.find("bootfs",key).getUuid());
        assertNotNull(context.query(current));
    }

    @Test void tombstonedBindingsAreNotListedAndLogicalCleanupDoesNotRequireTheOwner() {
        keys.put("bootfs","deleted/folder/file",new ObjectKeyBinding("dead",1,"hash",1,"deleted/folder/file","text/plain"));
        routes.putFile("dead","http://offline-owner/f/dead","{}");
        routes.put("/tombstones/dead","deleted");
        S3ListPage page=listing.listV2("bootfs","","/",10,null);
        assertTrue(page.getContents().isEmpty());
        assertTrue(page.getCommonPrefixes().isEmpty());
        assertNull(context.query("dead"));
        recovery.resumeGlobalDeletions();
        assertNull(keys.find("bootfs","deleted/folder/file"));
        assertNull(routes.getOne("/files/dead"));
        long revision=metadata.revision();
        recovery.resumeGlobalDeletions();
        assertEquals(revision,metadata.revision());
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); request.setContentType("application/octet-stream");
        return request;
    }
}
