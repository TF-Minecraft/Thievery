package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PickpocketGameTest {
    /** A first-phase press with a roll of 0: 85% of the gain, doubled for the half-length phase. */
    private static final double FIRST_PRESS = PickpocketGame.PRESS_GAIN * 0.85 / 0.5;

    private ServerMock server;
    private org.mockito.MockedStatic<Thievery> plugin;
    private LockPickManager lockPicks;
    private LockMinigameManager manager;
    private World world;
    private PlayerMock player;
    private PlayerMock victim;
    private RingView view;
    private Random rolls;
    private final List<Object[]> opened = new ArrayList<>();
    private final AtomicInteger picked = new AtomicInteger();
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
        saved.take();
        Parameters.lockpickFailCooldownMs = 60_000L;
        PickpocketLoader.load(new YamlConfiguration());
        lockPicks = new LockPickManager();
        rolls = mock(Random.class);
        manager = new LockMinigameManager(lockPicks, rolls);
        view = mock(RingView.class);
        manager.gaugeViews = (who, mark, phases) -> {
            opened.add(new Object[] {who, phases, mark});
            return view;
        };
        world = server.addSimpleWorld("market");
        player = spy(server.addPlayer());
        victim = spy(server.addPlayer());
        player.teleport(new Location(world, 0, 64, 0));
        victim.teleport(new Location(world, 2, 64, 0));
        doReturn(input(false, false)).when(player).getCurrentInput();
        doReturn(true).when(player).isOnGround();
        player.setWalkSpeed(0.2f);
    }

    @AfterEach
    void tearDown() {
        PickpocketLoader.load(new YamlConfiguration());
        saved.restore();
        plugin.close();
        MockBukkit.unmock();
    }

    static Input input(boolean jump, boolean sneak) {
        Input input = mock(Input.class);
        when(input.isJump()).thenReturn(jump);
        when(input.isSneak()).thenReturn(sneak);
        return input;
    }

    private PickpocketGame start() {
        assertTrue(manager.startPickpocket(player, victim, picked::incrementAndGet));
        return (PickpocketGame) manager.game(player.getUniqueId());
    }

    /** Starts a game and ticks past the steady pause into the first phase. */
    private PickpocketGame mashing() {
        PickpocketGame game = start();
        ticks(PickpocketGame.PREPARE_TICKS);
        assertEquals(PickpocketGame.Phase.MASH, game.phase);
        return game;
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    private void send(Input input) {
        manager.onInput(new PlayerInputEvent(player, input));
    }

    /** Presses and releases jump {@code count} times within one tick. */
    private void mash(int count) {
        for (int i = 0; i < count; i++) {
            send(input(true, false));
            send(input(false, false));
        }
    }

    private void drainMessages() {
        while (player.nextMessage() != null) {
            // Skip to the message under test.
        }
    }

    @Test
    void openingFreezesTheThiefAndFloatsAnEmptyGauge() {
        PickpocketGame game = start();
        assertTrue(game.holdsStill());
        assertFalse(game.targetGone());
        assertSame(PickpocketGame.POCKET, game.wording());
        assertEquals(0, game.mistakes());
        assertNull(game.target);
        assertEquals("pocket:" + victim.getUniqueId(), game.targetId);
        assertTrue(LockFreeze.isFrozen(player));
        assertEquals(1, opened.size());
        assertSame(player, opened.get(0)[0]);
        assertEquals(PickpocketGame.PHASES, opened.get(0)[1]);
        assertSame(victim, opened.get(0)[2]);
        verify(view).label(Component.text("Steady...", NamedTextColor.GRAY), PickpocketGame.WORD_SIZE);
        assertTrue(game.bar.getTitle().contains("Pick their pocket"));
        assertTrue(game.bar.getPlayers().contains(player));
        assertEquals(PickpocketGame.Phase.PREPARE, game.phase);
        // Two thieves can work two marks, and a pocket is never a chest being picked.
        assertFalse(manager.isPicking(world.getBlockAt(0, 64, 0)));
    }

    @Test
    void theFirstPhaseStartsEmptyInItsOwnColour() {
        PickpocketGame game = mashing();
        assertEquals(0, game.section);
        verify(view).caption(Component.text("PHASE I", PickpocketGame.ACCENTS[0]));
        verify(view).pip(0, PickpocketGame.ACCENTS[0], false);
        verify(view).fill(0.0, PickpocketGame.ACCENTS[0], PickpocketGame.TRACKS[0]);
        verify(view).label(Component.text(0, PickpocketGame.ACCENTS[0]), 1.6f);
        assertEquals(BarColor.BLUE, game.bar.getColor());
        assertEquals(0, game.elapsed);
    }

    @Test
    void jumpsFillTheRingAndItDrainsOnAWave() {
        when(rolls.nextDouble()).thenReturn(0.5);
        PickpocketGame game = mashing();
        assertEquals(Math.PI, game.wavePhase, 1e-9);
        mash(1);
        // A roll of 0.5 is the middle of the spread: the full gain.
        assertEquals(PickpocketGame.PRESS_GAIN * 2, game.progress, 1e-9);
        verify(player).playSound(any(Location.class), eq(Sound.BLOCK_TRIPWIRE_CLICK_ON), eq(0.3f), eq(1.6f));
        verify(view).label(Component.text(4, PickpocketGame.ACCENTS[0]), 1.6f);
        double before = game.progress;
        ticks(1);
        double wave = 1 + PickpocketGame.WAVE * Math.sin(2 * Math.PI * PickpocketGame.WAVE_HERTZ / 20.0 + Math.PI);
        assertEquals(before - PickpocketGame.DRAIN_PER_SECOND * wave / 20.0 / 0.5, game.progress, 1e-9);
        // The percentage is sent only when it changes.
        verify(view, times(1)).label(Component.text(3, PickpocketGame.ACCENTS[0]), 1.6f);
        ticks(1);
        verify(view, times(1)).label(Component.text(3, PickpocketGame.ACCENTS[0]), 1.6f);
        ticks(40);
        assertEquals(0.0, game.progress);
        verify(view, atLeastOnce()).fill(0.0, PickpocketGame.ACCENTS[0], PickpocketGame.TRACKS[0]);
    }

    @Test
    void jumpsOnlyCountWhileMashingAndAHeldJumpOnlyOnceReleased() {
        doReturn(input(true, false)).when(player).getCurrentInput();
        PickpocketGame game = start();
        mash(3);
        assertEquals(0.0, game.progress);
        ticks(PickpocketGame.PREPARE_TICKS);
        send(input(false, false));
        assertEquals(0.0, game.progress);
        send(input(true, false));
        assertEquals(FIRST_PRESS, game.progress, 1e-9);
        send(input(true, false));
        assertEquals(FIRST_PRESS, game.progress, 1e-9);
    }

    @Test
    void aSneakHeldFromBeforeThePickDoesNotGiveUp() {
        doReturn(input(false, true)).when(player).getCurrentInput();
        PickpocketGame game = mashing();
        send(input(false, true));
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        send(input(false, false));
        send(input(false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
    }

    @Test
    void fillingThePhaseSetsItAndTheNextPhaseFollows() {
        PickpocketGame game = mashing();
        mash(28);
        assertEquals(PickpocketGame.Phase.MASH, game.phase);
        mash(1);
        assertEquals(PickpocketGame.Phase.SET, game.phase);
        verify(view).fill(1.0, RingView.GREEN, PickpocketGame.TRACKS[0]);
        verify(view).label(Component.text(100, RingView.GREEN), 1.6f);
        verify(view).pip(0, RingView.GREEN, true);
        verify(view, never()).burst(any(), anyInt());
        verify(player).playSound(any(Location.class), eq(Sound.BLOCK_IRON_TRAPDOOR_CLOSE), eq(0.5f), eq(1.8f));
        assertEquals(BarColor.GREEN, game.bar.getColor());
        mash(5);
        assertEquals(100.0, game.progress);
        ticks(PickpocketGame.SET_TICKS - 1);
        assertEquals(PickpocketGame.Phase.SET, game.phase);
        ticks(1);
        assertEquals(PickpocketGame.Phase.MASH, game.phase);
        assertEquals(1, game.section);
        assertEquals(0.0, game.progress);
        assertEquals(PickpocketGame.SET_TICKS, game.elapsed);
        verify(view).caption(Component.text("PHASE II", PickpocketGame.ACCENTS[1]));
        verify(view).pip(1, PickpocketGame.ACCENTS[1], false);
        verify(view).label(Component.text(0, PickpocketGame.ACCENTS[1]), 1.6f);
        assertEquals(BarColor.YELLOW, game.bar.getColor());
        // The second phase is full length: each press adds half as much.
        mash(1);
        assertEquals(PickpocketGame.PRESS_GAIN * 0.85, game.progress, 1e-9);
    }

    @Test
    void fillingBothPhasesOpensThePocketAndLetsTheThiefGo() {
        PickpocketGame game = mashing();
        mash(29);
        ticks(PickpocketGame.SET_TICKS);
        mash(58);
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        assertTrue(game.bar.getTitle().contains("You're into their pocket"));
        verify(view).label(Component.text("Got it", RingView.GREEN), PickpocketGame.WORD_SIZE);
        verify(view).caption(Component.empty());
        verify(view, never()).fill(1.0, RingView.RED, RingView.RED);
        verify(player).playSound(any(Location.class), eq(Sound.ITEM_BUNDLE_REMOVE_ONE), eq(0.8f), eq(1.2f));
        mash(3);
        ticks(LockMinigame.SOLVED_TICKS);
        assertEquals(1, picked.get());
        verify(view).remove();
        assertFalse(LockFreeze.isFrozen(player));
        assertEquals(0.2f, player.getWalkSpeed());
        verify(victim, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void runningOutOfTimeAlertsTheMarkAndKeepsTheThiefOffThemForAWhile() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("pickpocket.minigame.time-limit-seconds", 6);
        PickpocketLoader.load(config);
        PickpocketGame game = mashing();
        assertEquals(120, game.limitTicks);
        ticks(119);
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        assertEquals(1.0 / 120, game.bar.getProgress(), 1e-9);
        ticks(1);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertTrue(game.bar.getTitle().contains("Your mark felt that"));
        verify(view).label(Component.text("Noticed", RingView.RED), PickpocketGame.WORD_SIZE);
        verify(view).fill(1.0, RingView.RED, RingView.RED);
        verify(victim).sendTitle(eq(""), contains("pickpocketing you"), eq(5), eq(40), eq(10));
        verify(player).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_BASS), eq(0.8f), eq(0.6f));
        drainMessages();
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        ticks(LockMinigame.FAILED_TICKS);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertFalse(manager.startPickpocket(player, victim, picked::incrementAndGet));
        String refusal = player.nextMessage();
        assertTrue(refusal.startsWith("§cYour mark is still on guard. Try again in "), refusal);
        assertEquals(0, picked.get());
    }

    @Test
    void theTimerKeepsRunningWhileAPhaseIsSet() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("pickpocket.minigame.time-limit-seconds", 1);
        PickpocketLoader.load(config);
        PickpocketGame game = mashing();
        // Clamped to five seconds.
        assertEquals(100, game.limitTicks);
        ticks(95);
        mash(29);
        assertEquals(PickpocketGame.Phase.SET, game.phase);
        ticks(5);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
    }

    @Test
    void givingUpIsAFailedAttemptTheMarkFeels() {
        PickpocketGame game = mashing();
        send(input(false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("§7You draw your hand back.", player.nextMessage());
        assertEquals("§cYour mark felt your hand!", player.nextMessage());
        verify(victim).sendTitle(eq(""), contains("pickpocketing you"), eq(5), eq(40), eq(10));
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void aMarkWhoWalksOffOrLeavesEndsTheAttemptWithoutAPenalty() {
        PickpocketGame game = mashing();
        victim.teleport(new Location(world, 10, 64, 0));
        ticks(1);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertEquals("§7Your mark is out of reach.", player.nextMessage());
        verify(view).remove();
        assertFalse(LockFreeze.isFrozen(player));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));

        victim.teleport(new Location(world, 2, 64, 0));
        start();
        doReturn(false).when(victim).isOnline();
        ticks(1);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertEquals("§7Your mark is out of reach.", player.nextMessage());
        assertEquals(0, picked.get());
    }

    @Test
    void aThiefWhoQuitsMidPickAlertsTheMarkButOneWhoseMarkHasGoneDoesNot() {
        PickpocketGame game = mashing();
        manager.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        verify(victim).sendTitle(eq(""), contains("pickpocketing you"), eq(5), eq(40), eq(10));

        lockPicks.clearCooldown(player.getUniqueId());
        drainMessages();
        PickpocketGame second = mashing();
        doReturn(false).when(victim).isOnline();
        second.penalise(player);
        verify(victim, times(1)).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        assertNull(player.nextMessage());
    }

    @Test
    void interruptionsUseThePickpocketsOwnWords() {
        PickpocketGame game = mashing();
        EntityDamageEvent hit = mock(EntityDamageEvent.class);
        when(hit.getEntity()).thenReturn(player);
        when(hit.getFinalDamage()).thenReturn(2.0);
        manager.onDamage(hit);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("§cYou flinch and your hand slips.", player.nextMessage());

        lockPicks.clearCooldown(player.getUniqueId());
        drainMessages();
        mashing();
        Location here = player.getLocation();
        manager.onTeleport(new PlayerTeleportEvent(player, here, here.clone().add(20, 0, 0),
                PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertEquals("§7You were pulled away from your mark.", player.nextMessage());
    }

    @Test
    void aThiefMidJumpGetsTheGaugeOnceTheyLandAndTheMarkIsNotCheckedUntilThen() {
        doReturn(false).when(player).isOnGround();
        PickpocketGame game = start();
        assertEquals(PickpocketGame.Phase.LANDING, game.phase);
        victim.teleport(new Location(world, 10, 64, 0));
        ticks(3);
        assertTrue(opened.isEmpty());
        assertTrue(manager.isPlaying(player.getUniqueId()));
        victim.teleport(new Location(world, 2, 64, 0));
        doReturn(true).when(player).isOnGround();
        ticks(1);
        assertEquals(1, opened.size());
        assertEquals(PickpocketGame.Phase.PREPARE, game.phase);
    }

    @Test
    void aThiefWhoNeverLandsStillGetsTheGaugeAfterASecond() {
        doReturn(false).when(player).isOnGround();
        PickpocketGame game = start();
        ticks(RingDialGame.LANDING_TICKS - 1);
        assertTrue(opened.isEmpty());
        ticks(1);
        assertEquals(PickpocketGame.Phase.PREPARE, game.phase);
    }

    @Test
    void givingUpBeforeLandingFailsWithoutDrawingTheGauge() {
        doReturn(false).when(player).isOnGround();
        PickpocketGame game = start();
        send(input(false, true));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        ticks(LockMinigame.FAILED_TICKS);
        verifyNoInteractions(view);
        assertFalse(LockFreeze.isFrozen(player));
        assertDoesNotThrow(() -> game.cleanup(null));
    }

    @Test
    void aStaffTestPlaysWithNoMarkEvenWhenTheMinigameIsOff() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("pickpocket.minigame.enabled", false);
        PickpocketLoader.load(config);
        assertTrue(manager.startPickpocket(player, null, picked::incrementAndGet));
        PickpocketGame game = (PickpocketGame) manager.game(player.getUniqueId());
        assertEquals("pocket:test", game.targetId);
        ticks(PickpocketGame.PREPARE_TICKS + 1);
        assertEquals(PickpocketGame.Phase.MASH, game.phase);
        send(input(false, true));
        assertEquals("§7You draw your hand back.", player.nextMessage());
        assertNull(player.nextMessage());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), "pocket:test"));
        verify(victim, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void withTheMinigameOffThePocketOpensStraightAway() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("pickpocket.minigame.enabled", false);
        PickpocketLoader.load(config);
        assertTrue(manager.startPickpocket(player, victim, picked::incrementAndGet));
        assertEquals(1, picked.get());
        assertFalse(manager.isPlaying(player.getUniqueId()));
    }

    @Test
    void aMountedOrGlidingThiefCannotStart() {
        doReturn(true).when(player).isInsideVehicle();
        assertFalse(manager.startPickpocket(player, victim, picked::incrementAndGet));
        assertEquals("§cYou need both feet on the ground to pick a pocket.", player.nextMessage());
        doReturn(false).when(player).isInsideVehicle();
        doReturn(true).when(player).isGliding();
        assertFalse(manager.startPickpocket(player, victim, picked::incrementAndGet));
        assertFalse(manager.isPlaying(player.getUniqueId()));
    }

    @Test
    void theRealGaugeIsOpenedForThePlugin() {
        LockMinigameManager real = new LockMinigameManager(lockPicks);
        try (var views = mockStatic(RingView.class)) {
            views.when(() -> RingView.openGauge(any(), any(), any(), anyInt())).thenReturn(view);
            assertSame(view, real.gaugeViews.open(player, victim, 2));
            views.verify(() -> RingView.openGauge(Thievery.getInstance(), player, victim, 2));
        }
    }

    @Test
    void anOutcomeBeforeTheGaugeOpensDrawsNothing() {
        doReturn(false).when(player).isOnGround();
        PickpocketGame game = start();
        game.outcome = LockMinigame.Outcome.SOLVED;
        game.showOutcome(player);
        verifyNoInteractions(view);
    }
}
