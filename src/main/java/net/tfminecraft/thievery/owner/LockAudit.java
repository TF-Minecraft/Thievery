package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Logger;

/** Staff lock commands are recorded in the server log and in {@code plugins/Thievery/logs/locks-audit.log}. */
public final class LockAudit {

    private LockAudit() {}

    public static void record(Path file, Logger log, String actor, String action) {
        String line = Instant.now() + " " + actor + " " + action;
        log.info("[Lock audit] " + actor + " " + action);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warning("Could not write the lock audit log: " + e.getMessage());
        }
    }
}
