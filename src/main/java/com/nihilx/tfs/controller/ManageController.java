package com.nihilx.tfs.controller;

import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.core.IdGenerator;
import com.nihilx.tfs.domain.dto.FileObject;
import com.nihilx.tfs.service.FileService;
import com.nihilx.tfs.service.FileTransferService;
import com.nihilx.tfs.domain.result.TransferResult;
import com.nihilx.tfs.service.ReverseProxyService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// ManegeController provide basic function for TFS
@Slf4j
@RestController
@CrossOrigin
@SuppressWarnings("all")
public class ManageController {
    @Autowired
    FileService fileService;
    @Autowired
    FileTransferService fileTransferService;
    @Autowired
    ReverseProxyService reverseProxyService;
    @Autowired
    Context context;
    @Autowired
    TfsConfig tfsConfig;

    // upload file
    @PutMapping("/f")
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file) {
        String url = "";
        try {
            log.info("Uploading file: {}", file.getOriginalFilename());
            url = fileService.upload(file);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().body(url);
    }

    // download file & cluster proxy based on this api
    @GetMapping("/f/{uuid}")
    public ResponseEntity<Resource> download(@PathVariable String uuid,
                                             HttpServletRequest request) throws IOException {
        Object[] ret = fileService.download(uuid, request);
        if (ret == null) {
            return ResponseEntity.notFound().build();
        }
        // support range-request for media resource
        if (StringUtils.isNotBlank(request.getHeader("Is-Range-Request"))) {
            String contentType = request.getHeader("Accept-Type");
            if (StringUtils.isBlank(contentType)) {
                contentType = "video/mp4";
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, (String) ret[2])
                    .body((Resource)ret[0]);
        } else if (StringUtils.isNotBlank(request.getHeader("Is-Proxy"))) {
            String contentType = request.getHeader("Accept-Type");
            if (StringUtils.isBlank(contentType)) {
                contentType = "video/mp4";
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, (String) ret[2])
                    .body((Resource)ret[0]);
        }
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename((String) ret[1], StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(contentDisposition);
        headers.setContentLength(Long.parseLong((String) ret[2]));
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .headers(headers)
                .body((Resource)ret[0]);
    }

    // reverse proxy file
    @GetMapping("/p/{uuid}")
    public void reverseProxy(@PathVariable String uuid,
                             HttpServletResponse response,
                             HttpServletRequest request) {
        reverseProxyService.reverseProxyFile(uuid, response, request);
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
        try {
            String fileName = request.getOrDefault("fileName", "virtual_video.mp4");
            String fileType = request.getOrDefault("fileType", "video/mp4");
            
            String uuid = IdGenerator.getUniqueId();
            String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
            
            FileObject fileObject = FileObject.builder()
                    .path(filePath)
                    .uuid(uuid)
                    .fileName(fileName)
                    .size(0L)
                    .hlsAvailable("1")
                    .build();
            
            context.record(fileObject, uuid);
            
            Path hlsDir = Path.of(tfsConfig.getPathPrefix(), "hls", uuid);
            Files.createDirectories(hlsDir);
            
            Path m3u8Path = hlsDir.resolve("playlist.m3u8");
            String initialM3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:EVENT\n";
            Files.writeString(m3u8Path, initialM3u8);
            
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".ts")) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hls/{uuid}/segments/reorder")
    public ResponseEntity<?> reorderHlsSegments(@PathVariable String uuid, @RequestBody java.util.List<String> segmentOrder) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/hls/{uuid}/segments/{fileName}")
    public ResponseEntity<?> deleteHlsSegment(@PathVariable String uuid, @PathVariable String fileName) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/file/{uuid}")
    public ResponseEntity<?> getFileInfo(@PathVariable String uuid) {
        try {
            FileObject fileObject = context.query(uuid);
            if (fileObject == null) {
                return ResponseEntity.status(404).body(Map.of("error", "FileObject not found: " + uuid));
            }
            
            fileObject.setUrl(context.buildUrl(uuid));
            
            return ResponseEntity.ok(fileObject);
        } catch (Exception e) {
            log.error("获取文件信息失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
