package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ClusterProxyServiceTest {
    private static final byte[] JSON_BODY = ("{\"uuid\":\"remote\",\"fileName\":\"clash.yaml\",\"version\":\"v1\","
            + "\"content\":\"proxies: []\\n中文配置\"}").getBytes(StandardCharsets.UTF_8);
    // Valid compressed representations of JSON_BODY; no native codec is needed by these tests.
    private static final byte[] BROTLI = Base64.getDecoder().decode(
            "G10AQIzUYs0Z7oxQ6RBss1Tl6ILVVJ8gDyi8KMrGWLHoOOUWMFcgYWAHv+P1Jei05gMS2R/nlEjEFDwY9ZdQ/c7y9a0C6pCrGIB7rvGUtLUwzvaMNf/wAw==");
    private static final byte[] ZSTD = Base64.getDecoder().decode(
            "KLUv/SBe8QIAeyJ1dWlkIjoicmVtb3RlIiwiZmlsZU5hbWUiOiJjbGFzaC55YW1sIiwidmVyc2lvbiI6InYxIiwiY29udGVudCI6InByb3hpZXM6IFtdXG7kuK3mlofphY3nva4ifQ==");

    private HttpServer owner;
    private String endpoint;
    private String forcedCoding;
    private int responseStatus = 200;
    private final AtomicReference<String> acceptedEncodings = new AtomicReference<>();
    private final AtomicReference<String> receivedTarget = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final ClusterProxyService proxy = new ClusterProxyService(
            mock(MeiliSearchService.class), mock(EtcdService.class), mock(Context.class));

    @BeforeEach void startOwner() throws Exception {
        owner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        owner.createContext("/", exchange -> {
            try (exchange) {
                String accepted = exchange.getRequestHeaders().getFirst("Accept-Encoding");
                acceptedEncodings.set(accepted);
                receivedTarget.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                receivedBody.set(exchange.getRequestBody().readAllBytes());
                String coding = forcedCoding;
                if (coding == null) {
                    // A reverse proxy can prefer zstd or Brotli when the browser advertises them.
                    String lower = accepted == null ? "" : accepted.toLowerCase(Locale.ROOT);
                    coding = lower.contains("zstd") ? "zstd" : lower.contains("br") ? "br" : "gzip";
                }
                byte[] body;
                if ("br".equals(coding)) body = BROTLI;
                else if ("zstd".equals(coding)) body = ZSTD;
                else {
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (var out = "deflate".equals(coding)
                            ? new DeflaterOutputStream(compressed) : new GZIPOutputStream(compressed)) {
                        out.write(JSON_BODY);
                    }
                    body = compressed.toByteArray();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().set("Content-Encoding", coding);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-BootFS-Revision", "42");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        owner.start();
        endpoint = "http://127.0.0.1:" + owner.getAddress().getPort();
    }

    @AfterEach void closeOwner() { if (owner != null) owner.stop(0); }

    private MockHttpServletRequest browserRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Accept-Encoding", "gzip, deflate, br, zstd");
        return request;
    }

    @ParameterizedTest @ValueSource(strings = {"/api/file/remote/details", "/api/file/remote/text",
            "/api/file/remote/clash", "/api/album/remote"})
    void browserCompressionDoesNotTurnRemoteJsonIntoUndecodedBytes(String route) {
        var response = proxy.proxyRequest(endpoint, browserRequest("GET", route), null);
        assertEquals(200, response.getStatusCodeValue());
        assertArrayEquals(JSON_BODY, response.getBody());
        assertEquals("clash.yaml", JSON.parseObject(response.getBody()).getString("fileName"));
        assertNull(response.getHeaders().getFirst("Content-Encoding"));
        assertFalse(acceptedEncodings.get().contains("br"));
        assertFalse(acceptedEncodings.get().contains("zstd"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("42", response.getHeaders().getFirst("X-BootFS-Revision"));
    }

    @Test void savingPreservesUtf8RequestBodyQueryAndCompressedConflictResponse() {
        responseStatus = 409;
        forcedCoding = "deflate";
        var request = browserRequest("PUT", "/api/file/remote/text");
        request.setQueryString("encoding=GBK");
        String update = "{\"content\":\"中文配置\\r\\n\",\"version\":\"v1\",\"encoding\":\"GBK\"}";
        var response = proxy.proxyRequest(endpoint, request, update);
        assertEquals(409, response.getStatusCodeValue());
        assertEquals("PUT /api/file/remote/text?encoding=GBK", receivedTarget.get());
        assertArrayEquals(update.getBytes(StandardCharsets.UTF_8), receivedBody.get());
        assertArrayEquals(JSON_BODY, response.getBody());
        assertNull(response.getHeaders().getFirst("Content-Encoding"));
    }

    @ParameterizedTest @ValueSource(strings = {"br", "zstd"})
    void anUnexpectedUndecodedResponseRetainsItsEncodingHeader(String coding) {
        forcedCoding = coding;
        var response = proxy.proxyRequest(endpoint, browserRequest("GET", "/api/file/remote/details"), null);
        assertEquals(200, response.getStatusCodeValue());
        assertArrayEquals("br".equals(coding) ? BROTLI : ZSTD, response.getBody());
        assertEquals(coding, response.getHeaders().getFirst("Content-Encoding"));
    }
}
