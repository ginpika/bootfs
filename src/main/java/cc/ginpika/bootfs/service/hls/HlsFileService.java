package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.cluster.ResourceVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.nio.file.*;

@Service
@RequiredArgsConstructor
public class HlsFileService {
    private final TfsConfig tfsConfig;
    private final ResourceVisibility visibility;
    public Resource mappingToLocal(String folder,String file) {
        if (visibility.deleted(folder) || file==null || file.contains("/") || file.contains("\\") || file.equals(".") || file.equals("..")) return null;
        Path root=Path.of(tfsConfig.getPathPrefix(),"hls").toAbsolutePath().normalize();
        Path target=root.resolve(folder).resolve(file).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root.resolve(folder))) return null;
        return new FileSystemResource(target);
    }
}
