package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.kernel.cluster.*;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/cluster")
public class ClusterController {
    private final ClusterCoordinator cluster;
    private final MetadataStore metadata;
    public ClusterController(ClusterCoordinator cluster, MetadataStore metadata) { this.cluster = cluster; this.metadata = metadata; }
    @ExceptionHandler({IOException.class, IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<?> failure(Exception failure) { return ResponseEntity.status(409).body(Map.of("error", failure.getMessage() == null ? "Cluster request failed" : failure.getMessage())); }
    @GetMapping("/ready") public JSONObject ready() { return cluster.dynamic().ready(); }
    @GetMapping("/status") public JSONObject status() { return cluster.status(); }
    @PostMapping("/connect") public ResponseEntity<?> connect(@RequestBody JSONObject request) throws IOException {
        cluster.dynamic().connect(request.getString("url")); return ResponseEntity.accepted().body(cluster.status());
    }
    @PostMapping("/merge") public JSONObject merge(@RequestBody JSONObject request) throws IOException { return cluster.dynamic().merge(request); }
    @PostMapping("/admit") public JSONObject admit(@RequestBody JSONObject request) throws IOException {
        if (!cluster.dynamic().status().getString("groupId").equals(request.getString("groupId"))) throw new IOException("Admission group identity differs");
        cluster.dynamic().admit(request.getString("nodeId"), request.getString("raftAddress")); return cluster.status();
    }
    @PostMapping("/remove") public JSONObject remove(@RequestBody JSONObject request) throws IOException {
        if (request.containsKey("groupId") && !cluster.dynamic().status().getString("groupId").equals(request.getString("groupId"))) throw new IOException("Removal group identity differs");
        cluster.dynamic().remove(request.getString("nodeId")); return cluster.status();
    }
    @PostMapping("/leave") public JSONObject leave() throws IOException { cluster.dynamic().leave(); return cluster.status(); }
    @GetMapping("/versions") public List<JSONObject> versions(@RequestParam String namespace, @RequestParam String key) {
        if (!Set.of("s3", "resource", "metadata").contains(namespace)) throw new IllegalArgumentException("Unknown version namespace");
        List<JSONObject> result = new ArrayList<>();
        ClusterMerge.versions(metadata.scan("/versions/"), namespace, key).values().forEach(value -> result.add(JSON.parseObject(value)));
        return result;
    }
}
