# syntax=docker/dockerfile:1
#
# Main OpenDota MCP server (stdio transport). This image is for distribution / running
# without a host JDK. Because the server speaks MCP over stdin/stdout, run it as:
#
#   docker run -i --rm --init -e OPENDOTA_API_KEY opendota-mcp:1.1.0
#
# Never allocate a TTY (-t) — it would corrupt the JSON-RPC framing on stdout. The MCP
# client config uses "command": "docker" with these args plus an "env" block carrying
# the key value (see docs/mcp-registration.md).

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Persistent BuildKit cache for the local Maven repo — faster and more reliable than
# dependency:go-offline, which under-resolves the Spring AI BOM + plugin set here.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests
# Split the Spring Boot jar into layers with the (non-deprecated) tools jarmode.
# --launcher yields the exploded layout that JarLauncher runs.
RUN java -Djarmode=tools -jar target/opendota-mcp-1.1.0.jar \
        extract --layers --launcher --destination extracted

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -g 10001 app && useradd -u 10001 -g app -m app
WORKDIR /app
# Copy layers most- to least-stable so the dependency layers cache across rebuilds.
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./
USER 10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
