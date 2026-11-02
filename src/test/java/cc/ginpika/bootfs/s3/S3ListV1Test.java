package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cyberduck lists with V1 markers; its cursor must advance across directory-only pages. */
class S3ListV1Test {
    private final Map<String, String> records = new TreeMap<>();
    private S3KeyIndex keys;
    private S3XmlWriter xml;
    private MockMvc mvc;

    @BeforeEach void setup() {
        EtcdService routes = mock(EtcdService.class);
        when(routes.scan(anyString())).thenAnswer(call -> {
            String prefix = call.getArgument(0);
            Map<String, String> result = new TreeMap<>();
            records.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        });
        keys = new S3KeyIndex(routes);
        xml = new S3XmlWriter();
        S3Config config = new S3Config(); config.setBucket("bootfs");
        mvc = MockMvcBuilders.standaloneSetup(new S3Controller(config, mock(S3StorageService.class), keys, xml)).build();
    }

    @Test void v1MarkerAdvancesAcrossCommonPrefixesAndNeverReplaysADirectory() {
        object("docs/", "folder"); object("docs/readme.md", "readme");
        object("docs/nested/file.txt", "nested"); object("images/a.png", "image");
        object("notes.txt", "notes");
        List<String> listed = new ArrayList<>();
        String marker = null;
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            S3ListPage page = keys.listIndexV1("bootfs", "", "/", 1, marker);
            listed.addAll(page.getCommonPrefixes());
            page.getContents().forEach(meta -> listed.add(meta.getKey()));
            assertNull(page.getNextContinuationToken());
            assertEquals(pageNumber < 2, page.isTruncated());
            marker = page.getNextMarker();
        }
        assertEquals(List.of("docs/", "images/", "notes.txt"), listed);
        assertNull(marker);
        assertEquals(List.of("images/"), keys.listIndexV1("bootfs", "", "/", 1, "docs/z").getCommonPrefixes());
    }

    @Test void nestedListingPreservesThePlaceholderAndCountsEachChildFolderOnce() {
        object("docs/", "folder"); object("docs/readme.md", "readme");
        object("docs/sub/", "subfolder"); object("docs/sub/a.txt", "a");
        object("docs/sub/b.txt", "b"); object("other.txt", "other");
        S3ListPage page = keys.listIndexV1("bootfs", "docs/", "/", 1000, null);
        assertEquals(List.of("docs/", "docs/readme.md"), page.getContents().stream().map(S3ObjectMeta::getKey).toList());
        assertEquals(List.of("docs/sub/"), page.getCommonPrefixes());
        assertFalse(page.isTruncated());
    }

    @Test void moreThanAThousandObjectsCanBeReadWithTheV1Cursor() {
        for (int n = 0; n < 1105; n++) object(String.format("file-%04d.txt", n), "id-" + n);
        List<String> listed = new ArrayList<>();
        S3ListPage first = keys.listIndexV1("bootfs", null, null, 1000, null);
        assertEquals(1000, first.getContents().size());
        assertEquals("file-0999.txt", first.getNextMarker());
        listed.addAll(first.getContents().stream().map(S3ObjectMeta::getKey).toList());
        S3ListPage next = keys.listIndexV1("bootfs", null, null, 1000, first.getNextMarker());
        listed.addAll(next.getContents().stream().map(S3ObjectMeta::getKey).toList());
        assertFalse(next.isTruncated());
        assertEquals(1105, new HashSet<>(listed).size());
        assertEquals("file-1104.txt", listed.get(listed.size() - 1));
    }

    @Test void tombstonesAndEmptyPagesDoNotProduceDanglingDirectoriesOrCursors() {
        object("deleted/a.txt", "deleted"); records.put("/tombstones/deleted", "deleted");
        S3ListPage page = keys.listIndexV1("bootfs", "", "/", 1, null);
        assertTrue(page.getContents().isEmpty()); assertTrue(page.getCommonPrefixes().isEmpty());
        assertFalse(page.isTruncated()); assertNull(page.getNextMarker());
        object("kept.txt", "kept");
        S3ListPage zero = keys.listIndexV1("bootfs", "", "/", 0, null);
        assertTrue(zero.getContents().isEmpty()); assertFalse(zero.isTruncated());
        assertNull(zero.getNextMarker());
    }

    @Test void v2StillUsesAnOpaqueContextBoundContinuationToken() {
        object("a.txt", "a"); object("b.txt", "b");
        S3ListPage first = keys.listIndex("bootfs", "", "/", 1, null);
        assertTrue(first.isTruncated()); assertNotEquals("a.txt", first.getNextContinuationToken());
        assertNull(first.getNextMarker());
        assertEquals("b.txt", keys.listIndex("bootfs", "", "/", 1, first.getNextContinuationToken()).getContents().get(0).getKey());
        assertThrows(IllegalArgumentException.class, () -> keys.listIndex("bootfs", "different/", "/", 1, first.getNextContinuationToken()));
    }

    @Test void controllerSelectsV1MarkersOrV2ContinuationTokensAndHonorsZeroMaxKeys() throws Exception {
        object("docs/file.txt", "docs"); object("file.txt", "file");
        mvc.perform(get("/s3/bootfs").param("delimiter", "/").param("max-keys", "1"))
                .andExpect(status().isOk())
                .andExpect(xpath("//*[local-name()='Marker']").string(""))
                .andExpect(xpath("//*[local-name()='NextMarker']").string("docs/"))
                .andExpect(xpath("//*[local-name()='NextContinuationToken']").doesNotExist());
        mvc.perform(get("/s3/bootfs").param("delimiter", "/").param("max-keys", "1").param("marker", "docs/"))
                .andExpect(status().isOk())
                .andExpect(xpath("//*[local-name()='Contents']/*[local-name()='Key']").string("file.txt"))
                .andExpect(xpath("//*[local-name()='IsTruncated']").string("false"));
        mvc.perform(get("/s3/bootfs").param("list-type", "2").param("delimiter", "/").param("max-keys", "1"))
                .andExpect(status().isOk())
                .andExpect(xpath("//*[local-name()='NextContinuationToken']").exists())
                .andExpect(xpath("//*[local-name()='NextMarker']").doesNotExist());
        mvc.perform(get("/s3/bootfs").param("max-keys", "0"))
                .andExpect(status().isOk())
                .andExpect(xpath("//*[local-name()='Contents']").doesNotExist())
                .andExpect(xpath("//*[local-name()='MaxKeys']").string("0"));
    }

    private void object(String key, String uuid) {
        records.put("/s3/bootfs/" + key, JSON.toJSONString(new S3ObjectMeta(uuid, 1, "etag", 1, key, "text/plain")));
    }
}
