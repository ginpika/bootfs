package cc.ginpika.bootfs.file.binding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Object namespace and S3 options loaded from the current node settings.
 */
@Configuration
@ConfigurationProperties(prefix = "s3")
@Data
@lombok.ToString(exclude={"accessKey","secretKey","settings"})
public class ObjectStorageProperties {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cc.ginpika.bootfs.settings.NodeSettings settings;
    public boolean isEnabled() { return settings == null ? enabled : settings.current().bool("s3Enabled"); }
    public String getAccessKey() { return settings == null ? accessKey : settings.current().string("s3AccessKey"); }
    public String getSecretKey() { return settings == null ? secretKey : settings.current().string("s3SecretKey"); }
    public String getBucket() { return settings == null ? bucket : settings.current().string("s3Bucket"); }
    public String[] credentials() {
        var snapshot = settings == null ? null : settings.current();
        return snapshot == null ? new String[]{accessKey,secretKey} : new String[]{snapshot.string("s3AccessKey"),snapshot.string("s3SecretKey")};
    }
    private boolean enabled = false;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private String bucket = "bootfs";

}
