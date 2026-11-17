package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.FederationService;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileListQueryTest {
    private final LocalFileInventory inventory = mock(LocalFileInventory.class);
    private final ResourceSearchService search = mock(ResourceSearchService.class);
    private final FederationService federation = mock(FederationService.class);
    private final FileListQuery query = new FileListQuery(inventory, search, federation);

    @Test void localListingKeepsTheWireFieldsWithoutChangingTheCachedResource() throws Exception {
        FileObject file = FileObject.builder().uuid("id").fileName("local.jpg").path("/data/id")
                .contentObjectId("id").isPublicAccess("1").build();
        when(inventory.searchCached(0, 10, "local")).thenReturn(new LocalFileInventory.CachedFilePage(1, List.of(file)));
        when(inventory.buildUrl("id")).thenReturn("/f/id");
        when(inventory.buildThumbUrl("id")).thenReturn("/thumb/id");

        ObjectMapper mapper = new ObjectMapper();
        var page = mapper.readTree(mapper.writeValueAsBytes(query.page(0, 10, "meta:LOCAL local", "")));
        var row = page.get("rows").get(0);
        assertEquals(1, page.get("total").asInt());
        assertEquals("/f/id", row.get("url").asText());
        assertEquals("/thumb/id", row.get("thumbUrl").asText());
        assertEquals("/data/id", row.get("path").asText());
        assertEquals("id", row.get("contentObjectId").asText());
        assertEquals("1", row.get("isPublicAccess").asText());
        assertNull(file.getUrl());
        assertNull(file.getThumbUrl());
        verifyNoInteractions(search, federation);
    }

    @Test void federatedListingPreservesThePeerOriginAndPartialResults() {
        when(federation.search("photo", List.of("manual:blue"), 2, 10, true)).thenReturn(JSONObject.parseObject("""
                {"total":9,"partial":true,"unavailableNodes":["offline"],
                 "hits":[{"uuid":"photo","title":"old document title","originEndpoint":"https://peer",
                          "poster":"/thumb/photo","tags":["manual:blue"]},{"title":"missing ID"}]}
                """));
        FileListResponse page = query.page(2, 10, "meta:all photo", " manual:blue, ");
        assertTrue(page.partial());
        assertEquals(9, page.total());
        assertEquals(List.of("offline"), page.unavailableNodes());
        assertEquals(1, page.rows().size());
        var row = page.rows().get(0);
        assertEquals("https://peer/f/photo", row.url());
        assertEquals("/thumb/photo", row.thumbUrl());
        assertEquals("old document title", row.fileName());
        assertEquals("manual:blue", row.tags().get(0).toIndexFormat());
        verifyNoInteractions(search);
    }
}
