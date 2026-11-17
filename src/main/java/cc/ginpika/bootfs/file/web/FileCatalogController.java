package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.result.SearchResult;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.FederationService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
class FileCatalogController {
    private final FileListQuery fileList;
    private final FederationService federation;
    private final ResourceSearchService search;

    @GetMapping("/queryPageOffset")
    FileListResponse queryPageOffset(@RequestParam(defaultValue = "0") int pageNumber,
                                     @RequestParam(defaultValue = "10") int pageSize,
                                     @RequestParam(defaultValue = "") String search,
                                     @RequestParam(defaultValue = "") String tags) {
        if (pageNumber < 0 || pageSize < 1 || pageSize > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        return fileList.page(pageNumber, pageSize, search, tags);
    }

    @GetMapping("/api/tags/search")
    public ResponseEntity<?> tagSearch(@RequestParam(value = "q", defaultValue = "") String query,
                                       @RequestParam(value = "tags", defaultValue = "") String tags,
                                       @RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "index", defaultValue = "full-text") String index) {
        if (page<0 || size<1 || size>1000 || (long)page*size>Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        if ("full-text".equals(index)) {
            JSONObject response=federation.search(query,StringUtils.isBlank(tags)?List.of():Arrays.asList(tags.split(",")),Math.multiplyExact(page,size),size,true);
            response.put("page",page); response.put("size",size); return ResponseEntity.ok(response);
        }
        List<String> tagFilters = StringUtils.isNotBlank(tags)
                ? Arrays.asList(tags.split(","))
                : List.of();
        SearchResult result = search.searchByTags(query, tagFilters, page, size, index);
        if (result == null) {
            return ResponseEntity.ok(new JSONObject()
                    .fluentPut("hits", new JSONArray())
                    .fluentPut("total", 0));
        }
        JSONObject response = new JSONObject();
        response.put("hits", new JSONArray(result.getHits()));
        response.put("total", result.getEstimatedTotalHits());
        response.put("page", page);
        response.put("size", size);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取所有可用标签及其文档计数
     * @param index 索引名，默认 full-text
     */
    @GetMapping("/api/tags/list")
    public ResponseEntity<Map<String, Integer>> tagList(@RequestParam(value = "index", defaultValue = "full-text") String index) {
        Map<String, Integer> tags = search.listAllTags(index);
        return ResponseEntity.ok(tags);
    }

}
