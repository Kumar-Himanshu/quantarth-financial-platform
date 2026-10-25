# Multi-Stage Build: Compilation
FROM gradle:8.8-jdk21 AS builder
WORKDIR /app

COPY --chown=gradle:gradle . .
RUN gradle :api-gateway:bootJar -x test --no-daemon

# Multi-Stage Build: Production JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/api-gateway/build/libs/api-gateway-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
