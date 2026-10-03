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
    private final List<Object[]> opened = new ArrayList<>();
    private final AtomicInteger solved = new AtomicInteger();
    private final AtomicInteger solvedMistakes = new AtomicInteger(-1);
    private final ParameterSnapshot saved = new ParameterSnapshot();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
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
        Parameters.chestMinigameMistakesToFail = 3;
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
        manager = new LockMinigameManager(lockPicks, mock(Random.class));
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
        assertTrue(manager.start(player, chest, LockMinigameManager.Mode.DIAL, mistakes -> {
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
        assertTrue(game.holdsStill());
        assertEquals(0f, player.getWalkSpeed());
        assertTrue(LockFreeze.isFrozen(player));
        assertEquals(1, opened.size());
        assertSame(player, opened.get(0)[0]);
        assertEquals(4, opened.get(0)[1]);
        assertEquals(3, opened.get(0)[2]);
        verify(view).label(Component.text("Steady...", NamedTextColor.GRAY), 1.1f);
        assertTrue(game.bar.getTitle().contains("Pick the lock"));
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
    void pressesAreJudgedWhereThePickLookedToALaggyThiefUpToTheCap() {
        doReturn(100).when(player).getPing();
        RingDialGame game = start();
        turnTo(game, 18);
        tap(1);
        assertEquals(1, game.set);
        Parameters.chestDialMaxLagTicks = 1;
        doReturn(500).when(player).getPing();
        ticks(RingDialGame.PAUSE_TICKS);
        // The second zone is narrower and starts at tick 12 again; at tick 18 a lag of one tick is still late.
        turnTo(game, 18);
        tap(1);
        assertEquals(1, game.set);
        assertEquals(1, game.slips);
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
