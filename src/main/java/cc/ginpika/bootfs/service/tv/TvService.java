package cc.ginpika.bootfs.service.tv;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.tv.TvCatalog.Program;
import cc.ginpika.bootfs.service.tv.TvCatalog.Ref;
import cc.ginpika.bootfs.service.tv.TvCatalog.Video;
import cc.ginpika.bootfs.service.tv.TvPlaylist.Schedule;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TvService {
    private static final String SCHEDULE_PREFIX = "/tv/schedule/";
    private static final String LEGACY_IMPORTED = "/tv/legacy-imported";
    static final int MESSAGE_LIMIT = 240;
    private static final int CHAT_HISTORY_SIZE = 100;
    private static final long CHAT_TTL_MS = TimeUnit.HOURS.toMillis(24);

    private final Context context;
    private final TfsConfig config;
    private final MetadataStore metadata;
    private final FederationService federation;
    private final TvCatalog catalog;

    private final ScheduledExecutorService peerSync = Executors.newSingleThreadScheduledExecutor(job -> {
        Thread thread = new Thread(job, "bootfs-tv-federation");
        thread.setDaemon(true);
        return thread;
    });

    private String room;
    private Schedule schedule;
    private TvPlaylist playlist;
    private Set<String> members = Set.of();

    public record Message(String id, String author, String text, long sentAt) {}
    private record LocalMessage(String userId, Message message) {}
    private final Deque<LocalMessage> localChat = new ArrayDeque<>();
    private final Map<String, List<Message>> peerChat = new HashMap<>();

    @PostConstruct
    void start() {
        updateRoom();
        importLegacy();
        peerSync.scheduleWithFixedDelay(this::syncSafely, 0, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() { peerSync.shutdownNow(); }

    private void importLegacy() {
        Path legacy = Path.of(config.getPathPrefix(), "tv-playlist.json");
        if (!Files.isRegularFile(legacy) || metadata.get(LEGACY_IMPORTED) != null) return;
        try {
            if (schedule == null) {
                List<String> ids = JSON.parseObject(Files.readString(legacy)).getList("uuids", String.class);
                if (ids != null) {
                    Map<String, Program> ready = new HashMap<>();
                    catalog.localPrograms(ids).forEach(program -> ready.put(program.ref().uuid(), program));
                    List<Program> ordered = ids.stream().map(ready::get).filter(Objects::nonNull).toList();
                    install(new Schedule(1, context.uuid, System.currentTimeMillis(), ordered));
                }
            }
            metadata.put(LEGACY_IMPORTED, "true");
        } catch (Exception failure) {
            log.warn("Could not import the legacy TV playlist", failure);
        }
    }

    // Every operation is scoped to the current group. Pairing or leaving cannot reuse another room's chat.
    private void updateRoom() {
        String current = metadata instanceof DynamicMetadataStore dynamic ? dynamic.groupId() : "local/" + context.uuid;
        Set<String> currentMembers = new HashSet<>(federation.peers().keySet());
        currentMembers.add(context.uuid);
        boolean changed = !current.equals(room);
        if (changed) {
            String raw = metadata.get(SCHEDULE_PREFIX + current);
            schedule = raw == null ? null : JSON.parseObject(raw, Schedule.class);
            room = current;
            localChat.clear();
            peerChat.clear();
        }
        if (changed || !currentMembers.equals(members)) {
            members = currentMembers;
            peerChat.keySet().retainAll(members);
            playlist = new TvPlaylist(schedule, members);
        }
    }

    private void install(Schedule next) {
        metadata.put(SCHEDULE_PREFIX + room, JSON.toJSONString(next));
        schedule = next;
        playlist = new TvPlaylist(schedule, members);
    }

    public List<Video> getAllHlsVideos() {
        List<Video> videos = new ArrayList<>(catalog.videos());
        Set<String> present = new HashSet<>();
        videos.forEach(video -> present.add(video.key()));
        for (Program program : programs()) {
            if (present.add(program.ref().key())) videos.add(program.video(false));
        }
        videos.sort(Comparator.comparing(Video::fileName, String.CASE_INSENSITIVE_ORDER).thenComparing(Video::key));
        return videos;
    }

    private synchronized List<Program> programs() {
        updateRoom();
        return schedule == null ? List.of() : schedule.programs();
    }

    public List<String> getPlaylistUuids() { return programs().stream().map(p -> p.ref().key()).toList(); }

    public void savePlaylist(List<String> keys) {
        List<Ref> refs = keys.stream().map(catalog::ref).toList();
        String originalRoom;
        Map<Ref, Program> previous = new HashMap<>();
        synchronized (this) {
            programs().forEach(program -> previous.put(program.ref(), program));
            originalRoom = room;
        }
        List<Program> next = catalog.load(refs, previous);
        synchronized (this) {
            updateRoom();
            if (!originalRoom.equals(room) || refs.stream().anyMatch(ref -> !members.contains(ref.nodeId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TV membership changed");
            }
            long revision = schedule == null ? 1 : schedule.revision() + 1;
            install(new Schedule(revision, context.uuid, System.currentTimeMillis(), next));
        }
    }

    public void reinitialize() { savePlaylist(getPlaylistUuids()); }

    private synchronized TvPlaylist currentPlaylist() {
        updateRoom();
        return playlist;
    }

    public String generatePlaylistM3u8() { return currentPlaylist().manifest(System.currentTimeMillis()); }

    Ref selectedSegment(String node, String uuid, String filename) {
        if (!TvCatalog.validId(uuid) || !TvCatalog.validSegment(filename)) return null;
        TvPlaylist current = currentPlaylist();
        if (node == null) return current.find(uuid, filename);
        if (!TvCatalog.validId(node)) return null;
        Ref ref = new Ref(node, uuid);
        return current.includes(ref, filename) ? ref : null;
    }

    public synchronized Map<String, Object> getStatus() {
        TvPlaylist current = currentPlaylist();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", true);
        status.put("live", current.live());
        status.put("allTsListSize", current.size());
        status.put("totalDuration", current.duration());
        status.put("totalDurationFormatted", formatDuration(current.duration()));
        status.put("maxSegmentDuration", current.targetDuration());
        status.put("playlistWindowSize", TvPlaylist.WINDOW_SIZE);
        status.put("playlistSize", schedule == null ? 0 : schedule.programs().size());
        status.put("streamStartTimestamp", current.startedAt());
        status.put("streamVersion", current.version());
        status.put("elapsedSeconds", "--");
        status.put("currentCyclePosition", "--");
        if (current.live()) {
            var position = current.position(System.currentTimeMillis());
            status.put("elapsedSeconds", String.format(Locale.ROOT, "%.1f", position.elapsed()));
            status.put("currentCyclePosition", String.format(Locale.ROOT, "%.1f", position.offset()));
            var program = current.currentProgram(position);
            status.put("currentProgram", Map.of("fileName", program.fileName(), "nodeId", program.ref().nodeId()));
        }
        return status;
    }

    private static String formatDuration(double seconds) {
        long total = (long) seconds;
        if (total >= 3600) return "%d小时%d分钟%d秒".formatted(total / 3600, total / 60 % 60, total % 60);
        if (total >= 60) return "%d分钟%d秒".formatted(total / 60, total % 60);
        return total + "秒";
    }

    synchronized List<Message> chat() {
        updateRoom();
        List<Message> messages = new ArrayList<>(localMessages());
        peerChat.values().forEach(messages::addAll);
        long cutoff = System.currentTimeMillis() - CHAT_TTL_MS;
        messages.removeIf(message -> message.sentAt() < cutoff);
        messages.sort(Comparator.comparingLong(Message::sentAt).thenComparing(Message::id));
        return messages.subList(Math.max(0, messages.size() - CHAT_HISTORY_SIZE), messages.size());
    }

    synchronized Message post(SsoUser user, String input) {
        updateRoom();
        String text = Objects.toString(input, "").strip();
        if (text.isEmpty() || text.length() > MESSAGE_LIMIT || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid TV message");
        }
        String author = firstText(user.getNickname(), user.getUsername(), "SSO").replaceAll("\\p{Cntrl}", "").strip();
        if (author.isEmpty()) author = "SSO";
        if (author.codePointCount(0, author.length()) > 80) author = author.substring(0, author.offsetByCodePoints(0, 80));
        String userId = firstText(user.getId(), user.getUsername(), author);
        long now = System.currentTimeMillis();
        if (localChat.stream().anyMatch(entry -> entry.userId().equals(userId) && now - entry.message().sentAt() < 750)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "TV messages are being sent too quickly");
        }
        Message message = new Message(UUID.randomUUID().toString(), author, text, now);
        localChat.addLast(new LocalMessage(userId, message));
        if (localChat.size() > CHAT_HISTORY_SIZE) localChat.removeFirst();
        return message;
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private List<Message> localMessages() {
        long cutoff = System.currentTimeMillis() - CHAT_TTL_MS;
        while (!localChat.isEmpty() && localChat.getFirst().message().sentAt() < cutoff) localChat.removeFirst();
        return localChat.stream().map(LocalMessage::message).toList();
    }

    synchronized Map<String, Object> peerState(JSONObject known) {
        updateRoom();
        if (!room.equals(known.getString("room"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
        Map<String, Object> state = new HashMap<>();
        state.put("room", room);
        state.put("messages", localMessages());
        if (schedule != null && schedule.compare(known.getLongValue("revision"), Objects.toString(known.getString("author"), "")) > 0) {
            state.put("schedule", schedule);
        }
        return state;
    }

    private void syncSafely() {
        try { syncPeers(); }
        catch (RuntimeException failure) { log.warn("Could not update TV room from peers", failure); }
    }

    private void syncPeers() {
        JSONObject known;
        synchronized (this) {
            updateRoom();
            if (members.size() == 1) return;
            known = new JSONObject(Map.of("room", room, "revision", schedule == null ? 0 : schedule.revision(),
                    "author", schedule == null ? "" : schedule.author()));
        }
        var replies = federation.queryPeerData("tv/state", known);
        synchronized (this) {
            updateRoom();
            if (!room.equals(known.getString("room"))) return;
            Schedule latest = schedule;
            for (var entry : replies.entrySet()) {
                String node = entry.getKey();
                JSONObject reply = entry.getValue();
                if (!members.contains(node) || !room.equals(reply.getString("room"))) continue;
                var messages = reply.getJSONArray("messages");
                if (messages != null) peerChat.put(node, messages.toJavaList(Message.class));
                Schedule remote = reply.getObject("schedule", Schedule.class);
                if (remote != null && remote.newerThan(latest)) latest = remote;
            }
            if (latest != schedule) install(latest);
        }
    }
}
