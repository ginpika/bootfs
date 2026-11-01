package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.ClashFileService;
import cc.ginpika.bootfs.service.ClashLatencyService;
import cc.ginpika.bootfs.service.ClusterProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/file/{uuid}/clash")
public class ClashFileController {
    private final Context context;
    private final ClashFileService files;
    private final ClashLatencyService latency;
    private final ClusterProxyService proxy;

    public ClashFileController(Context context, ClashFileService files, ClashLatencyService latency, ClusterProxyService proxy) {
        this.context = context;
        this.files = files;
        this.latency = latency;
        this.proxy = proxy;
    }

    @GetMapping
    public ResponseEntity<?> details(@PathVariable String uuid, HttpServletRequest request) throws IOException {
        ResponseEntity<?> remote = remote(uuid, request);
        if (remote != null) return remote;
        try { return ok(files.details(uuid)); }
        catch (ResponseStatusException failure) { return error(failure.getRawStatusCode(), failure.getReason()); }
    }

    @PostMapping("/latency")
    public ResponseEntity<?> latency(@PathVariable String uuid,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "16") int limit,
                                     HttpServletRequest request) throws IOException {
        ResponseEntity<?> remote = remote(uuid, request);
        if (remote != null) return remote;
        try { return ok(latency.measure(files.details(uuid), offset, limit)); }
        catch (ResponseStatusException failure) { return error(failure.getRawStatusCode(), failure.getReason()); }
    }

    private ResponseEntity<?> remote(String uuid, HttpServletRequest request) {
        if (!uuid.matches("[A-Za-z0-9_-]+")) return error(400, "无效的文件标识");
        if (context.query(uuid) != null) return null;
        if ("1".equals(request.getHeader("X-BootFS-Forwarded"))) return error(404, "文件不存在");
        String endpoint = proxy.getRemoteNodeUrl(uuid);
        if (endpoint == null) return error(404, "文件不存在");
        return proxy.proxyRequest(endpoint, request, null);
    }

    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("message", message));
    }
}
