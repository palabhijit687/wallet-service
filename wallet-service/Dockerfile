# --- Build stage: compile + package the fat jar ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies first (only re-download when pom.xml changes).
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests clean package \
    && cp target/wallet-service-*.jar /build/app.jar

# --- Runtime stage: slim JRE, non-root, healthcheck ---
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# curl is used by the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    # Non-root user to run the app.
    && groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /build/app.jar /app/app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

# Liveness/readiness via the actuator health endpoint (open, no auth).
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
