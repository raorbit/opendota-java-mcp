package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the verbatim copies of the OpenDota client classes against silent drift.
 *
 * <p>{@code OpenDotaClient}, {@code OpenDotaException}, {@code RateLimiter} and
 * {@code TtlCache} are copied byte-for-byte from the root opendota-mcp module into
 * this standalone sidecar module (which deliberately cannot depend on the root jar).
 * These classes hold the security-critical logic — API-key redaction, the heap-bounding
 * response cap, the rate limiter — so a fix applied to only one copy would leave the
 * other (here, the key-holding sidecar) silently unpatched. This test fails the sidecar
 * build if any copy diverges from its canonical source.
 *
 * <p><strong>To sync:</strong> edit the canonical file under the root
 * {@code src/main/.../client/}, then copy it verbatim over the sidecar copy here so this
 * guard passes again.
 *
 * <p>When the root tree is not present beside the sidecar module (e.g. the sidecar built
 * in isolation from a distributed source tarball), the check is skipped rather than failed.
 */
class ClientCopyDriftTest {

    private static final String[] COPIED = {
            "OpenDotaClient.java", "OpenDotaException.java", "RateLimiter.java", "TtlCache.java"
    };
    private static final String CLIENT_PKG = "src/main/java/com/raorbit/opendota/client/";

    @Test
    void copiedClientClassesMatchTheCanonicalRootSources() throws IOException {
        Path moduleDir = Paths.get("").toAbsolutePath();   // the sidecar module dir when surefire runs here
        Path rootDir = moduleDir.getParent();              // the repo root, one level up
        assumeTrue(rootDir != null && Files.isDirectory(rootDir.resolve(CLIENT_PKG)),
                "canonical root sources not found beside the sidecar module; skipping the drift check");

        for (String name : COPIED) {
            Path canonical = rootDir.resolve(CLIENT_PKG + name);
            Path copy = moduleDir.resolve(CLIENT_PKG + name);
            assertThat(Files.readString(copy, StandardCharsets.UTF_8))
                    .as("sidecar copy of %s has drifted from the canonical root source at %s "
                            + "(re-copy the root file over the sidecar copy)", name, canonical)
                    .isEqualTo(Files.readString(canonical, StandardCharsets.UTF_8));
        }
    }
}
