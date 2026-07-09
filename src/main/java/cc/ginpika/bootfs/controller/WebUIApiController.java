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
import cc.ginpika.bootfs.service.ClusterProxyService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.ImageHostDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.ai.AiMetadataService;
import com.meilisearch.sdk.model.SearchResult;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WebUIApiController {
    private final Context context;
    private final ContextIO contextIO;
    private final TfsConfig tfsConfig;
    private final EtcdService etcdService;
    private final MeiliSearchService meiliSearchService;
    private final AiMetadataService aiMetadataService;
    private final ReverseProxyService reverseProxyService;
    private final ClusterProxyService clusterProxyService;

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
                                           @RequestParam(value = "search", defaultValue = "") String search,
                                           @RequestParam(value = "tags", defaultValue = "") String tags) {
        // local 关键字 → 强制走本地内存查询，不走 MeiliSearch
        if (StringUtils.containsIgnoreCase(search, "meta:local")) {
            String localSearch = search.replaceAll("(?i)meta:local", "").trim();
            return context.queryByOffset(pageNumber, pageSize, localSearch);
        }

        // 其他情况统一走 MeiliSearch，直接从文档还原 FileObject，支撑集群列表查询
        FileObjectWebVO result = meiliSearchQuery(pageNumber, pageSize, search, tags);
        return result != null ? result : emptyPage(pageNumber, pageSize);
    }

    /**
     * 走 MeiliSearch，并行搜索 image-host 和 full-text 两个索引，合并结果后直接从文档还原 FileObject 并翻页
     * @param tagFilter 逗号分隔的标签，为 null 时不做标签过滤（纯全文搜索）
     * @return 搜索结果，文档集合为空时返回 null
     */
    private FileObjectWebVO meiliSearchQuery(int pageNumber, int pageSize, String query, String tagFilter) {
        List<String> tagList = tagFilter != null
                ? Arrays.stream(tagFilter.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toList())
                : null;

        // 按 uuid 去重，保留插入顺序；full-text 索引的数据更完整，后写入覆盖 image-host 的结果
        LinkedHashMap<String, FileObject> fileMap = new LinkedHashMap<>();

        SearchResult r2 = meiliSearchService.searchByTags(query, tagList, 0, 1000, "full-text");
        if (r2 != null && r2.getHits() != null) {
            for (Map<String, Object> hit : r2.getHits()) {
                FileObject fo = reconstructFileObject(hit);
                if (fo != null) {
                    fileMap.put(fo.getUuid(), fo);
                }
            }
        }

        if (fileMap.isEmpty()) return null;

        List<FileObject> allFiles = new ArrayList<>(fileMap.values());
        int total = allFiles.size();

        int from = pageNumber * pageSize;
        int to = Math.min(from + pageSize, allFiles.size());
        if (from >= allFiles.size()) {
            return FileObjectWebVO.builder()
                    .pageNumber(pageNumber).pageSize(pageSize).total(total)
                    .rows(Collections.emptyList()).build();
        }

        List<FileObject> files = new ArrayList<>(allFiles.subList(from, to));
        for (FileObject fo : files) {
            fo.setUrl(context.buildUrl(fo.getUuid()));
        }

        return FileObjectWebVO.builder()
                .pageNumber(pageNumber).pageSize(pageSize).total(total)
                .rows(files).build();
    }

    /**
     * 从 MeiliSearch 搜索命中文档还原 FileObject（不包含 path 等 node-local 字段）
     */
    @SuppressWarnings("unchecked")
    private FileObject reconstructFileObject(Map<String, Object> hit) {
        String uuid = (String) hit.get("uuid");
        if (uuid == null) return null;

        FileObject fo = new FileObject();
        fo.setUuid(uuid);
        // title 和 fileName 冗余存储，优先取 fileName，回退到 title
        String fileName = (String) hit.get("fileName");
        if (fileName == null) fileName = (String) hit.get("title");
        fo.setFileName(fileName);
        fo.setSize(hit.get("size") != null ? ((Number) hit.get("size")).longValue() : null);
        fo.setCopyOf((String) hit.get("copyOf"));
        fo.setHlsAvailable((String) hit.get("hlsAvailable"));
        fo.setThumbAvailable((String) hit.get("thumbAvailable"));
        fo.setParent((String) hit.get("parent"));
        fo.setAlbumAvailable((String) hit.get("albumAvailable"));
        fo.setNsfw((String) hit.get("nsfw"));
        fo.setIsPublicAccess((String) hit.get("isPublicAccess"));
        fo.setCreatedAt(hit.get("fileCreatedAt") != null ? ((Number) hit.get("fileCreatedAt")).longValue() : null);

        // 缩略图 URL：优先取 thumbUrl 字段，旧文档回退到 poster（full-text 索引的 poster 即 thumbUrl）
        String thumbUrl = (String) hit.get("thumbUrl");
        if (thumbUrl == null) thumbUrl = (String) hit.get("poster");
        fo.setThumbUrl(thumbUrl);

        // 从 tags 数组还原 List<Tag>
        Object tagsObj = hit.get("tags");
        if (tagsObj instanceof List) {
            List<Tag> tags = new ArrayList<>();
            for (Object tagObj : (List<?>) tagsObj) {
                String tagStr = String.valueOf(tagObj);
                if (!tagStr.isEmpty()) {
                    tags.add(Tag.fromIndexFormat(tagStr));
                }
            }
            fo.setTags(tags);
        }

        return fo;
    }

    private FileObjectWebVO emptyPage(int pageNumber, int pageSize) {
        return FileObjectWebVO.builder()
                .pageNumber(pageNumber).pageSize(pageSize).total(0)
                .rows(Collections.emptyList()).build();
    }

    @PostMapping("/removeFileByUuid")
    public Boolean removeFileByUuid(HttpServletRequest httpServletRequest,
                            @RequestParam("uuid") String uuid) throws InterruptedException {
        String remoteIp = httpServletRequest.getHeader("X-forwarded-for");
        if (remoteIp == null) remoteIp = httpServletRequest.getRemoteAddr();
        log.info("remove: {} from {}", uuid, remoteIp);
        deleteAtlasChildren(uuid);
        meiliSearchService.deleteDocumentFromIndex("full-text", uuid);
        meiliSearchService.deleteDocumentFromIndex("image-host", uuid);
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
                    String uuid = (String) r;
                    deleteAtlasChildren(uuid);
                    meiliSearchService.deleteDocumentFromIndex("full-text", uuid);
                    meiliSearchService.deleteDocumentFromIndex("image-host", uuid);
                    context.remove(uuid);
                    etcdService.delFileAndReplicas(uuid);
                } catch (Exception e) {
                    log.error("uuid 不合法，删除失败: {}", r.toString(), e);
                }
            }, threadPoolExecutor);
        });
        return Boolean.TRUE;
    }

    /**
     * 删除图集时，通过 MeiliSearch 索引找到该图集的子资源并一并删除
     */
    private void deleteAtlasChildren(String uuid) {
        // 通过 MeiliSearch 的 full-text 索引查找该图集文档
        Map<String, Object> docResult = meiliSearchService.getDocument("full-text", uuid);
        if (!Boolean.TRUE.equals(docResult.get("succeed")) || docResult.get("data") == null) {
            return;
        }
        JSONObject docData = (JSONObject) docResult.get("data");
        JSONArray resources = docData.getJSONArray("resources");
        if (resources == null || resources.isEmpty()) {
            return;
        }
        log.info("从 MeiliSearch 找到图集 {} 的文档, resources 数量: {}", uuid, resources.size());

        // 从 resources 的 url 列表中提取子图片 uuid（url 末尾即为 uuid）
        List<String> childrenUuids = new ArrayList<>();
        for (int i = 0; i < resources.size(); i++) {
            String url = resources.getString(i);
            if (url != null && url.contains("/")) {
                String childUuid = url.substring(url.lastIndexOf("/") + 1);
                childrenUuids.add(childUuid);
            }
        }

        log.info("图集 {} 共有 {} 个子图片需要删除: {}", uuid, childrenUuids.size(), childrenUuids);
        for (String childUuid : childrenUuids) {
            try {
                context.remove(childUuid);
                etcdService.delFileAndReplicas(childUuid);
                log.info("已删除图集子图片: {}", childUuid);
            } catch (Exception e) {
                log.error("删除图集子图片失败: {}", childUuid, e);
            }
        }

        // 从 MeiliSearch 的 full-text 索引中删除该图集文档
        meiliSearchService.deleteDocumentFromIndex("full-text", uuid);

        // 同时清理 image-host 索引中可能存在的文档
        meiliSearchService.deleteDocumentFromIndex("image-host", uuid);
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
                    .thumbUrl(context.buildThumbUrl(uuid))
                    .uuid(uuid)
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
     * 重命名文件（仅更新 fileName 元数据，磁盘文件按 uuid 命名不受影响）
     */
    @PutMapping("/api/file/{uuid}/rename")
    public ResponseEntity<?> rename(@PathVariable("uuid") String uuid,
                                    @RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        String newFileName = body.get("fileName");
        if (newFileName == null || newFileName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }
        newFileName = newFileName.trim();
        if (newFileName.contains("/") || newFileName.contains("\\") || newFileName.contains("\0")) {
            return ResponseEntity.badRequest().body("文件名包含非法字符");
        }
        if (newFileName.length() > 255) {
            return ResponseEntity.badRequest().body("文件名过长");
        }
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            String remoteUrl = clusterProxyService.getRemoteNodeUrl(uuid);
            if (remoteUrl != null) {
                return clusterProxyService.proxyRequest(remoteUrl, request, new JSONObject(body).toJSONString());
            }
            return ResponseEntity.notFound().build();
        }
        fileObject.setFileName(newFileName);
        contextIO.update(uuid, fileObject);
        meiliSearchService.updateDocumentTitle("full-text", uuid, newFileName);
        return ResponseEntity.ok().build();
    }

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

    // ======================== 文件详情 API ========================

    /**
     * 获取文件详情，包含多媒体元数据（异步提取）
     */
    @GetMapping("/api/file/{uuid}/details")
    public ResponseEntity<?> getFileDetails(@PathVariable("uuid") String uuid, HttpServletRequest request) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            String remoteUrl = clusterProxyService.getRemoteNodeUrl(uuid);
            if (remoteUrl != null) {
                return clusterProxyService.proxyRequest(remoteUrl, request, (String) null);
            }
            return ResponseEntity.notFound().build();
        }

        JSONObject details = new JSONObject();
        details.put("uuid", fileObject.getUuid());
        details.put("fileName", fileObject.getFileName());
        details.put("size", fileObject.getSize());
        details.put("path", fileObject.getPath());
        details.put("url", context.buildUrl(fileObject.getUuid()));
        details.put("copyOf", fileObject.getCopyOf());
        details.put("hlsAvailable", fileObject.getHlsAvailable());
        details.put("thumbAvailable", fileObject.getThumbAvailable());
        details.put("albumAvailable", fileObject.getAlbumAvailable());
        details.put("nsfw", fileObject.getNsfw());
        details.put("isPublicAccess", fileObject.getIsPublicAccess());
        details.put("parent", fileObject.getParent());

        List<Tag> tags = fileObject.getTags();
        details.put("tags", tags != null ? tags : Collections.emptyList());

        // 提取多媒体元数据（音频/视频）
        String fileName = fileObject.getFileName();
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            boolean isVideo = lower.matches(".*\\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$");
            boolean isAudio = lower.matches(".*\\.(mp3|wav|flac|aac|ogg|wma|m4a|opus)$");
            if (isVideo || isAudio) {
                try {
                    JSONObject mediaInfo = extractMediaInfo(fileObject.getPath());
                    details.put("mediaInfo", mediaInfo);
                } catch (Exception e) {
                    log.warn("提取媒体信息失败: {} - {}", uuid, e.getMessage());
                    details.put("mediaInfo", null);
                }
            }
        }

        return ResponseEntity.ok(details);
    }

    /**
     * 读取图片中的 AI 生成元数据（Stable Diffusion / ComfyUI）
     */
    @GetMapping("/api/file/{uuid}/ai-metadata")
    public ResponseEntity<?> getAiMetadata(@PathVariable("uuid") String uuid, HttpServletRequest request) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            String remoteUrl = clusterProxyService.getRemoteNodeUrl(uuid);
            if (remoteUrl != null) {
                return clusterProxyService.proxyRequest(remoteUrl, request, (String) null);
            }
            return ResponseEntity.notFound().build();
        }
        try {
            AiMetadataService.AIResult result = aiMetadataService.extract(fileObject.getPath());
            if (result == null) {
                return ResponseEntity.ok(Collections.emptyMap());
            }
            JSONObject json = new JSONObject();
            if (result.positivePrompt != null) json.put("positivePrompt", result.positivePrompt);
            if (result.negativePrompt != null) json.put("negativePrompt", result.negativePrompt);
            if (result.model != null) json.put("model", result.model);
            if (result.sampler != null) json.put("sampler", result.sampler);
            if (result.steps != null) json.put("steps", result.steps);
            if (result.cfgScale != null) json.put("cfgScale", result.cfgScale);
            if (result.seed != null) json.put("seed", result.seed);
            if (result.size != null) json.put("size", result.size);
            return ResponseEntity.ok(json);
        } catch (Exception e) {
            log.warn("读取 AI 元数据异常: {} - {}", uuid, e.getMessage());
            return ResponseEntity.ok(Collections.emptyMap());
        }
    }

    /**
     * 提取音频文件内嵌专辑封面
     */
    @GetMapping("/api/file/{uuid}/cover")
    public ResponseEntity<?> getCoverArt(@PathVariable("uuid") String uuid, HttpServletRequest request) {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            String remoteUrl = clusterProxyService.getRemoteNodeUrl(uuid);
            if (remoteUrl != null) {
                return clusterProxyService.proxyRequest(remoteUrl, request, (String) null);
            }
            return ResponseEntity.notFound().build();
        }

        String ffmpegUrl = tfsConfig.getFfmpegUrl();
        int lastIdx = ffmpegUrl.lastIndexOf("ffmpeg");
        String ffmpegPath = ffmpegUrl.substring(0, lastIdx) + "ffmpeg" + ffmpegUrl.substring(lastIdx + 6);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-v", "quiet",
                    "-i", fileObject.getPath(),
                    "-an",
                    "-vcodec", "copy",
                    "-f", "image2pipe",
                    "-"
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();

            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);

            byte[] imageBytes = baos.toByteArray();
            if (imageBytes.length == 0) {
                return ResponseEntity.notFound().build();
            }

            // 根据文件头判定 MIME 类型
            String contentType = detectImageMime(imageBytes);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("Cache-Control", "private, max-age=3600")
                    .body(imageBytes);
        } catch (Exception e) {
            log.warn("提取封面失败: {} - {}", uuid, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private String detectImageMime(byte[] data) {
        if (data.length < 4) return "image/jpeg";
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "image/png";
        }
        // WebP: 52 49 46 46 ... 57 45 42 50
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46) {
            return "image/webp";
        }
        // BMP: 42 4D
        if (data[0] == 0x42 && data[1] == 0x4D) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    private JSONObject extractMediaInfo(String filePath) {
        JSONObject info = new JSONObject();

        String ffmpegUrl = tfsConfig.getFfmpegUrl();
        int lastIdx = ffmpegUrl.lastIndexOf("ffmpeg");
        String ffprobePath = ffmpegUrl.substring(0, lastIdx) + "ffprobe" + ffmpegUrl.substring(lastIdx + 6);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    filePath
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);

            JSONObject probeResult = JSONObject.parseObject(output.toString());

            // 解析 format 信息
            JSONObject format = probeResult.getJSONObject("format");
            if (format != null) {
                info.put("formatName", format.getString("format_name"));
                info.put("formatLongName", format.getString("format_long_name"));
                info.put("duration", format.getDouble("duration"));
                info.put("bitRate", format.getLong("bit_rate"));
                info.put("size", format.getLong("size"));

                // 提取元数据标签 (ID3 / Vorbis comments 等)
                JSONObject tags = format.getJSONObject("tags");
                if (tags != null && !tags.isEmpty()) {
                    JSONObject metadata = new JSONObject();
                    metadata.put("title", tags.getString("title"));
                    metadata.put("artist", tags.getString("artist"));
                    metadata.put("album", tags.getString("album"));
                    metadata.put("albumArtist", tags.getString("album_artist"));
                    metadata.put("track", tags.getString("track"));
                    metadata.put("date", tags.getString("date"));
                    metadata.put("genre", tags.getString("genre"));
                    metadata.put("composer", tags.getString("composer"));
                    metadata.put("comment", tags.getString("comment"));
                    info.put("metadata", metadata);
                }
            }

            // 解析 streams
            JSONArray streams = probeResult.getJSONArray("streams");
            if (streams != null) {
                JSONObject videoStream = null;
                JSONObject audioStream = null;
                JSONObject coverStream = null;

                for (int i = 0; i < streams.size(); i++) {
                    JSONObject stream = streams.getJSONObject(i);
                    String codecType = stream.getString("codec_type");
                    if ("video".equals(codecType)) {
                        // 检查是否为内嵌封面 (attached_pic)
                        JSONObject disposition = stream.getJSONObject("disposition");
                        if (disposition != null && disposition.getIntValue("attached_pic") == 1) {
                            coverStream = stream;
                        } else if (videoStream == null) {
                            videoStream = stream;
                        }
                    } else if ("audio".equals(codecType) && audioStream == null) {
                        audioStream = stream;
                    }
                }

                // 内嵌封面
                if (coverStream != null) {
                    JSONObject cover = new JSONObject();
                    cover.put("codec", coverStream.getString("codec_name"));
                    cover.put("width", coverStream.getInteger("width"));
                    cover.put("height", coverStream.getInteger("height"));
                    info.put("cover", cover);
                }

                if (videoStream != null) {
                    JSONObject video = new JSONObject();
                    video.put("codec", videoStream.getString("codec_name"));
                    video.put("codecLong", videoStream.getString("codec_long_name"));
                    video.put("width", videoStream.getInteger("width"));
                    video.put("height", videoStream.getInteger("height"));
                    video.put("pixFmt", videoStream.getString("pix_fmt"));
                    if (videoStream.containsKey("r_frame_rate")) {
                        String fpsStr = videoStream.getString("r_frame_rate");
                        video.put("frameRate", parseFrameRate(fpsStr));
                    }
                    if (videoStream.containsKey("bit_rate")) {
                        video.put("bitRate", videoStream.getLong("bit_rate"));
                    }
                    if (videoStream.containsKey("profile")) {
                        video.put("profile", videoStream.getString("profile"));
                    }
                    info.put("video", video);
                }

                if (audioStream != null) {
                    JSONObject audio = new JSONObject();
                    audio.put("codec", audioStream.getString("codec_name"));
                    audio.put("codecLong", audioStream.getString("codec_long_name"));
                    audio.put("sampleRate", audioStream.getInteger("sample_rate"));
                    audio.put("channels", audioStream.getInteger("channels"));
                    if (audioStream.containsKey("bit_rate")) {
                        audio.put("bitRate", audioStream.getLong("bit_rate"));
                    }
                    if (audioStream.containsKey("channel_layout")) {
                        audio.put("channelLayout", audioStream.getString("channel_layout"));
                    }
                    info.put("audio", audio);
                }
            }
        } catch (Exception e) {
            log.warn("ffprobe 执行失败: {}", e.getMessage());
            info.put("error", "无法提取媒体信息");
        }

        return info;
    }

    private Double parseFrameRate(String fpsStr) {
        if (fpsStr == null || fpsStr.isEmpty()) return null;
        try {
            if (fpsStr.contains("/")) {
                String[] parts = fpsStr.split("/");
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            }
            return Double.parseDouble(fpsStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
