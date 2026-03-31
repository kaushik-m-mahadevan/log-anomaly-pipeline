# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Copy all source and POM files
COPY pom.xml .
COPY common/pom.xml common/
COPY common/src common/src
COPY alert-service/pom.xml alert-service/
COPY alert-service/src alert-service/src
# Build common first, then alert-service
RUN cd common && mvn clean install -DskipTests -q && \
    cd ../alert-service && mvn clean package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/alert-service/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
