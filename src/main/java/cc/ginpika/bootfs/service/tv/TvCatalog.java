package cc.ginpika.bootfs.service.tv;

import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.hls.HlsFileService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
class TvCatalog {
    private final LocalFileInventory context;
    private final FederationService federation;
    private final HlsFileService hls;

    public record Ref(String nodeId, String uuid) {
        public Ref {
            if (!validId(nodeId) || !validId(uuid)) throw new IllegalArgumentException("Invalid TV resource identity");
        }

        String key() { return nodeId + ":" + uuid; }

        String segmentUrl(String filename) { return "/tv/ts/" + nodeId + "/" + uuid + "/" + filename; }
    }

    public record Segment(String filename, double duration) {
        public Segment {
            if (!validSegment(filename) || !Double.isFinite(duration) || duration <= 0) {
                throw new IllegalArgumentException("Invalid HLS segment");
            }
        }
    }

    public record Program(Ref ref, String fileName, List<Segment> segments) {
        public Program {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(fileName);
            segments = List.copyOf(segments);
        }

        Video video(boolean available) {
            return new Video(ref.key(), ref.uuid(), ref.nodeId(), fileName, segments.size(),
                    segments.stream().mapToDouble(Segment::duration).sum(), available);
        }
    }

    public record Video(String key, String uuid, String nodeId, String fileName,
                        int segmentCount, double totalDuration, boolean available) {}

    static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]+"); }

    static boolean validSegment(String value) {
        return value != null && value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,200}\\.ts");
    }

    Ref ref(String key) {
        String[] parts = Objects.toString(key, "").split(":", -1);
        try {
            if (parts.length == 1) return new Ref(context.uuid, parts[0]);
            if (parts.length == 2) return new Ref(parts[0], parts[1]);
        } catch (IllegalArgumentException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid TV resource identity");
    }

    List<Video> videos() {
        List<Video> videos = new ArrayList<>(localVideos());
        federation.queryPeerData("tv/videos", null).forEach((node, reply) -> {
            var entries = reply.getJSONArray("videos");
            if (entries == null) return;
            for (Video video : entries.toJavaList(Video.class)) {
                if (validId(video.uuid()) && node.equals(video.nodeId())) {
                    videos.add(new Video(new Ref(node, video.uuid()).key(), video.uuid(), node, video.fileName(),
                            video.segmentCount(), video.totalDuration(), true));
                }
            }
        });
        return videos;
    }

    List<Video> localVideos() {
        try {
            return localPrograms(hls.localFolders()).stream().map(program -> program.video(true)).toList();
        } catch (IOException failure) {
            log.warn("Unable to list the local TV catalog", failure);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TV catalog is unavailable");
        }
    }

    List<Program> localPrograms(List<String> ids) {
        return ids.stream().filter(TvCatalog::validId).distinct()
                .map(this::localProgram).filter(Objects::nonNull).toList();
    }

    List<Program> load(List<Ref> order, Map<Ref, Program> previous) {
        Map<String, List<String>> byNode = new HashMap<>();
        for (Ref ref : order) byNode.computeIfAbsent(ref.nodeId(), ignored -> new ArrayList<>()).add(ref.uuid());
        Map<Ref, Program> resolved = new HashMap<>();
        List<String> local = byNode.remove(context.uuid);
        if (local != null) localPrograms(local).forEach(program -> resolved.put(program.ref(), program));
        Map<String, String> peers = federation.peers();
        Map<String, JSONObject> requests = new HashMap<>();
        byNode.forEach((node, ids) -> requests.put(node, new JSONObject(Map.of("uuids", ids))));
        var replies = federation.querySelectedPeerData("tv/programs", requests);
        replies.forEach((node, reply) -> {
            var programs = reply.getJSONArray("programs");
            if (programs == null) return;
            for (Program program : programs.toJavaList(Program.class)) {
                if (node.equals(program.ref().nodeId())) resolved.put(program.ref(), program);
            }
        });
        List<Program> programs = new ArrayList<>();
        for (Ref ref : order) {
            Program program = resolved.get(ref);
            if (program == null && peers.containsKey(ref.nodeId()) && !replies.containsKey(ref.nodeId())) {
                program = previous.get(ref);
                if (program == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TV resource node is unavailable");
            }
            if (program == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TV video is unavailable");
            programs.add(program);
        }
        return programs;
    }

    private Program localProgram(String uuid) {
        var file = context.query(uuid);
        if (file == null) return null;
        var manifest = hls.mappingToLocal(uuid, "playlist.m3u8");
        if (manifest == null) return null;
        List<Segment> segments = new ArrayList<>();
        // FFmpeg writes ENDLIST when conversion completes. The inventory flag can lag behind it.
        boolean ready = "1".equals(file.getHlsAvailable());
        try (var reader = new BufferedReader(new InputStreamReader(manifest.getInputStream(), StandardCharsets.UTF_8))) {
            double duration = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.trim();
                if (line.equals("#EXT-X-ENDLIST")) {
                    ready = true;
                } else if (line.startsWith("#EXTINF:")) {
                    duration = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    segments.add(new Segment(line, duration));
                    duration = 0;
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            log.warn("Unable to read the TV manifest for {}: {}", uuid, failure.getMessage());
            return null;
        }
        return !ready || segments.isEmpty() ? null
                : new Program(new Ref(context.uuid, uuid), Objects.toString(file.getFileName(), uuid), segments);
    }
}
