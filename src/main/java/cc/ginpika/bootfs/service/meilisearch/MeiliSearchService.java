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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MeiliSearchService {
    @Autowired
    MeiliSearchConfig meiliSearchConfig;

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
        addToFullText(JSONObject.toJSONString(fullTextDocument));
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
