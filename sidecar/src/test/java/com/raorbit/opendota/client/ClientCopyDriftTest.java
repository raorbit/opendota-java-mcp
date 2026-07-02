package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
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
 * {@code src/main/.../client/} (the single source of truth), then run
 * {@code scripts/sync-client-copies.sh} (or {@code .ps1}) to mirror it into the sidecar so this
 * guard passes again, and commit both.
 *
 * <p>When the root tree is not present beside the sidecar module (e.g. the sidecar built
 * in isolation from a distributed source tarball), the check is skipped <em>locally</em> —
 * but under CI (where the build always runs from the repo root) a missing root tree is a
 * HARD FAILURE, so the only guard over the key-holding copies can never pass by silently
 * skipping itself green.
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
        boolean rootPresent = rootDir != null && Files.isDirectory(rootDir.resolve(CLIENT_PKG));
        if (!rootPresent) {
            String where = rootDir == null ? "<no parent dir>" : rootDir.resolve(CLIENT_PKG).toString();
            // A silent skip under CI would let the sole guard over the key-holding copies pass
            // invisibly. Run the sidecar build from the repo root so the root tree is present.
            if (isCi()) {
                fail("drift check cannot run: canonical root sources not found at " + where
                        + " — run the sidecar build from the repo root");
            }
            assumeTrue(false, "canonical root sources not found at " + where
                    + "; skipping the drift check (set CI=true to make this fatal)");
        }

        int compared = 0;
        for (String name : COPIED) {
            Path canonical = rootDir.resolve(CLIENT_PKG + name);
            Path copy = moduleDir.resolve(CLIENT_PKG + name);
            assertThat(canonical).as("canonical root source %s must exist", name).exists();
            assertThat(copy).as("sidecar copy %s must exist", name).exists();
            assertThat(Files.readString(copy, StandardCharsets.UTF_8))
                    .as("sidecar copy of %s has drifted from the canonical root source at %s "
                            + "(re-copy the root file over the sidecar copy)", name, canonical)
                    .isEqualTo(Files.readString(canonical, StandardCharsets.UTF_8));
            compared++;
        }
        // Guard against a vacuous pass (e.g. an emptied COPIED array): every file must be checked.
        assertThat(compared).as("all copied client files must be compared").isEqualTo(COPIED.length);

        // Completeness guard beyond the hard-coded names: the sidecar client package must contain EXACTLY
        // the guarded copies — no more, no fewer. This catches a NEW security-relevant class copied into
        // the sidecar (the copy pattern already exists for these four) that was never added to COPIED and
        // would otherwise drift unguarded, as well as a guarded file deleted from the sidecar. We assert
        // against the SIDECAR side, which holds only copies; the ROOT package may legitimately carry
        // additional non-copied classes (e.g. ToolResults) so a root==sidecar equality would be wrong.
        Set<String> sidecarClientFiles = new TreeSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(moduleDir.resolve(CLIENT_PKG), "*.java")) {
            for (Path p : stream) {
                sidecarClientFiles.add(p.getFileName().toString());
            }
        }
        assertThat(sidecarClientFiles)
                .as("the sidecar client package must contain exactly the drift-guarded copies; a new .java "
                        + "file here must be added to COPIED (and mirrored from the root) so it is byte-compared too")
                .containsExactlyInAnyOrder(COPIED);
    }

    /** True when running under CI, where a missing root tree must fail rather than skip. */
    private static boolean isCi() {
        return Boolean.parseBoolean(System.getenv("CI"))
                || System.getenv("GITHUB_ACTIONS") != null
                || Boolean.getBoolean("ci");
    }
}
