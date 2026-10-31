package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Protocol bindings only. Resource publication and deletion live in FileService. */
@Component
@ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3KeyIndex {
    private final EtcdService etcdService;

    private String indexKey(String bucket, String key) { return bucketPrefix(bucket) + key; }
    private String bucketPrefix(String bucket) { return "/s3/" + bucket + "/"; }

    public void putIndex(String bucket, String key, S3ObjectMeta meta) {
        etcdService.put(indexKey(bucket, key), JSON.toJSONString(meta));
    }

    public S3ObjectMeta getIndex(String bucket, String key) {
        String value = etcdService.getOne(indexKey(bucket, key));
        return value == null ? null : JSON.parseObject(value, S3ObjectMeta.class);
    }

    public boolean deleteIndex(String bucket, String key) { return etcdService.delete(indexKey(bucket, key)); }

    public String rawIndex(String bucket, String key) { return etcdService.getOne(indexKey(bucket, key)); }

    public boolean compareAndSet(String bucket, String key, String expected, S3ObjectMeta replacement) {
        return etcdService.compareAndSet(indexKey(bucket, key), expected,
                replacement == null ? null : JSON.toJSONString(replacement));
    }

    public void deleteBindingsForResource(String uuid) {
        etcdService.scan("/s3/").forEach((key, value) -> {
            S3ObjectMeta meta = JSON.parseObject(value, S3ObjectMeta.class);
            if (uuid.equals(meta.getUuid())) etcdService.compareAndSet(key, value, null);
        });
    }

    public Set<String> boundResourceIds() {
        Set<String> result = new HashSet<>();
        etcdService.scan("/s3/").values().forEach(value -> result.add(JSON.parseObject(value, S3ObjectMeta.class).getUuid()));
        return result;
    }

    /** Filter before pagination; a delimiter group consumes one result, including across pages. */
    public S3ListPage listIndex(String bucket, String prefix, String delimiter, int maxKeys, String continuationToken) {
        String requestedPrefix = prefix == null ? "" : prefix;
        String requestedDelimiter = delimiter == null ? "" : delimiter;
        int limit = Math.max(0, Math.min(1000, maxKeys));
        TreeMap<String, S3ObjectMeta> entries = new TreeMap<>();
        String base = bucketPrefix(bucket);
        Set<String> deleted = new HashSet<>();
        etcdService.scan("/tombstones/").keySet().forEach(key -> deleted.add(key.substring("/tombstones/".length())));
        etcdService.scan(base + requestedPrefix).forEach((storedKey, value) -> {
            S3ObjectMeta object = JSON.parseObject(value, S3ObjectMeta.class);
            if (deleted.contains(object.getUuid())) return;
            String key = storedKey.substring(base.length());
            int group = requestedDelimiter.isEmpty() ? -1 : key.indexOf(requestedDelimiter, requestedPrefix.length());
            if (group >= 0) {
                entries.put(key.substring(0, group + requestedDelimiter.length()), null);
            } else {
                entries.put(key, object);
            }
        });
        NavigableMap<String, S3ObjectMeta> pageEntries = entries;
        if (continuationToken != null && !continuationToken.isEmpty()) {
            String last = decodeToken(continuationToken, bucket, requestedPrefix, requestedDelimiter);
            pageEntries = entries.tailMap(last, false);
        }
        List<S3ObjectMeta> objects = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        int emitted = 0;
        String last = null;
        for (Map.Entry<String, S3ObjectMeta> entry : pageEntries.entrySet()) {
            if (emitted == limit) break;
            if (entry.getValue() == null) prefixes.add(entry.getKey());
            else objects.add(entry.getValue());
            last = entry.getKey();
            emitted++;
        }
        boolean truncated = limit > 0 && pageEntries.size() > emitted;
        return S3ListPage.builder().contents(objects).commonPrefixes(prefixes).truncated(truncated)
                .nextContinuationToken(truncated ? encodeToken(bucket, requestedPrefix, requestedDelimiter, last) : null).build();
    }

    private String encodeToken(String bucket, String prefix, String delimiter, String last) {
        JSONObject token = new JSONObject();
        token.put("bucket", bucket); token.put("prefix", prefix); token.put("delimiter", delimiter); token.put("last", last);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private String decodeToken(String raw, String bucket, String prefix, String delimiter) {
        try {
            JSONObject token = JSON.parseObject(new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
            if (!bucket.equals(token.getString("bucket")) || !prefix.equals(token.getString("prefix"))
                    || !delimiter.equals(token.getString("delimiter")) || token.getString("last") == null) {
                throw new IllegalArgumentException("Continuation token does not match the listing");
            }
            return token.getString("last");
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid continuation token", failure);
        }
    }
}
