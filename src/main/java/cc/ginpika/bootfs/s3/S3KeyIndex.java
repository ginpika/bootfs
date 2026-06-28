package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSON;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.options.GetOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S3 key <-> uuid 映射,存于 etcd /s3/{bucket}/{key}。
 * key 中的 / 保留不编码,与 S3 prefix 语义一致。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
public class S3KeyIndex {

    @Autowired
    private EtcdService etcdService;

    private String indexKey(String bucket, String key) {
        return "/s3/" + bucket + "/" + key;
    }

    private String bucketPrefix(String bucket) {
        return "/s3/" + bucket + "/";
    }

    /** rangeEnd:/s3/{bucket}0 (0x30 > / 0x2F),覆盖整个 bucket。 */
    private String bucketRangeEnd(String bucket) {
        return "/s3/" + bucket + "0";
    }

    public void putIndex(String bucket, String key, S3ObjectMeta meta) {
        etcdService.put(indexKey(bucket, key), JSON.toJSONString(meta));
    }

    public S3ObjectMeta getIndex(String bucket, String key) {
        String value = etcdService.getOne(indexKey(bucket, key));
        if (value == null) return null;
        return JSON.parseObject(value, S3ObjectMeta.class);
    }

    public boolean deleteIndex(String bucket, String key) {
        return etcdService.delete(indexKey(bucket, key));
    }

    /**
     * 列举 bucket 下匹配 prefix 的 key。
     * MVP 取全部匹配 key 内存分组(单 bucket 小规模足够),MVP2 改 jetcd limit + cursor。
     */
    public S3ListPage listIndex(String bucket, String prefix, String delimiter,
                                int maxKeys, String continuationToken) {
        String startKey = continuationToken != null
                ? continuationToken
                : bucketPrefix(bucket) + (prefix == null ? "" : prefix);
        String rangeEnd = bucketRangeEnd(bucket);

        KV kv = etcdService.client.getKVClient();
        List<KeyValue> kvs;
        try {
            GetOption opt = GetOption.builder()
                    .withRange(ByteSequence.from(rangeEnd.getBytes(StandardCharsets.UTF_8)))
                    .build();
            kvs = kv.get(ByteSequence.from(startKey.getBytes(StandardCharsets.UTF_8)), opt)
                    .join().getKvs();
            kvs.sort(Comparator.comparing(kv2 -> kv2.getKey().toString()));
        } finally {
            kv.close();
        }

        List<S3ObjectMeta> contents = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();
        boolean truncated = false;
        String nextToken = null;
        boolean skipToken = continuationToken != null;
        int emitted = 0;
        int pfxLen = prefix == null ? 0 : prefix.length();
        String bp = bucketPrefix(bucket);

        for (KeyValue kv2 : kvs) {
            String etcdKeyStr = kv2.getKey().toString();
            if (skipToken && etcdKeyStr.equals(continuationToken)) {
                skipToken = false;
                continue;
            }
            skipToken = false;

            if (emitted >= maxKeys) {
                truncated = true;
                nextToken = etcdKeyStr;
                break;
            }

            String s3Key = etcdKeyStr.startsWith(bp) ? etcdKeyStr.substring(bp.length()) : etcdKeyStr;
            String commonPrefix = null;
            if (delimiter != null && !delimiter.isEmpty()) {
                int delimIdx = s3Key.indexOf(delimiter, pfxLen);
                if (delimIdx >= 0) {
                    commonPrefix = s3Key.substring(0, delimIdx + delimiter.length());
                }
            }

            if (commonPrefix != null) {
                if (seenPrefixes.contains(commonPrefix)) {
                    continue;
                }
                seenPrefixes.add(commonPrefix);
                commonPrefixes.add(commonPrefix);
                emitted++;
            } else {
                S3ObjectMeta meta = JSON.parseObject(kv2.getValue().toString(), S3ObjectMeta.class);
                contents.add(meta);
                emitted++;
            }
        }

        return S3ListPage.builder()
                .contents(contents)
                .commonPrefixes(commonPrefixes)
                .truncated(truncated)
                .nextContinuationToken(nextToken)
                .build();
    }
}
