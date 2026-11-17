package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.TransferThreadPool;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Schedules and retries asynchronous best-effort replica transfers for committed resources. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReplicationService {
    private final LocalFileInventory context;
    private final TfsConfig tfsConfig;
    private final MetadataRegistry metadataRegistry;
    private final FileTransferService fileTransferService;
    private final ResourceDeletionService deletionService;

    private final TransferThreadPool transferThreadPool = new TransferThreadPool();
    private final Set<String> replicationRunning = ConcurrentHashMap.newKeySet();

    public void scheduleReplication(String fileId, String originFilename) {
        scheduleReplication(fileId, originFilename, tfsConfig.getCopies() == null ? 1 : tfsConfig.getCopies());
    }

    public void scheduleReplication(String fileId, String originFilename, int copies) {
        int requested = Math.max(0, copies - 1);
        if (requested == 0) return;
        JSONObject job = new JSONObject();
        job.put("uuid", fileId);
        job.put("filename", originFilename);
        job.put("replicas", requested);
        job.put("attempts", 0);
        job.put("nextAttempt", 0L);
        metadataRegistry.compareAndSet(replicationPrefix() + fileId, null, job.toJSONString());
        scheduleJob(fileId);
    }

    public void retryReplicationJobs() {
        metadataRegistry.scan(replicationPrefix()).forEach((key, raw) -> {
            JSONObject job = JSONObject.parseObject(raw);
            if (job.getLongValue("nextAttempt") <= System.currentTimeMillis()) {
                scheduleJob(job.getString("uuid"));
            }
        });
    }

    private void scheduleJob(String fileId) {
        if (!replicationRunning.add(fileId)) return;
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    executeReplication(fileId);
                } catch (Exception failure) {
                    log.warn("Replica repair will retry for {}", fileId, failure);
                } finally {
                    replicationRunning.remove(fileId);
                }
            }, transferThreadPool);
        } catch (RuntimeException failure) {
            replicationRunning.remove(fileId);
            throw failure;
        }
    }

    private void executeReplication(String fileId) throws IOException {
        String raw = metadataRegistry.getOne(replicationPrefix() + fileId);
        if (raw == null) return;
        JSONObject job = JSONObject.parseObject(raw);
        FileObject resource = context.query(fileId);
        if (resource == null || deletionService.isDeleted(resource)) {
            metadataRegistry.delete(replicationPrefix() + fileId);
            return;
        }
        String own = stripSlash(tfsConfig.getWebEntrypoint());
        Set<String> distinct = new TreeSet<>();
        for (String node : metadataRegistry.getAllNodes()) {
            if (node != null) distinct.add(node);
        }
        List<String> nodes = new ArrayList<>(distinct);
        nodes.removeIf(node -> node == null || stripSlash(node).equals(own));
        // Stable rendezvous-style placement avoids choosing different receivers on every retry.
        nodes.sort(Comparator.comparing(node -> UUID.nameUUIDFromBytes((fileId + ":" + node).getBytes(StandardCharsets.UTF_8)).toString()));
        Set<String> confirmed = replicaNodes(fileId);
        int requested = job.getIntValue("replicas");
        for (String target : nodes) {
            if (confirmed.size() >= requested) break;
            if (confirmed.contains(stripSlash(target))) continue;
            if (deletionService.isDeleted(resource)) break;
            try {
                fileTransferService.put(new File(resource.getPath()), fileId, job.getString("filename"), NodeObject.builder().url(target).build());
                confirmed = replicaNodes(fileId);
            } catch (Exception failure) {
                log.warn("Replica transfer {} to {} failed", fileId, target, failure);
            }
        }
        if (confirmed.size() >= requested) {
            metadataRegistry.delete(replicationPrefix() + fileId);
        } else {
            int attempts = Math.min(30, job.getIntValue("attempts") + 1);
            job.put("attempts", attempts);
            job.put("nextAttempt", System.currentTimeMillis() + Math.min(60000L, 1000L << Math.min(6, attempts)));
            metadataRegistry.compareAndSet(replicationPrefix() + fileId, raw, job.toJSONString());
        }
    }

    private Set<String> replicaNodes(String fileId) {
        Set<String> nodes = new HashSet<>();
        String metaRaw = metadataRegistry.getOne("/files/" + fileId + "/meta");
        String hash = metaRaw == null ? null : JSONObject.parseObject(metaRaw).getString("sha256");
        metadataRegistry.scan("/replication-receipts/" + context.uuid + "/" + fileId + "/").values().forEach(raw -> {
            JSONObject receipt = JSONObject.parseObject(raw);
            if (hash == null || (receipt.getJSONObject("receipt") != null && hash.equals(receipt.getJSONObject("receipt").getString("sha256")))) {
                nodes.add(stripSlash(receipt.getString("url")));
            }
        });
        metadataRegistry.scan("/files/" + fileId + "/replicas/").forEach((key, value) -> {
            if (!key.endsWith("/meta")) {
                String replicaMeta = metadataRegistry.getOne(key + "/meta");
                if (hash != null && (replicaMeta == null || !hash.equals(JSONObject.parseObject(replicaMeta).getString("sha256")))) return;
                int path = value.lastIndexOf("/f/");
                if (path > 0 && !stripSlash(value.substring(0, path)).equals(stripSlash(tfsConfig.getWebEntrypoint()))) {
                    nodes.add(stripSlash(value.substring(0, path)));
                }
            }
        });
        return nodes;
    }

    private String replicationPrefix() {
        return "/replication-jobs/" + context.uuid + "/";
    }

    private static String stripSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    @PreDestroy
    public void close() {
        transferThreadPool.shutdown();
    }
}
