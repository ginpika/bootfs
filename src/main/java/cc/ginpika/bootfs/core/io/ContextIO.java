package cc.ginpika.bootfs.core.io;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Synchronous durable local inventory. Its namespace belongs to one stable node identity. */
@Service
@DependsOn("legacyMetadataImport")
public class ContextIO {
    private final MetadataStore store;
    private final TfsConfig config;
    private final String prefix;
    private final boolean allowCatalogOnly;
    public ContextIO(MetadataStore store, TfsConfig config, NodeIdentity identity) {
        this(store,config,identity,false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ContextIO(MetadataStore store, TfsConfig config, NodeIdentity identity,
                     @org.springframework.beans.factory.annotation.Value("${bootfs.migration.allow-catalog-only:false}") boolean allowCatalogOnly) {
        this.store = store;
        this.config = config;
        this.prefix = "/nodes/"+identity.id()+"/catalog/";
        this.allowCatalogOnly = allowCatalogOnly;
    }
    public void append(String id, FileObject file) { update(id,file); }
    public void update(String id, FileObject file) {
        if (file.getContentObjectId() != null && !file.getContentObjectId().matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid content identity");
        }
        if (deleted(file)) throw new IllegalStateException("Resource was deleted: "+id);
        String route = store.get("/files/" + id);
        String own = config.getWebEntrypoint() == null ? "" : config.getWebEntrypoint().replaceAll("/+$", "");
        if (store.get(prefix + id) == null && route != null && !route.equals(own + "/f/" + id)) {
            throw new IllegalStateException("Cannot revive another node's resource from an obsolete local task: " + id);
        }
        JSONObject json = fileObjectToJsonObject(file);
        json.remove("path"); // Paths are resolved against this node's data root, never a remote machine's path.
        store.put(prefix+id,json.toJSONString());
    }
    private boolean deleted(FileObject file) {
        for (String id : new String[]{file.getUuid(),file.getParent(),file.getCopyOf()}) {
            if (id != null && store.get("/tombstones/"+id)!=null) return true;
        }
        return false;
    }
    public void remove(String id) { store.delete(prefix+id); }
    public FileObject current(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) return null;
        String raw = store.get(prefix + id);
        if (raw == null) return null;
        FileObject file = JSONObject.parseObject(raw, FileObject.class);
        String contentId = file.getContentObjectId() == null ? id : file.getContentObjectId();
        if (!id.equals(file.getUuid()) || !contentId.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("Invalid catalog content identity");
        file.setPath(Path.of(config.getPathPrefix(), contentId).toString());
        return file;
    }
    public Map<String,FileObject> load() throws IOException {
        importLegacy();
        Map<String,FileObject> result = new LinkedHashMap<>();
        for (Map.Entry<String,String> entry : store.scan(prefix).entrySet()) {
            FileObject file = JSONObject.parseObject(entry.getValue(),FileObject.class);
            validateId(file.getUuid());
            if (!entry.getKey().equals(prefix+file.getUuid())) throw new IOException("Catalog identity mismatch: "+entry.getKey());
            String contentId = file.getContentObjectId() == null ? file.getUuid() : file.getContentObjectId();
            validateId(contentId);
            file.setPath(Path.of(config.getPathPrefix(),contentId).toString());
            result.put(file.getUuid(),file);
        }
        return result;
    }
    private void importLegacy() throws IOException {
        String marker = prefix.substring(0,prefix.length()-"catalog/".length())+"migration/db-json-v1";
        if (store.get(marker) != null) return;
        Path legacy = Path.of(config.getConfig());
        if (Files.exists(legacy) && Files.size(legacy)>0) {
            // Both passes consume the same immutable copy; a malformed tail cannot publish a partial catalog.
            Path snapshot=Files.createTempFile("bootfs-legacy-catalog-",".json");
            try {
                Files.copy(legacy,snapshot,StandardCopyOption.REPLACE_EXISTING);
                boolean allowed=allowCatalogOnly || !store.scan("/migration/import/").isEmpty();
                readLegacy(snapshot,(id,json) -> {
                    if (!allowed) throw new IOException("Legacy db.json detected. Export etcd and MeiliSearch and set bootfs.migration.import-file, or explicitly opt into catalog-only recovery with bootfs.migration.allow-catalog-only=true. Original files have not been changed.");
                    requireCompatible(prefix+id,json);
                });
                readLegacy(snapshot,(id,json) -> {
                    // Recheck at the mutation itself: another peer may have changed state after preflight.
                    String key=prefix+id;
                    for (int attempt=0;attempt<16;attempt++) {
                        if (store.compareAndSet(key,null,json.toJSONString())) return;
                        String existing=store.get(key);
                        if (existing!=null) { requireCompatible(key,existing,json); return; }
                    }
                    throw new IOException("Catalog changed repeatedly during import: "+id);
                });
            } finally { Files.deleteIfExists(snapshot); }
        }
        // Original db.json remains untouched for export and recovery.
        if (!store.compareAndSet(marker,null,"complete") && !"complete".equals(store.get(marker))) {
            throw new IOException("Legacy catalog migration marker conflicts with authoritative state");
        }
    }

    private void readLegacy(Path file,CatalogEntry entry) throws IOException {
        ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (JsonParser parser=mapper.getFactory().createParser(file.toFile())) {
            if (parser.nextToken()!=JsonToken.START_OBJECT) throw new IOException("Legacy db.json must be a JSON object");
            while (parser.nextToken()!=JsonToken.END_OBJECT) {
                if (parser.currentToken()!=JsonToken.FIELD_NAME) throw new IOException("Invalid or truncated legacy catalog entry");
                String id=parser.currentName();
                validateId(id);
                if (parser.nextToken()!=JsonToken.START_OBJECT) throw new IOException("Legacy catalog value must be an object: "+id);
                FileObject resource=mapper.readValue(parser,FileObject.class);
                if (resource.getUuid()==null) resource.setUuid(id);
                if (!id.equals(resource.getUuid())) throw new IOException("Legacy resource identity mismatch: "+id);
                validateId(resource.getUuid());
                for (String related:new String[]{resource.getParent(),resource.getCopyOf()}) {
                    if (related!=null && !related.isBlank()) validateId(related);
                }
                entry.accept(id,normalized(fileObjectToJsonObject(resource)));
            }
            if (parser.nextToken()!=null) throw new IOException("Trailing content in legacy catalog");
        }
    }
    private void requireCompatible(String key,JSONObject expected) throws IOException {
        String existing=store.get(key);
        if (existing==null) return;
        requireCompatible(key,existing,expected);
    }
    private void requireCompatible(String key,String existing,JSONObject expected) throws IOException {
        try {
            if (normalized(JSONObject.parseObject(existing)).equals(expected)) return;
        } catch (RuntimeException invalid) { throw new IOException("Invalid authoritative catalog record: "+key,invalid); }
        throw new IOException("Legacy catalog conflicts with authoritative state: "+key);
    }
    private static JSONObject normalized(JSONObject record) {
        JSONObject copy=JSONObject.parseObject(record.toJSONString());
        copy.remove("path"); copy.remove("url"); copy.remove("thumbUrl");
        return copy;
    }
    private static void validateId(String id) throws IOException {
        if (id==null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid catalog resource identity: "+id);
    }
    @FunctionalInterface private interface CatalogEntry {
        void accept(String id,JSONObject value) throws IOException;
    }
    public static JSONObject fileObjectToJsonObject(FileObject object) { return JSONObject.from(object); }
    public static FileObject jsonObjectToFileObject(JSONObject json) { return json.toJavaObject(FileObject.class); }
    public static UUID findLowIndexUUID() { return UUID.randomUUID(); }
    public static UUID findHighIndexUUID() { return UUID.randomUUID(); }
}
