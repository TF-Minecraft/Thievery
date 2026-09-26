package net.tfminecraft.thievery.robbery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.player.*;
import net.tfminecraft.thievery.steal.*;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class RobberyManagerTest {
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Runnable> endCallbacks = new ArrayList<>();
    private final List<RobberySession> openedSessions = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PlayerInteractCooldown> interactions;
    private MockedStatic<GuildAccessCooldown> guilds;
    private MockedStatic<RobberyUtil> range;
    private MockedStatic<StealManager> stealStatics;
    private MockedStatic<PlayerSlotMap> slots;
    private MockedConstruction<PlayerTargetDataManager> targets;
    private MockedConstruction<RobberyStealReference> references;
    private RobberyManager manager;
    private Map<UUID, RobberySession> sessions;
    private StealManager steals;
    private Player robber;
    private Player victim;
    private PlayerTargetData targetData;
    private Inventory gui;

    private <T> MockedStatic<T> statics(Class<T> type) {
        MockedStatic<T> mock = mockStatic(type);
        statics.add(mock);
        return mock;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(null, 1, 2, 3));
        return player;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        robber = player("Robber");
        victim = player("Victim");
        targetData = new PlayerTargetData(victim.getUniqueId());
        gui = mock(Inventory.class);
        targets = mockConstruction(PlayerTargetDataManager.class, (mock, context) -> when(mock.load(victim.getUniqueId())).thenReturn(targetData));
        references = mockConstruction(RobberyStealReference.class, (mock, context) -> {
            openedSessions.add((RobberySession) context.arguments().get(0));
            endCallbacks.add((Runnable) context.arguments().get(1));
            when(mock.buildInventory(robber, victim)).thenReturn(gui);
        });
        bukkit = statics(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(robber.getUniqueId())).thenReturn(robber);
        bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskLater(any(Thievery.class), any(Runnable.class), eq(600L))).thenAnswer(call -> {
            timers.add(call.getArgument(1));
            return task;
        });
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        Thievery plugin = mock(Thievery.class);
        statics(Thievery.class).when(Thievery::getInstance).thenReturn(plugin);
        MockedStatic<RobberyLoader> config = statics(RobberyLoader.class);
        config.when(RobberyLoader::getMaxDistance).thenReturn(4.0);
        config.when(RobberyLoader::getBudget).thenReturn(30.0);
        config.when(RobberyLoader::getCooldownDays).thenReturn(3);
        config.when(RobberyLoader::getAcceptTimeoutSeconds).thenReturn(30);
        config.when(RobberyLoader::getDurationSeconds).thenReturn(120);
        steals = mock(StealManager.class);
        stealStatics = statics(StealManager.class);
        stealStatics.when(StealManager::getInstance).thenReturn(steals);
        range = statics(RobberyUtil.class);
        range.when(() -> RobberyUtil.isWithinRange(robber, victim, 4)).thenReturn(true);
        interactions = statics(PlayerInteractCooldown.class);
        interactions.when(() -> PlayerInteractCooldown.tryAcquire(robber.getUniqueId())).thenReturn(true);
        guilds = statics(GuildAccessCooldown.class);
        guilds.when(GuildAccessCooldown::today).thenReturn("2026-09-26");
        slots = statics(PlayerSlotMap.class);
        statics(EvilRpPlays.class);
        manager = new RobberyManager();
        // Observe the real pending session to advance its public deadline without sleeping.
        Field sessionField = RobberyManager.class.getDeclaredField("sessionsByRobber");
        sessionField.setAccessible(true);
        sessions = (Map<UUID, RobberySession>) sessionField.get(manager);
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        references.close();
        targets.close();
    }

    private void click(Entity clicked) {
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(robber);
        when(event.getRightClicked()).thenReturn(clicked);
        manager.onPlayerInteractEntity(event);
    }

    private RobberySession request() {
        manager.startAwaitingTarget(robber);
        click(victim);
        return sessions.get(robber.getUniqueId());
    }

    private void activate() {
        request();
        assertTrue(manager.acceptRobbery(victim));
    }

    private void quit(Player player) {
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        manager.onPlayerQuit(event);
    }

    @Test
    void selectionIgnoresUnarmedNonPlayerAndThrottledInteractions() {
        click(victim);
        assertFalse(manager.isAwaitingTarget(robber.getUniqueId()));
        manager.startAwaitingTarget(robber);
        assertTrue(manager.isAwaitingTarget(robber.getUniqueId()));
        verify(robber).sendMessage(contains("within 4.0 blocks"));
        click(mock(Entity.class));
        interactions.verifyNoInteractions();
        interactions.when(() -> PlayerInteractCooldown.tryAcquire(robber.getUniqueId())).thenReturn(false);
        click(victim);
        assertTrue(sessions.isEmpty());
        verifyNoInteractions(targets.constructed().getFirst());
    }

    @Test
    void selfTargetDistanceAndGuildCooldownRejectWithoutConsumingSelection() {
        manager.startAwaitingTarget(robber);
        click(robber);
        verify(robber).sendMessage(contains("cannot rob yourself"));
        range.when(() -> RobberyUtil.isWithinRange(robber, victim, 4)).thenReturn(false);
        click(victim);
        verify(robber).sendMessage(contains("too far away"));
        range.when(() -> RobberyUtil.isWithinRange(robber, victim, 4)).thenReturn(true);
        guilds.when(() -> GuildAccessCooldown.isOnCooldown(targetData.getRobberyAccessMap(), robber, 3)).thenReturn(true);
        guilds.when(() -> GuildAccessCooldown.getMillisRemaining(targetData.getRobberyAccessMap(), robber, 3)).thenReturn(120_000L);
        guilds.when(() -> GuildAccessCooldown.formatRemaining(120_000)).thenReturn("2 minutes");
        click(victim);
        verify(robber).sendMessage(contains("wait 2 minutes"));
        assertTrue(manager.isAwaitingTarget(robber.getUniqueId()));
        assertTrue(sessions.isEmpty());
        verify(targets.constructed().getFirst(), never()).save(any());
    }

    @Test
    void validRequestCreatesPendingSessionAndClickableConsentWithoutRestraintOrCooldown() {
        long before = System.currentTimeMillis();
        RobberySession session = request();
        assertNotNull(session);
        assertEquals(RobberySession.State.PENDING_ACCEPT, session.getState());
        assertEquals(victim.getUniqueId(), session.getVictimId());
        assertEquals(30, session.getBudget().getCapacity());
        assertEquals(PlayerSlotMap.TOTAL_LOGICAL_SLOTS, session.getLayout().getLogicalSlotCount());
        assertTrue(session.getAcceptDeadlineMs() >= before + 30_000);
        assertFalse(manager.isAwaitingTarget(robber.getUniqueId()));
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        assertFalse(manager.isRobberInActiveSession(robber.getUniqueId()));
        assertEquals(1, timers.size());
        verify(targets.constructed().getFirst(), never()).save(any());
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(victim).sendMessage(message.capture());
        Component accept = message.getValue().children().get(1);
        assertEquals(ClickEvent.runCommand("/robbery accept"), accept.clickEvent());
        assertNotNull(accept.hoverEvent());
        verify(robber).sendMessage(contains("Waiting for Victim"));
    }

    @Test
    void acceptanceRequiresPendingUnexpiredRequestAndAnAvailableRobber() {
        assertFalse(manager.acceptRobbery(victim));
        verify(victim).sendMessage(contains("no pending robbery request"));
        request().setAcceptDeadlineMs(0);
        assertFalse(manager.acceptRobbery(victim));
        verify(victim).sendMessage(contains("request has expired"));
        assertTrue(sessions.isEmpty());
        request();
        bukkit.when(() -> Bukkit.getPlayer(robber.getUniqueId())).thenReturn(null);
        assertFalse(manager.acceptRobbery(victim));
        assertTrue(sessions.isEmpty());
        request();
        bukkit.when(() -> Bukkit.getPlayer(robber.getUniqueId())).thenReturn(robber);
        when(robber.isOnline()).thenReturn(false);
        assertFalse(manager.acceptRobbery(victim));
        verify(victim, times(2)).sendMessage(contains("no longer available"));
        assertTrue(sessions.isEmpty());
    }

    @Test
    void outOfRangeAcceptanceCanBeRetriedAndSuccessRecordsCooldownAndOpensSession() {
        RobberySession session = request();
        range.when(() -> RobberyUtil.isWithinRange(robber, victim, 4)).thenReturn(false);
        assertFalse(manager.acceptRobbery(victim));
        assertSame(session, sessions.get(robber.getUniqueId()));
        verify(victim).sendMessage(contains("too far from the robber"));
        range.when(() -> RobberyUtil.isWithinRange(robber, victim, 4)).thenReturn(true);
        long before = System.currentTimeMillis();
        assertTrue(manager.acceptRobbery(victim));
        assertEquals(RobberySession.State.ACTIVE, session.getState());
        assertTrue(session.getActiveEndMs() >= before + 120_000);
        assertTrue(manager.isVictimRestrained(victim.getUniqueId()));
        assertTrue(manager.isRobberInActiveSession(robber.getUniqueId()));
        assertSame(session, openedSessions.getFirst());
        guilds.verify(() -> GuildAccessCooldown.recordAccess(targetData.getRobberyAccessMap(), robber.getUniqueId(), "2026-09-26"));
        verify(targets.constructed().getFirst()).save(targetData);
        verify(steals).openSession(robber, references.constructed().getFirst(), gui);
        assertFalse(manager.acceptRobbery(victim));
    }

    @Test
    void pendingTimerExpiresOnlyAfterDeadlineAndNotifiesBothOnlineParticipants() {
        RobberySession session = request();
        timers.getFirst().run();
        assertSame(session, sessions.get(robber.getUniqueId()));
        session.setAcceptDeadlineMs(0);
        timers.getFirst().run();
        assertTrue(sessions.isEmpty());
        verify(robber).sendMessage(contains("request timed out"));
        verify(victim).sendMessage(contains("request has expired"));
        timers.getFirst().run();
        verify(robber, times(1)).sendMessage(contains("request timed out"));
    }

    @Test
    void staleTimeoutCannotEndAReplacementRequestOrAnActiveSession() {
        RobberySession old = request();
        old.setAcceptDeadlineMs(0);
        RobberySession replacement = request();
        timers.getFirst().run();
        assertSame(replacement, sessions.get(robber.getUniqueId()));
        assertTrue(manager.acceptRobbery(victim));
        replacement.setAcceptDeadlineMs(0);
        timers.get(1).run();
        assertTrue(manager.isVictimRestrained(victim.getUniqueId()));
        assertTrue(manager.isRobberInActiveSession(robber.getUniqueId()));
    }

    @Test
    void expiredRequestSilentlyCleansUpOfflineParticipants() {
        request().setAcceptDeadlineMs(0);
        when(robber.isOnline()).thenReturn(false);
        when(victim.isOnline()).thenReturn(false);
        timers.getFirst().run();
        assertTrue(sessions.isEmpty());
        verify(robber, never()).sendMessage(contains("request timed out"));
        verify(victim, never()).sendMessage(contains("request has expired"));
    }

    @Test
    void endCallbackReleasesVictimAndNotifiesThemWhileRestartReleasesWithoutNotification() {
        activate();
        endCallbacks.getFirst().run();
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        assertFalse(manager.isRobberInActiveSession(robber.getUniqueId()));
        verify(victim).sendMessage(contains("robbery has ended"));
        activate();
        manager.startAwaitingTarget(robber);
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        assertTrue(manager.isAwaitingTarget(robber.getUniqueId()));
        verify(victim, times(1)).sendMessage(contains("robbery has ended"));
    }

    @Test
    void endCallbackDoesNotMessageVictimWhoIsUnavailableOrOffline() {
        activate();
        bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(null);
        endCallbacks.getFirst().run();
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
        activate();
        when(victim.isOnline()).thenReturn(false);
        endCallbacks.get(1).run();
        verify(victim, never()).sendMessage(contains("robbery has ended"));
    }

    @Test
    void activeParticipantsCannotDropOrUseOtherInventoriesButRobberyGuiRemainsUsable() {
        activate();
        for (Player participant : List.of(robber, victim)) {
            PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
            when(drop.getPlayer()).thenReturn(participant);
            manager.onPlayerDropItem(drop);
            verify(drop).setCancelled(true);
            InventoryClickEvent click = inventoryClick(participant);
            manager.onInventoryClick(click);
            verify(click).setCancelled(true);
        }
        Player other = player("other");
        PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
        when(drop.getPlayer()).thenReturn(other);
        manager.onPlayerDropItem(drop);
        verify(drop, never()).setCancelled(anyBoolean());
        InventoryClickEvent ordinary = inventoryClick(other);
        manager.onInventoryClick(ordinary);
        verify(ordinary, never()).setCancelled(anyBoolean());
        InventoryClickEvent nonPlayer = inventoryClick(mock(HumanEntity.class));
        manager.onInventoryClick(nonPlayer);
        verify(nonPlayer, never()).setCancelled(anyBoolean());
        InventoryClickEvent allowed = inventoryClick(robber);
        StealGuiHolder robberyHolder = new StealGuiHolder(robber.getUniqueId(), StealGuiHolder.Kind.ROBBERY);
        stealStatics.when(() -> StealManager.getStealGuiHolder(allowed.getView().getTopInventory()))
                .thenReturn(robberyHolder);
        manager.onInventoryClick(allowed);
        verify(allowed, never()).setCancelled(anyBoolean());
        StealGuiHolder chestHolder = new StealGuiHolder(robber.getUniqueId(), StealGuiHolder.Kind.CHEST);
        stealStatics.when(() -> StealManager.getStealGuiHolder(allowed.getView().getTopInventory())).thenReturn(chestHolder);
        manager.onInventoryClick(allowed);
        verify(allowed).setCancelled(true);
    }

    private InventoryClickEvent inventoryClick(HumanEntity actor) {
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(click.getView()).thenReturn(view);
        when(click.getWhoClicked()).thenReturn(actor);
        return click;
    }

    @Test
    void restraintBlocksVictimEquipmentChangesAndDamageButNotTheRobber() {
        activate();
        for (Player actor : List.of(victim, robber)) {
            PlayerSwapHandItemsEvent swap = mock(PlayerSwapHandItemsEvent.class);
            when(swap.getPlayer()).thenReturn(actor);
            PlayerItemHeldEvent held = mock(PlayerItemHeldEvent.class);
            when(held.getPlayer()).thenReturn(actor);
            EntityDamageEvent damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(actor);
            manager.onSwapHandItems(swap);
            manager.onItemHeld(held);
            manager.onEntityDamage(damage);
            if (actor == victim) {
                verify(swap).setCancelled(true);
                verify(held).setCancelled(true);
                verify(damage).setCancelled(true);
            } else {
                verify(swap, never()).setCancelled(anyBoolean());
                verify(held, never()).setCancelled(anyBoolean());
                verify(damage, never()).setCancelled(anyBoolean());
            }
        }
        EntityDamageEvent nonPlayer = mock(EntityDamageEvent.class);
        when(nonPlayer.getEntity()).thenReturn(mock(Entity.class));
        manager.onEntityDamage(nonPlayer);
        verify(nonPlayer, never()).setCancelled(anyBoolean());
    }

    @Test
    void restrainedVictimCanLookAroundButCannotChangeAnyPositionCoordinate() {
        activate();
        Location from = new Location(null, 1, 2, 3, 0, 0);
        for (Location to : List.of(new Location(null, 2, 2, 3), new Location(null, 1, 3, 3), new Location(null, 1, 2, 4))) {
            PlayerMoveEvent event = move(victim, from, to);
            manager.onPlayerMove(event);
            verify(event).setCancelled(true);
        }
        PlayerMoveEvent look = move(victim, from, new Location(null, 1, 2, 3, 90, 45));
        manager.onPlayerMove(look);
        verify(look, never()).setCancelled(anyBoolean());
        PlayerMoveEvent absentDestination = move(victim, from, null);
        manager.onPlayerMove(absentDestination);
        verify(absentDestination, never()).setCancelled(anyBoolean());
        PlayerMoveEvent robberMove = move(robber, from, new Location(null, 3, 4, 5));
        manager.onPlayerMove(robberMove);
        verify(robberMove, never()).setCancelled(anyBoolean());
    }

    private PlayerMoveEvent move(Player actor, Location from, Location to) {
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(actor);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        return event;
    }

    @Test
    void quittingRobberReleasesVictimWhileQuittingActiveVictimDropsInventory() {
        activate();
        quit(robber);
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        assertFalse(manager.isRobberInActiveSession(robber.getUniqueId()));
        slots.verifyNoInteractions();
        activate();
        quit(victim);
        slots.verify(() -> PlayerSlotMap.dropAllExceptIgnored(victim));
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
        assertFalse(manager.isRobberInActiveSession(robber.getUniqueId()));
        interactions.verify(() -> PlayerInteractCooldown.clear(victim.getUniqueId()));
        manager.startAwaitingTarget(robber);
        quit(robber);
        assertFalse(manager.isAwaitingTarget(robber.getUniqueId()));
    }

    @Test
    void unrelatedPlayerQuittingDoesNotEndPendingRobberyOrDropTheirInventory() {
        RobberySession pending = request();
        Player other = player("Other");
        quit(other);
        assertSame(pending, sessions.get(robber.getUniqueId()));
        slots.verifyNoInteractions();
        assertFalse(manager.acceptRobbery(other));
        quit(victim);
        slots.verifyNoInteractions();
        assertFalse(manager.isVictimRestrained(victim.getUniqueId()));
    }
}
