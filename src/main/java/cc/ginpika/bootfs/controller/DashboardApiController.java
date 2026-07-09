package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.metrics.AppStartupTracker;
import cc.ginpika.bootfs.metrics.RequestStatsService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

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

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final ExecutorService clusterFetchExecutor = Executors.newFixedThreadPool(8);

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

    /**
     * 本节点统计端点，供集群内其他节点调用
     */
    @GetMapping("/node-stats")
    public Map<String, Object> nodeStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nodeId", context.uuid);
            data.put("disk", buildDiskStats());
            data.put("resources", buildResourceStats());
            data.put("fileTypes", buildFileTypeStats());
            result.put("succeed", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取本节点统计失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildStats() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 获取集群所有节点 URL
        List<NodeInfo> clusterNodes = getClusterNodes();
        // 并行拉取其他节点的统计数据
        Map<String, Map<String, Object>> remoteStats = fetchAllRemoteNodeStats(clusterNodes);

        // 本节点统计（构建一次，复用于节点列表与聚合，避免重复计算）
        Map<String, Object> localDisk = buildDiskStats();
        localDisk.put("nodeId", context.uuid);
        Map<String, Object> localResources = buildResourceStats();
        List<Map<String, Object>> localFileTypes = buildFileTypeStats();

        // 节点列表（逐节点详情：当前节点 + 全部远程节点）
        data.put("nodes", buildNodesList(localDisk, localResources, localFileTypes, remoteStats));

        // 聚合磁盘
        data.put("disk", localDisk);
        List<Map<String, Object>> allDisks = new ArrayList<>();
        allDisks.add(localDisk);
        for (Map.Entry<String, Map<String, Object>> entry : remoteStats.entrySet()) {
            Map<String, Object> remoteData = entry.getValue();
            if (remoteData != null && remoteData.containsKey("disk")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> remoteDisk = (Map<String, Object>) remoteData.get("disk");
                remoteDisk.put("nodeId", remoteData.getOrDefault("nodeId", entry.getKey()));
                allDisks.add(remoteDisk);
            }
        }
        data.put("disks", allDisks);

        // 资源: 聚合本节点 + 远程节点（用副本聚合，避免污染节点列表中的本节点数据）
        Map<String, Object> resources = new LinkedHashMap<>(localResources);
        for (Map.Entry<String, Map<String, Object>> entry : remoteStats.entrySet()) {
            Map<String, Object> remoteData = entry.getValue();
            if (remoteData != null && remoteData.containsKey("resources")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rr = (Map<String, Object>) remoteData.get("resources");
                resources.put("total", ((Number) resources.get("total")).intValue() + ((Number) rr.get("total")).intValue());
                resources.put("mainCount", ((Number) resources.get("mainCount")).intValue() + ((Number) rr.get("mainCount")).intValue());
                resources.put("replicaCount", ((Number) resources.get("replicaCount")).intValue() + ((Number) rr.get("replicaCount")).intValue());
                resources.put("totalSizeBytes", ((Number) resources.get("totalSizeBytes")).longValue() + ((Number) rr.get("totalSizeBytes")).longValue());
            }
        }
        // 如果集群模式且远程节点可用，把本节点的主资源数修正为 etcd 中的计数
        if (!remoteStats.isEmpty()) {
            int etcdMainCount = getEtcdMainResourceCount();
            if (etcdMainCount > 0) {
                resources.put("mainCount", etcdMainCount);
            }
        }
        data.put("resources", resources);

        // 文件类型: 聚合（深拷贝，避免 mergeFileTypes 污染节点列表中的本节点数据）
        List<Map<String, Object>> fileTypes = new ArrayList<>();
        for (Map<String, Object> ft : localFileTypes) {
            fileTypes.add(new LinkedHashMap<>(ft));
        }
        for (Map.Entry<String, Map<String, Object>> entry : remoteStats.entrySet()) {
            Map<String, Object> remoteData = entry.getValue();
            if (remoteData != null && remoteData.containsKey("fileTypes")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rf = (List<Map<String, Object>>) remoteData.get("fileTypes");
                mergeFileTypes(fileTypes, rf);
            }
        }
        data.put("fileTypes", fileTypes);

        // 集群信息
        data.put("cluster", buildClusterStats());

        // 流量和请求统计（仅本地）
        data.putAll(requestStatsService.buildStats());
        data.put("appStartTime", appStartupTracker.getReadyTime());
        return data;
    }

    /**
     * 构建逐节点详情列表：当前节点 + 全部远程节点（含离线节点）。
     * 当前节点用本地统计；远程节点从已拉取的 remoteStats 取数据，取不到则标记为离线。
     */
    private List<Map<String, Object>> buildNodesList(Map<String, Object> localDisk,
                                                     Map<String, Object> localResources,
                                                     List<Map<String, Object>> localFileTypes,
                                                     Map<String, Map<String, Object>> remoteStats) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        // 当前节点
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("nodeId", context.uuid);
        current.put("isCurrent", true);
        current.put("online", true);
        current.put("url", tfsConfig.getWebEntrypoint());
        current.put("disk", localDisk);
        current.put("resources", localResources);
        current.put("fileTypes", localFileTypes);
        nodes.add(current);

        // 远程节点（来自 etcd 全量节点列表，含离线节点）
        Map<String, String> allNodes = Collections.emptyMap();
        try {
            EtcdService etcdService = etcdServiceProvider.getIfAvailable();
            if (etcdService != null) {
                Map<String, String> nwk = etcdService.getNodesWithKeys();
                if (nwk != null) allNodes = nwk;
            }
        } catch (Exception e) {
            log.warn("获取集群节点列表失败: {}", e.getMessage());
        }

        for (Map.Entry<String, String> entry : allNodes.entrySet()) {
            String uuid = entry.getKey();
            String url = entry.getValue();
            if (context.uuid.equals(uuid)) continue;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeId", uuid);
            node.put("isCurrent", false);
            node.put("url", url);

            Map<String, Object> remoteData = remoteStats.get(uuid);
            if (remoteData != null) {
                node.put("online", true);
                node.put("disk", remoteData.get("disk"));
                node.put("resources", remoteData.get("resources"));
                node.put("fileTypes", remoteData.get("fileTypes"));
            } else {
                node.put("online", false);
            }
            nodes.add(node);
        }

        return nodes;
    }

    // ---- 集群节点信息 ----

    private static class NodeInfo {
        final String nodeId;
        final String url;

        NodeInfo(String nodeId, String url) {
            this.nodeId = nodeId;
            this.url = url;
        }
    }

    private List<NodeInfo> getClusterNodes() {
        List<NodeInfo> nodes = new ArrayList<>();
        try {
            EtcdService etcdService = etcdServiceProvider.getIfAvailable();
            if (etcdService != null) {
                Map<String, String> nodesWithKeys = etcdService.getNodesWithKeys();
                if (nodesWithKeys != null) {
                    for (Map.Entry<String, String> entry : nodesWithKeys.entrySet()) {
                        String uuid = entry.getKey();
                        String url = entry.getValue();
                        if (!context.uuid.equals(uuid)) {
                            nodes.add(new NodeInfo(uuid, url));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取集群节点列表失败: {}", e.getMessage());
        }
        return nodes;
    }

    // ---- 远程节点数据拉取 ----

    private Map<String, Map<String, Object>> fetchAllRemoteNodeStats(List<NodeInfo> nodes) {
        if (nodes.isEmpty()) return Collections.emptyMap();

        Map<String, Map<String, Object>> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (NodeInfo node : nodes) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> stats = fetchNodeStats(node.url);
                    if (stats != null) {
                        results.put(node.nodeId, stats);
                    }
                } catch (Exception e) {
                    log.warn("拉取节点 {} ({}) 统计数据失败: {}", node.nodeId, node.url, e.getMessage());
                }
            }, clusterFetchExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待远程节点统计数据超时或失败: {}", e.getMessage());
        }
        return results;
    }

    private Map<String, Object> fetchNodeStats(String nodeUrl) {
        try {
            String apiUrl = nodeUrl.endsWith("/") ? nodeUrl + "api/dashboard/node-stats"
                    : nodeUrl + "/api/dashboard/node-stats";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                if (json.getBooleanValue("succeed")) {
                    return json.getJSONObject("data");
                }
            }
        } catch (Exception e) {
            log.warn("请求节点 {} 失败: {}", nodeUrl, e.getMessage());
        }
        return null;
    }

    // ---- 文件类型聚合 ----

    private void mergeFileTypes(List<Map<String, Object>> target, List<Map<String, Object>> source) {
        for (Map<String, Object> src : source) {
            String category = (String) src.get("category");
            int srcCount = ((Number) src.get("count")).intValue();
            boolean found = false;
            for (Map<String, Object> tgt : target) {
                if (category.equals(tgt.get("category"))) {
                    tgt.put("count", ((Number) tgt.get("count")).intValue() + srcCount);
                    found = true;
                    break;
                }
            }
            if (!found) {
                Map<String, Object> newItem = new LinkedHashMap<>();
                newItem.put("category", category);
                newItem.put("count", srcCount);
                target.add(newItem);
            }
        }
    }

    // ---- etcd 主资源数 ----

    private int getEtcdMainResourceCount() {
        try {
            EtcdService etcdService = etcdServiceProvider.getIfAvailable();
            if (etcdService != null) {
                return etcdService.getMainResourceCount();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ---- 本节点统计构建 ----

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
