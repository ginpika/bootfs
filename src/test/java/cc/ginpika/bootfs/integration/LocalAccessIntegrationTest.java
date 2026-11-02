package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.BootFS;
import com.alibaba.fastjson2.JSON;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class LocalAccessIntegrationTest {
    @TempDir Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private ConfigurableApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--tfs.root-path=" + root.resolve(java.util.UUID.randomUUID().toString()),
                "--tfs.unique-id=local-test", "--tfs.web-entrypoint=http://127.0.0.1:8181",
                "--sso.enabled=true", "--sso.local-bypass.enabled=false", "--bootfs.security.public-sharing.enabled=false", "--sso.server-url=http://127.0.0.1:1", "--s3.enabled=true",
                "--s3.access-key=test-key", "--s3.secret-key=test-secret",
                "--tfs.ffmpeg-url=/nonexistent-ffmpeg", "--bootfs.cluster.token=test-cluster-token",
                "--spring.main.banner-mode=off"));
        if (java.util.Arrays.stream(extra).anyMatch(v -> v.startsWith("--sso.local-bypass.enabled="))) args.removeIf(v -> v.startsWith("--sso.local-bypass.enabled="));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(BootFS.class).logStartupInfo(false).run(args.toArray(String[]::new));
    }

    private String base(ConfigurableApplicationContext app) {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    private HttpResponse<String> send(ConfigurableApplicationContext app, String path, String method, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base(app) + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) request.headers(headers);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(ConfigurableApplicationContext app, String... headers) throws Exception {
        String body = "--local-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"local.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nlocal content\r\n--local-boundary--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base(app) + "/f"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "multipart/form-data; boundary=local-boundary")
                .headers(headers).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test @Timeout(60) void explicitlyDisabledBypassRejectsRealLoopbackConnections() throws Exception {
        try (var app = start()) {
            assertEquals("false", app.getEnvironment().getProperty("sso.local-bypass.enabled"));
            assertEquals(302, send(app, "/", "GET").statusCode());
            assertEquals(302, send(app, "/dashboard", "GET").statusCode());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"none", "native", "framework"}) @Timeout(60)
    void localAccessAndProxyExclusionSurviveEveryForwardingStrategy(String strategy) throws Exception {
        try (var app = start("--sso.local-bypass.enabled=true", "--server.forward-headers-strategy=" + strategy,
                "--server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1")) {
            HttpResponse<String> index = send(app, "/", "GET");
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("本地访问"));
            assertFalse(index.body().contains("href=\"/api/logout\""));
            assertEquals("no-store", index.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(index.headers().allValues("Set-Cookie").isEmpty());
            HttpResponse<String> old = send(app, "/index-old", "GET");
            assertEquals(200, old.statusCode());
            assertTrue(old.body().contains("本地访问"));
            assertFalse(old.body().contains("href=\"/api/logout\""));
            assertEquals("local", JSON.parseObject(send(app, "/api/auth/me", "GET").body()).getString("username"));
            assertEquals(200, send(app, "/api/logout", "GET").statusCode());
            assertEquals(200, send(app, "/api/cluster/status", "GET").statusCode());

            for (String header : List.of("X-Forwarded-For", "X-Real-IP", "Via")) {
                assertEquals(302, send(app, "/", "GET", header, "127.0.0.1").statusCode(), strategy + ": " + header);
            }
            assertEquals(302, send(app, "/", "GET", "Forwarded", "for=127.0.0.1;host=localhost").statusCode());
            assertEquals(302, send(app, "/", "GET", "X-Forwarded-For", "203.0.113.42").statusCode());
            assertEquals(403, send(app, "/api/file/missing/details", "GET", "Origin", "https://evil.example").statusCode());
            assertEquals(403, send(app, "/api/file/missing/details", "GET", "Sec-Fetch-Site", "cross-site").statusCode());
            assertEquals(403, send(app, "/f", "PUT").statusCode());
            assertEquals(200, upload(app, "Origin", base(app)).statusCode());
            HttpResponse<String> uploaded = upload(app, "X-BootFS-Local-Request", "1");
            assertEquals(200, uploaded.statusCode());
            assertEquals("local content", send(app, URI.create(uploaded.body()).getPath(), "GET").body());
            assertEquals(403, send(app, "/f", "PUT", "Origin", "null", "X-BootFS-Local-Request", "1").statusCode());

            // Local access cannot substitute for node credentials or AWS signatures.
            assertEquals(401, send(app, "/api/cluster/join", "POST", "X-BootFS-Local-Request", "1").statusCode());
            assertEquals(404, send(app, "/api/cluster/pairing/adopt", "POST", "X-BootFS-Local-Request", "1").statusCode());
            assertEquals(401, send(app, "/api/cluster/status", "GET", "X-BootFS-Cluster-Token", "invalid").statusCode());
            assertEquals(403, send(app, "/s3/bootfs/object", "GET").statusCode());
        }
    }

    @Test @Timeout(60) void customNativeProxyHeadersAlsoDisqualifyLocalAccess() throws Exception {
        try (var app = start("--sso.local-bypass.enabled=true", "--server.forward-headers-strategy=native",
                "--server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1",
                "--server.tomcat.remoteip.remote-ip-header=Client-IP",
                "--server.tomcat.remoteip.protocol-header=Client-Proto")) {
            assertEquals(200, send(app, "/", "GET").statusCode());
            assertEquals(302, send(app, "/", "GET", "Client-IP", "127.0.0.1").statusCode());
            assertEquals(302, send(app, "/", "GET", "Client-Proto", "http").statusCode());
        }
    }
}
