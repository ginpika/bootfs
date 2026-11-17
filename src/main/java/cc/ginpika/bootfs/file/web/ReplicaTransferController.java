package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.result.TransferResult;
import cc.ginpika.bootfs.file.FileTransferService;
import cc.ginpika.bootfs.kernel.cluster.ClusterHttp;
import cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
class ReplicaTransferController {
    private final FileTransferService fileTransferService;
    private final MetadataStore localMetadata;

    @PutMapping("/t/prepare")
    public JSONObject prepareReplica(@RequestPart("file") MultipartFile file,
                                    @RequestParam("originalFilename") String filename,
                                    @RequestParam("originalFileId") String id,
                                    @RequestParam("sha256") String sha256,
                                    @RequestParam(value="snapshot",required=false) String snapshot,
                                    HttpServletRequest request) throws IOException {
        if (snapshot!=null && !snapshot.isBlank()) {
            if (!ClusterHttp.digest(snapshot.getBytes(StandardCharsets.UTF_8)).equals(request.getHeader("X-BootFS-Digest"))) throw new IOException("Replica snapshot signature does not match its metadata");
            String actor=localPeer(request.getHeader("X-BootFS-Cluster-Token"));
            return fileTransferService.prepare(file,filename,id,sha256,JSONObject.parseObject(snapshot),actor);
        }
        if (localMetadata instanceof DynamicMetadataStore dynamic
                && dynamic.actor(request.getHeader("X-BootFS-Cluster-Token"), false) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signed replica snapshot required");
        }
        return fileTransferService.prepare(file, filename, id, sha256);
    }
    private String localPeer(String token) throws IOException {
        String actor=localMetadata instanceof DynamicMetadataStore d ? d.actor(token,false) : null;
        if (actor==null) throw new IOException("Active peer authorization is required"); return actor;
    }

    // get the replica from other nodes
    @PutMapping("/t")
    public TransferResult transfer(@RequestPart("file") MultipartFile file,
                                   @RequestParam("originalFilename") String originalFilename,
                                   @RequestParam("originalFileId") String originalFileUuid,
                                   @RequestParam(required = false, defaultValue = "1", name = "isCopy") String isCopy) {
        return fileTransferService.accept(file, originalFilename, originalFileUuid);
    }

}
