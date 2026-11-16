package cc.ginpika.bootfs.service.tv;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.SsoService;
import cc.ginpika.bootfs.service.hls.HlsFileService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class TvController {
    private final TvService tv;
    private final TvCatalog catalog;
    private final Context context;
    private final HlsFileService hls;
    private final FederationService federation;
    private final SsoService sso;
    private final TfsConfig config;

    @GetMapping({"/tv", "/tv/"})
    public ModelAndView tvPage() {
        return new ModelAndView("tv", Map.of("stats", tv.getStatus(), "messageLimit", TvService.MESSAGE_LIMIT));
    }

    @GetMapping("/tv/manage")
    public ModelAndView managePage() { return new ModelAndView("tv-manage", "stats", tv.getStatus()); }

    @GetMapping(value = "/tv/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    @ResponseBody
    public ResponseEntity<String> playlist() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tv.generatePlaylistM3u8());
    }

    @GetMapping({"/tv/ts/{node}/{uuid}/{fileName}", "/tv/ts/{uuid}/{fileName}"})
    @ResponseBody
    public ResponseEntity<?> segment(@PathVariable(required = false) String node,
                                     @PathVariable String uuid, @PathVariable String fileName,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        var ref = tv.selectedSegment(node, uuid, fileName);
        if (ref == null) return ResponseEntity.notFound().build();
        if (context.uuid.equals(ref.nodeId())) return localSegment(uuid, fileName);
        response.setHeader("Cache-Control", "no-store");
        federation.streamPeerAsset(ref.nodeId(), "tv/ts/" + uuid + "/" + fileName,
                "HEAD".equals(request.getMethod()), response);
        return null;
    }

    @GetMapping("/api/cluster/data/tv/ts/{uuid}/{fileName}")
    @ResponseBody
    public ResponseEntity<?> localSegment(@PathVariable String uuid, @PathVariable String fileName) {
        if (!TvCatalog.validSegment(fileName)) return ResponseEntity.notFound().build();
        var resource = hls.mappingToLocal(uuid, fileName);
        if (resource == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Type", "video/mp2t").body(resource);
    }

    @PostMapping("/tv/refresh")
    @ResponseBody
    public Map<String, Object> refresh() {
        tv.reinitialize();
        Map<String, Object> result = tv.getStatus();
        result.put("success", true);
        return result;
    }

    @GetMapping("/tv/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tv.getStatus());
    }

    @GetMapping("/tv/api/videos")
    @ResponseBody
    public List<TvCatalog.Video> videos() { return tv.getAllHlsVideos(); }

    @GetMapping("/tv/api/playlist")
    @ResponseBody
    public List<String> playlistIds() { return tv.getPlaylistUuids(); }

    @PostMapping("/tv/api/playlist")
    @ResponseBody
    public Map<String, Object> save(@RequestBody List<String> keys) {
        tv.savePlaylist(keys);
        return Map.of("success", true, "message", "播放列表已保存，TV 服务已重新初始化", "playlistSize", keys.size());
    }

    @GetMapping("/tv/chat")
    @ResponseBody
    public ResponseEntity<List<TvService.Message>> chat() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tv.chat());
    }

    @GetMapping("/tv/chat/session")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> chatSession(@RequestAttribute(value = "ssoUser", required = false) SsoUser user) {
        if (user != null) return ResponseEntity.ok(Map.of("authenticated", true));
        Map<String, Object> session = Map.of("authenticated", false);
        if (sso.isEnabled()) {
            String returnUrl = config.getWebEntrypoint().replaceAll("/+$", "") + "/tv";
            session = Map.of("authenticated", false, "loginUrl", sso.getLoginUrl(returnUrl));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(session);
    }

    record ChatInput(String text) {}

    @PostMapping(path = "/tv/chat", consumes = "application/json")
    @ResponseBody
    public ResponseEntity<TvService.Message> post(@RequestAttribute("ssoUser") SsoUser user, @RequestBody ChatInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tv.post(user, input.text()));
    }

    @GetMapping("/api/cluster/data/tv/videos")
    @ResponseBody
    public Map<String, ?> peerVideos() { return Map.of("videos", catalog.localVideos()); }

    @PostMapping("/api/cluster/data/tv/programs")
    @ResponseBody
    public Map<String, ?> peerPrograms(@RequestBody JSONObject request) {
        List<String> ids = request.getList("uuids", String.class);
        if (ids == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("programs", catalog.localPrograms(ids));
    }

    @PostMapping("/api/cluster/data/tv/state")
    @ResponseBody
    public Map<String, Object> peerState(@RequestBody JSONObject known) { return tv.peerState(known); }
}
