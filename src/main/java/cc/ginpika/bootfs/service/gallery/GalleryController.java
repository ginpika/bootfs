package cc.ginpika.bootfs.service.gallery;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.FederationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class GalleryController {
    private final GalleryService gallery;
    private final FederationService federation;
    private final Context context;
    private final TfsConfig config;

    @GetMapping("/setu")
    public ResponseEntity<?> randomImage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var images = gallery.sample(1);
        if (images.isEmpty()) return ResponseEntity.notFound().build();
        var image = images.get(0);
        return image(image.nodeId(), image.uuid(), false, request, response);
    }

    @GetMapping("/setu-url")
    public ResponseEntity<String> randomUrl() {
        var images = gallery.sample(1);
        if (images.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(config.getWebEntrypoint().replaceAll("/+$", "") + images.get(0).url());
    }

    @GetMapping("/setu-urls")
    public ResponseEntity<List<String>> randomUrls(@RequestParam(defaultValue = "240") int count) {
        var images = gallery.sample(checkedCount(count));
        if (images.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(images.stream().map(GalleryService.Image::url).toList());
    }

    @GetMapping("/gallery/image/{node}/{uuid}")
    public ResponseEntity<?> image(@PathVariable String node, @PathVariable String uuid,
                                   @RequestParam(defaultValue = "false") boolean thumbnail,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!GalleryService.validId(node) || !GalleryService.validId(uuid)) return ResponseEntity.notFound().build();
        if (context.uuid.equals(node)) return gallery.localImage(uuid, thumbnail, request);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        federation.streamPeerAsset(node, "gallery/" + uuid + "?thumbnail=" + thumbnail,
                "HEAD".equals(request.getMethod()), response);
        return null;
    }

    @GetMapping("/api/cluster/data/gallery")
    public Map<String, ?> peerSample(@RequestParam int count) {
        return Map.of("samples", gallery.localSample(checkedCount(count)));
    }

    @GetMapping("/api/cluster/data/gallery/{uuid}")
    public ResponseEntity<?> peerImage(@PathVariable String uuid, @RequestParam(defaultValue = "false") boolean thumbnail,
                                       HttpServletRequest request) throws IOException {
        return gallery.localImage(uuid, thumbnail, request);
    }

    private static int checkedCount(int count) {
        if (count < 1 || count > 480) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid gallery sample size");
        return count;
    }
}
