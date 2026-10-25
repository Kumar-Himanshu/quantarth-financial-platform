# Multi-Stage Build: Compilation
FROM gradle:8.8-jdk21 AS builder
WORKDIR /app

COPY --chown=gradle:gradle . .
RUN gradle :plugin-framework:bootJar -x test --no-daemon

# Multi-Stage Build: Production JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/plugin-framework/build/libs/plugin-framework-1.0.0.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
