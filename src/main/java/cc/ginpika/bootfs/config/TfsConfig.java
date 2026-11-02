package cc.ginpika.bootfs.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@ConfigurationProperties(prefix = "tfs")
@Data
public class TfsConfig {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cc.ginpika.bootfs.settings.NodeSettings settings;
    public String getWebEntrypoint() { return settings == null ? webEntrypoint : settings.current().string("webEntrypoint"); }
    public Integer getCopies() { return settings == null ? copies : settings.current().integer("copies"); }
    private String rootPath;
    private String webEntrypoint;
    private String uniqueId;
    private Integer copies;
    private String ffmpegUrl;
    private String meiliSearchUrl;
    private String etcdUrl;
    private Integer thumbnailWidth = 480;
    private Integer thumbnailQuality = 80;

    public static final String EXE_SUFFIX =
            System.getProperty("os.name").toLowerCase().startsWith("win") ? ".exe" : "";

    public String getPathPrefix() {
        return Path.of(this.rootPath, "data").toString();
    }

    public String getConfig() {
        return Path.of(this.rootPath, "db.json").toString();
    }

    public String getFfmpegUrl() {
        if (StringUtils.isNotBlank(this.ffmpegUrl)) {
            return this.ffmpegUrl;
        }
        return Path.of(this.rootPath, "ffmpeg", "bin", "ffmpeg") + EXE_SUFFIX;
    }
}
