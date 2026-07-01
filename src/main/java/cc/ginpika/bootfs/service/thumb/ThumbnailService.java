package cc.ginpika.bootfs.service.thumb;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.FileObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {
    private final TfsConfig tfsConfig;
    private final Context context;
    private final ContextIO contextIO;

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif");
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".webm", ".avi", ".mov", ".mkv", ".flv", ".wmv", ".m4v", ".ts");

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "thumbnail-generator");
        t.setDaemon(true);
        return t;
    });

    public Path thumbPath(String uuid) {
        return Path.of(tfsConfig.getPathPrefix(), "thumb", uuid + ".webp");
    }

    public void generateAsync(String uuid) {
        CompletableFuture.runAsync(() -> {
            try {
                generate(uuid);
            } catch (Exception e) {
                log.error("生成缩略图失败: {}", uuid, e);
            }
        }, executor);
    }

    public void generate(String uuid) throws IOException, InterruptedException {
        FileObject fileObject = context.query(uuid);
        if (fileObject == null) return;

        String fileName = fileObject.getFileName();
        if (fileName == null) return;
        int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx < 0) return;
        String ext = fileName.substring(dotIdx).toLowerCase();
        if (!IMAGE_EXTENSIONS.contains(ext) && !VIDEO_EXTENSIONS.contains(ext)) return;

        Path sourceFile = Path.of(fileObject.getPath());
        if (!Files.exists(sourceFile)) {
            log.warn("源文件不存在，跳过缩略图生成: {}", uuid);
            return;
        }

        Path thumbFile = thumbPath(uuid);
        if (Files.exists(thumbFile)) return;

        Path thumbDir = thumbFile.getParent();
        if (!Files.exists(thumbDir)) {
            Files.createDirectories(thumbDir);
        }

        String ffmpeg = tfsConfig.getFfmpegUrl();
        int width = tfsConfig.getThumbnailWidth();
        int quality = tfsConfig.getThumbnailQuality();

        ProcessBuilder pb;
        if (VIDEO_EXTENSIONS.contains(ext)) {
            // 视频：提取第一帧 (-ss 放前面加速解码)
            pb = new ProcessBuilder(
                    ffmpeg,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-ss", "0.5",  // 从0.5秒开始，避免黑帧
                    "-i", sourceFile.toString(),
                    "-vframes", "1",
                    "-vf", String.format("scale='min(%d,iw)':-2", width),
                    "-q:v", "2",
                    "-y",
                    thumbFile.toString()
            );
        } else {
            // 图片：直接缩放
            pb = new ProcessBuilder(
                    ffmpeg,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", sourceFile.toString(),
                    "-vf", String.format("scale='min(%d,iw)':-2", width),
                    "-quality", String.valueOf(quality),
                    "-y",
                    thumbFile.toString()
            );
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("ffmpeg 生成缩略图失败，退出码 {}，输出: {}", exitCode, new String(output));
            Files.deleteIfExists(thumbFile);
        } else {
            fileObject.setThumbAvailable("1");
            contextIO.update(uuid, fileObject);
            log.info("缩略图已生成: {} -> {}", uuid, thumbFile);
        }
    }
}
