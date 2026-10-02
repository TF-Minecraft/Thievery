package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DialTest {

    private static Random rolls(int zone, int key, int step) {
        Random random = mock(Random.class);
        when(random.nextInt(5)).thenReturn(zone);
        when(random.nextInt(4)).thenReturn(key);
        when(random.nextInt(3)).thenReturn(step);
        return random;
    }

    @Test
    void ringRunsClockwiseRoundTheMiddleOfASixRowMenu() {
        assertEquals(12, Dial.STEPS);
        assertEquals(54, Dial.MENU_SIZE);
        assertEquals(3, Dial.slotOf(0));
        assertEquals(4, Dial.slotOf(1));
        assertEquals(24, Dial.slotOf(3));
        assertEquals(49, Dial.slotOf(6));
        assertEquals(28, Dial.slotOf(9));
        assertEquals(11, Dial.slotOf(11));
        assertEquals(Dial.slotOf(11), Dial.slotOf(-1));
        assertEquals(Dial.slotOf(0), Dial.slotOf(12));
        Set<Integer> ring = new HashSet<>();
        for (int step = 0; step < Dial.STEPS; step++) {
            ring.add(Dial.slotOf(step));
        }
        assertEquals(12, ring.size());
        assertArrayEquals(new int[] {20, 21, 22, 23}, Dial.keySlots());
        for (int slot : Dial.keySlots()) {
            assertFalse(ring.contains(slot));
        }
    }

    @Test
    void spinPicksTheZoneKeyAndSpeedWithinBounds() {
        Dial dial = new Dial();
        dial.spin(rolls(2, 3, 1), 2, 4, 2);
        assertEquals(6, dial.zoneStart());
        assertEquals(2, dial.zoneLength());
        assertEquals(4, dial.key());
        assertEquals(3, dial.ticksPerStep());
        assertEquals(0, dial.position());
        assertFalse(dial.inZone(5));
        assertTrue(dial.inZone(6));
        assertTrue(dial.inZone(7));
        assertFalse(dial.inZone(8));

        Random low = mock(Random.class);
        dial.spin(low, 0, -5, 0);
        assertEquals(4, dial.zoneStart());
        assertEquals(1, dial.zoneLength());
        assertEquals(1, dial.key());
        assertEquals(1, dial.ticksPerStep());
        verify(low).nextInt(1);
        dial.spin(rolls(4, 0, 0), 1, 1, 9);
        assertEquals(8, dial.zoneStart());
        assertEquals(4, dial.zoneLength());
        assertTrue(dial.inZone(11));
    }

    @Test
    void thePickGoesRoundOnceAndPressesAreJudgedWhereTheThiefSawIt() {
        Dial dial = new Dial();
        dial.spin(rolls(0, 1, 0), 2, 4, 2);
        assertEquals(4, dial.zoneStart());
        assertEquals(2, dial.key());
        assertEquals(Dial.Press.WRONG_KEY, dial.press(1, 0));
        assertEquals(Dial.Press.EARLY, dial.press(2, 0));
        for (int tick = 0; tick < 8; tick++) {
            assertTrue(dial.advance());
        }
        assertEquals(4, dial.position());
        assertEquals(Dial.Press.HIT, dial.press(2, 0));
        assertEquals(Dial.Press.EARLY, dial.press(2, 1));
        assertEquals(Dial.Press.HIT, dial.press(2, -3));
        for (int tick = 0; tick < 4; tick++) {
            dial.advance();
        }
        assertEquals(6, dial.position());
        assertEquals(Dial.Press.LATE, dial.press(2, 0));
        assertEquals(Dial.Press.HIT, dial.press(2, 2));
        assertEquals(Dial.Press.EARLY, dial.press(2, 99));
        for (int tick = 12; tick < 23; tick++) {
            assertTrue(dial.advance());
        }
        assertFalse(dial.advance());
        assertEquals(12, dial.position());
    }
}
