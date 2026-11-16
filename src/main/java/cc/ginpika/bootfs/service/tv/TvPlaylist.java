package cc.ginpika.bootfs.service.tv;

import cc.ginpika.bootfs.service.tv.TvCatalog.Program;
import cc.ginpika.bootfs.service.tv.TvCatalog.Ref;
import cc.ginpika.bootfs.service.tv.TvCatalog.Segment;

import java.util.*;

final class TvPlaylist {
    static final int WINDOW_SIZE = 5;
    private static final String HEADER = "#EXTM3U\n#EXT-X-VERSION:3\n";
    private static final String EMPTY = HEADER + "#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n";

    record Schedule(long revision, String author, long startedAt, List<Program> programs) {
        Schedule {
            Objects.requireNonNull(author);
            programs = List.copyOf(programs);
        }

        boolean newerThan(Schedule other) {
            return other == null || compare(other.revision, other.author) > 0;
        }

        int compare(long otherRevision, String otherAuthor) {
            int order = Long.compare(revision, otherRevision);
            return order == 0 ? author.compareTo(otherAuthor) : order;
        }
    }

    private record Slice(Program program, Segment segment, int index, int programIndex, double end) {}
    record Position(long cycles, int index, double elapsed, double offset) {}

    private final List<Slice> slices = new ArrayList<>();
    private final Map<Ref, Set<String>> filenames = new HashMap<>();
    private final long startedAt;
    private final String version;
    private final double duration;
    private final int targetDuration;
    private final int programCount;

    TvPlaylist(Schedule schedule, Set<String> members) {
        startedAt = schedule == null ? 0 : schedule.startedAt();
        double end = 0;
        double max = 0;
        int programIndex = 0;
        Set<String> sources = new TreeSet<>();
        if (schedule != null) for (Program program : schedule.programs()) {
            if (!members.contains(program.ref().nodeId())) continue;
            sources.add(program.ref().nodeId());
            Set<String> files = filenames.computeIfAbsent(program.ref(), ignored -> new HashSet<>());
            for (int i = 0; i < program.segments().size(); i++) {
                Segment segment = program.segments().get(i);
                end += segment.duration();
                max = Math.max(max, segment.duration());
                slices.add(new Slice(program, segment, i, programIndex, end));
                files.add(segment.filename());
            }
            programIndex++;
        }
        programCount = programIndex;
        duration = end;
        targetDuration = slices.isEmpty() ? 10 : (int) Math.ceil(max);
        version = schedule == null ? "" : schedule.revision() + "-" + schedule.author() + ":" + String.join(",", sources);
    }

    boolean live() { return !slices.isEmpty(); }
    int size() { return slices.size(); }
    double duration() { return duration; }
    int targetDuration() { return targetDuration; }
    long startedAt() { return startedAt; }
    String version() { return version; }

    boolean includes(Ref ref, String filename) {
        return filenames.getOrDefault(ref, Set.of()).contains(filename);
    }

    Ref find(String uuid, String filename) {
        Ref match = null;
        for (var entry : filenames.entrySet()) {
            if (!entry.getKey().uuid().equals(uuid) || !entry.getValue().contains(filename)) continue;
            if (match != null) return null;
            match = entry.getKey();
        }
        return match;
    }

    Position position(long now) {
        double elapsed = Math.max(0, (now - startedAt) / 1000.0 - 3);
        double offset = elapsed % duration;
        int low = 0;
        int high = slices.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (slices.get(mid).end() <= offset) low = mid + 1;
            else high = mid;
        }
        long cycles = (long) (elapsed / duration);
        return new Position(cycles, low, elapsed, offset);
    }

    Program currentProgram(Position position) { return slices.get(position.index()).program(); }

    String manifest(long now) {
        if (!live()) return EMPTY;
        Position current = position(now);
        long sequence = current.cycles() * slices.size() + current.index();
        long discontinuity = current.cycles() * programCount + slices.get(current.index()).programIndex();
        StringBuilder playlist = new StringBuilder(HEADER)
                .append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
                .append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n')
                .append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuity).append('\n');
        for (int i = 0; i < WINDOW_SIZE; i++) {
            Slice slice = slices.get((current.index() + i) % slices.size());
            if (i > 0 && slice.index() == 0) playlist.append("#EXT-X-DISCONTINUITY\n");
            playlist.append("#EXTINF:").append(String.format(Locale.ROOT, "%.6f", slice.segment().duration())).append(",\n")
                    .append(slice.program().ref().segmentUrl(slice.segment().filename())).append('\n');
        }
        return playlist.toString();
    }
}
