package cc.ginpika.bootfs.service.hls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** M3U8 event playlist parsing and serialization for uploaded HLS segments. */
final class HlsPlaylist {
    static final double DEFAULT_DURATION = 10.0;
    private static final String HEADER = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:EVENT\n";
    private static final String ENDLIST = "#EXT-X-ENDLIST";

    record SegmentEntry(String fileName, double duration) {}

    private HlsPlaylist() {}

    static void writeInitial(Path playlistPath) throws IOException {
        Files.writeString(playlistPath, HEADER);
    }

    static void appendSegment(Path playlistPath, String fileName, double duration) throws IOException {
        String content = Files.exists(playlistPath) ? Files.readString(playlistPath) : HEADER;
        String segmentEntry = String.format(Locale.ROOT, "#EXTINF:%.6f,\n%s\n", duration, fileName);
        if (content.contains(ENDLIST)) {
            content = content.replace(ENDLIST, segmentEntry);
        } else {
            content += segmentEntry;
        }
        Files.writeString(playlistPath, content);
    }

    static void finalizePlaylist(Path playlistPath) throws IOException {
        String content = Files.readString(playlistPath);
        if (!content.contains(ENDLIST)) {
            Files.writeString(playlistPath, content + ENDLIST + "\n");
        }
    }

    static boolean isFinalized(Path playlistPath) throws IOException {
        return Files.exists(playlistPath) && Files.readString(playlistPath).contains(ENDLIST);
    }

    static List<SegmentEntry> parseSegments(String content) {
        List<SegmentEntry> segments = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXTINF:") || i + 1 >= lines.length) continue;
            String fileName = lines[i + 1].trim();
            if (!fileName.startsWith("#") && fileName.endsWith(".ts")) {
                segments.add(new SegmentEntry(fileName, parseDuration(line)));
            }
        }
        return segments;
    }

    static Map<String, Double> parseDurations(String content) {
        Map<String, Double> durations = new HashMap<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXTINF:") || i + 1 >= lines.length) continue;
            double duration = parseStrictDuration(line);
            if (Double.isNaN(duration)) continue;
            String fileName = lines[i + 1].trim();
            if (!fileName.startsWith("#")) {
                durations.put(fileName, duration);
            }
        }
        return durations;
    }

    static void writeOrdered(Path playlistPath, List<SegmentEntry> entries, boolean finalized) throws IOException {
        StringBuilder updated = new StringBuilder(HEADER);
        for (SegmentEntry entry : entries) {
            updated.append(String.format(Locale.ROOT, "#EXTINF:%.6f,\n", entry.duration()));
            updated.append(entry.fileName()).append("\n");
        }
        if (finalized) {
            updated.append(ENDLIST).append("\n");
        }
        Files.writeString(playlistPath, updated.toString());
    }

    static void removeSegment(Path playlistPath, String fileName) throws IOException {
        if (!Files.exists(playlistPath)) return;
        String[] lines = Files.readString(playlistPath).split("\n");
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals(fileName)) {
                if (i > 0 && lines[i - 1].trim().startsWith("#EXTINF:") && !kept.isEmpty()) {
                    kept.remove(kept.size() - 1);
                }
                continue;
            }
            kept.add(lines[i]);
        }
        String result = kept.isEmpty() ? "" : String.join("\n", kept) + "\n";
        Files.writeString(playlistPath, result);
    }

    private static double parseDuration(String extinfLine) {
        double parsed = parseStrictDuration(extinfLine);
        return Double.isNaN(parsed) ? DEFAULT_DURATION : parsed;
    }

    private static double parseStrictDuration(String extinfLine) {
        try {
            return Double.parseDouble(extinfLine.substring(8, extinfLine.indexOf(",")));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }
}
