package cc.ginpika.bootfs.core.io;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.AdditionalAnswers;

class ContextPersistenceTest {
    @TempDir Path root;
    @Test void importsLongLegacyRecordAndDoesNotResurrectDeletedResourceOnRestart() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        NodeIdentity identity=new NodeIdentity(config);
        FileObject file=new FileObject(root.resolve("data/legacy").toString(),"legacy","x".repeat(70000),42L);
        JSONObject legacy=new JSONObject(); legacy.put("legacy",JSONObject.from(file));
        Path json=root.resolve("db.json"); Files.writeString(json,legacy.toJSONString());
        String original=Files.readString(json);
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            ContextIO io=new ContextIO(store,config,identity,true);
            assertEquals(70000,io.load().get("legacy").getFileName().length());
            io.remove("legacy");
        }
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            ContextIO io=new ContextIO(store,config,new NodeIdentity(config),true);
            assertTrue(io.load().isEmpty());
        }
        assertEquals(original,Files.readString(json));
    }
    @Test void corruptLegacyCatalogFailsRatherThanSilentlyLoadingSubset() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        Files.writeString(root.resolve("db.json"),"{\"broken\":");
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            assertThrows(Exception.class,() -> new ContextIO(store,config,new NodeIdentity(config)).load());
        }
    }
    @Test void legacyCatalogRequiresExplicitMigrationDecision() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        Files.writeString(root.resolve("db.json"),"{\"old\":{\"uuid\":\"old\",\"fileName\":\"old.txt\"}}");
        try(RocksMetadataStore store=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            assertThrows(java.io.IOException.class,() -> new ContextIO(store,config,new NodeIdentity(config)).load());
            assertTrue(store.scan("/nodes/").isEmpty());
        }
    }

    @Test void malformedTailAfterManyRecordsNeverPublishesPartialCatalog() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        NodeIdentity identity=new NodeIdentity(config);
        JSONObject source=new JSONObject();
        for (int i=0;i<300;i++) source.put("file"+i,JSONObject.of("uuid","file"+i,"fileName","old.txt"));
        String valid=source.toJSONString();
        Files.writeString(root.resolve("db.json"),valid.substring(0,valid.length()-1)+",\"truncated\":");
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            assertThrows(java.io.IOException.class,() -> new ContextIO(store,config,identity,true).load());
            assertTrue(store.scan("/nodes/").isEmpty(),"Validation must finish before the first catalog write");
        }
    }

    @Test void differingPartialImportFailsBeforeInsertingOtherResources() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        NodeIdentity identity=new NodeIdentity(config);
        JSONObject source=JSONObject.of("a",JSONObject.of("uuid","a","fileName","a.txt"),
                "b",JSONObject.of("uuid","b","fileName","corrected.txt"));
        Files.writeString(root.resolve("db.json"),source.toJSONString());
        String prefix="/nodes/"+identity.id()+"/catalog/";
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            store.put(prefix+"b",JSONObject.of("uuid","b","fileName","stale.txt").toJSONString());
            assertThrows(java.io.IOException.class,() -> new ContextIO(store,config,identity,true).load());
            assertNull(store.get(prefix+"a"));
            assertEquals("stale.txt",JSONObject.parseObject(store.get(prefix+"b")).getString("fileName"));
            assertNull(store.get("/nodes/"+identity.id()+"/migration/db-json-v1"));
        }
    }

    @Test void invalidResourceAndRelationshipIdsFailBeforeMutation() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        NodeIdentity identity=new NodeIdentity(config);
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            for (String source:new String[]{"{\"../escape\":{\"uuid\":\"../escape\"}}",
                    "{\"safe\":{\"uuid\":\"safe\",\"parent\":\"../parent\"}}",
                    "{\"safe\":{\"uuid\":\"safe\"},\"safe\":{\"uuid\":\"safe\"}}"}) {
                Files.writeString(root.resolve("db.json"),source);
                assertThrows(java.io.IOException.class,() -> new ContextIO(store,config,identity,true).load());
                assertTrue(store.scan("/nodes/").isEmpty());
            }
        }
    }

    @Test void concurrentCatalogChangeBetweenValidationAndInsertIsPreserved() throws Exception {
        TfsConfig config=new TfsConfig(); config.setRootPath(root.toString());
        NodeIdentity identity=new NodeIdentity(config);
        Files.writeString(root.resolve("db.json"),"{\"a\":{\"uuid\":\"a\",\"fileName\":\"legacy.txt\"}}");
        String key="/nodes/"+identity.id()+"/catalog/a";
        try (RocksMetadataStore store=new RocksMetadataStore(root.resolve("store"))) {
            MetadataStore racing=mock(MetadataStore.class,AdditionalAnswers.delegatesTo(store));
            doAnswer(call -> {
                store.put(key,JSONObject.of("uuid","a","fileName","newer.txt").toJSONString());
                return store.compareAndSet(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            }).when(racing).compareAndSet(eq(key),isNull(),anyString());
            assertThrows(java.io.IOException.class,() -> new ContextIO(racing,config,identity,true).load());
            assertEquals("newer.txt",JSONObject.parseObject(store.get(key)).getString("fileName"));
            assertNull(store.get("/nodes/"+identity.id()+"/migration/db-json-v1"));
        }
    }
}
