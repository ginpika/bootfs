package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * S3 存储协调:落盘/读取/删除,复用现有 Context/EtcdService/FileService/ReverseProxyService。
 * PutObject 走与 /f/{uuid} 一致的链路,实现双向互通。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3StorageService {

    private final TfsConfig tfsConfig;
    private final Context context;
    private final EtcdService etcdService;
    private final FileService fileService;
    private final ReverseProxyService reverseProxyService;
    private final S3KeyIndex keyIndex;
    private final S3XmlWriter xmlWriter;

    public synchronized S3ObjectMeta putObject(String bucket, String key, HttpServletRequest req) throws IOException {
        String hash = req.getHeader("x-amz-content-sha256");
        String encoding = req.getHeader("Content-Encoding");
        if ((encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (hash != null && !hash.equals("UNSIGNED-PAYLOAD") && !hash.matches("[0-9a-fA-F]{64}"))) {
            throw new S3PayloadException("Streaming aws-chunked signing modes are not supported", false);
        }
        String expected = keyIndex.rawIndex(bucket, key);
        String type = req.getContentType() == null ? "application/octet-stream" : req.getContentType();
        FileService.PublishedResource published = fileService.publishS3(req.getInputStream(), key, bucket, type, expected, hash, req.getHeader("X-BootFS-Request-Id"));
        return new S3ObjectMeta(published.file().getUuid(), published.file().getSize(), published.etag(),
                published.file().getCreatedAt(), key, type);
    }

    public void getObject(String bucket, String key, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null || etcdService.getOne("/tombstones/" + meta.getUuid()) != null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        FileObject fo = context.query(meta.getUuid());
        File localFile = (fo != null && fo.getPath() != null) ? new File(fo.getPath()) : null;
        if (localFile != null && localFile.exists()) {
            writeLocalObject(localFile, meta, req, resp);
        } else {
            proxyRemoteObject(meta, req, resp);
        }
    }

    public void headObject(String bucket, String key, HttpServletResponse resp, HttpServletRequest req) throws IOException {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null || etcdService.getOne("/tombstones/" + meta.getUuid()) != null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        resp.setStatus(200);
        resp.setHeader("ETag", "\"" + meta.getEtag() + "\"");
        resp.setHeader("Accept-Ranges", "bytes");
        resp.setContentType(meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
        resp.setHeader("Last-Modified", formatHttpDate(meta.getLastModified()));
        resp.setContentLengthLong(meta.getSize());
    }

    public synchronized void deleteObject(String bucket, String key) throws S3ConcurrentWriteException {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null) return;
        // The shared lifecycle commits the tombstone before conditionally removing its bindings.
        // A competing PUT with a different resource generation is preserved.
        fileService.deleteResource(meta.getUuid());
    }

    private void proxyRemoteObject(S3ObjectMeta meta, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (etcdService.getOne("/tombstones/" + meta.getUuid()) != null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        String primary = etcdService.getOne("/files/" + meta.getUuid());
        if (primary != null) candidates.add(primary);
        etcdService.scan("/files/" + meta.getUuid() + "/replicas/").forEach((key, value) -> {
            if (!key.endsWith("/meta")) candidates.add(value);
        });
        org.apache.http.client.config.RequestConfig config = org.apache.http.client.config.RequestConfig.custom()
                .setConnectTimeout(5000).setSocketTimeout(60000).build();
        try (org.apache.http.impl.client.CloseableHttpClient client = org.apache.http.impl.client.HttpClients.custom()
                .setDefaultRequestConfig(config).disableContentCompression().disableRedirectHandling().build()) {
            for (String url : candidates) {
                org.apache.http.client.methods.HttpGet get = new org.apache.http.client.methods.HttpGet(url);
                get.setHeader("Accept-Encoding", "identity");
                get.setHeader("Is-Proxy", "1");
                get.setHeader("Accept-Type", meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
                for (String header : new String[]{"Range", "If-Range", "If-Modified-Since", "If-None-Match"}) {
                    if (req.getHeader(header) != null) get.setHeader(header, req.getHeader(header));
                }
                try (org.apache.http.client.methods.CloseableHttpResponse upstream = client.execute(get)) {
                    int status = upstream.getStatusLine().getStatusCode();
                    if (status == 404 || status >= 500 || (status >= 300 && status != 304)) continue;
                    resp.setStatus(status);
                    for (String header : new String[]{"Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified"}) {
                        org.apache.http.Header value = upstream.getFirstHeader(header);
                        if (value != null) resp.setHeader(header, value.getValue());
                    }
                    resp.setContentType(meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
                    resp.setHeader("ETag", "\"" + meta.getEtag() + "\"");
                    if (upstream.getEntity() != null) upstream.getEntity().writeTo(resp.getOutputStream());
                    return;
                } catch (IOException failure) {
                    if (resp.isCommitted()) throw failure;
                    log.warn("S3 resource {} candidate {} unavailable", meta.getUuid(), url, failure);
                }
            }
        }
        resp.sendError(503, "No confirmed object replica is currently reachable");
    }

    private void writeLocalObject(File file, S3ObjectMeta meta, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long total = file.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        String rangeHeader = req.getHeader("Range");
        String ifRange = req.getHeader("If-Range");
        boolean rangeAllowed = ifRange == null || ifRange.equals("\"" + meta.getEtag() + "\"")
                || ifRange.equals(formatHttpDate(meta.getLastModified()));
        if (rangeHeader != null && rangeAllowed) {
            try {
                if (!rangeHeader.startsWith("bytes=") || rangeHeader.indexOf(',') >= 0) throw new IllegalArgumentException();
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash < 0 || spec.indexOf('-', dash + 1) >= 0 || total == 0) throw new IllegalArgumentException();
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, total - suffix);
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) end = Long.parseLong(spec.substring(dash + 1));
                    if (start < 0 || start >= total || end < start) throw new IllegalArgumentException();
                    end = Math.min(end, total - 1);
                }
                partial = true;
            } catch (IllegalArgumentException invalid) {
                resp.setStatus(416);
                resp.setHeader("Content-Range", "bytes */" + total);
                return;
            }
        }

        resp.setHeader("ETag", "\"" + meta.getEtag() + "\"");
        resp.setHeader("Accept-Ranges", "bytes");
        resp.setContentType(meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
        resp.setHeader("Last-Modified", formatHttpDate(meta.getLastModified()));

        if (partial) {
            long len = end - start + 1;
            resp.setStatus(206);
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
            resp.setContentLengthLong(len);
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(start);
                OutputStream out = resp.getOutputStream();
                byte[] buf = new byte[8192];
                long remaining = len;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = raf.read(buf, 0, toRead);
                    if (read < 0) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
            }
        } else {
            resp.setStatus(200);
            resp.setContentLengthLong(total);
            Files.copy(file.toPath(), resp.getOutputStream());
        }
    }

    private void writeError(HttpServletResponse resp, S3ErrorCode code, HttpServletRequest req) throws IOException {
        resp.setStatus(code.getHttpStatus());
        resp.setContentType("application/xml");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(xmlWriter.error(code, code.getDefaultMessage(),
                req.getRequestURI(), Long.toHexString(System.nanoTime())));
    }

    private void ensureDataDir() throws IOException {
        Path dataDir = Path.of(tfsConfig.getPathPrefix());
        if (Files.notExists(dataDir)) {
            Files.createDirectories(dataDir);
        }
    }

    private String formatHttpDate(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date(millis));
    }
}
