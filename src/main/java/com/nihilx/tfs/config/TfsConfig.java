package com.nihilx.tfs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@ConfigurationProperties(prefix = "tfs")
@Data
public class TfsConfig {
    private String rootPath;
    private String pathPrefix;
    private String config;
    private String webEntrypoint;
    private String uniqueId;
    private Integer copies;
    private String ffmpegUrl;
    private String meiliSearchUrl;
    private String etcdUrl;

    public static final String EXE_SUFFIX =
            System.getProperty("os.name").toLowerCase().startsWith("win") ? ".exe" : "";

    public String getPathPrefix() {
        return Path.of(this.rootPath, "data").toString();
    }

    public String getConfig() {
        return Path.of(this.rootPath, "db.json").toString();
    }

    public String getFfmpegUrl() {
        return Path.of(this.rootPath, "ffmpeg", "bin", "ffmpeg") + EXE_SUFFIX;
    }
}
