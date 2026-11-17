package cc.ginpika.bootfs.file.binding;

import cc.ginpika.bootfs.metadata.MetadataRegistry;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Shared object-key bindings for file publication, folders and the S3 adapter. */
@Component
@RequiredArgsConstructor
public class ObjectKeyRepository {
    private final MetadataRegistry metadataRegistry;

    private String indexKey(String bucket, String key) { return bucketPrefix(bucket) + key; }
    private String bucketPrefix(String bucket) { return "/s3/" + bucket + "/"; }

    public void put(String bucket, String key, ObjectKeyBinding meta) {
        metadataRegistry.put(indexKey(bucket, key), JSON.toJSONString(meta));
    }

    public ObjectKeyBinding find(String bucket, String key) {
        String value = metadataRegistry.getOne(indexKey(bucket, key));
        return value == null ? null : JSON.parseObject(value, ObjectKeyBinding.class);
    }

    public boolean delete(String bucket, String key) { return metadataRegistry.delete(indexKey(bucket, key)); }

    public String findRaw(String bucket, String key) { return metadataRegistry.getOne(indexKey(bucket, key)); }

    public boolean compareAndSet(String bucket, String key, String expected, ObjectKeyBinding replacement) {
        return metadataRegistry.compareAndSet(indexKey(bucket, key), expected,
                replacement == null ? null : JSON.toJSONString(replacement));
    }

    public void deleteBindingsForResource(String uuid) {
        metadataRegistry.scan("/s3/").forEach((key, value) -> {
            ObjectKeyBinding meta = JSON.parseObject(value, ObjectKeyBinding.class);
            if (uuid.equals(meta.getUuid())) metadataRegistry.compareAndSet(key, value, null);
        });
    }

    public Set<String> boundResourceIds() {
        Set<String> result = new HashSet<>();
        metadataRegistry.scan("/s3/").values().forEach(value -> result.add(JSON.parseObject(value, ObjectKeyBinding.class).getUuid()));
        return result;
    }

    /** Browser uploads keep their names without replacing another upload with the same name. */
    public ObjectKeyBinding bindFilename(String bucket, String filename, ObjectKeyBinding resource) throws ResourceWriteConflictException {
        String base = bucketPrefix(bucket);
        // Recover the same key after a lost publication reply, even if a name collision has since disappeared.
        for (Map.Entry<String, String> entry : metadataRegistry.scan(base).entrySet()) {
            ObjectKeyBinding existing = JSON.parseObject(entry.getValue(), ObjectKeyBinding.class);
            if (resource.getUuid().equals(existing.getUuid())
                    && !entry.getKey().equals(base + resource.getUuid())) return existing;
        }
        String name = filename == null ? "unnamed" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "unnamed";
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int attempt = 0; attempt < 1000; attempt++) {
            String key = attempt == 0 ? name : stem + " (" + resource.getUuid()
                    + (attempt == 1 ? "" : "-" + attempt) + ")" + suffix;
            ObjectKeyBinding current = find(bucket, key);
            if (current != null && resource.getUuid().equals(current.getUuid())) return current;
            if (current != null) continue;
            ObjectKeyBinding binding = new ObjectKeyBinding(resource.getUuid(), resource.getSize(), resource.getEtag(),
                    resource.getLastModified(), key, resource.getContentType());
            if (compareAndSet(bucket, key, null, binding)) return binding;
            current = find(bucket, key);
            if (current != null && resource.getUuid().equals(current.getUuid())) return current;
            if (current == null) throw new ResourceWriteConflictException("Filename binding changed; retry publication");
        }
        throw new ResourceWriteConflictException("Cannot allocate an available object filename");
    }

    /** Keys are relative to the bucket. Tombstoned resource bindings are excluded. */
    public Map<String, ObjectKeyBinding> visibleBindings(String bucket, String prefix) {
        String base = bucketPrefix(bucket);
        Set<String> deleted = new HashSet<>();
        metadataRegistry.scan("/tombstones/").keySet().forEach(key -> deleted.add(key.substring("/tombstones/".length())));
        Map<String, ObjectKeyBinding> result = new LinkedHashMap<>();
        metadataRegistry.scan(base + prefix).forEach((storedKey, value) -> {
            ObjectKeyBinding binding = JSON.parseObject(value, ObjectKeyBinding.class);
            if (!deleted.contains(binding.getUuid())) result.put(storedKey.substring(base.length()), binding);
        });
        return result;
    }
}
