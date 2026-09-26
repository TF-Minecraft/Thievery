package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoorLockInteractionTest {

    @Test
    void closedBlocksStayLocked() {
        assertFalse(DoorLockInteraction.allowsToggleWithoutKey(false, false));
        assertFalse(DoorLockInteraction.allowsToggleWithoutKey(false, true));
    }

    @Test
    void openDoorsCanStillBeClosed() {
        assertTrue(DoorLockInteraction.allowsToggleWithoutKey(true, false));
    }

    @Test
    void openTrapdoorsStayLocked() {
        assertFalse(DoorLockInteraction.allowsToggleWithoutKey(true, true));
    }
}
