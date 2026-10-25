package com.fip.plugin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fip")
public class PluginApplication {
    public static void main(String[] args) {
        SpringApplication.run(PluginApplication.class, args);
    }
}
