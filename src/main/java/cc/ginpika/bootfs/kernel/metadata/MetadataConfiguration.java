package cc.ginpika.bootfs.kernel.metadata;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.consensus.RaftMetadataStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

@Configuration
public class MetadataConfiguration {
    @Bean(destroyMethod = "close")
    public MetadataStore metadataStore(TfsConfig files, Environment environment, cc.ginpika.bootfs.kernel.cluster.ClusterCredentials credentials) {
        Path root = Path.of(Objects.requireNonNull(files.getRootPath(), "tfs.root-path is required"), "kernel");
        String configuredMode = environment.getProperty("bootfs.metadata.mode", "auto");
        String mode = resolveMode(root, configuredMode);
        if ("auto".equals(configuredMode)) {
            org.slf4j.LoggerFactory.getLogger(MetadataConfiguration.class).info("Using {} metadata mode for {} (automatic selection)", mode, root);
        }
        if (java.nio.file.Files.exists(root.getParent().resolve("restore-plan.json"))
                && environment.getProperty("bootfs.restore.plan", "").isBlank()
                && !java.nio.file.Files.exists(root.resolve("metadata/CURRENT"))
                && !java.nio.file.Files.exists(root.resolve("dynamic/node.json"))) {
            throw new MetadataException("A restored seed requires explicit bootfs.restore.plan on its first start");
        }
        if ("legacy-dynamic".equals(mode)) throw new MetadataException("Legacy global-Raft dynamic storage requires portable backup/restore into a local node; original data is unchanged");
        if ("dynamic".equals(mode) || "embedded".equals(mode)) {
            try {
                return restore(new cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore(files), environment, root);
            } catch (java.io.IOException failure) { throw new MetadataException("Cannot start dynamic cluster", failure); }
        }
        if (java.nio.file.Files.exists(root.resolve("dynamic/node.json"))) throw new MetadataException("Dynamic storage cannot change modes in place; use portable backup/restore");
        if (!"raft".equals(mode)) throw new IllegalArgumentException("Unknown bootfs.metadata.mode: " + mode);
        String nodeId = environment.getRequiredProperty("bootfs.raft.node-id");
        UUID groupId = UUID.fromString(environment.getRequiredProperty("bootfs.raft.group-id"));
        Map<String, String> peers = parsePeers(environment.getRequiredProperty("bootfs.raft.peers"));
        boolean bootstrap = environment.getProperty("bootfs.raft.bootstrap", Boolean.class, false);
        boolean allowInsecure = environment.getProperty("bootfs.raft.allow-insecure-network", Boolean.class, false);
        return restore(new RaftMetadataStore(root, nodeId, groupId, peers, bootstrap, allowInsecure), environment, root);
    }

    /** New roots use dynamic; initialized roots keep their authority and consensus history. */
    static String resolveMode(Path root, String configured) {
        if (!"auto".equals(configured)) return configured;
        boolean dynamic = Files.exists(root.resolve("dynamic/node.json")) || hasEntries(root.resolve("dynamic"));
        boolean raft = Files.exists(root.resolve("raft-identity.json")) || Files.exists(root.resolve("raft-initialized"))
                || hasEntries(root.resolve("raft"));
        boolean embedded = Files.exists(root.resolve("metadata/CURRENT"));
        boolean metadataPresent = hasEntries(root.resolve("metadata"));
        if (dynamic && (raft || metadataPresent)) {
            throw new MetadataException("Conflicting dynamic and legacy metadata states at " + root + "; explicit recovery is required");
        }
        if (dynamic) return "legacy-dynamic";
        if (raft) return "raft";
        if (embedded) return "dynamic";
        if (metadataPresent) {
            throw new MetadataException("Metadata directory exists without CURRENT at " + root + "; recover the original storage before starting");
        }
        return "dynamic";
    }

    private static boolean hasEntries(Path directory) {
        if (!Files.exists(directory)) return false;
        if (!Files.isDirectory(directory)) throw new MetadataException("Expected a storage directory at " + directory);
        try (var entries = Files.list(directory)) { return entries.findAny().isPresent(); }
        catch (java.io.IOException failure) { throw new MetadataException("Cannot inspect existing storage at " + directory, failure); }
    }

    private MetadataStore restore(MetadataStore store, Environment environment, Path root) {
        String plan = environment.getProperty("bootfs.restore.plan", "");
        try {
            if (plan.isBlank()) {
                if (java.nio.file.Files.exists(root.getParent().resolve("restore-plan.json")) && store.get("/recovery/plan") == null) {
                    throw new MetadataException("Restored seed requires explicit bootfs.restore.plan on its first start");
                }
            } else cc.ginpika.bootfs.kernel.backup.RestorePlan.apply(store, Path.of(plan));
            return store;
        } catch (Exception error) {
            store.close();
            throw new MetadataException("Cannot initialize restored metadata", error);
        }
    }

    /** Explicit static initial membership: node-a=host:port,node-b=host:port. */
    static Map<String, String> parsePeers(String configured) {
        Map<String, String> peers = new TreeMap<>();
        for (String item : configured.split(",")) {
            String[] parts = item.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Raft peers must use nodeId=host:port");
            }
            String id = parts[0].trim(), address = parts[1].trim();
            int separator = address.lastIndexOf(':');
            if (separator <= 0 || separator == address.length() - 1) throw new IllegalArgumentException("Raft peer has no host:port");
            int port = Integer.parseInt(address.substring(separator + 1));
            if (port < 1 || port > 65535 || peers.putIfAbsent(id, address) != null) {
                throw new IllegalArgumentException("Invalid port or duplicate Raft peer ID");
            }
        }
        if (new HashSet<>(peers.values()).size() != peers.size()) throw new IllegalArgumentException("Raft peers must have unique addresses");
        return peers;
    }
}
