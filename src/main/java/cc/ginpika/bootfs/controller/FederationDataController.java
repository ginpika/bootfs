package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.service.FederationService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cluster/data")
public class FederationDataController {
    private final FederationService federation;
    private final cc.ginpika.bootfs.service.FileTransferService replicas;
    private final cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata;
    public FederationDataController(FederationService federation,cc.ginpika.bootfs.service.FileTransferService replicas,cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata) { this.federation=federation; this.replicas=replicas; this.metadata=metadata; }
    @PostMapping("/search") public JSONObject search(@RequestBody JSONObject request) { return federation.localSearch(request); }
    @GetMapping("/resource/{id}") public JSONObject resource(@PathVariable String id) { return federation.localResource(id); }
    @GetMapping("/text-owner/{id}") public JSONObject textOwner(@PathVariable String id) { return federation.localTextOwner(id); }
    @GetMapping("/replica-source/{id}") public JSONObject replicaSource(@PathVariable String id) {
        if (metadata.get("/tombstones/"+id)!=null) return new JSONObject(java.util.Map.of("deleted",true));
        JSONObject response=new JSONObject(java.util.Map.of("deleted",false));
        if (federation.localResource(id).getBooleanValue("found")) response.put("snapshot",replicas.snapshot(id)); return response;
    }
    @PostMapping("/replica/commit") public JSONObject commit(@RequestBody JSONObject snapshot,@RequestHeader("X-BootFS-Cluster-Token") String token) throws java.io.IOException {
        String actor=metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore d ? d.actor(token,false) : null;
        if (actor==null) throw new java.io.IOException("Active peer authorization required"); return replicas.commitReplica(snapshot,actor);
    }
}
