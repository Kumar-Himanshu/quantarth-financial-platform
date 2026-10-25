package com.fip.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI authServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Financial Intelligence Platform - Auth Service API")
                        .description("Authentication, RBAC, JWT issuance, and refresh token rotation service.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("FIP Architecture Team")
                                .email("architecture@fip.internal"))
                        .license(new License().name("Apache 2.0")));
    }
}
