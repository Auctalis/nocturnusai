# Build Stage
FROM gradle:8-jdk17 AS build
WORKDIR /home/gradle/src

# Cache Gradle dependencies
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle ./gradle
COPY axiombase-core/build.gradle.kts axiombase-core/
COPY axiombase-server/build.gradle.kts axiombase-server/

# Download dependencies (this layer will be cached unless gradle files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY . .
RUN ./gradlew :axiombase-server:installDist --no-daemon

# Run Stage
FROM eclipse-temurin:21-jre-alpine
EXPOSE 9300 9443

ENV PORT=9300
ENV HOST=0.0.0.0
ENV STORAGE_DIR=/data

# Install curl for healthcheck
RUN apk add --no-cache curl

# Create a non-root user
RUN addgroup -S logic && adduser -S logic -G logic

# Create app directory and set permissions
RUN mkdir -p /app /data && \
    chown -R logic:logic /app /data

VOLUME ["/data"]

USER logic

COPY --from=build --chown=logic:logic /home/gradle/src/axiombase-server/build/install/axiombase-server /app
WORKDIR /app

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl --fail http://localhost:$PORT/health || exit 1

CMD ["/app/bin/axiombase-server"]
