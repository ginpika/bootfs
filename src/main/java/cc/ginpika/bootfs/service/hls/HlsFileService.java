package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.cluster.ResourceVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HlsFileService {
    private final TfsConfig tfsConfig;
    private final ResourceVisibility visibility;

    public boolean validFolderId(String uuid) {
        return uuid != null && uuid.matches("[A-Za-z0-9_-]+");
    }

    public boolean isDeleted(String uuid) {
        return visibility.deleted(uuid);
    }

    public boolean safeSegmentName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,200}\\.[tT][sS]");
    }

    public Path directory(String uuid) throws IOException {
        if (!validFolderId(uuid)) throw new IOException("Invalid HLS resource identity");
        Path root = root();
        if (Files.isSymbolicLink(root)) throw new IOException("HLS root must not be a symlink");
        Path dir = root.resolve(uuid).normalize();
        if (!dir.startsWith(root) || Files.isSymbolicLink(dir)) throw new IOException("Invalid HLS directory");
        return dir;
    }

    public Path playlistPath(String uuid) throws IOException {
        return directory(uuid).resolve("playlist.m3u8");
    }

    public Path segmentPath(String uuid, String fileName) throws IOException {
        if (!safeSegmentName(fileName)) throw new IOException("Invalid HLS segment name");
        Path dir = directory(uuid);
        Path segment = dir.resolve(fileName).normalize();
        if (!segment.startsWith(dir) || Files.isSymbolicLink(segment)) throw new IOException("Invalid HLS segment path");
        return segment;
    }

    public List<String> localFolders() throws IOException {
        Path root = root();
        try (var entries = Files.list(root)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[A-Za-z0-9_-]+"))
                    .toList();
        } catch (NoSuchFileException empty) {
            return List.of();
        }
    }

    public Resource mappingToLocal(String folder,String file) {
        if (visibility.deleted(folder) || file==null || file.contains("/") || file.contains("\\") || file.equals(".") || file.equals("..")) return null;
        Path root=root();
        Path target=root.resolve(folder).resolve(file).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root.resolve(folder))) return null;
        return new FileSystemResource(target);
    }

    private Path root() { return Path.of(tfsConfig.getPathPrefix(),"hls").toAbsolutePath().normalize(); }
}
