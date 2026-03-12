package com.nihilx.tfs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TFS {
    public static void main(String[] args) {
        SpringApplication.run(TFS.class, args);
    }
}
