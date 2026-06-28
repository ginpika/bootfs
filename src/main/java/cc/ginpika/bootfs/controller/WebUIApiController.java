package cc.ginpika.bootfs.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.FileObjectWebVO;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.ImageHostDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.meilisearch.sdk.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.parallel.InputStreamSupplier;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


// All api here supported web UI
@Slf4j
@RestController
@CrossOrigin
@SuppressWarnings("all")
public class WebUIApiController {
    @Autowired
    Context context;
    @Autowired
    ContextIO contextIO;
    @Autowired
    TfsConfig tfsConfig;
    @Autowired
    EtcdService etcdService;
    @Autowired
    MeiliSearchService meiliSearchService;
    @Autowired
    ReverseProxyService reverseProxyService;

    private static final Cache<String, String> cache = CacheBuilder.newBuilder().build();

    private static final int CORE_POOL_SIZE = 8;
    private static final int MAXIMUM_POOL_SIZE = 16;
    private static final int KEEP_ALIVE_TIME = 30;
    private static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;
    private static final BlockingQueue<Runnable> BLOCKING_QUEUE = new LinkedBlockingQueue<>(16);
    private static final RejectedExecutionHandler REJECTED_EXECUTION_HANDLER  = (r, executor) -> {
        try {
            // 阻塞当前线程，直到队列有空位
            executor.getQueue().put(r);
        } catch (InterruptedException e) {
            try {
                Thread.sleep(1000);
                executor.getQueue().put(r);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
    };

    ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_TIME, TIME_UNIT, BLOCKING_QUEUE, REJECTED_EXECUTION_HANDLER);

    @GetMapping("/queryPageOffset")
    public FileObjectWebVO queryPageOffset(@RequestParam(value = "pageNumber", defaultValue = "0") int pageNumber,
                                           @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                           @RequestParam(value = "search", defaultValue = "") String search) {
        return context.queryByOffset(pageNumber, pageSize, search);
    }

    @PostMapping("/removeFileByUuid")
    public Boolean removeFileByUuid(HttpServletRequest httpServletRequest,
                            @RequestParam("uuid") String uuid) throws InterruptedException {
        String remoteIp = httpServletRequest.getHeader("X-forwarded-for");
        if (remoteIp == null) remoteIp = httpServletRequest.getRemoteAddr();
        log.info("remove: {} from {}", uuid, remoteIp);
        context.remove(uuid);
        etcdService.delFileAndReplicas(uuid);
        return Boolean.TRUE;
    }

    @PostMapping("/removeFileBatch")
    public Boolean removeFileBatch(HttpServletRequest httpServletRequest,
                                   @RequestBody String uuids) {
        String remoteIp = httpServletRequest.getHeader("X-forwarded-for");
        if (remoteIp == null) remoteIp = httpServletRequest.getRemoteAddr();
        JSONArray array = JSONArray.parseArray(uuids);
        log.info("batch remove: {} from {}", array.toJSONString(), remoteIp);

        array.forEach(r -> {
            CompletableFuture.runAsync(() -> {
                try {
                    context.remove((String) r);
                    etcdService.delFileAndReplicas((String) r);
                } catch (Exception e) {
                    log.error("uuid 不合法，删除失败: {}", r.toString(), e);
                }
            }, threadPoolExecutor);
        });
        return Boolean.TRUE;
    }

    @PostMapping("/downloadFileBatch")
    public String downloadFileBatch(HttpServletRequest request,
                                     HttpServletResponse response,
                                     @RequestBody String uuids) throws IOException {
        String uuid = IdGenerator.getUniqueId();
        String zipPath = Path.of(tfsConfig.getPathPrefix(),uuid + ".zip").toString();
        try (FileOutputStream fos = new FileOutputStream(zipPath)) {
            ZipArchiveOutputStream zos = new ZipArchiveOutputStream(fos);
            zos.setEncoding("utf-8");
            JSONArray arr = JSONArray.parseArray(uuids);
            ParallelScatterZipCreator parallelScatterZipCreator = new ParallelScatterZipCreator();
            arr.forEach(r -> {
                FileObject tf = context.query((String) r);
                String path = tf.getPath();
                File file = new File(tf.getPath());
                final InputStreamSupplier inputStreamSupplier = () -> {
                    try {
                        return new FileInputStream(path);
                    } catch (FileNotFoundException e) {
                        return new NullInputStream(0);
                    }
                };
                ZipArchiveEntry entry = new ZipArchiveEntry(tf.getFileName());
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                entry.setSize(file.length());
                entry.setUnixMode(UnixStat.FILE_FLAG | 436);
                parallelScatterZipCreator.addArchiveEntry(entry, inputStreamSupplier);
            });
            parallelScatterZipCreator.writeTo(zos);
            zos.close();
            cache.put(uuid, zipPath);
            return new JSONObject().fluentPut("uuid", uuid).toJSONString();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @RequestMapping("/g/b/{uuid}")
    public void downloadBatchFile(@PathVariable String uuid,
                                  HttpServletResponse response) {
        Path zipUrl = Path.of(tfsConfig.getPathPrefix(), uuid + ".zip");
        try (FileInputStream fis = new FileInputStream(zipUrl.toString())) {
            File file = new File(zipUrl.toString());
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setCharacterEncoding("utf-8");
            response.setContentLengthLong(file.length());
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + file.getName());
            OutputStream os = response.getOutputStream();
            fis.transferTo(os);
            os.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/publicAccess/{uuid}")
    public ResponseEntity<?> setPublicAccess(@PathVariable("uuid") String uuid) {
        try {
            FileObject fileObject =  context.query(uuid);
            fileObject.setIsPublicAccess("1");
            contextIO.update(uuid, fileObject);
            LocalDateTime now = LocalDateTime.now();
            meiliSearchService.addToImageHost(ImageHostDocument.builder()
                    .poster(context.buildProxyUrl(uuid))
                    .uuid(uuid)
                    .tags(new JSONArray())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("publicAccess setting failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/setu")
    public void randomPicturesApi(@RequestParam(value = "time", required = false) String clientTime,
                                  HttpServletRequest request, HttpServletResponse response) {
        String proxyUrl = meiliSearchService.querySetu();
        if (proxyUrl == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String uuid = proxyUrl.substring(proxyUrl.lastIndexOf("/") + 1);
        reverseProxyService.reverseProxyFile(uuid, response, request);
    }

    @GetMapping("/setu-url")
    public ResponseEntity<String> randomPicturesUrl() {
        String url = meiliSearchService.querySetu();
        if (url == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().body(url);
    }

    // ======================== 标签管理 API ========================

    /**
     * 设置文件标签（覆盖）
     */
    @PutMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> setTags(@PathVariable("uuid") String uuid,
                                     @RequestBody List<Tag> tags) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            return ResponseEntity.notFound().build();
        }
        fileObject.setTags(tags);
        contextIO.update(uuid, fileObject);
        syncTagsToMeiliSearch(uuid, tags);
        return ResponseEntity.ok().build();
    }

    /**
     * 追加文件标签（合并，去重）
     */
    @PostMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> addTags(@PathVariable("uuid") String uuid,
                                     @RequestBody List<Tag> tags) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            return ResponseEntity.notFound().build();
        }
        List<Tag> existing = fileObject.getTags() != null ? fileObject.getTags() : new ArrayList<>();
        Set<String> existingKeys = existing.stream()
                .map(Tag::toIndexFormat)
                .collect(Collectors.toSet());
        for (Tag tag : tags) {
            if (!existingKeys.contains(tag.toIndexFormat())) {
                existing.add(tag);
                existingKeys.add(tag.toIndexFormat());
            }
        }
        fileObject.setTags(existing);
        contextIO.update(uuid, fileObject);
        syncTagsToMeiliSearch(uuid, existing);
        return ResponseEntity.ok().build();
    }

    /**
     * 删除文件的指定标签
     */
    @DeleteMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> removeTags(@PathVariable("uuid") String uuid,
                                        @RequestBody List<String> tagNames) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            return ResponseEntity.notFound().build();
        }
        if (fileObject.getTags() == null) {
            return ResponseEntity.ok().build();
        }
        Set<String> toRemove = new HashSet<>(tagNames);
        List<Tag> remaining = fileObject.getTags().stream()
                .filter(tag -> !toRemove.contains(tag.toIndexFormat()))
                .collect(Collectors.toList());
        fileObject.setTags(remaining);
        contextIO.update(uuid, fileObject);
        syncTagsToMeiliSearch(uuid, remaining);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取文件的所有标签
     */
    @GetMapping("/api/file/{uuid}/tags")
    public ResponseEntity<List<Tag>> getTags(@PathVariable("uuid") String uuid) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(fileObject.getTags() != null ? fileObject.getTags() : Collections.emptyList());
    }

    private void syncTagsToMeiliSearch(String uuid, List<Tag> tags) {
        CompletableFuture.runAsync(() -> {
            try {
                // 同步到 full-text 索引
                meiliSearchService.updateDocumentTags("full-text", uuid, tags);
            } catch (Exception e) {
                log.warn("同步 tags 到 full-text 索引失败: {}", e.getMessage());
            }
            try {
                // 同步到 image-host 索引
                meiliSearchService.updateDocumentTags("image-host", uuid, tags);
            } catch (Exception e) {
                log.warn("同步 tags 到 image-host 索引失败: {}", e.getMessage());
            }
        }, threadPoolExecutor);
    }

    // ======================== 标签搜索 API ========================

    /**
     * 按标签搜索文件
     * @param query 全文搜索关键词（可选）
     * @param tags 标签过滤，逗号分隔，格式 namespace:name（可选）
     * @param page 页码，默认 0
     * @param size 每页数量，默认 20
     * @param index 索引名：full-text 或 image-host，默认 full-text
     */
    @GetMapping("/api/tags/search")
    public ResponseEntity<?> tagSearch(@RequestParam(value = "q", defaultValue = "") String query,
                                       @RequestParam(value = "tags", defaultValue = "") String tags,
                                       @RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "index", defaultValue = "full-text") String index) {
        List<String> tagFilters = StringUtils.isNotBlank(tags)
                ? Arrays.asList(tags.split(","))
                : Collections.emptyList();
        SearchResult result = meiliSearchService.searchByTags(query, tagFilters, page, size, index);
        if (result == null) {
            return ResponseEntity.ok(new JSONObject()
                    .fluentPut("hits", new JSONArray())
                    .fluentPut("total", 0));
        }
        JSONObject response = new JSONObject();
        response.put("hits", new JSONArray(result.getHits()));
        response.put("total", result.getEstimatedTotalHits());
        response.put("page", page);
        response.put("size", size);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取所有可用标签及其文档计数
     * @param index 索引名，默认 full-text
     */
    @GetMapping("/api/tags/list")
    public ResponseEntity<Map<String, Integer>> tagList(@RequestParam(value = "index", defaultValue = "full-text") String index) {
        Map<String, Integer> tags = meiliSearchService.listAllTags(index);
        return ResponseEntity.ok(tags);
    }
}
