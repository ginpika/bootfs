package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSONArray;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.core.io.ComicArchiveProcessor;
import cc.ginpika.bootfs.core.io.TransferThreadPool;
import cc.ginpika.bootfs.service.meilisearch.FullTextDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class FileService {
    @Autowired
    Context context;
    @Autowired
    private TfsConfig tfsConfig;
    @Autowired
    private EtcdService etcdService;
    @Autowired
    private FileTransferService fileTransferService;
    @Autowired
    private MeiliSearchService meiliSearchService;
    @Autowired
    private ThumbnailService thumbnailService;

    public FileService(Context context, TfsConfig tfsConfig, EtcdService etcdService, FileTransferService fileTransferService, 
        MeiliSearchService meiliSearchService, ThumbnailService thumbnailService) {
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.etcdService = etcdService;
        this.fileTransferService = fileTransferService;
        this.meiliSearchService = meiliSearchService;
        this.thumbnailService = thumbnailService;
    }

    // Thread context parameters passing for function with parent-child-relation
    // like comic zip archive
    private final ThreadLocal<String> threadLocalParent = new ThreadLocal<>();
    private final ThreadLocal<String> threadLocalAlbumAvailable = new ThreadLocal<>();

    // a thread-pool for cluster resource replication
    private final TransferThreadPool transferThreadPool = new TransferThreadPool();

    public String upload(MultipartFile file) throws IOException {
        // if multipart-file name start with [Comic], it will be seen as a Comic zip archive
        // tfs will unzip it automatically, and will save every image page as children of zip archive
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && originalFilename.startsWith("[Comic]")) {
            this.comicUnzipPreProcess(file);
        } else if (originalFilename != null && originalFilename.startsWith("[Picture]")) {
            this.pictureUnzipPreProcess(file);
        }
        return normalFilePersistence(file);
    }

    public void pictureUnzipPreProcess(MultipartFile file) {

    }

    // 上传图包入口：自动识别 .zip 并走解包逻辑，不需要文件名以 [Comic] 开头
    public String uploadComic(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.isBlank()) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
            if (ext.equals(".zip")) {
                try {
                    this.comicUnzipPreProcess(file);
                } catch (IOException e) {
                    log.error("图包上传解包失败", e);
                    throw e;
                }
            }
        }
        return normalFilePersistence(file);
    }

    public void comicUnzipPreProcess(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IOException("MultipartFile.getOriginalFilename should not be null");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        // current version only support zip archive
        if (!ext.equals(".zip")) return;
        // create tmp file for zip archive
        Path tmp = Files.createTempFile("tmp" + System.currentTimeMillis(), ext);
        try (OutputStream tmpOs = Files.newOutputStream(tmp)) {
            file.getInputStream().transferTo(tmpOs);
        }
        // create tmp file for ArchiveEntry of archive
        List<Path> outputs = ComicArchiveProcessor.extraction(tmp);
        List<String> comicUrls = new ArrayList<>();
        String title = originalFilename.substring(0, originalFilename.lastIndexOf(".")).substring(7);
        AtomicInteger pageCount = new AtomicInteger(1);
        AtomicReference<String> firstPageUuid = new AtomicReference<>();
        if (outputs.isEmpty()) return;
        // passing parent-children-relation, write in thread-local for parent
        this.threadLocalAlbumAvailable.set("1");
        this.threadLocalParent.set(IdGenerator.getUniqueId());
        outputs.forEach(path -> {
            String uuid = IdGenerator.getUniqueId();
            String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
            Path target = Path.of(filePath);
            log.info(String.valueOf(path));
            try {
                Files.copy(path, target);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            String imageExt = Optional.ofNullable(path.getFileName())
                    .map(name -> name.toString().substring(name.toString().lastIndexOf('.')))
                    .orElse(".jpg");
            String imageTitle = title + "_" + pageCount.getAndIncrement() + imageExt;
            long imageSize = target.toFile().length();
            FileObject imageFileObject = new FileObject(filePath, uuid, imageTitle, imageSize);
            imageFileObject.setParent(this.threadLocalParent.get());
            context.record(imageFileObject, uuid);
            String localNodeUrl = context.buildUrl(uuid);
            etcdService.putFile(uuid, localNodeUrl, context.buildMetaJson(file, imageTitle));
            replication(target.toFile(), uuid, imageTitle);
            thumbnailService.generateAsync(uuid);
            if (firstPageUuid.get() == null) firstPageUuid.set(uuid);
            comicUrls.add(context.buildThumbUrl(uuid));
        });
        LocalDateTime now = LocalDateTime.now();
        String documentUUID = this.threadLocalParent.get();
        String poster = firstPageUuid.get() == null ? null : context.buildThumbUrl(firstPageUuid.get());
        FullTextDocument fullTextDocument = FullTextDocument.builder().title(title).poster(poster)
                .thumbUrl(poster)
                .resources(JSONArray.from(comicUrls))
                .tags(new JSONArray())
                .uuid(documentUUID)
                .createdAt(now).updatedAt(now)
                .fileName(originalFilename)
                .size(file.getSize())
                .albumAvailable("1")
                .fileCreatedAt(System.currentTimeMillis())
                .build();
        meiliSearchService.addToFullText(fullTextDocument);
        tmp.toFile().delete();
        outputs.forEach(path -> path.toFile().delete());
    }

    private String normalFilePersistence(MultipartFile file) throws IOException {
        String uuid = IdGenerator.getUniqueId();
        if (threadLocalParent.get() != null) uuid = threadLocalParent.get();
        saveToLocal(uuid, file);
        String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
        String originalFilename = file.getOriginalFilename();
        FileObject fileObject = new FileObject(filePath, uuid, originalFilename, file.getSize());
        if (threadLocalAlbumAvailable.get() != null) fileObject.setAlbumAvailable("1");
        context.record(fileObject, uuid);
        String localNodeUrl = context.buildUrl(uuid);
        etcdService.putFile(uuid, localNodeUrl, context.buildMetaJson(file));
        File target = new File(filePath);
        replication(target, uuid, originalFilename);
        thumbnailService.generateAsync(uuid);
        // 索引到本地 MeiliSearch full_text，支持分布式下各节点仅展示本地数据
        indexToMeiliSearch(fileObject);
        return localNodeUrl;
    }

    private void indexToMeiliSearch(FileObject fileObject) {
        try {
            LocalDateTime now = LocalDateTime.now();
            FullTextDocument doc = FullTextDocument.builder()
                    .uuid(fileObject.getUuid())
                    .title(fileObject.getFileName())
                    .poster(context.buildThumbUrl(fileObject.getUuid()))
                    .thumbUrl(context.buildThumbUrl(fileObject.getUuid()))
                    .tags(new JSONArray())
                    .createdAt(now)
                    .updatedAt(now)
                    .fileName(fileObject.getFileName())
                    .size(fileObject.getSize())
                    .copyOf(fileObject.getCopyOf())
                    .hlsAvailable(fileObject.getHlsAvailable())
                    .thumbAvailable(fileObject.getThumbAvailable())
                    .parent(fileObject.getParent())
                    .albumAvailable(fileObject.getAlbumAvailable())
                    .nsfw(fileObject.getNsfw())
                    .isPublicAccess(fileObject.getIsPublicAccess())
                    .fileCreatedAt(fileObject.getCreatedAt())
                    .build();
            meiliSearchService.addToFullText(doc);
        } catch (Exception e) {
            log.warn("索引文件到 MeiliSearch full_text 失败, uuid={}: {}", fileObject.getUuid(), e.getMessage());
        }
    }

    public void saveToLocal(String uuid, MultipartFile file) throws IOException {
        String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
        File dataDir = new File(tfsConfig.getPathPrefix());
        if (!dataDir.exists()) {
            boolean result = dataDir.mkdirs();
            if (result) log.info("创建 dataDir 目录: {}", dataDir.getAbsoluteFile());
        }
        File target = new File(filePath);
        if (!target.createNewFile()) {
            throw new IOException("文件创建异常");
        }
        try (FileOutputStream fos = new FileOutputStream(target)) {
            file.getInputStream().transferTo(fos);
        }
    }

    public void replication(File file, String fileId, String originFilename) {
        List<String> nodes = etcdService.getAllNodes();
        if (nodes == null || nodes.isEmpty()) {
            log.warn("No nodes available for replication");
            return;
        }
        Random random = new Random();
        int sampleCount = tfsConfig.getCopies();
        for (int i=0;i<sampleCount-1;i++) {
            int idx = random.nextInt(nodes.size());
            String targetNode = nodes.get(idx);
            // rpc 调用
            CompletableFuture.runAsync(() -> {
                try {
                    fileTransferService.put(file, fileId, originFilename, NodeObject.builder().url(targetNode).build());
                } catch (Exception e) {
                    log.error("replication 时遇到了问题", e);
                }
            }, transferThreadPool);
        }
    }

    public Object[] download(String uuid, HttpServletRequest request) throws IOException {
        FileObject target = context.query(uuid);
        if (target == null) {
            log.error("文件不存在: {}", uuid);
            return null;
        }
        log.info("{} - {}", request.getRemoteHost(), target.getFileName());
        File file = new File(target.getPath());
        if (!file.exists()) {
            log.error("文件不存在");
            return null;
        }
        Object[] ret = new Object[3];
        Resource resource = new FileSystemResource(file);
        ret[0] = resource;
        ret[1] = target.getFileName();
        log.info("ret[1] file name when read: {}", ret[1]);
        ret[2] = String.valueOf(resource.getFile().length());
        return ret;
    }
}
