package org.alfresco.contentlake.nuxeo.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.alfresco.contentlake")
public class NuxeoBatchIngesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(NuxeoBatchIngesterApplication.class, args);
    }
}
