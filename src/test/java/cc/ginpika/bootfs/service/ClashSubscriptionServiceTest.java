package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClashSubscriptionServiceTest {
    private final ClashSubscriptionService service = new ClashSubscriptionService();
    private static final String UUID = "05a6a8cb-b2be-42fd-90b1-0fe23adb9576";

    @Test void mixedSubscriptionExportsAllSupportedTypesAndReportsUnsupportedNodes() {
        String config = """
                proxies:
                  - {name: 香港 VLESS, type: vless, server: example.com, port: 443, uuid: %s, tls: true, network: ws, servername: sni.example.com, ws-opts: {path: '/a?x=1&y=2', headers: {Host: ws.example.com}}}
                  - {name: VMess, type: vmess, server: 1.2.3.4, port: 8443, uuid: %s, alterId: 0, cipher: auto, tls: true, network: grpc, grpc-opts: {grpc-service-name: my.service}, skip-cert-verify: true}
                  - {name: Trojan, type: trojan, server: example.com, port: 443, password: 'a:b@?密', sni: tls.example.com, skip-cert-verify: true}
                  - {name: SS, type: ss, server: example.com, port: 8388, cipher: aes-256-gcm, password: 'p@ss:字'}
                  - {name: HY2, type: hysteria2, server: example.com, port: 443, password: 'auth#?密', obfs: salamander, obfs-password: 'ob%%41fs', sni: hy.example.com}
                  - {name: TUIC, type: tuic, server: example.com, port: 443, uuid: %s, password: 'pass:word', congestion-controller: bbr, alpn: [h3]}
                  - {name: unsupported, type: snell, server: example.com, port: 443, psk: hidden}
                """.formatted(UUID, UUID, UUID);
        var subscription = service.convert(config);
        assertEquals(6, subscription.converted());
        assertEquals(1, subscription.warnings().size());
        assertTrue(subscription.warnings().get(0).contains("snell"));
        assertFalse(subscription.warnings().toString().contains("hidden"));
        String[] links = decode(subscription.content()).strip().split("\n");
        assertEquals(6, links.length);
        URI vless = URI.create(links[0]);
        assertEquals("香港 VLESS", URLDecoder.decode(vless.getRawFragment(), StandardCharsets.UTF_8));
        assertEquals("/a?x=1&y=2", query(vless).get("path"));
        assertEquals("ws.example.com", query(vless).get("host"));
        assertEquals("tls", query(vless).get("security"));
        JSONObject vmess = JSON.parseObject(decode(links[1].substring("vmess://".length())));
        assertEquals("my.service", vmess.getString("path")); assertEquals("grpc", vmess.getString("net"));
        assertEquals("gun", vmess.getString("type"));
        assertEquals("1", vmess.getString("insecure")); assertEquals(UUID, vmess.getString("id"));
        assertEquals("a:b@?密", URLDecoder.decode(URI.create(links[2]).getRawUserInfo(), StandardCharsets.UTF_8));
        assertEquals("1", query(URI.create(links[2])).get("allowInsecure"));
        String ssUser = URI.create(links[3]).getRawUserInfo();
        assertEquals("aes-256-gcm:p@ss:字", new String(Base64.getUrlDecoder().decode(ssUser), StandardCharsets.UTF_8));
        assertEquals("ob%41fs", query(URI.create(links[4])).get("obfs-password"));
        assertEquals("bbr", query(URI.create(links[5])).get("congestion_control"));
    }

    @Test void realityIpv6InternationalHostAndEscapingPreserveAllRequiredValues() {
        String yaml = """
                proxies:
                  - name: 'Reality #1'
                    type: vless
                    server: '[2001:db8::1]'
                    port: 443
                    uuid: %s
                    tls: true
                    flow: xtls-rprx-vision
                    client-fingerprint: chrome
                    servername: real.example.com
                    reality-opts: {public-key: 'abc_def-123', short-id: 'a1b2'}
                  - {name: international, type: ss, server: '例子.测试', port: 443, cipher: 2022-blake3-aes-256-gcm, password: 'ab+/=:#字'}
                """.formatted(UUID);
        List<ClashSubscriptionService.ClashNode> parsed = service.parse(yaml);
        assertEquals("2001:db8::1", parsed.get(0).server());
        assertEquals("xn--fsqu00a.xn--0zwm56d", parsed.get(1).server());
        String[] links = decode(service.convert(yaml).content()).strip().split("\n");
        assertTrue(links[0].contains("@[2001:db8::1]:443"));
        var query = query(URI.create(links[0]));
        assertEquals("reality", query.get("security")); assertEquals("abc_def-123", query.get("pbk"));
        assertEquals("a1b2", query.get("sid")); assertEquals("chrome", query.get("fp"));
        assertEquals("xtls-rprx-vision", query.get("flow"));
        assertEquals("2022-blake3-aes-256-gcm:ab+/=:#字", URLDecoder.decode(URI.create(links[1]).getRawUserInfo(), StandardCharsets.UTF_8));
    }

    @Test void unsupportedSecurityTransportAndMissingCredentialsNeverProduceMisleadingLinks() {
        String yaml = """
                proxies:
                  - {name: chain, type: vless, server: example.com, port: 443, uuid: %s, dialer-proxy: secret-chain}
                  - {name: quic, type: vmess, server: example.com, port: 443, uuid: %s, network: quic}
                  - {name: headers, type: vless, server: example.com, port: 443, uuid: %s, network: ws, ws-opts: {headers: {Authorization: 'secret-token'}}}
                  - {name: missing, type: trojan, server: example.com, port: 443}
                  - {name: insecure-vless, type: vless, server: example.com, port: 443, uuid: %s, tls: true, skip-cert-verify: true}
                  - {name: good, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: valid}
                """.formatted(UUID, UUID, UUID, UUID);
        var result = service.convert(yaml);
        assertEquals(1, result.converted()); assertEquals(5, result.warnings().size());
        assertFalse(result.warnings().toString().contains("secret-token"));
        assertFalse(result.warnings().toString().contains("secret-chain"));
        var noNodes = assertThrows(ResponseStatusException.class, () -> service.convert("proxies: [{type: direct, server: example.com, port: 80}]"));
        assertEquals(422, noNodes.getRawStatusCode());
    }

    @Test void safeYamlRejectsJavaTagsDuplicateKeysExcessiveDepthAliasesAndBounds() {
        for (String yaml : List.of("!!javax.script.ScriptEngineManager []", "proxies: []\nproxies: []", "proxies: " + "[".repeat(70) + "x" + "]".repeat(70), "proxies: [&self {<<: *self}]")) {
            assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.parse(yaml)).getRawStatusCode());
        }
        String aliases = "base: &base {value: test}\nproxies: [" + String.join(",", java.util.Collections.nCopies(25, "*base")) + "]";
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.parse(aliases)).getRawStatusCode());
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> service.parse("x".repeat(ClashSubscriptionService.MAX_BYTES + 1))).getRawStatusCode());
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> service.parse("proxies: [" + String.join(",", java.util.Collections.nCopies(501, "{}")) + "]")).getRawStatusCode());
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.parse("proxy-providers: {remote: {url: https://example.com/sub}} ")).getRawStatusCode());
    }

    @Test void malformedNodesAreReportedWhileValidNodesAndSafeYamlMergeRemainAvailable() {
        String yaml = """
                defaults: &defaults {type: ss, port: 443, cipher: aes-128-gcm, password: pass}
                proxies:
                  - {<<: *defaults, name: valid, server: example.com}
                  - {<<: *defaults, name: fractional-port, server: example.com, port: 443.5}
                  - {<<: *defaults, name: url, server: 'https://example.com/path'}
                  - {<<: *defaults, name: invalid-ipv6, server: '[bad:ipv6]'}
                  - {<<: *defaults, name: invalid-value, server: example.com, unsupported: !!binary aGVsbG8=}
                """;
        var result = service.parseConfig(yaml);
        assertEquals(1, result.nodes().size()); assertEquals(4, result.warnings().size());
        assertEquals("valid", result.nodes().get(0).name());
        assertThrows(UnsupportedOperationException.class, () -> result.nodes().get(0).options().put("password", "changed"));
    }

    @Test void boundedAliasesCannotExpandIntoUnboundedCopiedObjects() {
        StringBuilder yaml = new StringBuilder("a0: &a0 [" + String.join(",", java.util.Collections.nCopies(500, "value")) + "]\n");
        for (int level = 1; level <= 8; level++) {
            yaml.append("a").append(level).append(": &a").append(level).append(" [*a").append(level - 1).append(", *a").append(level - 1).append("]\n");
        }
        // Only 17 aliases and about 1,100 events, but expanding the shared DAG would
        // copy over 128,000 scalars. The budget covers every node in the same parse.
        yaml.append("proxies: [{name: bounded, type: ss, server: example.com, port: 443, padding: *a8}]\n");
        var failure = assertThrows(ResponseStatusException.class, () -> service.parse(yaml.toString()));
        assertEquals(422, failure.getRawStatusCode());
        assertTrue(failure.getReason().contains("展开"));
    }

    @Test void shadowsocksPluginAndXhttpRemainExplicitlyRepresentable() {
        String yaml = """
                proxies:
                  - {name: plugin, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: pass, plugin: v2ray-plugin, plugin-opts: {mode: websocket, tls: true, host: edge.example.com, path: '/ws?x=1:a'}}
                  - {name: xhttp, type: vless, server: example.com, port: 443, uuid: %s, tls: true, network: xhttp, xhttp-opts: {path: '/x', host: edge.example.com, mode: stream-one, headers: {X-Custom: 'a&b'}}}
                """.formatted(UUID);
        String[] links = decode(service.convert(yaml).content()).strip().split("\n");
        URI ss = URI.create(links[0]); assertEquals("/", ss.getPath());
        assertTrue(query(ss).get("plugin").contains("path=/ws?x\\=1:a"));
        assertTrue(query(ss).get("plugin").contains(";tls"));
        Map<String, String> xhttp = query(URI.create(links[1]));
        assertEquals("xhttp", xhttp.get("type")); assertEquals("stream-one", xhttp.get("mode"));
        assertEquals("a&b", JSON.parseObject(xhttp.get("extra")).getJSONObject("headers").getString("X-Custom"));
    }

    @Test void shadowsocksPluginsThatV2rayNCannotRepresentAreSkipped() {
        String yaml = """
                proxies:
                  - {name: obfs-tls, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: pass, plugin: obfs, plugin-opts: {mode: tls, host: example.com}}
                  - {name: mux, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: pass, plugin: v2ray-plugin, plugin-opts: {mode: websocket, mux: true}}
                  - {name: semicolon, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: pass, plugin: v2ray-plugin, plugin-opts: {mode: websocket, path: '/a;b'}}
                  - {name: good-obfs, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: pass, plugin: obfs, plugin-opts: {mode: http, host: example.com}}
                """;
        var result = service.convert(yaml);
        assertEquals(1, result.converted()); assertEquals(3, result.warnings().size());
        assertEquals("obfs-local;obfs=http;obfs-host=example.com", query(URI.create(decode(result.content()).strip())).get("plugin"));
    }

    private static String decode(String encoded) { return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
