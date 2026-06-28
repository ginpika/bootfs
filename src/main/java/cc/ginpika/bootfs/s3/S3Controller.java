package cc.ginpika.bootfs.s3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * S3 协议入口。path-style 寻址,endpoint_url=http://host:port/s3。
 * 单 bucket,校验失败返回 NoSuchBucket。
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
public class S3Controller {

    @Autowired private S3Config s3Config;
    @Autowired private S3StorageService storageService;
    @Autowired private S3KeyIndex keyIndex;
    @Autowired private S3XmlWriter xmlWriter;

    @GetMapping("/s3")
    public String listBuckets() {
        return xmlWriter.listBuckets(s3Config.getBucket());
    }

    @GetMapping("/s3/{bucket}")
    public String listObjects(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return null;
        return doListObjects(bucket, req);
    }

    @RequestMapping(value = "/s3/{bucket}/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void object(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return;
        String key = extractKey(req, bucket);
        if (key.isEmpty()) {
            // /s3/{bucket}/ 尾斜杠按 ListObjects 处理
            String xml = doListObjects(bucket, req);
            resp.setContentType(MediaType.APPLICATION_XML_VALUE);
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(xml);
            return;
        }
        if ("HEAD".equals(req.getMethod())) {
            storageService.headObject(bucket, key, resp, req);
        } else {
            storageService.getObject(bucket, key, req, resp);
        }
    }

    @PutMapping("/s3/{bucket}/**")
    public void putObject(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return;
        String key = extractKey(req, bucket);
        if (key.isEmpty()) {
            writeError(resp, S3ErrorCode.INVALID_REQUEST, req, "key is empty");
            return;
        }
        S3ObjectMeta meta = storageService.putObject(bucket, key, req);
        resp.setStatus(200);
        resp.setHeader("ETag", "\"" + meta.getEtag() + "\"");
    }

    @DeleteMapping("/s3/{bucket}/**")
    public void deleteObject(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return;
        String key = extractKey(req, bucket);
        if (key.isEmpty()) {
            writeError(resp, S3ErrorCode.INVALID_REQUEST, req, "key is empty");
            return;
        }
        storageService.deleteObject(bucket, key);
        resp.setStatus(204);
    }

    private String doListObjects(String bucket, HttpServletRequest req) {
        String prefix = req.getParameter("prefix");
        String delimiter = req.getParameter("delimiter");
        String continuationToken = req.getParameter("continuation-token");
        int maxKeys = 1000;
        String maxKeysParam = req.getParameter("max-keys");
        if (maxKeysParam != null) {
            try {
                maxKeys = Integer.parseInt(maxKeysParam);
            } catch (NumberFormatException ignored) {}
        }
        if (maxKeys <= 0) maxKeys = 1000;
        S3ListPage page = keyIndex.listIndex(bucket, prefix, delimiter, maxKeys, continuationToken);
        return xmlWriter.listBucketResult(page, bucket, prefix, delimiter, maxKeys, continuationToken);
    }

    private boolean validateBucket(String bucket, HttpServletResponse resp, HttpServletRequest req) throws IOException {
        if (s3Config.getBucket().equals(bucket)) return true;
        writeError(resp, S3ErrorCode.NO_SUCH_BUCKET, req, null);
        return false;
    }

    private void writeError(HttpServletResponse resp, S3ErrorCode code, HttpServletRequest req, String message) throws IOException {
        resp.setStatus(code.getHttpStatus());
        resp.setContentType(MediaType.APPLICATION_XML_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(xmlWriter.error(code, message, req.getRequestURI(), Long.toHexString(System.nanoTime())));
    }

    private String extractKey(HttpServletRequest req, String bucket) {
        String uri = req.getRequestURI();
        String prefix = "/s3/" + bucket + "/";
        int idx = uri.indexOf(prefix);
        if (idx < 0) return "";
        String encoded = uri.substring(idx + prefix.length());
        try {
            return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded;
        }
    }
}
