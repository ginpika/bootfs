package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.s3.S3CryptoUtils;
import cc.ginpika.bootfs.s3.S3KeyIndex;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cyberduck moves an S3 object with CopyObject followed by DeleteObject. */
@SpringBootTest(properties={"sso.enabled=false","s3.enabled=true","s3.access-key=test","s3.secret-key=test-secret","tfs.ffmpeg-url=/nonexistent-ffmpeg"})
@AutoConfigureMockMvc
@DirtiesContext
class S3CopyObjectIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path", ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired Context context;
    @Autowired S3KeyIndex keys;
    @Autowired EtcdService routes;

    private String encoded(String key) { return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/"); }
    private String path(String key) { return "/s3/bootfs/" + encoded(key); }

    private MockHttpServletRequestBuilder signed(String method, String path, byte[] body, String type, Map<String,String> extra) {
        URI uri = URI.create(path);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String date = stamp.substring(0, 8), hash = S3CryptoUtils.sha256Hex(body), scope = date + "/us-east-1/s3/aws4_request";
        TreeMap<String,String> headers = new TreeMap<>(extra);
        headers.put("host", "localhost"); headers.put("x-amz-content-sha256", hash); headers.put("x-amz-date", stamp);
        String names = String.join(";", headers.keySet());
        String canonical = method + "\n" + uri.getRawPath() + "\n" + (uri.getRawQuery() == null ? "" : uri.getRawQuery()) + "\n"
                + headers.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue() + "\n").collect(Collectors.joining())
                + "\n" + names + "\n" + hash;
        byte[] signingKey = "AWS4test-secret".getBytes(StandardCharsets.UTF_8);
        for (String part : new String[]{date, "us-east-1", "s3", "aws4_request"}) signingKey = S3CryptoUtils.hmacSha256(signingKey, part);
        String signature = S3CryptoUtils.hmacSha256Hex(signingKey, "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + S3CryptoUtils.sha256Hex(canonical));
        var request = request(HttpMethod.valueOf(method), uri).content(body).contentType(type);
        headers.forEach(request::header);
        return request.header("Authorization", "AWS4-HMAC-SHA256 Credential=test/" + scope + ", SignedHeaders=" + names + ", Signature=" + signature);
    }
    private MockHttpServletRequestBuilder signed(String method, String path, String body) {
        return signed(method, path, body.getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
    }
    private MockHttpServletRequestBuilder copy(String target, String source, Map<String,String> extra) {
        TreeMap<String,String> headers = new TreeMap<>(extra);
        headers.put("x-amz-copy-source", "/bootfs/" + encoded(source));
        return signed("PUT", path(target), new byte[0], "application/octet-stream", headers);
    }

    @Test void copyThenDeleteMovesExactBytesAndReturnsTheXmlCyberduckParses() throws Exception {
        String source = "待归档 +%?#.txt", target = "资料/子目录/待归档 +%?#.txt", body = "profile contents\n中文\u0000end";
        mvc.perform(signed("PUT", path(source), body)).andExpect(status().isOk());
        String sourceId = keys.getIndex("bootfs", source).getUuid();
        String etag = "\"" + keys.getIndex("bootfs", source).getEtag() + "\"";
        mvc.perform(copy(target, source, Map.of())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(xpath("/*[local-name()='CopyObjectResult']/*[local-name()='ETag']").string(etag))
                .andExpect(xpath("/*[local-name()='CopyObjectResult']/*[local-name()='LastModified']").exists());
        String targetId = keys.getIndex("bootfs", target).getUuid();
        assertNotEquals(sourceId, targetId);
        mvc.perform(signed("GET", path(source), "")).andExpect(status().isOk()).andExpect(content().bytes(body.getBytes(StandardCharsets.UTF_8)));
        mvc.perform(signed("HEAD", path(target), "")).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", body.getBytes(StandardCharsets.UTF_8).length))
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
        mvc.perform(signed("DELETE", path(source), "")).andExpect(status().isNoContent());
        assertNull(context.query(sourceId)); assertNotNull(context.query(targetId));
        mvc.perform(signed("GET", path(target), "")).andExpect(status().isOk()).andExpect(content().bytes(body.getBytes(StandardCharsets.UTF_8)));
        mvc.perform(signed("GET", path(source), "")).andExpect(status().isNotFound());
    }

    @Test void missingOrInvalidSourcesCannotOverwriteAnExistingDestination() throws Exception {
        String target = "protected-copy.txt";
        mvc.perform(signed("PUT", path(target), "original")).andExpect(status().isOk());
        String id = keys.getIndex("bootfs", target).getUuid();
        mvc.perform(copy(target, "missing-source", Map.of())).andExpect(status().isNotFound()).andExpect(xpath("/Error/Code").string("NoSuchKey"));
        mvc.perform(signed("PUT", path(target), new byte[0], "application/octet-stream", Map.of("x-amz-copy-source", "/missing/file")))
                .andExpect(status().isNotFound()).andExpect(xpath("/Error/Code").string("NoSuchBucket"));
        mvc.perform(signed("PUT", path(target), new byte[0], "application/octet-stream", Map.of("x-amz-copy-source", "bad-source")))
                .andExpect(status().isBadRequest()).andExpect(xpath("/Error/Code").string("InvalidRequest"));
        assertEquals(id, keys.getIndex("bootfs", target).getUuid());
        mvc.perform(signed("GET", path(target), "")).andExpect(content().string("original"));
    }

    @Test void copyHonorsReplacedContentTypeAndSourceEtag() throws Exception {
        String source = "metadata-source.txt", target = "metadata-target.txt";
        mvc.perform(signed("PUT", path(source), "content")).andExpect(status().isOk());
        mvc.perform(copy(target, source, Map.of("x-amz-copy-source-if-match", "\"wrong\"")))
                .andExpect(status().isPreconditionFailed()).andExpect(xpath("/Error/Code").string("PreconditionFailed"));
        assertNull(keys.getIndex("bootfs", target));
        mvc.perform(copy(target, source, Map.of("x-amz-metadata-directive", "REPLACE"))).andExpect(status().isOk());
        mvc.perform(signed("GET", path(target), "")).andExpect(status().isOk()).andExpect(content().string("content"))
                .andExpect(content().contentTypeCompatibleWith("application/octet-stream"));
    }

    @Test void anUnsignedCopySourceCannotBeAddedToASignedPut() throws Exception {
        mvc.perform(signed("PUT", path("unsigned-copy.txt"), "").header("x-amz-copy-source", "/bootfs/anything"))
                .andExpect(status().isForbidden()).andExpect(xpath("/Error/Code").string("SignatureDoesNotMatch"));
        assertNull(keys.getIndex("bootfs", "unsigned-copy.txt"));
    }

    @Test void copyReadsARemoteReplicaAndDoesNotPublishTruncatedSourceBytes() throws Exception {
        String source = "remote-copy-source.txt", target = "remote-copy-target.txt";
        byte[] body = "remote source bytes".getBytes(StandardCharsets.UTF_8);
        mvc.perform(signed("PUT", path(source), body, "text/plain", Map.of())).andExpect(status().isOk());
        var sourceMeta = keys.getIndex("bootfs", source);
        Files.delete(Path.of(context.query(sourceMeta.getUuid()).getPath()));
        var served = new java.util.concurrent.atomic.AtomicReference<>("truncated".getBytes(StandardCharsets.UTF_8));
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source", exchange -> {
            byte[] bytes = served.get();
            if (!("\"" + sourceMeta.getEtag() + "\"").equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
                exchange.sendResponseHeaders(412, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            routes.put("/files/" + sourceMeta.getUuid(), "http://127.0.0.1:" + server.getAddress().getPort() + "/source");
            mvc.perform(copy(target, source, Map.of())).andExpect(status().isServiceUnavailable())
                    .andExpect(xpath("/Error/Code").string("ServiceUnavailable"));
            assertNull(keys.getIndex("bootfs", target));
            served.set(body);
            mvc.perform(copy(target, source, Map.of())).andExpect(status().isOk());
            mvc.perform(signed("GET", path(target), "")).andExpect(status().isOk()).andExpect(content().bytes(body));
        } finally {
            server.stop(0);
        }
    }
}
