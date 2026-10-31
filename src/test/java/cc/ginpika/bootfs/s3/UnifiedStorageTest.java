package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.FullTextDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
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
    private Context context;
    private FileService files;
    private S3StorageService s3;
    private S3KeyIndex keys;
    private MeiliSearchService search;
    private ThumbnailService thumbnails;
    private TfsConfig config;
    private EtcdService routes;

    @BeforeEach void setup() throws Exception {
        config = new TfsConfig();
        config.setRootPath(root.toString()); config.setWebEntrypoint("http://node"); config.setCopies(1);
        NodeIdentity identity = new NodeIdentity(config);
        metadata = new RocksMetadataStore(root.resolve("metadata"));
        context = new Context(config, new ContextIO(metadata, config, identity), identity);
        context.load();
        routes = new EtcdService(metadata, config, context);
        keys = new S3KeyIndex(routes);
        search = mock(MeiliSearchService.class);
        thumbnails = mock(ThumbnailService.class);
        when(thumbnails.thumbPath(anyString())).thenAnswer(call -> root.resolve("thumb").resolve(call.getArgument(0) + ".webp"));
        files = new FileService(context, config, routes, mock(FileTransferService.class), search, thumbnails);
        ReflectionTestUtils.setField(files, "s3KeyIndex", keys);
        s3 = new S3StorageService(config, context, routes, files, mock(ReverseProxyService.class), keys, new S3XmlWriter());
    }

    @AfterEach void close() { files.close(); metadata.close(); }

    @Test void ordinaryUploadIsReadableThroughS3AndSharesTheSearchLifecycle() throws Exception {
        String url = files.upload(new MockMultipartFile("file", "photo.png", "image/png", "pixels".getBytes(StandardCharsets.UTF_8)));
        String uuid = url.substring(url.lastIndexOf('/') + 1);
        assertNotNull(keys.getIndex("bootfs", uuid));
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", uuid, new MockHttpServletRequest(), response);
        assertEquals("pixels", response.getContentAsString());
        assertEquals("image/png", response.getContentType());
        verify(search).addToFullText(argThat((FullTextDocument doc) -> uuid.equals(doc.getUuid())));
        verify(thumbnails).generateAsync(uuid);
        files.deleteResource(uuid);
        assertNull(keys.getIndex("bootfs", uuid));
        assertNull(context.query(uuid));
        assertFalse(Files.exists(root.resolve("data").resolve(uuid)));
        verify(search).deleteDocumentFromIndex("full-text", uuid);
    }

    @Test void s3PutIsANormalResourceAndS3DeleteRemovesItsFileAndSearchEntry() throws Exception {
        S3ObjectMeta object = s3.putObject("bootfs", "archive/data.bin", request("payload"));
        assertNotNull(files.download(object.getUuid(), new MockHttpServletRequest()));
        assertEquals("archive/data.bin", context.query(object.getUuid()).getFileName());
        assertEquals(1, keys.listIndex("bootfs", "", null, 1000, null).getContents().size());
        verify(search).addToFullText(argThat((FullTextDocument doc) -> object.getUuid().equals(doc.getUuid())));
        s3.deleteObject("bootfs", "archive/data.bin");
        assertNull(context.query(object.getUuid()));
        assertNull(keys.getIndex("bootfs", "archive/data.bin"));
        assertFalse(Files.exists(root.resolve("data").resolve(object.getUuid())));
    }

    @Test void overwritePublishesNewGenerationAndCleansTheOldGeneration() throws Exception {
        S3ObjectMeta first = s3.putObject("bootfs", "same", request("first"));
        S3ObjectMeta second = s3.putObject("bootfs", "same", request("second"));
        assertNotEquals(first.getUuid(), second.getUuid());
        assertNull(context.query(first.getUuid()));
        assertFalse(Files.exists(root.resolve("data").resolve(first.getUuid())));
        assertEquals(second.getUuid(), keys.getIndex("bootfs", "same").getUuid());
        MockHttpServletResponse response = new MockHttpServletResponse();
        s3.getObject("bootfs", "same", new MockHttpServletRequest(), response);
        assertEquals("second", response.getContentAsString());
    }

    @Test void failedUploadCannotReplacePreviouslyCommittedContent() throws Exception {
        S3ObjectMeta first = s3.putObject("bootfs", "same", request("first"));
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("broken stream"); } };
        javax.servlet.http.HttpServletRequest brokenRequest = mock(javax.servlet.http.HttpServletRequest.class);
        when(brokenRequest.getInputStream()).thenReturn(new DelegatingServletInputStream(broken));
        assertThrows(IOException.class, () -> s3.putObject("bootfs", "same", brokenRequest));
        assertEquals(first.getUuid(), keys.getIndex("bootfs", "same").getUuid());
        assertTrue(Files.exists(root.resolve("data").resolve(first.getUuid())));
        try (var paths = Files.list(root.resolve("data"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
    }

    @Test void losingConcurrentPutCleansOnlyItsOwnGeneration() throws Exception {
        S3ObjectMeta first = s3.putObject("bootfs", "same", request("first"));
        S3KeyIndex losingKeys = spy(keys);
        doReturn(false).when(losingKeys).compareAndSet(eq("bootfs"), eq("same"), anyString(), any(S3ObjectMeta.class));
        ReflectionTestUtils.setField(files, "s3KeyIndex", losingKeys);
        S3StorageService loser = new S3StorageService(config, context, routes, files, mock(ReverseProxyService.class), losingKeys, new S3XmlWriter());
        assertThrows(S3ConcurrentWriteException.class, () -> loser.putObject("bootfs", "same", request("loser")));
        assertEquals(first.getUuid(), keys.getIndex("bootfs", "same").getUuid());
        assertEquals(Set.of(first.getUuid()), context.STORAGE.keySet());
    }

    @Test void staleBindingRemovalCannotDeleteAConcurrentOverwrite() throws Exception {
        S3ObjectMeta first = s3.putObject("bootfs", "same", request("first"));
        String stale = keys.rawIndex("bootfs", "same");
        S3ObjectMeta second = s3.putObject("bootfs", "same", request("second"));
        assertFalse(keys.compareAndSet("bootfs", "same", stale, null));
        keys.deleteBindingsForResource(first.getUuid());
        assertEquals(second.getUuid(), keys.getIndex("bootfs", "same").getUuid());
    }

    @Test void prefixAndDelimiterPaginationDoNotSkipKeysOrRepeatGroups() {
        for (String key : List.of("foo/a", "foo/b", "foo/dir/a", "foo/dir/b", "foo/z", "foobar/c")) {
            keys.putIndex("bootfs", key, new S3ObjectMeta(key, 1, "etag", 1, key, "text/plain"));
        }
        List<String> all = new ArrayList<>();
        String token = null;
        do {
            S3ListPage page = keys.listIndex("bootfs", "foo/", "/", 1, token);
            page.getContents().forEach(meta -> all.add(meta.getKey()));
            all.addAll(page.getCommonPrefixes());
            token = page.getNextContinuationToken();
        } while (token != null);
        assertEquals(List.of("foo/a", "foo/b", "foo/dir/", "foo/z"), all);
        S3ListPage first = keys.listIndex("bootfs", "foo/", "/", 1, null);
        assertThrows(IllegalArgumentException.class,
                () -> keys.listIndex("bootfs", "foobar/", "/", 1, first.getNextContinuationToken()));
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
        files.deleteResource(albumId);
        assertTrue(context.STORAGE.values().stream().noneMatch(file -> albumId.equals(file.getParent())));
        assertNotNull(context.query(plainId));
    }

    @Test void lostPublicationReplyPreservesBytesAndRecoveryCompletesTheSameResource() throws Exception {
        EtcdService uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("reply lost after commit"); })
                .when(uncertain).putFile(anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(files, "etcdService", uncertain);
        assertThrows(IOException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{1,2,3}), "safe.bin", true));
        assertEquals(1, context.STORAGE.size());
        String uuid = context.STORAGE.keySet().iterator().next();
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(root.resolve("data").resolve(uuid)));
        assertNull(routes.getOne("/tombstones/" + uuid));
        assertEquals(1, routes.scan("/pending-publications/").size());
        doCallRealMethod().when(uncertain).putFile(anyString(), anyString(), anyString());
        files.resumePendingPublications();
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        assertEquals(uuid, keys.getIndex("bootfs", uuid).getUuid());
        assertEquals(Set.of(uuid), context.STORAGE.keySet());
    }

    @Test void lostIntentReplyPreservesStagingAndCanResumeBeforeTheCatalogExists() throws Exception {
        EtcdService uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("intent committed but reply lost"); })
                .when(uncertain).put(startsWith("/pending-publications/"), anyString());
        ReflectionTestUtils.setField(files, "etcdService", uncertain);
        assertThrows(IOException.class, () -> files.publish(new ByteArrayInputStream(new byte[]{7,8}), "safe.bin", true));
        assertTrue(context.STORAGE.isEmpty());
        assertEquals(1, routes.scan("/pending-publications/").size());
        doCallRealMethod().when(uncertain).put(anyString(), anyString());
        files.resumePendingPublications();
        assertEquals(1, context.STORAGE.size());
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        String uuid = context.STORAGE.keySet().iterator().next();
        assertArrayEquals(new byte[]{7,8}, Files.readAllBytes(root.resolve("data").resolve(uuid)));
    }

    @Test void lostS3CasReplyCannotDeleteTheNewlyCommittedGeneration() throws Exception {
        EtcdService uncertain = spy(routes);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("CAS committed but reply lost"); })
                .when(uncertain).compareAndSet(eq("/s3/bootfs/safe"), isNull(), anyString());
        S3KeyIndex uncertainKeys = new S3KeyIndex(uncertain);
        ReflectionTestUtils.setField(files, "etcdService", uncertain);
        ReflectionTestUtils.setField(files, "s3KeyIndex", uncertainKeys);
        S3StorageService endpoint = new S3StorageService(config, context, uncertain, files, mock(ReverseProxyService.class), uncertainKeys, new S3XmlWriter());
        assertThrows(IOException.class, () -> endpoint.putObject("bootfs", "safe", request("durable")));
        S3ObjectMeta committed = keys.getIndex("bootfs", "safe");
        assertNotNull(committed);
        assertTrue(Files.exists(root.resolve("data").resolve(committed.getUuid())));
        assertNull(routes.getOne("/tombstones/" + committed.getUuid()));
        files.resumePendingPublications();
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
        assertNull(files.download("replica", new MockHttpServletRequest()));
        assertTrue(Files.exists(content)); // Serving is blocked even before physical cleanup runs.
    }

    @Test void deletionRemovesHlsTreesWithoutFollowingSymlinks() throws Exception {
        FileService.PublishedResource published = files.publish(new ByteArrayInputStream(new byte[]{1}), "movie.mp4", true);
        String uuid = published.file().getUuid();
        Path hls = root.resolve("data/hls").resolve(uuid);
        Files.createDirectories(hls.resolve("nested")); Files.writeString(hls.resolve("playlist.m3u8"), "playlist");
        Files.writeString(hls.resolve("nested/segment.ts"), "segment");
        Path external = root.resolve("keep.txt"); Files.writeString(external, "keep");
        Files.createSymbolicLink(hls.resolve("external"), external);
        files.deleteResource(uuid);
        assertFalse(Files.exists(hls)); assertEquals("keep", Files.readString(external));
        assertTrue(routes.scan("/cleanup/").isEmpty());
    }

    @Test void repeatedReplicaReceiptHasOneStableIdentityAndReportsFailureTruthfully() throws Exception {
        FileTransferService transfers = new FileTransferService();
        ReflectionTestUtils.setField(transfers, "context", context);
        ReflectionTestUtils.setField(transfers, "tfsConfig", config);
        ReflectionTestUtils.setField(transfers, "etcdService", routes);
        ReflectionTestUtils.setField(transfers, "meiliSearchService", search);
        MockMultipartFile content = new MockMultipartFile("file", "a", "application/octet-stream", new byte[]{1,2,3});
        var first = (cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original");
        var second = (cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original");
        assertTrue(first.getSucceed()); assertTrue(second.getSucceed());
        assertEquals(first.getFileNode().getUuid(), second.getFileNode().getUuid());
        assertEquals(3L, first.getFileNode().getSize()); assertEquals(1, context.STORAGE.size());
        routes.put("/tombstones/original", "deleted");
        assertFalse(((cc.ginpika.bootfs.domain.result.SimpleTransferResult) transfers.accept(content, "noextension", "original")).getSucceed());
    }

    @Test void signedPayloadDigestIsVerifiedBeforeAnyMetadataOrBindingIsPublished() throws Exception {
        S3ObjectMeta original = s3.putObject("bootfs", "safe", request("original"));
        MockHttpServletRequest wrong = request("modified");
        wrong.addHeader("x-amz-content-sha256", "0".repeat(64));
        S3PayloadException mismatch = assertThrows(S3PayloadException.class, () -> s3.putObject("bootfs", "safe", wrong));
        assertTrue(mismatch.isDigestMismatch());
        assertEquals(original.getUuid(), keys.getIndex("bootfs", "safe").getUuid());
        assertEquals(Set.of(original.getUuid()), context.STORAGE.keySet());
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        MockHttpServletRequest valid = request("modified");
        valid.addHeader("x-amz-content-sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("modified".getBytes(StandardCharsets.UTF_8))));
        S3ObjectMeta replacement = s3.putObject("bootfs", "safe", valid);
        assertNotEquals(original.getUuid(), replacement.getUuid());
        assertEquals(replacement.getUuid(), keys.getIndex("bootfs", "safe").getUuid());
    }

    @Test void unsupportedStreamingSigningIsRejectedBeforeConsumingTheRequest() throws Exception {
        javax.servlet.http.HttpServletRequest chunked = mock(javax.servlet.http.HttpServletRequest.class);
        when(chunked.getHeader("x-amz-content-sha256")).thenReturn("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
        assertFalse(assertThrows(S3PayloadException.class, () -> s3.putObject("bootfs", "chunked", chunked)).isDigestMismatch());
        verify(chunked, never()).getInputStream();
        assertTrue(context.STORAGE.isEmpty());
        MockHttpServletRequest unsigned = request("allowed"); unsigned.addHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
        assertNotNull(s3.putObject("bootfs", "unsigned", unsigned));
    }

    @Test void supersededLostReplyPublicationStillRetiresItsPredecessor() throws Exception {
        String key="chain";
        String first=s3.putObject("bootfs",key,request("A")).getUuid();
        S3KeyIndex uncertain=spy(keys);
        java.util.concurrent.atomic.AtomicBoolean lost=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            boolean committed=(boolean)call.callRealMethod();
            if (committed && !lost.getAndSet(true)) throw new IllegalStateException("CAS committed but reply lost");
            return committed;
        }).when(uncertain).compareAndSet(eq("bootfs"),eq(key),anyString(),any(S3ObjectMeta.class));
        ReflectionTestUtils.setField(files,"s3KeyIndex",uncertain);
        assertThrows(IOException.class,() -> s3.putObject("bootfs",key,request("B")));
        String second=keys.getIndex("bootfs",key).getUuid();
        ReflectionTestUtils.setField(files,"s3KeyIndex",keys);
        String current=s3.putObject("bootfs",key,request("C")).getUuid();
        files.resumePendingPublications();
        files.bootstrapExistingBindings();
        assertNull(context.query(first));
        assertNull(context.query(second));
        assertNull(keys.getIndex("bootfs",first));
        assertEquals(current,keys.getIndex("bootfs",key).getUuid());
        assertNotNull(context.query(current));
    }

    @Test void tombstonedBindingsAreNotListedAndLogicalCleanupDoesNotRequireTheOwner() {
        keys.putIndex("bootfs","deleted/folder/file",new S3ObjectMeta("dead",1,"hash",1,"deleted/folder/file","text/plain"));
        routes.putFile("dead","http://offline-owner/f/dead","{}");
        routes.put("/tombstones/dead","deleted");
        S3ListPage page=keys.listIndex("bootfs","","/",10,null);
        assertTrue(page.getContents().isEmpty());
        assertTrue(page.getCommonPrefixes().isEmpty());
        assertNull(context.query("dead"));
        files.resumeGlobalDeletions();
        assertNull(keys.getIndex("bootfs","deleted/folder/file"));
        assertNull(routes.getOne("/files/dead"));
        long revision=metadata.revision();
        files.resumeGlobalDeletions();
        assertEquals(revision,metadata.revision());
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); request.setContentType("application/octet-stream");
        return request;
    }
}
