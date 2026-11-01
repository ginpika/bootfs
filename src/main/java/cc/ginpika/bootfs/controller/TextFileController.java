package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.object.PublicationPendingException;
import cc.ginpika.bootfs.s3.S3ConcurrentWriteException;
import cc.ginpika.bootfs.service.ClusterProxyService;
import cc.ginpika.bootfs.service.TextFileService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/file/{uuid}/text")
public class TextFileController {
    private final Context context;
    private final TextFileService textFiles;
    private final ClusterProxyService proxy;

    public TextFileController(Context context, TextFileService textFiles, ClusterProxyService proxy) {
        this.context = context; this.textFiles = textFiles; this.proxy = proxy;
    }

    public record SaveTextRequest(String content, String version, String encoding) {
        public SaveTextRequest(String content, String version) { this(content, version, null); }
    }

    @GetMapping
    public ResponseEntity<?> read(@PathVariable String uuid, @RequestParam(required = false) String encoding, HttpServletRequest request) throws IOException {
        ResponseEntity<?> remote = remote(uuid, request, null, false);
        if (remote != null) return remote;
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(textFiles.read(uuid, encoding)); }
        catch (ResponseStatusException failure) { return error(failure.getRawStatusCode(), failure.getReason()); }
    }

    public ResponseEntity<?> read(String uuid, HttpServletRequest request) throws IOException { return read(uuid, null, request); }

    @PutMapping
    public ResponseEntity<?> save(@PathVariable String uuid, @RequestBody SaveTextRequest body, HttpServletRequest request) throws IOException {
        ResponseEntity<?> remote = remote(uuid, request, body, true);
        if (remote != null) return remote;
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(textFiles.save(uuid, body.content(), body.version(), body.encoding())); }
        catch (ResponseStatusException failure) { return error(failure.getRawStatusCode(), failure.getReason()); }
        catch (S3ConcurrentWriteException conflict) { return error(409, conflict.getMessage()); }
        catch (PublicationPendingException pending) {
            return ResponseEntity.status(503).header("X-BootFS-Upload-Id", pending.getUploadId())
                    .body(Map.of("message", "保存尚未完成，请稍后使用相同内容重试"));
        }
    }

    private ResponseEntity<?> remote(String uuid, HttpServletRequest request, Object body, boolean saving) {
        if (!uuid.matches("[A-Za-z0-9_-]+")) return error(400, "无效的文件标识");
        if (context.query(uuid) != null || (saving && textFiles.hasEditHistory(uuid))) return null;
        if ("1".equals(request.getHeader("X-BootFS-Forwarded"))) return error(404, "文件不存在");
        String endpoint = saving ? proxy.getTextEditorNodeUrl(uuid) : proxy.getRemoteNodeUrl(uuid);
        if (endpoint == null) return error(404, "文件不存在");
        return proxy.proxyRequest(endpoint, request, body == null ? null : JSONObject.toJSONString(body));
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("message", message));
    }
}
