package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.ResourceVisibility;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlbumControllerTest {
    private final Context context = mock(Context.class);
    private final MeiliSearchService search = mock(MeiliSearchService.class);
    private final ClusterProxyService proxy = mock(ClusterProxyService.class);
    private final ManageController controller = new ManageController(mock(FileService.class),
            mock(FileTransferService.class), mock(ReverseProxyService.class), search, context,
            mock(TfsConfig.class), mock(ThumbnailService.class), proxy);

    @Test void localAlbumRewritesBootfsUrlsWithoutChangingStoredMetadataOrExternalImages() {
        List<String> resources = List.of("http://owner.internal/thumb/page-1", "/thumb/page-2",
                "https://old-owner/base/p/page-3?version=1", "https://images.example/photo.jpg");
        Map<String, Object> stored = Map.of("uuid", "album", "resources", resources,
                "poster", resources.get(0), "thumbUrl", resources.get(0), "title", "kept");
        when(context.query("album")).thenReturn(new FileObject());
        when(search.getDocument("full-text", "album")).thenReturn(Map.of("succeed", true, "data", stored));

        var response = controller.getAlbumDocument("album", new MockHttpServletRequest());
        assertEquals(200, response.getStatusCodeValue());
        Map<?, ?> album = (Map<?, ?>) response.getBody();
        assertEquals(List.of("/thumb/page-1", "/thumb/page-2", "/p/page-3?version=1",
                "https://images.example/photo.jpg"), album.get("resources"));
        assertEquals("/thumb/page-1", album.get("poster"));
        assertEquals("/thumb/page-1", album.get("thumbUrl"));
        assertEquals("kept", album.get("title"));
        assertSame(resources, stored.get("resources"));
        assertEquals("http://owner.internal/thumb/page-1", stored.get("poster"));
        verifyNoInteractions(proxy);
    }

    @Test void remoteThumbnailReturnsImageBytesInsteadOfRedirectingTheBrowser() {
        ReflectionTestUtils.setField(controller, "visibility", mock(ResourceVisibility.class));
        var request = new MockHttpServletRequest("GET", "/thumb/page");
        byte[] bytes = {1, 2, 3};
        ResponseEntity<byte[]> upstream = ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes);
        when(proxy.getRemoteNodeUrl("page")).thenReturn("http://owner.internal");
        when(proxy.proxyRequest("http://owner.internal", request, null)).thenReturn(upstream);

        var response = controller.thumbnail("page", request);
        assertSame(upstream, response);
        assertArrayEquals(bytes, (byte[]) response.getBody());
        assertNull(response.getHeaders().getLocation());
    }

    @Test void missingForwardedThumbnailStopsWithoutForwardingAgain() {
        ReflectionTestUtils.setField(controller, "visibility", mock(ResourceVisibility.class));
        var request = new MockHttpServletRequest("GET", "/thumb/missing");
        request.addHeader("X-BootFS-Forwarded", "1");

        assertEquals(404, controller.thumbnail("missing", request).getStatusCodeValue());
        verifyNoInteractions(proxy);
    }
}
