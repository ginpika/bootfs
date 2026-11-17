package cc.ginpika.bootfs.file.archive;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.IdGenerator;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.ResourceDeletionService;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BatchArchiveService {
    private final LocalFileInventory context;
    private final ResourceDeletionService deletionService;
    private final TfsConfig tfsConfig;

    public String create(List<String> ids) throws IOException {
        List<FileObject> batch = new ArrayList<>();
        long total = 0;
        for (String id : ids) {
            FileObject resource = context.query(id);
            if (resource == null || deletionService.isDeleted(resource)) throw new ResourceNotFoundException();
            total += Files.size(Path.of(resource.getPath()));
            if (total > 4L * 1024 * 1024 * 1024) throw new ArchiveLimitExceededException();
            batch.add(resource);
        }
        String uuid = IdGenerator.getUniqueId();
        Path zipPath = archivePath(uuid);
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(zipPath))) {
            zos.setEncoding("utf-8");
            Set<String> names = new HashSet<>();
            for (FileObject tf : batch) {
                String name = archiveName(tf.getFileName(), tf.getUuid());
                if (!names.add(name)) name = tf.getUuid() + "-" + name;
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                entry.setUnixMode(UnixStat.FILE_FLAG | 436);
                zos.putArchiveEntry(entry);
                try (InputStream input = Files.newInputStream(Path.of(tf.getPath()))) { input.transferTo(zos); }
                zos.closeArchiveEntry();
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(zipPath);
            } catch (IOException cleanupFailure) {
                failed.addSuppressed(cleanupFailure);
            }
            throw failed;
        }
        return uuid;
    }

    public Path archivePath(String uuid) {
        if (!uuid.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid archive ID");
        return Path.of(tfsConfig.getPathPrefix(), uuid + ".zip");
    }

    public static class ResourceNotFoundException extends IOException {}

    public static class ArchiveLimitExceededException extends IOException {}

    static String archiveName(String name, String fallback) {
        if (name == null) return fallback;
        String basename = name.replace('\\', '/'); basename = basename.substring(basename.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}:]", "_");
        return basename.isBlank() || basename.equals(".") || basename.equals("..") ? fallback : basename;
    }

}
