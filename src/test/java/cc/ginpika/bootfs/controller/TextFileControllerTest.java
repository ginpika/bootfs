package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.object.PublicationPendingException;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import cc.ginpika.bootfs.s3.S3KeyIndex;
import cc.ginpika.bootfs.s3.S3ObjectMeta;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TextFileControllerTest {
    private static final Charset GBK = Charset.forName("GBK");
    @TempDir Path root;
    private RocksMetadataStore metadata;
    private EmbeddedSearchEngine projection;
    private EtcdService routes;
    private Context context;
    private TfsConfig config;
    private FileService files;
    private TextFileService texts;
    private TextFileController controller;
    private MeiliSearchService search;
    private ClusterProxyService proxy;
    private S3KeyIndex keys;

    @BeforeEach void setup() throws Exception {
        config = new TfsConfig(); config.setRootPath(root.toString()); config.setWebEntrypoint("http://node"); config.setCopies(1);
        metadata = new RocksMetadataStore(root.resolve("metadata"));
        NodeIdentity identity = new NodeIdentity(config);
        context = new Context(config, new ContextIO(metadata, config, identity), identity); context.load();
        routes = new EtcdService(metadata, config, context);
        projection = new EmbeddedSearchEngine(metadata, root.resolve("search"));
        search = new MeiliSearchService(projection);
        ThumbnailService thumbnails = mock(ThumbnailService.class);
        when(thumbnails.thumbPath(anyString())).thenAnswer(call -> root.resolve("thumb").resolve(call.getArgument(0) + ".webp"));
        files = new FileService(context, config, routes, mock(FileTransferService.class), search, thumbnails);
        keys = new S3KeyIndex(routes); ReflectionTestUtils.setField(files, "s3KeyIndex", keys);
        texts = new TextFileService(context, files, routes);
        proxy = mock(ClusterProxyService.class);
        controller = new TextFileController(context, texts, proxy);
    }

    @AfterEach void close() throws Exception { files.close(); projection.close(); metadata.close(); }

    @Test void savingPublishesNewBytesAndPreservesMetadataAndEveryS3Binding() throws Exception {
        String uuid = upload("settings.yaml", "# 配置\r\nport: 8080\r\n");
        var before = texts.read(uuid);
        search.addToFullText("{\"uuid\":\"" + uuid + "\",\"author\":\"kept\",\"nsfw\":\"1\"}");
        search.updateDocumentTags("full-text", uuid, List.of(Tag.manual("配置")));
        S3ObjectMeta originalBinding = keys.getIndex("bootfs", uuid);
        keys.putIndex("bootfs", "config/current.yaml", new S3ObjectMeta(uuid, originalBinding.getSize(), originalBinding.getEtag(),
                originalBinding.getLastModified(), "config/current.yaml", "text/yaml"));
        var response = controller.save(uuid, new TextFileController.SaveTextRequest("# 新配置\nport: 9090\n", before.version()), request("PUT"));
        assertEquals(200, response.getStatusCodeValue());
        var saved = (TextFileService.TextDocument) response.getBody();
        assertNotEquals(uuid, saved.uuid()); assertEquals("# 新配置\nport: 9090\n", saved.content());
        assertEquals(before.fileName(), saved.fileName()); assertNotEquals(before.version(), saved.version());
        assertEquals("kept", search.findDocument("full-text", saved.uuid()).getString("author"));
        assertEquals("1", search.findDocument("full-text", saved.uuid()).getString("nsfw"));
        assertEquals("1", context.query(saved.uuid()).getNsfw());
        assertEquals(List.of("manual:配置"), search.getDocumentTags("full-text", saved.uuid()).stream().map(Tag::toIndexFormat).toList());
        assertEquals(saved.size(), Files.size(Path.of(context.query(saved.uuid()).getPath())));
        assertEquals(saved.size(), context.query(saved.uuid()).getSize());
        assertEquals(saved.uuid(), keys.getIndex("bootfs", uuid).getUuid());
        assertEquals(saved.uuid(), keys.getIndex("bootfs", "config/current.yaml").getUuid());
        assertEquals("text/yaml", keys.getIndex("bootfs", "config/current.yaml").getContentType());
        assertNull(context.query(uuid)); assertNull(search.findDocument("full-text", uuid));
        assertNotNull(routes.getOne("/tombstones/" + uuid)); assertTrue(routes.scan("/pending-publications/").isEmpty());
        assertFalse(Files.exists(root.resolve("data").resolve(uuid)));
        assertNotNull(files.download(saved.uuid(), request("GET")));
        context.reload(); assertEquals(saved.size(), context.query(saved.uuid()).getSize());
    }

    @Test void staleSaveCannotOverwriteANewerGenerationAndExactRetryIsIdempotent() throws Exception {
        String uuid = upload("notes.txt", "first"); var initial = texts.read(uuid);
        var saved = texts.save(uuid, "second", initial.version());
        var repeated = texts.save(uuid, "second", initial.version());
        assertEquals(saved.uuid(), repeated.uuid()); assertEquals(1, context.STORAGE.size());
        assertEquals(409, controller.save(uuid, new TextFileController.SaveTextRequest("third", initial.version()), request("PUT")).getStatusCodeValue());
        assertEquals("second", texts.read(saved.uuid()).content());
    }

    @Test void unchangedAndEmptyTextRoundTripWithoutUnnecessaryGenerations() throws Exception {
        String uuid = upload("README.MD", "\ufeffhello\r\n世界\r\n");
        var original = texts.read(uuid);
        assertEquals("\ufeffhello\r\n世界\r\n", original.content());
        assertEquals(uuid, texts.save(uuid, original.content(), original.version()).uuid());
        var empty = texts.save(uuid, "", original.version());
        assertEquals("", empty.content()); assertEquals(0, empty.size());
    }

    @Test void gbkChineseFilesRoundTripAndPreserveTheirOriginalEncodingOnSave() throws Exception {
        String content = "# 配置文件\r\n名称=原始中文\r\n";
        String uuid = uploadBytes("配置.ini", content.getBytes(GBK));
        var original = texts.read(uuid);
        assertEquals("GBK", original.encoding());
        assertEquals(content, original.content());
        assertEquals(content.getBytes(GBK).length, original.size());
        assertEquals(uuid, texts.save(uuid, original.content(), original.version()).uuid());

        String edited = "# 更新配置\r\n名称=新的中文\r\n";
        var response = controller.save(uuid,
                new TextFileController.SaveTextRequest(edited, original.version()), request("PUT"));
        assertEquals(200, response.getStatusCodeValue());
        var saved = (TextFileService.TextDocument) response.getBody();
        assertEquals("GBK", saved.encoding());
        assertEquals(edited, saved.content());
        assertArrayEquals(edited.getBytes(GBK), Files.readAllBytes(Path.of(context.query(saved.uuid()).getPath())));
        assertFalse(java.util.Arrays.equals(edited.getBytes(StandardCharsets.UTF_8), edited.getBytes(GBK)));
    }

    @Test void gbkAsciiAndEmptyEditsRetainTheirEncodingAfterMetadataReload() throws Exception {
        String uuid = uploadBytes("settings.conf", "配置=原始\r\n".getBytes(GBK));
        var original = texts.read(uuid);
        var ascii = texts.save(uuid, "port=8080\r\n", original.version());
        assertEquals("GBK", ascii.encoding());
        assertArrayEquals("port=8080\r\n".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(Path.of(context.query(ascii.uuid()).getPath())));
        context.reload();
        TextFileService reloaded = new TextFileService(context, files, routes);
        assertEquals("GBK", reloaded.read(ascii.uuid()).encoding());
        assertEquals("port=8080\r\n", reloaded.read(ascii.uuid()).content());

        var empty = reloaded.save(ascii.uuid(), "", ascii.version());
        assertEquals("GBK", empty.encoding());
        assertEquals(0, empty.size());
        context.reload();
        assertEquals("GBK", new TextFileService(context, files, routes).read(empty.uuid()).encoding());
        assertEquals("", texts.read(empty.uuid()).content());
    }

    @Test void gbkCannotSilentlyReplaceUnmappableCharactersOrUseUnsupportedCharsets() throws Exception {
        byte[] content = "标题=中文\n".getBytes(GBK);
        String uuid = uploadBytes("notes.txt", content);
        var original = texts.read(uuid);
        assertEquals(422, controller.save(uuid,
                new TextFileController.SaveTextRequest("标题=😀\n", original.version(), "GBK"), request("PUT")).getStatusCodeValue());
        assertEquals(400, controller.save(uuid,
                new TextFileController.SaveTextRequest("\ud800", original.version(), "GBK"), request("PUT")).getStatusCodeValue());
        assertEquals(400, controller.save(uuid,
                new TextFileController.SaveTextRequest("标题=新值\n", original.version(), "UTF-16"), request("PUT")).getStatusCodeValue());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> texts.read(uuid, "UTF-16")).getRawStatusCode());
        assertEquals(1, context.STORAGE.size());
        assertArrayEquals(content, Files.readAllBytes(Path.of(context.query(uuid).getPath())));
        assertEquals(original.version(), texts.read(uuid).version());

        String malformed = uploadBytes("broken.txt", new byte[]{(byte) 0x81});
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> texts.read(malformed, "GBK")).getRawStatusCode());
    }

    @Test void byteLimitUsesTheActualGbkEncodingRatherThanUtf8ByteLength() throws Exception {
        String uuid = uploadBytes("large.txt", "初始".getBytes(GBK));
        var original = texts.read(uuid);
        String content = "中".repeat(TextFileService.MAX_BYTES / 2);
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length > TextFileService.MAX_BYTES);
        var saved = texts.save(uuid, content, original.version());
        assertEquals("GBK", saved.encoding());
        assertEquals(TextFileService.MAX_BYTES, saved.size());
        assertArrayEquals(content.getBytes(GBK), Files.readAllBytes(Path.of(context.query(saved.uuid()).getPath())));
        assertEquals(413, controller.save(saved.uuid(),
                new TextFileController.SaveTextRequest(content + "中", saved.version(), "GBK"), request("PUT")).getStatusCodeValue());
        assertEquals(saved.version(), texts.read(saved.uuid()).version());
    }

    @Test void lostGbkCommitReplyUsesTheDurableEncodingWhenTheOldFileIsAlreadyRetired() throws Exception {
        String uuid = uploadBytes("settings.toml", "名称 = '原始'\r\n".getBytes(GBK));
        var original = texts.read(uuid);
        EtcdService uncertain = spy(routes);
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            Map<String, String> puts = call.getArgument(1);
            if (puts.keySet().stream().anyMatch(key -> key.startsWith("/text-replacements/")) && once.compareAndSet(false, true)) {
                throw new IllegalStateException("reply lost after GBK commit");
            }
            return result;
        }).when(uncertain).compareAndWrite(anyMap(), anyMap(), anySet());
        ReflectionTestUtils.setField(files, "etcdService", uncertain);
        String edited = "port = 443\r\n";
        assertThrows(PublicationPendingException.class, () -> texts.save(uuid, edited, original.version()));
        assertNotNull(routes.getOne("/tombstones/" + uuid));
        assertEquals(1, routes.scan("/pending-publications/").size());
        var recovered = texts.save(uuid, edited, original.version());
        assertEquals("GBK", recovered.encoding());
        assertEquals(edited, recovered.content());
        assertEquals(recovered.uuid(), texts.save(uuid, edited, original.version()).uuid());
        assertArrayEquals(edited.getBytes(GBK), Files.readAllBytes(Path.of(context.query(recovered.uuid()).getPath())));
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        context.reload();
        assertEquals("GBK", texts.read(recovered.uuid()).encoding());
    }

    @Test void explicitGbkReadResolvesAmbiguousBytesAndSameByteSavePersistsItsMeaning() throws Exception {
        byte[] ambiguous = "漏".getBytes(GBK); // C2 A9 is also the UTF-8 representation of ©.
        String uuid = uploadBytes("ambiguous.txt", ambiguous);
        var automatic = texts.read(uuid);
        assertEquals("UTF-8", automatic.encoding());
        assertEquals("©", automatic.content());
        var forced = texts.read(uuid, "GBK");
        assertEquals("GBK", forced.encoding());
        assertEquals("漏", forced.content());
        var saved = texts.save(uuid, forced.content(), forced.version(), "GBK");
        assertEquals("GBK", saved.encoding());
        assertEquals("漏", saved.content());
        assertArrayEquals(ambiguous, Files.readAllBytes(Path.of(context.query(saved.uuid()).getPath())));
        context.reload();
        assertEquals("漏", texts.read(saved.uuid()).content());
        assertEquals("GBK", texts.read(saved.uuid()).encoding());
        assertEquals(saved.uuid(), texts.save(uuid, forced.content(), forced.version(), "GBK").uuid());
        assertEquals(409, controller.save(uuid,
                new TextFileController.SaveTextRequest("©", forced.version(), "UTF-8"), request("PUT")).getStatusCodeValue());
    }

    @Test void invalidExtensionsBinaryEncodingAndOversizeFilesAreRejected() throws Exception {
        String image = upload("image.png", "text bytes");
        String binary = upload("bad.txt", "a\u0000b");
        String invalid = files.publish(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), "bad.yaml", false).file().getUuid();
        String large = upload("huge.log", "a".repeat(TextFileService.MAX_BYTES + 1));
        for (String id : List.of(image, binary, invalid)) assertEquals(415, controller.read(id, request("GET")).getStatusCodeValue());
        assertEquals(413, controller.read(large, request("GET")).getStatusCodeValue());
        String valid = upload("valid.json", "{}"); var original = texts.read(valid);
        assertEquals(400, controller.save(valid, new TextFileController.SaveTextRequest("x", "bad"), request("PUT")).getStatusCodeValue());
        assertEquals(400, controller.save(valid, new TextFileController.SaveTextRequest("\ud800", original.version()), request("PUT")).getStatusCodeValue());
        assertEquals(415, controller.save(valid, new TextFileController.SaveTextRequest("x\u0000", original.version()), request("PUT")).getStatusCodeValue());
        assertEquals(413, controller.save(valid, new TextFileController.SaveTextRequest("界".repeat(TextFileService.MAX_BYTES / 2), original.version()), request("PUT")).getStatusCodeValue());
        assertEquals("{}", texts.read(valid).content());
        assertTrue(TextFileService.editableName(".env.production")); assertTrue(TextFileService.editableName("Dockerfile"));
        assertFalse(TextFileService.editableName("archive.zip"));
    }

    @Test void lostCommitReplyRecoversTheSameGenerationWithoutLosingMetadata() throws Exception {
        String uuid = upload("settings.toml", "port = 80"); var original = texts.read(uuid);
        EtcdService uncertain = spy(routes); AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            Map<String, String> puts = call.getArgument(1);
            if (puts.keySet().stream().anyMatch(key -> key.startsWith("/text-replacements/")) && once.compareAndSet(false, true)) {
                throw new IllegalStateException("reply lost after commit");
            }
            return result;
        }).when(uncertain).compareAndWrite(anyMap(), anyMap(), anySet());
        ReflectionTestUtils.setField(files, "etcdService", uncertain);
        assertThrows(PublicationPendingException.class, () -> texts.save(uuid, "port = 443", original.version()));
        assertEquals(1, routes.scan("/pending-publications/").size());
        var recovered = texts.save(uuid, "port = 443", original.version());
        assertEquals("port = 443", recovered.content()); assertEquals(1, context.STORAGE.size());
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        assertEquals(recovered.uuid(), keys.getIndex("bootfs", uuid).getUuid());
    }

    @Test void concurrentMetadataChangeRejectsSaveAndPreservesTheOriginal() throws Exception {
        String uuid = upload("settings.ini", "a=1"); var original = texts.read(uuid);
        EtcdService racing = spy(routes); AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            Map<String, String> puts = call.getArgument(1);
            if (puts.keySet().stream().anyMatch(key -> key.startsWith("/text-replacements/")) && once.compareAndSet(false, true)) {
                search.updateDocumentTags("full-text", uuid, List.of(Tag.manual("latest")));
            }
            return call.callRealMethod();
        }).when(racing).compareAndWrite(anyMap(), anyMap(), anySet());
        ReflectionTestUtils.setField(files, "etcdService", racing);
        assertEquals(409, controller.save(uuid, new TextFileController.SaveTextRequest("a=2", original.version()), request("PUT")).getStatusCodeValue());
        assertEquals("a=1", texts.read(uuid).content()); assertEquals(1, context.STORAGE.size());
        assertEquals(List.of("manual:latest"), search.getDocumentTags("full-text", uuid).stream().map(Tag::toIndexFormat).toList());
        assertNull(routes.getOne("/tombstones/" + uuid)); assertTrue(routes.scan("/pending-publications/").isEmpty());
        var retried = texts.save(uuid, "a=2", texts.read(uuid).version());
        assertEquals("a=2", retried.content());
        assertEquals(List.of("manual:latest"), search.getDocumentTags("full-text", retried.uuid()).stream().map(Tag::toIndexFormat).toList());
    }

    @Test void missingRemoteRequestsAreProxiedAndForwardedRequestsNeverLoop() throws Exception {
        when(proxy.getRemoteNodeUrl("remote")).thenReturn("http://other");
        when(proxy.getTextEditorNodeUrl("remote")).thenReturn("http://other");
        when(proxy.proxyRequest(eq("http://other"), any(), isNull())).thenReturn(ResponseEntity.ok("remote".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, controller.read("remote", request("GET")).getStatusCodeValue());
        verify(proxy).proxyRequest(eq("http://other"), any(), isNull());
        var body = new TextFileController.SaveTextRequest("x", "a".repeat(64));
        when(proxy.proxyRequest(eq("http://other"), any(), anyString())).thenReturn(ResponseEntity.status(409).body(new byte[0]));
        assertEquals(409, controller.save("remote", body, request("PUT")).getStatusCodeValue());
        var forwarded = request("GET"); forwarded.addHeader("X-BootFS-Forwarded", "1");
        assertEquals(404, controller.read("remote", forwarded).getStatusCodeValue());
        assertEquals(404, controller.read("missing", request("GET")).getStatusCodeValue());
    }

    @Test void insufficientDurableCopiesKeepTheOriginalUntilTheSameSaveCanComplete() throws Exception {
        String uuid = upload("durable.conf", "value=original"); var original = texts.read(uuid);
        ReflectionTestUtils.setField(files, "writePolicy", "replicated");
        ReflectionTestUtils.setField(files, "requiredCopies", 2);
        routes.put("/cluster/node/backup", "http://backup");
        assertThrows(PublicationPendingException.class, () -> texts.save(uuid, "value=updated", original.version()));
        assertEquals("value=original", texts.read(uuid).content());
        assertNull(routes.getOne("/tombstones/" + uuid));
        assertEquals(uuid, keys.getIndex("bootfs", uuid).getUuid());
        assertEquals(1, context.STORAGE.size()); assertEquals(1, routes.scan("/pending-publications/").size());
        FileTransferService transfers = (FileTransferService) ReflectionTestUtils.getField(files, "fileTransferService");
        when(transfers.prepareRemote(any(), anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(call ->
                new JSONObject(Map.of("nodeId", "backup", "objectId", call.getArgument(1), "sha256", call.getArgument(3),
                        "size", ((java.io.File)call.getArgument(0)).length())));
        var saved = texts.save(uuid, "value=updated", original.version());
        assertEquals("value=updated", saved.content()); assertNull(context.query(uuid));
        assertTrue(routes.scan("/pending-publications/").isEmpty());
        assertEquals(saved.uuid(), keys.getIndex("bootfs", uuid).getUuid());
    }

    private String upload(String filename, String content) throws IOException {
        return uploadBytes(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test void explicitGbkCanCorrectUtf8ControlCharacterInterpretationAndPersistTheHint() throws Exception {
        byte[] source = {(byte) 0xc2, (byte) 0x80};
        String id = uploadBytes("ambiguous-control.txt", source);
        assertEquals(415, controller.read(id, request("GET")).getStatusCodeValue());
        var corrected = texts.read(id, "GBK");
        assertEquals("GBK", corrected.encoding());
        assertEquals("UTF-8", corrected.detectedEncoding());
        var saved = texts.save(id, corrected.content(), corrected.version(), "GBK");
        assertEquals("GBK", saved.encoding());
        assertNotEquals(id, saved.uuid());
        assertArrayEquals(source, Files.readAllBytes(Path.of(context.query(saved.uuid()).getPath())));
        context.reload();
        assertEquals(corrected.content(), texts.read(saved.uuid()).content());
        assertEquals("GBK", texts.read(saved.uuid()).encoding());
    }

    private String uploadBytes(String filename, byte[] content) throws IOException {
        String url = files.upload(new MockMultipartFile("file", filename, "text/plain", content));
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/file/remote/text");
    }
}
