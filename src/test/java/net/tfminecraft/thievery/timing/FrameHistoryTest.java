package net.tfminecraft.thievery.timing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FrameHistoryTest {

    @Test
    void nothingIsReadyBeforeTheFirstFrame() {
        FrameHistory frames = new FrameHistory();
        assertEquals(-1, frames.framesBack(Long.MAX_VALUE));
        assertTrue(Double.isNaN(frames.valueAt(Long.MAX_VALUE)));
        frames.record(1000, 4);
        assertEquals(-1, frames.framesBack(999));
        assertTrue(Double.isNaN(frames.valueAt(999)));
        assertEquals(0, frames.framesBack(1000));
        assertEquals(4, frames.valueAt(5000));
    }

    @Test
    void aClickIsJudgedOnTheLatestFrameSentAtOrBeforeItsMoment() {
        FrameHistory frames = new FrameHistory();
        for (int tick = 0; tick < 10; tick++) {
            frames.record(tick * 50L, tick);
        }
        assertEquals(0, frames.framesBack(450));
        assertEquals(0, frames.framesBack(499));
        assertEquals(1, frames.framesBack(449));
        assertEquals(1, frames.framesBack(400));
        assertEquals(6, frames.framesBack(150));
        assertEquals(3, frames.value(6));
        assertEquals(9, frames.framesBack(0));
        assertEquals(0, frames.value(9));
    }

    @Test
    void theRingGlidesBetweenFramesAndTpsDipsStretchTheGap() {
        FrameHistory frames = new FrameHistory();
        frames.record(0, 0);
        frames.record(50, 1);
        // A slow tick: the next frame came 200 ms later.
        frames.record(250, 2);
        assertEquals(0.5, frames.valueAt(25), 1e-9);
        assertEquals(1.0, frames.valueAt(50), 1e-9);
        assertEquals(1.25, frames.valueAt(100), 1e-9);
        assertEquals(2.0, frames.valueAt(250), 1e-9);
        assertEquals(2.0, frames.valueAt(400), 1e-9);
    }

    @Test
    void framesThatLeftTheHistoryAreStoodInForByTheOldestKept() {
        FrameHistory frames = new FrameHistory(4);
        for (int tick = 0; tick < 10; tick++) {
            frames.record(tick * 50L, tick);
        }
        // Frames 6 to 9 are kept; a moment at frame 2 lands on frame 6, the oldest kept.
        assertEquals(3, frames.framesBack(100));
        assertEquals(6, frames.valueAt(100));
        assertEquals(6, frames.valueAt(300));
        // It is still after the first frame ever sent, so it is not "before the bar".
        assertEquals(-1, frames.framesBack(-1));
    }

    @Test
    void clearingStartsAFreshPassAndATinyCapacityStillHoldsTwoFrames() {
        FrameHistory frames = new FrameHistory(0);
        frames.record(0, 0);
        frames.record(50, 1);
        frames.record(100, 2);
        assertEquals(1.5, frames.valueAt(75), 1e-9);
        frames.clear();
        assertEquals(-1, frames.framesBack(100));
        frames.record(200, 0);
        assertEquals(-1, frames.framesBack(150));
        assertEquals(0, frames.valueAt(250));
    }
}
