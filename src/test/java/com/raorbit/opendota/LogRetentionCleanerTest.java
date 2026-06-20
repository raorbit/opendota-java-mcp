package com.raorbit.opendota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LogRetentionCleanerTest {

    @Test
    void purgeDeletesOldOrphansButKeepsRecentCurrentAndLegacyFiles(@TempDir Path dir) throws Exception {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        // Old, but it is THIS process's file -> must be kept despite age.
        Path current = stampedLog(dir, "opendota-mcp-100.log", cutoff.minus(10, ChronoUnit.DAYS));
        // Old orphan from another PID -> deleted.
        Path oldOrphan = stampedLog(dir, "opendota-mcp-200.log", cutoff.minus(1, ChronoUnit.DAYS));
        // Recent orphan -> kept (younger than the cutoff).
        Path recentOrphan = stampedLog(dir, "opendota-mcp-300.log", cutoff.plus(1, ChronoUnit.DAYS));
        // Legacy non-per-PID file -> kept (the glob requires the trailing '-<pid>').
        Path legacy = stampedLog(dir, "opendota-mcp.log", cutoff.minus(10, ChronoUnit.DAYS));
        // Unrelated file -> kept (doesn't match the glob at all).
        Path unrelated = stampedLog(dir, "other.log", cutoff.minus(10, ChronoUnit.DAYS));

        int deleted = LogRetentionCleaner.purge(dir, "opendota-mcp-${PID}.log", "100", cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(oldOrphan).doesNotExist();
        assertThat(current).exists();
        assertThat(recentOrphan).exists();
        assertThat(legacy).exists();
        assertThat(unrelated).exists();
    }

    private static Path stampedLog(Path dir, String name, Instant mtime) throws IOException {
        Path p = Files.writeString(dir.resolve(name), "x");
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }
}
