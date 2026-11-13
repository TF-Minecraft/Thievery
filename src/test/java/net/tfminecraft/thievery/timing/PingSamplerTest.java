package net.tfminecraft.thievery.timing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.thievery.cache.Parameters;

class PingSamplerTest {

    private ServerMock server;
    private int window;
    private final PingSampler pings = new PingSampler();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        window = Parameters.lagPingWindowSeconds;
        Parameters.lagPingWindowSeconds = 30;
    }

    @AfterEach
    void tearDown() {
        Parameters.lagPingWindowSeconds = window;
        MockBukkit.unmock();
    }

    @Test
    void withoutSamplesTheEstimateIsTheCurrentPing() {
        UUID id = UUID.randomUUID();
        assertEquals(120, pings.estimate(id, 120));
        assertFalse(pings.tracks(id));
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
        for (int second = 0; second < 30; second++) {
            pings.sample(id, 50);
        }
        for (int second = 0; second < 14; second++) {
            pings.sample(id, 250);
        }
        assertEquals(50, pings.estimate(id, 250));
        pings.sample(id, 250);
        pings.sample(id, 250);
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
    void theLowestSampleIsKeptPerPlayerAndWarningsAreRateLimited() {
        UUID id = UUID.randomUUID();
        assertEquals(-1, pings.lowest(id));
        pings.sample(id, 90);
        pings.sample(id, 40);
        pings.sample(id, 300);
        assertEquals(40, pings.lowest(id));
        assertTrue(pings.mayWarn(id, 1_000, 600_000));
        assertFalse(pings.mayWarn(id, 600_999, 600_000));
        assertTrue(pings.mayWarn(id, 601_000, 600_000));
        assertTrue(pings.mayWarn(UUID.randomUUID(), 601_000, 600_000));
    }

    @Test
    void theTaskSamplesEveryOnlinePlayerOnceASecondAndQuittingDropsTheirSamples() {
        PlayerMock other = server.addPlayer();
        PingSampler sampler = new PingSampler();
        sampler.start(MockBukkit.createMockPlugin());
        // Restarting keeps a single task.
        sampler.start(MockBukkit.createMockPlugin("Second"));
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS - 1);
        assertFalse(sampler.tracks(other.getUniqueId()));
        server.getScheduler().performOneTick();
        assertTrue(sampler.tracks(other.getUniqueId()));
        assertEquals(Math.max(0, Math.min(other.getPing(), 1000)), sampler.estimate(other.getUniqueId(), 1000));

        assertTrue(sampler.mayWarn(other.getUniqueId(), 0, 600_000));
        sampler.onQuit(new PlayerQuitEvent(other, net.kyori.adventure.text.Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(sampler.tracks(other.getUniqueId()));

        // A warning time alone still counts as tracked until the player leaves or the sampler stops.
        assertTrue(sampler.mayWarn(other.getUniqueId(), 0, 600_000));
        assertTrue(sampler.tracks(other.getUniqueId()));
        sampler.stop();
        assertFalse(sampler.tracks(other.getUniqueId()));
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS * 2);
        assertFalse(sampler.tracks(other.getUniqueId()));
        sampler.stop();
    }
}
