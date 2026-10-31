package cc.ginpika.bootfs.kernel.search;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lucene is a disposable projection. The complete, original JSON documents live in
 * MetadataStore and are replicated by its selected backend. No user-authored field
 * depends on the availability or survival of the Lucene directory.
 */
@Component
@DependsOn("legacyMetadataImport")
public class EmbeddedSearchEngine implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmbeddedSearchEngine.class);
    public static final String SOURCE_PREFIX = "/documents/";
    private static final String DELETED = "_bootfsDeleted";
    private final MetadataStore metadata;
    private final Path root;
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final Map<String, IndexState> indexes = new TreeMap<>();
    private Map<String, String> projectedSources = new TreeMap<>();
    private Set<String> resourceTombstones = new HashSet<>();
    private long visibleRevision = -1;
    private boolean closed;
    private volatile String projectionFailure;

    @Autowired
    public EmbeddedSearchEngine(MetadataStore metadata, TfsConfig config) throws IOException {
        this(metadata, Path.of(config.getRootPath(), "kernel", "search"));
    }

    public EmbeddedSearchEngine(MetadataStore metadata, Path root) throws IOException {
        this.metadata = metadata;
        this.root = root;
        Files.createDirectories(root);
        // Start from canonical sources each time, also repairing an incomplete or
        // corrupt previous Lucene commit. This never deletes canonical documents.
        index("full-text");
        index("image-host");
        synchronizeProjection();
    }

    public JSONObject get(String index, String id) {
        if ("image-host".equals(index)) {
            JSONObject canonical = get("full-text", id);
            return canonical != null && isPublic(canonical) ? galleryDocument(canonical) : null;
        }
        String raw = metadata.get(sourceKey(index, id));
        if (raw == null) return null;
        JSONObject doc = JSON.parseObject(raw);
        return doc.getBooleanValue(DELETED) || isResourceDeleted(doc) ? null : doc;
    }

    public void upsert(String index, JSONObject changes) {
        if (changes == null || changes.containsKey(DELETED)) {
            throw new IllegalArgumentException("Invalid document");
        }
        if ("image-host".equals(index)) {
            JSONObject canonicalChanges = JSON.parseObject(changes.toJSONString());
            Object poster = canonicalChanges.remove("poster");
            if (poster != null) canonicalChanges.put("publicUrl", poster);
            if (!canonicalChanges.containsKey("isPublicAccess")) canonicalChanges.put("isPublicAccess", "1");
            JSONObject canonical = get("full-text", changes.getString("uuid"));
            JSONArray tags = canonicalChanges.getJSONArray("tags");
            if (canonical != null && tags != null && tags.isEmpty()) canonicalChanges.remove("tags");
            upsert("full-text", canonicalChanges);
            return;
        }
        String id = changes.getString("uuid");
        String key = sourceKey(index, id);
        for (int attempt = 0; attempt < 64; attempt++) {
            String existing = metadata.get(key);
            JSONObject merged = existing == null ? new JSONObject() : JSON.parseObject(existing);
            if (merged.getBooleanValue(DELETED)) {
                throw new IllegalStateException("Document was deleted: " + id);
            }
            changes.forEach((field, value) -> {
                if (value != null) merged.put(field, value);
            });
            if (merged.getIntValue("albumAvailable") == 1) {
                JSONArray resources = merged.getJSONArray("resources");
                if (resources != null && !resources.isEmpty()) {
                    merged.put("thumbUrl", resources.getString(0));
                    merged.put("poster", merged.get("thumbUrl"));
                }
            }
            ensureResourceLive(merged);
            luceneDocument(merged); // Validate indexable values before committing authority.
            if (metadata.compareAndSet(key, existing, merged.toJSONString())) {
                rejectRacingDeletion(index, merged);
                synchronizeProjection();
                return;
            }
        }
        throw new IllegalStateException("Concurrent document modifications exceeded retry budget");
    }

    public void update(String index, String id, JSONObject changes) {
        if (changes.containsKey(DELETED) || (changes.containsKey("uuid") && !id.equals(changes.getString("uuid")))) {
            throw new IllegalArgumentException("Document identity cannot change");
        }
        mutate(index, id, document -> document.putAll(changes));
    }

    /** Re-evaluates the mutation against the latest source after every CAS conflict. */
    public JSONObject mutate(String index, String id, java.util.function.Consumer<JSONObject> mutation) {
        if ("image-host".equals(index)) {
            if (get(index, id) == null) throw new IllegalArgumentException("Document not found: " + id);
            return mutate("full-text", id, mutation);
        }
        String key = sourceKey(index, id);
        for (int attempt = 0; attempt < 64; attempt++) {
            String existing = metadata.get(key);
            if (existing == null) throw new IllegalArgumentException("Document not found: " + id);
            JSONObject merged = JSON.parseObject(existing);
            if (merged.getBooleanValue(DELETED)) throw new IllegalArgumentException("Document not found: " + id);
            mutation.accept(merged);
            if (merged.containsKey(DELETED) || !id.equals(merged.getString("uuid"))) {
                throw new IllegalArgumentException("Document identity cannot change");
            }
            ensureResourceLive(merged);
            luceneDocument(merged);
            if (metadata.compareAndSet(key, existing, merged.toJSONString())) {
                rejectRacingDeletion(index, merged);
                synchronizeProjection();
                return merged;
            }
        }
        throw new IllegalStateException("Concurrent document modifications exceeded retry budget");
    }

    public void delete(String index, String id) {
        if ("image-host".equals(index)) {
            if (get("full-text", id) != null) {
                mutate("full-text", id, source -> source.remove("isPublicAccess"));
            }
            return;
        }
        // A tombstone occupies the SAME CAS key as the document. A delayed upsert
        // cannot race a separate tombstone lookup and resurrect a deleted UUID.
        JSONObject tombstone = new JSONObject();
        tombstone.put("uuid", id);
        tombstone.put(DELETED, true);
        metadata.put(sourceKey(index, id), tombstone.toJSONString());
        synchronizeProjection();
    }

    public void deleteAll(String index) {
        validateIndex(index);
        if ("image-host".equals(index)) {
            for (String key : metadata.scan(SOURCE_PREFIX + "full-text/").keySet()) {
                String id = key.substring((SOURCE_PREFIX + "full-text/").length());
                if (get("image-host", id) != null) delete("image-host", id);
            }
            return;
        }
        Map<String, String> tombstones = new TreeMap<>();
        metadata.scan(SOURCE_PREFIX + index + "/").forEach((key, raw) -> {
            JSONObject tombstone = new JSONObject();
            tombstone.put("uuid", JSON.parseObject(raw).getString("uuid"));
            tombstone.put(DELETED, true);
            tombstones.put(key, tombstone.toJSONString());
        });
        metadata.writeBatch(tombstones, Collections.emptySet());
        synchronizeProjection();
    }

    public synchronized SearchPage search(String index, String query, List<String> tags,
                                          int offset, int limit, boolean mainResourcesOnly,
                                          String sortField, boolean descending) {
        if (offset < 0 || limit < 0 || limit > 10000) throw new IllegalArgumentException("Invalid pagination");
        try {
            synchronizeProjection();
            IndexState state = index(index);
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            Query text = query == null || query.isBlank() ? new MatchAllDocsQuery()
                    : new QueryParser("text", analyzer).parse(QueryParser.escape(query.trim()));
            builder.add(text, BooleanClause.Occur.MUST);
            if (tags != null) {
                for (String tag : tags) {
                    if (tag != null && !tag.isBlank()) {
                        builder.add(new TermQuery(new Term("tags", tag.trim())), BooleanClause.Occur.FILTER);
                    }
                }
            }
            if (mainResourcesOnly) {
                builder.add(new TermQuery(new Term("main", "true")), BooleanClause.Occur.FILTER);
            }
            Query request = builder.build();
            IndexSearcher searcher = state.searchers.acquire();
            try {
                int total = searcher.count(request);
                List<JSONObject> hits = new ArrayList<>();
                if (limit > 0 && offset < total) {
                    int fetch = (int) Math.min(total, (long) offset + limit);
                    Sort sort = sort(sortField, descending, query != null && !query.isBlank());
                    TopFieldDocs top = searcher.search(request, fetch, sort);
                    for (int i = offset; i < top.scoreDocs.length; i++) {
                        JSONObject hit = JSON.parseObject(searcher.storedFields().document(top.scoreDocs[i].doc).get("source"));
                        if ("image-host".equals(index)) hit = get("image-host", hit.getString("uuid"));
                        if (hit != null) hits.add(hit);
                    }
                }
                return new SearchPage(hits, total, offset, limit, visibleRevision);
            } finally {
                state.searchers.release(searcher);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedded search failed", e);
        }
    }

    private Sort sort(String field, boolean descending, boolean hasQuery) {
        if (field == null || field.isBlank()) {
            return hasQuery ? new Sort(SortField.FIELD_SCORE, new SortField("uuidSort", SortField.Type.STRING))
                    : new Sort(new SortField("createdSort", SortField.Type.LONG, true),
                    new SortField("uuidSort", SortField.Type.STRING));
        }
        switch (field) {
            case "fileCreatedAt": case "createdAt":
                return new Sort(new SortField("createdSort", SortField.Type.LONG, descending),
                        new SortField("uuidSort", SortField.Type.STRING));
            case "size":
                return new Sort(new SortField("sizeSort", SortField.Type.LONG, descending),
                        new SortField("uuidSort", SortField.Type.STRING));
            case "title": case "fileName":
                return new Sort(new SortField("nameSort", SortField.Type.STRING, descending),
                        new SortField("uuidSort", SortField.Type.STRING));
            default: throw new IllegalArgumentException("Unsupported sort field: " + field);
        }
    }

    public synchronized Map<String, Integer> tags(String index) {
        synchronizeProjection();
        Map<String, Integer> counts = new TreeMap<>();
        String prefix = SOURCE_PREFIX + validateIndex(index) + "/";
        projectedSources.forEach((key, raw) -> {
            if (!key.startsWith(prefix)) return;
            JSONObject doc = JSON.parseObject(raw);
            if ("image-host".equals(index)) doc = get(index, doc.getString("uuid"));
            if (doc == null) return;
            if (!doc.getBooleanValue(DELETED) && !isResourceDeleted(doc, resourceTombstones)) {
                for (String tag : documentTags(doc)) counts.merge(tag, 1, Integer::sum);
            }
        });
        return counts;
    }

    public synchronized List<Map<String, Object>> indexes() {
        synchronizeProjection();
        List<Map<String, Object>> result = new ArrayList<>();
        indexes.forEach((name, state) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", name);
            item.put("primaryKey", "uuid");
            item.put("numberOfDocuments", state.writer.getDocStats().numDocs);
            item.put("visibleRevision", visibleRevision);
            item.put("engine", "lucene");
            result.add(item);
        });
        return result;
    }

    public synchronized String randomPoster(String index) {
        synchronizeProjection();
        String chosen = null;
        String chosenId = null;
        int count = 0;
        String prefix = SOURCE_PREFIX + validateIndex(index) + "/";
        for (Map.Entry<String, String> entry : projectedSources.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            JSONObject doc = JSON.parseObject(entry.getValue());
            if ("image-host".equals(index)) doc = get(index, doc.getString("uuid"));
            if (doc == null) continue;
            String poster = doc.getString("poster");
            if (!doc.getBooleanValue(DELETED) && !isResourceDeleted(doc, resourceTombstones) && poster != null && !poster.isBlank()
                    && ThreadLocalRandom.current().nextInt(++count) == 0) {
                chosen = poster;
                chosenId = doc.getString("uuid");
            }
        }
        if ("image-host".equals(index) && chosen != null) {
            // Recheck policy at selection time as well as during candidate enumeration.
            JSONObject current = get("image-host", chosenId);
            return current == null ? null : current.getString("poster");
        }
        return chosen;
    }

    @Scheduled(fixedDelayString = "${bootfs.search.refresh-ms:500}")
    public void refreshInBackground() {
        try {
            synchronizeProjection();
            if (projectionFailure != null) log.info("Embedded search projection recovered");
            projectionFailure = null;
        } catch (RuntimeException e) {
            String failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!failure.equals(projectionFailure)) log.warn("Embedded search projection could not catch up", e);
            projectionFailure = failure;
        }
    }

    public String projectionFailure() { return projectionFailure; }
    public synchronized long visibleRevision() { return visibleRevision; }

    /** At-least revision barrier; it does not create a historical query snapshot. */
    public boolean awaitVisibleRevision(long minimumRevision, long timeoutMillis) {
        if (minimumRevision < 0 || timeoutMillis < 0 || timeoutMillis > 30000) {
            throw new IllegalArgumentException("Revision must be nonnegative and timeout must be 0..30000ms");
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            synchronizeProjection();
            if (visibleRevision() >= minimumRevision) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                Thread.sleep(Math.min(25, Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for search revision", interrupted);
            }
        }
    }

    public synchronized void rebuild() {
        try {
            for (IndexState state : indexes.values()) {
                state.writer.deleteAll();
                state.writer.commit();
                state.searchers.maybeRefreshBlocking();
            }
            projectedSources = new TreeMap<>();
            visibleRevision = -1;
            synchronizeProjection();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot rebuild search projection", e);
        }
    }

    private synchronized void synchronizeProjection() {
        if (closed) throw new IllegalStateException("Search engine is closed");
        long revisionBeforeScan = metadata.revision();
        if (revisionBeforeScan == visibleRevision) return;
        adoptLegacyGalleryDocuments();
        NavigableMap<String, String> sources = new TreeMap<>(metadata.scan(SOURCE_PREFIX));
        if (metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore local) {
            String prefix="/nodes/"+local.nodeId()+"/catalog/";
            Set<String> inventory=new HashSet<>(); metadata.scan(prefix).keySet().forEach(key -> inventory.add(key.substring(prefix.length())));
            sources.keySet().removeIf(key -> !inventory.contains(key.substring(key.lastIndexOf('/')+1)));
        }
        // The historic gallery namespace is an archival input. Its live view is
        // generated exclusively from canonical resource policy and metadata.
        sources.keySet().removeIf(key -> key.startsWith(SOURCE_PREFIX + "image-host/"));
        Map<String, String> gallery = new TreeMap<>();
        sources.forEach((key, raw) -> {
            if (!key.startsWith(SOURCE_PREFIX + "full-text/")) return;
            JSONObject source = JSON.parseObject(raw);
            if (!source.getBooleanValue(DELETED) && isPublic(source)) {
                gallery.put(SOURCE_PREFIX + "image-host/" + source.getString("uuid"), galleryDocument(source).toJSONString());
            }
        });
        sources.putAll(gallery);
        Set<String> tombstones = new HashSet<>();
        metadata.scan("/tombstones/").keySet().forEach(key -> tombstones.add(key.substring("/tombstones/".length())));
        boolean resourceDeletionChanged = !tombstones.equals(resourceTombstones);
        try {
            Set<String> changed = new TreeSet<>(projectedSources.keySet());
            changed.addAll(sources.keySet());
            Set<IndexState> touched = new HashSet<>();
            for (String key : changed) {
                String raw = sources.get(key);
                if (Objects.equals(raw, projectedSources.get(key)) && !resourceDeletionChanged) continue;
                String relative = key.substring(SOURCE_PREFIX.length());
                int separator = relative.indexOf('/');
                if (separator <= 0) throw new IllegalStateException("Invalid canonical document key: " + key);
                String indexName = relative.substring(0, separator);
                String id = relative.substring(separator + 1);
                IndexState state = index(indexName);
                if (raw == null || JSON.parseObject(raw).getBooleanValue(DELETED)
                        || isResourceDeleted(JSON.parseObject(raw), tombstones)) {
                    state.writer.deleteDocuments(new Term("uuid", id));
                } else {
                    JSONObject source = JSON.parseObject(raw);
                    if (!id.equals(source.getString("uuid"))) throw new IllegalStateException("Mismatched document identity");
                    state.writer.updateDocument(new Term("uuid", id), luceneDocument(source));
                }
                touched.add(state);
            }
            for (IndexState state : touched) {
                state.writer.commit();
                state.searchers.maybeRefreshBlocking();
            }
            projectedSources = new TreeMap<>(sources);
            resourceTombstones = tombstones;
            // Using the revision BEFORE the scan cannot skip a concurrent mutation.
            visibleRevision = revisionBeforeScan;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot update search projection", e);
        }
    }

    private Document luceneDocument(JSONObject source) {
        Document doc = new Document();
        String id = source.getString("uuid");
        doc.add(new StringField("uuid", id, Field.Store.NO));
        doc.add(new SortedDocValuesField("uuidSort", new BytesRef(id)));
        doc.add(new StoredField("source", source.toJSONString()));
        StringBuilder text = new StringBuilder(id);
        for (String field : List.of("title", "fileName", "author", "description", "text", "prompt", "metadata")) {
            Object value = source.get(field);
            if (value != null) text.append(' ').append(value);
        }
        for (String tag : documentTags(source)) {
            if (tag.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32766) {
                throw new IllegalArgumentException("Tag exceeds Lucene term length limit");
            }
            doc.add(new StringField("tags", tag, Field.Store.NO));
            text.append(' ').append(tag);
        }
        doc.add(new TextField("text", text.toString(), Field.Store.NO));
        doc.add(new StringField("main", Boolean.toString(isBlank(source.getString("copyOf"))
                && isBlank(source.getString("parent"))), Field.Store.NO));
        doc.add(new NumericDocValuesField("createdSort", timestamp(source)));
        doc.add(new NumericDocValuesField("sizeSort", source.getLongValue("size")));
        String name = source.getString("fileName");
        if (name == null) name = source.getString("title");
        if (name == null) name = "";
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32766) {
            throw new IllegalArgumentException("Filename exceeds Lucene sort term length limit");
        }
        doc.add(new SortedDocValuesField("nameSort", new BytesRef(name.toLowerCase(Locale.ROOT))));
        return doc;
    }

    private long timestamp(JSONObject source) {
        Object value = source.get("fileCreatedAt");
        if (value == null) value = source.get("createdAt");
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return java.time.Instant.parse((String) value).toEpochMilli(); }
            catch (java.time.DateTimeException ignored) {
                try { return java.time.LocalDateTime.parse((String) value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(); }
                catch (java.time.DateTimeException invalid) { return 0; }
            }
        }
        return 0;
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    private boolean isPublic(JSONObject source) {
        String flag = source.getString("isPublicAccess");
        return "1".equals(flag) || "true".equalsIgnoreCase(flag);
    }

    private JSONObject galleryDocument(JSONObject canonical) {
        JSONObject gallery = JSON.parseObject(canonical.toJSONString());
        String poster = gallery.getString("publicUrl");
        JSONObject legacy = gallery.getJSONObject("legacyImageHost");
        if (poster == null && legacy != null) poster = legacy.getString("poster");
        if (poster != null) gallery.put("poster", poster);
        return gallery;
    }

    /** Adopt historic gallery-only fields once, preserving conflicting originals as provenance. */
    private void adoptLegacyGalleryDocuments() {
        metadata.scan(SOURCE_PREFIX + "image-host/").forEach((key, raw) -> {
            JSONObject legacy = JSON.parseObject(raw);
            if (legacy.getBooleanValue(DELETED) || isResourceDeleted(legacy)) return;
            String id = legacy.getString("uuid");
            String canonicalKey = sourceKey("full-text", id);
            for (int attempt = 0; attempt < 64; attempt++) {
                String previous = metadata.get(canonicalKey);
                JSONObject canonical = previous == null ? new JSONObject() : JSON.parseObject(previous);
                if (canonical.getBooleanValue(DELETED) || canonical.containsKey("legacyImageHost")) return;
                JSONObject merged = JSON.parseObject(canonical.toJSONString());
                legacy.forEach((field, value) -> {
                    if (value != null && !merged.containsKey(field)) merged.put(field, value);
                });
                Set<String> tags = new LinkedHashSet<>(documentTags(canonical));
                tags.addAll(documentTags(legacy));
                if (!tags.isEmpty()) merged.put("tags", JSONArray.from(tags));
                Map<String, JSONObject> tagMetadata = tagDetails(canonical);
                tagDetails(legacy).forEach(tagMetadata::putIfAbsent);
                if (!tagMetadata.isEmpty()) merged.put("tagMetadata", JSONArray.from(tagMetadata.values()));
                if (!canonical.containsKey("isPublicAccess") && !legacy.containsKey("isPublicAccess")) {
                    merged.put("isPublicAccess", "1");
                }
                if (!merged.containsKey("publicUrl") && legacy.getString("poster") != null) {
                    merged.put("publicUrl", legacy.getString("poster"));
                }
                merged.put("legacyImageHost", legacy);
                if (isResourceDeleted(merged)) return;
                luceneDocument(merged);
                if (metadata.compareAndSet(canonicalKey, previous, merged.toJSONString())) return;
            }
            throw new IllegalStateException("Legacy gallery adoption exceeded CAS retry budget: " + id);
        });
    }

    private boolean isResourceDeleted(JSONObject doc) {
        for (String field : List.of("uuid", "parent", "copyOf")) {
            String id = doc.getString(field);
            if (!isBlank(id) && metadata.get("/tombstones/" + id) != null) return true;
        }
        return false;
    }

    private boolean isResourceDeleted(JSONObject doc, Set<String> tombstones) {
        for (String field : List.of("uuid", "parent", "copyOf")) {
            if (tombstones.contains(doc.getString(field))) return true;
        }
        return false;
    }

    private void ensureResourceLive(JSONObject doc) {
        if (isResourceDeleted(doc)) throw new IllegalStateException("Resource or its parent was deleted: " + doc.getString("uuid"));
    }

    private void rejectRacingDeletion(String index, JSONObject doc) {
        if (isResourceDeleted(doc)) {
            delete(index, doc.getString("uuid"));
            throw new IllegalStateException("Resource was deleted while applying document mutation");
        }
    }

    private Set<String> documentTags(JSONObject source) {
        Set<String> tags = new LinkedHashSet<>();
        JSONArray values = source.getJSONArray("tags");
        if (values != null) for (Object value : values) {
            if (value instanceof String) tags.add((String) value);
            else if (value instanceof Map) {
                Map<?, ?> tag = (Map<?, ?>) value;
                Object name = tag.get("name");
                Object namespace = tag.get("namespace");
                if (name != null) tags.add((namespace == null ? "manual" : namespace) + ":" + name);
            }
        }
        return tags;
    }

    private Map<String, JSONObject> tagDetails(JSONObject source) {
        Map<String, JSONObject> details = new LinkedHashMap<>();
        for (String field : List.of("tagMetadata", "tags")) {
            JSONArray values = source.getJSONArray(field);
            if (values == null) continue;
            for (Object value : values) {
                if (!(value instanceof Map)) continue;
                JSONObject tag = JSON.parseObject(JSON.toJSONString(value));
                if (tag.getString("name") == null) continue;
                if (isBlank(tag.getString("namespace"))) tag.put("namespace", "manual");
                details.putIfAbsent(tag.getString("namespace") + ":" + tag.getString("name"), tag);
            }
        }
        return details;
    }

    private String sourceKey(String index, String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.length() > 1024) {
            throw new IllegalArgumentException("Invalid document UUID");
        }
        return SOURCE_PREFIX + validateIndex(index) + "/" + id;
    }

    private String validateIndex(String index) {
        if (index == null || !index.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid index name");
        return index;
    }

    private IndexState index(String name) throws IOException {
        validateIndex(name);
        IndexState existing = indexes.get(name);
        if (existing != null) return existing;
        Directory directory = FSDirectory.open(root.resolve(name));
        IndexWriter writer = new IndexWriter(directory,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE));
        IndexState state = new IndexState(directory, writer, new SearcherManager(writer, null));
        indexes.put(name, state);
        return state;
    }

    @Override
    @PreDestroy
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (IndexState state : indexes.values()) {
            try { state.close(); } catch (IOException e) { failure = e; }
        }
        analyzer.close();
        if (failure != null) throw failure;
    }

    private static class IndexState implements AutoCloseable {
        final Directory directory;
        final IndexWriter writer;
        final SearcherManager searchers;
        IndexState(Directory directory, IndexWriter writer, SearcherManager searchers) {
            this.directory = directory; this.writer = writer; this.searchers = searchers;
        }
        public void close() throws IOException {
            try { searchers.close(); } finally { try { writer.close(); } finally { directory.close(); } }
        }
    }

    public static class SearchPage {
        public final List<JSONObject> hits;
        public final int total, offset, limit;
        public final long visibleRevision;
        SearchPage(List<JSONObject> hits, int total, int offset, int limit, long visibleRevision) {
            this.hits = hits; this.total = total; this.offset = offset; this.limit = limit; this.visibleRevision = visibleRevision;
        }
    }
}
