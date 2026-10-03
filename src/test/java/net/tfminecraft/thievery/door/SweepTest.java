package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class SweepTest {

    @Test
    void rollsKeepTheZoneAfterTheFirstThirdAndThePeriodInRange() {
        Random random = new Random(11);
        for (int i = 0; i < 500; i++) {
            Sweep sweep = Sweep.roll(random, 36, 52, 0.14, i % 2 == 0);
            assertTrue(sweep.periodTicks() >= 36 && sweep.periodTicks() <= 52);
            assertTrue(sweep.zoneFrom() >= Sweep.EARLIEST_ZONE - 1e-9);
            assertTrue(sweep.zoneTo() <= Sweep.LATEST_ZONE_END + 1e-9);
            assertEquals(0.14, sweep.zoneTo() - sweep.zoneFrom(), 1e-9);
            assertTrue(sweep.key() >= 1 && sweep.key() <= Sweep.KEYS);
            assertEquals(i % 2 == 0, sweep.clockwise());
        }
    }

    @Test
    void rollClampsOddInputs() {
        Random random = mock(Random.class);
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(1.0);
        Sweep tiny = Sweep.roll(random, 0, -3, 0.001, true);
        assertEquals(1, tiny.periodTicks());
        assertEquals(0.02, tiny.zoneTo() - tiny.zoneFrom(), 1e-9);
        assertEquals(1, tiny.key());
        Sweep wide = Sweep.roll(random, 40, 40, 0.9, true);
        assertEquals(0.25, wide.zoneTo() - wide.zoneFrom(), 1e-9);
        assertEquals(Sweep.LATEST_ZONE_END, wide.zoneTo(), 1e-9);
        verify(random, times(2)).nextInt(1);
        assertEquals(1, new Sweep(0, 0.4, 0.5, true, 2).periodTicks());
    }

    @Test
    void angleRunsClockwiseOrBackAndMapsBackToProgress() {
        Sweep clockwise = new Sweep(40, 0.4, 0.55, true, 1);
        assertEquals(0, clockwise.angleAt(0), 1e-9);
        assertEquals(90, clockwise.angleAt(0.25), 1e-9);
        assertEquals(0.25, clockwise.progressAt(90), 1e-9);
        assertEquals(0.75, clockwise.progressAt(-90), 1e-9);
        assertEquals(0.25, clockwise.progressAt(450), 1e-9);
        Sweep back = new Sweep(40, 0.4, 0.55, false, 1);
        assertEquals(0, back.angleAt(0), 1e-9);
        assertEquals(270, back.angleAt(0.25), 1e-9);
        assertEquals(0, back.angleAt(1), 1e-9);
        assertEquals(0.25, back.progressAt(270), 1e-9);
        assertEquals(0, back.progressAt(0), 1e-9);
        assertEquals(0.5, clockwise.progress(20), 1e-9);
        assertEquals(1, clockwise.progress(99), 1e-9);
        assertEquals(0, clockwise.progress(-5), 1e-9);
    }

    @Test
    void zoneIncludesItsStartButNotItsEnd() {
        Sweep sweep = new Sweep(40, 0.4, 0.55, true, 3);
        assertFalse(sweep.inZone(0.39));
        assertTrue(sweep.inZone(0.4));
        assertTrue(sweep.inZone(0.54));
        assertFalse(sweep.inZone(0.55));
        assertEquals(3, sweep.key());
    }

    @Test
    void pressesAreJudgedWhereThePickLookedAfterLag() {
        Sweep sweep = new Sweep(40, 0.4, 0.55, true, 2);
        assertEquals(Sweep.Result.EARLY, sweep.judge(15, 0));
        assertEquals(Sweep.Result.HIT, sweep.judge(16, 0));
        assertEquals(Sweep.Result.HIT, sweep.judge(21, 0));
        assertEquals(Sweep.Result.LATE, sweep.judge(22, 0));
        assertEquals(Sweep.Result.HIT, sweep.judge(24, 3));
        assertEquals(Sweep.Result.EARLY, sweep.judge(18, 4));
        assertEquals(Sweep.Result.LATE, sweep.judge(22, -5));
        assertEquals(Sweep.Result.EARLY, sweep.judge(5, 99));
        assertFalse(sweep.isOver(39));
        assertTrue(sweep.isOver(40));
    }
}
