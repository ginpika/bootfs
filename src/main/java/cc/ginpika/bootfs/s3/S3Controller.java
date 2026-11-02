package cc.ginpika.bootfs.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class S3Controller {

    @ExceptionHandler(S3OperationException.class)
    public void operationFailed(S3OperationException error, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        writeError(resp, error.getCode(), req, error.getMessage());
    }

    @ExceptionHandler(cc.ginpika.bootfs.kernel.object.PublicationPendingException.class)
    public void pending(cc.ginpika.bootfs.kernel.object.PublicationPendingException error, HttpServletResponse response) throws IOException {
        response.setStatus(503); response.setHeader("X-BootFS-Upload-Id", error.getUploadId());
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        response.getWriter().write("<Error><Code>ServiceUnavailable</Code><Message>Upload not acknowledged; query status or retry with the same request ID.</Message></Error>");
    }

    @ExceptionHandler(S3PayloadException.class)
    public void invalidPayload(S3PayloadException error, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(400);
        resp.setContentType(MediaType.APPLICATION_XML_VALUE);
        resp.getWriter().write(error.isDigestMismatch()
                ? "<Error><Code>XAmzContentSHA256Mismatch</Code><Message>Payload hash does not match.</Message></Error>"
                : "<Error><Code>InvalidRequest</Code><Message>Unsupported payload encoding or hash.</Message></Error>");
    }

    @ExceptionHandler(S3ConcurrentWriteException.class)
    public void concurrentWrite(S3ConcurrentWriteException error, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(409);
        resp.setContentType(MediaType.APPLICATION_XML_VALUE);
        resp.getWriter().write("<Error><Code>OperationAborted</Code><Message>Concurrent object modification; retry the request.</Message></Error>");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void invalidRequest(IllegalArgumentException error, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        writeError(resp,S3ErrorCode.INVALID_REQUEST,req,error.getMessage());
    }

    private final S3Config s3Config;
    private final S3StorageService storageService;
    private final S3KeyIndex keyIndex;
    private final S3XmlWriter xmlWriter;

    @GetMapping(value = "/s3", produces = MediaType.APPLICATION_XML_VALUE)
    public String listBuckets() {
        return xmlWriter.listBuckets(s3Config.getBucket());
    }

    @GetMapping(value = {"/s3/{bucket}", "/s3/{bucket}/"}, params = "location", produces = MediaType.APPLICATION_XML_VALUE)
    public String bucketLocation(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return null;
        return xmlWriter.bucketLocation(s3Config.getRegion());
    }

    @RequestMapping(value = {"/s3/{bucket}", "/s3/{bucket}/"}, method = RequestMethod.HEAD)
    public void headBucket(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return;
        resp.setHeader("x-amz-bucket-region", s3Config.getRegion());
    }

    @GetMapping(value = "/s3/{bucket}", produces = MediaType.APPLICATION_XML_VALUE)
    public String listObjects(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!validateBucket(bucket, resp, req)) return null;
        return doListObjects(bucket, req);
    }

    @RequestMapping(value = "/s3/{bucket}/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void object(@PathVariable String bucket, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
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
        String copySource = req.getHeader("x-amz-copy-source");
        S3ObjectMeta meta;
        if (copySource != null) {
            String[] source = extractCopySource(copySource);
            if (!validateBucket(source[0], resp, req)) return;
            meta = storageService.copyObject(bucket, key, source[1], req);
            resp.setContentType(MediaType.APPLICATION_XML_VALUE);
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(xmlWriter.copyObjectResult(meta));
        } else {
            meta = storageService.putObject(bucket, key, req);
        }
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
        boolean v2 = "2".equals(req.getParameter("list-type"));
        String cursor = req.getParameter(v2 ? "continuation-token" : "marker");
        int maxKeys = 1000;
        String maxKeysParam = req.getParameter("max-keys");
        if (maxKeysParam != null) {
            try {
                maxKeys = Integer.parseInt(maxKeysParam);
            } catch (NumberFormatException ignored) {}
        }
        maxKeys = Math.max(0, Math.min(1000, maxKeys));
        S3ListPage page = v2 ? keyIndex.listIndex(bucket, prefix, delimiter, maxKeys, cursor)
                : keyIndex.listIndexV1(bucket, prefix, delimiter, maxKeys, cursor);
        return xmlWriter.listBucketResult(page, bucket, prefix, delimiter, maxKeys, cursor, v2);
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

    private String[] extractCopySource(String value) {
        // Split the optional version query before decoding: %3F belongs to the key.
        int query = value.indexOf('?');
        if (query >= 0) {
            if (!"versionId=null".equals(value.substring(query + 1))) {
                throw new IllegalArgumentException("Source object versions are not supported");
            }
            value = value.substring(0, query);
        }
        String source = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (source.startsWith("/")) source = source.substring(1);
        int slash = source.indexOf('/');
        if (slash <= 0 || slash == source.length() - 1) throw new IllegalArgumentException("Invalid copy source");
        return new String[]{source.substring(0, slash), source.substring(slash + 1)};
    }
}
