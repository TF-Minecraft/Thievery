package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.DoorLockpick.ProximityAnchor;
import net.tfminecraft.thievery.door.LockPickManager.SelectResult;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.timing.LagCompensation;
import net.tfminecraft.thievery.timing.LagTestSupport;
import net.tfminecraft.thievery.utils.EvilRpPlays;

/**
 * The door and display bar judged on the frame the thief saw: the same as before at 0 ping, the same chances at
 * any ping up to the cap, and no click before the first frame.
 */
class LockPickLagCompensationTest {

    private static final int SEEDS = 200;

    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<EvilRpPlays> plays;
    private Player player;
    private final UUID id = UUID.randomUUID();
    private ProximityAnchor anchor;
    private PlayerData data;
    private final AtomicLong clock = new AtomicLong();
    private final Deque<long[]> sent = new ArrayDeque<>();
    private final ParameterSnapshot saved = new ParameterSnapshot();
    private LockPickManager manager;
    @TempDir Path temp;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        plays = mockStatic(EvilRpPlays.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Thief");
        when(player.isOnline()).thenReturn(true);
        // Each title is a frame: when it was sent and the subtitle bar it drew.
        doAnswer(invocation -> {
            String bar = invocation.getArgument(1);
            if (!bar.isEmpty()) {
                sent.add(frame(clock.get(), bar));
            }
            return null;
        }).when(player).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        anchor = mock(ProximityAnchor.class);
        when(anchor.isInRange(player)).thenReturn(true);
        data = mock(PlayerData.class);
        PlayerManager players = mock(PlayerManager.class);
        when(players.get(id)).thenReturn(data);
        plugin.when(Thievery::getPlayerManager).thenReturn(players);
        saved.take();
        // The live bar: 20 slots, 1-3 success slots, a Dexterity 10 cursor of about 2 slots a tick.
        Parameters.barLength = 20;
        Parameters.maxSuccessSlots = 3;
        Parameters.minBreakSlots = 3;
        Parameters.maxBreakSlots = 19;
        Parameters.baseBarSpeed = 2.5;
        Parameters.dexSpeedReductionPerLevel = 0.02;
        Parameters.minBarSpeed = 0.4;
        Parameters.speedJitterFraction = 0.3;
        Parameters.randomFlipChance = 0.03;
        Parameters.lagCompensationEnabled = true;
        Parameters.lagMaxRewindMs = 300;
        Parameters.lagClientInputDelayMs = 0;
        Parameters.lagDebugLog = false;
        LagTestSupport.useClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.cancelSession(id);
        }
        saved.restore();
        LagTestSupport.reset();
        plays.close();
        plugin.close();
        MockBukkit.unmock();
    }

    @Test
    void atZeroPingEveryClickGetsWhatTheOldCursorCheckGave() throws Exception {
        int checked = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            manager = new LockPickManager(new Random(seed));
            start();
            int ticks = 1 + seed % 30;
            for (int tick = 0; tick < ticks; tick++) {
                tick();
            }
            SelectResult old = oldResult(manager);
            assertEquals(old, manager.handleSelect(player), "seed " + seed);
            assertFalse(manager.isInSession(id));
            manager.clearAllCooldowns();
            checked++;
        }
        assertEquals(SEEDS, checked);
    }

    @Test
    void aDelayedClientGetsTheSameResultsAtAnyPingUpToTheCap() {
        for (int reaction : new int[] {0, 90}) {
            List<SelectResult> atZero = play(0, reaction, false);
            for (int ping : new int[] {130, 150, 300}) {
                assertEquals(atZero, play(ping, reaction, false), "reaction " + reaction + " ms at " + ping + " ms");
            }
            if (reaction == 0) {
                // Clicking the moment the cursor shows on a success slot always wins, at any ping.
                assertEquals(SEEDS, wins(atZero));
            }
        }
    }

    @Test
    void aTpsDipStretchingTicksKeepsThePingsEven() {
        // Every fifth tick takes 250 ms instead of 50: frames are judged by when they were sent, not by count.
        for (int reaction : new int[] {0, 90}) {
            List<SelectResult> atZero = play(0, reaction, true);
            for (int ping : new int[] {130, 300}) {
                assertEquals(atZero, play(ping, reaction, true), "reaction " + reaction + " ms at " + ping + " ms");
            }
        }
    }

    @Test
    void theCursorNeverStartsOnASuccessSlot() throws Exception {
        for (int seed = 0; seed < SEEDS; seed++) {
            manager = new LockPickManager(new Random(seed));
            start();
            double position = (double) field(session(manager), "position");
            assertNotEquals('s', oldLayout(manager)[(int) position], "seed " + seed);
            manager.cancelSession(id);
        }
    }

    @Test
    void withoutCompensationHighPingLosesTheSameClicks() {
        Parameters.lagCompensationEnabled = false;
        int atZero = wins(play(0, 0, false));
        int at150 = wins(play(150, 0, false));
        int at300 = wins(play(300, 0, false));
        assertEquals(SEEDS, atZero);
        assertTrue(at150 < atZero * 0.6, "150 ms without compensation won " + at150);
        assertTrue(at300 < atZero * 0.6, "300 ms without compensation won " + at300);
    }

    @Test
    void pingOverTheCapIsOnlyRewoundToTheCap() {
        // At 1000 ms the client sees each frame 500 ms late but the click is judged only 300 ms back, so a
        // click on sight lands 700 ms after its frame: about as bad as no compensation at 700 ms.
        List<SelectResult> capped = play(1000, 0, false);
        Parameters.lagMaxRewindMs = 1000;
        List<SelectResult> uncapped = play(1000, 0, false);
        assertEquals(SEEDS, wins(uncapped));
        assertTrue(wins(capped) < SEEDS * 0.6, "1000 ms capped at 300 won " + wins(capped));
    }

    @Test
    void aClickBeforeTheThiefCouldSeeTheBarIsNotReadyAtAnyPing() {
        when(player.getPing()).thenReturn(300);
        manager = new LockPickManager(new Random(7));
        start();
        assertEquals(SelectResult.NOT_READY, manager.handleSelect(player));
        // Two frames in (100 ms), a 300 ms thief still has not seen the first one.
        tick();
        tick();
        assertEquals(SelectResult.NOT_READY, manager.handleSelect(player));
        assertTrue(manager.isInSession(id));
        assertFalse(manager.isOnCooldown(id, "target"));
        // Seven frames in (350 ms), the first frame is what they see.
        for (int tick = 0; tick < 5; tick++) {
            tick();
        }
        String first = bar(sent.getFirst());
        SelectResult expected = slotUnderCursor(first, oldLayout(manager));
        assertEquals(expected, manager.handleSelect(player));
        assertFalse(manager.isInSession(id));
    }

    @Test
    void theDebugLogShowsTheJudgedAndTheUnrewoundResult() throws Exception {
        Path file = temp.resolve("minigame-timing.log");
        LagTestSupport.useLog(file, Logger.getAnonymousLogger());
        Parameters.lagDebugLog = true;
        when(player.getPing()).thenReturn(1000);
        manager = new LockPickManager(new Random(3));
        start();
        manager.handleSelect(player);
        for (int tick = 0; tick < 8; tick++) {
            tick();
        }
        SelectResult result = manager.handleSelect(player);
        manager.startDoorSession(player, server.addSimpleWorld("w").getBlockAt(0, 64, 0).getLocation(), 0.5, 10, 0.5);
        manager.handleSelect(player);
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("player=Thief game=display ping=1000 estimate=1000 lowest=-1 baseline=-1 rewind=300"
                + " framesBack=-1.0 result=NOT_READY unrewound=NOT_READY capped=true flagged=true"), lines.get(0));
        assertTrue(lines.get(1).contains("game=display ping=1000 estimate=1000 lowest=-1 baseline=-1 rewind=300 framesBack=6.0"
                + " result=" + result + " unrewound="), lines.get(1));
        assertTrue(lines.get(2).contains("game=door "), lines.get(2));
    }

    /**
     * Plays one attempt per seed with a client {@code ping} ms away: it sees each frame half the ping after it is
     * sent, and clicks {@code reaction} ms after it sees the cursor on a success slot it knows; the click reaches
     * the server half the ping later. With {@code dip}, every fifth tick takes 250 ms instead of 50.
     */
    private List<SelectResult> play(int ping, int reaction, boolean dip) {
        when(player.getPing()).thenReturn(ping);
        SelectResult[] results = new SelectResult[SEEDS];
        for (int seed = 0; seed < SEEDS; seed++) {
            clock.set(0);
            sent.clear();
            manager = new LockPickManager(new Random(seed));
            start();
            char[] known = new char[Parameters.barLength];
            Deque<long[]> inFlight = new ArrayDeque<>();
            long decided = -1;
            long clickArrives = -1;
            SelectResult result = null;
            long nextTick = 0;
            int ticks = 0;
            for (long now = 0; result == null && now < 20_000; now += 5) {
                clock.set(now);
                if (now == nextTick) {
                    ticks++;
                    nextTick += dip && ticks % 5 == 0 ? 250 : 50;
                    int before = sent.size();
                    server.getScheduler().performOneTick();
                    for (long[] frame : sent.stream().skip(before).toList()) {
                        inFlight.add(new long[] {frame[0] + ping / 2, frame[1]});
                    }
                }
                String visible = null;
                while (!inFlight.isEmpty() && inFlight.peek()[0] <= now) {
                    visible = bar(inFlight.poll());
                }
                if (visible != null) {
                    learn(visible, known);
                    if (decided < 0 && known[cursor(visible)] == 's') {
                        decided = now;
                    }
                }
                if (decided >= 0 && clickArrives < 0 && now >= decided + reaction) {
                    clickArrives = now + ping / 2;
                }
                if (clickArrives >= 0 && now >= clickArrives) {
                    result = manager.handleSelect(player);
                }
            }
            assertNotNull(result, "seed " + seed + " never clicked");
            results[seed] = result;
            manager.clearAllCooldowns();
        }
        return List.of(results);
    }

    private static int wins(List<SelectResult> results) {
        return (int) results.stream().filter(result -> result == SelectResult.SUCCESS).count();
    }

    private void start() {
        manager.startSession(player, anchor, LockPickManager.SessionKind.DISPLAY, "target", 0.5, 10, 0.5, null);
    }

    private void tick() {
        clock.addAndGet(50);
        server.getScheduler().performOneTick();
    }

    // Frames are kept as {time, index into bars}.
    private final java.util.List<String> bars = new java.util.ArrayList<>();

    private long[] frame(long time, String bar) {
        bars.add(bar);
        return new long[] {time, bars.size() - 1};
    }

    private String bar(long[] frame) {
        return bars.get((int) frame[1]);
    }

    /** The bar's slots: 's', 'b', 'f', or '=' for the cursor, which hides the slot under it. */
    private static char[] slots(String bar) {
        String inner = bar.substring(3, bar.length() - 3);
        char[] slots = new char[inner.length() / 3];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = switch (inner.substring(i * 3, i * 3 + 3)) {
                case "§a-" -> 's';
                case "§c-" -> 'b';
                case "§e-" -> 'f';
                default -> '=';
            };
        }
        return slots;
    }

    private static int cursor(String bar) {
        return new String(slots(bar)).indexOf('=');
    }

    private static void learn(String bar, char[] known) {
        char[] slots = slots(bar);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != '=') {
                known[i] = slots[i];
            }
        }
    }

    private static SelectResult slotUnderCursor(String bar, char[] layout) {
        return switch (layout[cursor(bar)]) {
            case 's' -> SelectResult.SUCCESS;
            case 'b' -> SelectResult.BREAK;
            default -> SelectResult.FAIL;
        };
    }

    /** What 1.9.4 gave for a click now: the slot under the rounded cursor position. */
    private SelectResult oldResult(LockPickManager manager) throws Exception {
        Object session = session(manager);
        double position = (double) field(session, "position");
        char type = oldLayout(manager)[(int) Math.round(position)];
        return switch (type) {
            case 's' -> SelectResult.SUCCESS;
            case 'b' -> SelectResult.BREAK;
            default -> SelectResult.FAIL;
        };
    }

    private char[] oldLayout(LockPickManager manager) {
        try {
            return (char[]) field(session(manager), "layout");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object session(LockPickManager manager) throws Exception {
        Field sessions = LockPickManager.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        return ((Map<?, ?>) sessions.get(manager)).get(id);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
