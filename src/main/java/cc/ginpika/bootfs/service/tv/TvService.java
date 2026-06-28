package cc.ginpika.bootfs.service.tv;

import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TvService {

    @Autowired
    private Context context;

    @Autowired
    private TfsConfig tfsConfig;

    private static final int PLAYLIST_WINDOW_SIZE = 5;

    private List<TsSegment> allTsList = new ArrayList<>();
    private double totalDuration = 0;
    private double maxSegmentDuration = 10;

    private volatile long streamStartTimestamp = 0;
    private volatile boolean initialized = false;
    private long mediaSequence = 0;
    private int lastCurrentIdx = -1;

    private List<String> playlistUuids = new ArrayList<>();
    private Path playlistConfigPath;

    @Data
    public static class HlsVideoInfo {
        private String uuid;
        private String fileName;
        private int segmentCount;
        private double totalDuration;
        private boolean enabled;
        private int order;
    }

    @Data
    public static class TsSegment {
        private String uuid;
        private String fileName;
        private double duration;
        private int segmentIndex;
    }

    @PostConstruct
    public void init() {
        playlistConfigPath = Path.of(tfsConfig.getPathPrefix(), "tv-playlist.json");
        loadPlaylistConfig();

        log.info("TvService initializing...");

        new Thread(() -> {
            try {
                int waitCount = 0;
                while (context.STORAGE.isEmpty() && waitCount < 30) {
                    Thread.sleep(1000);
                    waitCount++;
                    log.info("Waiting for Context to load... ({})", waitCount);
                }

                refreshAllTsList();
                log.info("TvService initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize TvService", e);
            }
        }, "TvService-Init").start();
    }

    public synchronized void reinitialize() {
        log.info("Reinitializing TvService...");
        allTsList.clear();
        totalDuration = 0;
        maxSegmentDuration = 10;
        lastCurrentIdx = -1;
        mediaSequence = 0;

        refreshAllTsList();
        log.info("TvService reinitialized");
    }

    public synchronized void refreshAllTsList() {
        try {
            List<FileObject> hlsFiles = getHlsFilesInOrder();

            log.info("Found {} HLS videos in playlist", hlsFiles.size());

            List<TsSegment> newAllTsList = new ArrayList<>();

            for (FileObject file : hlsFiles) {
                List<TsSegment> segments = parseHlsSegments(file);
                if (segments != null && !segments.isEmpty()) {
                    newAllTsList.addAll(segments);
                    log.info("Parsed {} segments from {}", segments.size(), file.getFileName());
                }
            }

            this.allTsList = newAllTsList;

            this.totalDuration = newAllTsList.stream()
                    .mapToDouble(TsSegment::getDuration)
                    .sum();
            this.maxSegmentDuration = newAllTsList.stream()
                    .mapToDouble(TsSegment::getDuration)
                    .max()
                    .orElse(10);

            if (!initialized) {
                this.streamStartTimestamp = System.currentTimeMillis();
                initialized = true;
            }

            log.info("AllTsList refreshed: {} segments, total duration: {}s",
                    newAllTsList.size(), String.format("%.1f", totalDuration));
        } catch (Exception e) {
            log.error("Failed to refresh allTsList", e);
        }
    }

    private List<FileObject> getHlsFilesInOrder() {
        List<FileObject> allFiles = new ArrayList<>(context.STORAGE.values());

        Map<String, FileObject> hlsFileMap = allFiles.stream()
                .filter(f -> "1".equalsIgnoreCase(f.getHlsAvailable()))
                .collect(Collectors.toMap(FileObject::getUuid, f -> f, (a, b) -> a));

        List<FileObject> orderedFiles = new ArrayList<>();

        if (playlistUuids.isEmpty()) {
            return new ArrayList<>(hlsFileMap.values());
        }

        for (String uuid : playlistUuids) {
            FileObject file = hlsFileMap.get(uuid);
            if (file != null) {
                orderedFiles.add(file);
            }
        }

        return orderedFiles;
    }

    private List<TsSegment> parseHlsSegments(FileObject file) {
        try {
            Path m3u8Path = Path.of(tfsConfig.getPathPrefix(), "hls", file.getUuid(), "playlist.m3u8");
            if (!Files.exists(m3u8Path)) {
                log.warn("M3U8 file not found for uuid: {}", file.getUuid());
                return null;
            }

            List<TsSegment> segments = new ArrayList<>();
            int segmentIndex = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(m3u8Path.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF:")) {
                        String durationStr = line.substring(8).replace(",", "").trim();
                        try {
                            double duration = Double.parseDouble(durationStr);

                            String tsFile = reader.readLine();
                            if (tsFile != null && tsFile.endsWith(".ts")) {
                                TsSegment segment = new TsSegment();
                                segment.setUuid(file.getUuid());
                                segment.setFileName(tsFile.trim());
                                segment.setDuration(duration);
                                segment.setSegmentIndex(segmentIndex++);
                                segments.add(segment);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse duration: {}", durationStr);
                        }
                    }
                }
            }

            return segments;
        } catch (Exception e) {
            log.error("Failed to parse HLS video: {}", file.getUuid(), e);
            return null;
        }
    }

    public synchronized String generatePlaylistM3u8() {
        if (allTsList.isEmpty()) {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n";
        }

        // 服务端时钟减一点缓冲，避免因系统时钟微小偏差让播放器被推着跳帧
        double elapsed = (System.currentTimeMillis() - streamStartTimestamp) / 1000.0 - 3.0;
        if (elapsed < 0) elapsed = 0;
        double positionInCycle = elapsed % totalDuration;

        // 根据时间流逝计算当前应播到的切片位置
        int currentIdx = 0;
        double accumulated = 0;
        for (int i = 0; i < allTsList.size(); i++) {
            accumulated += allTsList.get(i).getDuration();
            if (accumulated > positionInCycle) {
                currentIdx = i;
                break;
            }
        }

        // 基于 currentIdx 的实际变化推进序列号，保证单调递增且不跳变
        if (lastCurrentIdx >= 0 && currentIdx != lastCurrentIdx) {
            int advance = (currentIdx - lastCurrentIdx + allTsList.size()) % allTsList.size();
            if (advance == 0) advance = allTsList.size();
            mediaSequence += advance;
        }
        lastCurrentIdx = currentIdx;

        // 生成当前窗口的 playlist
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(maxSegmentDuration)).append("\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append("\n");

        String previousUuid = null;
        int previousSegmentIndex = -1;
        for (int i = 0; i < PLAYLIST_WINDOW_SIZE; i++) {
            int idx = (currentIdx + i) % allTsList.size();
            TsSegment segment = allTsList.get(idx);

            boolean isDifferentSource = previousUuid != null && !previousUuid.equals(segment.getUuid());
            boolean isLoopPlayback = previousSegmentIndex >= 0 && segment.getSegmentIndex() <= previousSegmentIndex;

            if (isDifferentSource || isLoopPlayback) {
                sb.append("#EXT-X-DISCONTINUITY\n");
            }

            sb.append("#EXTINF:").append(String.format("%.3f", segment.getDuration())).append(",\n");
            sb.append("/tv/ts/").append(segment.getUuid()).append("/").append(segment.getFileName()).append("\n");

            previousUuid = segment.getUuid();
            previousSegmentIndex = segment.getSegmentIndex();
        }

        return sb.toString();
    }

    public Resource getTsSegment(String uuid, String fileName) {
        Path tsPath = Path.of(tfsConfig.getPathPrefix(), "hls", uuid, fileName);
        if (!Files.exists(tsPath)) {
            log.warn("TS file not found: {}", tsPath);
            return null;
        }
        return new FileSystemResource(tsPath);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", initialized);
        status.put("allTsListSize", allTsList.size());
        status.put("totalDuration", totalDuration);
        status.put("totalDurationFormatted", formatDuration(totalDuration));
        status.put("maxSegmentDuration", maxSegmentDuration);
        status.put("playlistWindowSize", PLAYLIST_WINDOW_SIZE);
        status.put("playlistSize", playlistUuids.size());
        status.put("streamStartTimestamp", streamStartTimestamp);

        if (initialized && !allTsList.isEmpty()) {
            double elapsed = (System.currentTimeMillis() - streamStartTimestamp) / 1000.0;
            status.put("elapsedSeconds", String.format("%.1f", elapsed));
            status.put("currentCyclePosition", String.format("%.1f", elapsed % totalDuration));
        }

        return status;
    }

    private double calculateTotalDuration() {
        return allTsList.stream()
                .mapToDouble(TsSegment::getDuration)
                .sum();
    }

    private String formatDuration(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);

        if (hours > 0) {
            return String.format("%d小时%d分钟%d秒", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, secs);
        } else {
            return String.format("%d秒", secs);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ========== 管理功能 ==========

    public List<HlsVideoInfo> getAllHlsVideos() {
        List<FileObject> allFiles = new ArrayList<>(context.STORAGE.values());

        return allFiles.stream()
                .filter(f -> "1".equalsIgnoreCase(f.getHlsAvailable()))
                .map(f -> {
                    HlsVideoInfo info = new HlsVideoInfo();
                    info.setUuid(f.getUuid());
                    info.setFileName(f.getFileName());
                    info.setEnabled(playlistUuids.contains(f.getUuid()));
                    info.setOrder(playlistUuids.indexOf(f.getUuid()));

                    List<TsSegment> segments = parseHlsSegments(f);
                    if (segments != null) {
                        info.setSegmentCount(segments.size());
                        info.setTotalDuration(segments.stream().mapToDouble(TsSegment::getDuration).sum());
                    } else {
                        info.setSegmentCount(0);
                        info.setTotalDuration(0);
                    }

                    return info;
                })
                .sorted((a, b) -> {
                    if (a.getOrder() < 0 && b.getOrder() < 0) return a.getFileName().compareTo(b.getFileName());
                    if (a.getOrder() < 0) return 1;
                    if (b.getOrder() < 0) return -1;
                    return Integer.compare(a.getOrder(), b.getOrder());
                })
                .collect(Collectors.toList());
    }

    public synchronized void savePlaylist(List<String> uuids) {
        this.playlistUuids = new ArrayList<>(uuids);
        savePlaylistConfig();
        reinitialize();
    }

    private void loadPlaylistConfig() {
        try {
            if (Files.exists(playlistConfigPath)) {
                String content = Files.readString(playlistConfigPath);
                JSONObject json = JSONObject.parseObject(content);
                this.playlistUuids = json.getList("uuids", String.class);
                log.info("Loaded playlist config: {} videos", playlistUuids.size());
            } else {
                this.playlistUuids = new ArrayList<>();
                log.info("No playlist config found, using default");
            }
        } catch (Exception e) {
            log.error("Failed to load playlist config", e);
            this.playlistUuids = new ArrayList<>();
        }
    }

    private void savePlaylistConfig() {
        try {
            JSONObject json = new JSONObject();
            json.put("uuids", playlistUuids);

            Files.writeString(playlistConfigPath, json.toJSONString());
            log.info("Saved playlist config: {} videos", playlistUuids.size());
        } catch (IOException e) {
            log.error("Failed to save playlist config", e);
        }
    }

    public List<String> getPlaylistUuids() {
        return new ArrayList<>(playlistUuids);
    }
}
