package cc.ginpika.bootfs.service.gallery;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.service.thumb.ThumbnailService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class GalleryService {
    private final Context context;
    private final FederationService federation;
    private final MeiliSearchService search;
    private final FileService files;
    private final ThumbnailService thumbnails;

    public record Image(String nodeId, String uuid) {
        public String url() { return "/gallery/image/" + nodeId + "/" + uuid; }
    }

    public record Sample(String uuid, double rank) {}

    // Independent ranks let the entry node merge bounded samples with equal odds per image.
    public List<Sample> localSample(int count) {
        Comparator<Sample> rank = Comparator.comparingDouble(Sample::rank);
        PriorityQueue<Sample> best = new PriorityQueue<>(count, rank.reversed());
        for (FileObject file : context.STORAGE.values()) {
            if (imageType(file.getFileName()) == null || !published(file.getUuid())) continue;
            Sample candidate = new Sample(file.getUuid(), ThreadLocalRandom.current().nextDouble());
            if (best.size() < count) {
                best.add(candidate);
            } else if (candidate.rank() < best.element().rank()) {
                best.remove();
                best.add(candidate);
            }
        }
        return List.copyOf(best);
    }

    public List<Image> sample(int count) {
        record Candidate(Image image, double rank) {}
        List<Candidate> candidates = new ArrayList<>();
        localSample(count).forEach(s -> candidates.add(new Candidate(new Image(context.uuid, s.uuid()), s.rank())));
        federation.queryPeerData("gallery?count=" + count, null).forEach((node, reply) -> {
            var samples = reply.getJSONArray("samples");
            if (samples == null) return;
            for (int i = 0; i < Math.min(count, samples.size()); i++) {
                JSONObject sample = samples.getJSONObject(i);
                String uuid = sample.getString("uuid");
                double rank = sample.getDoubleValue("rank");
                if (validId(uuid) && rank >= 0 && rank < 1) {
                    candidates.add(new Candidate(new Image(node, uuid), rank));
                }
            }
        });
        if (candidates.isEmpty()) return List.of();
        candidates.sort(Comparator.comparingDouble(Candidate::rank));
        int poolSize = Math.min(count, candidates.size());
        List<Image> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) images.add(candidates.get(i % poolSize).image());
        return images;
    }

    public ResponseEntity<Resource> localImage(String uuid, boolean thumbnail, HttpServletRequest request) throws IOException {
        if (!validId(uuid) || !published(uuid)) return ResponseEntity.notFound().build();
        Object[] file = files.download(uuid, request);
        if (file == null) return ResponseEntity.notFound().build();
        String type = imageType((String) file[1]);
        if (type == null) return ResponseEntity.notFound().build();
        Resource asset = (Resource) file[0];
        if (thumbnail) {
            Path preview = thumbnails.thumbPath(uuid);
            if (Files.isRegularFile(preview)) {
                asset = new FileSystemResource(preview);
                type = "image/webp";
            } else if (thumbnails.isImage((String) file[1])) {
                thumbnails.generateAsync(uuid);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .contentType(MediaType.parseMediaType(type)).body(asset);
    }

    private boolean published(String uuid) {
        return search.findDocument("image-host", uuid) != null;
    }

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]+"); }

    private static String imageType(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> null;
        };
    }
}
