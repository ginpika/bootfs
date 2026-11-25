package cc.ginpika.bootfs.file.inventory;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.ResourceUrls;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Node-local materialized inventory; durable writes are delegated to the metadata kernel. */
@Service
public class LocalFileInventory {
    private final TfsConfig config;
    private final FileCatalogRepository io;
    public final String uuid;
    private volatile Map<String, FileObject> files = new ConcurrentHashMap<>();
    private boolean loaded;
    public LocalFileInventory(TfsConfig config, FileCatalogRepository io, NodeIdentity identity) {
        this.config=config;
        this.io=io;
        this.uuid=identity.id();
    }
    @PostConstruct
    public synchronized void load() throws IOException {
        if (loaded) return;
        Files.createDirectories(Path.of(config.getPathPrefix()));
        Map<String,FileObject> files=io.load();
        files.values().forEach(this::fillCreatedAt);
        this.files.putAll(files);
        loaded=true;
    }
    public synchronized void reload() throws IOException {
        Map<String,FileObject> files = io.load(); files.values().forEach(this::fillCreatedAt);
        this.files = new ConcurrentHashMap<>(files);
    }
    private String endpoint() {
        String value=config.getWebEntrypoint();
        return value==null || value.equals("/") ? "" : value.replaceAll("/+$","");
    }
    public String buildUrl(String id) { return endpoint()+"/f/"+id; }
    public String buildProxyUrl(String id) { return ResourceUrls.proxy(id); }
    public String buildThumbUrl(String id) { return ResourceUrls.thumb(id); }
    public FileObject cached(String id) { return files.get(id); }

    /** Read-only live view of the current node's materialized records. */
    public Collection<FileObject> cachedFiles() { return Collections.unmodifiableCollection(files.values()); }

    public int cachedCount() { return files.size(); }

    /** Refresh the in-memory view after a caller has committed the catalog record. */
    public void cacheCommitted(String id, FileObject file) { files.put(id, file); }

    public void replaceCached(String id, FileObject expected, FileObject replacement) {
        files.replace(id, expected, replacement);
    }

    public synchronized void record(FileObject file,String id) {
        if (file.getCreatedAt()==null) file.setCreatedAt(System.currentTimeMillis());
        io.update(id,file);
        this.files.put(id,file);
    }
    public synchronized void remove(String id) {
        io.remove(id);
        this.files.remove(id);
    }
    public FileObject query(String id) {
        Map<String, FileObject> view = this.files;
        FileObject cached = view.get(id), committed = io.current(id);
        if (committed == null) return cached;
        if (cached == null || !Objects.equals(cached.getContentObjectId(), committed.getContentObjectId())
                || !Objects.equals(cached.getSize(), committed.getSize())) {
            view.put(id, committed);
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
    public record CachedFilePage(int total, List<FileObject> rows) {}

    public CachedFilePage searchCached(int offset, int limit, String search) {
        if (offset<0 || limit<1 || limit>1000) throw new IllegalArgumentException("Invalid pagination");
        String q=search==null ? "" : search;
        boolean all=q.contains("meta:all");
        String[] words=Arrays.stream(q.split("\\s+")).filter(s -> !s.isBlank() && !s.startsWith("meta:")).toArray(String[]::new);
        List<FileObject> files=this.files.values().stream()
            .filter(f -> all || (StringUtils.isBlank(f.getCopyOf()) && StringUtils.isBlank(f.getParent())))
            .filter(f -> Arrays.stream(words).allMatch(w -> StringUtils.containsIgnoreCase(f.getFileName(),w) || StringUtils.containsIgnoreCase(f.getUuid(),w)))
            .sorted(Comparator.comparing(FileObject::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(FileObject::getUuid))
            .collect(Collectors.toList());
        List<FileObject> page=files.stream().skip(offset).limit(limit).collect(Collectors.toList());
        return new CachedFilePage(files.size(), page);
    }
}
