package cc.ginpika.bootfs.controller;

import com.alibaba.fastjson2.JSONArray;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.s3.S3ConcurrentWriteException;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.FileTransferService;
import cc.ginpika.bootfs.service.FolderService;
import cc.ginpika.bootfs.domain.result.TransferResult;
import cc.ginpika.bootfs.service.ClusterProxyService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.meilisearch.FullTextDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// ManegeController provide basic function for TFS
@Slf4j
@RestController
@SuppressWarnings("all")
public class ManageController {
    private static final Pattern ALBUM_RESOURCE_PATH = Pattern.compile(".*/(thumb|p|f)/([A-Za-z0-9_-]+)");
    @Autowired
    private FileService fileService;
    @Autowired
    private FileTransferService fileTransferService;
    @Autowired
    private ReverseProxyService reverseProxyService;
    @Autowired
    private MeiliSearchService meiliSearchService;
    @Autowired
    private Context context;
    @Autowired
    private TfsConfig tfsConfig;
    @Autowired
    private ThumbnailService thumbnailService;
    @Autowired
    private ClusterProxyService clusterProxyService;
    @Autowired
    private cc.ginpika.bootfs.kernel.cluster.ResourceVisibility visibility;

    public ManageController(FileService fileService,
        FileTransferService fileTransferService,
        ReverseProxyService reverseProxyService,
        MeiliSearchService meiliSearchService,
        Context context,
        TfsConfig tfsConfig,
        ThumbnailService thumbnailService,
        ClusterProxyService clusterProxyService) {
        this.fileService = fileService;
        this.fileTransferService = fileTransferService;
        this.reverseProxyService = reverseProxyService;
        this.meiliSearchService = meiliSearchService;
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.thumbnailService = thumbnailService;
        this.clusterProxyService = clusterProxyService;
    }

    

    // upload file
    @PutMapping("/f")
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
                                    @RequestHeader(value="X-BootFS-Request-Id", required=false) String requestId) {
        String url = "";
        try {
            log.info("Uploading file: {}", file.getOriginalFilename());
            url = fileService.upload(file, requestId);
        } catch (cc.ginpika.bootfs.kernel.object.PublicationPendingException pending) {
            return ResponseEntity.status(503).header("X-BootFS-Upload-Id", pending.getUploadId()).body(pending.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(meiliSearchService.visibleRevision())).body(url);
    }

    // upload comic/zip archive
    @PutMapping("/comic")
    public ResponseEntity<?> uploadComic(@RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "folder", defaultValue = "") String folder,
                                         @RequestHeader(value="X-BootFS-Request-Id", required=false) String requestId) {
        String url = "";
        try {
            log.info("Uploading comic archive: {}", file.getOriginalFilename());
            url = fileService.uploadComic(file, requestId, folder);
        } catch (cc.ginpika.bootfs.kernel.object.PublicationPendingException pending) {
            return ResponseEntity.status(503).header("X-BootFS-Upload-Id", pending.getUploadId()).body(pending.getMessage());
        } catch (S3ConcurrentWriteException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict.getMessage());
        } catch (ResponseStatusException invalidPath) {
            throw invalidPath;
        } catch (Exception e) {
            log.error("图包上传失败", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(meiliSearchService.visibleRevision())).body(url);
    }

    // download file & cluster proxy based on this api
    @GetMapping("/f/{uuid}")
    public ResponseEntity<Resource> download(@PathVariable String uuid,
                                             HttpServletRequest request) throws IOException {
        Object[] ret = fileService.download(uuid, request);
        if (ret == null) {
            String remoteUrl = clusterProxyService.getRemoteNodeUrl(uuid);
            if (remoteUrl != null) {
                return ResponseEntity.status(302)
                        .header(HttpHeaders.LOCATION, remoteUrl + "/f/" + uuid)
                        .build();
            }
            log.info("uuid {} 不存在, 返回 not found", uuid);
            return ResponseEntity.notFound().build();
        }
        HttpHeaders contentHeaders = new HttpHeaders();
        contentHeaders.set("Content-Security-Policy", "sandbox; default-src 'none'");
        if (ret[3] != null) contentHeaders.setETag("\"" + ret[3] + "\"");
        if (Boolean.TRUE.equals(ret[4])) contentHeaders.setCacheControl("no-cache");
        String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(contentHeaders.getETag())) {
            return ResponseEntity.status(412).headers(contentHeaders).build();
        }
        // support range-request for media resource
        if (StringUtils.isNotBlank(request.getHeader("Is-Range-Request"))) {
            log.info("uuid {} 是一个 Range-Request");
            String contentType = inlineType((String) ret[1]);
            return ResponseEntity.ok()
                    .headers(contentHeaders)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, (String) ret[2])
                    .body((Resource)ret[0]);
        } else if (StringUtils.isNotBlank(request.getHeader("Is-Proxy"))) {
            log.info("接收到一个 Proxy Request {}", uuid);
            String contentType = inlineType((String) ret[1]);
            return ResponseEntity.ok()
                    .headers(contentHeaders)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, (String) ret[2])
                    .body((Resource)ret[0]);
        }
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename((String) ret[1], StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(contentHeaders);
        headers.setContentDisposition(contentDisposition);
        headers.setContentLength(Long.parseLong((String) ret[2]));
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .headers(headers)
                .body((Resource)ret[0]);
    }

    // reverse proxy file
    @GetMapping("/p/{uuid}")
    public ResponseEntity<?> reverseProxy(@PathVariable String uuid,
                             HttpServletResponse response,
                             HttpServletRequest request) throws IOException {
        // Local reads must not loop through an unauthenticated HTTP request to /f.
        if (context.query(uuid) != null) {
            Object[] file = fileService.download(uuid, request);
            if (file == null) return ResponseEntity.notFound().build();
            String type = inlineType((String) file[1]);
            if (MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(type)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                    .contentLength(Long.parseLong((String) file[2]))
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", "sandbox; default-src 'none'")
                    .body(file[0]);
        }
        reverseProxyService.reverseProxyFile(uuid, response, request);
        return null;
    }

    private static String inlineType(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return switch (dot < 0 ? "" : lower.substring(dot)) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".mp4" -> "video/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".yaml", ".yml" -> "application/yaml";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    // serve webp thumbnail - 不存在时返回原图/占位图并后台生成
    @GetMapping("/thumb/{uuid}")
    public ResponseEntity<?> thumbnail(@PathVariable String uuid, HttpServletRequest request) {
        if (visibility.deleted(uuid)) return ResponseEntity.notFound().build();
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            // Keep album previews on the browser's origin, including HTTPS frontends
            // whose peers are only reachable over internal HTTP endpoints.
            return proxyMissingResource(uuid, request);
        }
        Path thumbFile = thumbnailService.thumbPath(uuid);
        if (Files.exists(thumbFile)) {
            Resource resource = new FileSystemResource(thumbFile);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/webp")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=2592000, immutable")
                    .body(resource);
        }
        
        String fileName = fileObject.getFileName();
        // 后台异步生成缩略图
        thumbnailService.generateAsync(uuid);
        
        if (thumbnailService.isImage(fileName)) {
            // 图片：返回原图作为临时缩略图
            Path sourceFile = Path.of(fileObject.getPath());
            if (Files.exists(sourceFile)) {
                // 根据扩展名确定 Content-Type
                String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
                String contentType = switch (ext) {
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    case "webp" -> "image/webp";
                    default -> "image/jpeg";
                };
                Resource resource = new FileSystemResource(sourceFile);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                        .body(resource);
            }
        } else if (thumbnailService.isVideo(fileName)) {
            // 视频：返回默认占位 SVG
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/svg+xml")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                    .body(thumbnailService.getVideoPlaceholderSvg());
        }
        
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/api/file/{uuid}/publication")
    public Map<String,String> publication(@PathVariable String uuid) {
        return Map.of("uploadId", uuid, "resolvedUploadId", fileService.resolveUploadId(uuid), "state", fileService.publicationState(uuid));
    }

    @PutMapping("/t/prepare")
    public com.alibaba.fastjson2.JSONObject prepareReplica(@RequestPart("file") MultipartFile file,
                                    @RequestParam("originalFilename") String filename,
                                    @RequestParam("originalFileId") String id,
                                    @RequestParam("sha256") String sha256,
                                    @RequestParam(value="snapshot",required=false) String snapshot,
                                    HttpServletRequest request) throws IOException {
        if (snapshot!=null && !snapshot.isBlank()) {
            if (!cc.ginpika.bootfs.kernel.cluster.ClusterHttp.digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(request.getHeader("X-BootFS-Digest"))) throw new IOException("Replica snapshot signature does not match its metadata");
            String actor=localPeer(request.getHeader("X-BootFS-Cluster-Token"));
            return fileTransferService.prepare(file,filename,id,sha256,com.alibaba.fastjson2.JSON.parseObject(snapshot),actor);
        }
        if (localMetadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic
                && dynamic.actor(request.getHeader("X-BootFS-Cluster-Token"), false) != null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Signed replica snapshot required");
        }
        return fileTransferService.prepare(file, filename, id, sha256);
    }
    @org.springframework.beans.factory.annotation.Autowired private cc.ginpika.bootfs.kernel.metadata.MetadataStore localMetadata;
    private String localPeer(String token) throws IOException {
        String actor=localMetadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore d ? d.actor(token,false) : null;
        if (actor==null) throw new IOException("Active peer authorization is required"); return actor;
    }

    // get the replica from other nodes
    @PutMapping("/t")
    public TransferResult transfer(@RequestPart("file") MultipartFile file,
                                   @RequestParam("originalFilename") String originalFilename,
                                   @RequestParam("originalFileId") String originalFileUuid,
                                   @RequestParam(required = false, defaultValue = "1", name = "isCopy") String isCopy) {
        return fileTransferService.accept(file, originalFilename, originalFileUuid);
    }

    @PostMapping("/virtual-file")
    public ResponseEntity<?> createVirtualFile(@RequestBody Map<String, String> request) {
        String fileName = request.getOrDefault("fileName", "virtual_video.mp4");
        String folder = request.getOrDefault("folder", "");
        String logicalName = "".equals(folder) ? fileName : FolderService.uploadKey(folder, fileName);
        try {
            String fileType = request.getOrDefault("fileType", "video/mp4");
            
            String uuid = IdGenerator.getUniqueId();
            String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
            
            FileObject fileObject = FileObject.builder()
                    .path(filePath)
                    .uuid(uuid)
                    .fileName(logicalName)
                    .size(0L)
                    .hlsAvailable("1")
                    .build();
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            Files.createDirectories(hlsDir);
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            String initialM3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:EVENT\n";
            Files.writeString(m3u8Path, initialM3u8);

            // HLS files have a catalog path. The playlist and segments are stored by UUID.
            context.record(fileObject, uuid);
            indexVirtualFileToMeiliSearch(fileObject);
            
            log.info("Created virtual FileObject with uuid: {}, hlsAvailable: {}", uuid, fileObject.getHlsAvailable());
            
            Map<String, Object> response = new HashMap<>();
            response.put("uuid", uuid);
            response.put("fileName", fileName);
            response.put("fileType", fileType);
            response.put("hlsAvailable", "1");
            response.put("url", context.buildUrl(uuid));
            response.put("hlsUrl", tfsConfig.getWebEntrypoint() + "/hls/" + uuid + "/playlist.m3u8");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("创建虚拟文件失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @PostMapping("/hls/{uuid}/segments")
    public ResponseEntity<?> uploadHlsSegment(
            @PathVariable String uuid,
            @RequestParam("segment") MultipartFile segment,
            @RequestParam("sequence") int sequence,
            @RequestParam(value = "duration", defaultValue = "10.0") double duration) {
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            if (!"1".equals(fileObject.getHlsAvailable())) {
                return ResponseEntity.badRequest().body(Map.of("error", "FileObject is not HLS enabled"));
            }
            
            if (segment.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Segment file is empty"));
            }
            
            String originalFilename = segment.getOriginalFilename();
            if (!safeSegmentName(originalFilename) || sequence < 0 || !Double.isFinite(duration) || duration <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only .ts files are allowed"));
            }
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            if (!Files.exists(hlsDir)) {
                Files.createDirectories(hlsDir);
            }
            
            Path segmentPath = hlsDir.resolve(originalFilename);
            segment.transferTo(segmentPath.toFile());
            
            long segmentSize = segmentPath.toFile().length();
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            String m3u8Content;
            if (Files.exists(m3u8Path)) {
                m3u8Content = Files.readString(m3u8Path);
            } else {
                m3u8Content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:EVENT\n";
            }
            
            String segmentEntry = String.format("#EXTINF:%.6f,\n%s\n", duration, originalFilename);
            
            if (m3u8Content.contains("#EXT-X-ENDLIST")) {
                m3u8Content = m3u8Content.replace("#EXT-X-ENDLIST", segmentEntry);
            } else {
                m3u8Content += segmentEntry;
            }
            
            Files.writeString(m3u8Path, m3u8Content);
            
            log.info("Uploaded HLS segment {} for uuid: {}, size: {} bytes", originalFilename, uuid, segmentSize);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("uuid", uuid);
            response.put("sequence", sequence);
            response.put("segmentFileName", originalFilename);
            response.put("segmentSize", segmentSize);
            response.put("duration", duration);
            response.put("m3u8Url", tfsConfig.getWebEntrypoint() + "/hls/" + uuid + "/playlist.m3u8");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("上传HLS切片失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @PostMapping("/hls/{uuid}/finalize")
    public ResponseEntity<?> finalizeHlsPlaylist(@PathVariable String uuid) {
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            Path m3u8Path = Path.of(tfsConfig.getPathPrefix(), "hls", uuid, "playlist.m3u8");
            if (!Files.exists(m3u8Path)) {
                return ResponseEntity.status(404).body(Map.of("error", "M3U8 playlist not found"));
            }
            
            String m3u8Content = Files.readString(m3u8Path);
            if (!m3u8Content.contains("#EXT-X-ENDLIST")) {
                m3u8Content += "#EXT-X-ENDLIST\n";
                Files.writeString(m3u8Path, m3u8Content);
            }
            
            log.info("Finalized HLS playlist for uuid: {}", uuid);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "uuid", uuid,
                    "message", "HLS playlist finalized"
            ));
        } catch (Exception e) {
            log.error("完成HLS播放列表失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @GetMapping("/hls/{uuid}/segments/info")
    public ResponseEntity<?> getHlsSegmentInfo(@PathVariable String uuid) {
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            if (!Files.exists(hlsDir)) {
                return ResponseEntity.ok(Map.of(
                        "uuid", uuid,
                        "segmentCount", 0,
                        "totalSize", 0L,
                        "hasPlaylist", false
                ));
            }
            
            int segmentCount = 0;
            long totalSize = 0L;
            
            File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.endsWith(".ts"));
            if (tsFiles != null) {
                segmentCount = tsFiles.length;
                for (File tsFile : tsFiles) {
                    totalSize += tsFile.length();
                }
            }
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            boolean hasPlaylist = Files.exists(m3u8Path);
            boolean isFinalized = false;
            
            if (hasPlaylist) {
                String m3u8Content = Files.readString(m3u8Path);
                isFinalized = m3u8Content.contains("#EXT-X-ENDLIST");
            }
            
            return ResponseEntity.ok(Map.of(
                    "uuid", uuid,
                    "segmentCount", segmentCount,
                    "totalSize", totalSize,
                    "hasPlaylist", hasPlaylist,
                    "isFinalized", isFinalized,
                    "fileName", fileObject.getFileName()
            ));
        } catch (Exception e) {
            log.error("获取HLS切片信息失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @GetMapping("/hls/{uuid}/segments/list")
    public ResponseEntity<?> getHlsSegmentList(@PathVariable String uuid) {
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            if (!Files.exists(hlsDir)) {
                return ResponseEntity.ok(Map.of("segments", new java.util.ArrayList<>(), "uuid", uuid));
            }
            
            java.util.List<Map<String, Object>> segments = new java.util.ArrayList<>();
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            if (Files.exists(m3u8Path)) {
                String m3u8Content = Files.readString(m3u8Path);
                String[] lines = m3u8Content.split("\n");
                
                int index = 0;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.startsWith("#EXTINF:")) {
                        double duration = 10.0;
                        try {
                            String durationStr = line.substring(8, line.indexOf(","));
                            duration = Double.parseDouble(durationStr);
                        } catch (Exception ignored) {}
                        
                        if (i + 1 < lines.length) {
                            String fileName = lines[i + 1].trim();
                            if (!fileName.startsWith("#") && fileName.endsWith(".ts")) {
                                Path segmentPath = hlsDir.resolve(fileName);
                                long size = Files.exists(segmentPath) ? segmentPath.toFile().length() : 0;
                                
                                segments.add(Map.of(
                                        "index", index,
                                        "fileName", fileName,
                                        "duration", duration,
                                        "size", size,
                                        "url", "/hls/" + uuid + "/" + fileName
                                ));
                                index++;
                            }
                        }
                    }
                }
            } else {
                File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.endsWith(".ts"));
                if (tsFiles != null) {
                    java.util.Arrays.sort(tsFiles, (a, b) -> a.getName().compareTo(b.getName()));
                    for (int i = 0; i < tsFiles.length; i++) {
                        File tsFile = tsFiles[i];
                        segments.add(Map.of(
                                "index", i,
                                "fileName", tsFile.getName(),
                                "duration", 10.0,
                                "size", tsFile.length(),
                                "url", "/hls/" + uuid + "/" + tsFile.getName()
                        ));
                    }
                }
            }
            
            return ResponseEntity.ok(Map.of("segments", segments, "uuid", uuid));
        } catch (Exception e) {
            log.error("获取HLS切片列表失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @PostMapping("/hls/{uuid}/segments/reorder")
    public ResponseEntity<?> reorderHlsSegments(@PathVariable String uuid, @RequestBody java.util.List<String> segmentOrder) {
        if (segmentOrder == null || segmentOrder.size() > 10000 || segmentOrder.stream().anyMatch(name -> !safeSegmentName(name))
                || new java.util.HashSet<>(segmentOrder).size() != segmentOrder.size()) return ResponseEntity.badRequest().build();
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            if (!Files.exists(hlsDir)) {
                return ResponseEntity.status(404).body(Map.of("error", "HLS directory not found"));
            }
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            if (!Files.exists(m3u8Path)) {
                return ResponseEntity.status(404).body(Map.of("error", "M3U8 playlist not found"));
            }
            
            String originalContent = Files.readString(m3u8Path);
            boolean wasFinalized = originalContent.contains("#EXT-X-ENDLIST");
            
            java.util.Map<String, Double> durations = new java.util.HashMap<>();
            String[] lines = originalContent.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("#EXTINF:")) {
                    try {
                        String durationStr = line.substring(8, line.indexOf(","));
                        double duration = Double.parseDouble(durationStr);
                        if (i + 1 < lines.length) {
                            String fileName = lines[i + 1].trim();
                            if (!fileName.startsWith("#")) {
                                durations.put(fileName, duration);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            
            StringBuilder newM3u8 = new StringBuilder();
            newM3u8.append("#EXTM3U\n");
            newM3u8.append("#EXT-X-VERSION:3\n");
            newM3u8.append("#EXT-X-TARGETDURATION:10\n");
            newM3u8.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
            
            for (String fileName : segmentOrder) {
                if (!Files.isRegularFile(hlsDir.resolve(fileName), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return ResponseEntity.badRequest().build();
                Double duration = durations.getOrDefault(fileName, 10.0);
                newM3u8.append(String.format("#EXTINF:%.6f,\n", duration));
                newM3u8.append(fileName).append("\n");
            }
            
            if (wasFinalized) {
                newM3u8.append("#EXT-X-ENDLIST\n");
            }
            
            Files.writeString(m3u8Path, newM3u8.toString());
            
            log.info("Reordered HLS segments for uuid: {}", uuid);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "uuid", uuid,
                    "segmentCount", segmentOrder.size()
            ));
        } catch (Exception e) {
            log.error("重新排序HLS切片失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @DeleteMapping("/hls/{uuid}/segments/{fileName}")
    public ResponseEntity<?> deleteHlsSegment(@PathVariable String uuid, @PathVariable String fileName) {
        if (!safeSegmentName(fileName)) return ResponseEntity.badRequest().build();
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            Path segmentPath = hlsDir.resolve(fileName);
            
            if (!Files.exists(segmentPath)) {
                return ResponseEntity.status(404).body(Map.of("error", "Segment not found: " + fileName));
            }
            
            Files.delete(segmentPath);
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            if (Files.exists(m3u8Path)) {
                String m3u8Content = Files.readString(m3u8Path);
                String[] lines = m3u8Content.split("\n");
                StringBuilder newM3u8 = new StringBuilder();
                
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.equals(fileName)) {
                        if (i > 0 && lines[i - 1].trim().startsWith("#EXTINF:")) {
                            newM3u8.setLength(newM3u8.length() - lines[i - 1].length() - 1);
                        }
                        continue;
                    }
                    newM3u8.append(lines[i]).append("\n");
                }
                
                Files.writeString(m3u8Path, newM3u8.toString());
            }
            
            log.info("Deleted HLS segment {} for uuid: {}", fileName, uuid);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "uuid", uuid,
                    "deletedSegment", fileName
            ));
        } catch (Exception e) {
            log.error("删除HLS切片失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    // 获取相册（comic）的 poster 和 resources，封装 MeiliSearch 查询避免前端 mixed content
    @GetMapping("/api/album/{uuid}")
    public ResponseEntity<?> getAlbumDocument(@PathVariable String uuid,HttpServletRequest request) {
        if (context.query(uuid)==null) return proxyMissingResource(uuid,request);
        Map<String, Object> result = meiliSearchService.getDocument("full-text", uuid);
        if (Boolean.TRUE.equals(result.get("succeed"))) {
            Map<String, Object> album = new HashMap<>((Map<String, Object>) result.get("data"));
            Object resources = album.get("resources");
            if (resources instanceof List<?>) {
                album.put("resources", ((List<?>) resources).stream()
                        .map(this::albumResourceUrl).collect(Collectors.toList()));
            }
            for (String field : List.of("poster", "thumbUrl")) {
                if (album.containsKey(field)) album.put(field, albumResourceUrl(album.get(field)));
            }
            return ResponseEntity.ok(album);
        }
        return ResponseEntity.status(404).body(Map.of("error", "album not found"));
    }

    private Object albumResourceUrl(Object value) {
        if (!(value instanceof String url)) return value;
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null && !"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) return value;
            var match = ALBUM_RESOURCE_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (match.matches()) {
                return "/" + match.group(1) + "/" + match.group(2)
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            }
        } catch (IllegalArgumentException ignored) { }
        return value;
    }

    @GetMapping("/file/{uuid}")
    public ResponseEntity<?> getFileInfo(@PathVariable String uuid,HttpServletRequest request) {
        if (context.query(uuid)==null) return proxyMissingResource(uuid,request);
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            fileObject.setUrl(context.buildUrl(uuid));
            
            return ResponseEntity.ok(fileObject);
        } catch (Exception e) {
            log.error("获取文件信息失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }
    private ResponseEntity<?> proxyMissingResource(String uuid,HttpServletRequest request) {
        if ("1".equals(request.getHeader("X-BootFS-Forwarded"))) return ResponseEntity.notFound().build();
        String remote=clusterProxyService.getRemoteNodeUrl(uuid);
        return remote==null ? ResponseEntity.notFound().build() : clusterProxyService.proxyRequest(remote,request,null);
    }

    private void indexVirtualFileToMeiliSearch(FileObject fileObject) {
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
                    .contextUuid(context.uuid)
                    .build();
            meiliSearchService.addToFullText(doc);
        } catch (Exception e) {
            log.warn("索引虚拟文件到 MeiliSearch full_text 失败, uuid={}: {}", fileObject.getUuid(), e.getMessage());
        }
    }
    private boolean safeSegmentName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,200}\\.[tT][sS]");
    }

}
