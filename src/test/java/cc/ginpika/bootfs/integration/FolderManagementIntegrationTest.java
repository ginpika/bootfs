package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.s3.S3KeyIndex;
import cc.ginpika.bootfs.s3.S3ObjectMeta;
import cc.ginpika.bootfs.s3.S3StorageService;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.FolderService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"sso.enabled=false", "s3.enabled=true", "s3.access-key=test",
        "s3.secret-key=test-secret", "tfs.ffmpeg-url=/nonexistent-ffmpeg"})
@AutoConfigureMockMvc
@DirtiesContext
class FolderManagementIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("tfs.root-path", ROOT::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired Context context;
    @Autowired FileService files;
    @Autowired FolderService folders;
    @Autowired S3StorageService storage;
    @Autowired S3KeyIndex keys;
    @Autowired EtcdService metadata;

    private void createFolder(String parent, String name) throws Exception {
        mvc.perform(post("/api/folders").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("folder", parent, "name", name))))
                .andExpect(status().is2xxSuccessful());
    }

    private S3ObjectMeta object(String key, String content) throws Exception {
        var request = new MockHttpServletRequest();
        request.setContentType("text/plain");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return storage.putObject("bootfs", key, request);
    }

    private JSONObject list(String path) throws Exception {
        byte[] body = mvc.perform(get("/api/folders").param("folder", path).param("pageSize", "1000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return JSONObject.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    private JSONObject folderRow(String parent, String path) throws Exception {
        return list(parent).getJSONArray("rows").stream().map(JSONObject::from)
                .filter(row -> path.equals(row.getString("folderPath"))).findFirst().orElseThrow();
    }

    private void rename(String path, String name) throws Exception {
        mvc.perform(put("/api/folders/rename").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("folder", path, "name", name))))
                .andExpect(status().is2xxSuccessful());
    }

    private void deleteFolder(String path) throws Exception {
        mvc.perform(delete("/api/folders").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("folder", path))))
                .andExpect(status().is2xxSuccessful());
    }

    private void addTag(String path, String name) throws Exception {
        mvc.perform(post("/api/folders/tags").contentType("application/json").content(JSONObject.toJSONString(
                Map.of("folder", path, "tags", List.of(Map.of("namespace", "manual", "name", name))))))
                .andExpect(status().is2xxSuccessful());
    }

    @Test void explicitFoldersExposePersistentIdentityCreationTimeAndEditableTags() throws Exception {
        createFolder("", "有元数据");
        JSONObject original = folderRow("", "有元数据/");
        assertEquals(keys.getIndex("bootfs", "有元数据/").getUuid(), original.getString("uuid"));
        assertTrue(original.getLongValue("createdAt") > 0);
        assertNotNull(original.getJSONArray("tags"));
        addTag("有元数据/", "重要");
        JSONObject tagged = folderRow("", "有元数据/");
        assertEquals(original.getString("uuid"), tagged.getString("uuid"));
        assertEquals(original.getLong("createdAt"), tagged.getLong("createdAt"));
        assertEquals("重要", tagged.getJSONArray("tags").getJSONObject(0).getString("name"));
        mvc.perform(delete("/api/folders/tags").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("folder", "有元数据/", "tags", List.of("manual:重要")))))
                .andExpect(status().is2xxSuccessful());
        assertTrue(folderRow("", "有元数据/").getJSONArray("tags").isEmpty());
    }

    @Test void implicitFolderRenameMovesAllNestedPathsWithoutChangingContentOrIdentity() throws Exception {
        S3ObjectMeta child = object("隐式 + 目录/内层/file.txt", "unchanged content");
        JSONObject original = folderRow("", "隐式 + 目录/");
        JSONObject nested = folderRow("隐式 + 目录/", "隐式 + 目录/内层/");
        addTag("隐式 + 目录/", "待归档");
        rename("隐式 + 目录/", "归档 + 中文");
        assertNull(keys.getIndex("bootfs", "隐式 + 目录/内层/file.txt"));
        assertEquals(child.getUuid(), keys.getIndex("bootfs", "归档 + 中文/内层/file.txt").getUuid());
        JSONObject renamed = folderRow("", "归档 + 中文/");
        assertEquals(original.getString("uuid"), renamed.getString("uuid"));
        assertEquals(original.getLong("createdAt"), renamed.getLong("createdAt"));
        assertEquals("待归档", renamed.getJSONArray("tags").getJSONObject(0).getString("name"));
        assertEquals(nested.getString("uuid"), folderRow("归档 + 中文/", "归档 + 中文/内层/").getString("uuid"));
        assertEquals("归档 + 中文/内层/file.txt", context.query(child.getUuid()).getFileName());
        assertEquals("归档 + 中文/内层/file.txt", JSONObject.parseObject(metadata.getOne(
                "/documents/full-text/" + child.getUuid())).getString("fileName"));
        assertEquals("unchanged content", Files.readString(Path.of(context.query(child.getUuid()).getPath())));
        context.reload();
        files.bootstrapExistingBindings();
        assertEquals("归档 + 中文/内层/file.txt", context.query(child.getUuid()).getFileName());
        assertTrue(metadata.scan("/s3/bootfs/隐式 + 目录/").isEmpty());
    }

    @Test void recursiveDeletionRemovesMarkersDescendantsBytesAndSearchButPreservesSimilarPrefixes() throws Exception {
        createFolder("", "delete-tree");
        createFolder("delete-tree/", "empty");
        S3ObjectMeta rootMarker = keys.getIndex("bootfs", "delete-tree/");
        S3ObjectMeta nestedMarker = keys.getIndex("bootfs", "delete-tree/empty/");
        S3ObjectMeta child = object("delete-tree/nested/child.txt", "remove");
        S3ObjectMeta sibling = object("delete-tree-other/keep.txt", "keep");
        folderRow("", "delete-tree/");
        folderRow("delete-tree/", "delete-tree/nested/");
        deleteFolder("delete-tree/");
        for (String uuid : List.of(rootMarker.getUuid(), nestedMarker.getUuid(), child.getUuid())) {
            assertNull(context.query(uuid));
            assertNotNull(metadata.getOne("/tombstones/" + uuid));
            assertFalse(Files.exists(ROOT.resolve("data").resolve(uuid)));
        }
        assertTrue(metadata.scan("/s3/bootfs/delete-tree/").isEmpty());
        assertNotNull(context.query(sibling.getUuid()));
        context.reload();
        files.bootstrapExistingBindings();
        assertTrue(metadata.scan("/s3/bootfs/delete-tree/").isEmpty());
        assertFalse(list("").getJSONArray("rows").stream().map(JSONObject::from)
                .anyMatch(row -> "delete-tree/".equals(row.getString("folderPath"))));
        assertEquals("keep", Files.readString(Path.of(context.query(sibling.getUuid()).getPath())));
    }

    @Test void renamingToAnExistingDirectoryIsRejectedAndRootOperationsCannotMutateAnything() throws Exception {
        S3ObjectMeta source = object("rename-source/child.txt", "source");
        S3ObjectMeta target = object("rename-target/child.txt", "target");
        mvc.perform(put("/api/folders/rename").contentType("application/json")
                .content("{\"folder\":\"rename-source/\",\"name\":\"rename-target\"}"))
                .andExpect(status().isConflict());
        for (String path : List.of("", "/")) {
            mvc.perform(delete("/api/folders").contentType("application/json")
                    .content(JSONObject.toJSONString(Map.of("folder", path)))).andExpect(status().isBadRequest());
            mvc.perform(put("/api/folders/rename").contentType("application/json")
                    .content(JSONObject.toJSONString(Map.of("folder", path, "name", "new-root"))))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(source.getUuid(), keys.getIndex("bootfs", "rename-source/child.txt").getUuid());
        assertEquals(target.getUuid(), keys.getIndex("bootfs", "rename-target/child.txt").getUuid());
    }

    @Test void interruptedRecursiveDeletionAlreadyBlocksEveryChildAndCleanupResumesWithoutRevivingPaths() throws Exception {
        createFolder("", "interrupted-delete");
        S3ObjectMeta marker = keys.getIndex("bootfs", "interrupted-delete/");
        S3ObjectMeta child = object("interrupted-delete/nested/child.txt", "private bytes");
        FileService interrupted = spy(files);
        doThrow(new IllegalStateException("Process stopped after the namespace commit"))
                .when(interrupted).deleteResource(anyString());
        ReflectionTestUtils.setField(folders, "files", interrupted);
        try {
            assertThrows(IllegalStateException.class, () -> folders.delete("interrupted-delete/"));
        } finally {
            ReflectionTestUtils.setField(folders, "files", files);
        }
        for (String id : List.of(marker.getUuid(), child.getUuid())) {
            assertNotNull(metadata.getOne("/tombstones/" + id));
            assertNull(files.download(id, new MockHttpServletRequest()));
        }
        assertTrue(metadata.scan("/s3/bootfs/interrupted-delete/").isEmpty());
        files.resumeGlobalDeletions();
        context.reload();
        files.bootstrapExistingBindings();
        assertNull(context.query(child.getUuid()));
        assertFalse(Files.exists(ROOT.resolve("data").resolve(child.getUuid())));
        assertTrue(metadata.scan("/s3/bootfs/interrupted-delete/").isEmpty());
    }

    @Test void iconViewMovePlacesFilesAndFoldersIntoTheDestinationWithoutRewritingBytes() throws Exception {
        createFolder("", "归档");
        S3ObjectMeta file = object("笔记.txt", "bytes");
        S3ObjectMeta nested = object("资料/内层.txt", "nested");
        mvc.perform(post("/api/folders/move").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("destination", "归档/",
                        "files", List.of("笔记.txt"), "folders", List.of("资料/")))))
                .andExpect(status().is2xxSuccessful());
        assertNull(keys.getIndex("bootfs", "笔记.txt"));
        assertNull(keys.getIndex("bootfs", "资料/内层.txt"));
        assertEquals(file.getUuid(), keys.getIndex("bootfs", "归档/笔记.txt").getUuid());
        assertEquals(nested.getUuid(), keys.getIndex("bootfs", "归档/资料/内层.txt").getUuid());
        assertEquals("归档/笔记.txt", context.query(file.getUuid()).getFileName());
        assertEquals("bytes", Files.readString(Path.of(context.query(file.getUuid()).getPath())));
        assertEquals("nested", Files.readString(Path.of(context.query(nested.getUuid()).getPath())));
        assertEquals("笔记.txt", list("归档/").getJSONArray("rows").stream().map(JSONObject::from)
                .filter(row -> !row.getBooleanValue("folder")).findFirst().orElseThrow().getString("fileName"));
        assertEquals("资料", folderRow("归档/", "归档/资料/").getString("fileName"));
    }

    @Test void moveKeepsARenamedDisplayNameAndRejectsCollisionsOrMovesIntoItself() throws Exception {
        createFolder("", "归档");
        S3ObjectMeta file = object("笔记.txt", "bytes");
        object("归档/笔记.txt", "already");
        String documentKey = "/documents/full-text/" + file.getUuid();
        JSONObject document = JSONObject.parseObject(metadata.getOne(documentKey));
        document.put("fileName", "夏日.jpg");
        document.put("title", "夏日.jpg");
        metadata.put(documentKey, document.toJSONString());
        mvc.perform(post("/api/folders/move").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("destination", "归档/", "files", List.of("笔记.txt"), "folders", List.of()))))
                .andExpect(status().isConflict());
        assertEquals(file.getUuid(), keys.getIndex("bootfs", "笔记.txt").getUuid());
        assertEquals("夏日.jpg", JSONObject.parseObject(metadata.getOne(documentKey)).getString("fileName"));

        S3ObjectMeta picture = object("照片.jpg", "picture");
        String pictureKey = "/documents/full-text/" + picture.getUuid();
        JSONObject pictureDocument = JSONObject.parseObject(metadata.getOne(pictureKey));
        pictureDocument.put("fileName", "夏日.jpg");
        pictureDocument.put("title", "夏日.jpg");
        metadata.put(pictureKey, pictureDocument.toJSONString());
        mvc.perform(post("/api/folders/move").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("destination", "归档/", "files", List.of("照片.jpg"), "folders", List.of()))))
                .andExpect(status().is2xxSuccessful());
        assertEquals(picture.getUuid(), keys.getIndex("bootfs", "归档/照片.jpg").getUuid());
        assertEquals("夏日.jpg", JSONObject.parseObject(metadata.getOne(pictureKey)).getString("fileName"));
        assertEquals("夏日.jpg", list("归档/").getJSONArray("rows").stream().map(JSONObject::from)
                .filter(row -> "归档/照片.jpg".equals(row.getString("objectKey"))).findFirst().orElseThrow().getString("fileName"));

        object("盒子/内层/child.txt", "child");
        mvc.perform(post("/api/folders/move").contentType("application/json")
                .content(JSONObject.toJSONString(Map.of("destination", "盒子/内层/", "files", List.of(), "folders", List.of("盒子/")))))
                .andExpect(status().isConflict());
        assertNotNull(keys.getIndex("bootfs", "盒子/内层/child.txt"));
    }
}
