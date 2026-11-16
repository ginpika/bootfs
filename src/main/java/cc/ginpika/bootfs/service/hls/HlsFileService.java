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
