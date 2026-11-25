package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"sso.enabled=false", "bootfs.cleanup.interval-ms=600000"})
@AutoConfigureMockMvc
@DirtiesContext
class DatabaseRebuildIntegrationTest {
    private static final Path ROOT = MononodeIntegrationTest.tempRoot();
    private static final Path DATA = ROOT.resolve("data");
    private static final Path DB = ROOT.resolve("db.json");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path", ROOT::toString); }

    @Autowired MockMvc mvc;
    @Autowired MetadataStore metadata;
    @Autowired LocalFileInventory inventory;
    @Autowired ResourceSearchService search;

    @Test void settingsRebuildsCatalogRoutesAndSearchFromPortableFileSources() throws Exception {
        prepareSources();
        String prefix = "/nodes/" + inventory.uuid + "/catalog/";
        String generations = "/resource-content/" + inventory.uuid + "/album/";
        metadata.writeBatch(Map.of(prefix + "missing", "{\"uuid\":\"missing\",\"fileName\":\"gone.jpg\"}",
                "/files/missing", "https://old.example/f/missing", "/tombstones/removed", "1",
                generations + "old-payload", "content",
                "/s3/bootfs/comics/book.zip", "{\"uuid\":\"album\",\"key\":\"comics/book.zip\"}"), Set.of());
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rebuildDatabase")));
        mvc.perform(rebuild().header("Origin", "https://other.example")).andExpect(status().isForbidden());
        mvc.perform(rebuild()).andExpect(status().isOk()).andExpect(jsonPath("$.files").value(5));

        assertNull(metadata.get(prefix + "missing"));
        assertNull(metadata.get("/files/missing"));
        assertNull(metadata.get(prefix + "removed"));
        assertNull(metadata.get(prefix + "payload"));
        assertNull(metadata.get(prefix + "old-payload"));
        assertEquals("/f/album", metadata.get("/files/album"));
        JSONObject album = JSONObject.parseObject(metadata.get("/documents/full-text/album"));
        assertEquals("/f/album", album.getString("postUrl"));
        assertEquals("/thumb/page", album.getString("thumbUrl"));
        assertEquals("/p/album", album.getString("publicUrl"));
        assertEquals(List.of("/thumb/page", "https://external.example/art.png"),
                album.getJSONArray("resources").toList(String.class));
        assertEquals(1, search.searchResources("旧版图包", List.of("manual:蓝色"), 0, 10, false).getTotalHits());
        assertEquals("1", inventory.query("video").getHlsAvailable());
        assertEquals("1", inventory.query("page").getThumbAvailable());
        mvc.perform(get("/f/album").header("Host", "new.example")).andExpect(status().isOk());

        JSONObject source = JSONObject.parseObject(Files.readString(DB));
        assertEquals("payload", source.getJSONObject("album").getString("contentObjectId"));
        assertFalse(source.getJSONObject("album").containsKey("path"));
        assertNull(source.get("missing"));
        assertEquals("旧版图包", source.getJSONObject("album").getJSONObject("_document").getString("title"));

        metadata.deletePrefix("/documents/");
        metadata.deletePrefix(prefix);
        metadata.deletePrefix("/files/");
        metadata.deletePrefix(generations);
        metadata.deletePrefix("/s3/");
        mvc.perform(rebuild()).andExpect(status().isOk()).andExpect(jsonPath("$.files").value(5));
        assertEquals("content", metadata.get(generations + "old-payload"));
        assertEquals("content", metadata.get(generations + "payload"));
        assertEquals("album", JSONObject.parseObject(metadata.get("/s3/bootfs/comics/book.zip")).getString("uuid"));
        assertEquals(1, search.searchResources("旧版图包", List.of("manual:蓝色"), 0, 10, false).getTotalHits());

        var before = metadata.scan("");
        Files.writeString(DB, "{\"album\":{");
        mvc.perform(rebuild()).andExpect(status().isServiceUnavailable());
        assertEquals(before, metadata.scan(""));
        assertEquals("{\"album\":{", Files.readString(DB));
        assertEquals("cover.jpg", inventory.query("page").getFileName());
    }

    private static MockHttpServletRequestBuilder rebuild() {
        return post("/api/settings/database/rebuild").header("X-BootFS-Request", "1")
                .contentType("application/json").content("{\"confirm\":\"REBUILD\"}");
    }

    private static void prepareSources() throws IOException {
        Files.createDirectories(DATA.resolve("thumb"));
        Files.createDirectories(DATA.resolve("hls/video"));
        Files.writeString(DATA.resolve("payload"), "album archive");
        Files.writeString(DATA.resolve("old-payload"), "superseded archive bytes");
        Files.writeString(DATA.resolve("page"), "page bytes");
        Files.writeString(DATA.resolve("orphan"), "bytes with no catalog record");
        Files.writeString(DATA.resolve("legacy"), "legacy bytes");
        Files.writeString(DATA.resolve("removed"), "uncollected deleted bytes");
        Files.writeString(DATA.resolve("hls/video/playlist.m3u8"), "#EXTM3U\n");
        Files.writeString(DATA.resolve("thumb/page.webp"), "thumbnail");
        Files.writeString(DB, """
                {"album":{"uuid":"album","fileName":"book.zip","albumAvailable":"1","contentObjectId":"payload",
                  "_document":{"uuid":"album","title":"旧版图包","tags":["manual:蓝色"],"postUrl":"https://old.example/f/album",
                    "publicUrl":"https://old.example/p/album","resources":["https://old.example/thumb/page","https://external.example/art.png"]}},
                 "page":{"uuid":"page","fileName":"cover.jpg","parent":"album","path":"/old/root/data/page"},
                 "legacy":{"fileName":"legacy.txt"},
                 "missing":{"uuid":"missing","fileName":"gone.jpg"}}
                """);
    }
}
