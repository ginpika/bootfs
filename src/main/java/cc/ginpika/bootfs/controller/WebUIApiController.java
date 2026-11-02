package cc.ginpika.bootfs.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
import cc.ginpika.bootfs.domain.result.SearchResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


// All api here supported web UI
@Slf4j
@RestController
@SuppressWarnings("all")
@RequiredArgsConstructor
public class WebUIApiController {
    private final Context context;
    private final cc.ginpika.bootfs.service.FileService fileService;
    private final ContextIO contextIO;
    private final TfsConfig tfsConfig;
    private final EtcdService etcdService;
    private final MeiliSearchService meiliSearchService;
    private final AiMetadataService aiMetadataService;
    private final ReverseProxyService reverseProxyService;
    private final ClusterProxyService clusterProxyService;
    @Autowired private cc.ginpika.bootfs.service.FederationService federation;



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
        if (pageNumber < 0 || pageSize < 1 || pageSize > 1000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid pagination");
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

        boolean includeAll = query != null && query.contains("meta:all");
        String text = query == null ? "" : query.replace("meta:all", "").trim();
        JSONObject federated = federation == null ? null : federation.search(text,tagList == null ? List.of() : tagList,pageNumber,pageSize,includeAll);
        SearchResult result = federated == null ? meiliSearchService.searchResources(text,tagList,pageNumber,pageSize,includeAll) : null;
        List<FileObject> files = new ArrayList<>();
        List<Map<String,Object>> hits = federated == null ? result.getHits() : federated.getJSONArray("hits").stream().map(raw -> (Map<String,Object>)JSONObject.from(raw)).collect(Collectors.toList());
        if (hits != null) {
            for (Map<String,Object> hit : hits) {
                FileObject file = reconstructFileObject(hit);
                if (file != null) {
                    file.setUrl(hit.get("originEndpoint") == null ? context.buildUrl(file.getUuid()) : hit.get("originEndpoint") + "/f/" + file.getUuid());
                    files.add(file);
                }
            }
        }
        return FileObjectWebVO.builder().total(federated == null ? result.getEstimatedTotalHits() : federated.getIntValue("total"))
                .partial(federated != null && federated.getBooleanValue("partial"))
                .unavailableNodes(federated == null ? List.of() : federated.getJSONArray("unavailableNodes").toJavaList(String.class))
                .pageNumber(pageNumber).pageSize(pageSize).rows(files).build();
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
        ResponseEntity<?> remote=remoteResource(uuid,null);
        if (remote!=null) {
            if (!remote.getStatusCode().is2xxSuccessful()) throw new org.springframework.web.server.ResponseStatusException(remote.getStatusCode());
            return Boolean.TRUE.equals(com.alibaba.fastjson2.JSON.parseObject(new String((byte[])remote.getBody(),java.nio.charset.StandardCharsets.UTF_8),Boolean.class));
        }
        String remoteIp = httpServletRequest.getHeader("X-forwarded-for");
        if (remoteIp == null) remoteIp = httpServletRequest.getRemoteAddr();
        log.info("remove: {} from {}", uuid, remoteIp);
        return fileService.deleteResource(uuid);
    }

    @PostMapping("/removeFileBatch")
    public Boolean removeFileBatch(HttpServletRequest request, @RequestBody String uuids) {
        JSONArray array = batchIds(uuids);
        // Return success only after all logical deletes are acknowledged.
        for (Object id : array) removeFileByUuidUnchecked(request,(String)id);
        return Boolean.TRUE;
    }
    private void removeFileByUuidUnchecked(HttpServletRequest request,String id) {
        try { removeFileByUuid(request,id); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    @PostMapping("/downloadFileBatch")
    public String downloadFileBatch(HttpServletRequest request,
                                     HttpServletResponse response,
                                     @RequestBody String uuids) throws IOException {
        JSONArray arr = batchIds(uuids);
        java.util.List<FileObject> batch = new java.util.ArrayList<>();
        long total = 0;
        for (Object value : arr) {
            FileObject resource = context.query((String) value);
            if (resource == null || fileService.isDeleted(resource)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found");
            total += Files.size(Path.of(resource.getPath()));
            if (total > 4L * 1024 * 1024 * 1024) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Batch exceeds 4 GiB");
            batch.add(resource);
        }
        String uuid = IdGenerator.getUniqueId();
        String zipPath = Path.of(tfsConfig.getPathPrefix(),uuid + ".zip").toString();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(new FileOutputStream(zipPath))) {
            zos.setEncoding("utf-8");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (FileObject tf : batch) {
                String name = archiveName(tf.getFileName(), tf.getUuid());
                if (!names.add(name)) name = tf.getUuid() + "-" + name;
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                entry.setUnixMode(UnixStat.FILE_FLAG | 436);
                zos.putArchiveEntry(entry);
                try (InputStream input = Files.newInputStream(Path.of(tf.getPath()))) { input.transferTo(zos); }
                zos.closeArchiveEntry();
            }
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(Path.of(zipPath)); throw failed;
        }
        return new JSONObject().fluentPut("uuid", uuid).toJSONString();
    }

    private static JSONArray batchIds(String json) {
        JSONArray values = JSONArray.parseArray(json);
        if (values == null || values.size() > 1000 || new java.util.HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(id -> !(id instanceof String) || !((String) id).matches("[A-Za-z0-9_-]{1,128}")))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid batch resource IDs");
        return values;
    }

    static String archiveName(String name, String fallback) {
        if (name == null) return fallback;
        String basename = name.replace('\\', '/'); basename = basename.substring(basename.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}:]", "_");
        return basename.isBlank() || basename.equals(".") || basename.equals("..") ? fallback : basename;
    }

    @GetMapping("/g/b/{uuid}")
    public void downloadBatchFile(@PathVariable String uuid,
                                  HttpServletResponse response) {
        if (!uuid.matches("[A-Za-z0-9_-]{1,128}")) { response.setStatus(404); return; }
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
    public ResponseEntity<?> setPublicAccess(@PathVariable("uuid") String uuid,
                                             @RequestParam(defaultValue = "true") boolean enabled) {
        ResponseEntity<?> remote=remoteResource(uuid,null); if (remote!=null) return remote;
        prepareLocalEdit(uuid);
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        meiliSearchService.setResourcePublic(uuid, enabled, context.buildProxyUrl(uuid));
        synchronizeLocalResourceMetadata(uuid);
        return resourceMutationResponse();
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
        String name = body.get("fileName");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().body("文件名不能为空");
        name = name.trim();
        if (name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0) {
            return ResponseEntity.badRequest().body("文件名包含非法字符");
        }
        if (name.length() > 255) return ResponseEntity.badRequest().body("文件名过长");
        ResponseEntity<?> remote=remoteResource(uuid,body); if (remote!=null) return remote;
        prepareLocalEdit(uuid);
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        // Only the node holding the complete resource commits its metadata.
        meiliSearchService.updateDocumentTitle("full-text", uuid, name);
        synchronizeLocalResourceMetadata(uuid);
        return resourceMutationResponse();
    }

    @PutMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> setTags(@PathVariable("uuid") String uuid, @RequestBody List<Tag> tags) {
        ResponseEntity<?> remote=remoteResource(uuid,tags); if (remote!=null) return remote;
        prepareLocalEdit(uuid);
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        meiliSearchService.mutateDocumentTags("full-text", uuid, tags, Collections.emptyList(), true);
        synchronizeLocalResourceMetadata(uuid);
        return resourceMutationResponse();
    }

    @PostMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> addTags(@PathVariable("uuid") String uuid, @RequestBody List<Tag> tags) {
        ResponseEntity<?> remote=remoteResource(uuid,tags); if (remote!=null) return remote;
        prepareLocalEdit(uuid);
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        meiliSearchService.mutateDocumentTags("full-text", uuid, tags, Collections.emptyList(), false);
        synchronizeLocalResourceMetadata(uuid);
        return resourceMutationResponse();
    }

    @DeleteMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> removeTags(@PathVariable("uuid") String uuid, @RequestBody List<String> tags) {
        ResponseEntity<?> remote=remoteResource(uuid,tags); if (remote!=null) return remote;
        prepareLocalEdit(uuid);
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        meiliSearchService.mutateDocumentTags("full-text", uuid, Collections.emptyList(), tags, false);
        synchronizeLocalResourceMetadata(uuid);
        return resourceMutationResponse();
    }

    @GetMapping("/api/file/{uuid}/tags")
    public ResponseEntity<?> getTags(@PathVariable("uuid") String uuid) {
        ResponseEntity<?> remote=remoteResource(uuid,null); if (remote!=null) return remote;
        if (meiliSearchService.findDocument("full-text", uuid) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().body(meiliSearchService.getDocumentTags("full-text", uuid));
    }

    private ResponseEntity<?> resourceMutationResponse() {
        return ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(meiliSearchService.visibleRevision())).build();
    }
    private ResponseEntity<?> remoteResource(String uuid,Object body) {
        if (context.query(uuid)!=null) return null;
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) return null;
        HttpServletRequest request=servlet.getRequest();
        if ("1".equals(request.getHeader("X-BootFS-Forwarded"))) return ResponseEntity.notFound().build();
        String remote=clusterProxyService.getRemoteNodeUrl(uuid);
        if (remote==null) return ResponseEntity.notFound().build();
        return clusterProxyService.proxyRequest(remote,request,body==null ? null : JSONObject.toJSONString(body));
    }

    private void synchronizeLocalResourceMetadata(String uuid) {
        FileObject local = context.query(uuid);
        if (local == null) return;
        synchronized (local) {
            JSONObject current = meiliSearchService.findDocument("full-text", uuid);
            if (current == null) return;
            String name = current.getString("fileName");
            if (name == null) name = current.getString("title");
            if (name != null) local.setFileName(name);
            local.setTags(meiliSearchService.getDocumentTags("full-text", uuid));
            local.setIsPublicAccess(current.getString("isPublicAccess"));
            try { contextIO.update(uuid, local); }
            catch (RuntimeException failure) {
                log.warn("Authoritative resource metadata committed; local inventory update needs retry for {}", uuid, failure);
            }
        }
    }
    private void prepareLocalEdit(String uuid) {
        for (int attempt=0;attempt<8;attempt++) {
            String raw=etcdService.getOne("/replica-sources/"+uuid); if (raw==null) return;
            JSONObject branch=JSONObject.parseObject(raw); if (branch.getBooleanValue("forked")) return;
            branch.put("forked",true); if (etcdService.compareAndSet("/replica-sources/"+uuid,raw,branch.toJSONString())) return;
        }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Replica is changing; retry the local edit");
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
        if (page<0 || size<1 || size>1000 || (long)page*size>Integer.MAX_VALUE) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid pagination");
        if (federation!=null && "full-text".equals(index)) {
            JSONObject response=federation.search(query,StringUtils.isBlank(tags)?List.of():Arrays.asList(tags.split(",")),Math.multiplyExact(page,size),size,true);
            response.put("page",page); response.put("size",size); return ResponseEntity.ok(response);
        }
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
            byte[] imageBytes = cc.ginpika.bootfs.core.io.MediaProcess.capture(
                    cc.ginpika.bootfs.core.io.MediaProcess.restrictInput(pb), 10, 8 * 1024 * 1024);
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
            // ffprobe has no -nostdin option; its stdin is never used by this invocation.
            java.util.List<String> args = new java.util.ArrayList<>(cc.ginpika.bootfs.core.io.MediaProcess.restrictInput(pb).command());
            args.remove("-nostdin"); pb.command(args);
            byte[] output = cc.ginpika.bootfs.core.io.MediaProcess.capture(pb, 10, 2 * 1024 * 1024);
            JSONObject probeResult = JSONObject.parseObject(new String(output, java.nio.charset.StandardCharsets.UTF_8));

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
