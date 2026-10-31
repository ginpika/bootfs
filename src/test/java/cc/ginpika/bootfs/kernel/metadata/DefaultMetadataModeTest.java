package cc.ginpika.bootfs.kernel.metadata;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.cluster.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class DefaultMetadataModeTest {
    @TempDir Path root;
    @Test void freshRootDefaultsToDynamicWithoutAConfiguredSecretAndLegacyMetadataIsProtected() throws Exception {
        TfsConfig config = new TfsConfig(); config.setRootPath(root.resolve("new").toString()); config.setWebEntrypoint("http://127.0.0.1:8181");
        ClusterCredentials credentials = new ClusterCredentials(config, "");
        MetadataConfiguration factory = new MetadataConfiguration();
        try (MetadataStore metadata = factory.metadataStore(config, new MockEnvironment(), credentials)) {
            assertInstanceOf(DynamicMetadataStore.class, metadata);
            metadata.put("key", "value"); assertEquals("value", metadata.get("key"));
        }
        try (MetadataStore metadata = factory.metadataStore(config, new MockEnvironment(), credentials)) {
            assertInstanceOf(DynamicMetadataStore.class, metadata);
            assertEquals("value", metadata.get("key"));
        }
        config.setRootPath(root.resolve("legacy").toString());
        Path metadataRoot = Path.of(config.getRootPath(), "kernel/metadata");
        try (MetadataStore metadata = new RocksMetadataStore(metadataRoot)) { metadata.put("old", "retained"); }
        ClusterCredentials legacyCredentials = new ClusterCredentials(config, "");
        // An empty directory left by the previous failed default-dynamic startup is harmless.
        Files.createDirectories(Path.of(config.getRootPath(), "kernel/dynamic"));
        try (MetadataStore metadata = factory.metadataStore(config, new MockEnvironment(), legacyCredentials)) {
            assertInstanceOf(DynamicMetadataStore.class, metadata);
            assertEquals("retained", metadata.get("old"));
        }
        try (MetadataStore metadata = factory.metadataStore(config,new MockEnvironment().withProperty("bootfs.metadata.mode","dynamic"),legacyCredentials)) { assertEquals("retained",metadata.get("old")); }
        assertFalse(Files.exists(Path.of(config.getRootPath(), "kernel/dynamic/node.json")));
        try (MetadataStore metadata = factory.metadataStore(config, new MockEnvironment().withProperty("bootfs.metadata.mode", "embedded"), legacyCredentials)) {
            assertEquals("retained", metadata.get("old"));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(60) void automaticSelectionReopensExistingStaticRaftWithItsOriginalConfiguration() throws Exception {
        TfsConfig config = new TfsConfig(); config.setRootPath(root.resolve("static").toString());
        config.setWebEntrypoint("http://127.0.0.1:8181");
        String address;
        try (var reservation = new java.net.ServerSocket(0)) { address = "127.0.0.1:" + reservation.getLocalPort(); }
        MockEnvironment environment = new MockEnvironment().withProperty("bootfs.metadata.mode", "raft")
                .withProperty("bootfs.raft.node-id", "static-node").withProperty("bootfs.raft.group-id", java.util.UUID.randomUUID().toString())
                .withProperty("bootfs.raft.peers", "static-node=" + address).withProperty("bootfs.raft.bootstrap", "true");
        MetadataConfiguration factory = new MetadataConfiguration();
        ClusterCredentials credentials = new ClusterCredentials(config, "");
        try (MetadataStore metadata = factory.metadataStore(config, environment, credentials)) { metadata.put("original", "raft-history"); }
        String identity = Files.readString(Path.of(config.getRootPath(), "kernel/raft-identity.json"));
        environment.withProperty("bootfs.metadata.mode", "auto").withProperty("bootfs.raft.bootstrap", "false");
        try (MetadataStore metadata = factory.metadataStore(config, environment, credentials)) {
            assertInstanceOf(cc.ginpika.bootfs.kernel.consensus.RaftMetadataStore.class, metadata);
            assertEquals("raft-history", metadata.get("original"));
        }
        assertEquals(identity, Files.readString(Path.of(config.getRootPath(), "kernel/raft-identity.json")));
        assertFalse(Files.exists(Path.of(config.getRootPath(), "kernel/dynamic/node.json")));
    }

    @Test void ambiguousAndIncompleteStorageDoesNotFallBackToCreatingANewCluster() throws Exception {
        Path kernel = root.resolve("kernel");
        Files.createDirectories(kernel.resolve("dynamic"));
        Files.writeString(kernel.resolve("dynamic/node.json"), "{}");
        assertEquals("legacy-dynamic", MetadataConfiguration.resolveMode(kernel, "auto"));
        Files.createDirectories(kernel.resolve("metadata"));
        Files.writeString(kernel.resolve("metadata/CURRENT"), "MANIFEST-000001\n");
        assertThrows(MetadataException.class, () -> MetadataConfiguration.resolveMode(kernel, "auto"));
        Files.delete(kernel.resolve("dynamic/node.json"));
        assertEquals("dynamic", MetadataConfiguration.resolveMode(kernel, "auto"));
        Files.writeString(kernel.resolve("raft-identity.json"), "{}");
        assertEquals("raft", MetadataConfiguration.resolveMode(kernel, "auto"));
        Files.delete(kernel.resolve("raft-identity.json"));
        Files.delete(kernel.resolve("metadata/CURRENT"));
        Files.writeString(kernel.resolve("metadata/MANIFEST-000001"), "partial metadata");
        assertThrows(MetadataException.class, () -> MetadataConfiguration.resolveMode(kernel, "auto"));
        Files.writeString(kernel.resolve("dynamic/previous-state"), "partial dynamic state");
        assertThrows(MetadataException.class, () -> MetadataConfiguration.resolveMode(kernel, "auto"));
    }
}
