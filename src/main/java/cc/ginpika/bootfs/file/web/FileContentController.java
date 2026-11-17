package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.FileDownload;
import cc.ginpika.bootfs.file.FileService;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.cluster.ResourceVisibility;
import cc.ginpika.bootfs.search.ResourceSearchService;
import cc.ginpika.bootfs.service.ClusterProxyService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
class FileContentController {
    private static final Pattern ALBUM_RESOURCE_PATH = Pattern.compile(".*/(thumb|p|f)/([A-Za-z0-9_-]+)");
    private final FileService fileService;
    private final ReverseProxyService reverseProxyService;
    private final ResourceSearchService resourceSearch;
    private final LocalFileInventory context;
    private final ThumbnailService thumbnailService;
    private final ClusterProxyService clusterProxyService;
    private final ResourceVisibility visibility;
    private final ResourceRequestForwarder forwarder;

    @ExceptionHandler(ResourceWriteConflictException.class)
    void conflict(ResourceWriteConflictException failure, HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.CONFLICT.value(), failure.getMessage());
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
        return ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(resourceSearch.visibleRevision())).body(url);
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
        } catch (ResourceWriteConflictException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict.getMessage());
        } catch (ResponseStatusException invalidPath) {
            throw invalidPath;
        } catch (Exception e) {
            log.error("图包上传失败", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(resourceSearch.visibleRevision())).body(url);
    }

    // download file & cluster proxy based on this api
    @GetMapping("/f/{uuid}")
    public ResponseEntity<Resource> download(@PathVariable String uuid,
                                             HttpServletRequest request) throws IOException {
        FileDownload download = fileService.openDownload(uuid);
        if (download == null) {
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
        if (download.etag() != null) contentHeaders.setETag("\"" + download.etag() + "\"");
        if (download.revalidate()) contentHeaders.setCacheControl("no-cache");
        String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(contentHeaders.getETag())) {
            return ResponseEntity.status(412).headers(contentHeaders).build();
        }
        // support range-request for media resource
        if (StringUtils.isNotBlank(request.getHeader("Is-Range-Request"))
                || StringUtils.isNotBlank(request.getHeader("Is-Proxy"))) {
            String contentType = inlineType(download.filename());
            return ResponseEntity.ok()
                    .headers(contentHeaders)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .contentLength(download.length())
                    .body(new FileSystemResource(download.path()));
        }
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(contentHeaders);
        headers.setContentDisposition(contentDisposition);
        headers.setContentLength(download.length());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .headers(headers)
                .body(new FileSystemResource(download.path()));
    }

    // reverse proxy file
    @GetMapping("/p/{uuid}")
    public ResponseEntity<?> reverseProxy(@PathVariable String uuid,
                             HttpServletResponse response,
                             HttpServletRequest request) throws IOException {
        // Local reads must not loop through an unauthenticated HTTP request to /f.
        if (context.query(uuid) != null) {
            FileDownload file = fileService.openDownload(uuid);
            if (file == null) return ResponseEntity.notFound().build();
            String type = inlineType(file.filename());
            if (MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(type)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                    .contentLength(file.length())
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", "sandbox; default-src 'none'")
                    .body(new FileSystemResource(file.path()));
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
            return forwarder.forwardMissing(uuid, request, null);
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

    // 获取相册（comic）的 poster 和 resources，封装索引查询避免前端 mixed content
    @GetMapping("/api/album/{uuid}")
    public ResponseEntity<?> getAlbumDocument(@PathVariable String uuid,HttpServletRequest request) {
        if (context.query(uuid)==null) return forwarder.forwardMissing(uuid, request, null);
        Map<String, Object> result = resourceSearch.getDocument("full-text", uuid);
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
        if (context.query(uuid)==null) return forwarder.forwardMissing(uuid, request, null);
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
}
