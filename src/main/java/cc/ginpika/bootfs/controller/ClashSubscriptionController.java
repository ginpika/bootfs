package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.ClashFileService;
import cc.ginpika.bootfs.service.ClashSubscriptionService;
import cc.ginpika.bootfs.service.ClusterProxyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/** Public file representation, like /p/{uuid}; only the response is converted. */
@RestController
public class ClashSubscriptionController {
    private final Context context;
    private final ClashFileService files;
    private final ClashSubscriptionService subscriptions;
    private final ClusterProxyService proxy;

    public ClashSubscriptionController(Context context, ClashFileService files,
                                       ClashSubscriptionService subscriptions, ClusterProxyService proxy) {
        this.context = context;
        this.files = files;
        this.subscriptions = subscriptions;
        this.proxy = proxy;
    }

    @GetMapping(value = "/p/{uuid}", params = "p=vless")
    public ResponseEntity<?> subscription(@PathVariable String uuid) throws IOException {
        if (!uuid.matches("[A-Za-z0-9_-]+")) return error(400, "无效的文件标识");
        if (context.query(uuid) == null) {
            String remote = proxy.getRemoteNodeUrl(uuid);
            if (remote == null) return error(404, "文件不存在或已删除");
            return ResponseEntity.status(HttpStatus.FOUND).header("Cache-Control", "no-store")
                    .header("Location", remote.replaceAll("/+$", "") + "/p/" + uuid + "?p=vless").build();
        }
        try {
            ClashSubscriptionService.Subscription result = subscriptions.convert(files.read(uuid).content());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .header("Cache-Control", "no-store")
                    .header("X-BootFS-Converted-Nodes", Integer.toString(result.converted()))
                    .header("X-BootFS-Conversion-Warnings", Integer.toString(result.warnings().size()))
                    .body(result.content());
        } catch (ResponseStatusException failure) {
            return error(failure.getRawStatusCode(), failure.getReason());
        }
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("message", message));
    }
}
