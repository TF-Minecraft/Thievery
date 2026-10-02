package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.Keys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class LockMinigameManagerTest {
    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<RiskCalculator> risk;
    private LockPickManager lockPicks;
    private Random random;
    private LockMinigameManager manager;
    private Player player;
    private World world;
    private Block chest;
    private RingView view;
    private final AtomicInteger solved = new AtomicInteger();
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
        Parameters.chestDialChance = 0.5;
        Parameters.chestMinigameRows = 2;
        Parameters.chestMinigameColumns = 3;
        Parameters.chestMinigamePins = 2;
        Parameters.chestMinigamePrepareSeconds = 0.05;
        Parameters.chestMinigameFailBreakChance = 0.0;
        Parameters.lockpickFailCooldownMs = 60_000L;
        lockPicks = new LockPickManager();
        random = mock(Random.class);
        manager = new LockMinigameManager(lockPicks, random);
        manager.gridScreens = (who, screen, onCell, onGiveUp) -> {};
        view = mock(RingView.class);
        manager.ringViews = (who, tumblers, slips) -> view;
        world = server.addSimpleWorld("vault");
        chest = world.getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        player = spy(server.addPlayer());
        doReturn(RingDialGameTest.input(false, false, false, false, false)).when(player).getCurrentInput();
        player.getInventory().setItemInMainHand(new ItemStack(Material.TRIPWIRE_HOOK));
    }

    @AfterEach
    void tearDown() {
        saved.restore();
        risk.close();
        plugin.close();
        MockBukkit.unmock();
    }

    private boolean start(LockMinigameManager.Mode mode) {
        return manager.start(player, chest, mode, mistakes -> solved.incrementAndGet());
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    @Test
    void theRealRingIsOpenedForThePluginAndStaleGiveUpsAreIgnored() {
        LockMinigameManager real = new LockMinigameManager(lockPicks);
        try (MockedStatic<RingView> views = mockStatic(RingView.class)) {
            views.when(() -> RingView.open(any(), any(), anyInt(), anyInt())).thenReturn(view);
            assertSame(view, real.ringViews.open(player, 4, 3));
            views.verify(() -> RingView.open(Thievery.getInstance(), player, 4, 3));
        }
        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame old = manager.game(player.getUniqueId());
        manager.cancel(player.getUniqueId());
        manager.giveUp(player, old);
        assertEquals(LockMinigame.Outcome.NONE, old.outcome);
    }

    @Test
    void targetIdsNameTheChestBlock() {
        assertEquals("chest:vault:0:64:0", LockMinigameManager.targetId(chest.getLocation()));
        assertEquals("", LockMinigameManager.targetId(null));
        assertEquals("", LockMinigameManager.targetId(new Location(null, 1, 2, 3)));
        assertEquals(1, LockMinigame.ticks(0));
        assertEquals(30, LockMinigame.ticks(1.5));
    }

    @Test
    void theMinigameOffOpensTheChestStraightAwayUnlessStaffForceOne() {
        Parameters.chestMinigameEnabled = false;
        assertTrue(manager.start(player, chest, mistakes -> solved.incrementAndGet()));
        assertEquals(1, solved.get());
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertTrue(start(LockMinigameManager.Mode.GRID));
        assertInstanceOf(PinGridGame.class, manager.game(player.getUniqueId()));
        assertEquals(1, solved.get());
    }

    @Test
    void theRollPicksTheRingOrTheGridAndForcedModesWin() {
        when(random.nextDouble()).thenReturn(0.4);
        assertTrue(start(null));
        assertInstanceOf(RingDialGame.class, manager.game(player.getUniqueId()));
        when(random.nextDouble()).thenReturn(0.6);
        assertTrue(start(null));
        assertInstanceOf(PinGridGame.class, manager.game(player.getUniqueId()));
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        assertInstanceOf(RingDialGame.class, manager.game(player.getUniqueId()));
        assertTrue(manager.isPicking(chest));
        assertFalse(manager.isPicking(chest.getRelative(1, 0, 0)));
    }

    @Test
    void aThiefOnCooldownIsSentAwayAndStartingAgainReplacesTheLastGame() {
        lockPicks.applyCooldown(player.getUniqueId(), LockMinigameManager.targetId(chest.getLocation()));
        assertFalse(start(LockMinigameManager.Mode.GRID));
        String message = ((PlayerMock) player).nextMessage();
        assertTrue(message.startsWith("§cYour hands are still shaking. Try this lock again in "), message);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        lockPicks.clearCooldown(player.getUniqueId());
        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame first = manager.game(player.getUniqueId());
        assertTrue(start(LockMinigameManager.Mode.GRID));
        assertNotSame(first, manager.game(player.getUniqueId()));
        assertTrue(first.task.isCancelled());
        assertTrue(first.bar.getPlayers().isEmpty());
    }

    @Test
    void theRingNeedsBothFeetOnTheGround() {
        doReturn(true).when(player).isInsideVehicle();
        assertFalse(start(LockMinigameManager.Mode.DIAL));
        assertEquals("§cYou need both feet on the ground to work this lock.", ((PlayerMock) player).nextMessage());
        doReturn(false).when(player).isInsideVehicle();
        doReturn(true).when(player).isGliding();
        assertFalse(start(LockMinigameManager.Mode.DIAL));
        assertTrue(start(LockMinigameManager.Mode.GRID));
        assertInstanceOf(PinGridGame.class, manager.game(player.getUniqueId()));
    }

    @Test
    void cancellingEndsGamesQuietlyAndTicksStopForGoneThievesOrChests() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        LockMinigame game = manager.game(player.getUniqueId());
        manager.cancel(player.getUniqueId());
        assertFalse(manager.isPlaying(player.getUniqueId()));
        verify(view).remove();
        assertFalse(LockFreeze.isFrozen(player));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        manager.cancel(player.getUniqueId());
        manager.tick(player, game);

        assertTrue(start(LockMinigameManager.Mode.GRID));
        chest.setType(Material.STONE);
        ticks(1);
        assertFalse(manager.isPlaying(player.getUniqueId()));

        chest.setType(Material.CHEST);
        assertTrue(start(LockMinigameManager.Mode.GRID));
        doReturn(false).when(player).isOnline();
        ticks(1);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        doReturn(true).when(player).isOnline();

        PlayerMock other = server.addPlayer();
        Block barrel = chest.getRelative(2, 0, 0);
        barrel.setType(Material.BARREL);
        assertTrue(start(LockMinigameManager.Mode.GRID));
        assertTrue(manager.start(other, barrel, LockMinigameManager.Mode.GRID, mistakes -> {}));
        manager.cancelAll();
        assertFalse(manager.isPicking(chest));
        assertFalse(manager.isPicking(barrel));
        assertEquals(0, solved.get());
    }

    @Test
    void movementKeysReachOnlyAGameStillBeingPlayed() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        RingDialGame game = (RingDialGame) manager.game(player.getUniqueId());
        manager.onInput(new PlayerInputEvent(player, RingDialGameTest.input(false, false, false, false, true)));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("§7You ease the pick back out.", ((PlayerMock) player).nextMessage());
        manager.onInput(new PlayerInputEvent(player, RingDialGameTest.input(false, false, false, false, false)));
        manager.onInput(new PlayerInputEvent(player, RingDialGameTest.input(false, false, false, false, true)));
        manager.giveUp(player, game);
        PlayerMock stranger = server.addPlayer();
        manager.onInput(new PlayerInputEvent(stranger, RingDialGameTest.input(true, false, false, false, false)));
        assertNull(stranger.nextMessage());
    }

    @Test
    void leavingMidPickIsAFailedAttemptButLeavingAfterASolveIsNot() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        LockMinigame game = manager.game(player.getUniqueId());
        manager.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertFalse(LockFreeze.isFrozen(player));
        verify(view).remove();

        lockPicks.clearCooldown(player.getUniqueId());
        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame solvedGame = manager.game(player.getUniqueId());
        solvedGame.solve(player);
        manager.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), solvedGame.targetId));
        assertEquals(0, solved.get());
        manager.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    @Test
    void joiningUndoesAFreezeLeftBehindByACrash() {
        player.getPersistentDataContainer().set(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT, 0.25f);
        player.setWalkSpeed(0f);
        manager.onJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
        assertEquals(0.25f, player.getWalkSpeed());
        assertFalse(LockFreeze.isFrozen(player));
        manager.onJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
        assertEquals(0.25f, player.getWalkSpeed());

        assertTrue(start(LockMinigameManager.Mode.DIAL));
        manager.onJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
        assertTrue(LockFreeze.isFrozen(player));
    }

    @Test
    void beingHurtLosesThePins() {
        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame game = manager.game(player.getUniqueId());
        EntityDamageEvent graze = mock(EntityDamageEvent.class);
        when(graze.getEntity()).thenReturn(player);
        when(graze.getFinalDamage()).thenReturn(0.0);
        manager.onDamage(graze);
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        EntityDamageEvent hit = mock(EntityDamageEvent.class);
        when(hit.getEntity()).thenReturn(player);
        when(hit.getFinalDamage()).thenReturn(2.0);
        manager.onDamage(hit);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("§cYou flinch and lose the pins.", ((PlayerMock) player).nextMessage());
        manager.onDamage(hit);
        EntityDamageEvent animal = mock(EntityDamageEvent.class);
        when(animal.getEntity()).thenReturn(mock(Entity.class));
        manager.onDamage(animal);
        PlayerMock bystander = server.addPlayer();
        EntityDamageEvent elsewhere = mock(EntityDamageEvent.class);
        when(elsewhere.getEntity()).thenReturn(bystander);
        when(elsewhere.getFinalDamage()).thenReturn(5.0);
        manager.onDamage(elsewhere);
        assertNull(bystander.nextMessage());
    }

    @Test
    void beingMovedAwayEndsThePickWithoutAPenalty() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        Location here = player.getLocation();
        manager.onTeleport(new PlayerTeleportEvent(player, here, here.clone().add(0.5, 0, 0.5)));
        assertTrue(manager.isPlaying(player.getUniqueId()));
        LockMinigame game = manager.game(player.getUniqueId());
        manager.onTeleport(new PlayerTeleportEvent(player, here, here.clone().add(5, 0, 0)));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals("§7You were pulled away from the lock.", ((PlayerMock) player).nextMessage());

        assertTrue(start(LockMinigameManager.Mode.GRID));
        World other = server.addSimpleWorld("elsewhere");
        manager.onTeleport(new PlayerTeleportEvent(player, here, new Location(other, 0, 64, 0)));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        manager.onTeleport(new PlayerTeleportEvent(player, here, here.clone().add(9, 0, 0)));
    }

    @Test
    void teleportingYourselfAwayIsAFailedAttempt() {
        Location here = player.getLocation();
        Location away = here.clone().add(20, 0, 0);
        manager.onCommand(new PlayerCommandPreprocessEvent(player, "/spawn"));

        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame game = manager.game(player.getUniqueId());
        assertFalse(game.ranCommand);
        manager.onCommand(new PlayerCommandPreprocessEvent(player, "/spawn"));
        assertTrue(game.ranCommand);
        manager.onTeleport(new PlayerTeleportEvent(player, here, away, PlayerTeleportEvent.TeleportCause.COMMAND));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals("§cThe pins slip back into place.", ((PlayerMock) player).nextMessage());
        assertEquals("§7You were pulled away from the lock.", ((PlayerMock) player).nextMessage());

        for (PlayerTeleportEvent.TeleportCause cause : new PlayerTeleportEvent.TeleportCause[] {
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT}) {
            lockPicks.clearCooldown(player.getUniqueId());
            assertTrue(start(LockMinigameManager.Mode.DIAL));
            manager.onTeleport(new PlayerTeleportEvent(player, here, away, cause));
            assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId), cause.name());
        }

        // A staff /tp is a command too, but not the thief's own, and a teleport after the lock gave way costs nothing.
        lockPicks.clearCooldown(player.getUniqueId());
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        manager.onTeleport(new PlayerTeleportEvent(player, here, away, PlayerTeleportEvent.TeleportCause.COMMAND));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        LockMinigame solved = manager.game(player.getUniqueId());
        solved.ranCommand = true;
        solved.outcome = LockMinigame.Outcome.SOLVED;
        manager.onTeleport(new PlayerTeleportEvent(player, here, away, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void aRingThiefIsHeldInPlaceButMayLookAroundAndStillFalls() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        Location from = new Location(world, 1, 64, 1, 0, 0);
        PlayerMoveEvent drift = new PlayerMoveEvent(player, from, new Location(world, 1.4, 64, 1, 30, 10));
        manager.onMove(drift);
        assertEquals(from.getX(), drift.getTo().getX());
        assertEquals(30f, drift.getTo().getYaw());
        assertEquals(10f, drift.getTo().getPitch());
        PlayerMoveEvent nudge = new PlayerMoveEvent(player, from, new Location(world, 1, 63.5, 1.5));
        manager.onMove(nudge);
        assertEquals(from.getZ(), nudge.getTo().getZ());
        assertEquals(63.5, nudge.getTo().getY());
        Location landing = new Location(world, 1, 63, 1);
        PlayerMoveEvent fall = new PlayerMoveEvent(player, from, landing);
        manager.onMove(fall);
        assertSame(landing, fall.getTo());
        PlayerMoveEvent look = new PlayerMoveEvent(player, from, new Location(world, 1, 64, 1, 90, 0));
        manager.onMove(look);
        assertEquals(90f, look.getTo().getYaw());

        assertTrue(start(LockMinigameManager.Mode.GRID));
        Location walk = new Location(world, 3, 64, 1);
        PlayerMoveEvent free = new PlayerMoveEvent(player, from, walk);
        manager.onMove(free);
        assertSame(walk, free.getTo());
        manager.cancel(player.getUniqueId());
        PlayerMoveEvent idle = new PlayerMoveEvent(player, from, walk);
        manager.onMove(idle);
        assertSame(walk, idle.getTo());
    }

    @Test
    void aRingThiefKeepsTheLockpickInHand() {
        assertTrue(start(LockMinigameManager.Mode.DIAL));
        var held = new org.bukkit.event.player.PlayerItemHeldEvent(player, 0, 3);
        manager.onHotbar(held);
        assertTrue(held.isCancelled());
        var drop = new org.bukkit.event.player.PlayerDropItemEvent(player, mock(org.bukkit.entity.Item.class));
        manager.onDrop(drop);
        assertTrue(drop.isCancelled());
        var swap = new org.bukkit.event.player.PlayerSwapHandItemsEvent(player, new ItemStack(Material.STICK), new ItemStack(Material.AIR));
        manager.onSwapHands(swap);
        assertTrue(swap.isCancelled());
        var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        manager.onInventoryClick(click);
        verify(click).setCancelled(true);
        var horse = mock(org.bukkit.entity.Entity.class);
        var mount = new EntityMountEvent(player, horse);
        manager.onMount(mount);
        assertTrue(mount.isCancelled());
        var pearl = new org.bukkit.event.player.PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                new ItemStack(Material.ENDER_PEARL), null, org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        manager.onInteract(pearl);
        assertEquals(org.bukkit.event.Event.Result.DENY, pearl.useItemInHand());
        var riderless = new EntityMountEvent(mock(org.bukkit.entity.Entity.class), horse);
        manager.onMount(riderless);
        assertFalse(riderless.isCancelled());

        assertTrue(start(LockMinigameManager.Mode.GRID));
        var mountFree = new EntityMountEvent(player, horse);
        manager.onMount(mountFree);
        assertFalse(mountFree.isCancelled());
        var eat = new org.bukkit.event.player.PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                new ItemStack(Material.BREAD), null, org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        manager.onInteract(eat);
        assertNotEquals(org.bukkit.event.Event.Result.DENY, eat.useItemInHand());
        var free = new org.bukkit.event.player.PlayerItemHeldEvent(player, 0, 3);
        manager.onHotbar(free);
        assertFalse(free.isCancelled());
        manager.cancel(player.getUniqueId());
        var idle = new org.bukkit.event.player.PlayerDropItemEvent(player, mock(org.bukkit.entity.Item.class));
        manager.onDrop(idle);
        assertFalse(idle.isCancelled());
        var swapIdle = new org.bukkit.event.player.PlayerSwapHandItemsEvent(player, new ItemStack(Material.STICK), new ItemStack(Material.AIR));
        manager.onSwapHands(swapIdle);
        assertFalse(swapIdle.isCancelled());
        var clickIdle = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        when(clickIdle.getWhoClicked()).thenReturn(player);
        manager.onInventoryClick(clickIdle);
        verify(clickIdle, never()).setCancelled(true);
    }

    @Test
    void penaltiesSnapOnePickFromAStackOrTheLastOneOrNoneInAnEmptyHand() {
        Parameters.chestMinigameFailBreakChance = 1.0;
        assertTrue(start(LockMinigameManager.Mode.GRID));
        LockMinigame game = manager.game(player.getUniqueId());
        player.getInventory().getItemInMainHand().setAmount(2);
        manager.penalise(player, game);
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        manager.penalise(player, game);
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        assertEquals("§cThe pins slip and your lockpick snaps!", ((PlayerMock) player).nextMessage());
        ((PlayerMock) player).nextMessage();
        manager.penalise(player, game);
        assertEquals("§cThe pins slip back into place.", ((PlayerMock) player).nextMessage());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }
}
