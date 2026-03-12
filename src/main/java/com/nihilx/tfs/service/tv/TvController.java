package com.nihilx.tfs.service.tv;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@CrossOrigin
@RequestMapping("/tv")
public class TvController {

    @Autowired
    private TvService tvService;

    @GetMapping("")
    public ModelAndView tvPage() {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("tv");
        
        Map<String, Object> status = tvService.getStatus();
        mv.getModel().put("stats", status);
        
        return mv;
    }

    @GetMapping("/manage")
    public ModelAndView managePage() {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("tv-manage");
        
        List<TvService.HlsVideoInfo> videos = tvService.getAllHlsVideos();
        mv.getModel().put("videos", videos);
        
        Map<String, Object> status = tvService.getStatus();
        mv.getModel().put("stats", status);
        
        return mv;
    }

    @GetMapping("/playlist.m3u8")
    @ResponseBody
    public ResponseEntity<String> getPlaylist() {
        if (!tvService.isInitialized()) {
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .header("Cache-Control", "no-cache")
                    .body("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n");
        }
        
        String playlist = tvService.generatePlaylistM3u8();
        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Cache-Control", "no-cache")
                .body(playlist);
    }

    @GetMapping("/ts/{uuid}/{fileName}")
    @ResponseBody
    public ResponseEntity<Resource> getTsSegment(
            @PathVariable("uuid") String uuid,
            @PathVariable("fileName") String fileName) {
        
        Resource resource = tvService.getTsSegment(uuid, fileName);
        if (resource != null) {
            return ResponseEntity.ok()
                    .header("Content-Type", "video/mp2t")
                    .header("Cache-Control", "public, max-age=3600")
                    .body(resource);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/refresh")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> refreshPlaylist() {
        tvService.reinitialize();
        
        Map<String, Object> result = tvService.getStatus();
        result.put("success", true);
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(tvService.getStatus());
    }

    @GetMapping("/api/videos")
    @ResponseBody
    public ResponseEntity<List<TvService.HlsVideoInfo>> getVideos() {
        return ResponseEntity.ok(tvService.getAllHlsVideos());
    }

    @PostMapping("/api/playlist")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> savePlaylist(@RequestBody List<String> uuids) {
        tvService.savePlaylist(uuids);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "播放列表已保存，TV 服务已重新初始化");
        result.put("playlistSize", uuids.size());
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/playlist")
    @ResponseBody
    public ResponseEntity<List<String>> getPlaylistUuids() {
        return ResponseEntity.ok(tvService.getPlaylistUuids());
    }
}
