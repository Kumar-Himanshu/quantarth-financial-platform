package com.fip.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.net.URI;
import java.time.Instant;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final GatewayAuthenticationFilter gatewayAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(GatewayAuthenticationFilter gatewayAuthenticationFilter, ObjectMapper objectMapper) {
        this.gatewayAuthenticationFilter = gatewayAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ingestion/**").hasAnyAuthority("ROLE_USER", "ROLE_ANALYST", "ROLE_ADMIN", "fip:ingest")
                        .requestMatchers(HttpMethod.GET, "/api/v1/ingestion/**").hasAnyAuthority("ROLE_USER", "ROLE_ANALYST", "ROLE_ADMIN", "fip:read")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(gatewayAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.UNAUTHORIZED,
                                    "Full authentication is required to access this resource: " + authException.getMessage()
                            );
                            problem.setTitle("Unauthorized");
                            problem.setType(URI.create("https://fip.com/errors/unauthorized"));
                            problem.setProperty("timestamp", Instant.now());
                            objectMapper.writeValue(response.getOutputStream(), problem);
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.FORBIDDEN,
                                    "Access denied: Missing required platform roles or permissions."
                            );
                            problem.setTitle("Forbidden");
                            problem.setType(URI.create("https://fip.com/errors/forbidden"));
                            problem.setProperty("timestamp", Instant.now());
                            objectMapper.writeValue(response.getOutputStream(), problem);
                        })
                );

        return http.build();
    }
}
