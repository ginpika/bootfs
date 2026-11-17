package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.kernel.object.PayloadDigestMismatchException;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;

import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.FileService;
import cc.ginpika.bootfs.file.PublishedResource;
import cc.ginpika.bootfs.file.ResourceDeletionService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FilterInputStream;
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
import java.util.HexFormat;
import java.util.TimeZone;

/**
 * S3 存储协调:落盘/读取/删除,复用现有 LocalFileInventory/MetadataRegistry/FileService/ReverseProxyService。
 * PutObject 走与 /f/{uuid} 一致的链路,实现双向互通。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3StorageService {

    private final TfsConfig tfsConfig;
    private final LocalFileInventory context;
    private final MetadataRegistry metadataRegistry;
    private final FileService fileService;
    private final ResourceDeletionService deletionService;
    private final ReverseProxyService reverseProxyService;
    private final ObjectKeyRepository keyIndex;
    private final S3XmlWriter xmlWriter;

    public synchronized ObjectKeyBinding putObject(String bucket, String key, HttpServletRequest req) throws IOException {
        String hash = req.getHeader("x-amz-content-sha256");
        String encoding = req.getHeader("Content-Encoding");
        if ((encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (hash != null && !hash.equals("UNSIGNED-PAYLOAD") && !hash.matches("[0-9a-fA-F]{64}"))) {
            throw new UnsupportedS3PayloadException("Streaming aws-chunked signing modes are not supported");
        }
        String expected = keyIndex.findRaw(bucket, key);
        String type = req.getContentType() == null ? "application/octet-stream" : req.getContentType();
        PublishedResource published = fileService.publishBoundObject(req.getInputStream(), key, bucket, type, expected, hash, req.getHeader("X-BootFS-Request-Id"));
        return new ObjectKeyBinding(published.file().getUuid(), published.file().getSize(), published.etag(),
                published.file().getCreatedAt(), key, type);
    }

    /** CopyObject has an empty request body; the new object must be published from the source bytes. */
    public synchronized ObjectKeyBinding copyObject(String bucket, String key, String sourceKey, HttpServletRequest req) throws IOException {
        String hash = req.getHeader("x-amz-content-sha256");
        String encoding = req.getHeader("Content-Encoding");
        if ((encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (hash != null && !hash.equals("UNSIGNED-PAYLOAD") && !hash.matches("[0-9a-fA-F]{64}"))) {
            throw new UnsupportedS3PayloadException("Streaming aws-chunked signing modes are not supported");
        }
        if (req.getInputStream().read() != -1) throw new IllegalArgumentException("CopyObject requests must have an empty body");
        if (hash != null && !"UNSIGNED-PAYLOAD".equals(hash) && !S3CryptoUtils.sha256Hex(new byte[0]).equalsIgnoreCase(hash)) {
            throw new PayloadDigestMismatchException();
        }
        ObjectKeyBinding source = keyIndex.find(bucket, sourceKey);
        if (source == null || metadataRegistry.getOne("/tombstones/" + source.getUuid()) != null) {
            throw new S3OperationException(S3ErrorCode.NO_SUCH_KEY);
        }
        String match = req.getHeader("x-amz-copy-source-if-match");
        String noneMatch = req.getHeader("x-amz-copy-source-if-none-match");
        String quotedEtag = "\"" + source.getEtag() + "\"";
        if ((match != null && !match.equals(quotedEtag) && !match.equals(source.getEtag()) && !match.equals("*"))
                || (noneMatch != null && (noneMatch.equals(quotedEtag) || noneMatch.equals(source.getEtag()) || noneMatch.equals("*")))) {
            throw new S3OperationException(S3ErrorCode.PRECONDITION_FAILED);
        }
        String directive = req.getHeader("x-amz-metadata-directive");
        if (directive != null && !"COPY".equals(directive) && !"REPLACE".equals(directive)) {
            throw new IllegalArgumentException("Invalid metadata directive");
        }
        String type = "REPLACE".equals(directive) ? req.getContentType() : source.getContentType();
        if (type == null) type = "application/octet-stream";
        String expected = keyIndex.findRaw(bucket, key);
        FileObject file = context.query(source.getUuid());
        if (file != null && file.getPath() != null && Files.isRegularFile(Path.of(file.getPath()))) {
            try (InputStream input = verifiedCopyStream(Files.newInputStream(Path.of(file.getPath())), source)) {
                return publishCopy(input, bucket, key, type, expected, req);
            }
        }
        return copyRemoteObject(bucket, key, source, type, expected, req);
    }

    private ObjectKeyBinding publishCopy(InputStream input, String bucket, String key, String type, String expected,
                                     HttpServletRequest req) throws IOException {
        // The request hash describes the empty CopyObject request, not the copied object's contents.
        PublishedResource published = fileService.publishBoundObject(input, key, bucket, type, expected, null,
                req.getHeader("X-BootFS-Request-Id"));
        return new ObjectKeyBinding(published.file().getUuid(), published.file().getSize(), published.etag(),
                published.file().getCreatedAt(), key, type);
    }

    private ObjectKeyBinding copyRemoteObject(String bucket, String key, ObjectKeyBinding source, String type, String expected,
                                          HttpServletRequest req) throws IOException {
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        String primary = metadataRegistry.getOne("/files/" + source.getUuid());
        if (primary != null) candidates.add(primary);
        metadataRegistry.scan("/files/" + source.getUuid() + "/replicas/").forEach((route, url) -> {
            if (!route.endsWith("/meta")) candidates.add(url);
        });
        var config = org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(60000).build();
        try (var client = org.apache.http.impl.client.HttpClients.custom().setDefaultRequestConfig(config)
                .disableContentCompression().disableRedirectHandling().build()) {
            for (String url : candidates) {
                var get = new org.apache.http.client.methods.HttpGet(url);
                get.setHeader("Accept-Encoding", "identity");
                get.setHeader("Is-Proxy", "1");
                get.setHeader("Accept-Type", type);
                get.setHeader("If-Match", "\"" + source.getEtag() + "\"");
                try (var upstream = client.execute(get)) {
                    if (upstream.getStatusLine().getStatusCode() != 200 || upstream.getEntity() == null) continue;
                    try (InputStream input = verifiedCopyStream(upstream.getEntity().getContent(), source)) {
                        return publishCopy(input, bucket, key, type, expected, req);
                    }
                } catch (ResourceWriteConflictException | cc.ginpika.bootfs.kernel.object.PublicationPendingException failure) {
                    throw failure;
                } catch (IOException failure) {
                    log.warn("S3 copy source {} candidate {} unavailable", source.getUuid(), url, failure);
                }
            }
        }
        throw new S3OperationException(S3ErrorCode.SERVICE_UNAVAILABLE);
    }

    private InputStream verifiedCopyStream(InputStream input, ObjectKeyBinding source) {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("MD5"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return new FilterInputStream(input) {
            long count;
            boolean verified;
            @Override public int read() throws IOException {
                int value = in.read();
                if (value < 0) verifyEnd();
                else { count++; digest.update((byte) value); }
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, length);
                if (read < 0) verifyEnd();
                else { count += read; digest.update(bytes, offset, read); }
                return read;
            }
            private void verifyEnd() throws IOException {
                if (verified) return;
                verified = true;
                if (count != source.getSize() || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(source.getEtag())) {
                    throw new IOException("Copy source content no longer matches its metadata");
                }
            }
        };
    }

    public void getObject(String bucket, String key, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
        ObjectKeyBinding meta = keyIndex.find(bucket, key);
        if (meta == null || metadataRegistry.getOne("/tombstones/" + meta.getUuid()) != null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        FileObject fo = context.query(meta.getUuid());
        File localFile = (fo != null && fo.getPath() != null) ? new File(fo.getPath()) : null;
        if (localFile != null && localFile.exists()) {
            String raw = metadataRegistry.getOne("/files/" + meta.getUuid() + "/meta");
            if (raw != null) {
                JSONObject resourceMeta = JSONObject.parseObject(raw);
                String contentId = fo.getContentObjectId() == null ? fo.getUuid() : fo.getContentObjectId();
                String committedId = resourceMeta.getString("contentObjectId");
                if (committedId == null) committedId = fo.getUuid();
                if (!contentId.equals(committedId) || (resourceMeta.getString("etag") != null
                        && !resourceMeta.getString("etag").equals(meta.getEtag()))) continue;
            }
            writeLocalObject(localFile, meta, req, resp);
        } else {
            proxyRemoteObject(meta, req, resp);
        }
        return;
        }
        resp.sendError(503, "Object is being updated; retry the download");
    }

    public void headObject(String bucket, String key, HttpServletResponse resp, HttpServletRequest req) throws IOException {
        ObjectKeyBinding meta = keyIndex.find(bucket, key);
        if (meta == null || metadataRegistry.getOne("/tombstones/" + meta.getUuid()) != null) {
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

    public synchronized void deleteObject(String bucket, String key) throws ResourceWriteConflictException {
        ObjectKeyBinding meta = keyIndex.find(bucket, key);
        if (meta == null) return;
        // The shared lifecycle commits the tombstone before conditionally removing its bindings.
        // A competing PUT with a different resource generation is preserved.
        deletionService.deleteResource(meta.getUuid());
    }

    private void proxyRemoteObject(ObjectKeyBinding meta, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (metadataRegistry.getOne("/tombstones/" + meta.getUuid()) != null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        String resourceMetadata = metadataRegistry.getOne("/files/" + meta.getUuid() + "/meta");
        boolean editableRevision = resourceMetadata != null && JSONObject.parseObject(resourceMetadata).getString("textVersion") != null;
        String primary = metadataRegistry.getOne("/files/" + meta.getUuid());
        if (primary != null) candidates.add(primary);
        metadataRegistry.scan("/files/" + meta.getUuid() + "/replicas/").forEach((key, value) -> {
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
                if (editableRevision && meta.getEtag() != null) get.setHeader("If-Match", "\"" + meta.getEtag() + "\"");
                for (String header : new String[]{"Range", "If-Range", "If-Modified-Since", "If-None-Match"}) {
                    if (req.getHeader(header) != null) get.setHeader(header, req.getHeader(header));
                }
                try (org.apache.http.client.methods.CloseableHttpResponse upstream = client.execute(get)) {
                    int status = upstream.getStatusLine().getStatusCode();
                    if (status == 404 || status == 412 || status >= 500 || (status >= 300 && status != 304)) continue;
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

    private void writeLocalObject(File file, ObjectKeyBinding meta, HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
