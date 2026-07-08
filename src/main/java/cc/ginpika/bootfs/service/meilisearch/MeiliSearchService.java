package cc.ginpika.bootfs.service.meilisearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import cc.ginpika.bootfs.config.MeiliSearchConfig;
import cc.ginpika.bootfs.domain.dto.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeiliSearchService {
    private final MeiliSearchConfig meiliSearchConfig;

    private Client client;

    private static final String FULL_TEXT_INDEX = "full-text";
    private static final String IMAGE_HOST_INDEX = "image-host";

    @EventListener
    public void onRefresh(ContextRefreshedEvent event) {
        String hostUrl = meiliSearchConfig.getUrl();
        String masterKey = meiliSearchConfig.getMasterKey();
        client = new Client(new Config(hostUrl, masterKey));
        configureIndexes(client);
    }

    private void configureIndexes(Client client) {
        configureFilterableAttributes(client, FULL_TEXT_INDEX);
        configureFilterableAttributes(client, IMAGE_HOST_INDEX);
    }

    private void configureFilterableAttributes(Client client, String indexUid) {
        try {
            Index index = client.index(indexUid);
            index.updateFilterableAttributesSettings(new String[]{"tags"});
            log.info("已为索引 {} 配置 filterableAttributes: [tags]", indexUid);
        } catch (Exception e) {
            log.warn("配置索引 {} 的 filterableAttributes 失败（可能索引尚未创建）: {}", indexUid, e.getMessage());
        }
    }

    // ======================== 文档索引 ========================

    public void addToFullText(String jsonString) {
        Index index = client.index(FULL_TEXT_INDEX);
        index.addDocuments(jsonString);
    }

    public void addToFullText(FullTextDocument fullTextDocument) {
        try {
            Index index = client.index(FULL_TEXT_INDEX);
            String uuid = fullTextDocument.getUuid();
            // 查询旧文档，存在则以旧文档为基础合并（跳过 null 值字段），避免覆盖 resources 等已有字段
            JSONObject merged = new JSONObject();
            try {
                String existing = index.getRawDocument(uuid);
                log.info("exists idx: {}", existing);
                if (StringUtils.isNotBlank(existing)) {
                    merged = JSON.parseObject(existing);
                }
            } catch (Exception e) {
                e.printStackTrace();
                log.info("查询旧文档失败（可能不存在），将直接写入, uuid={}", uuid);
            }
            // log.info("uuid {} resources:{}", merged.getString(uuid), merged.getJSONArray("resources"));
            // 将新文档转为 JSONObject，仅用非 null 字段覆盖旧文档
            JSONObject newDoc = JSON.parseObject(JSON.toJSONString(fullTextDocument));
            for (Map.Entry<String, Object> entry : newDoc.entrySet()) {
                log.info(entry.getKey());
                if (entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            // 如果是图集，thumbUrl 取图集的第一张图
            if (merged.getIntValue("albumAvailable") == 1) {
                merged.put("thumbUrl", merged.getJSONArray("resources").getString(0));
                merged.put("poster", merged.get("thumbUrl"));
            }
            String jsonString = merged.toJSONString();
            index.addDocuments(jsonString);
            // TODO 优化
            // 因为 MeiliSearch 是异步的，需要等待一段时间确保索引更新，防止并发时幻读问题
            TimeUnit.SECONDS.sleep(1);
        } catch (Exception e) {
            log.error("添加文档到索引 {} 失败, uuid={}", FULL_TEXT_INDEX, fullTextDocument.getUuid(), e);
        }
    }

    public void addToImageHost(ImageHostDocument imageHostDocument) {
        Index index = client.index(IMAGE_HOST_INDEX);
        index.addDocuments(JSONObject.toJSONString(imageHostDocument));
    }

    // ======================== 标签搜索 ========================

    /**
     * 按标签过滤搜索
     * @param query 全文搜索关键词，传空字符串表示不限制
     * @param tagFilters 标签过滤条件，格式如 "namespace:name"，多个 tag 之间为 AND 关系
     * @param page 页码（从0开始）
     * @param size 每页数量
     * @param indexUid 索引名
     */
    public SearchResult searchByTags(String query, List<String> tagFilters, int page, int size, String indexUid) {
        try {
            Index index = client.index(indexUid);
            SearchRequest request = new SearchRequest(StringUtils.isBlank(query) ? "" : query)
                    .setOffset(page * size)
                    .setLimit(size);

            if (tagFilters != null && !tagFilters.isEmpty()) {
                String[] filterArray = tagFilters.stream()
                        .map(tag -> "tags = \"" + tag + "\"")
                        .toArray(String[]::new);
                request.setFilter(filterArray);
            }

            return (SearchResult) index.search(request);
        } catch (Exception e) {
            log.error("标签搜索失败, index={}, query={}, tags={}", indexUid, query, tagFilters, e);
            return null;
        }
    }

    /**
     * 标签聚合：获取索引中所有已使用的标签及其文档计数
     * @param indexUid 索引名
     * @return Map<标签值, 文档数量>
     */
    public Map<String, Integer> listAllTags(String indexUid) {
        try {
            Index index = client.index(indexUid);
            SearchRequest request = new SearchRequest("")
                    .setFacets(new String[]{"tags"})
                    .setLimit(0);
            SearchResult result = (SearchResult) index.search(request);
            if (result == null || result.getFacetDistribution() == null) {
                return Collections.emptyMap();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> facetDist = (Map<String, Object>) result.getFacetDistribution();
            @SuppressWarnings("unchecked")
            Map<String, Integer> tagFacets = (Map<String, Integer>) facetDist.get("tags");
            return tagFacets != null ? tagFacets : Collections.emptyMap();
        } catch (Exception e) {
            log.error("获取标签列表失败, index={}", indexUid, e);
            return Collections.emptyMap();
        }
    }

    // ======================== 文档标签更新 ========================

    /**
     * 增量更新某个文档的 tags 字段（仅更新 tags，不覆盖其他字段）
     */
    public void updateDocumentTags(String indexUid, String uuid, List<Tag> tags) {
        try {
            Index index = client.index(indexUid);
            List<String> indexTags = tags.stream()
                    .map(Tag::toIndexFormat)
                    .collect(Collectors.toList());
            JSONObject partialDoc = new JSONObject();
            partialDoc.put("uuid", uuid);
            partialDoc.put("tags", JSONArray.from(indexTags));
            index.updateDocuments(JSON.toJSONString(Collections.singletonList(partialDoc)));
            log.info("已更新索引 {} 中文档 {} 的 tags: {}", indexUid, uuid, indexTags);
        } catch (Exception e) {
            log.error("更新文档标签失败, index={}, uuid={}", indexUid, uuid, e);
        }
    }

    /**
     * 从索引中删除文档
     */
    public void deleteDocumentFromIndex(String indexUid, String uuid) {
        try {
            Index index = client.index(indexUid);
            index.deleteDocument(uuid);
            log.info("已从索引 {} 中删除文档 {}", indexUid, uuid);
        } catch (Exception e) {
            log.error("从索引删除文档失败, index={}, uuid={}", indexUid, uuid, e);
        }
    }

    // ======================== 索引/文档管理（通过 OkHttp 直接调用 MeiliSearch REST API） ========================

    private OkHttpClient httpClient = new OkHttpClient();

    private String apiBaseUrl() {
        return meiliSearchConfig.getUrl();
    }

    private String authHeader() {
        return "Bearer " + meiliSearchConfig.getMasterKey();
    }

    private String get(String path) throws IOException {
        Request request = new Request.Builder()
                .url(apiBaseUrl() + path)
                .header("Authorization", authHeader())
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String delete(String path) throws IOException {
        Request request = new Request.Builder()
                .url(apiBaseUrl() + path)
                .header("Authorization", authHeader())
                .delete()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String post(String path, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(apiBaseUrl() + path)
                .header("Authorization", authHeader())
                .post(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    public List<Map<String, Object>> getAllIndexesWithStats() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String raw = get("/indexes");
            JSONObject json = JSON.parseObject(raw);
            JSONArray results = json.getJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject idx = results.getJSONObject(i);
                    Map<String, Object> item = new HashMap<>();
                    item.put("uid", idx.getString("uid"));
                    item.put("primaryKey", idx.getString("primaryKey"));
                    // 查询该索引的文档数
                    try {
                        String statsRaw = get("/indexes/" + idx.getString("uid") + "/stats");
                        JSONObject statsJson = JSON.parseObject(statsRaw);
                        item.put("numberOfDocuments", statsJson.getIntValue("numberOfDocuments"));
                    } catch (Exception e) {
                        item.put("numberOfDocuments", 0);
                    }
                    result.add(item);
                }
            }
        } catch (Exception e) {
            log.error("获取 MeiliSearch 索引列表失败", e);
        }
        return result;
    }

    public Map<String, Object> getDocuments(String indexUid, int offset, int limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            String path = "/indexes/" + indexUid + "/documents?offset=" + offset + "&limit=" + limit;
            String raw = get(path);
            result.put("succeed", true);
            result.put("data", JSON.parse(raw));
        } catch (Exception e) {
            log.error("获取 MeiliSearch 文档列表失败, index={}", indexUid, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getDocument(String indexUid, String docId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String path = "/indexes/" + indexUid + "/documents/" + docId;
            String raw = get(path);
            result.put("succeed", true);
            result.put("data", JSON.parse(raw));
        } catch (Exception e) {
            log.error("获取 MeiliSearch 文档失败, index={}, id={}", indexUid, docId, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> deleteDocument(String indexUid, String docId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String path = "/indexes/" + indexUid + "/documents/" + docId;
            delete(path);
            result.put("succeed", true);
            result.put("message", "删除成功");
        } catch (Exception e) {
            log.error("删除 MeiliSearch 文档失败, index={}, id={}", indexUid, docId, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> deleteAllDocuments(String indexUid) {
        Map<String, Object> result = new HashMap<>();
        try {
            String path = "/indexes/" + indexUid + "/documents";
            delete(path);
            result.put("succeed", true);
            result.put("message", "已清空索引中的所有文档");
        } catch (Exception e) {
            log.error("清空 MeiliSearch 索引失败, index={}", indexUid, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> searchDocuments(String indexUid, String query, int offset, int limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            JSONObject body = new JSONObject();
            body.put("q", query != null ? query : "");
            body.put("offset", offset);
            body.put("limit", limit);
            String path = "/indexes/" + indexUid + "/search";
            String raw = post(path, body.toJSONString());
            result.put("succeed", true);
            result.put("data", JSON.parse(raw));
        } catch (Exception e) {
            log.error("搜索 MeiliSearch 文档失败, index={}, query={}", indexUid, query, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> addDocuments(String indexUid, String jsonBody) {
        Map<String, Object> result = new HashMap<>();
        try {
            String path = "/indexes/" + indexUid + "/documents";
            String raw = post(path, jsonBody);
            result.put("succeed", true);
            result.put("data", JSON.parse(raw));
        } catch (Exception e) {
            log.error("新增 MeiliSearch 文档失败, index={}", indexUid, e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ======================== 原有方法 ========================

    public String querySetu() {
        try {
            Index index = client.index(IMAGE_HOST_INDEX);
            SearchResult searchResult = (SearchResult) index.search(new SearchRequest("")
                    .setLimit(Integer.MAX_VALUE));
            List<String> setuList = new ArrayList<>();
            if (searchResult != null && searchResult.getHits() != null) {
                searchResult.getHits().forEach(hit -> {
                    String url = (String) hit.get("poster");
                    if (StringUtils.isNotBlank(url)) {
                        setuList.add(url);
                    }
                });
            }
            if (setuList.isEmpty()) {
                log.warn("No public images found in image-host index");
                return null;
            }
            int lucky = new Random().nextInt(setuList.size());
            return setuList.get(lucky);
        } catch (Exception e) {
            log.error("Failed to query setu from MeiliSearch", e);
            return null;
        }
    }
}
