package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockGuardTest {
    @TempDir Path temp;

    @Test
    void theGuardFreezesUntilLiftedAndLiftingRunsQueuedDeletionsThenClearsTheCacheThenGoesFull() throws Exception {
        LockGuard.enter("starting up");
        assertTrue(LockGuard.guarded());
        assertEquals(LockGuard.Mode.GUARD, LockGuard.mode());
        assertEquals("starting up", LockGuard.reason());
        Path doomed = Files.writeString(temp.resolve("doomed.json"), "{}");
        Path busy = temp.resolve("busy");
        Files.createDirectories(busy.resolve("child"));
        LockGuard.queueDeletion(doomed);
        LockGuard.queueDeletion(busy);
        LockGuard.queueDeletion(temp.resolve("already-gone.json"));
        assertEquals(List.of(doomed, busy, temp.resolve("already-gone.json")), LockGuard.pendingDeletions());
        Logger logger = mock(Logger.class);
        boolean[] cleared = {false};
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            LockGuard.lift(() -> {
                assertFalse(Files.exists(doomed), "deletions are applied before the cache is cleared");
                assertTrue(LockGuard.guarded(), "the mode turns FULL last");
                cleared[0] = true;
            });
        }
        assertTrue(cleared[0]);
        assertFalse(LockGuard.guarded());
        assertNull(LockGuard.reason());
        assertTrue(LockGuard.pendingDeletions().isEmpty());
        assertTrue(Files.exists(busy));
        verify(logger).warning(contains("Could not delete queued lock file"));
    }

    @Test
    void anExceptionOnAnOwnershipPathCancelsTheEvent() {
        Cancellable event = mock(Cancellable.class);
        Logger logger = mock(Logger.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            LockGuard.failClosed(event, "a test", () -> { });
            verify(event, never()).setCancelled(anyBoolean());
            LockGuard.failClosed(event, "a test", () -> { throw new IllegalStateException("boom"); });
            verify(event).setCancelled(true);
            verify(logger).log(eq(Level.SEVERE), contains("cancelled a test"), any(IllegalStateException.class));
        }
    }
}
