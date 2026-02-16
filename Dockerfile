# ─────────────────────────────────────────────────────────────────────────────
# AxiomBase — Logic Server for Agentic AI
# Multi-stage build: Gradle build → minimal JRE runtime
# ─────────────────────────────────────────────────────────────────────────────

# ── Build Stage ──────────────────────────────────────────────────────────────
FROM gradle:8-jdk17 AS build
WORKDIR /home/gradle/src

# Cache Gradle dependencies (this layer only rebuilds when gradle files change)
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle ./gradle
COPY axiombase-core/build.gradle.kts axiombase-core/
COPY axiombase-server/build.gradle.kts axiombase-server/
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY . .
RUN ./gradlew :axiombase-server:installDist --no-daemon

# ── Runtime Stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="AxiomBase"
LABEL org.opencontainers.image.description="Logic server for Agentic AI — deterministic reasoning, truth maintenance, and agent memory"
LABEL org.opencontainers.image.url="https://github.com/essaouirallc/logic-server"
LABEL org.opencontainers.image.vendor="Essaouira LLC"

# ── Defaults (overridden by .env / docker-compose environment) ───────────────
ENV PORT=9300 \
    HOST=0.0.0.0 \
    STORAGE_DIR=/data \
    EXTRACTION_ENABLED=true

EXPOSE ${PORT} 9443

# Install curl for healthcheck + tini for proper signal handling
RUN apk add --no-cache curl tini

# Non-root user for security
RUN addgroup -S axiombase && adduser -S axiombase -G axiombase

# App and data directories
RUN mkdir -p /app /data && \
    chown -R axiombase:axiombase /app /data

VOLUME ["/data"]

USER axiombase

COPY --from=build --chown=axiombase:axiombase \
    /home/gradle/src/axiombase-server/build/install/axiombase-server /app

WORKDIR /app

# Healthcheck — used by Docker and orchestrators
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -sf http://localhost:${PORT}/health || exit 1

# tini ensures proper PID 1 behavior (signal forwarding, zombie reaping)
ENTRYPOINT ["tini", "--"]
CMD ["/app/bin/axiombase-server"]
