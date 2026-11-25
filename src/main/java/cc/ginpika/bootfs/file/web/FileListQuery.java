package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.core.ResourceUrls;

import cc.ginpika.bootfs.domain.result.SearchResult;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.FederationService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class FileListQuery {
    private final LocalFileInventory inventory;
    private final ResourceSearchService search;
    private final FederationService federation;

    FileListResponse page(int offset, int size, String query, String tags) {
        if (StringUtils.containsIgnoreCase(query, "meta:local")) {
            var page = inventory.searchCached(offset, size, query.replaceAll("(?i)meta:local", "").trim());
            var rows = page.rows().stream().map(file -> FileListResponse.Row.fromInventory(file,
                    ResourceUrls.file(file.getUuid()), inventory.buildThumbUrl(file.getUuid()))).toList();
            return new FileListResponse(offset, size, page.total(), rows, false, null);
        }
        List<String> tagFilter = tags == null ? List.of() : Arrays.stream(tags.split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).toList();
        String text = query == null ? "" : query.replace("meta:all", "").trim();
        boolean includeAll = query != null && query.contains("meta:all");
        JSONObject page = federation.search(text, tagFilter, offset, size, includeAll);
        if (page != null) {
            return new FileListResponse(offset, size, page.getIntValue("total"), searchRows(page.getJSONArray("hits")),
                    page.getBooleanValue("partial"), page.getJSONArray("unavailableNodes").toJavaList(String.class));
        }
        SearchResult result = search.searchResources(text, tagFilter, offset, size, includeAll);
        return new FileListResponse(offset, size, result.getEstimatedTotalHits(), searchRows(result.getHits()), false, List.of());
    }

    private List<FileListResponse.Row> searchRows(Iterable<?> hits) {
        List<FileListResponse.Row> rows = new ArrayList<>();
        for (Object hit : hits) {
            if (hit instanceof Map<?, ?> document) {
                String uuid = (String) document.get("uuid");
                if (uuid == null) continue;
                Object origin = document.get("originEndpoint");
                String endpoint = origin == null ? "" : origin.toString();
                rows.add(FileListResponse.Row.fromSearchHit(document, endpoint + ResourceUrls.file(uuid), endpoint));
            }
        }
        return rows;
    }
}
