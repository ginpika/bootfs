package cc.ginpika.bootfs.clash;

import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.service.ClusterProxyService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClashSubscriptionControllerTest {
    @Test void remoteSubscriptionsKeepTheConversionParameterAndDoNotConvertLocally() throws Exception {
        LocalFileInventory context = mock(LocalFileInventory.class);
        ClashFileService files = mock(ClashFileService.class);
        ClashSubscriptionService subscriptions = mock(ClashSubscriptionService.class);
        ClusterProxyService proxy = mock(ClusterProxyService.class);
        when(proxy.getRemoteNodeUrl("remote")).thenReturn("http://owner/");
        var controller = new ClashSubscriptionController(context, files, subscriptions, proxy);
        var response = controller.subscription("remote");
        assertEquals(302, response.getStatusCodeValue());
        assertEquals("http://owner/p/remote?p=vless", response.getHeaders().getFirst("Location"));
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(400, controller.subscription("bad.id").getStatusCodeValue());
        assertEquals(404, controller.subscription("missing").getStatusCodeValue());
        verifyNoInteractions(files, subscriptions);
    }
}
