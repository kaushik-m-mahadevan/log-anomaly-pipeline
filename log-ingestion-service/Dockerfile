# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
# Cache dependencies before copying source (layer cache optimization)
WORKDIR /app/log-ingestion-service
COPY pom.xml ../pom.xml
COPY log-ingestion-service/pom.xml .
RUN mvn dependency:go-offline -q
COPY log-ingestion-service/src ./src
RUN mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/log-ingestion-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
