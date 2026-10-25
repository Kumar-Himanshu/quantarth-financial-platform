package com.fip.eventengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.fip")
@EnableScheduling
public class EventEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventEngineApplication.class, args);
    }
}
