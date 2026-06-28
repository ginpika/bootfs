package cc.ginpika.bootfs.s3;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.service.FileService;
import cc.ginpika.bootfs.service.ReverseProxyService;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
public class S3StorageService {

    @Autowired private TfsConfig tfsConfig;
    @Autowired private Context context;
    @Autowired private EtcdService etcdService;
    @Autowired private FileService fileService;
    @Autowired private ReverseProxyService reverseProxyService;
    @Autowired private S3KeyIndex keyIndex;
    @Autowired private S3XmlWriter xmlWriter;

    public S3ObjectMeta putObject(String bucket, String key, HttpServletRequest req) throws IOException {
        String uuid = IdGenerator.getUniqueId();
        Path path = Path.of(tfsConfig.getPathPrefix(), uuid);
        ensureDataDir();

        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException(e);
        }

        long size;
        try (InputStream in = req.getInputStream();
             DigestInputStream dis = new DigestInputStream(in, md5);
             OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            size = dis.transferTo(out);
        }
        String etag = S3CryptoUtils.hex(md5.digest());

        String contentType = req.getContentType();
        if (contentType == null) contentType = "application/octet-stream";

        FileObject fo = new FileObject(path.toString(), uuid, key, size);
        context.record(fo, uuid);

        int dot = key.lastIndexOf('.');
        String ext = dot >= 0 ? key.substring(dot) : "";
        JSONObject meta = new JSONObject();
        meta.put("size", size);
        meta.put("ext", ext);
        meta.put("etag", etag);
        etcdService.putFile(uuid, context.buildUrl(uuid), meta.toJSONString());

        try {
            fileService.replication(new File(path.toString()), uuid, key);
        } catch (Exception e) {
            log.warn("S3 PutObject replication failed for {}: {}", uuid, e.getMessage());
        }

        S3ObjectMeta s3meta = new S3ObjectMeta(uuid, size, etag, System.currentTimeMillis(), key, contentType);
        keyIndex.putIndex(bucket, key, s3meta);
        return s3meta;
    }

    public void getObject(String bucket, String key, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null) {
            writeError(resp, S3ErrorCode.NO_SUCH_KEY, req);
            return;
        }
        FileObject fo = context.query(meta.getUuid());
        File localFile = (fo != null && fo.getPath() != null) ? new File(fo.getPath()) : null;
        if (localFile != null && localFile.exists()) {
            writeLocalObject(localFile, meta, req, resp);
        } else {
            // 远程文件兜底(媒体类型有效,非媒体远程 MVP2 补)
            reverseProxyService.reverseProxyFile(meta.getUuid(), resp, req);
            resp.setHeader("ETag", "\"" + meta.getEtag() + "\"");
        }
    }

    public void headObject(String bucket, String key, HttpServletResponse resp, HttpServletRequest req) throws IOException {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null) {
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

    public void deleteObject(String bucket, String key) {
        S3ObjectMeta meta = keyIndex.getIndex(bucket, key);
        if (meta == null) return; // 幂等
        String uuid = meta.getUuid();
        FileObject fo = context.query(uuid);
        if (fo != null && fo.getPath() != null) {
            try {
                new File(fo.getPath()).delete();
            } catch (Exception ignored) {}
        }
        context.remove(uuid);
        etcdService.delFileAndReplicas(uuid);
        keyIndex.deleteIndex(bucket, key);
    }

    private void writeLocalObject(File file, S3ObjectMeta meta, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long total = file.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        String rangeHeader = req.getHeader("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                    partial = true;
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    end = (dash < spec.length() - 1) ? Long.parseLong(spec.substring(dash + 1)) : total - 1;
                    partial = true;
                }
                if (partial) {
                    if (start >= total) {
                        resp.setStatus(416);
                        resp.setHeader("Content-Range", "bytes */" + total);
                        return;
                    }
                    if (end >= total) end = total - 1;
                    if (end < start) end = start;
                }
            } catch (NumberFormatException ignored) {
                partial = false;
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
