package cc.ginpika.bootfs.kernel.cluster;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerBodySecurityTest {
    @ParameterizedTest @ValueSource(strings = {"application/json", "application/problem+json", "text/plain"})
    void changingTheMimeTypeCannotBypassPeerBodyVerification(String mime) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cluster/data/search");
        request.setContentType(mime); request.setContent("{\"query\":\"changed\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-BootFS-Signature", "test");
        request.addHeader("X-BootFS-Digest", ClusterHttp.digest("{\"query\":\"original\"}".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean invoked = new AtomicBoolean();
        new PeerBodyFilter().doFilter(request, response, (rq, rs) -> invoked.set(true));
        assertEquals(401, response.getStatus()); assertFalse(invoked.get());
    }
    @ParameterizedTest @ValueSource(strings = {"application/json", "application/problem+json"})
    void validBodiesRemainReadableByTheController(String mime) throws Exception {
        byte[] body = "{\"query\":\"original\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cluster/data/search");
        request.setContentType(mime); request.setContent(body); request.addHeader("X-BootFS-Signature", "test");
        request.addHeader("X-BootFS-Digest", ClusterHttp.digest(body));
        AtomicBoolean invoked = new AtomicBoolean();
        new PeerBodyFilter().doFilter(request, new MockHttpServletResponse(), (rq, rs) -> {
            assertEquals(Boolean.TRUE, rq.getAttribute("bootfs.peerBodyVerified"));
            assertArrayEquals(body, rq.getInputStream().readAllBytes()); invoked.set(true);
        });
        assertTrue(invoked.get());
    }
}
