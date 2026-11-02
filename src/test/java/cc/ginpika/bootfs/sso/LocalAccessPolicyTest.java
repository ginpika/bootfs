package cc.ginpika.bootfs.sso;

import cc.ginpika.bootfs.dto.SsoUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class LocalAccessPolicyTest {
    private final SsoConfig config = new SsoConfig();
    private final LocalAccessPolicy policy = new LocalAccessPolicy(config);

    private MockHttpServletRequest request() {
        config.setEnabled(true);
        config.setLocalBypassEnabled(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        request.setLocalPort(8181);
        request.addHeader("Host", "localhost:8181");
        return request;
    }

    private boolean authenticate(MockHttpServletRequest request) {
        LocalAccessPolicy.capture(request, List.of("Client-IP"));
        return policy.authenticate(request, new MockHttpServletResponse());
    }

    @Test void requiresOptInAndOriginalConnectionSnapshot() {
        MockHttpServletRequest request = request();
        assertFalse(policy.authenticate(request, new MockHttpServletResponse()));
        config.setLocalBypassEnabled(false);
        assertFalse(authenticate(request));
        config.setLocalBypassEnabled(true);
        config.setEnabled(false);
        assertFalse(authenticate(request));
    }

    @Test void directAccessCreatesOnlyARequestScopedIdentity() {
        MockHttpServletRequest request = request();
        LocalAccessPolicy.capture(request, List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(policy.authenticate(request, response));
        assertEquals("本地访问", ((SsoUser) request.getAttribute("ssoUser")).getNickname());
        assertEquals(true, request.getAttribute("ssoLocalAccess"));
        assertNull(request.getAttribute("ssoToken"));
        assertNull(request.getSession(false));
        assertEquals(0, response.getCookies().length);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"172.18.0.1", "192.168.1.2", "203.0.113.42", "127.0.0.2", "::ffff:172.18.0.1"})
    void gatewaysAndRemoteClientsNeverQualifyEvenAfterAddressRewriting(String address) {
        MockHttpServletRequest request = request();
        request.setRemoteAddr(address);
        LocalAccessPolicy.capture(request, List.of());
        request.setRemoteAddr("127.0.0.1");
        assertFalse(policy.authenticate(request, new MockHttpServletResponse()));
    }

    @Test void destinationMustAlsoBeLoopback() {
        MockHttpServletRequest request = request();
        request.setLocalAddr("172.18.0.2");
        assertFalse(authenticate(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
            "X-Forwarded-Port", "X-Forwarded-Custom", "X-Real-IP", "Via", "CF-Connecting-IP", "Client-IP"})
    void forwardingMarkersCannotBeErasedByDownstreamProcessing(String header) {
        MockHttpServletRequest request = request();
        request.addHeader(header, "127.0.0.1");
        LocalAccessPolicy.capture(request, List.of("Client-IP"));
        request.removeHeader(header);
        assertFalse(policy.authenticate(request, new MockHttpServletResponse()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example:8181", "localhost.evil.example:8181", "localhost:9999",
            "localhost", "localhost:8181/path", "user@localhost:8181", "localhost:8181#fragment"})
    void hostMustBeAnExactLocalAuthorityOnTheListeningPort(String host) {
        MockHttpServletRequest request = request();
        request.removeHeader("Host");
        request.addHeader("Host", host);
        assertFalse(authenticate(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8181", "LOCALHOST:8181", "127.0.0.1:8181", "[::1]:8181"})
    void supportsLocalNamesAndIpv6(String host) {
        MockHttpServletRequest request = request();
        request.removeHeader("Host");
        request.addHeader("Host", host);
        if (host.startsWith("[")) {
            request.setLocalAddr("::1");
            request.setRemoteAddr("0:0:0:0:0:0:0:1");
        }
        assertTrue(authenticate(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "null", "http://localhost:9999", "http://localhost:8181.evil.example"})
    void crossOriginReadsAndWritesAreDenied(String origin) {
        MockHttpServletRequest request = request();
        request.addHeader("Origin", origin);
        assertThrows(ResponseStatusException.class, () -> authenticate(request));
        request.setMethod("POST");
        request.addHeader("X-BootFS-Local-Request", "1");
        assertThrows(ResponseStatusException.class, () -> authenticate(request));
    }

    @Test void writesNeedSameOriginEvidenceOrAnExplicitNonBrowserHeader() {
        MockHttpServletRequest request = request();
        request.setMethod("POST");
        assertThrows(ResponseStatusException.class, () -> authenticate(request));
        request.addHeader("Origin", "http://localhost:8181");
        assertTrue(authenticate(request));
        request.removeHeader("Origin");
        request.addHeader("Referer", "http://localhost:8181/page?query=1");
        assertTrue(authenticate(request));
        request.removeHeader("Referer");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        assertTrue(authenticate(request));
        request.removeHeader("Sec-Fetch-Site");
        request.addHeader("X-BootFS-Local-Request", "1");
        assertTrue(authenticate(request));
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThrows(ResponseStatusException.class, () -> authenticate(request));
    }
}
