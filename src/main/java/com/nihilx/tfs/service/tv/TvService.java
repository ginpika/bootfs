package com.nihilx.tfs.service.tv;

import com.alibaba.fastjson2.JSONObject;
import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.domain.dto.FileObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TvService {

    @Autowired
    private Context context;

    @Autowired
    private TfsConfig tfsConfig;

    private static final int CACHE_ONE_SIZE = 5;
    private static final int CACHE_TWO_SIZE = 20;
    private static final int SEGMENT_DURATION_SECONDS = 10;

    private List<TsSegment> allTsList = new ArrayList<>();
    private AtomicInteger curTsIdx = new AtomicInteger(0);

    private LinkedList<TsSegment> cacheOne = new LinkedList<>();
    private LinkedList<TsSegment> cacheTwo = new LinkedList<>();

    private int mediaSequence = 0;
    private volatile boolean initialized = false;
    private volatile boolean running = true;

    private Timer timer;

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
                startTimer();
                log.info("TvService initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize TvService", e);
            }
        }, "TvService-Init").start();
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (timer != null) {
            timer.cancel();
        }
        log.info("TvService destroyed");
    }

    private void startTimer() {
        timer = new Timer("TvService-Timer", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Exception e) {
                    log.error("Error in timer tick", e);
                }
            }
        }, SEGMENT_DURATION_SECONDS * 1000L, SEGMENT_DURATION_SECONDS * 1000L);
        log.info("Timer started, interval: {}s", SEGMENT_DURATION_SECONDS);
    }

    private synchronized void tick() {
        if (!initialized || !running) {
            return;
        }

        if (!cacheOne.isEmpty()) {
            cacheOne.removeFirst();
            mediaSequence++;
        }

        if (!cacheTwo.isEmpty()) {
            TsSegment segment = cacheTwo.removeFirst();
            cacheOne.addLast(segment);
        }

        fillCacheTwo();
    }

    private synchronized void fillCacheTwo() {
        while (cacheTwo.size() < CACHE_TWO_SIZE && !allTsList.isEmpty()) {
            int idx = curTsIdx.getAndIncrement();
            
            if (idx >= allTsList.size()) {
                curTsIdx.set(0);
                idx = 0;
            }

            if (idx < allTsList.size()) {
                TsSegment segment = allTsList.get(idx);
                cacheTwo.addLast(segment);
            }
        }
    }

    public synchronized void reinitialize() {
        log.info("Reinitializing TvService...");
        initialized = false;
        curTsIdx.set(0);
        cacheOne.clear();
        cacheTwo.clear();
        mediaSequence = 0;
        allTsList.clear();
        
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
            
            if (!initialized) {
                curTsIdx.set(0);
                cacheOne.clear();
                cacheTwo.clear();
                mediaSequence = 0;
                
                fillCacheOne();
                fillCacheTwo();
                
                initialized = true;
            }

            log.info("AllTsList refreshed: {} segments", newAllTsList.size());
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

    private void fillCacheOne() {
        while (cacheOne.size() < CACHE_ONE_SIZE && !allTsList.isEmpty()) {
            int idx = curTsIdx.getAndIncrement();
            
            if (idx >= allTsList.size()) {
                curTsIdx.set(1);
                idx = 0;
            }

            TsSegment segment = allTsList.get(idx);
            cacheOne.addLast(segment);
        }
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
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append(SEGMENT_DURATION_SECONDS).append("\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append("\n");

        String previousUuid = null;
        int previousSegmentIndex = -1;
        for (TsSegment segment : cacheOne) {
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
        status.put("curTsIdx", curTsIdx.get());
        status.put("cacheOneSize", cacheOne.size());
        status.put("cacheTwoSize", cacheTwo.size());
        status.put("mediaSequence", mediaSequence);
        status.put("totalDuration", calculateTotalDuration());
        status.put("totalDurationFormatted", formatDuration(calculateTotalDuration()));
        status.put("playlistSize", playlistUuids.size());
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

    public List<TsSegment> getCacheOne() {
        return new ArrayList<>(cacheOne);
    }

    public List<TsSegment> getCacheTwo() {
        return new ArrayList<>(cacheTwo);
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
