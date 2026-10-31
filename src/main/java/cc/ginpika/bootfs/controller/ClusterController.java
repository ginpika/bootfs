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
    public ClusterController(ClusterCoordinator cluster,MetadataStore metadata) { this.cluster=cluster; this.metadata=metadata; }
    @ExceptionHandler({IOException.class,IllegalStateException.class,IllegalArgumentException.class})
    public ResponseEntity<?> failure(Exception failure) { return ResponseEntity.status(409).body(Map.of("error",Objects.toString(failure.getMessage(),"Cluster request failed"))); }
    @GetMapping("/ready") public JSONObject ready() { return cluster.dynamic().ready(); }
    @GetMapping("/status") public JSONObject status() { return cluster.status(); }
    @PostMapping("/link") public ResponseEntity<?> link(@RequestBody JSONObject request) throws IOException {
        return ResponseEntity.accepted().body(cluster.link(request.getString("url"),request.getString("code")));
    }
    @GetMapping("/link/status") public JSONObject linkStatus(@RequestParam String url) throws IOException { return cluster.linkStatus(url); }
    @PostMapping("/pairing/code") public ResponseEntity<?> pairingCode() { return privateResponse(cluster.issuePairingCode()); }
    @PostMapping("/pairing/redeem") public ResponseEntity<?> redeem(@RequestBody JSONObject request) throws IOException { return privateResponse(cluster.redeemPairingCode(request)); }
    private ResponseEntity<?> privateResponse(Object body) { return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body); }
    private String actor(String authorization,boolean pending) throws IOException {
        String actor=cluster.dynamic().actor(authorization,pending);
        if (actor==null) throw new IOException("Peer authorization required"); return actor;
    }
    @PostMapping("/join") public ResponseEntity<?> join(@RequestBody JSONObject request,@RequestHeader("X-BootFS-Cluster-Token") String token) throws IOException {
        return privateResponse(cluster.dynamic().join(request,actor(token,true)));
    }
    @PostMapping("/membership") public JSONObject membership(@RequestBody JSONObject request,@RequestHeader("X-BootFS-Cluster-Token") String token) throws IOException {
        cluster.dynamic().membership(request,actor(token,false)); return new JSONObject(Map.of("accepted",true));
    }
    @PostMapping("/departure") public JSONObject departure(@RequestBody JSONObject request,@RequestHeader("X-BootFS-Cluster-Token") String token) throws IOException {
        cluster.dynamic().departure(request,actor(token,false)); return new JSONObject(Map.of("accepted",true));
    }
    @PostMapping("/connect") public ResponseEntity<?> connect(@RequestBody JSONObject request,@RequestHeader("X-BootFS-Cluster-Token") String authorization) throws IOException {
        String caller=actor(authorization,true);
        if (!Objects.equals(cluster.dynamic().endpointFor(caller),DynamicMetadataStore.normalizeEndpoint(request.getString("url")))) throw new IOException("Connect must target the authenticated requesting node");
        cluster.dynamic().connect(request.getString("url")); return ResponseEntity.accepted().body(cluster.status());
    }
    @PostMapping("/remove") public JSONObject remove(@RequestBody JSONObject request) throws IOException { cluster.dynamic().remove(request.getString("nodeId")); return cluster.status(); }
    @PostMapping("/leave") public JSONObject leave() throws IOException { cluster.dynamic().leave(); return cluster.status(); }
    @GetMapping("/versions") public List<JSONObject> versions(@RequestParam String namespace,@RequestParam String key) {
        if (!Set.of("s3","resource","metadata").contains(namespace)) throw new IllegalArgumentException("Unknown version namespace");
        return ResourceVersions.versions(metadata.scan("/versions/"),namespace,key).values().stream().map(JSON::parseObject).toList();
    }
}
