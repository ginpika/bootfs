package com.nihilx.tfs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "etcd")
@Data
public class EtcdConfig {
    private List<String> endpoints;
}
