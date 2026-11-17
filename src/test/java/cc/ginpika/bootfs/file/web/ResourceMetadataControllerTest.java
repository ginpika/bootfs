package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.file.ResourceWriteLock;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.MetadataException;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import cc.ginpika.bootfs.service.ClusterProxyService;
import cc.ginpika.bootfs.file.FileMetadataService;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.ResourceSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceMetadataControllerTest {
    @TempDir Path temp;

    @Test void localResourceEditsUpdateItsInventoryAndPreserveOriginalMetadata() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine search = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            LocalFileInventory context = mock(LocalFileInventory.class);
            FileCatalogRepository localInventory = mock(FileCatalogRepository.class);
            cc.ginpika.bootfs.domain.dto.FileObject local=new cc.ginpika.bootfs.domain.dto.FileObject("/local/resource","remote","before",0L);
            when(context.query("remote")).thenReturn(local);
            ClusterProxyService proxy = mock(ClusterProxyService.class);
            ResourceSearchService catalog = new ResourceSearchService(search);
            catalog.addToFullText("{\"uuid\":\"remote\",\"title\":\"before\",\"author\":\"kept\",\"contextUuid\":\"offline-owner\"}");
            FileMetadataService edits = new FileMetadataService(context, localInventory, mock(MetadataRegistry.class),
                    catalog, new ResourceWriteLock());
            FileMetadataController controller = new FileMetadataController(edits, new ResourceRequestForwarder(context, proxy));
            assertEquals(200, controller.rename("remote", Map.of("fileName", "after.jpg"), null).getStatusCodeValue());
            assertEquals(200, controller.addTags("remote", List.of(Tag.manual("first")), null).getStatusCodeValue());
            assertEquals(200, controller.addTags("remote", List.of(Tag.manual("second")), null).getStatusCodeValue());
            assertEquals(200, controller.removeTags("remote", List.of("manual:first"), null).getStatusCodeValue());
            assertEquals("after.jpg", catalog.findDocument("full-text", "remote").getString("fileName"));
            assertEquals("kept", catalog.findDocument("full-text", "remote").getString("author"));
            assertEquals(List.of("manual:second"), ((List<Tag>)controller.getTags("remote", null).getBody()).stream().map(Tag::toIndexFormat).toList());
            when(context.buildProxyUrl("remote")).thenReturn("/p/remote");
            assertEquals(200, controller.setPublicAccess("remote", true, null).getStatusCodeValue());
            assertEquals("/p/remote", catalog.querySetu());
            assertEquals(List.of("/p/remote", "/p/remote"), catalog.querySetu(2));
            assertEquals(200, controller.setPublicAccess("remote", false, null).getStatusCodeValue());
            assertNull(catalog.querySetu());
            assertTrue(catalog.querySetu(2).isEmpty());
            verify(localInventory, atLeastOnce()).update(eq("remote"),
                    argThat(file -> "after.jpg".equals(file.getFileName())));
            verifyNoInteractions(proxy);
            assertEquals(404, controller.getTags("missing", null).getStatusCodeValue());
        }
    }

    @Test void failedCatalogWriteKeepsTheOldCachedViewUntilAWriteSucceeds() throws Exception {
        try (RocksMetadataStore metadata = new RocksMetadataStore(temp.resolve("metadata"));
             EmbeddedSearchEngine search = new EmbeddedSearchEngine(metadata, temp.resolve("search"))) {
            FileCatalogRepository repository = mock(FileCatalogRepository.class);
            TfsConfig config = new TfsConfig();
            config.setRootPath(temp.resolve("node").toString());
            LocalFileInventory inventory = new LocalFileInventory(config, repository, new NodeIdentity(config));
            FileObject original = new FileObject("/local/resource", "id", "before", 0L);
            inventory.cacheCommitted("id", original);
            doThrow(new MetadataException("catalog unavailable")).doNothing().when(repository).update(eq("id"), any());
            ResourceSearchService catalog = new ResourceSearchService(search);
            catalog.addToFullText("{\"uuid\":\"id\",\"fileName\":\"before\"}");
            FileMetadataService edits = new FileMetadataService(inventory, repository, mock(MetadataRegistry.class),
                    catalog, new ResourceWriteLock());

            assertTrue(edits.rename("id", "after"));
            assertEquals("after", catalog.findDocument("full-text", "id").getString("fileName"));
            assertSame(original, inventory.cached("id"));
            assertEquals("before", inventory.cached("id").getFileName());

            assertTrue(edits.addTags("id", List.of(Tag.manual("tag"))));
            assertEquals("after", inventory.cached("id").getFileName());
            assertEquals(List.of(Tag.manual("tag")), inventory.cached("id").getTags());
        }
    }
}
