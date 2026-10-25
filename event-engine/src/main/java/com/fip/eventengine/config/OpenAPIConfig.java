package com.fip.eventengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI eventEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Financial Intelligence Platform - Event Engine API")
                        .description("Canonical financial event ingestion, event store persistence, audit trails, and stream distribution.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("FIP Architecture Team")
                                .email("architecture@fip.internal"))
                        .license(new License().name("Apache 2.0")));
    }
}
