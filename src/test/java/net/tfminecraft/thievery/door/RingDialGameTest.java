package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.timing.LagTestSupport;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class RingDialGameTest {
    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<RiskCalculator> risk;
    private LockPickManager lockPicks;
    private LockMinigameManager manager;
    private Player player;
    private Block chest;
    private RingView view;
    private Random rolls;
    private final List<Object[]> opened = new ArrayList<>();
    private final AtomicInteger solved = new AtomicInteger();
    private final AtomicInteger solvedMistakes = new AtomicInteger(-1);
    private final ParameterSnapshot saved = new ParameterSnapshot();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // Each server tick is 50 ms on the lag compensation clock.
        LagTestSupport.useClock(() -> server.getScheduler().getCurrentTick() * 50L);
        Thievery instance = mock(Thievery.class);
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        when(instance.namespace()).thenReturn("thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        risk = mockStatic(RiskCalculator.class);
        saved.take();
        Parameters.chestMinigameEnabled = true;
        Parameters.chestMinigamePrepareSeconds = 0.05;
        Parameters.chestMinigameFailBreakChance = 0.0;
        Parameters.chestDialTumblers = 4;
        Parameters.chestDialZoneWidth = 0.14;
        Parameters.chestDialZoneShrinkPerTumbler = 0.15;
        Parameters.chestDialMinLapSeconds = 1.8;
        Parameters.chestDialMaxLapSeconds = 2.6;
        Parameters.chestDialLapSecondsPerDexterity = 0.01;
        Parameters.chestDialAlternate = true;
        Parameters.chestDialMaxLagTicks = 6;
        // Three slips here so the tests can walk through each kind; the real default is one.
        Parameters.chestDialMistakesToFail = 3;
        lockPicks = new LockPickManager();
        // Rolls of 0: every pass takes the shortest lap (36 ticks), starts its zone a third of the way round
        // (ticks 12 to 17) and asks for the first key, forward.
        rolls = mock(Random.class);
        manager = new LockMinigameManager(lockPicks, rolls);
        view = mock(RingView.class);
        manager.ringViews = (who, tumblers, slips) -> {
            opened.add(new Object[] {who, tumblers, slips});
            return view;
        };
        chest = server.addSimpleWorld("vault").getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        player = spy(server.addPlayer());
        doReturn(input(false, false, false, false, false)).when(player).getCurrentInput();
        doReturn(true).when(player).isOnGround();
        player.getInventory().setItemInMainHand(new ItemStack(Material.TRIPWIRE_HOOK));
        player.setWalkSpeed(0.2f);
    }

    @AfterEach
    void tearDown() {
        saved.restore();
        LagTestSupport.reset();
        risk.close();
        plugin.close();
        MockBukkit.unmock();
    }

    static Input input(boolean forward, boolean left, boolean back, boolean right, boolean sneak) {
        Input input = mock(Input.class);
        when(input.isForward()).thenReturn(forward);
        when(input.isLeft()).thenReturn(left);
        when(input.isBackward()).thenReturn(back);
        when(input.isRight()).thenReturn(right);
        when(input.isSneak()).thenReturn(sneak);
        return input;
    }

    private RingDialGame start() {
        assertTrue(manager.start(player, chest, true, mistakes -> {
            solved.incrementAndGet();
            solvedMistakes.set(mistakes);
        }));
        return (RingDialGame) manager.game(player.getUniqueId());
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    private void send(Input input) {
        manager.onInput(new PlayerInputEvent(player, input));
    }

    /** Presses then releases one key: 1 forward, 2 left, 3 back, 4 right. */
    private void tap(int key) {
        send(input(key == 1, key == 2, key == 3, key == 4, false));
        send(input(false, false, false, false, false));
    }

    /** Ticks into the first pass and on to {@code turnTicks}. */
    private void turnTo(RingDialGame game, int turnTicks) {
        if (game.phase != RingDialGame.Phase.TURN) {
            ticks(game.ticksLeft);
        }
        ticks(turnTicks - game.turnTicks);
        assertEquals(turnTicks, game.turnTicks);
    }

    @Test
    void openingFreezesTheThiefAndFloatsTheRing() {
        RingDialGame game = start();
        assertEquals(0f, player.getWalkSpeed());
        assertTrue(LockFreeze.isFrozen(player));
        assertEquals(1, opened.size());
        assertSame(player, opened.get(0)[0]);
        assertEquals(4, opened.get(0)[1]);
        assertEquals(3, opened.get(0)[2]);
        verify(view).label(Component.text("Steady...", NamedTextColor.GRAY), 1.1f);
        assertTrue(game.bar.getTitle().contains("Pick the lock"));
        assertTrue(game.bar.getPlayers().contains(player));
        assertEquals(RingDialGame.Phase.PREPARE, game.phase);
    }

    @Test
    void aThiefPickingMidJumpGetsTheRingOnceTheyLand() {
        doReturn(false).when(player).isOnGround();
        RingDialGame game = start();
        assertTrue(LockFreeze.isFrozen(player));
        assertTrue(game.bar.getTitle().contains("Pick the lock"));
        assertEquals(RingDialGame.Phase.LANDING, game.phase);
        ticks(3);
        assertTrue(opened.isEmpty());
        doReturn(true).when(player).isOnGround();
        ticks(1);
        assertEquals(1, opened.size());
        verify(view).label(Component.text("Steady...", NamedTextColor.GRAY), 1.1f);
        assertEquals(RingDialGame.Phase.PREPARE, game.phase);
        assertEquals(LockMinigame.ticks(Parameters.chestMinigamePrepareSeconds), game.ticksLeft);
        ticks(1);
        assertEquals(RingDialGame.Phase.TURN, game.phase);
    }

    @Test
    void aThiefWhoNeverLandsStillGetsTheRingAfterASecond() {
        doReturn(false).when(player).isOnGround();
        RingDialGame game = start();
        ticks(RingDialGame.LANDING_TICKS - 1);
        assertTrue(opened.isEmpty());
        ticks(1);
        assertEquals(1, opened.size());
        assertEquals(RingDialGame.Phase.PREPARE, game.phase);
    }

    @Test
    void waterAndLaddersCountAsComingToRest() {
        Player floating = mock(Player.class);
        assertFalse(RingDialGame.settled(floating));
        when(floating.isInWater()).thenReturn(true);
        assertTrue(RingDialGame.settled(floating));
        Player climbing = mock(Player.class);
        when(climbing.isClimbing()).thenReturn(true);
        assertTrue(RingDialGame.settled(climbing));
    }

    @Test
    void givingUpBeforeLandingFailsWithoutDrawingTheRing() {
        doReturn(false).when(player).isOnGround();
        RingDialGame game = start();
        send(input(false, false, false, false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        ticks(LockMinigame.FAILED_TICKS);
        assertTrue(opened.isEmpty());
        verifyNoInteractions(view);
        assertNull(manager.game(player.getUniqueId()));
        assertFalse(LockFreeze.isFrozen(player));
    }

    @Test
    void eachPassShowsTheThiefsOwnKeyAndTheZone() {
        RingDialGame game = start();
        ticks(1);
        assertEquals(RingDialGame.Phase.TURN, game.phase);
        assertEquals(36, game.sweep.periodTicks());
        assertTrue(game.sweep.clockwise());
        verify(view).zone(game.sweep, RingView.ZONE);
        ArgumentCaptor<Component> label = ArgumentCaptor.forClass(Component.class);
        verify(view, atLeastOnce()).label(label.capture(), eq(2.2f));
        KeybindComponent key = (KeybindComponent) label.getValue();
        assertEquals("key.forward", key.keybind());
        assertEquals(RingView.GOLD, key.color());
        assertTrue(key.hasDecoration(TextDecoration.BOLD));
        verify(view).pick(0.0, 0);
        assertEquals(BarColor.YELLOW, game.bar.getColor());
        assertTrue(game.bar.getTitle().endsWith("↻"));
    }

    @Test
    void thePickGlidesAheadAndClicksPastEachNotch() {
        RingDialGame game = start();
        turnTo(game, 4);
        verify(view).pick(game.sweep.angleAt(game.sweep.progress(4)), RingDialGame.LEAD_TICKS);
        verify(view).pick(game.sweep.angleAt(game.sweep.progress(4)), RingDialGame.LEAD_TICKS);
        verify(view, never()).pick(game.sweep.angleAt(game.sweep.progress(5)), RingDialGame.LEAD_TICKS);
        verify(player, atLeastOnce()).playSound(any(Location.class), eq(Sound.BLOCK_TRIPWIRE_CLICK_ON), eq(0.15f), eq(1.2f));
        verify(player, never()).playSound(any(Location.class), eq(Sound.BLOCK_TRIPWIRE_CLICK_ON), eq(0.35f), eq(1.9f));
        turnTo(game, 14);
        verify(player, atLeastOnce()).playSound(any(Location.class), eq(Sound.BLOCK_TRIPWIRE_CLICK_ON), eq(0.35f), eq(1.9f));
        assertEquals(1 - 14 / 36.0, game.bar.getProgress(), 1e-9);
    }

    @Test
    void aHitPopsAPinThenTheNextPassReversesWithANarrowerZone() {
        RingDialGame game = start();
        turnTo(game, 14);
        tap(1);
        assertEquals(1, game.set);
        verify(view).pin(0);
        verify(view).zone(game.sweep, RingView.GREEN);
        verify(view).burst(RingView.GREEN, 3);
        verify(view).label(Component.text("Set", RingView.GREEN), 1.1f);
        verify(view).pick(game.sweep.angleAt(game.sweep.progress(14)), 0);
        assertEquals(RingDialGame.Phase.PAUSE, game.phase);
        assertEquals(BarColor.GREEN, game.bar.getColor());
        tap(1);
        assertEquals(1, game.set);
        Sweep first = game.sweep;
        ticks(RingDialGame.PAUSE_TICKS);
        assertNotSame(first, game.sweep);
        assertFalse(game.sweep.clockwise());
        assertEquals(0.14 * 0.85, game.sweep.zoneTo() - game.sweep.zoneFrom(), 1e-9);
        assertTrue(game.bar.getTitle().endsWith("↺"));
    }

    @Test
    void keysHeldWhenThePickStartsOnlyCountOnceReleased() {
        doReturn(input(true, false, false, false, false)).when(player).getCurrentInput();
        RingDialGame game = start();
        turnTo(game, 13);
        send(input(true, false, false, false, false));
        assertEquals(0, game.set);
        assertEquals(0, game.slips);
        send(input(false, false, false, false, false));
        send(input(true, false, false, false, false));
        assertEquals(1, game.set);
    }

    @Test
    void aSneakHeldFromBeforeThePickDoesNotGiveUp() {
        doReturn(input(false, false, false, false, true)).when(player).getCurrentInput();
        RingDialGame game = start();
        turnTo(game, 13);
        send(input(false, false, false, false, true));
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        send(input(false, false, false, false, false));
        send(input(false, false, false, false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
    }

    @Test
    void wrongEarlyAndFumbledPressesAreSlipsUntilThePinsGive() {
        RingDialGame game = start();
        turnTo(game, 14);
        tap(2);
        assertEquals(1, game.slips);
        verify(view).slip(0);
        verify(view).zone(game.sweep, RingView.RED);
        verify(view).burst(RingView.RED, 4);
        verify(view).label(Component.text("Wrong key", RingView.RED), 1.1f);
        assertEquals(BarColor.RED, game.bar.getColor());
        ticks(RingDialGame.PAUSE_TICKS);
        turnTo(game, 5);
        tap(1);
        verify(view).label(Component.text("Too soon", RingView.RED), 1.1f);
        ticks(RingDialGame.PAUSE_TICKS);
        turnTo(game, 14);
        send(input(true, true, false, false, false));
        assertEquals(3, game.slips);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        verify(view).label(Component.text("The pins slip", RingView.RED), 0.9f);
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals(3, game.mistakes());
        send(input(false, false, false, false, true));
        ticks(LockMinigame.FAILED_TICKS);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        verify(view).remove();
        assertEquals(0.2f, player.getWalkSpeed());
    }

    @Test
    void byDefaultOneSlipFailsTheRing() {
        Parameters.chestDialMistakesToFail = 1;
        RingDialGame game = start();
        assertEquals(1, opened.get(0)[2]);
        turnTo(game, 14);
        tap(2);
        assertEquals(1, game.slips);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void lateAndMissedPassesAreSlipsToo() {
        RingDialGame game = start();
        turnTo(game, 20);
        tap(1);
        verify(view).label(Component.text("Too late", RingView.RED), 1.1f);
        ticks(RingDialGame.PAUSE_TICKS);
        turnTo(game, 35);
        assertEquals(1, game.slips);
        ticks(1);
        assertEquals(2, game.slips);
        assertEquals(RingDialGame.Phase.PAUSE, game.phase);
    }

    @Test
    void aLaggyPressForTheEndOfALateZoneArrivesBeforeThePassRunsOut() {
        // A roll near 1 puts the zone as late as it goes: about ticks 27 to 32 of the 36-tick pass.
        when(rolls.nextDouble()).thenReturn(0.999);
        doReturn(200).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 36);
        // The thief, four ticks behind, saw the needle at tick 32, still in the zone.
        assertEquals(0, game.slips);
        tap(1);
        assertEquals(1, game.set);
        assertEquals(0, game.slips);
        verify(view).zone(game.sweep, RingView.GREEN);
    }

    @Test
    void aPassRunsOutOnceEvenALaggyThiefHasSeenItEnd() {
        doReturn(200).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 39);
        assertEquals(0, game.slips);
        ticks(1);
        assertEquals(1, game.slips);
    }

    @Test
    void pressesAreJudgedWhereThePickLookedToALaggyThiefUpToTheCap() {
        doReturn(100).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 18);
        tap(1);
        assertEquals(1, game.set);
        Parameters.lagMaxRewindMs = 50;
        doReturn(500).when(player).getPing();
        ticks(RingDialGame.PAUSE_TICKS);
        // The second zone is narrower and starts at tick 12 again; at tick 18 a rewind of one tick is still late.
        turnTo(game, 18);
        tap(1);
        assertEquals(1, game.set);
        assertEquals(1, game.slips);
    }

    /**
     * End to end: the thief sees tick {@code seen} of the pass {@code ping / 2} ms after it is sent and presses
     * at once; the press arrives {@code ping / 2} ms later, between server ticks for an odd ping. Whatever the
     * ping, it gets what a 0 ms press at that tick gets. The zone covers ticks 12 to 17 of the 36-tick pass.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "0, 11, Too soon", "130, 11, Too soon", "150, 11, Too soon", "300, 11, Too soon",
            "0, 12, Set", "130, 12, Set", "150, 12, Set", "300, 12, Set",
            "0, 14, Set", "130, 14, Set", "150, 14, Set", "300, 14, Set",
            "0, 17, Set", "130, 17, Set", "150, 17, Set", "300, 17, Set",
            "0, 18, Too late", "130, 18, Too late", "150, 18, Too late", "300, 18, Too late"})
    void aPressAtTheSameSeenTickGetsTheSameResultAtAnyPingUpToTheCap(int ping, int seen, String outcome) {
        doReturn(ping).when(player).getPing();
        RingDialGame game = start();
        pressSeenAt(game, seen, ping);
        verify(view).label(Component.text(outcome, outcome.equals("Set") ? RingView.GREEN : RingView.RED), 1.1f);
        assertEquals(outcome.equals("Set") ? 1 : 0, game.set);
    }

    /**
     * The pass waits for a late press for its last zone tick: with the zone as late as it goes (ticks 27 to 32)
     * a press seen at tick 32 is a hit at 0, 130, 150 and 300 ms, though at 300 ms it arrives at tick 38, after the
     * pass has ended on the server.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 130, 150, 300})
    void aPressForTheLastZoneTickArrivingAfterThePassEndsStillSets(int ping) {
        when(rolls.nextDouble()).thenReturn(0.999);
        doReturn(ping).when(player).getPing();
        RingDialGame game = start();
        pressSeenAt(game, 32, ping);
        assertEquals(0, game.slips);
        assertEquals(1, game.set);
    }

    /** Beyond the cap the press is judged only 300 ms back, so seen at tick 32 at 1000 ms the pass has run out. */
    @Test
    void aPressFromBeyondTheCapIsJudgedOnlyAsFarBackAsTheCap() {
        when(rolls.nextDouble()).thenReturn(0.999);
        doReturn(1000).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 36 + 6);
        assertEquals(1, game.slips);
        assertEquals(RingDialGame.Phase.PAUSE, game.phase);
    }

    /** Ticks into the pass until the press seen at tick {@code seen} arrives, {@code ping} ms later, and presses. */
    private void pressSeenAt(RingDialGame game, int seen, int ping) {
        long arrives = seen * 50L + ping;
        turnTo(game, (int) (arrives / 50));
        long extra = arrives % 50;
        LagTestSupport.useClock(() -> server.getScheduler().getCurrentTick() * 50L + extra);
        tap(1);
        LagTestSupport.useClock(() -> server.getScheduler().getCurrentTick() * 50L);
    }

    @Test
    void aRewindBetweenTicksGlidesTheNeedleAsTheClientDraws() {
        // 225 ms is four and a half ticks: at tick 21 the thief saw the needle half way from tick 16 to 17.
        doReturn(225).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 21);
        assertEquals(16.5, game.seenTicks(player, net.tfminecraft.thievery.timing.LagCompensation.rewind(player)),
                1e-9);
        tap(1);
        assertEquals(1, game.set);
    }

    @Test
    void aPressBeforeTheThiefCouldSeeThePassDoesNothing() {
        // At 300 ms the thief is six ticks behind: three ticks into the pass they still see the pause.
        doReturn(300).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 3);
        tap(2);
        send(input(true, true, false, false, false));
        send(input(false, false, false, false, false));
        assertEquals(0, game.slips);
        assertEquals(RingDialGame.Phase.TURN, game.phase);
        // Six ticks on, the thief sees tick 14 at tick 20: inside the zone.
        turnTo(game, 20);
        tap(1);
        assertEquals(1, game.set);
        assertEquals(0, game.slips);
    }

    @Test
    void aSpikeInPingDoesNotPushThePressFurtherBack() {
        // The usual ping is 50 ms; a 300 ms spike right now still rewinds only one tick.
        for (int i = 0; i < 30; i++) {
            LagTestSupport.sample(player.getUniqueId(), 50);
        }
        doReturn(300).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 19);
        tap(1);
        // Tick 18 is past the zone (12 to 17); the spike's six ticks would have judged tick 13, a hit.
        assertEquals(0, game.set);
        assertEquals(1, game.slips);
    }

    @Test
    void withLagCompensationOffTheRingKeepsItsOldTickRewind() {
        Parameters.lagCompensationEnabled = false;
        doReturn(200).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 3);
        // The old rewind clamps to the start of the pass, so an early press is a slip, not ignored.
        tap(1);
        assertEquals(1, game.slips);
        verify(view).label(Component.text("Too soon", RingView.RED), 1.1f);
        ticks(RingDialGame.PAUSE_TICKS);
        Parameters.chestDialMaxLagTicks = 2;
        // round(200 / 50) = 4 ticks, capped at 2: at tick 19 the thief is judged on tick 17, still a hit.
        turnTo(game, 19);
        tap(1);
        assertEquals(1, game.set);
    }

    @Test
    void theDebugLogRecordsEachJudgedPress(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
            throws Exception {
        java.nio.file.Path log = folder.resolve("logs").resolve("minigame-timing.log");
        LagTestSupport.useLog(log, java.util.logging.Logger.getAnonymousLogger());
        Parameters.lagDebugLog = true;
        doReturn(300).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 2);
        tap(1);
        turnTo(game, 20);
        tap(1);
        assertEquals(1, game.set);
        List<String> lines = java.nio.file.Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("game=ring ping=300 estimate=300 lowest=-1 baseline=-1 rewind=300 framesBack=-1.0"
                + " result=NOT_READY unrewound=EARLY capped=false flagged=true"), lines.get(0));
        assertTrue(lines.get(1).contains("game=ring ping=300 estimate=300 lowest=-1 baseline=-1 rewind=300 framesBack=6.0"
                + " result=HIT unrewound=LATE capped=false flagged=true"), lines.get(1));
    }

    @Test
    void sneakingGivesUpAndPausedInputIsIgnored() {
        RingDialGame game = start();
        send(input(true, false, false, false, false));
        send(input(false, false, false, false, false));
        assertEquals(0, game.slips);
        turnTo(game, 14);
        send(input(false, false, false, false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void settingEveryTumblerOpensTheLockAndLetsTheThiefGo() {
        Parameters.chestDialTumblers = 2;
        RingDialGame game = start();
        turnTo(game, 14);
        tap(1);
        ticks(RingDialGame.PAUSE_TICKS);
        turnTo(game, 14);
        tap(1);
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        verify(view).label(Component.text("The lock gives way", RingView.GREEN), 0.9f);
        verify(player).playSound(any(Location.class), eq(Sound.BLOCK_IRON_TRAPDOOR_OPEN), eq(0.8f), eq(1.2f));
        assertEquals(0f, player.getWalkSpeed());
        ticks(LockMinigame.SOLVED_TICKS);
        assertEquals(1, solved.get());
        assertEquals(0, solvedMistakes.get());
        verify(view).remove();
        assertEquals(0.2f, player.getWalkSpeed());
        assertFalse(LockFreeze.isFrozen(player));
    }

    @Test
    void dexteritySlowsThePickAndTheZoneNeverShrinksAway() {
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(50);
        Parameters.chestDialAlternate = false;
        Parameters.chestDialZoneShrinkPerTumbler = 0.5;
        Parameters.chestDialTumblers = 4;
        RingDialGame game = start();
        ticks(1);
        assertEquals(46, game.sweep.periodTicks());
        for (int pass = 0; pass < 3; pass++) {
            turnTo(game, 17);
            tap(1);
            ticks(RingDialGame.PAUSE_TICKS);
            assertTrue(game.sweep.clockwise());
        }
        assertEquals(3, game.set);
        assertEquals(0.14 * RingDialGame.MIN_ZONE_SHARE, game.sweep.zoneTo() - game.sweep.zoneFrom(), 1e-9);
    }

    @Test
    void cleanupCopesWithAGameThatNeverOpened() {
        RingDialGame game = new RingDialGame(manager, player.getUniqueId(), chest, "chest", mock(Random.class),
                -3, manager.ringViews, mistakes -> {});
        assertEquals(0, game.dexterity);
        assertDoesNotThrow(() -> game.cleanup(null));
        game.cleanup(player);
        assertFalse(LockFreeze.isFrozen(player));
    }
}
