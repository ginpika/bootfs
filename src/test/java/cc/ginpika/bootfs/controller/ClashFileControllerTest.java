package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.service.ClashFileService;
import cc.ginpika.bootfs.service.ClashLatencyService;
import cc.ginpika.bootfs.service.ClusterProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClashFileControllerTest {
    private final Context context = mock(Context.class);
    private final ClashFileService files = mock(ClashFileService.class);
    private final ClashLatencyService latency = mock(ClashLatencyService.class);
    private final ClusterProxyService proxy = mock(ClusterProxyService.class);
    private final ClashFileController controller = new ClashFileController(context, files, latency, proxy);

    @Test void detailsAndLatencyRunLocallyOnlyForTheOwningResource() throws Exception {
        when(context.query("resource")).thenReturn(new FileObject());
        var details = new ClashFileService.ClashDetails("resource", "version", List.of(), List.of(), "tcp", "server");
        when(files.details("resource")).thenReturn(details);
        var response = controller.details("resource", new MockHttpServletRequest("GET", "/api/file/resource/clash"));
        assertEquals(200, response.getStatusCodeValue()); assertSame(details, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var batch = new ClashLatencyService.LatencyBatch("resource", "version", 123, List.of(), true, 0, 0, 0, "tcp", "server");
        when(latency.measure(details, 0, 16)).thenReturn(batch);
        var measured = controller.latency("resource", 0, 16, new MockHttpServletRequest("POST", "/api/file/resource/clash/latency"));
        assertSame(batch, measured.getBody()); verifyNoInteractions(proxy);
    }

    @Test void remoteCallsPreserveTheirRequestAndForwardedCallsNeverLoop() throws Exception {
        when(proxy.getRemoteNodeUrl("remote")).thenReturn("http://owner");
        when(proxy.proxyRequest(eq("http://owner"), any(), isNull())).thenReturn(ResponseEntity.ok(new byte[0]));
        var request = new MockHttpServletRequest("POST", "/api/file/remote/clash/latency");
        request.setQueryString("offset=16&limit=16");
        assertEquals(200, controller.latency("remote", 16, 16, request).getStatusCodeValue());
        verify(proxy).proxyRequest("http://owner", request, null); verifyNoInteractions(files, latency);
        request.addHeader("X-BootFS-Forwarded", "1");
        assertEquals(404, controller.latency("remote", 16, 16, request).getStatusCodeValue());
        assertEquals(400, controller.details("../invalid", request).getStatusCodeValue());
        verify(proxy, times(1)).getRemoteNodeUrl("remote");
    }
}
