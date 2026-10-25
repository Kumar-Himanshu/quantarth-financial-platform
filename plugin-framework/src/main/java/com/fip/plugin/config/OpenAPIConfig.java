package com.fip.plugin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI pluginFrameworkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Financial Intelligence Platform - Plugin Framework API")
                        .description("Registry, lifecycle coordination, and sandboxed execution engine for platform plugins.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("FIP Architecture Team")
                                .email("architecture@fip.internal"))
                        .license(new License().name("Apache 2.0")));
    }
}
