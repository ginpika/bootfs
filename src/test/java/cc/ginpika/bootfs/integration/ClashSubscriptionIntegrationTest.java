package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"sso.enabled=true", "s3.enabled=false", "tfs.ffmpeg-url=/nonexistent-ffmpeg"})
@AutoConfigureMockMvc
@DirtiesContext
class ClashSubscriptionIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path", ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired FileService files;
    @Autowired MeiliSearchService search;
    @Autowired Context context;

    private static final String CONFIG = """
            proxies:
              - {name: 'VLESS 日本', type: vless, server: example.com, port: 443, uuid: 11111111-1111-4111-8111-111111111111, tls: true, network: ws, servername: edge.example.com, ws-opts: {path: '/ws', headers: {Host: edge.example.com}}}
              - {name: VMess, type: vmess, server: vmess.example.com, port: 443, uuid: 22222222-2222-4222-8222-222222222222, alterId: 0, cipher: auto, tls: true}
              - {name: Trojan, type: trojan, server: trojan.example.com, port: 443, password: demo-password, sni: trojan.example.com}
              - {name: SS, type: ss, server: ss.example.com, port: 8388, cipher: aes-128-gcm, password: demo-password}
              - {name: Unsupported, type: socks5, server: socks.example.com, port: 1080}
            """;

    @Test void publicSubscriptionExportsAllConvertibleTypesAndLeavesStoredYamlUntouched() throws Exception {
        int initialCount = context.STORAGE.size();
        String id = publish("Clash.YAML", CONFIG, true);
        var response = mvc.perform(get("/p/" + id).param("p", "vless")
                        .header("Range", "bytes=0-2").header("If-None-Match", "*"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-BootFS-Converted-Nodes", "4"))
                .andReturn().getResponse();
        String subscription = new String(Base64.getDecoder().decode(response.getContentAsString()), StandardCharsets.UTF_8);
        assertEquals(4, subscription.lines().count());
        assertTrue(subscription.lines().anyMatch(line -> line.startsWith("vless://")));
        assertTrue(subscription.lines().anyMatch(line -> line.startsWith("vmess://")));
        assertTrue(subscription.lines().anyMatch(line -> line.startsWith("trojan://")));
        assertTrue(subscription.lines().anyMatch(line -> line.startsWith("ss://")));
        assertTrue(subscription.contains("VLESS%20%E6%97%A5%E6%9C%AC"));
        assertFalse(subscription.contains("socks5://"));
        assertEquals(CONFIG, Files.readString(Path.of(context.query(id).getPath())));
        assertEquals(initialCount + 1, context.STORAGE.size());
        mvc.perform(get("/api/file/" + id + "/clash")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/file/" + id + "/clash/latency")).andExpect(status().is3xxRedirection());
    }

    @Test void tagsAndYamlExtensionAreBothRequiredForConversion() throws Exception {
        String untagged = publish("ordinary.yaml", CONFIG, false);
        String wrongExtension = publish("clash.txt", CONFIG, true);
        mvc.perform(get("/p/" + untagged).param("p", "vless")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/p/" + wrongExtension).param("p", "vless")).andExpect(status().isUnsupportedMediaType());
    }

    @Test void invalidOrUnconvertibleConfigurationReturnsAnExplicitError() throws Exception {
        String malformed = publish("broken.yml", "proxies: [\npassword: private-test-value", true);
        String noSupportedNodes = publish("unsupported.yaml", "proxies: [{name: SOCKS, type: socks5, server: example.com, port: 1080}]", true);
        var result = mvc.perform(get("/p/" + malformed).param("p", "vless"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        assertFalse(result.getContentAsString().contains("private-test-value"));
        mvc.perform(get("/p/" + noSupportedNodes).param("p", "vless")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/p/missing").param("p", "vless")).andExpect(status().isNotFound());
    }

    private String publish(String name, String yaml, boolean tagged) throws Exception {
        String id = files.publish(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), name, false).file().getUuid();
        if (tagged) search.updateDocumentTags("full-text", id, List.of(Tag.manual("Clash")));
        return id;
    }
}
