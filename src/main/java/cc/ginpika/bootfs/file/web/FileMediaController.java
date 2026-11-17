package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.service.ai.AiMetadataService;
import cc.ginpika.bootfs.service.hls.FfmpegMetadataReader;
import cc.ginpika.bootfs.service.hls.FfmpegMetadataReader.CoverArt;
import cc.ginpika.bootfs.service.ClusterProxyService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Slf4j
@RestController
@RequiredArgsConstructor
class FileMediaController {
    private final LocalFileInventory context;
    private final ClusterProxyService clusterProxyService;
    private final AiMetadataService aiMetadataService;
    private final FfmpegMetadataReader media;

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
            String lower = fileName.toLowerCase(Locale.ROOT);
            boolean isVideo = lower.matches(".*\\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$");
            boolean isAudio = lower.matches(".*\\.(mp3|wav|flac|aac|ogg|wma|m4a|opus)$");
            if (isVideo || isAudio) {
                try {
                    JSONObject mediaInfo = media.probe(fileObject.getPath());
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

        try {
            CoverArt cover = media.readCover(fileObject.getPath());
            if (cover.bytes().length == 0) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(cover.contentType()))
                    .header("Cache-Control", "private, max-age=3600")
                    .body(cover.bytes());
        } catch (Exception e) {
            log.warn("提取封面失败: {} - {}", uuid, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
