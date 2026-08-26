package org.hyland.filesystem.contentlake.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "org.hyland.contentlake",
        "org.hyland.filesystem.contentlake"
})
public class FilesystemBatchIngesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilesystemBatchIngesterApplication.class, args);
    }
}
