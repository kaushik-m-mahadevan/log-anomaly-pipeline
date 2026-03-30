# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
# Cache dependencies before copying source (layer cache optimization)
WORKDIR /app/alert-service
COPY pom.xml ../pom.xml
COPY alert-service/pom.xml .
RUN mvn dependency:go-offline -q
COPY alert-service/src ./src
RUN mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/alert-service/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
