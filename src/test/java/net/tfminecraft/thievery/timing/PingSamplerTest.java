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

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        window = Parameters.lagPingWindowSeconds;
        Parameters.lagPingWindowSeconds = 30;
        LagTestSupport.reset();
    }

    @AfterEach
    void tearDown() {
        Parameters.lagPingWindowSeconds = window;
        LagTestSupport.reset();
        MockBukkit.unmock();
    }

    @Test
    void withoutSamplesTheEstimateIsTheCurrentPing() {
        UUID id = UUID.randomUUID();
        assertEquals(120, PingSampler.estimate(id, 120));
        assertFalse(PingSampler.tracks(id));
    }

    @Test
    void theMedianIgnoresSpikesAndAShortFakedBurst() {
        UUID id = UUID.randomUUID();
        for (int second = 0; second < 30; second++) {
            // A steady 80 ms with a burst of eight seconds at 900 ms.
            PingSampler.sample(id, second >= 10 && second < 18 ? 900 : 80);
        }
        assertEquals(80, PingSampler.estimate(id, 900));
        // A ping that drops below the usual one is used at once: the estimate never exceeds the current ping.
        assertEquals(40, PingSampler.estimate(id, 40));
    }

    @Test
    void aSustainedRiseMovesTheEstimateOnceItIsMostOfTheWindow() {
        UUID id = UUID.randomUUID();
        for (int second = 0; second < 30; second++) {
            PingSampler.sample(id, 50);
        }
        for (int second = 0; second < 14; second++) {
            PingSampler.sample(id, 250);
        }
        assertEquals(50, PingSampler.estimate(id, 250));
        PingSampler.sample(id, 250);
        PingSampler.sample(id, 250);
        assertEquals(250, PingSampler.estimate(id, 250));
    }

    @Test
    void anEvenCountUsesTheLowerMiddleSampleAndNegativePingsCountAsZero() {
        UUID id = UUID.randomUUID();
        PingSampler.sample(id, 100);
        PingSampler.sample(id, 200);
        assertEquals(100, PingSampler.estimate(id, 500));
        PingSampler.sample(id, -1);
        assertEquals(100, PingSampler.estimate(id, 500));
        PingSampler.sample(id, -1);
        assertEquals(0, PingSampler.estimate(id, 500));
    }

    @Test
    void aNewWindowSizeStartsTheHistoryAfresh() {
        UUID id = UUID.randomUUID();
        PingSampler.sample(id, 300);
        Parameters.lagPingWindowSeconds = 5;
        PingSampler.sample(id, 60);
        assertEquals(60, PingSampler.estimate(id, 500));
        Parameters.lagPingWindowSeconds = 0;
        PingSampler.sample(id, 70);
        assertEquals(70, PingSampler.estimate(id, 500));
    }

    @Test
    void theTaskSamplesEveryOnlinePlayerOnceASecondAndQuittingDropsTheirSamples() {
        PlayerMock other = server.addPlayer();
        PingSampler sampler = new PingSampler();
        sampler.start(MockBukkit.createMockPlugin());
        // Restarting keeps a single task.
        sampler.start(MockBukkit.createMockPlugin("Second"));
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS - 1);
        assertFalse(PingSampler.tracks(other.getUniqueId()));
        server.getScheduler().performOneTick();
        assertTrue(PingSampler.tracks(other.getUniqueId()));
        assertEquals(Math.max(0, Math.min(other.getPing(), 1000)), PingSampler.estimate(other.getUniqueId(), 1000));

        sampler.onQuit(new PlayerQuitEvent(other, net.kyori.adventure.text.Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(PingSampler.tracks(other.getUniqueId()));

        sampler.stop();
        server.getScheduler().performTicks(PingSampler.INTERVAL_TICKS * 2);
        assertFalse(PingSampler.tracks(other.getUniqueId()));
        sampler.stop();
    }
}
