package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.s3.S3Config;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import cc.ginpika.bootfs.service.FederationService;
import com.alibaba.fastjson2.JSONArray;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderControllerTest {
    @TempDir Path temp;
    final Map<String, String> metadata = new LinkedHashMap<>();
    final EtcdService etcd = mock(EtcdService.class);
    final FileService files = mock(FileService.class);
    Context context;
    FolderController controller;

    @BeforeEach void setup() throws Exception {
        TfsConfig config = mock(TfsConfig.class);
        when(config.getWebEntrypoint()).thenReturn("https://files.example");
        when(config.getRootPath()).thenReturn(temp.toString());
        when(config.getUniqueId()).thenReturn("local");
        NodeIdentity identity = new NodeIdentity(config);
        context = new Context(config, mock(ContextIO.class), identity);
        S3Config s3 = new S3Config();
        controller = new FolderController(context, files, etcd, s3);
        when(etcd.getOne(anyString())).thenAnswer(call -> metadata.get(call.getArgument(0)));
        when(etcd.scan(anyString())).thenAnswer(call -> metadata.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(call.getArgument(0)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right, TreeMap::new)));
    }

    void object(String key, String uuid) {
        metadata.put("/s3/bootfs/" + key, new JSONObject(Map.of("uuid", uuid, "size", 4, "lastModified", 12)).toJSONString());
        metadata.put("/documents/full-text/" + uuid, new JSONObject(Map.of("uuid", uuid, "fileName", key,
                "fileCreatedAt", 12, "tags", List.of("manual:kept"))).toJSONString());
    }

    @Test void foldersGroupExplicitAndImplicitPathsBeforePaginationAndShowBasenames() {
        object("docs/", "marker");
        object("docs/子目录/report + 1.txt", "nested");
        object("empty/", "empty");
        object("root.txt", "root");
        JSONObject first = controller.list("", 0, 1, "");
        assertEquals(3, first.getIntValue("total"));
        JSONObject folder = first.getJSONArray("rows").getJSONObject(0);
        assertTrue(folder.getBooleanValue("folder"));
        assertEquals("docs", folder.getString("fileName"));
        assertEquals("docs/", folder.getString("folderPath"));
        assertEquals("marker", folder.getString("uuid"));
        assertEquals(12L, folder.getLongValue("createdAt"));
        assertEquals("kept", folder.getJSONArray("tags").getJSONObject(0).getString("name"));
        assertEquals("empty", controller.list("", 1, 1, "").getJSONArray("rows").getJSONObject(0).getString("fileName"));
        JSONObject child = controller.list("docs/", 0, 10, "");
        assertEquals(1, child.getIntValue("total"));
        assertEquals("子目录", child.getJSONArray("rows").getJSONObject(0).getString("fileName"));
        JSONObject file = controller.list("docs/子目录/", 0, 10, "").getJSONArray("rows").getJSONObject(0);
        assertEquals("report + 1.txt", file.getString("fileName"));
        assertEquals("docs/子目录/report + 1.txt", file.getString("objectKey"));
        assertEquals("kept", file.getJSONArray("tags").getJSONObject(0).getString("name"));
        assertEquals(0, controller.list("empty/", 0, 10, "").getIntValue("total"));
    }

    @Test void tombstonesAndExistingLocalReplicaVisibilityAreAppliedBeforeGrouping() {
        object("removed/file.txt", "gone");
        metadata.put("/tombstones/gone", "deleted");
        object("visible/file.txt", "present");
        object("replicas/file.txt", "replica");
        metadata.put("/documents/full-text/replica", "{\"uuid\":\"replica\",\"fileName\":\"replicas/file.txt\",\"copyOf\":\"original\"}");
        assertEquals(1, controller.list("", 0, 10, "").getIntValue("total"));
        assertEquals(2, controller.list("", 0, 10, "meta:all").getIntValue("total"));
        assertEquals(0, controller.list("", 0, 10, "meta:local").getIntValue("total"));
        assertEquals(0, controller.list("", 0, 10, "META:LOCAL").getIntValue("total"));
        context.STORAGE.put("present", new FileObject("/private/object", "present", "visible/file.txt", 4L));
        assertEquals(1, controller.list("", 0, 10, "meta:local").getIntValue("total"));
    }

    @Test void unboundCatalogFilesRemainVisibleWithoutExposingDiskPaths() {
        FileObject file = new FileObject("/private/object", "old", "old.txt", 4L);
        context.STORAGE.put("old", file);
        JSONObject result = controller.list("", 0, 10, "").getJSONArray("rows").getJSONObject(0);
        assertEquals("old.txt", result.getString("fileName"));
        assertFalse(result.containsKey("path"));
        assertEquals("/private/object", file.getPath());
    }

    @Test void browserRenamesRemainVisibleWhileObjectPathsAndArchivedResourcesArePreserved() {
        object("docs/original.txt", "renamed");
        metadata.put("/documents/full-text/renamed", "{\"uuid\":\"renamed\",\"fileName\":\"display.txt\"}");
        object("archived/file.txt", "archived");
        metadata.put("/documents/full-text/archived", "{\"uuid\":\"archived\",\"fileName\":\"archived/file.txt\",\"_bootfsDeleted\":true}");
        JSONObject file = controller.list("docs/", 0, 10, "").getJSONArray("rows").getJSONObject(0);
        assertEquals("display.txt", file.getString("fileName"));
        assertEquals("docs/original.txt", file.getString("objectKey"));
        assertEquals(1, controller.list("", 0, 10, "").getIntValue("total"));
    }

    @Test void independentNodeResourcesRemainVisibleInFoldersAndReportUnavailableNodes() {
        FederationService federation = mock(FederationService.class);
        ReflectionTestUtils.setField(controller, "federation", federation);
        when(federation.peers()).thenReturn(Map.of("remote", "https://remote.example"));
        JSONObject hit = new JSONObject(Map.of("uuid", "remote-file", "fileName", "共享/file.txt",
                "originEndpoint", "https://remote.example", "originNodeId", "remote", "fileCreatedAt", 20));
        when(federation.search("", List.of(), 0, 1000, false)).thenReturn(new JSONObject(Map.of(
                "hits", new JSONArray(List.of(hit)), "total", 1, "partial", true, "unavailableNodes", List.of("offline"))));
        JSONObject root = controller.list("", 0, 10, "");
        assertEquals("共享", root.getJSONArray("rows").getJSONObject(0).getString("fileName"));
        assertTrue(root.getBooleanValue("partial"));
        assertEquals(List.of("offline"), root.getJSONArray("unavailableNodes").toJavaList(String.class));
        JSONObject file = controller.list("共享/", 0, 10, "").getJSONArray("rows").getJSONObject(0);
        assertEquals("file.txt", file.getString("fileName"));
        assertEquals("https://remote.example/f/remote-file", file.getString("url"));
        assertEquals(0, controller.list("", 0, 10, "meta:local").getIntValue("total"));
    }

    @Test void createsEmptyDirectoryMarkerAndUploadsToExactUnicodePath() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-BootFS-Request-Id", "request-1");
        JSONObject folder = controller.create(Map.of("folder", "父目录/", "name", "名称 + 空格"), request);
        assertEquals("名称 + 空格", folder.getString("fileName"));
        verify(files).publishS3(argThat(input -> { try { return input.read() == -1; } catch (Exception failure) { return false; } }),
                eq("父目录/名称 + 空格/"), eq("bootfs"), eq("application/x-directory"), isNull(), isNull(), eq("request-1"));
        when(files.publishS3(any(InputStream.class), eq("父目录/a + b.txt"), eq("bootfs"), eq("text/plain"),
                isNull(), isNull(), eq("request-1"))).thenAnswer(call -> {
                    assertArrayEquals(new byte[]{1, 2, 3}, ((InputStream) call.getArgument(0)).readAllBytes());
                    return new FileService.PublishedResource(new FileObject(null, "new", "父目录/a + b.txt", 3L), "etag");
                });
        JSONObject result = controller.upload("父目录/", new MockMultipartFile("file", "a + b.txt", "text/plain", new byte[]{1, 2, 3}), request);
        assertEquals("父目录/a + b.txt", result.getString("objectKey"));
        assertEquals("new", result.getString("uuid"));
    }

    @Test void invalidNamesPathsAndExistingUploadsDoNotPublish() throws Exception {
        var request = new MockHttpServletRequest();
        for (String name : List.of("", " ", ".", "..", "a/b", "a\\b", "a\u0000b")) {
            assertThrows(ResponseStatusException.class, () -> controller.create(Map.of("name", name), request));
        }
        assertThrows(ResponseStatusException.class, () -> controller.list("missing-slash", 0, 10, ""));
        assertThrows(ResponseStatusException.class, () -> controller.list("", -1, 10, ""));
        assertThrows(ResponseStatusException.class, () -> controller.list("", 0, 1001, ""));
        object("existing/file.txt", "existing");
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> controller.upload("existing/",
                new MockMultipartFile("file", "file.txt", "text/plain", new byte[0]), request));
        assertEquals(409, failure.getRawStatusCode());
        verifyNoInteractions(files);
    }
}
