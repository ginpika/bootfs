package cc.ginpika.bootfs.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/meilisearch")
public class SearchIndexAdminController {
    @Autowired
    private ResourceSearchService resourceSearch;

    public SearchIndexAdminController(ResourceSearchService resourceSearch) {
        this.resourceSearch = resourceSearch;
    }

    @GetMapping("/indexes")
    public Map<String, Object> listIndexes() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> indexes = resourceSearch.getAllIndexesWithStats();
            result.put("succeed", true);
            result.put("data", indexes);
        } catch (Exception e) {
            log.error("获取索引列表失败", e);
            result.put("succeed", false);
            result.put("message", "Operation failed");
        }
        return result;
    }

    @GetMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> listDocuments(@PathVariable String indexUid,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "20") int limit) {
        return resourceSearch.getDocuments(indexUid, offset, limit);
    }

    @GetMapping("/indexes/{indexUid}/documents/{docId}")
    public Map<String, Object> getDocument(@PathVariable String indexUid,
                                            @PathVariable String docId) {
        return resourceSearch.getDocument(indexUid, docId);
    }

    @DeleteMapping("/indexes/{indexUid}/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String indexUid,
                                               @PathVariable String docId) {
        return resourceSearch.deleteDocument(indexUid, docId);
    }

    @DeleteMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> deleteAllDocuments(@PathVariable String indexUid) {
        return resourceSearch.deleteAllDocuments(indexUid);
    }

    @GetMapping("/indexes/{indexUid}/search")
    public Map<String, Object> searchDocuments(@PathVariable String indexUid,
                                                @RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(required = false) String sort,
                                                @RequestParam(defaultValue = "false") boolean descending,
                                                @RequestParam(required = false) Long minRevision,
                                                @RequestParam(defaultValue = "1000") long timeoutMs) {
        return resourceSearch.searchDocuments(indexUid, q, offset, limit, sort, descending, minRevision, timeoutMs);
    }

    @PostMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> addDocuments(@PathVariable String indexUid,
                                             @RequestBody String jsonBody) {
        return resourceSearch.addDocuments(indexUid, jsonBody);
    }
}
