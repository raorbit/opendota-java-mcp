package com.raorbit.opendota;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Sweeps orphaned per-PID log files on startup. Each JVM logs to its own
 * {@code ~/.opendota-mcp/logs/opendota-mcp-<pid>.log} (the per-PID name avoids multi-process contention on one file),
 * and desktop MCP clients spawn a process per chat and SIGKILL it on close, so without cleanup the log
 * directory accumulates one orphaned file per session forever.
 *
 * <p>On startup this deletes matching files older than {@code opendota.log-retention-days} (default 7),
 * <strong>never</strong> the current process's file. It logs only via SLF4J (to the log file, never
 * stdout — which carries the MCP transport) and never throws out of startup.
 */
@Component
public class LogRetentionCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionCleaner.class);

    private final String logFileName;
    private final int retentionDays;

    public LogRetentionCleaner(
            @Value("${logging.file.name:}") String logFileName,
            @Value("${opendota.log-retention-days:7}") int retentionDays) {
        this.logFileName = logFileName;
        this.retentionDays = retentionDays;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (logFileName == null || logFileName.isBlank() || retentionDays < 0) {
            return;
        }
        try {
            Path configured = Path.of(logFileName);
            Path dir = configured.getParent();
            if (dir == null) {
                return;
            }
            String currentPid = String.valueOf(ProcessHandle.current().pid());
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = purge(dir, configured.getFileName().toString(), currentPid, cutoff);
            if (deleted > 0) {
                log.info("purged {} orphaned log file(s) older than {} days from {}", deleted, retentionDays, dir);
            }
        } catch (RuntimeException e) {
            // Best-effort housekeeping — a sweep failure must never block startup.
            log.debug("log retention sweep skipped", e);
        }
    }

    /**
     * Delete per-PID log files in {@code dir} older than {@code cutoff}, keeping the current PID's file
     * and any name that doesn't match the {@code <prefix>-<pid>.log} shape (e.g. a legacy
     * {@code opendota-mcp.log}). Returns the number deleted. Package-private + static so it's unit-testable
     * against a temp dir without booting Spring.
     */
    static int purge(Path dir, String templateFileName, String currentPid, Instant cutoff) {
        int dash = templateFileName.lastIndexOf('-');
        int dot = templateFileName.lastIndexOf('.');
        if (dash < 0 || dot <= dash) {
            return 0;   // unexpected name shape — do nothing rather than guess a glob
        }
        // e.g. "opendota-mcp-${PID}.log" / "opendota-mcp-12345.log" -> glob "opendota-mcp-*.log". The
        // trailing '-' keeps a non-per-PID "opendota-mcp.log" out of the match.
        String glob = templateFileName.substring(0, dash + 1) + "*" + templateFileName.substring(dot);
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                int d = name.lastIndexOf('-');
                int t = name.lastIndexOf('.');
                String pid = (d >= 0 && t > d) ? name.substring(d + 1, t) : "";
                if (pid.equals(currentPid)) {
                    continue;   // never delete our own live log
                }
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("could not evaluate/delete log file {}", p, e);
                }
            }
        } catch (IOException e) {
            log.debug("could not list log directory {}", dir, e);
        }
        return deleted;
    }
}
