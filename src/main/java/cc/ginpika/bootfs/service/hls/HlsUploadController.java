package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.service.hls.HlsUploadService.HlsUploadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
class HlsUploadController {
    private final HlsUploadService hlsUploadService;

    @PostMapping("/virtual-file")
    public ResponseEntity<?> createVirtualFile(@RequestBody Map<String, String> request) {
        String fileName = request.getOrDefault("fileName", "virtual_video.mp4");
        String folder = request.getOrDefault("folder", "");
        String fileType = request.getOrDefault("fileType", "video/mp4");
        return execute("创建虚拟文件失败", () -> hlsUploadService.createVirtualFile(fileName, folder, fileType));
    }

    @PostMapping("/hls/{uuid}/segments")
    public ResponseEntity<?> uploadHlsSegment(
            @PathVariable String uuid,
            @RequestParam("segment") MultipartFile segment,
            @RequestParam("sequence") int sequence,
            @RequestParam(value = "duration", defaultValue = "10.0") double duration) {
        return execute("上传HLS切片失败", () -> hlsUploadService.uploadSegment(uuid, segment, sequence, duration));
    }

    @PostMapping("/hls/{uuid}/finalize")
    public ResponseEntity<?> finalizeHlsPlaylist(@PathVariable String uuid) {
        return execute("完成HLS播放列表失败", () -> hlsUploadService.finalizePlaylist(uuid));
    }

    @GetMapping("/hls/{uuid}/segments/info")
    public ResponseEntity<?> getHlsSegmentInfo(@PathVariable String uuid) {
        return execute("获取HLS切片信息失败", () -> hlsUploadService.segmentInfo(uuid));
    }

    @GetMapping("/hls/{uuid}/segments/list")
    public ResponseEntity<?> getHlsSegmentList(@PathVariable String uuid) {
        return execute("获取HLS切片列表失败", () -> hlsUploadService.segmentList(uuid));
    }

    @PostMapping("/hls/{uuid}/segments/reorder")
    public ResponseEntity<?> reorderHlsSegments(@PathVariable String uuid, @RequestBody List<String> segmentOrder) {
        return execute("重新排序HLS切片失败", () -> hlsUploadService.reorderSegments(uuid, segmentOrder));
    }

    @DeleteMapping("/hls/{uuid}/segments/{fileName}")
    public ResponseEntity<?> deleteHlsSegment(@PathVariable String uuid, @PathVariable String fileName) {
        return execute("删除HLS切片失败", () -> hlsUploadService.deleteSegment(uuid, fileName));
    }

    private ResponseEntity<?> execute(String failureLog, ThrowingSupplier action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (ResponseStatusException status) {
            throw status;
        } catch (HlsUploadException failure) {
            if (failure.error() == null) {
                return ResponseEntity.status(failure.status()).build();
            }
            return ResponseEntity.status(failure.status()).body(Map.of("error", failure.error()));
        } catch (Exception e) {
            log.error(failureLog, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Operation failed"));
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Map<String, Object> get() throws Exception;
    }
}
