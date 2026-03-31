# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Copy all source and POM files
COPY pom.xml .
COPY common/pom.xml common/
COPY common/src common/src
COPY anomaly-detector-service/pom.xml anomaly-detector-service/
COPY anomaly-detector-service/src anomaly-detector-service/src
# Build common first, then anomaly-detector-service
RUN cd common && mvn clean install -DskipTests -q && \
    cd ../anomaly-detector-service && mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/anomaly-detector-service/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
