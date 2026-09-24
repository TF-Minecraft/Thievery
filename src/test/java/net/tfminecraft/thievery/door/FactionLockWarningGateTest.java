package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FactionLockWarningGateTest {

    @Test
    void sendsTheFirstFactionWarning() {
        assertTrue(FactionLockWarningGate.shouldSend(false, null, 1_000L));
    }

    @Test
    void staysQuietAfterThePlayerDismissesIt() {
        assertFalse(FactionLockWarningGate.shouldSend(true, null, 1_000L));
        assertFalse(FactionLockWarningGate.shouldSend(true, 0L, FactionLockWarningGate.MIN_GAP_MS + 1));
    }

    @Test
    void repeatsUntilDismissedOnceTheGapHasPassed() {
        long sentAt = 5_000L;
        assertFalse(FactionLockWarningGate.shouldSend(false, sentAt, sentAt + FactionLockWarningGate.MIN_GAP_MS - 1));
        assertTrue(FactionLockWarningGate.shouldSend(false, sentAt, sentAt + FactionLockWarningGate.MIN_GAP_MS));
    }
}
