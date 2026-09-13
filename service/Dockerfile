# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
# Build
COPY src ./src
RUN mvn -q -e -DskipTests clean package

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl is used by the compose healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/rag-mcp-server-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
