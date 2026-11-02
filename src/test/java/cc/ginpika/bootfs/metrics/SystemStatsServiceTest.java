package cc.ginpika.bootfs.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemStatsServiceTest {
    @TempDir Path root;

    private void write(String name, String value) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    private Map<String, Object> stats(Map<String, String> environment) {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);
        when(os.getName()).thenReturn("Linux");
        when(os.getVersion()).thenReturn("6.1");
        when(os.getArch()).thenReturn("aarch64");
        when(os.getAvailableProcessors()).thenReturn(2);
        return new SystemStatsService(root, environment, os).buildStats();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> memory(Map<String, Object> stats) {
        return (Map<String, Object>) stats.get("memory");
    }

    @Test void dockerSeparatesProcessRssHostRamAndCgroupLimit() throws Exception {
        write(".dockerenv", "");
        write("proc/meminfo", "MemTotal: 8388608 kB\nMemAvailable: 3145728 kB\n");
        write("proc/self/status", "Name: java\nVmRSS: 524288 kB\nVmSize: 2097152 kB\n");
        write("proc/cpuinfo", "processor : 0\nmodel name : Test CPU\nprocessor : 1\nmodel name : Test CPU\n");
        write("proc/self/cgroup", "0::/\n");
        write("sys/fs/cgroup/memory.max", "1073741824\n");
        write("sys/fs/cgroup/memory.current", "629145600\n");
        Map<String, Object> stats = stats(Map.of("INVOCATION_ID", "service"));
        Map<String, Object> memory = memory(stats);
        assertEquals("docker", stats.get("runtime"));
        assertEquals("Test CPU", stats.get("cpuModel"));
        assertEquals(2, stats.get("logicalProcessors"));
        assertEquals(8589934592L, memory.get("hostTotalBytes"));
        assertEquals(5368709120L, memory.get("hostUsedBytes"));
        assertEquals(536870912L, memory.get("processRssBytes"));
        assertEquals(6.25, memory.get("processHostPercent"));
        assertEquals(1073741824L, memory.get("containerLimitBytes"));
        assertEquals(629145600L, memory.get("containerUsedBytes"));
        assertTrue(((Number) memory.get("heapUsedBytes")).longValue() > 0);
    }

    @Test void systemdUsesParentCgroupLimitWhenChildIsUnlimited() throws Exception {
        write("proc/self/cgroup", "0::/system.slice/bootfs.service\n");
        write("sys/fs/cgroup/system.slice/bootfs.service/memory.max", "max\n");
        write("sys/fs/cgroup/system.slice/bootfs.service/memory.current", "123456\n");
        write("sys/fs/cgroup/system.slice/memory.max", "2147483648\n");
        write("sys/fs/cgroup/memory.max", "4294967296\n");
        Map<String, Object> stats = stats(Map.of());
        assertEquals("systemd", stats.get("runtime"));
        assertEquals(2147483648L, memory(stats).get("containerLimitBytes"));
        assertEquals(123456L, memory(stats).get("containerUsedBytes"));
    }

    @Test void cgroupV1SupportsNestedMembershipAndUnlimitedSentinel() throws Exception {
        write("proc/self/cgroup", "4:cpu,cpuacct:/docker/test\n5:memory:/docker/test\n");
        write("sys/fs/cgroup/memory/docker/test/memory.limit_in_bytes", "9223372036854771712\n");
        write("sys/fs/cgroup/memory/docker/test/memory.usage_in_bytes", "2048\n");
        Map<String, Object> stats = stats(Map.of());
        assertEquals("docker", stats.get("runtime"));
        assertNull(memory(stats).get("containerLimitBytes"));
        assertEquals(2048L, memory(stats).get("containerUsedBytes"));
        write("sys/fs/cgroup/memory/docker/test/memory.limit_in_bytes", "4096\n");
        assertEquals(4096L, memory(stats(Map.of())).get("containerLimitBytes"));
    }

    @Test void unavailableOrMalformedMeasurementsAreNotReportedAsZero() throws Exception {
        write("proc/meminfo", "MemTotal: invalid kB\nMemAvailable: -5 kB\n");
        write("proc/self/status", "VmRSS: 999999999999999999 kB\n");
        Map<String, Object> stats = stats(Map.of());
        assertEquals("standalone", stats.get("runtime"));
        assertNull(stats.get("cpuModel"));
        assertNull(memory(stats).get("hostTotalBytes"));
        assertNull(memory(stats).get("hostAvailableBytes"));
        assertNull(memory(stats).get("processRssBytes"));
        assertNull(memory(stats).get("processHostPercent"));
    }

    @Test void detectsSystemdAndOtherContainersWithoutClaimingTheyAreDocker() {
        assertEquals("systemd", stats(Map.of("INVOCATION_ID", "test")).get("runtime"));
        assertEquals("container", stats(Map.of("container", "podman")).get("runtime"));
    }
}
