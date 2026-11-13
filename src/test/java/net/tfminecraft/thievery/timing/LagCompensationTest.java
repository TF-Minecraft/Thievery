package net.tfminecraft.thievery.timing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
    private final PingSampler pings = new PingSampler();
    private final AtomicLong clock = new AtomicLong();
    private Player player;
    private Logger log;
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
        log = mock(Logger.class);
        LagCompensation.configure(pings, null, log);
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
        LagTestSupport.reset();
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
            pings.sample(id, 1000);
        }
        assertEquals(new LagCompensation.Rewind(1000, 1000, 300, true), LagCompensation.rewind(player));
    }

    @Test
    void aSpikeOverTheUsualPingIsNotRewound() {
        for (int second = 0; second < 30; second++) {
            pings.sample(id, 60);
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
    void usualPingsNearTheCapOrFarAboveTheLowestSampleAreFlagged() {
        // Nothing rewound: nothing gained, never flagged.
        assertFalse(LagCompensation.flagged(new LagCompensation.Rewind(900, 900, 0, false), 10));
        // At or near the cap (90% of 300 ms).
        assertTrue(LagCompensation.flagged(new LagCompensation.Rewind(270, 270, 270, false), -1));
        assertTrue(LagCompensation.flagged(new LagCompensation.Rewind(400, 400, 300, true), 380));
        assertFalse(LagCompensation.flagged(new LagCompensation.Rewind(269, 269, 269, false), -1));
        // More than twice the ten-minute baseline and over 100 ms above it.
        assertTrue(LagCompensation.flagged(new LagCompensation.Rewind(220, 220, 220, false), 60));
        assertFalse(LagCompensation.flagged(new LagCompensation.Rewind(150, 150, 150, false), 60));
        assertFalse(LagCompensation.flagged(new LagCompensation.Rewind(100, 100, 100, false), 40));
        assertFalse(LagCompensation.flagged(new LagCompensation.Rewind(220, 220, 220, false), 130));
    }

    @Test
    void aFlaggedClickIsWarnedAboutOncePerPlayerEveryTenMinutesEvenWithTheDebugLogOff() {
        LagTestSupport.useClock(clock::get);
        samples(30, 40);
        samples(30, 290);
        when(player.getPing()).thenReturn(290);
        LagCompensation.Rewind rewind = LagCompensation.rewind(player);
        @SuppressWarnings("unchecked")
        Supplier<String> unrewound = mock(Supplier.class);
        long first = clock.get();
        LagCompensation.judged(player, "door", rewind, 5, "SUCCESS", unrewound);
        clock.set(first + LagCompensation.WARN_INTERVAL_MS - 1);
        LagCompensation.judged(player, "door", rewind, 5, "SUCCESS", unrewound);
        verify(log, times(1)).warning("Lockpick timing: Thief was judged on a usual ping of 290 ms (lowest in 10"
                + " minutes 40 ms, rewind 290 ms). Possible ping inflation; turn on"
                + " lockpicking.lag-compensation.debug-log to review.");
        clock.set(first + LagCompensation.WARN_INTERVAL_MS);
        LagCompensation.judged(player, "door", rewind, 5, "SUCCESS", unrewound);
        verify(log, times(2)).warning(startsWith("Lockpick timing: Thief"));
        // The result without rewind is only worked out for the debug log.
        verifyNoInteractions(unrewound);
        // An unflagged click warns nothing.
        when(player.getPing()).thenReturn(30);
        clock.set(first + LagCompensation.WARN_INTERVAL_MS * 5);
        LagCompensation.judged(player, "door", LagCompensation.rewind(player), 0, "FAIL", unrewound);
        verify(log, times(2)).warning(anyString());
    }

    @Test
    void aPingHeldJustUnderTheCapLongerThanTheWindowIsStillFlagged() {
        // 260 ms is under the 270 ms near-cap line, and after a minute the window holds nothing else; the
        // ten-minute baseline still remembers the honest 40 ms.
        LagTestSupport.useClock(clock::get);
        samples(30, 40);
        samples(60, 260);
        when(player.getPing()).thenReturn(260);
        LagCompensation.Rewind rewind = LagCompensation.rewind(player);
        assertEquals(new LagCompensation.Rewind(260, 260, 260, false), rewind);
        assertEquals(260, pings.lowest(id));
        assertEquals(40, pings.baseline(id, clock.get()));
        assertTrue(LagCompensation.flagged(rewind, pings.baseline(id, clock.get())));
        LagCompensation.judged(player, "door", rewind, 5, "SUCCESS", () -> "FAIL");
        verify(log).warning(contains("usual ping of 260 ms (lowest in 10 minutes 40 ms, rewind 260 ms)"));
    }

    /** One sample a second, as the sampler's task takes them. */
    private void samples(int count, int ping) {
        for (int i = 0; i < count; i++) {
            clock.addAndGet(1000);
            pings.sample(id, ping);
        }
    }

    @Test
    void theDebugLogWritesOnlyWhenOnAndConfigured() throws Exception {
        Path file = temp.resolve("logs").resolve("minigame-timing.log");
        LagCompensation.Rewind rewind = new LagCompensation.Rewind(320, 310, 300, true);
        Parameters.lagDebugLog = true;
        LagCompensation.judged(player, "door", rewind, 6, "SUCCESS", () -> "FAIL");
        assertFalse(Files.exists(file));

        LagCompensation.configure(pings, file, log);
        assertTrue(Files.isDirectory(file.getParent()), "the folder is made once, when configured");
        Parameters.lagDebugLog = false;
        LagCompensation.judged(player, "door", rewind, 6, "SUCCESS", () -> "FAIL");
        assertFalse(Files.exists(file));

        Parameters.lagDebugLog = true;
        pings.sample(id, 250);
        LagCompensation.judged(player, "door", rewind, 6, "SUCCESS", () -> "FAIL");
        LagCompensation.judged(player, "ring", new LagCompensation.Rewind(120, 110, 110, false), 2.5, "HIT",
                () -> "LATE");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith(" player=Thief game=door ping=320 estimate=310 lowest=250 baseline=-1"
                + " rewind=300 framesBack=6.0 result=SUCCESS unrewound=FAIL capped=true flagged=true"), lines.get(0));
        assertTrue(lines.get(1).endsWith("game=ring ping=120 estimate=110 lowest=250 baseline=-1 rewind=110"
                + " framesBack=2.5 result=HIT unrewound=LATE capped=false flagged=false"), lines.get(1));
    }

    @Test
    void aLogThatCannotBeWrittenIsReportedOnceNotThrown() throws Exception {
        Path blocker = temp.resolve("blocker");
        Files.writeString(blocker, "a file where the logs folder should be");
        Parameters.lagDebugLog = true;
        // The folder cannot be made: warned once, at configure time.
        LagCompensation.configure(pings, blocker.resolve("logs").resolve("minigame-timing.log"), log);
        LagCompensation.Rewind none = new LagCompensation.Rewind(0, 0, 0, false);
        assertDoesNotThrow(() -> LagCompensation.judged(player, "door", none, 0, "FAIL", () -> "FAIL"));
        LagCompensation.judged(player, "door", none, 0, "FAIL", () -> "FAIL");
        verify(log, times(1)).warning(startsWith("Could not write the minigame timing log: "));

        // The folder exists but the file cannot be written: warned once more after a new configure.
        Path folder = temp.resolve("logs2");
        Files.createDirectories(folder.resolve("minigame-timing.log"));
        LagCompensation.configure(pings, folder.resolve("minigame-timing.log"), log);
        LagCompensation.judged(player, "door", none, 0, "FAIL", () -> "FAIL");
        LagCompensation.judged(player, "door", none, 0, "FAIL", () -> "FAIL");
        verify(log, times(2)).warning(startsWith("Could not write the minigame timing log: "));
    }
}
