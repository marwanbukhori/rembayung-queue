package dev.marwan.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QueueGateApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueGateApplication.class, args);
    }
}
