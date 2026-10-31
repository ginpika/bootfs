package cc.ginpika.bootfs.service.meilisearch;

import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.domain.result.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Compatibility facade: authoritative documents in MetadataStore, queries in Lucene. */
@Service
@RequiredArgsConstructor
public class MeiliSearchService {
    private final EmbeddedSearchEngine search;
    private static final String FULL_TEXT_INDEX = "full-text";
    private static final String IMAGE_HOST_INDEX = "image-host";

    public void addToFullText(String jsonString) { upsertDocuments(FULL_TEXT_INDEX, jsonString); }
    public void addToFullText(FullTextDocument document) {
        search.upsert(FULL_TEXT_INDEX, JSON.parseObject(JSON.toJSONString(document)));
    }
    public void addToImageHost(ImageHostDocument document) {
        search.upsert(IMAGE_HOST_INDEX, JSON.parseObject(JSON.toJSONString(document)));
    }
    public SearchResult searchByTags(String query, List<String> tags, int page, int size, String index) {
        if (page < 0 || size < 0) throw new IllegalArgumentException("Invalid pagination");
        return searchByTagsOffset(query, tags, Math.multiplyExact(page, size), size, index);
    }
    public SearchResult searchByTagsOffset(String query, List<String> tags, int offset, int limit, String index) {
        return asLegacyResult(search.search(index, query, tags, offset, limit, false, null, false), query);
    }
    /** Paginate in Lucene, without the old first-1000-documents truncation. */
    public SearchResult searchResources(String query, List<String> tags, int offset, int limit, boolean includeAll) {
        return asLegacyResult(search.search(FULL_TEXT_INDEX, query, tags, offset, limit, !includeAll, null, false), query);
    }
    public Map<String, Integer> listAllTags(String index) { return search.tags(index); }
    public void updateDocumentTags(String index, String uuid, List<Tag> tags) {
        mutateDocumentTags(index, uuid, tags, Collections.emptyList(), true);
    }
    public List<Tag> mutateDocumentTags(String index, String uuid, List<Tag> additions,
                                        List<String> removals, boolean replace) {
        List<Tag> requested = normalizeTags(additions);
        Set<String> removed = removals == null ? Collections.emptySet() : new HashSet<>(removals);
        JSONObject updated = search.mutate(index, uuid, source -> {
            Map<String, Tag> tags = new LinkedHashMap<>();
            if (!replace) for (Tag existing : tagsFromDocument(source)) tags.put(existing.toIndexFormat(), existing);
            for (Tag tag : requested) tags.putIfAbsent(tag.toIndexFormat(), tag);
            removed.forEach(tags::remove);
            source.put("tags", JSONArray.from(tags.keySet()));
            source.put("tagMetadata", JSONArray.from(tags.values()));
        });
        return tagsFromDocument(updated);
    }
    public List<Tag> getDocumentTags(String index, String uuid) {
        JSONObject source = search.get(index, uuid);
        if (source == null) throw new IllegalArgumentException("Document not found: " + uuid);
        return tagsFromDocument(source);
    }
    public JSONObject findDocument(String index, String uuid) { return search.get(index, uuid); }
    public void setResourcePublic(String uuid, boolean enabled, String publicUrl) {
        search.mutate(FULL_TEXT_INDEX, uuid, source -> {
            if (enabled) {
                source.put("isPublicAccess", "1");
                if (publicUrl != null) source.put("publicUrl", publicUrl);
            } else source.remove("isPublicAccess");
        });
    }
    private List<Tag> tagsFromDocument(JSONObject source) {
        Map<String, Tag> details = new LinkedHashMap<>();
        JSONArray metadata = source.getJSONArray("tagMetadata");
        if (metadata != null) for (Object value : metadata) {
            Tag tag = JSON.parseObject(JSON.toJSONString(value), Tag.class);
            details.put(tag.toIndexFormat(), tag);
        }
        List<Tag> result = new ArrayList<>();
        JSONArray tags = source.getJSONArray("tags");
        if (tags != null) for (Object value : tags) {
            Tag tag = value instanceof String ? Tag.fromIndexFormat((String) value)
                    : JSON.parseObject(JSON.toJSONString(value), Tag.class);
            result.add(details.getOrDefault(tag.toIndexFormat(), tag));
        }
        return result;
    }
    private List<Tag> normalizeTags(List<Tag> tags) {
        if (tags == null) return Collections.emptyList();
        if (tags.size() > 10000) throw new IllegalArgumentException("Too many tags");
        List<Tag> result = new ArrayList<>();
        for (Tag input : tags) {
            if (input == null || input.getName() == null || input.getName().isBlank()) throw new IllegalArgumentException("Tag name is required");
            Tag tag = JSON.parseObject(JSON.toJSONString(input), Tag.class);
            tag.setName(tag.getName().trim());
            if (tag.getNamespace() == null || tag.getNamespace().isBlank()) tag.setNamespace("manual");
            if (tag.getNamespace().contains(":")) throw new IllegalArgumentException("Tag namespace cannot contain a colon");
            if (tag.getSource() == null || tag.getSource().isBlank()) tag.setSource("manual");
            if (tag.getConfidence() == null) tag.setConfidence(1.0f);
            if (!Float.isFinite(tag.getConfidence()) || tag.getConfidence() < 0 || tag.getConfidence() > 1) throw new IllegalArgumentException("Tag confidence must be 0..1");
            result.add(tag);
        }
        return result;
    }
    public void updateDocumentTitle(String index, String uuid, String title) {
        JSONObject changes = new JSONObject(); changes.put("title", title); changes.put("fileName", title);
        search.update(index, uuid, changes);
    }
    public void deleteDocumentFromIndex(String index, String uuid) { search.delete(index, uuid); }
    public List<Map<String, Object>> getAllIndexesWithStats() { return search.indexes(); }
    public Map<String, Object> getDocuments(String index, int offset, int limit) {
        try {
            EmbeddedSearchEngine.SearchPage page = search.search(index, "", Collections.emptyList(), offset, limit, false, "fileName", false);
            JSONObject data = new JSONObject(); data.put("results", page.hits); data.put("offset", offset);
            data.put("limit", limit); data.put("total", page.total); data.put("visibleRevision", page.visibleRevision);
            return success(data);
        } catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> getDocument(String index, String uuid) {
        try {
            JSONObject doc = search.get(index, uuid);
            if (doc == null) return failure(new IllegalArgumentException("Document not found"));
            return success(doc);
        } catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> deleteDocument(String index, String uuid) {
        try { search.delete(index, uuid); return success(null); } catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> deleteAllDocuments(String index) {
        try { search.deleteAll(index); return success(null); } catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> searchDocuments(String index, String query, int offset, int limit) {
        return searchDocuments(index, query, offset, limit, null, false);
    }
    public Map<String, Object> searchDocuments(String index, String query, int offset, int limit, String sortField, boolean descending) {
        try { return success(pageData(search.search(index, query, Collections.emptyList(), offset, limit, false, sortField, descending), query)); }
        catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> searchDocuments(String index, String query, int offset, int limit,
                                               String sortField, boolean descending, Long minRevision, long timeoutMillis) {
        try {
            if (minRevision != null && !search.awaitVisibleRevision(minRevision, timeoutMillis)) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("succeed", false); pending.put("pending", true);
                pending.put("message", "Search projection has not reached requested revision");
                pending.put("visibleRevision", search.visibleRevision());
                return pending;
            }
            return searchDocuments(index, query, offset, limit, sortField, descending);
        } catch (RuntimeException e) { return failure(e); }
    }
    public Map<String, Object> addDocuments(String index, String jsonBody) {
        try { upsertDocuments(index, jsonBody); return success(Map.of("status", "succeeded")); }
        catch (RuntimeException e) { return failure(e); }
    }
    private void upsertDocuments(String index, String json) {
        Object parsed = JSON.parse(json);
        if (parsed instanceof JSONObject) search.upsert(index, (JSONObject) parsed);
        else if (parsed instanceof JSONArray) {
            for (Object value : (JSONArray) parsed) {
                if (!(value instanceof JSONObject)) throw new IllegalArgumentException("Expected a document object");
                search.upsert(index, (JSONObject) value);
            }
        } else throw new IllegalArgumentException("Expected a document or document array");
    }
    public String querySetu() { return search.randomPoster(IMAGE_HOST_INDEX); }
    public void rebuildIndexes() { search.rebuild(); }
    public long visibleRevision() { return search.visibleRevision(); }
    private SearchResult asLegacyResult(EmbeddedSearchEngine.SearchPage page, String query) {
        List<Map<String, Object>> hits = page.hits.stream()
                .map(document -> (Map<String, Object>) new LinkedHashMap<String, Object>(document))
                .collect(Collectors.toList());
        return new SearchResult(hits, page.total, page.offset, page.limit, query == null ? "" : query, page.visibleRevision);
    }
    private JSONObject pageData(EmbeddedSearchEngine.SearchPage page, String query) {
        JSONObject data = new JSONObject(); data.put("hits", page.hits); data.put("estimatedTotalHits", page.total);
        data.put("totalHits", page.total); data.put("offset", page.offset); data.put("limit", page.limit);
        data.put("query", query == null ? "" : query); data.put("visibleRevision", page.visibleRevision); return data;
    }
    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("succeed", true);
        if (data != null) result.put("data", data); result.put("visibleRevision", search.visibleRevision()); return result;
    }
    private Map<String, Object> failure(RuntimeException e) {
        return Map.of("succeed", false, "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
