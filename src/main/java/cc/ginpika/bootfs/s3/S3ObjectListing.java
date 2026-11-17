package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@RequiredArgsConstructor
class S3ObjectListing {
    private final ObjectKeyRepository bindings;

    /** Filter before pagination; a delimiter group consumes one result, including across pages. */
    S3ListPage listV2(String bucket, String prefix, String delimiter, int maxKeys, String continuationToken) {
        String requestedPrefix = prefix == null ? "" : prefix;
        String requestedDelimiter = delimiter == null ? "" : delimiter;
        String afterKey = continuationToken == null || continuationToken.isEmpty() ? null
                : decodeToken(continuationToken, bucket, requestedPrefix, requestedDelimiter);
        S3ListPage page = listIndexAfterKey(bucket, requestedPrefix, requestedDelimiter, maxKeys, afterKey);
        if (page.isTruncated()) {
            page.setNextContinuationToken(encodeToken(bucket, requestedPrefix, requestedDelimiter, page.getNextMarker()));
        }
        page.setNextMarker(null);
        return page;
    }

    /** ListObjects V1 uses the last emitted key or common prefix as its plain marker. */
    S3ListPage listV1(String bucket, String prefix, String delimiter, int maxKeys, String marker) {
        return listIndexAfterKey(bucket, prefix == null ? "" : prefix, delimiter == null ? "" : delimiter,
                maxKeys, marker == null || marker.isEmpty() ? null : marker);
    }

    private S3ListPage listIndexAfterKey(String bucket, String requestedPrefix, String requestedDelimiter,
                                        int maxKeys, String afterKey) {
        int limit = Math.max(0, Math.min(1000, maxKeys));
        TreeMap<String, ObjectKeyBinding> entries = new TreeMap<>();
        bindings.visibleBindings(bucket, requestedPrefix).forEach((key, object) -> {
            int group = requestedDelimiter.isEmpty() ? -1 : key.indexOf(requestedDelimiter, requestedPrefix.length());
            if (group >= 0) {
                entries.put(key.substring(0, group + requestedDelimiter.length()), null);
            } else {
                entries.put(key, object);
            }
        });
        NavigableMap<String, ObjectKeyBinding> pageEntries = entries;
        if (afterKey != null) {
            pageEntries = entries.tailMap(afterKey, false);
        }
        List<ObjectKeyBinding> objects = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        int emitted = 0;
        String last = null;
        for (Map.Entry<String, ObjectKeyBinding> entry : pageEntries.entrySet()) {
            if (emitted == limit) break;
            if (entry.getValue() == null) prefixes.add(entry.getKey());
            else objects.add(entry.getValue());
            last = entry.getKey();
            emitted++;
        }
        boolean truncated = limit > 0 && pageEntries.size() > emitted;
        return S3ListPage.builder().contents(objects).commonPrefixes(prefixes).truncated(truncated)
                .nextMarker(truncated ? last : null).build();
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
