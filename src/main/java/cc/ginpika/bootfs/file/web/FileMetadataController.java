package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.domain.dto.Tag;
import cc.ginpika.bootfs.file.FileMetadataService;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
class FileMetadataController {
    private final FileMetadataService metadata;
    private final ResourceRequestForwarder forwarder;

    @PostMapping("/publicAccess/{uuid}")
    ResponseEntity<?> setPublicAccess(@PathVariable String uuid, @RequestParam(defaultValue = "true") boolean enabled,
                                      HttpServletRequest request) throws ResourceWriteConflictException {
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, null);
        return remote != null ? remote : mutationResponse(metadata.setPublicAccess(uuid, enabled));
    }

    @PutMapping("/api/file/{uuid}/rename")
    ResponseEntity<?> rename(@PathVariable String uuid, @RequestBody Map<String, String> body,
                             HttpServletRequest request) throws ResourceWriteConflictException {
        String name = body.get("fileName");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().body("文件名不能为空");
        name = name.trim();
        if (name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0) {
            return ResponseEntity.badRequest().body("文件名包含非法字符");
        }
        if (name.length() > 255) return ResponseEntity.badRequest().body("文件名过长");
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, body);
        return remote != null ? remote : mutationResponse(metadata.rename(uuid, name));
    }

    @PutMapping("/api/file/{uuid}/tags")
    ResponseEntity<?> setTags(@PathVariable String uuid, @RequestBody List<Tag> tags,
                              HttpServletRequest request) throws ResourceWriteConflictException {
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, tags);
        return remote != null ? remote : mutationResponse(metadata.replaceTags(uuid, tags));
    }

    @PostMapping("/api/file/{uuid}/tags")
    ResponseEntity<?> addTags(@PathVariable String uuid, @RequestBody List<Tag> tags,
                              HttpServletRequest request) throws ResourceWriteConflictException {
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, tags);
        return remote != null ? remote : mutationResponse(metadata.addTags(uuid, tags));
    }

    @DeleteMapping("/api/file/{uuid}/tags")
    ResponseEntity<?> removeTags(@PathVariable String uuid, @RequestBody List<String> tags,
                                 HttpServletRequest request) throws ResourceWriteConflictException {
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, tags);
        return remote != null ? remote : mutationResponse(metadata.removeTags(uuid, tags));
    }

    @GetMapping("/api/file/{uuid}/tags")
    ResponseEntity<?> getTags(@PathVariable String uuid, HttpServletRequest request) {
        ResponseEntity<?> remote = forwarder.forwardIfRemote(uuid, request, null);
        if (remote != null) return remote;
        List<Tag> tags = metadata.tags(uuid);
        return tags == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tags);
    }

    @ExceptionHandler(ResourceWriteConflictException.class)
    void conflict(ResourceWriteConflictException failure, HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.CONFLICT.value(), failure.getMessage());
    }

    private ResponseEntity<?> mutationResponse(boolean changed) {
        return changed ? ResponseEntity.ok().header("X-BootFS-Revision", Long.toString(metadata.visibleRevision())).build()
                : ResponseEntity.notFound().build();
    }
}
