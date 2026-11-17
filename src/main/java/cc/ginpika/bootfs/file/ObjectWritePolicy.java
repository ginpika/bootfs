package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.settings.NodeSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Set;

/** Effective node write durability policy, bucket, and timeout configuration. */
@Component
public class ObjectWritePolicy {
    private final TfsConfig tfsConfig;

    @Autowired(required = false)
    private NodeSettings settings;

    @Value("${s3.bucket:bootfs}")
    private String s3Bucket = "bootfs";

    @Value("${bootfs.objects.write-policy:single-local}")
    private String writePolicy = "single-local";

    @Value("${bootfs.objects.required-copies:0}")
    private int requiredCopies;

    @Value("${bootfs.objects.prepare-timeout-ms:30000}")
    private int prepareTimeoutMs = 30000;

    public ObjectWritePolicy(TfsConfig tfsConfig) {
        this.tfsConfig = tfsConfig;
    }

    public record TaskPolicy(int requiredCopies, int prepareTimeoutMs, int copies) {}

    @PostConstruct
    public void validateWritePolicy() {
        if (!Set.of("single-local", "replicated").contains(writePolicy) || requiredCopies < 0 || prepareTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid object write policy, copies or timeout");
        }
        if ("replicated".equals(policy()) && configuredCopies() < 2) {
            throw new IllegalArgumentException("replicated requires at least two copies including the local node");
        }
    }

    public String bucket() {
        return settings == null ? s3Bucket : settings.current().string("s3Bucket");
    }

    public String policy() {
        return settings == null ? writePolicy : settings.current().string("writePolicy");
    }

    public int timeout() {
        return settings == null ? prepareTimeoutMs : settings.current().integer("prepareTimeoutMs");
    }

    public int configuredCopies() {
        if (settings != null) return settings.current().effectiveCopies();
        return requiredCopies > 0 ? requiredCopies : defaultCopies();
    }

    public int defaultCopies() {
        return tfsConfig.getCopies() == null ? 1 : tfsConfig.getCopies();
    }

    public TaskPolicy captureTaskPolicy() {
        var taskSettings = settings == null ? null : settings.current();
        int required = taskSettings == null
                ? ("replicated".equals(policy()) ? configuredCopies() : 1)
                : ("replicated".equals(taskSettings.string("writePolicy")) ? taskSettings.effectiveCopies() : 1);
        int prepareTimeout = taskSettings == null ? timeout() : taskSettings.integer("prepareTimeoutMs");
        int copies = taskSettings == null ? defaultCopies() : taskSettings.integer("copies");
        return new TaskPolicy(required, prepareTimeout, copies);
    }
}
