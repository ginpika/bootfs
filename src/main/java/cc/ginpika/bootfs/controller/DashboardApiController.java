package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.metrics.AppStartupTracker;
import cc.ginpika.bootfs.metrics.RequestStatsService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.*;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/api/dashboard")
public class DashboardApiController {

    private final Context context;
    private final TfsConfig tfsConfig;
    private final ObjectProvider<EtcdService> etcdServiceProvider;
    private final RequestStatsService requestStatsService;
    private final AppStartupTracker appStartupTracker;

    public DashboardApiController(Context context, TfsConfig tfsConfig,
                                  ObjectProvider<EtcdService> etcdServiceProvider,
                                  RequestStatsService requestStatsService,
                                  AppStartupTracker appStartupTracker) {
        this.context = context;
        this.tfsConfig = tfsConfig;
        this.etcdServiceProvider = etcdServiceProvider;
        this.requestStatsService = requestStatsService;
        this.appStartupTracker = appStartupTracker;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("succeed", true);
            result.put("data", buildStats());
        } catch (Exception e) {
            log.error("获取 dashboard 统计数据失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildStats() {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("disk", buildDiskStats());
        data.put("resources", buildResourceStats());
        data.put("fileTypes", buildFileTypeStats());
        data.put("cluster", buildClusterStats());
        data.putAll(requestStatsService.buildStats());
        data.put("appStartTime", appStartupTracker.getReadyTime());
        return data;
    }

    private Map<String, Object> buildDiskStats() {
        Map<String, Object> disk = new LinkedHashMap<>();
        File dataDir = new File(tfsConfig.getPathPrefix());
        long totalBytes = dataDir.getTotalSpace();
        long usableBytes = dataDir.getUsableSpace();
        long usedBytes = Math.max(0, totalBytes - usableBytes);
        double usagePercent = totalBytes > 0 ? (usedBytes * 100.0 / totalBytes) : 0.0;
        disk.put("totalBytes", totalBytes);
        disk.put("usableBytes", usableBytes);
        disk.put("usedBytes", usedBytes);
        disk.put("usagePercent", Math.round(usagePercent * 100) / 100.0);
        disk.put("path", tfsConfig.getPathPrefix());
        return disk;
    }

    private Map<String, Object> buildResourceStats() {
        Map<String, Object> resources = new LinkedHashMap<>();
        Collection<FileObject> all = context.STORAGE.values();
        long totalSize = 0;
        int replicaCount = 0;
        for (FileObject fo : all) {
            if (fo.getSize() != null) {
                totalSize += fo.getSize();
            }
            if (StringUtils.isNotBlank(fo.getCopyOf())) {
                replicaCount++;
            }
        }
        int total = context.STORAGE.size();
        int mainCount = Math.max(0, total - replicaCount);

        resources.put("total", total);
        resources.put("mainCount", mainCount);
        resources.put("replicaCount", replicaCount);
        resources.put("totalSizeBytes", totalSize);
        return resources;
    }

    private List<Map<String, Object>> buildFileTypeStats() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String cat : new String[]{"image", "video", "audio", "doc", "zip", "other"}) {
            counts.put(cat, 0);
        }
        for (FileObject fo : context.STORAGE.values()) {
            counts.merge(categorize(fo.getFileName()), 1, Integer::sum);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", e.getKey());
            item.put("count", e.getValue());
            list.add(item);
        }
        return list;
    }

    private String categorize(String fileName) {
        if (StringUtils.isBlank(fileName)) return "other";
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "other";
        String ext = fileName.substring(idx + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "svg":
                return "image";
            case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv": case "webm":
                return "video";
            case "mp3": case "flac": case "wav": case "aac": case "ogg": case "m4a":
                return "audio";
            case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "txt": case "md":
                return "doc";
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "zip";
            default:
                return "other";
        }
    }

    private Map<String, Object> buildClusterStats() {
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("currentNode", context.uuid);
        cluster.put("webEntrypoint", tfsConfig.getWebEntrypoint());

        List<String> nodes = Collections.emptyList();
        int mainCount = 0;
        try {
            EtcdService etcdService = etcdServiceProvider.getIfAvailable();
            if (etcdService != null) {
                List<String> all = etcdService.getAllNodes();
                if (all != null) {
                    nodes = all;
                }
                mainCount = etcdService.getMainResourceCount();
            }
        } catch (Exception e) {
            log.warn("获取 etcd 集群信息失败，忽略: {}", e.getMessage());
        }
        cluster.put("nodeCount", nodes.size());
        cluster.put("nodes", nodes);
        cluster.put("mainResourceCount", mainCount);
        return cluster;
    }
}
