package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerInteractCooldownTest {
    @Test void rapidDuplicateInteractionsAreSuppressedPerPlayerUntilExpiryOrDisconnect() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            assertTrue(PlayerInteractCooldown.tryAcquire(first));
            assertFalse(PlayerInteractCooldown.tryAcquire(first));
            // Interactions without a player identity must not clear another player's cooldown.
            assertTrue(PlayerInteractCooldown.tryAcquire(null));
            PlayerInteractCooldown.clear(null);
            assertFalse(PlayerInteractCooldown.tryAcquire(first));
            assertTrue(PlayerInteractCooldown.tryAcquire(second));
            Thread.sleep(220); // Exercise the public wall-clock expiry rather than changing private timestamps.
            assertTrue(PlayerInteractCooldown.tryAcquire(first));
            assertFalse(PlayerInteractCooldown.tryAcquire(first));
            PlayerInteractCooldown.clear(first);
            assertTrue(PlayerInteractCooldown.tryAcquire(first));
        } finally {
            PlayerInteractCooldown.clear(first);
            PlayerInteractCooldown.clear(second);
        }
    }
}
