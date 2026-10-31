package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** A stable storage identity, shared by local catalogs and cluster registration. */
@Component
public final class NodeIdentity {
    private final String id;
    public NodeIdentity(TfsConfig config) throws IOException {
        Path root = Path.of(config.getRootPath());
        Files.createDirectories(root);
        Path identity = root.resolve("uuid");
        String configured = config.getUniqueId();
        if (Files.exists(identity)) {
            id = Files.readString(identity).trim();
            if (id.isEmpty()) throw new IOException("Empty node identity: " + identity);
            if (configured != null && !configured.equals(id)) {
                throw new IOException("Configured node identity differs from existing storage identity");
            }
        } else {
            id = configured == null || configured.isBlank() ? UUID.randomUUID().toString() : configured;
            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid node identity");
            Files.writeString(identity, id, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(root,StandardOpenOption.READ)) {
                directory.force(true);
            }
        }
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid stored node identity");
    }
    public String id() { return id; }
}
