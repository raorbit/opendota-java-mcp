# syntax=docker/dockerfile:1
#
# Shared OpenDota sidecar — a long-lived HTTP service on :31337. Run it via the
# repo-root docker-compose.yml. Build context is the sidecar/ subtree: the module
# compiles standalone (its client-class copies live under src/), and drift against the
# root is enforced in CI, so the image build skips tests.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
# Fixed UID/GID + a pre-created, owned /data BEFORE the volume is declared: on first mount
# of an empty named volume, Docker copies this ownership onto it, so the non-root user can
# write the SQLite L2 cache (l2-cache.db + -wal/-shm). curl already ships in this base image.
RUN groupadd -g 10001 app && useradd -u 10001 -g app -m app \
    && mkdir -p /data && chown 10001:10001 /data
WORKDIR /app
# The plain jar's manifest carries `Class-Path: lib/sqlite-jdbc-*.jar`, resolved relative to
# the jar — so the jar and its lib/ MUST be siblings in this directory.
COPY --from=builder /build/target/opendota-sidecar-1.1.0.jar ./
COPY --from=builder /build/target/lib/ ./lib/
USER 10001
VOLUME /data
EXPOSE 31337
# Probe loopback inside the container (works once OPENDOTA_SIDECAR_BIND=0.0.0.0, which also
# binds loopback). /health stays open even when a token gates /api and /stats.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD curl -fsS "http://127.0.0.1:${OPENDOTA_SIDECAR_PORT:-31337}/health" || exit 1
ENTRYPOINT ["java", "-jar", "opendota-sidecar-1.1.0.jar"]
