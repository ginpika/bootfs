package cc.ginpika.bootfs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BootFS {
    public static void main(String[] args) {
        SpringApplication.run(BootFS.class, args);
    }
}
