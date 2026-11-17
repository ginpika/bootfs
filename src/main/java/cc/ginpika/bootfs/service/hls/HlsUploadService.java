package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.FolderPath;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/** Manages virtual HLS video resources, segment storage, and M3U8 playlist updates. */
@Slf4j
@Service
@RequiredArgsConstructor
public class HlsUploadService {
    private final LocalFileInventory context;
    private final TfsConfig tfsConfig;
    private final ResourceSearchService resourceSearch;
    private final HlsFileService hlsFiles;

    static final class HlsUploadException extends RuntimeException {
        private final HttpStatus status;
        private final String error;

        HlsUploadException(HttpStatus status, String error) {
            super(error);
            this.status = status;
            this.error = error;
        }

        HttpStatus status() { return status; }
        String error() { return error; }
    }

    public Map<String, Object> createVirtualFile(String fileName, String folder, String fileType) throws IOException {
        String logicalName = "".equals(folder) ? fileName : FolderPath.uploadKey(folder, fileName);
        String uuid = IdGenerator.getUniqueId();
        String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();

        FileObject fileObject = FileObject.builder()
                .path(filePath)
                .uuid(uuid)
                .fileName(logicalName)
                .size(0L)
                .hlsAvailable("1")
                .build();

        Path hlsDir = hlsFiles.directory(uuid);
        Files.createDirectories(hlsDir);
        HlsPlaylist.writeInitial(hlsFiles.playlistPath(uuid));

        // HLS files have a catalog path. The playlist and segments are stored by UUID.
        context.record(fileObject, uuid);
        indexVirtualFile(fileObject);

        log.info("Created virtual FileObject with uuid: {}, hlsAvailable: {}", uuid, fileObject.getHlsAvailable());

        Map<String, Object> response = new HashMap<>();
        response.put("uuid", uuid);
        response.put("fileName", fileName);
        response.put("fileType", fileType);
        response.put("hlsAvailable", "1");
        response.put("url", context.buildUrl(uuid));
        response.put("hlsUrl", playlistUrl(uuid));
        return response;
    }

    public synchronized Map<String, Object> uploadSegment(String uuid, MultipartFile segment, int sequence, double duration) throws IOException {
        FileObject fileObject = requireResource(uuid);
        if (!"1".equals(fileObject.getHlsAvailable())) {
            throw new HlsUploadException(HttpStatus.BAD_REQUEST, "FileObject is not HLS enabled");
        }
        if (segment == null || segment.isEmpty()) {
            throw new HlsUploadException(HttpStatus.BAD_REQUEST, "Segment file is empty");
        }
        String originalFilename = segment.getOriginalFilename();
        if (!hlsFiles.safeSegmentName(originalFilename) || sequence < 0 || !Double.isFinite(duration) || duration <= 0) {
            throw new HlsUploadException(HttpStatus.BAD_REQUEST, "Only .ts files are allowed");
        }

        Path hlsDir = hlsFiles.directory(uuid);
        if (!Files.exists(hlsDir)) {
            Files.createDirectories(hlsDir);
        }

        Path segmentPath = hlsFiles.segmentPath(uuid, originalFilename);
        segment.transferTo(segmentPath);
        long segmentSize = Files.size(segmentPath);

        HlsPlaylist.appendSegment(hlsFiles.playlistPath(uuid), originalFilename, duration);
        log.info("Uploaded HLS segment {} for uuid: {}, size: {} bytes", originalFilename, uuid, segmentSize);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("uuid", uuid);
        response.put("sequence", sequence);
        response.put("segmentFileName", originalFilename);
        response.put("segmentSize", segmentSize);
        response.put("duration", duration);
        response.put("m3u8Url", playlistUrl(uuid));
        return response;
    }

    public synchronized Map<String, Object> finalizePlaylist(String uuid) throws IOException {
        requireResource(uuid);
        Path m3u8Path = hlsFiles.playlistPath(uuid);
        if (!Files.exists(m3u8Path)) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "M3U8 playlist not found");
        }
        HlsPlaylist.finalizePlaylist(m3u8Path);
        log.info("Finalized HLS playlist for uuid: {}", uuid);
        return Map.of("success", true, "uuid", uuid, "message", "HLS playlist finalized");
    }

    public Map<String, Object> segmentInfo(String uuid) throws IOException {
        FileObject fileObject = requireResource(uuid);
        Path hlsDir = hlsFiles.directory(uuid);
        if (!Files.exists(hlsDir)) {
            return Map.of("uuid", uuid, "segmentCount", 0, "totalSize", 0L, "hasPlaylist", false);
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

        Path m3u8Path = hlsFiles.playlistPath(uuid);
        boolean hasPlaylist = Files.exists(m3u8Path);
        boolean isFinalized = hasPlaylist && HlsPlaylist.isFinalized(m3u8Path);

        return Map.of(
                "uuid", uuid,
                "segmentCount", segmentCount,
                "totalSize", totalSize,
                "hasPlaylist", hasPlaylist,
                "isFinalized", isFinalized,
                "fileName", fileObject.getFileName()
        );
    }

    public Map<String, Object> segmentList(String uuid) throws IOException {
        requireResource(uuid);
        Path hlsDir = hlsFiles.directory(uuid);
        if (!Files.exists(hlsDir)) {
            return Map.of("segments", new ArrayList<>(), "uuid", uuid);
        }

        List<Map<String, Object>> segments = new ArrayList<>();
        Path m3u8Path = hlsFiles.playlistPath(uuid);
        if (Files.exists(m3u8Path)) {
            List<HlsPlaylist.SegmentEntry> entries = HlsPlaylist.parseSegments(Files.readString(m3u8Path));
            for (int i = 0; i < entries.size(); i++) {
                HlsPlaylist.SegmentEntry entry = entries.get(i);
                Path segmentPath = hlsDir.resolve(entry.fileName());
                long size = Files.exists(segmentPath) ? Files.size(segmentPath) : 0;
                segments.add(segmentRow(i, uuid, entry.fileName(), entry.duration(), size));
            }
        } else {
            File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.endsWith(".ts"));
            if (tsFiles != null) {
                Arrays.sort(tsFiles, Comparator.comparing(File::getName));
                for (int i = 0; i < tsFiles.length; i++) {
                    File tsFile = tsFiles[i];
                    segments.add(segmentRow(i, uuid, tsFile.getName(), HlsPlaylist.DEFAULT_DURATION, tsFile.length()));
                }
            }
        }
        return Map.of("segments", segments, "uuid", uuid);
    }

    private Map<String, Object> segmentRow(int index, String uuid, String fileName, double duration, long size) {
        return Map.of(
                "index", index,
                "fileName", fileName,
                "duration", duration,
                "size", size,
                "url", "/hls/" + uuid + "/" + fileName
        );
    }

    public synchronized Map<String, Object> reorderSegments(String uuid, List<String> segmentOrder) throws IOException {
        if (segmentOrder == null || segmentOrder.size() > 10000
                || segmentOrder.stream().anyMatch(name -> !hlsFiles.safeSegmentName(name))
                || new HashSet<>(segmentOrder).size() != segmentOrder.size()) {
            throw new HlsUploadException(HttpStatus.BAD_REQUEST, null);
        }
        requireResource(uuid);
        Path hlsDir = hlsFiles.directory(uuid);
        if (!Files.exists(hlsDir)) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "HLS directory not found");
        }
        Path m3u8Path = hlsFiles.playlistPath(uuid);
        if (!Files.exists(m3u8Path)) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "M3U8 playlist not found");
        }

        String originalContent = Files.readString(m3u8Path);
        boolean wasFinalized = originalContent.contains("#EXT-X-ENDLIST");
        Map<String, Double> durations = HlsPlaylist.parseDurations(originalContent);

        List<HlsPlaylist.SegmentEntry> ordered = new ArrayList<>(segmentOrder.size());
        for (String fileName : segmentOrder) {
            Path segmentPath = hlsFiles.segmentPath(uuid, fileName);
            if (!Files.isRegularFile(segmentPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new HlsUploadException(HttpStatus.BAD_REQUEST, null);
            }
            ordered.add(new HlsPlaylist.SegmentEntry(fileName, durations.getOrDefault(fileName, HlsPlaylist.DEFAULT_DURATION)));
        }
        HlsPlaylist.writeOrdered(m3u8Path, ordered, wasFinalized);
        log.info("Reordered HLS segments for uuid: {}", uuid);
        return Map.of("success", true, "uuid", uuid, "segmentCount", segmentOrder.size());
    }

    public synchronized Map<String, Object> deleteSegment(String uuid, String fileName) throws IOException {
        if (!hlsFiles.safeSegmentName(fileName)) {
            throw new HlsUploadException(HttpStatus.BAD_REQUEST, null);
        }
        requireResource(uuid);
        Path segmentPath = hlsFiles.segmentPath(uuid, fileName);
        if (!Files.exists(segmentPath)) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "Segment not found: " + fileName);
        }
        Files.delete(segmentPath);
        HlsPlaylist.removeSegment(hlsFiles.playlistPath(uuid), fileName);
        log.info("Deleted HLS segment {} for uuid: {}", fileName, uuid);
        return Map.of("success", true, "uuid", uuid, "deletedSegment", fileName);
    }

    private FileObject requireResource(String uuid) {
        if (!hlsFiles.validFolderId(uuid) || hlsFiles.isDeleted(uuid)) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "FileObject not found: " + uuid);
        }
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) {
            throw new HlsUploadException(HttpStatus.NOT_FOUND, "FileObject not found: " + uuid);
        }
        return fileObject;
    }

    private String playlistUrl(String uuid) {
        return tfsConfig.getWebEntrypoint() + "/hls/" + uuid + "/playlist.m3u8";
    }

    private void indexVirtualFile(FileObject fileObject) {
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
            resourceSearch.addToFullText(doc);
        } catch (Exception e) {
            log.warn("索引虚拟文件失败, uuid={}: {}", fileObject.getUuid(), e.getMessage());
        }
    }
}
