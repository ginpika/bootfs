package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.ai.AiMetadataService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
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
            Context context = mock(Context.class);
            ContextIO localInventory = mock(ContextIO.class);
            cc.ginpika.bootfs.domain.dto.FileObject local=new cc.ginpika.bootfs.domain.dto.FileObject("/local/resource","remote","before",0L);
            when(context.query("remote")).thenReturn(local);
            ClusterProxyService proxy = mock(ClusterProxyService.class);
            MeiliSearchService catalog = new MeiliSearchService(search);
            catalog.addToFullText("{\"uuid\":\"remote\",\"title\":\"before\",\"author\":\"kept\",\"contextUuid\":\"offline-owner\"}");
            WebUIApiController controller = new WebUIApiController(context, mock(FileService.class), localInventory,
                    mock(TfsConfig.class), mock(EtcdService.class), catalog, mock(AiMetadataService.class),
                    mock(ReverseProxyService.class), proxy);
            assertEquals(200, controller.rename("remote", Map.of("fileName", "after.jpg"), null).getStatusCodeValue());
            assertEquals(200, controller.addTags("remote", List.of(Tag.manual("first"))).getStatusCodeValue());
            assertEquals(200, controller.addTags("remote", List.of(Tag.manual("second"))).getStatusCodeValue());
            assertEquals(200, controller.removeTags("remote", List.of("manual:first")).getStatusCodeValue());
            assertEquals("after.jpg", catalog.findDocument("full-text", "remote").getString("fileName"));
            assertEquals("kept", catalog.findDocument("full-text", "remote").getString("author"));
            assertEquals(List.of("manual:second"), ((List<Tag>)controller.getTags("remote").getBody()).stream().map(Tag::toIndexFormat).toList());
            when(context.buildProxyUrl("remote")).thenReturn("/p/remote");
            assertEquals(200, controller.setPublicAccess("remote", true).getStatusCodeValue());
            assertEquals("/p/remote", catalog.querySetu());
            assertEquals(200, controller.setPublicAccess("remote", false).getStatusCodeValue());
            assertNull(catalog.querySetu());
            verify(localInventory,atLeastOnce()).update(eq("remote"),same(local));
            verifyNoInteractions(proxy);
            assertEquals(404, controller.getTags("missing").getStatusCodeValue());
        }
    }
}
