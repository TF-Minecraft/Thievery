package net.tfminecraft.thievery.timing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.thievery.cache.Parameters;

class LagCompensationTest {

    @TempDir Path temp;
    private final UUID id = UUID.randomUUID();
    private Player player;
    private boolean enabled;
    private long cap;
    private long inputDelay;
    private boolean debug;

    @BeforeEach
    void setUp() {
        enabled = Parameters.lagCompensationEnabled;
        cap = Parameters.lagMaxRewindMs;
        inputDelay = Parameters.lagClientInputDelayMs;
        debug = Parameters.lagDebugLog;
        Parameters.lagCompensationEnabled = true;
        Parameters.lagMaxRewindMs = 300;
        Parameters.lagClientInputDelayMs = 0;
        Parameters.lagDebugLog = false;
        LagTestSupport.reset();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Thief");
    }

    @AfterEach
    void tearDown() {
        Parameters.lagCompensationEnabled = enabled;
        Parameters.lagMaxRewindMs = cap;
        Parameters.lagClientInputDelayMs = inputDelay;
        Parameters.lagDebugLog = debug;
        LagTestSupport.reset();
    }

    @Test
    void theRealClockMovesForward() {
        long before = LagCompensation.now();
        assertTrue(LagCompensation.now() >= before);
    }

    @Test
    void zeroPingRewindsNothing() {
        assertEquals(new LagCompensation.Rewind(0, 0, 0, false), LagCompensation.rewind(player));
        when(player.getPing()).thenReturn(-1);
        assertEquals(new LagCompensation.Rewind(0, 0, 0, false), LagCompensation.rewind(player));
    }

    @Test
    void theRewindIsTheUsualPingUpToTheCapEvenAtOneSecond() {
        when(player.getPing()).thenReturn(150);
        assertEquals(new LagCompensation.Rewind(150, 150, 150, false), LagCompensation.rewind(player));
        when(player.getPing()).thenReturn(300);
        assertEquals(new LagCompensation.Rewind(300, 300, 300, false), LagCompensation.rewind(player));
        when(player.getPing()).thenReturn(1000);
        assertEquals(new LagCompensation.Rewind(1000, 1000, 300, true), LagCompensation.rewind(player));
        for (int second = 0; second < 30; second++) {
            PingSampler.sample(id, 1000);
        }
        assertEquals(new LagCompensation.Rewind(1000, 1000, 300, true), LagCompensation.rewind(player));
    }

    @Test
    void aSpikeOverTheUsualPingIsNotRewound() {
        for (int second = 0; second < 30; second++) {
            PingSampler.sample(id, 60);
        }
        when(player.getPing()).thenReturn(280);
        assertEquals(new LagCompensation.Rewind(280, 60, 60, false), LagCompensation.rewind(player));
    }

    @Test
    void theClientInputDelayIsAddedBeforeTheCap() {
        Parameters.lagClientInputDelayMs = 50;
        when(player.getPing()).thenReturn(200);
        assertEquals(new LagCompensation.Rewind(200, 200, 250, false), LagCompensation.rewind(player));
        when(player.getPing()).thenReturn(280);
        assertEquals(new LagCompensation.Rewind(280, 280, 300, true), LagCompensation.rewind(player));
    }

    @Test
    void turnedOffNothingIsRewound() {
        Parameters.lagCompensationEnabled = false;
        when(player.getPing()).thenReturn(200);
        assertEquals(new LagCompensation.Rewind(200, 200, 0, false), LagCompensation.rewind(player));
    }

    @Test
    void theDebugLogWritesOnlyWhenOnAndConfigured() throws Exception {
        Path file = temp.resolve("logs").resolve("minigame-timing.log");
        LagCompensation.Rewind rewind = new LagCompensation.Rewind(320, 310, 300, true);
        Parameters.lagDebugLog = true;
        LagCompensation.log(player, "door", rewind, 6, "SUCCESS", "FAIL");
        assertFalse(Files.exists(file));

        LagCompensation.configureLog(file, Logger.getAnonymousLogger());
        Parameters.lagDebugLog = false;
        LagCompensation.log(player, "door", rewind, 6, "SUCCESS", "FAIL");
        assertFalse(Files.exists(file));

        Parameters.lagDebugLog = true;
        LagCompensation.log(player, "door", rewind, 6, "SUCCESS", "FAIL");
        LagCompensation.log(player, "ring", rewind, 5.5, "HIT", "LATE");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith(" player=Thief game=door ping=320 estimate=310 rewind=300 framesBack=6.0"
                + " result=SUCCESS unrewound=FAIL capped=true"), lines.get(0));
        assertTrue(lines.get(1).endsWith("game=ring ping=320 estimate=310 rewind=300 framesBack=5.5 result=HIT"
                + " unrewound=LATE capped=true"), lines.get(1));
    }

    @Test
    void aLogThatCannotBeWrittenIsReportedNotThrown() throws Exception {
        Path blocker = temp.resolve("blocker");
        Files.writeString(blocker, "a file where the logs folder should be");
        Logger log = mock(Logger.class);
        LagCompensation.configureLog(blocker.resolve("minigame-timing.log"), log);
        Parameters.lagDebugLog = true;
        assertDoesNotThrow(() -> LagCompensation.log(player, "door", new LagCompensation.Rewind(0, 0, 0, false), 0,
                "FAIL", "FAIL"));
        verify(log).warning(startsWith("Could not write the minigame timing log: "));
    }
}
