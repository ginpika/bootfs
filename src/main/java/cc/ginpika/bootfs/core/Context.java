package cc.ginpika.bootfs.core;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.*;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Node-local materialized inventory; durable writes are delegated to the metadata kernel. */
@Service
public class Context {
    private final TfsConfig config;
    private final ContextIO io;
    public final String uuid;
    public final Map<String,FileObject> STORAGE = new ConcurrentHashMap<>();
    public static final int STORAGE_CAPACITY = 4096000;
    private boolean loaded;
    public Context(TfsConfig config, ContextIO io, NodeIdentity identity) {
        this.config=config;
        this.io=io;
        this.uuid=identity.id();
    }
    @PostConstruct
    public synchronized void load() throws IOException {
        if (loaded) return;
        checkDataDir();
        Map<String,FileObject> files=io.load();
        files.values().forEach(this::fillCreatedAt);
        STORAGE.putAll(files);
        loaded=true;
    }
    public synchronized void reload() throws IOException {
        Map<String,FileObject> files = io.load(); files.values().forEach(this::fillCreatedAt);
        STORAGE.clear(); STORAGE.putAll(files);
    }
    public void checkDataDir() throws IOException { Files.createDirectories(Path.of(config.getPathPrefix())); }
    private String endpoint() {
        String value=config.getWebEntrypoint();
        return value==null || value.equals("/") ? "" : value.replaceAll("/+$","");
    }
    public String buildUrl(String id) { return endpoint()+"/f/"+id; }
    public String buildProxyUrl(String id) { return endpoint()+"/p/"+id; }
    public String buildThumbUrl(String id) { return endpoint()+"/thumb/"+id; }
    public String buildMetaJson(MultipartFile file) { return buildMetaJson(file,file.getOriginalFilename()); }
    public String buildMetaJson(MultipartFile file,String name) {
        int dot=name==null ? -1 : name.lastIndexOf('.');
        JSONObject json=new JSONObject();
        json.put("size",file.getSize());
        json.put("ext",dot<0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT));
        return json.toJSONString();
    }
    public String buildMetaJson4Replica(MultipartFile file,String name,String originalId) {
        JSONObject json=JSONObject.parseObject(buildMetaJson(file,name));
        json.put("copyOf",originalId);
        return json.toJSONString();
    }
    public synchronized void record(FileObject file,String id) {
        if (file.getCreatedAt()==null) file.setCreatedAt(System.currentTimeMillis());
        io.append(id,file);
        STORAGE.put(id,file);
    }
    public synchronized void remove(String id) {
        io.remove(id);
        STORAGE.remove(id);
    }
    public FileObject query(String id) {
        FileObject cached = STORAGE.get(id), committed = io.current(id);
        if (committed == null) return cached;
        if (cached == null || !Objects.equals(cached.getContentObjectId(), committed.getContentObjectId())
                || !Objects.equals(cached.getSize(), committed.getSize())) {
            STORAGE.put(id, committed);
            return committed;
        }
        return cached;
    }
    private void fillCreatedAt(FileObject file) {
        if (file.getCreatedAt()!=null) return;
        try {
            file.setCreatedAt(Files.readAttributes(Path.of(file.getPath()),BasicFileAttributes.class).creationTime().toMillis());
        } catch (IOException ignored) { }
    }
    public FileObjectWebVO queryByOffset(int offset,int limit,String search) {
        if (offset<0 || limit<1 || limit>1000) throw new IllegalArgumentException("Invalid pagination");
        String q=search==null ? "" : search;
        boolean all=q.contains("meta:all");
        String[] words=Arrays.stream(q.split("\\s+")).filter(s -> !s.isBlank() && !s.startsWith("meta:")).toArray(String[]::new);
        List<FileObject> files=STORAGE.values().stream()
            .filter(f -> all || (StringUtils.isBlank(f.getCopyOf()) && StringUtils.isBlank(f.getParent())))
            .filter(f -> Arrays.stream(words).allMatch(w -> StringUtils.containsIgnoreCase(f.getFileName(),w) || StringUtils.containsIgnoreCase(f.getUuid(),w)))
            .sorted(Comparator.comparing(FileObject::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(FileObject::getUuid))
            .collect(Collectors.toList());
        List<FileObject> page=files.stream().skip(offset).limit(limit).collect(Collectors.toList());
        page.forEach(f -> { f.setUrl(buildUrl(f.getUuid())); f.setThumbUrl(buildThumbUrl(f.getUuid())); });
        return FileObjectWebVO.builder().total(files.size()).pageNumber(offset).pageSize(limit).rows(page).build();
    }
}
