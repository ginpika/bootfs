package cc.ginpika.bootfs.service.gallery;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.cluster.ClusterHttp;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class GalleryService {
    private static final int RECENT_LIMIT = 8;
    private static final Comparator<Publication> NEWEST = Comparator.comparingLong(Publication::publishedAt)
            .reversed().thenComparing(Publication::uuid);

    private final Context context;
    private final MetadataStore metadata;
    private final FederationService federation;
    private final MeiliSearchService search;
    private final FileService files;
    private final ThumbnailService thumbnails;
    private volatile Catalog catalog;

    public record Image(String nodeId, String uuid) {
        public String url() { return "/gallery/image/" + nodeId + "/" + uuid; }
    }

    public record Sample(String uuid, double rank) {}
    public record Publication(String uuid, long publishedAt) {}
    public record LocalSample(String version, List<Sample> samples, List<Publication> recent) {}
    public record Wall(String etag, List<Image> images) {}
    private record Catalog(long revision, String version, List<Publication> images, List<Publication> recent) {}

    // Independent ranks let the entry node merge bounded samples with equal odds per image.
    public LocalSample localSample(int count) {
        Catalog current = catalog;
        if (current == null || current.revision() != metadata.revision()) current = refreshCatalog();
        Comparator<Sample> rank = Comparator.comparingDouble(Sample::rank);
        PriorityQueue<Sample> best = new PriorityQueue<>(count, rank.reversed());
        for (Publication image : current.images()) {
            Sample candidate = new Sample(image.uuid(), ThreadLocalRandom.current().nextDouble());
            if (best.size() < count) {
                best.add(candidate);
            } else if (candidate.rank() < best.element().rank()) {
                best.remove();
                best.add(candidate);
            }
        }
        return new LocalSample(current.version(), List.copyOf(best), current.recent());
    }

    private synchronized Catalog refreshCatalog() {
        long revision = metadata.revision();
        if (catalog != null && catalog.revision() == revision) return catalog;
        Map<String, Long> published = new TreeMap<>();
        for (FileObject file : context.STORAGE.values()) {
            if (imageType(file.getFileName()) == null) continue;
            JSONObject document = search.findDocument("image-host", file.getUuid());
            if (document != null) published.put(file.getUuid(), document.getLongValue("galleryPublishedAt"));
        }
        List<Publication> images = published.entrySet().stream()
                .map(entry -> new Publication(entry.getKey(), entry.getValue())).toList();
        List<Publication> recent = images.stream().filter(image -> image.publishedAt() > 0)
                .sorted(NEWEST).limit(RECENT_LIMIT).toList();
        catalog = new Catalog(revision, digest(published.toString()), images, recent);
        return catalog;
    }

    public Wall sample(int count, String knownWall) {
        record Candidate(Image image, double rank) {}
        record Recent(Image image, Publication publication) {}
        List<Candidate> candidates = new ArrayList<>();
        List<Recent> recent = new ArrayList<>();
        Map<String, String> versions = new TreeMap<>();
        LocalSample local = localSample(count);
        versions.put(context.uuid, local.version());
        local.samples().forEach(s -> candidates.add(new Candidate(new Image(context.uuid, s.uuid()), s.rank())));
        local.recent().forEach(p -> recent.add(new Recent(new Image(context.uuid, p.uuid()), p)));
        federation.queryPeerData("gallery?count=" + count, null).forEach((node, reply) -> {
            var samples = reply.getJSONArray("samples");
            if (samples == null) return;
            List<String> sampledIds = new ArrayList<>();
            for (int i = 0; i < Math.min(count, samples.size()); i++) {
                JSONObject sample = samples.getJSONObject(i);
                String uuid = sample.getString("uuid");
                double rank = sample.getDoubleValue("rank");
                if (validId(uuid) && rank >= 0 && rank < 1) {
                    candidates.add(new Candidate(new Image(node, uuid), rank));
                    sampledIds.add(uuid);
                }
            }
            String version = reply.getString("version");
            // Older peers still participate in sampling during rolling upgrades.
            if (version == null) {
                Collections.sort(sampledIds);
                version = digest(sampledIds.toString());
            }
            versions.put(node, version);
            var publications = reply.getJSONArray("recent");
            if (publications == null) return;
            for (int i = 0; i < Math.min(RECENT_LIMIT, publications.size()); i++) {
                JSONObject publication = publications.getJSONObject(i);
                String uuid = publication.getString("uuid");
                long publishedAt = publication.getLongValue("publishedAt");
                if (validId(uuid) && publishedAt > 0) {
                    recent.add(new Recent(new Image(node, uuid), new Publication(uuid, publishedAt)));
                }
            }
        });
        String etag = "W/\"gallery-" + count + "-" + digest(versions.toString()) + "\"";
        if (etag.equals(knownWall) || candidates.isEmpty()) return new Wall(etag, List.of());

        Set<Image> selected = new LinkedHashSet<>();
        if (knownWall != null) {
            recent.stream().sorted(Comparator.comparing(Recent::publication, NEWEST))
                    .limit(Math.min(count, RECENT_LIMIT)).forEach(update -> selected.add(update.image()));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::rank));
        for (Candidate candidate : candidates) {
            if (selected.size() == count) break;
            selected.add(candidate.image());
        }
        List<Image> pool = List.copyOf(selected);
        List<Image> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) images.add(pool.get(i % pool.size()));
        return new Wall(etag, images);
    }

    private static String digest(String value) {
        return ClusterHttp.digest(value.getBytes(StandardCharsets.UTF_8));
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
