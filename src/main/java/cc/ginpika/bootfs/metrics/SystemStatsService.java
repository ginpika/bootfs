package cc.ginpika.bootfs.metrics;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local measurements only. Unknown values stay null rather than masquerading as zero. */
@Service
public class SystemStatsService {
    private final Path root;
    private final Map<String, String> environment;
    private final OperatingSystemMXBean os;

    public SystemStatsService() {
        this(Path.of("/"), System.getenv(), ManagementFactory.getOperatingSystemMXBean());
    }

    SystemStatsService(Path root, Map<String, String> environment, OperatingSystemMXBean os) {
        this.root = root;
        this.environment = environment;
        this.os = os;
    }

    public Map<String, Object> buildStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        String cgroup = read("proc/self/cgroup");
        String container = environment.getOrDefault("container", "");
        String runtime;
        if (Files.exists(root.resolve(".dockerenv")) || cgroup.contains("docker") || "docker".equals(container)) {
            runtime = "docker";
        } else if (Files.exists(root.resolve("run/.containerenv")) || !container.isBlank()
                || cgroup.contains("kubepods") || cgroup.contains("containerd") || cgroup.contains("lxc")) {
            runtime = "container";
        } else if (environment.containsKey("INVOCATION_ID") || environment.containsKey("JOURNAL_STREAM")
                || cgroup.contains(".service")) {
            runtime = "systemd";
        } else {
            runtime = "standalone";
        }
        stats.put("runtime", runtime);
        stats.put("os", os.getName());
        stats.put("osVersion", os.getVersion());
        stats.put("architecture", os.getArch());
        stats.put("availableProcessors", os.getAvailableProcessors());
        stats.put("javaVersion", System.getProperty("java.version"));
        stats.put("jvmName", System.getProperty("java.vm.name"));
        stats.put("pid", ProcessHandle.current().pid());

        String cpuInfo = read("proc/cpuinfo");
        String cpuModel = null;
        int logicalProcessors = 0;
        for (String line : cpuInfo.split("\n")) {
            String[] pair = line.split(":", 2);
            if (pair.length != 2) continue;
            String key = pair[0].trim();
            if ("processor".equals(key)) logicalProcessors++;
            if (cpuModel == null && ("model name".equals(key) || "Hardware".equals(key)
                    || "Processor".equals(key))) cpuModel = pair[1].trim();
        }
        stats.put("cpuModel", cpuModel);
        stats.put("logicalProcessors", logicalProcessors > 0 ? logicalProcessors : null);

        // /proc/meminfo reports host RAM; the JVM OS bean may instead report the cgroup limit.
        Map<String, Long> memInfo = readKilobytes(read("proc/meminfo"));
        Long hostTotal = memInfo.get("MemTotal");
        Long hostAvailable = memInfo.get("MemAvailable");
        if (!os.getName().toLowerCase(Locale.ROOT).contains("linux")
                && os instanceof com.sun.management.OperatingSystemMXBean extended) {
            hostTotal = positive(extended.getTotalMemorySize());
            hostAvailable = nonNegative(extended.getFreeMemorySize());
        }
        Long rss = readKilobytes(read("proc/self/status")).get("VmRSS");
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("hostTotalBytes", hostTotal);
        memory.put("hostAvailableBytes", hostAvailable);
        memory.put("hostUsedBytes", hostTotal != null && hostAvailable != null
                ? Math.max(0, hostTotal - hostAvailable) : null);
        memory.put("processRssBytes", rss);
        memory.put("processHostPercent", hostTotal != null && hostTotal > 0 && rss != null
                ? Math.round(rss * 10000.0 / hostTotal) / 100.0 : null);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        memory.put("heapUsedBytes", heap.getUsed());
        memory.put("heapCommittedBytes", heap.getCommitted());
        memory.put("heapMaxBytes", positive(heap.getMax()));
        memory.put("nonHeapUsedBytes", nonHeap.getUsed());
        addCgroupMemory(memory, cgroup);
        stats.put("memory", memory);
        stats.put("sampledAt", System.currentTimeMillis());
        return stats;
    }

    private void addCgroupMemory(Map<String, Object> memory, String membership) {
        Long limit = null;
        Long usage = null;
        // Both cgroup namespaces (membership=/) and conventional v1/v2 mounts.
        for (String line : membership.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length != 3) continue;
            boolean v2 = parts[1].isEmpty();
            if (!v2 && !List.of(parts[1].split(",")).contains("memory")) continue;
            Path base = root.resolve(v2 ? "sys/fs/cgroup" : "sys/fs/cgroup/memory");
            Path directory = base.resolve(parts[2].replaceFirst("^/+", "")).normalize();
            if (!directory.startsWith(base)) continue;
            String limitName = v2 ? "memory.max" : "memory.limit_in_bytes";
            String usageName = v2 ? "memory.current" : "memory.usage_in_bytes";
            usage = readNumber(directory.resolve(usageName));
            // A parent cgroup can impose a tighter limit than the process's own cgroup.
            for (Path path = directory; path != null && path.startsWith(base); path = path.getParent()) {
                Long candidate = readNumber(path.resolve(limitName));
                // v1 represents unlimited memory by a value near Long.MAX_VALUE.
                if (candidate != null && candidate > 0 && candidate < (1L << 60)) {
                    limit = limit == null ? candidate : Math.min(limit, candidate);
                }
            }
            if (usage == null) usage = readNumber(base.resolve(usageName));
            break;
        }
        memory.put("containerLimitBytes", limit);
        memory.put("containerUsedBytes", usage);
    }

    private String read(String path) {
        return read(root.resolve(path));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private Long readNumber(Path path) {
        try {
            return nonNegative(Long.parseLong(read(path).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Long> readKilobytes(String content) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            String[] pair = line.split(":", 2);
            if (pair.length != 2) continue;
            String[] value = pair[1].trim().split("\\s+");
            if (value.length != 2 || !"kB".equals(value[1])) continue;
            try {
                Long bytes = nonNegative(Math.multiplyExact(Long.parseLong(value[0]), 1024));
                if (bytes != null) values.put(pair[0].trim(), bytes);
            } catch (NumberFormatException | ArithmeticException ignored) { }
        }
        return values;
    }

    private Long positive(long value) { return value > 0 ? value : null; }
    private Long nonNegative(long value) { return value >= 0 ? value : null; }
}
