package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.file.ResourceDeletionService;
import cc.ginpika.bootfs.file.archive.BatchArchiveService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
class FileBatchController {
    private final ResourceDeletionService deletionService;
    private final BatchArchiveService archives;
    private final ResourceRequestForwarder forwarder;

    @PostMapping("/removeFileByUuid")
    Boolean removeFileByUuid(HttpServletRequest request, @RequestParam("uuid") String uuid) {
        ResponseEntity<byte[]> remote = forwarder.forwardIfRemote(uuid, request, null);
        if (remote != null) {
            if (!remote.getStatusCode().is2xxSuccessful()) throw new ResponseStatusException(remote.getStatusCode());
            return Boolean.TRUE.equals(JSONObject.parseObject(new String(remote.getBody(), StandardCharsets.UTF_8), Boolean.class));
        }
        String remoteIp = request.getHeader("X-forwarded-for");
        log.info("remove: {} from {}", uuid, remoteIp == null ? request.getRemoteAddr() : remoteIp);
        return deletionService.deleteResource(uuid);
    }

    @PostMapping("/removeFileBatch")
    Boolean removeFileBatch(HttpServletRequest request, @RequestBody String uuids) {
        for (String uuid : batchIds(uuids)) removeFileByUuid(request, uuid);
        return Boolean.TRUE;
    }

    @PostMapping("/downloadFileBatch")
    String downloadFileBatch(@RequestBody String uuids) throws IOException {
        try {
            return new JSONObject().fluentPut("uuid", archives.create(batchIds(uuids))).toJSONString();
        } catch (BatchArchiveService.ResourceNotFoundException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        } catch (BatchArchiveService.ArchiveLimitExceededException tooLarge) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Batch exceeds 4 GiB");
        }
    }

    private static List<String> batchIds(String json) {
        JSONArray values;
        try {
            values = JSONArray.parseArray(json);
        } catch (JSONException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid batch resource IDs", invalid);
        }
        if (values == null || values.size() > 1000 || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(id -> !(id instanceof String name) || !name.matches("[A-Za-z0-9_-]{1,128}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid batch resource IDs");
        }
        return values.stream().map(String.class::cast).toList();
    }

    @GetMapping("/g/b/{uuid}")
    void downloadBatchFile(@PathVariable String uuid, HttpServletResponse response) throws IOException {
        Path path;
        try {
            path = archives.archivePath(uuid);
        } catch (IllegalArgumentException invalid) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setContentLengthLong(Files.size(path));
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + path.getFileName());
            try (OutputStream output = response.getOutputStream()) {
                input.transferTo(output);
            }
        }
    }
}
