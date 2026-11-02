package cc.ginpika.bootfs.kernel.backup;

import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KernelBackupTest {
    @TempDir Path temporary;
    Path source, backup;
    Map<String,String> original;
    @BeforeEach void setup() throws Exception {
        source = temporary.resolve("source"); backup = temporary.resolve("backup");
        Files.createDirectories(source.resolve("data/thumb")); Files.writeString(source.resolve("uuid"), "old");
        Files.write(source.resolve("data/book"), new byte[]{0,1,2,-1});
        Files.writeString(source.resolve("data/thumb/book.webp"), "thumbnail");
        Files.createDirectories(source.resolve("data/hls/virtual")); Files.writeString(source.resolve("data/hls/virtual/playlist.m3u8"), "#EXTM3U");
        original = new TreeMap<>();
        original.put("/nodes/old/catalog/book", "{\"uuid\":\"book\",\"fileName\":\"银河漫画.zip\",\"size\":4,\"albumAvailable\":\"1\"}");
        original.put("/nodes/old/catalog/virtual", "{\"uuid\":\"virtual\",\"size\":0,\"hlsAvailable\":\"1\"}");
        original.put("/files/book", "http://old/f/book");
        original.put("/files/book/meta", "{\"size\":4,\"sha256\":\"" + ContentHash.sha256(source.resolve("data/book")) + "\"}");
        original.put("/cluster/node/old", "http://old");
        original.put("/documents/full-text/book", "{\"uuid\":\"book\",\"title\":\"银河漫画\",\"author\":\"作者甲\",\"description\":\"星际旅行\",\"contextUuid\":\"old\",\"tags\":[\"manual:科幻\"],\"resources\":[\"http://old/thumb/page2\",\"http://old/thumb/page1\"],\"poster\":\"http://old/thumb/book\"}");
        original.put("/s3/bootfs/books", "{\"uuid\":\"book\",\"size\":4,\"key\":\"books\"}");
        original.put("/tombstones/deleted", "123"); original.put("custom-key", "original text");
        try (var store = new RocksMetadataStore(source.resolve("kernel/metadata"))) { store.writeBatch(original, Set.of()); }
    }
    @Test void portableBackupRestoresIdentityBindingsOriginalDocumentsAndRebuildsSearch() throws Exception {
        KernelBackup.export(List.of(source), backup, true);
        JSONObject manifest = KernelBackup.verify(backup); assertEquals(1, manifest.getLongValue("sourceRevision"));
        Path seed = temporary.resolve("seed"); Path plan = KernelBackup.restore(backup, seed, "new", "http://new");
        assertArrayEquals(new byte[]{0,1,2,-1}, Files.readAllBytes(seed.resolve("data/book")));
        assertEquals("#EXTM3U", Files.readString(seed.resolve("data/hls/virtual/playlist.m3u8")));
        assertFalse(Files.exists(seed.resolve("kernel/search"))); assertFalse(Files.exists(seed.resolve("kernel/raft")));
        try (var store = new RocksMetadataStore(seed.resolve("kernel/metadata"))) {
            RestorePlan.apply(store, plan); long revision = store.revision(); RestorePlan.apply(store, plan); assertEquals(revision, store.revision());
            assertEquals("http://new/f/book", store.get("/files/book")); assertEquals("original text", store.get("custom-key"));
            assertEquals(original.get("/s3/bootfs/books"), store.get("/s3/bootfs/books"));
            assertNull(store.get("/nodes/old/catalog/book")); assertNotNull(store.get("/nodes/new/catalog/book")); assertEquals("123", store.get("/tombstones/deleted"));
            try (var search = new EmbeddedSearchEngine(store, seed.resolve("kernel/search"))) {
                var restored = search.get("full-text", "book"); assertEquals("作者甲", restored.getString("author"));
                assertEquals(List.of("http://new/thumb/page2", "http://new/thumb/page1"), restored.getJSONArray("resources").toList(String.class));
                assertEquals(1, search.search("full-text", "银河", List.of("manual:科幻"), 0, 10, true, null, false).total);
            }
        }
        try (var store = new RocksMetadataStore(source.resolve("kernel/metadata"))) { assertEquals(original, store.scan("")); }
        assertThrows(IOException.class, () -> KernelBackup.restore(backup, seed, "new", "http://new"));
    }
    @Test void portableSettingsAreChecksummedRestoredAndExcludeNodeSecrets() throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("tfs.root-path",source.toString())
                .withProperty("S3_ACCESS_KEY","backup-access").withProperty("S3_SECRET_KEY","backup-secret-value")
                .withProperty("SERVER_FORWARD_HEADERS_STRATEGY","native").withProperty("TFS_COPIES","3");
        try (var settings = new cc.ginpika.bootfs.settings.NodeSettings(env)) { assertEquals(3,settings.current().integer("copies")); }
        KernelBackup.export(List.of(source),backup,true);
        String manifestText=Files.readString(backup.resolve("manifest.json"));
        assertFalse(manifestText.contains("backup-secret-value")); assertFalse(manifestText.contains("backup-access"));
        assertFalse(manifestText.contains("internalProxies")); assertFalse(manifestText.contains("signingPrivate"));
        assertEquals(3,KernelBackup.verify(backup).getJSONObject("nodeSettings").getJSONObject("old").getIntValue("copies"));
        Path seed=temporary.resolve("settings-seed"); KernelBackup.restore(backup,seed,"new","http://new");
        try (var settings=new cc.ginpika.bootfs.settings.NodeSettings(new org.springframework.mock.env.MockEnvironment().withProperty("tfs.root-path",seed.toString()))) {
            assertEquals(3,settings.current().integer("copies")); assertEquals("http://new",settings.current().string("webEntrypoint"));
            assertEquals("none",settings.current().string("forwardHeadersStrategy")); assertFalse(settings.current().bool("ssoEnabled")); assertFalse(settings.current().bool("s3Enabled"));
            assertEquals("",settings.current().string("s3SecretKey"));
        }
    }
    @Test void corruptContentCannotBeRestoredAndLeavesNoDestination() throws Exception {
        KernelBackup.export(List.of(source), backup, true);
        JSONObject manifest = KernelBackup.verify(backup);
        String hash = manifest.getJSONArray("files").getJSONObject(0).getString("sha256");
        Files.writeString(backup.resolve("blobs/" + hash), "corrupted");
        Path destination = temporary.resolve("rejected");
        assertThrows(IOException.class, () -> KernelBackup.restore(backup, destination, "new", "http://new"));
        assertFalse(Files.exists(destination));
    }
    @Test void missingObjectsUnfinishedWritesAndLiveDatabasesRejectExport() throws Exception {
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source), backup, false));
        try (var store = new RocksMetadataStore(source.resolve("kernel/metadata"))) {
            assertThrows(Exception.class, () -> KernelBackup.export(List.of(source), backup, true));
            store.put("/pending-publications/old/book", "{}");
        }
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source), backup, true)); assertFalse(Files.exists(backup));
        try (var store = new RocksMetadataStore(source.resolve("kernel/metadata"))) { store.delete("/pending-publications/old/book"); }
        Files.delete(source.resolve("data/book"));
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source), backup, true)); assertFalse(Files.exists(backup));
    }
    @Test void allNodesMustHaveTheSameMetadataBoundaryAndEveryInventoryOwnerMustBeSupplied() throws Exception {
        try (var store = new RocksMetadataStore(source.resolve("kernel/metadata"))) {
            store.put("/nodes/missing/catalog/another", "{\"uuid\":\"another\",\"size\":1}");
        }
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source), backup, true));
        Path other = temporary.resolve("other"); Files.createDirectories(other.resolve("data")); Files.writeString(other.resolve("uuid"), "other");
        try (var store = new RocksMetadataStore(other.resolve("kernel/metadata"))) { store.put("different", "state"); }
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source, other), backup, true));
    }
    @Test void maliciousPathsAndSymlinksCannotEscapeTheRestoreRoot() throws Exception {
        KernelBackup.export(List.of(source), backup, true);
        JSONObject manifest = KernelBackup.verify(backup);
        manifest.getJSONArray("files").getJSONObject(0).put("path", "../escape");
        Files.writeString(backup.resolve("manifest.json"), manifest.toJSONString());
        Files.writeString(backup.resolve("manifest.sha256"), ContentHash.sha256(backup.resolve("manifest.json")));
        assertThrows(IOException.class, () -> KernelBackup.verify(backup));
        Path second = temporary.resolve("second-backup"); Files.createSymbolicLink(source.resolve("data/link"), source.resolve("data/book"));
        assertThrows(IOException.class, () -> KernelBackup.export(List.of(source), second, true)); assertFalse(Files.exists(second));
    }
    @Test void destinationParentAliasesCannotWriteABackupBackIntoTheSource() throws Exception {
        Path alias = temporary.resolve("alias-to-data"); Files.createSymbolicLink(alias,source.resolve("data"));
        assertThrows(IOException.class,() -> KernelBackup.export(List.of(source),alias.resolve("backup"),true));
        assertFalse(Files.exists(source.resolve("data/backup")));
        KernelBackup.export(List.of(source),backup,true);
        Path dangling = temporary.resolve("dangling"); Files.createSymbolicLink(dangling,temporary.resolve("absent"));
        assertThrows(IOException.class,() -> KernelBackup.restore(backup,dangling,"new","http://new"));
        assertTrue(Files.isSymbolicLink(dangling));
    }

    @Test void restoreRefusesExistingStateAndResumesAnUncertainInitialBatch() throws Exception {
        KernelBackup.export(List.of(source), backup, true);
        Path plan = KernelBackup.restore(backup, temporary.resolve("seed"), "new", "http://new");
        try (var store = new RocksMetadataStore(temporary.resolve("existing"))) {
            store.put("/files/live", "unrelated"); assertThrows(IOException.class, () -> RestorePlan.apply(store, plan));
            assertEquals("unrelated", store.get("/files/live"));
        }
        try (var store = new RocksMetadataStore(temporary.resolve("resumable"))) {
            store.put("/recovery/plan", ContentHash.sha256(plan) + ":claimed");
            RestorePlan.apply(store, plan); assertEquals("http://new/f/book", store.get("/files/book"));
        }
    }
}
