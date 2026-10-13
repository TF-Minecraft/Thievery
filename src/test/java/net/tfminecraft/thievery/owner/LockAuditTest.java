package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockAuditTest {
    @TempDir Path temp;

    @Test void staffActionsAreAppendedToTheAuditLogAndTheServerLog() throws Exception {
        Logger log = mock(Logger.class);
        Path file = temp.resolve("logs/locks-audit.log");
        LockAudit.record(file, log, "Mod", "set the owner of a chest");
        LockAudit.record(file, log, "Mod", "listed the lock quarantine");
        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith(" Mod set the owner of a chest"));
        assertTrue(lines.get(1).endsWith(" Mod listed the lock quarantine"));
        verify(log).info("[Lock audit] Mod set the owner of a chest");
        Path blocked = temp.resolve("blocked");
        Files.writeString(blocked, "a file, not a directory");
        LockAudit.record(blocked.resolve("audit.log"), log, "Mod", "tried");
        verify(log).warning(contains("Could not write the lock audit log"));
    }
}
