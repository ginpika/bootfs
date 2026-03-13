package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class HlsFileService {
    @Autowired
    TfsConfig tfsConfig;

    public Resource mappingToLocal(String folder, String file) {
        Path target = Path.of(tfsConfig.getPathPrefix(), "hls", folder, file);
        if (!Files.exists(target)) return null;
        return new FileSystemResource(target);
    }
}
