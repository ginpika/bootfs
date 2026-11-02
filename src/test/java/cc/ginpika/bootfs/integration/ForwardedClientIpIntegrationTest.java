package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.BootFS;
import cc.ginpika.bootfs.metrics.RequestStatsService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForwardedClientIpIntegrationTest {
    @TempDir Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private ConfigurableApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--tfs.root-path=" + root,
                "--tfs.unique-id=proxy-test", "--tfs.web-entrypoint=http://127.0.0.1:8181",
                "--sso.enabled=false", "--s3.enabled=false", "--tfs.ffmpeg-url=/nonexistent-ffmpeg",
                "--spring.main.banner-mode=off"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(BootFS.class).logStartupInfo(false).run(args.toArray(String[]::new));
    }

    private String recordedIp(ConfigurableApplicationContext app, String path, String forwardedFor) throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        assertEquals(404, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        JSONObject stats = JSON.parseObject(JSON.toJSONString(app.getBean(RequestStatsService.class).buildStats()));
        return stats.getJSONObject("requests").getJSONArray("recent").stream()
                .map(value -> (JSONObject) value)
                .filter(entry -> path.equals(entry.getString("uri")))
                .findFirst().orElseThrow().getString("clientIp");
    }

    @Test @Timeout(30) void nativeProxyHeadersReachMetricsFilterForIpv4Ipv6AndChains() throws Exception {
        // Loopback stands in for the configured Caddy/Docker gateway address.
        try (var app = start("--server.forward-headers-strategy=native",
                "--server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1")) {
            assertEquals("203.0.113.42", recordedIp(app, "/proxy-test/ipv4", "203.0.113.42"));
            assertEquals("2001:db8::42", recordedIp(app, "/proxy-test/ipv6", "2001:db8::42"));
            assertEquals("203.0.113.43", recordedIp(app, "/proxy-test/chain", "198.51.100.99, 203.0.113.43"));
            assertEquals("127.0.0.1", recordedIp(app, "/proxy-test/direct", null));
        }
    }

    @Test @Timeout(30) void nativeHeadersAreIgnoredForAnUntrustedConnection() throws Exception {
        try (var app = start("--server.forward-headers-strategy=native",
                "--server.tomcat.remoteip.internal-proxies=192[.]0[.]2[.]10")) {
            assertEquals("127.0.0.1", recordedIp(app, "/proxy-test/untrusted", "203.0.113.42"));
        }
    }

    @Test @Timeout(30) void forwardedHeadersRemainDisabledByDefault() throws Exception {
        try (var app = start()) {
            assertEquals("none", app.getEnvironment().getProperty("server.forward-headers-strategy"));
            assertEquals("127.0.0.1", recordedIp(app, "/proxy-test/default", "203.0.113.42"));
        }
    }
}
