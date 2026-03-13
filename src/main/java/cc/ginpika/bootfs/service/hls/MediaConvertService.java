package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class MediaConvertService {
    @Resource
    Context context;
    @Resource
    TfsConfig tfsConfig;
    @Resource
    ContextIO contextIO;

    public static final String EXE_SUFFIX =
            System.getProperty("os.name").toLowerCase().startsWith("win") ? ".exe" : "";

    // TODO plan refactor, this method is too lang
    public void mp4ToH264Hls(String uuid) {
        Path ffmpeg = Path.of(tfsConfig.getFfmpegUrl());

        FileObject fileObject = context.STORAGE.get(uuid);

        // output_segment
        String outputUuid = fileObject.getUuid();
        Path outputSegmentDir = Path.of(tfsConfig.getPathPrefix(), "hls", outputUuid);
        try {
            if (Files.exists(outputSegmentDir)) {
                log.info("目标 uuid 已经经过 hls 转码");
                fileObject.setHlsAvailable("1");
                contextIO.update(uuid, fileObject);
                return;
            }
            Files.createDirectories(outputSegmentDir);
        } catch (Exception e) {
            log.error("MediaConvertService Error", e);
        }
        Path outputLog = Path.of(String.valueOf(outputSegmentDir), "output.log");

        try {
            Files.createFile(outputLog);
        } catch (Exception e) {
            log.error("日志文件创建失败");
        }

        // String filename = fileObject.getFileName().substring(0, fileObject.getFileName().lastIndexOf("."));

        Path playlistPath = Path.of(String.valueOf(outputSegmentDir), "playlist.m3u8");
        Path outputSegmentPath = Path.of(String.valueOf(outputSegmentDir), "segment_%05d.ts");

        // if not run in win, chmod ffmpeg binary file for media software encoding
        if (!System.getProperty("os.name").toLowerCase().startsWith("win")) this.chmodFfmpeg();

        ProcessBuilder processBuilder = new ProcessBuilder();

        try {
            if (System.getProperty("os.name").toLowerCase().startsWith("win")) {
                // nvidia gpu encoding
                processBuilder.command(String.valueOf(ffmpeg),
                        "-hwaccel", "cuda",             // 1. 开启硬件解码加速
                        "-i", fileObject.getPath(),
                        "-c:v", "h264_nvenc",
                        "-preset", "p7",                // 2. 最高质量预设
                        "-rc", "vbr",                   // 3. 启用可变码率控制
                        "-cq", "19",                    // 4. 高画质核心参数（18-20为极佳）
                        "-b:v", "0",                    // 5. 让 cq 模式完全接管码率
                        "-profile:v", "high",
                        "-g", "120",                    // 6. GOP大小（建议为 hls_time 的整数倍，这里 20fps*6s=120）
                        "-keyint_min", "120",
                        "-sc_threshold", "0",           // 7. 禁用场景检测切换，确保切片均匀
                        "-c:a", "aac",
                        "-b:a", "192k",                 // 8. 音频码率稍微提升到 192k 匹配高画质
                        "-f", "hls",
                        "-hls_list_size", "0",
                        "-hls_time", "6",
                        "-hls_playlist_type", "event",
                        "-hls_segment_type", "mpegts",
                        "-hls_flags", "independent_segments",
                        "-hls_segment_filename", outputSegmentPath.toString(), // 9. 注意：ProcessBuilder 不需要手动加双引号
                        playlistPath.toString()
                );
            } else {
                // cpu encoding
                processBuilder.command(String.valueOf(ffmpeg),
                        "-i", fileObject.getPath(), "-c:v", "libx264", "-crf", "22.5", "-preset", "medium", "-c:a", "aac",
                        "-b:a", "128k", "-f", "hls", "-hls_list_size", "0", "-hls_time", "6", "-hls_playlist_type", "event",
                        "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments", "-hls_segment_filename",
                        "\"" + outputSegmentPath + "\"",
                        "\"" + playlistPath + "\"");
            }

            processBuilder.directory(ffmpeg.getParent().toFile());

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            log.info(processBuilder.command().toString());
            Thread pLog = new Thread(() -> logStream(process.getInputStream(), outputLog));
            pLog.start();
            process.waitFor();
            process.destroy();
            fileObject.setHlsAvailable("1");
            contextIO.update(uuid, fileObject);
        } catch (Exception e) {
            log.error("MediaConvertService", e);
        }
    }

    private static void logStream(InputStream inputStream, Path logFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            try (BufferedWriter bufferedWriter = Files.newBufferedWriter(logFile)) {
                while ((line = reader.readLine()) != null) {
                    // log.info("[ffmpeg] {}", line);
                    bufferedWriter.write("[" + System.currentTimeMillis() + "] " + line);
                    bufferedWriter.newLine();
                }
            }
        } catch (IOException e) {
            log.error("读取输出流失败", e);
        }
    }

    private void chmodFfmpeg() {
        try  {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("chmod", "777", tfsConfig.getFfmpegUrl());
            Process process = processBuilder.start();
            process.waitFor();
            process.destroy();
        } catch (IOException | InterruptedException e) {
            log.error("chmod ffmpeg failed", e);
        }
    }
}
