package net.tfminecraft.thievery.timing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.thievery.cache.Parameters;

class PingSamplerTest {

    private static final long MINUTE = PingBaseline.MINUTE_MS;

    private ServerMock server;
    private int window;
    private final PingSampler pings = new PingSampler();
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        window = Parameters.lagPingWindowSeconds;
        Parameters.lagPingWindowSeconds = 30;
        LagTestSupport.useClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        Parameters.lagPingWindowSeconds = window;
        LagTestSupport.reset();
        MockBukkit.unmock();
    }

    /** One sample a second, as the task takes them. */
    private void samples(UUID id, int count, int ping) {
        for (int i = 0; i < count; i++) {
            clock.addAndGet(1000);
            pings.sample(id, ping);
        }
    }

    private void quit(PingSampler sampler, PlayerMock player) {
        sampler.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    @Test
    void withoutSamplesTheEstimateIsTheCurrentPing() {
        UUID id = UUID.randomUUID();
        assertEquals(120, pings.estimate(id, 120));
        assertFalse(pings.hasSamples(id));
        assertEquals(-1, pings.baseline(id, clock.get()));
    }

    @Test
    void theMedianIgnoresSpikesAndAShortFakedBurst() {
        UUID id = UUID.randomUUID();
        for (int second = 0; second < 30; second++) {
            // A steady 80 ms with a burst of eight seconds at 900 ms.
            pings.sample(id, second >= 10 && second < 18 ? 900 : 80);
        }
        assertEquals(80, pings.estimate(id, 900));
        // A ping that drops below the usual one is used at once: the estimate never exceeds the current ping.
        assertEquals(40, pings.estimate(id, 40));
    }

    @Test
    void aSustainedRiseMovesTheEstimateOnceItIsMostOfTheWindow() {
        UUID id = UUID.randomUUID();
        samples(id, 30, 50);
        samples(id, 14, 250);
        assertEquals(50, pings.estimate(id, 250));
        samples(id, 2, 250);
        assertEquals(250, pings.estimate(id, 250));
    }

    @Test
    void anEvenCountUsesTheLowerMiddleSampleAndNegativePingsCountAsZero() {
        UUID id = UUID.randomUUID();
        pings.sample(id, 100);
        pings.sample(id, 200);
        assertEquals(100, pings.estimate(id, 500));
        pings.sample(id, -1);
        assertEquals(100, pings.estimate(id, 500));
        pings.sample(id, -1);
        assertEquals(0, pings.estimate(id, 500));
    }

    @Test
    void aNewWindowSizeStartsTheHistoryAfresh() {
        UUID id = UUID.randomUUID();
        pings.sample(id, 300);
        Parameters.lagPingWindowSeconds = 5;
        pings.sample(id, 60);
        assertEquals(60, pings.estimate(id, 500));
        Parameters.lagPingWindowSeconds = 0;
        pings.sample(id, 70);
        assertEquals(70, pings.estimate(id, 500));
    }

    @Test
    void theWindowLowestIsKeptForTheDebugLog() {
        UUID id = UUID.randomUUID();
        assertEquals(-1, pings.lowest(id));
        pings.sample(id, 90);
        pings.sample(id, 40);
        pings.sample(id, 300);
        assertEquals(40, pings.lowest(id));
    }

    @Test
    void theBaselineOutlivesTheMedianWindow() {
        UUID id = UUID.randomUUID();
        samples(id, 30, 40);
        samples(id, 60, 260);
        // The window holds only 260 ms now; the ten-minute baseline still remembers 40 ms.
        assertEquals(260, pings.lowest(id));
        assertEquals(260, pings.estimate(id, 260));
        assertEquals(40, pings.baseline(id, clock.get()));
    }

    @Test
    void zeroReadingsAndTheFirstSamplesAfterJoiningNeverBecomeTheBaseline() {
        UUID id = UUID.randomUUID();
        // Before the first keep-alive answer Paper can report 0; the first samples after joining are skipped.
        samples(id, 3, 0);
        samples(id, PingSampler.BASELINE_SKIP_SAMPLES - 3, 30);
        assertFalse(pings.hasBaseline(id));
        samples(id, 10, 120);
        assertEquals(120, pings.baseline(id, clock.get()));
        // A 0 ms reading later on is ignored too.
        samples(id, 1, 0);
        assertEquals(120, pings.baseline(id, clock.get()));
    }

    @Test
    void theBaselineKeepsTenMinutesInOneMinuteBuckets() {
        UUID id = UUID.randomUUID();
        samples(id, PingSampler.BASELINE_SKIP_SAMPLES, 999);
        clock.set(5 * MINUTE);
        pings.sample(id, 70);
        pings.sample(id, 90);
        clock.set(6 * MINUTE + 10);
        pings.sample(id, 150);
        assertEquals(70, pings.baseline(id, clock.get()));
        // Nine minutes and more after minute 5, its bucket has left the ten minutes.
        assertEquals(70, pings.baseline(id, 14 * MINUTE + 59_999));
        assertEquals(150, pings.baseline(id, 15 * MINUTE));
        assertEquals(-1, pings.baseline(id, 16 * MINUTE));
        // The same bucket a whole cycle later starts afresh.
        clock.set(15 * MINUTE + 1);
        pings.sample(id, 200);
        assertEquals(150, pings.baseline(id, clock.get()));
        assertEquals(200, pings.baseline(id, 16 * MINUTE));
    }

    @Test
    void warningsAreRateLimitedPerPlayer() {
        UUID id = UUID.randomUUID();
        assertTrue(pings.mayWarn(id, 1_000));
        assertFalse(pings.mayWarn(id, 1_000 + PingSampler.REMEMBER_MS - 1));
        assertTrue(pings.mayWarn(id, 1_000 + PingSampler.REMEMBER_MS));
        assertTrue(pings.mayWarn(UUID.randomUUID(), 1_000));
    }

    @Test
    void aRelogKeepsTheBaselineAndTheWarningTimeUntilTheyAreTenMinutesOld() {
        PlayerMock thief = server.addPlayer();
        UUID id = thief.getUniqueId();
        samples(id, 20, 50);
        assertTrue(pings.mayWarn(id, clock.get()));
        long warnedAt = clock.get();

        quit(pings, thief);
        assertFalse(pings.hasSamples(id));
        assertTrue(pings.hasBaseline(id));
        assertTrue(pings.hasWarned(id));
        // Back within ten minutes: no second warning, and the old baseline still counts.
        clock.addAndGet(2 * MINUTE);
        pings.sampleOnline();
        assertEquals(50, pings.baseline(id, clock.get()));
        assertFalse(pings.mayWarn(id, clock.get()));

        // Gone for good: the sample pass prunes both once ten minutes old.
        clock.set(warnedAt + PingSampler.REMEMBER_MS - 1);
        pings.prune(clock.get());
        assertTrue(pings.hasWarned(id));
        clock.set(warnedAt + PingSampler.REMEMBER_MS);
        pings.sampleOnline();
        assertFalse(pings.hasWarned(id));
        assertFalse(pings.hasBaseline(id));
    }

    @Test
    void theTaskSamplesEveryOnlinePlayerOnceASecondAndStoppingForgetsEverything() {
        PlayerMock other = server.addPlayer();
        PingSampler sampler = new PingSampler();
        sampler.start(MockBukkit.createMockPlugin());
        // Restarting keeps a single task.
        sampler.start(MockBukkit.createMockPlugin("Second"));
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS - 1);
        assertFalse(sampler.hasSamples(other.getUniqueId()));
        server.getScheduler().performOneTick();
        assertTrue(sampler.hasSamples(other.getUniqueId()));
        assertEquals(Math.max(0, Math.min(other.getPing(), 1000)), sampler.estimate(other.getUniqueId(), 1000));

        sampler.mayWarn(other.getUniqueId(), clock.get());
        quit(sampler, other);
        assertFalse(sampler.hasSamples(other.getUniqueId()));
        assertTrue(sampler.hasWarned(other.getUniqueId()));
        sampler.stop();
        assertFalse(sampler.hasWarned(other.getUniqueId()));
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS * 2);
        assertFalse(sampler.hasSamples(other.getUniqueId()));
        sampler.stop();
    }
}
