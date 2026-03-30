# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
# Cache dependencies before copying source (layer cache optimization)
WORKDIR /app/anomaly-detector-service
COPY pom.xml ../pom.xml
COPY anomaly-detector-service/pom.xml .
RUN mvn dependency:go-offline -q
COPY anomaly-detector-service/src ./src
RUN mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/anomaly-detector-service/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
