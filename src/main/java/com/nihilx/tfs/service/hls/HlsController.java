package com.nihilx.tfs.service.hls;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/hls")
public class HlsController {
    @Autowired
    MediaConvertService mediaConvertService;
    @Autowired
    HlsFileService hlsFileService;

    public static ReentrantLock CPU_LOCK = new ReentrantLock();

    @RequestMapping("/convert")
    public ResponseEntity<?> convert(@RequestParam("uuid") String uuid) {
        if (CPU_LOCK.isLocked()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            CPU_LOCK.lock();
            mediaConvertService.mp4ToH264Hls(uuid);
        } catch (Exception e) {
            log.error("Hls Convert fail", e);
            return ResponseEntity.internalServerError().build();
        } finally {
            CPU_LOCK.unlock();
        }
        return ResponseEntity.ok().body("200 Media converted");
    }

    @GetMapping("/{folder}/{file}")
    public ResponseEntity<?> hlsStaticServer(@PathVariable("folder") String folder,
                                             @PathVariable("file") String file) {
        Resource resource = hlsFileService.mappingToLocal(folder, file);
        if (Objects.nonNull(resource)) {
            if (file.endsWith(".ts")) {
                return ResponseEntity.ok()
                        .header("Content-Type", "video/mp2t")
                        .body(resource);
            } else if (file.endsWith(".m3u8")) {
                return ResponseEntity.ok().header("Content-Type", "application/vnd.apple.mpegurl").body(resource);
            }
            return ResponseEntity.ok().body("404 Not Found");
        }
        return ResponseEntity.ok().body("404 Not Found");
    }
}
