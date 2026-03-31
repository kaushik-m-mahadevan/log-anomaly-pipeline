# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Copy all source and POM files
COPY pom.xml .
COPY common/pom.xml common/
COPY common/src common/src
COPY log-ingestion-service/pom.xml log-ingestion-service/
COPY log-ingestion-service/src log-ingestion-service/src
# Build common first, then log-ingestion-service
RUN cd common && mvn clean install -DskipTests -q && \
    cd ../log-ingestion-service && mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/log-ingestion-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
