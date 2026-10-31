package cc.ginpika.bootfs.kernel.backup;

import java.nio.file.Path;
import java.util.*;

/** Runs before Spring: no HTTP listener, cleanup worker or automatic catalog import. */
public final class BackupCommand {
    private BackupCommand() {}
    public static boolean run(String[] args) throws Exception {
        if (Arrays.stream(args).noneMatch(arg -> arg.startsWith("--bootfs-maintenance="))) return false;
        Map<String,List<String>> options = new HashMap<>();
        for (String arg : args) {
            String[] pair = arg.split("=", 2);
            if (!Set.of("--bootfs-maintenance", "--source", "--output", "--backup", "--node-id", "--endpoint", "--writes-stopped").contains(pair[0])) {
                throw new IllegalArgumentException("Unknown maintenance option: " + pair[0]);
            }
            options.computeIfAbsent(pair[0], key -> new ArrayList<>()).add(pair.length == 1 ? "true" : pair[1]);
        }
        switch (one(options, "--bootfs-maintenance")) {
            case "backup" -> KernelBackup.export(options.getOrDefault("--source", List.of()).stream().map(Path::of).toList(),
                    Path.of(one(options, "--output")), "true".equals(optional(options, "--writes-stopped", "false")));
            case "verify" -> {
                var manifest = KernelBackup.verify(Path.of(one(options, "--backup")));
                System.out.println("Verified backup at source revision " + manifest.getLongValue("sourceRevision") + "; files=" + manifest.getJSONArray("files").size());
            }
            case "restore" -> System.out.println("Prepared new seed; import this plan on every new cluster peer: " + KernelBackup.restore(
                    Path.of(one(options, "--backup")), Path.of(one(options, "--output")), one(options, "--node-id"), one(options, "--endpoint")));
            default -> throw new IllegalArgumentException("Maintenance command must be backup, verify or restore");
        }
        return true;
    }
    private static String optional(Map<String,List<String>> options, String key, String fallback) {
        return options.containsKey(key) ? one(options, key) : fallback;
    }
    private static String one(Map<String,List<String>> options, String key) {
        List<String> values = options.get(key);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) throw new IllegalArgumentException("Supply exactly one " + key);
        return values.get(0);
    }
}
