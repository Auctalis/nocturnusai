# ─────────────────────────────────────────────────────────────────────────────
# NocturnusAI — Logic Server for Agentic AI
# Multi-stage build: Gradle build → minimal JRE runtime
# ─────────────────────────────────────────────────────────────────────────────

# ── Build Stage ──────────────────────────────────────────────────────────────
FROM gradle:8-jdk17 AS build
WORKDIR /home/gradle/src

# Cache Gradle dependencies (this layer only rebuilds when gradle files change)
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle ./gradle
COPY nocturnusai-core/build.gradle.kts nocturnusai-core/
COPY nocturnusai-server/build.gradle.kts nocturnusai-server/
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY . .
RUN ./gradlew :nocturnusai-server:installDist --no-daemon

# ── Runtime Stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# OCI image metadata — used by ghcr.io, Dependabot, Trivy/Grype, and all major
# container scanners. `licenses` is the key one: without it, scanners flag the
# image as NOASSERTION and enterprise policy gates block the pull.
LABEL org.opencontainers.image.title="NocturnusAI"
LABEL org.opencontainers.image.description="Logic server for Agentic AI — deterministic reasoning, truth maintenance, and agent memory"
LABEL org.opencontainers.image.url="https://nocturnus.ai"
LABEL org.opencontainers.image.source="https://github.com/Auctalis/nocturnusai"
LABEL org.opencontainers.image.documentation="https://nocturnus.ai/docs"
LABEL org.opencontainers.image.vendor="Auctalis LLC"
LABEL org.opencontainers.image.licenses="BUSL-1.1"
# Build-arg — injected by the release workflow via --build-arg VERSION=${GITHUB_REF_NAME#v}.
# Falls back to `dev` for local builds so the LABEL is always present.
ARG VERSION=dev
LABEL org.opencontainers.image.version="${VERSION}"

# ── Defaults (overridden by .env / docker-compose environment) ───────────────
ENV PORT=9300 \
    HOST=0.0.0.0 \
    STORAGE_DIR=/data \
    EXTRACTION_ENABLED=true \
    LLM_BASE_URL=http://host.docker.internal:11434/v1

EXPOSE ${PORT} 9443

# Install curl for healthcheck + tini for proper signal handling
RUN apk add --no-cache curl tini

# Non-root user for security
RUN addgroup -S nocturnusai && adduser -S nocturnusai -G nocturnusai

# App and data directories
RUN mkdir -p /app /data /backups && \
    chown -R nocturnusai:nocturnusai /app /data /backups

VOLUME ["/data", "/backups"]

USER nocturnusai

COPY --from=build --chown=nocturnusai:nocturnusai \
    /home/gradle/src/nocturnusai-server/build/install/nocturnusai-server /app

WORKDIR /app

# Healthcheck — used by Docker and orchestrators
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -sf http://localhost:${PORT}/health || exit 1

# tini ensures proper PID 1 behavior (signal forwarding, zombie reaping)
ENTRYPOINT ["tini", "--"]
CMD ["/app/bin/nocturnusai-server"]
