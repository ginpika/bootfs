package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Startup migration that exposes existing top-level resources under filename-based object keys. */
@Service
public class ObjectKeyBootstrap {
    private final LocalFileInventory context;
    private final MetadataRegistry metadataRegistry;
    private final ObjectWritePolicy writePolicy;

    @Autowired(required = false)
    private ObjectKeyRepository objectKeys;

    @Autowired
    public ObjectKeyBootstrap(LocalFileInventory context, MetadataRegistry metadataRegistry, ObjectWritePolicy writePolicy) {
        this.context = context;
        this.metadataRegistry = metadataRegistry;
        this.writePolicy = writePolicy;
    }

    public ObjectKeyBootstrap(LocalFileInventory context, MetadataRegistry metadataRegistry,
                              ObjectWritePolicy writePolicy, ObjectKeyRepository objectKeys) {
        this(context, metadataRegistry, writePolicy);
        this.objectKeys = objectKeys;
    }

    /** Startup migration: expose original names and replace legacy automatic UUID keys without touching resource URLs. */
    public void bootstrapExistingBindings() {
        if (objectKeys == null) return;
        String bucket = writePolicy.bucket();
        Set<String> bound = objectKeys.boundResourceIds();
        for (FileObject resource : context.cachedFiles()) {
            if (resource.getParent() != null || resource.getCopyOf() != null) continue;
            if (resource.getPath() == null || metadataRegistry.getOne("/tombstones/" + resource.getUuid()) != null) continue;
            Path path = Path.of(resource.getPath());
            if (!Files.isRegularFile(path)) continue;
            String legacyRaw = objectKeys.findRaw(bucket, resource.getUuid());
            ObjectKeyBinding legacy = legacyRaw == null ? null : JSONObject.parseObject(legacyRaw, ObjectKeyBinding.class);
            boolean automaticUuidKey = legacy != null && resource.getUuid().equals(legacy.getUuid())
                    && resource.getUuid().equals(legacy.getKey());
            if (bound.contains(resource.getUuid()) && !automaticUuidKey) continue;
            try {
                ObjectKeyBinding binding = legacy;
                if (!automaticUuidKey) {
                    try (InputStream input = Files.newInputStream(path); DigestInputStream content = new DigestInputStream(input, md5())) {
                        content.transferTo(OutputStream.nullOutputStream());
                        binding = new ObjectKeyBinding(resource.getUuid(), Files.size(path), HexFormat.of().formatHex(content.getMessageDigest().digest()),
                                resource.getCreatedAt() == null ? Files.getLastModifiedTime(path).toMillis() : resource.getCreatedAt(),
                                null, contentType(resource.getFileName()));
                    }
                }
                ObjectKeyBinding named = objectKeys.bindFilename(bucket, resource.getFileName(), binding);
                // Publish the named key before removing the old one; a retry can safely finish either step.
                if (automaticUuidKey && !resource.getUuid().equals(named.getKey())) {
                    objectKeys.compareAndSet(bucket, resource.getUuid(), legacyRaw, null);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot expose existing resource through S3: " + resource.getUuid(), failure);
            }
        }
    }

    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String contentType(String filename) {
        String type = URLConnection.guessContentTypeFromName(filename);
        return type == null ? "application/octet-stream" : type;
    }
}
