package cc.ginpika.bootfs.kernel.search;

import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.domain.result.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedSearchEngineTest {
    @TempDir Path temp;

    @Test void reconstructsOriginalFieldsAfterIndexLossAndMetadataRestart() throws Exception {
        Path metadataPath = temp.resolve("metadata"), indexPath = temp.resolve("search");
        try (RocksMetadataStore metadata = new RocksMetadataStore(metadataPath);
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, indexPath)) {
            JSONObject source = JSON.parseObject("{\"uuid\":\"comic-a\",\"title\":\"银河漫画\",\"author\":\"作者甲\","
                    + "\"description\":\"一部关于星际旅行的故事\",\"resources\":[\"/thumb/page1\",\"/thumb/page2\"],"
                    + "\"albumAvailable\":\"1\",\"tags\":[\"manual:科幻\"]}");
            engine.upsert("full-text", source);
            assertEquals(1, engine.search("full-text", "银河", List.of("manual:科幻"), 0, 10, true, null, false).total);
            assertEquals("/thumb/page1", engine.get("full-text", "comic-a").getString("poster"));
        }
        try (var paths = Files.walk(indexPath)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        try (RocksMetadataStore metadata = new RocksMetadataStore(metadataPath);
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, indexPath)) {
            JSONObject restored = engine.get("full-text", "comic-a");
            assertEquals("作者甲", restored.getString("author"));
            assertEquals("一部关于星际旅行的故事", restored.getString("description"));
            assertEquals(List.of("/thumb/page1", "/thumb/page2"), restored.getJSONArray("resources").toList(String.class));
            assertEquals(1, engine.search("full-text", "星际", List.of(), 0, 10, true, null, false).total);
        }
    }

    @Test void facadePreservesFieldsUpdatesTagsAndNeverResurrectsDeletedDocuments() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            MeiliSearchService facade = new MeiliSearchService(engine);
            facade.addToFullText("{\"uuid\":\"a\",\"title\":\"old\",\"author\":\"original\",\"resources\":[\"page\"],\"tags\":[\"manual:old\"]}");
            facade.updateDocumentTitle("full-text", "a", "new title");
            facade.updateDocumentTags("full-text", "a", List.of(Tag.manual("new")));
            SearchResult result = facade.searchByTags("new", List.of("manual:new"), 0, 10, "full-text");
            assertEquals(1, result.getEstimatedTotalHits());
            assertEquals("original", result.getHits().get(0).get("author"));
            assertEquals(0, facade.searchByTags("", List.of("manual:old"), 0, 10, "full-text").getEstimatedTotalHits());
            assertEquals(Map.of("manual:new", 1), facade.listAllTags("full-text"));
            facade.deleteDocumentFromIndex("full-text", "a");
            assertThrows(IllegalStateException.class, () -> facade.addToFullText("{\"uuid\":\"a\",\"title\":\"late event\"}"));
            assertThrows(IllegalArgumentException.class, () -> facade.updateDocumentTitle("full-text", "a", "late rename"));
            engine.rebuild();
            assertEquals(0, facade.searchByTags("", List.of(), 0, 10, "full-text").getEstimatedTotalHits());
            assertNull(engine.get("full-text", "a"));
        }
    }

    @Test void fullPaginationSortAndMainResourceFilteringUseEntireCollection() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            Map<String, String> sources = new TreeMap<>();
            for (int i = 0; i < 1105; i++) {
                JSONObject doc = new JSONObject();
                doc.put("uuid", String.format("id-%04d", i));
                doc.put("fileName", String.format("resource-%04d", i));
                doc.put("fileCreatedAt", i);
                doc.put("size", 1105 - i);
                sources.put(EmbeddedSearchEngine.SOURCE_PREFIX + "full-text/" + doc.getString("uuid"), doc.toJSONString());
            }
            sources.put("/documents/full-text/child", "{\"uuid\":\"child\",\"parent\":\"id-0000\",\"fileName\":\"child\"}");
            sources.put("/documents/full-text/copy", "{\"uuid\":\"copy\",\"copyOf\":\"id-0000\",\"fileName\":\"copy\"}");
            metadata.writeBatch(sources, Set.of());
            MeiliSearchService facade = new MeiliSearchService(engine);
            SearchResult tail = facade.searchResources("", List.of(), 1100, 20, false);
            assertEquals(1105, tail.getEstimatedTotalHits());
            assertEquals(5, tail.getHits().size());
            assertEquals("id-0004", tail.getHits().get(0).get("uuid"));
            assertEquals(1107, facade.searchResources("", List.of(), 0, 1, true).getEstimatedTotalHits());
            var sorted = engine.search("full-text", "", List.of(), 0, 1, true, "size", false);
            assertEquals("id-1104", sorted.hits.get(0).getString("uuid"));
            assertThrows(IllegalArgumentException.class, () -> engine.search("full-text", "", List.of(), -1, 10, false, null, false));
        }
    }

    @Test void anotherNodeProjectsSharedSourceChangesAndDeletion() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine nodeA = new EmbeddedSearchEngine(metadata, temp.resolve("node-a"));
             EmbeddedSearchEngine nodeB = new EmbeddedSearchEngine(metadata, temp.resolve("node-b"))) {
            nodeA.upsert("full-text", JSON.parseObject("{\"uuid\":\"remote\",\"title\":\"distributed comet\"}"));
            assertEquals(1, nodeB.search("full-text", "comet", List.of(), 0, 10, true, null, false).total);
            nodeA.delete("full-text", "remote");
            nodeB.refreshInBackground();
            assertEquals(0, nodeB.search("full-text", "comet", List.of(), 0, 10, true, null, false).total);
            assertEquals(metadata.revision(), nodeB.visibleRevision());
        }
    }

    @Test void revisionBarrierReportsPendingAndBecomesVisibleAfterCommit() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            MeiliSearchService facade = new MeiliSearchService(engine);
            long next = metadata.revision() + 1;
            Map<String, Object> pending = facade.searchDocuments("full-text", "", 0, 10, null, false, next, 0);
            assertEquals(false, pending.get("succeed"));
            assertEquals(true, pending.get("pending"));
            engine.upsert("full-text", JSON.parseObject("{\"uuid\":\"committed\",\"title\":\"visible\"}"));
            assertTrue(engine.awaitVisibleRevision(next, 0));
            Map<String, Object> ready = facade.searchDocuments("full-text", "", 0, 10, null, false, next, 0);
            assertEquals(true, ready.get("succeed"));
            assertThrows(IllegalArgumentException.class, () -> engine.awaitVisibleRevision(-1, 0));
            assertThrows(IllegalArgumentException.class, () -> engine.awaitVisibleRevision(next, 30001));
        }
    }

    @Test void concurrentPartialUpdatesOnTwoNodesPreserveUnrelatedFields() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine nodeA = new EmbeddedSearchEngine(metadata, temp.resolve("node-a"));
             EmbeddedSearchEngine nodeB = new EmbeddedSearchEngine(metadata, temp.resolve("node-b"))) {
            nodeA.upsert("full-text", JSON.parseObject("{\"uuid\":\"shared\",\"title\":\"before\",\"author\":\"original\"}"));
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var rename = executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        JSONObject changes = new JSONObject(); changes.put("title", "name-" + i);
                        nodeA.update("full-text", "shared", changes);
                    }
                    return true;
                });
                var retag = executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        JSONObject changes = new JSONObject(); changes.put("tags", List.of("manual:tag-" + i));
                        nodeB.update("full-text", "shared", changes);
                    }
                    return true;
                });
                start.countDown();
                assertTrue(rename.get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(retag.get(20, java.util.concurrent.TimeUnit.SECONDS));
                JSONObject finalDoc = nodeA.get("full-text", "shared");
                assertEquals("name-9", finalDoc.getString("title"));
                assertEquals(List.of("manual:tag-9"), finalDoc.getJSONArray("tags").toList(String.class));
                assertEquals("original", finalDoc.getString("author"));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void resourceAndParentTombstonesRetractProjectionAndRejectLateCallbacks() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            engine.upsert("full-text", JSON.parseObject("{\"uuid\":\"album\",\"title\":\"album\"}"));
            engine.upsert("full-text", JSON.parseObject("{\"uuid\":\"page\",\"parent\":\"album\",\"title\":\"page\"}"));
            assertEquals(2, engine.search("full-text", "", List.of(), 0, 10, false, null, false).total);
            metadata.put("/tombstones/album", "deleted");
            assertEquals(0, engine.search("full-text", "", List.of(), 0, 10, false, null, false).total);
            assertNull(engine.get("full-text", "page"));
            assertThrows(IllegalStateException.class, () -> engine.upsert("full-text",
                    JSON.parseObject("{\"uuid\":\"late-page\",\"parent\":\"album\",\"title\":\"late\"}")));
            assertNull(metadata.get("/documents/full-text/late-page"));
            assertThrows(IllegalStateException.class, () -> engine.update("full-text", "page", new JSONObject()));
        }
    }

    @Test void invalidIndexValuesCannotPoisonCanonicalSource() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            JSONObject invalid = JSON.parseObject("{\"uuid\":\"invalid\",\"size\":\"garbage\"}");
            assertThrows(RuntimeException.class, () -> engine.upsert("full-text", invalid));
            assertNull(metadata.get("/documents/full-text/invalid"));
            engine.upsert("full-text", JSON.parseObject("{\"uuid\":\"valid\",\"title\":\"healthy\"}"));
            assertEquals(1, engine.search("full-text", "healthy", List.of(), 0, 10, false, null, false).total);
        }
    }

    @Test void additiveTagMutationsAcrossNodesRetainEveryTagAndItsProvenance() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine nodeA = new EmbeddedSearchEngine(metadata, temp.resolve("node-a"));
             EmbeddedSearchEngine nodeB = new EmbeddedSearchEngine(metadata, temp.resolve("node-b"))) {
            nodeA.upsert("full-text", JSON.parseObject("{\"uuid\":\"shared\",\"title\":\"tags\"}"));
            MeiliSearchService a = new MeiliSearchService(nodeA), b = new MeiliSearchService(nodeB);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var additionsA = executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) a.mutateDocumentTags("full-text", "shared", List.of(Tag.manual("a-" + i)), List.of(), false);
                    return true;
                });
                var additionsB = executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) b.mutateDocumentTags("full-text", "shared", List.of(Tag.manual("b-" + i)), List.of(), false);
                    return true;
                });
                start.countDown();
                assertTrue(additionsA.get(30, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(additionsB.get(30, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(20, a.getDocumentTags("full-text", "shared").size());
                Tag inferred = Tag.builder().name("galaxy").namespace("content").source("ai").confidence(.82f).build();
                a.mutateDocumentTags("full-text", "shared", List.of(inferred), List.of("manual:a-0"), false);
                nodeB.rebuild();
                List<Tag> restored = b.getDocumentTags("full-text", "shared");
                assertEquals(20, restored.size());
                Tag ai = restored.stream().filter(tag -> tag.toIndexFormat().equals("content:galaxy")).findFirst().orElseThrow();
                assertEquals("ai", ai.getSource());
                assertEquals(.82f, ai.getConfidence());
                assertFalse(restored.stream().anyMatch(tag -> tag.toIndexFormat().equals("manual:a-0")));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void galleryDerivesCanonicalMetadataAndRevocationHidesItImmediately() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            MeiliSearchService catalog = new MeiliSearchService(engine);
            catalog.addToFullText("{\"uuid\":\"public\",\"title\":\"before\",\"tags\":[\"manual:old\"],\"author\":\"kept\"}");
            catalog.setResourcePublic("public", true, "/p/public");
            catalog.updateDocumentTitle("full-text", "public", "after.jpg");
            catalog.updateDocumentTags("full-text", "public", List.of(Tag.manual("new")));
            assertEquals("after.jpg", catalog.findDocument("image-host", "public").getString("fileName"));
            assertEquals(Map.of("manual:new", 1), catalog.listAllTags("image-host"));
            assertEquals(1, catalog.searchByTags("after", List.of("manual:new"), 0, 10, "image-host").getEstimatedTotalHits());
            assertEquals("/p/public", catalog.querySetu());
            // Simulate a policy change on another node before this node's background refresh.
            JSONObject canonical = catalog.findDocument("full-text", "public");
            canonical.remove("isPublicAccess");
            metadata.put("/documents/full-text/public", canonical.toJSONString());
            assertNull(catalog.findDocument("image-host", "public"));
            assertNull(catalog.querySetu());
            assertEquals(Map.of(), catalog.listAllTags("image-host"));
            assertEquals(0, catalog.searchByTags("", List.of(), 0, 10, "image-host").getEstimatedTotalHits());
            assertEquals("kept", catalog.findDocument("full-text", "public").getString("author"));
            catalog.setResourcePublic("public", true, "/p/public");
            catalog.deleteDocumentFromIndex("image-host", "public");
            assertNull(catalog.querySetu());
            assertNotNull(catalog.findDocument("full-text", "public"));
        }
    }

    @Test void galleryBatchSamplesLivePostersOnceAndRepeatsAShortPool() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            MeiliSearchService catalog = new MeiliSearchService(engine);
            catalog.addToFullText("{\"uuid\":\"a\",\"title\":\"a.jpg\"}");
            catalog.addToFullText("{\"uuid\":\"b\",\"title\":\"b.jpg\"}");
            catalog.addToFullText("{\"uuid\":\"secret\",\"title\":\"secret.jpg\"}");
            catalog.setResourcePublic("a", true, "/p/a");
            catalog.setResourcePublic("b", true, "/p/b");
            List<String> batch = catalog.querySetu(5);
            assertEquals(5, batch.size());
            assertEquals(Set.of("/p/a", "/p/b"), new HashSet<>(batch));
            catalog.setResourcePublic("a", false, null);
            assertEquals(List.of("/p/b", "/p/b", "/p/b"), catalog.querySetu(3));
            catalog.deleteDocumentFromIndex("image-host", "b");
            assertTrue(catalog.querySetu(4).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> catalog.querySetu(0));
            assertThrows(IllegalArgumentException.class, () -> catalog.querySetu(481));
        }
    }

    @Test void legacyGalleryOnlyFieldsAreAdoptedWithoutOverwritingCanonicalOrRevivingRevokedPolicy() throws Exception {
        Path metadataPath = temp.resolve("metadata"), indexPath = temp.resolve("search");
        try (RocksMetadataStore metadata = new RocksMetadataStore(metadataPath)) {
            metadata.put("/documents/image-host/legacy", "{\"uuid\":\"legacy\",\"author\":\"legacy author\",\"poster\":\"/p/legacy\",\"description\":\"saved\",\"tags\":[{\"name\":\"legacy\",\"namespace\":\"content\",\"source\":\"ai\",\"confidence\":0.73}]}");
            metadata.put("/documents/full-text/conflict", "{\"uuid\":\"conflict\",\"author\":\"canonical author\",\"tags\":[\"manual:canonical\"]}");
            metadata.put("/documents/image-host/conflict", "{\"uuid\":\"conflict\",\"author\":\"old gallery author\",\"poster\":\"/p/conflict\",\"tags\":[\"manual:gallery\"]}");
            metadata.put("/documents/image-host/private", "{\"uuid\":\"private\",\"poster\":\"/p/private\",\"isPublicAccess\":\"0\"}");
            try (EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, indexPath)) {
                assertEquals("saved", engine.get("full-text", "legacy").getString("description"));
                Tag restoredTag = new MeiliSearchService(engine).getDocumentTags("full-text", "legacy").get(0);
                assertEquals("ai", restoredTag.getSource());
                assertEquals(.73f, restoredTag.getConfidence());
                JSONObject conflict = engine.get("full-text", "conflict");
                assertEquals("canonical author", conflict.getString("author"));
                assertEquals("old gallery author", conflict.getJSONObject("legacyImageHost").getString("author"));
                assertEquals(Set.of("manual:canonical", "manual:gallery"), new HashSet<>(conflict.getJSONArray("tags").toList(String.class)));
                assertNull(engine.get("image-host", "private"));
                new MeiliSearchService(engine).setResourcePublic("legacy", false, null);
                assertNull(engine.get("image-host", "legacy"));
            }
        }
        try (RocksMetadataStore metadata = new RocksMetadataStore(metadataPath);
             EmbeddedSearchEngine engine = new EmbeddedSearchEngine(metadata, indexPath)) {
            assertNull(engine.get("image-host", "legacy"));
            assertEquals("saved", engine.get("full-text", "legacy").getString("description"));
            assertNotNull(metadata.get("/documents/image-host/legacy")); // untouched archival source
        }
    }
}
