package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads only explicitly tagged Clash YAML resources and exposes non-secret endpoint summaries. */
@Service
public class ClashFileService {
    private final TextFileService textFiles;
    private final MeiliSearchService search;
    private final ClashSubscriptionService subscriptions;

    public ClashFileService(TextFileService textFiles, MeiliSearchService search, ClashSubscriptionService subscriptions) {
        this.textFiles = textFiles;
        this.search = search;
        this.subscriptions = subscriptions;
    }

    public TextFileService.TextDocument read(String uuid) throws IOException {
        TextFileService.TextDocument text = textFiles.read(uuid);
        String name = text.fileName().toLowerCase(Locale.ROOT);
        boolean yaml = name.endsWith(".yaml") || name.endsWith(".yml");
        boolean tagged = search.getDocumentTags("full-text", uuid).stream()
                .anyMatch(tag -> tag != null && tag.getName() != null && "clash".equalsIgnoreCase(tag.getName().trim()));
        if (!yaml || !tagged) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅带 clash 标签的 YAML 文件支持此功能");
        }
        return text;
    }

    public ClashDetails details(String uuid) throws IOException {
        TextFileService.TextDocument text = read(uuid);
        ClashSubscriptionService.ParsedConfig parsed = subscriptions.parseConfig(text.content());
        List<ClashEndpoint> nodes = new ArrayList<>();
        for (int index = 0; index < parsed.nodes().size(); index++) {
            ClashSubscriptionService.ClashNode node = parsed.nodes().get(index);
            nodes.add(new ClashEndpoint("node-" + index, node.name(), node.type(), node.server(), node.port()));
        }
        return new ClashDetails(uuid, text.version(), List.copyOf(nodes), parsed.warnings(), "tcp", "server");
    }

    public record ClashEndpoint(String id, String name, String type, String server, int port) {}
    public record ClashDetails(String uuid, String version, List<ClashEndpoint> nodes, List<String> warnings,
                               String measurement, String vantagePoint) {}
}
