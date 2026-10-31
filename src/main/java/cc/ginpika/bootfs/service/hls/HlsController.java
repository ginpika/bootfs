package cc.ginpika.bootfs.service.hls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/hls")
@RequiredArgsConstructor
public class HlsController {
    private final MediaConvertService mediaConvertService;
    private final HlsFileService hlsFileService;
    @org.springframework.beans.factory.annotation.Autowired private cc.ginpika.bootfs.core.Context context;
    @org.springframework.beans.factory.annotation.Autowired private cc.ginpika.bootfs.service.ClusterProxyService proxy;

    public static ReentrantLock CPU_LOCK = new ReentrantLock();

    @PostMapping("/convert")
    public ResponseEntity<?> convert(@RequestParam("uuid") String uuid) {
        if (context.query(uuid)==null) {
            var attributes=(org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes==null || "1".equals(attributes.getRequest().getHeader("X-BootFS-Forwarded"))) return ResponseEntity.notFound().build();
            String owner=proxy.getRemoteNodeUrl(uuid); if (owner==null) return ResponseEntity.notFound().build();
            var request=new javax.servlet.http.HttpServletRequestWrapper(attributes.getRequest()) {
                @Override public String getQueryString() { return "uuid="+java.net.URLEncoder.encode(uuid,java.nio.charset.StandardCharsets.UTF_8); }
            };
            return proxy.proxyRequest(owner,request,null);
        }
        if (CPU_LOCK.isLocked()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            CPU_LOCK.lock();
            mediaConvertService.mp4ToH264Hls(uuid);
        } catch (Exception e) {
            log.error("Hls Convert fail", e);
            return ResponseEntity.internalServerError().build();
        } finally {
            CPU_LOCK.unlock();
        }
        return ResponseEntity.ok().body("200 Media converted");
    }

    @GetMapping("/{folder}/{file}")
    public ResponseEntity<?> hlsStaticServer(@PathVariable("folder") String folder,
                                             @PathVariable("file") String file) {
        if (!folder.matches("[A-Za-z0-9_-]+") || file.contains("/") || file.contains("\\") || file.equals(".") || file.equals("..")) return ResponseEntity.notFound().build();
        Resource resource = hlsFileService.mappingToLocal(folder, file);
        if (Objects.nonNull(resource)) {
            if (file.endsWith(".ts")) {
                return ResponseEntity.ok()
                        .header("Content-Type", "video/mp2t")
                        .body(resource);
            } else if (file.endsWith(".m3u8")) {
                return ResponseEntity.ok().header("Content-Type", "application/vnd.apple.mpegurl").body(resource);
            }
            return ResponseEntity.notFound().build();
        }
        if (context.query(folder)==null) {
            String owner=proxy.getRemoteNodeUrl(folder);
            if (owner!=null) return ResponseEntity.status(302).header("Location",owner+"/hls/"+folder+"/"+org.springframework.web.util.UriUtils.encodePathSegment(file,java.nio.charset.StandardCharsets.UTF_8)).build();
        }
        return ResponseEntity.notFound().build();
    }
}
