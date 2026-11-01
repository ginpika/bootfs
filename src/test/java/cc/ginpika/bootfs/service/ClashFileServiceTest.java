package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClashFileServiceTest {
    private final TextFileService texts = mock(TextFileService.class);
    private final MeiliSearchService search = mock(MeiliSearchService.class);
    private final ClashSubscriptionService subscriptions = mock(ClashSubscriptionService.class);
    private final ClashFileService files = new ClashFileService(texts, search, subscriptions);

    @Test void onlyYamlResourcesWithAnActualClashTagAreAccepted() throws Exception {
        when(texts.read("resource")).thenReturn(document("CONFIG.YAML"));
        when(search.getDocumentTags("full-text", "resource")).thenReturn(List.of(Tag.manual("ClAsH")));
        assertEquals("CONFIG.YAML", files.read("resource").fileName());
        when(texts.read("resource")).thenReturn(document("config.yml"));
        assertEquals("config.yml", files.read("resource").fileName());
        when(search.getDocumentTags("full-text", "resource")).thenReturn(List.of(Tag.manual("clash", "other")));
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> files.read("resource")).getRawStatusCode());
        when(search.getDocumentTags("full-text", "resource")).thenReturn(List.of(Tag.manual("clash")));
        when(texts.read("resource")).thenReturn(document("config.txt"));
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> files.read("resource")).getRawStatusCode());
    }

    @Test void detailSummariesExposeOnlyEndpointInformationAndStableOrderedIds() throws Exception {
        when(texts.read("resource")).thenReturn(document("config.yaml"));
        when(search.getDocumentTags("full-text", "resource")).thenReturn(List.of(Tag.manual("clash")));
        when(subscriptions.parseConfig("proxies: []")).thenReturn(new ClashSubscriptionService.ParsedConfig(List.of(
                new ClashSubscriptionService.ClashNode("香港", "vless", "edge.example", 443, Map.of("uuid", "secret-credential")),
                new ClashSubscriptionService.ClashNode("内网", "ss", "192.168.1.2", 8388, Map.of("password", "secret-password"))
        ), List.of("Skipped malformed node")));
        var details = files.details("resource");
        assertEquals(List.of("node-0", "node-1"), details.nodes().stream().map(ClashFileService.ClashEndpoint::id).toList());
        assertEquals("edge.example", details.nodes().get(0).server()); assertEquals(8388, details.nodes().get(1).port());
        assertEquals("version", details.version()); assertEquals(List.of("Skipped malformed node"), details.warnings());
        String serialized = JSONObject.toJSONString(details);
        assertFalse(serialized.contains("secret-credential")); assertFalse(serialized.contains("secret-password"));
        assertFalse(serialized.contains("options"));
    }

    private TextFileService.TextDocument document(String name) {
        return new TextFileService.TextDocument("resource", name, "proxies: []", "version", 11, "http://node/f/resource");
    }
}
