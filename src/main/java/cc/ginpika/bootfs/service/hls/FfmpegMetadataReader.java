package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.MediaProcess;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegMetadataReader {
    private final TfsConfig config;

    public record CoverArt(byte[] bytes, String contentType) {}

    public CoverArt readCover(String filePath) throws IOException, InterruptedException {
        ProcessBuilder process = new ProcessBuilder(config.getFfmpegUrl(),
                "-v", "quiet", "-i", filePath, "-an", "-vcodec", "copy", "-f", "image2pipe", "-");
        process.redirectError(ProcessBuilder.Redirect.DISCARD);
        byte[] bytes = MediaProcess.capture(MediaProcess.restrictInput(process), 10, 8 * 1024 * 1024);
        return new CoverArt(bytes, detectImageMime(bytes));
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

    public JSONObject probe(String filePath) {
        JSONObject info = new JSONObject();

        String ffmpegUrl = config.getFfmpegUrl();
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
            var args = new ArrayList<>(MediaProcess.restrictInput(pb).command());
            args.remove("-nostdin");
            pb.command(args);
            byte[] output = MediaProcess.capture(pb, 10, 2 * 1024 * 1024);
            JSONObject probeResult = JSONObject.parseObject(new String(output, StandardCharsets.UTF_8));

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
