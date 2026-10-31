package cc.ginpika.bootfs.kernel.migration;

import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Explicit, resumable offline import. Never connects to or modifies legacy services. */
@Component("legacyMetadataImport")
public class LegacyMetadataImport {
    private final MetadataStore store;
    private final String file;
    public LegacyMetadataImport(MetadataStore store,@Value("${bootfs.migration.import-file:}") String file) {
        this.store=store; this.file=file;
    }
    @PostConstruct
    public void run() throws IOException {
        if (file.isBlank()) return;
        byte[] bytes=Files.readAllBytes(Path.of(file));
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        String marker="/migration/import/"+digest;
        if (store.get(marker)!=null) return;
        JSONObject export=JSON.parseObject(bytes);
        if (export.getIntValue("formatVersion")!=1) throw new IOException("Unsupported legacy export version");
        Map<String,String> entries=new TreeMap<>();
        JSONObject metadata=export.getJSONObject("metadata");
        if (metadata!=null) metadata.forEach((key,value) -> {
            if (!key.startsWith("/files/") && !key.startsWith("/s3/") && !key.startsWith("/properties/")) {
                throw new IllegalArgumentException("Legacy import key is outside supported resource namespaces: "+key);
            }
            if (!(value instanceof String)) throw new IllegalArgumentException("Metadata values must be strings");
            entries.put(key,(String)value);
        });
        JSONObject indexes=export.getJSONObject("documents");
        if (indexes!=null) for (String index:indexes.keySet()) {
            if (!index.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Invalid source index");
            for (Object value:indexes.getJSONArray(index)) {
                JSONObject doc=(JSONObject)value;
                String id=doc.getString("uuid");
                if (id==null || !id.matches("[A-Za-z0-9_-]+") || doc.containsKey("_bootfsDeleted")) {
                    throw new IOException("Invalid legacy document identity");
                }
                entries.put("/documents/"+index+"/"+id,doc.toJSONString());
            }
        }
        // Preflight every conflict before writing; resumptions accept exactly matching records only.
        for (Map.Entry<String,String> entry:entries.entrySet()) {
            String existing=store.get(entry.getKey());
            if (existing!=null && !existing.equals(entry.getValue())) {
                throw new IOException("Import conflicts with existing authoritative state: "+entry.getKey());
            }
        }
        for (Map.Entry<String,String> entry:entries.entrySet()) {
            if (!store.compareAndSet(entry.getKey(),null,entry.getValue())) {
                String current=store.get(entry.getKey());
                if (!entry.getValue().equals(current)) {
                    throw new IOException("Import conflicts with concurrently updated authoritative state: "+entry.getKey());
                }
            }
        }
        if (!store.compareAndSet(marker,null,"complete") && !"complete".equals(store.get(marker))) {
            throw new IOException("Import completion marker conflicts with authoritative state");
        }
    }
}
