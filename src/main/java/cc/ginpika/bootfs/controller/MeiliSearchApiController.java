package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/api/meilisearch")
public class MeiliSearchApiController {
    @Autowired
    private MeiliSearchService meiliSearchService;

    public MeiliSearchApiController(MeiliSearchService meiliSearchService) {
        this.meiliSearchService = meiliSearchService;
    }

    @GetMapping("/indexes")
    public Map<String, Object> listIndexes() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> indexes = meiliSearchService.getAllIndexesWithStats();
            result.put("succeed", true);
            result.put("data", indexes);
        } catch (Exception e) {
            log.error("获取索引列表失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> listDocuments(@PathVariable String indexUid,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "20") int limit) {
        return meiliSearchService.getDocuments(indexUid, offset, limit);
    }

    @GetMapping("/indexes/{indexUid}/documents/{docId}")
    public Map<String, Object> getDocument(@PathVariable String indexUid,
                                            @PathVariable String docId) {
        return meiliSearchService.getDocument(indexUid, docId);
    }

    @DeleteMapping("/indexes/{indexUid}/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String indexUid,
                                               @PathVariable String docId) {
        return meiliSearchService.deleteDocument(indexUid, docId);
    }

    @DeleteMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> deleteAllDocuments(@PathVariable String indexUid) {
        return meiliSearchService.deleteAllDocuments(indexUid);
    }

    @GetMapping("/indexes/{indexUid}/search")
    public Map<String, Object> searchDocuments(@PathVariable String indexUid,
                                                @RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit) {
        return meiliSearchService.searchDocuments(indexUid, q, offset, limit);
    }

    @PostMapping("/indexes/{indexUid}/documents")
    public Map<String, Object> addDocuments(@PathVariable String indexUid,
                                             @RequestBody String jsonBody) {
        return meiliSearchService.addDocuments(indexUid, jsonBody);
    }
}
