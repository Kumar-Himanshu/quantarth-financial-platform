# Multi-Stage Build: Compilation
FROM gradle:8.8-jdk21 AS builder
WORKDIR /app

COPY --chown=gradle:gradle . .
RUN gradle :ingestion-service:bootJar -x test --no-daemon

# Multi-Stage Build: Production JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /var/data/ingestion/staging && chown -R appuser:appgroup /var/data/ingestion
USER appuser

COPY --from=builder /app/ingestion-service/build/libs/ingestion-service-1.0.0.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
