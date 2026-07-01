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

    @Test
    void purgeKeepsOldFilesWhosePidSegmentIsNotNumeric(@TempDir Path dir) throws Exception {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        // Matches the "opendota-mcp-*.log" glob but the segment isn't a PID -> a hand-renamed/backup
        // file the app never wrote; must be kept even though it is well past the retention cutoff.
        Path backup = stampedLog(dir, "opendota-mcp-backup.log", cutoff.minus(10, ChronoUnit.DAYS));
        // Arabic-Indic digits (١٢٣): Character.isDigit would accept these as a "PID", but the
        // app only ever writes ASCII PIDs, so the ASCII-only guard must keep this file.
        Path unicodeDigits = stampedLog(dir, "opendota-mcp-١٢٣.log", cutoff.minus(10, ChronoUnit.DAYS));
        // A genuine old orphan alongside them -> still deleted, proving the guard is selective.
        Path oldOrphan = stampedLog(dir, "opendota-mcp-200.log", cutoff.minus(1, ChronoUnit.DAYS));

        int deleted = LogRetentionCleaner.purge(dir, "opendota-mcp-${PID}.log", "100", cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(oldOrphan).doesNotExist();
        assertThat(backup).exists();
        assertThat(unicodeDigits).exists();
    }

    private static Path stampedLog(Path dir, String name, Instant mtime) throws IOException {
        Path p = Files.writeString(dir.resolve(name), "x");
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }
}
