package cc.ginpika.bootfs.kernel.migration;

import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.AdditionalAnswers;

class LegacyMetadataImportTest {
    @TempDir Path root;
    @Test void sourceOnlyFieldsSurviveResumableImport() throws Exception {
        JSONObject source=new JSONObject(); source.put("uuid","comic"); source.put("author","author");
        source.put("description","description"); source.put("resources",JSONArray.of("page2","page1"));
        JSONObject export=new JSONObject(); export.put("formatVersion",1);
        export.put("metadata",JSONObject.of("/s3/bootfs/key","{\"uuid\":\"comic\"}"));
        export.put("documents",JSONObject.of("full-text",JSONArray.of(source)));
        Path file=root.resolve("export.json"); Files.writeString(file,export.toJSONString());
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            new LegacyMetadataImport(store,file.toString()).run();
            long revision=store.revision();
            new LegacyMetadataImport(store,file.toString()).run();
            assertEquals(revision,store.revision());
            assertEquals(source,JSON.parseObject(store.get("/documents/full-text/comic")));
        }
    }
    @Test void conflictingImportFailsBeforeWritingAnyOtherRecord() throws Exception {
        JSONObject export=JSONObject.of("formatVersion",1,"metadata",JSONObject.of("/files/a","new","/files/b","new"));
        Path file=root.resolve("export.json"); Files.writeString(file,export.toJSONString());
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            store.put("/files/b","existing");
            assertThrows(Exception.class,() -> new LegacyMetadataImport(store,file.toString()).run());
            assertNull(store.get("/files/a"));
            assertEquals("existing",store.get("/files/b"));
        }
    }

    @Test void concurrentMutationAfterPreflightCannotBeOverwritten() throws Exception {
        JSONObject export=JSONObject.of("formatVersion",1,"metadata",JSONObject.of("/files/a","legacy","/files/b","legacy"));
        Path file=root.resolve("export.json"); Files.writeString(file,export.toJSONString());
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            MetadataStore racing=mock(MetadataStore.class,AdditionalAnswers.delegatesTo(store));
            doAnswer(call -> {
                store.put("/files/a","newer-authority");
                return store.compareAndSet(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            }).when(racing).compareAndSet(eq("/files/a"),isNull(),anyString());
            assertThrows(java.io.IOException.class,() -> new LegacyMetadataImport(racing,file.toString()).run());
            assertEquals("newer-authority",store.get("/files/a"));
            assertNull(store.get("/files/b"));
            assertTrue(store.scan("/migration/import/").isEmpty());
        }
    }

    @Test void exactlyMatchingPartialImportCanResume() throws Exception {
        JSONObject export=JSONObject.of("formatVersion",1,"metadata",JSONObject.of("/files/a","same","/files/b","new"));
        Path file=root.resolve("export.json"); Files.writeString(file,export.toJSONString());
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            store.put("/files/a","same");
            new LegacyMetadataImport(store,file.toString()).run();
            assertEquals("same",store.get("/files/a"));
            assertEquals("new",store.get("/files/b"));
            assertEquals(1,store.scan("/migration/import/").size());
        }
    }
}
