package com.nihilx.tfs.service;

import com.alibaba.fastjson2.JSONArray;
import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.core.IdGenerator;
import com.nihilx.tfs.domain.dto.FileObject;
import com.nihilx.tfs.domain.dto.NodeObject;
import com.nihilx.tfs.service.etcd.EtcdService;
import com.nihilx.tfs.core.io.ComicArchiveProcessor;
import com.nihilx.tfs.core.io.TransferThreadPool;
import com.nihilx.tfs.service.meilisearch.FullTextDocument;
import com.nihilx.tfs.service.meilisearch.MeiliSearchService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Thread context parameters passing for function with parent-child-relation
    // like comic zip archive
    private final ThreadLocal<String> threadLocalParent = new ThreadLocal<>();
    private final ThreadLocal<String> threadLocalAlbumAvailable = new ThreadLocal<>();

    // a thread-pool for cluster resource replication
    private final TransferThreadPool transferThreadPool = new TransferThreadPool();

    public String upload(MultipartFile file) throws IOException {
        // if multipart-file name start with [Comic], it will be seen as a Comic zip archive
        // tfs will unzip it automatically, and will save every image page as children of zip archive
        if (file.getOriginalFilename() != null && file.getOriginalFilename().startsWith("[Comic]")) {
            this.comicUnzipPreProcess(file);
        } else if (file.getOriginalFilename() != null && file.getOriginalFilename().startsWith("[Picture]")) {
            this.pictureUnzipPreProcess(file);
        }
        return normalFilePersistence(file);
    }

    public void pictureUnzipPreProcess(MultipartFile file) {

    }

    public void comicUnzipPreProcess(MultipartFile file) throws IOException {
        String fileOriginalName = file.getOriginalFilename();
        if (StringUtils.isBlank(fileOriginalName)) {
            throw new IOException("MultipartFile.getOriginalFilename should not be null");
        }
        String ext = fileOriginalName.substring(fileOriginalName.lastIndexOf("."));
        // current version only support zip archive
        if (!ext.equals(".zip")) return;
        // create tmp file for zip archive
        Path tmp = Files.createTempFile("tmp" + System.currentTimeMillis(), ext);
        OutputStream tmpOs = Files.newOutputStream(tmp);
        InputStream zipIs = file.getInputStream();
        zipIs.transferTo(tmpOs);
        zipIs.close();tmpOs.close();
        // create tmp file for ArchiveEntry of archive
        List<Path> outputs = ComicArchiveProcessor.extraction(tmp);
        List<String> comicUrls = new ArrayList<>();
        String title = file.getOriginalFilename().substring(0, file.getOriginalFilename().lastIndexOf(".")).substring(7);;
        AtomicInteger pageCount = new AtomicInteger(1);
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
            String imageExt = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.'));
            String imageTitle = title + "_" + pageCount.getAndIncrement() + imageExt;
            long imageSize = target.toFile().length();
            FileObject imageFileObject = new FileObject(filePath, uuid, imageTitle, imageSize);
            imageFileObject.setParent(this.threadLocalParent.get());
            context.record(imageFileObject, uuid);
            String localNodeUrl = context.buildUrl(uuid);
            etcdService.putFile(uuid, localNodeUrl, context.buildMetaJson(file, imageTitle));
            replication(target.toFile(), uuid, imageTitle);
            comicUrls.add(context.buildProxyUrl(uuid));
        });
        LocalDateTime now = LocalDateTime.now();
        String documentUUID = this.threadLocalParent.get();
        FullTextDocument fullTextDocument = FullTextDocument.builder().title(title).poster(comicUrls.get(0))
                .resources(JSONArray.from(comicUrls))
                .uuid(documentUUID)
                .createdAt(now).updatedAt(now).build();
        meiliSearchService.addToFullText(fullTextDocument);
        tmp.toFile().delete();
        outputs.forEach(path -> path.toFile().delete());
    }

    private String normalFilePersistence(MultipartFile file) throws IOException {
        String uuid = IdGenerator.getUniqueId();
        if (threadLocalParent.get() != null) uuid = threadLocalParent.get();
        saveToLocal(uuid, file);
        String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
        FileObject fileObject = new FileObject(filePath, uuid, file.getOriginalFilename(), file.getSize());
        if (threadLocalAlbumAvailable.get() != null) fileObject.setAlbumAvailable("1");
        context.record(fileObject, uuid);
        String localNodeUrl = context.buildUrl(uuid);
        etcdService.putFile(uuid, localNodeUrl, context.buildMetaJson(file));
        File target = new File(filePath);
        replication(target, uuid, file.getOriginalFilename());
        return localNodeUrl;
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
        FileOutputStream fos = new FileOutputStream(target);
        file.getInputStream().transferTo(fos);
        fos.close();
        file.getInputStream().close();
    }

    public void replication(File file, String fileId, String originFilename) {
        List<String> nodes = etcdService.getAllNodes();
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
