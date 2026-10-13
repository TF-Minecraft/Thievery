package net.tfminecraft.thievery.steal;

import static net.tfminecraft.thievery.testsupport.FactionFixture.co;
import net.tfminecraft.thievery.owner.LockOwner;

import net.tfminecraft.thievery.testsupport.FactionFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.tfminecraft.tlibs.character.*;
import org.bukkit.plugin.ServicePriority;
import java.util.Set;
import java.util.UUID;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.LockpickTargetCache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.door.*;
import net.tfminecraft.thievery.player.LockpickDefinition;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class DisplayStealManagerTest {
    private FactionFixture stage5Factions;
    @BeforeEach void factionReady() { stage5Factions = new FactionFixture(); }
    @AfterEach void closeFactionFixture() { stage5Factions.close(); }

    private DisplayStealManager manager;
    private LockPickManager picks;
    private EntityLockDataManager store;
    private EntityLockData lock;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack held;
    private ArmorStand stand;
    private ItemFrame frame;
    private LockpickDefinition definition;
    private PlayerData playerData;
    private List<DisplayLoot.DisplaySlot> lootSlots;
    private List<String> oldTraits;
    private Set<EntityType> oldTypes;
    private boolean oldDebug, oldOwnerRequired;
    private MockedConstruction<EntityLockDataManager> stores;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<ToolResolver> tools;
    private MockedStatic<LockAccess> access;
    private MockedStatic<FactionLockTutorial> tutorial;
    private MockedStatic<DisplayLoot> loot;
    private MockedStatic<ClueChecker> clues;
    private MockedStatic<ClueDropper> drops;
    private MockedStatic<Database> database;
    private MockedStatic<RiskCalculator> risk;

    @BeforeEach
    void setUp() {
        var server = MockBukkit.mock();
        World world = server.addSimpleWorld("display");
        oldTraits = Cache.traits; oldTypes = Parameters.lockableEntityTypes;
        oldDebug = Cache.debugAllowOwnChest; oldOwnerRequired = Cache.requireOwnerOnline;
        Cache.traits = List.of(); Cache.debugAllowOwnChest = false; Cache.requireOwnerOnline = false;
        Parameters.lockableEntityTypes = EnumSet.of(EntityType.ARMOR_STAND, EntityType.ITEM_FRAME);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        held = new ItemStack(Material.STICK);
        when(inventory.getItemInMainHand()).thenReturn(held);
        stand = mock(ArmorStand.class);
        when(stand.getType()).thenReturn(EntityType.ARMOR_STAND);
        when(stand.getUniqueId()).thenReturn(UUID.randomUUID());
        when(stand.isValid()).thenReturn(true);
        when(stand.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        frame = mock(ItemFrame.class);
        when(frame.getType()).thenReturn(EntityType.ITEM_FRAME);
        when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
        when(frame.isValid()).thenReturn(true);
        when(frame.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        stores = mockConstruction(EntityLockDataManager.class);
        picks = mock(LockPickManager.class);
        manager = new DisplayStealManager(picks);
        store = stores.constructed().getFirst();
        lock = new EntityLockData(stand.getUniqueId(),co( UUID.randomUUID()));
        lock.setLockState(LockState.PRIVATE);
        when(store.load(any())).thenReturn(lock);
        plugin = mockStatic(Thievery.class);
        PlayerManager players = mock(PlayerManager.class);
        playerData = mock(PlayerData.class);
        when(players.get(player.getUniqueId())).thenReturn(playerData);
        plugin.when(Thievery::getPlayerManager).thenReturn(players);
        tools = mockStatic(ToolResolver.class);
        definition = mock(LockpickDefinition.class);
        when(definition.getStrength()).thenReturn(0.7);
        when(definition.getCapacity()).thenReturn(40);
        tools.when(() -> ToolResolver.resolveLockpick(held)).thenReturn(definition);
        tools.when(() -> ToolResolver.getLockpickStrength(held)).thenReturn(0.7);
        access = mockStatic(LockAccess.class);
        access.when(() -> LockAccess.isOwner(any(), any())).thenCallRealMethod();
        tutorial = mockStatic(FactionLockTutorial.class);
        DisplayLoot.DisplaySlot displaySlot = mock(DisplayLoot.DisplaySlot.class);
        ItemStack displayedItem = new ItemStack(Material.DIAMOND);
        when(displaySlot.get()).thenReturn(displayedItem);
        lootSlots = List.of(displaySlot);
        loot = mockStatic(DisplayLoot.class);
        loot.when(() -> DisplayLoot.hasAnything(anyList(), eq(playerData), eq(40.0))).thenReturn(true);
        clues = mockStatic(ClueChecker.class);
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(true);
        drops = mockStatic(ClueDropper.class);
        database = mockStatic(Database.class);
        risk = mockStatic(RiskCalculator.class);
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(15);
    }

    @AfterEach
    void tearDown() {
        risk.close(); database.close(); drops.close(); clues.close(); loot.close(); tutorial.close();
        access.close(); tools.close(); plugin.close(); stores.close();
        Cache.traits = oldTraits; Parameters.lockableEntityTypes = oldTypes;
        Cache.debugAllowOwnChest = oldDebug; Cache.requireOwnerOnline = oldOwnerRequired;
        MockBukkit.unmock();
    }

    @Test
    void onlyConfiguredVisibleDisplaysAreLockable() {
        assertFalse(DisplayStealManager.isLockableDisplay(null));
        assertTrue(DisplayStealManager.isLockableDisplay(stand));
        assertTrue(DisplayStealManager.isLockableDisplay(frame));
        when(stand.isInvisible()).thenReturn(true);
        assertFalse(DisplayStealManager.isLockableDisplay(stand));
        Entity zombie = mock(Entity.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        assertFalse(DisplayStealManager.isLockableDisplay(zombie));
    }

    @Test
    void staffBypassCanNotifyButOrdinaryAccessDoesNotNeedPermission() {
        assertFalse(DisplayStealManager.canUse(player, lock.getOwner(), LockState.PRIVATE, true));
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        assertTrue(DisplayStealManager.canUse(player, lock.getOwner(), LockState.PRIVATE, false));
        verify(player, never()).sendMessage(anyString());
        assertTrue(DisplayStealManager.canUse(player, lock.getOwner(), LockState.PRIVATE, true));
        verify(player).sendMessage(contains("Bypassing lock"));
        access.when(() -> LockAccess.canAccess(player, lock.getOwner(), LockState.PRIVATE)).thenReturn(true);
        when(player.hasPermission("thievery.admin")).thenReturn(false);
        assertTrue(DisplayStealManager.canUse(player, lock.getOwner(), LockState.PRIVATE, true));
        assertEquals("Faction", DisplayStealManager.formatLockState(LockState.FACTION));
    }

    @Test
    void placementsClaimUnownedDisplaysAndNeverOverwriteAnOwner() {
        EntityPlaceEvent placed = mock(EntityPlaceEvent.class);
        when(placed.getEntity()).thenReturn(stand);
        when(placed.getPlayer()).thenReturn(player);
        manager.onEntityPlace(placed);
        verify(store, never()).save(any());
        lock.setOwner(co(null));
        manager.onEntityPlace(placed);
        assertEquals(co(player.getUniqueId()), lock.getOwner());
        assertEquals(LockState.DEFAULT, lock.getLockState());
        verify(store).save(lock);
        tutorial.verify(() -> FactionLockTutorial.onLockState(player, LockState.DEFAULT));
        lock.setOwner(co(null));
        HangingPlaceEvent hanging = mock(HangingPlaceEvent.class);
        when(hanging.getEntity()).thenReturn(frame);
        when(hanging.getPlayer()).thenReturn(player);
        manager.onHangingPlace(hanging);
        assertEquals(co(player.getUniqueId()), lock.getOwner());
        verify(store, times(2)).save(lock);
        manager.onHangingPlace(hanging);
        verify(store, times(2)).save(lock);
        when(hanging.getPlayer()).thenReturn(null);
        manager.onHangingPlace(hanging);
        when(placed.getPlayer()).thenReturn(null);
        manager.onEntityPlace(placed);
        verify(store, times(2)).save(lock);
    }

    @Test
    void sneakingDamageClaimsRotatesOrRejectsBasedOnOwner() {
        EntityDamageByEntityEvent event = damage(stand, player);
        when(player.isSneaking()).thenReturn(true);
        lock.setOwner(co(null));
        manager.onEntityDamage(event);
        assertEquals(co(player.getUniqueId()), lock.getOwner());
        assertEquals(LockState.DEFAULT, lock.getLockState());
        try (MockedStatic<LockStateLog> log = mockStatic(LockStateLog.class)) {
            manager.onEntityDamage(event);
            log.verify(() -> LockStateLog.record(player, stand.getLocation().getBlock().getRelative(BlockFace.DOWN).getLocation(),
                    LockState.PRIVATE, false));
        }
        assertEquals(LockState.PRIVATE, lock.getLockState());
        verify(store, times(2)).save(lock);
        lock.setOwner(co(UUID.randomUUID()));
        manager.onEntityDamage(event);
        verify(store, times(2)).save(lock);
        verify(player).sendMessage(contains("Only the owner can change this lock"));
        verify(event, times(3)).setCancelled(true);
    }

    @Test
    void ordinaryDamageAndInteractionRespectAccessAndIgnoreUnrelatedEvents() {
        EntityDamageByEntityEvent event = damage(stand, player);
        manager.onEntityDamage(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage("§cIt's locked. Only its owner holds the key.");
        EntityDamageByEntityEvent nonplayer = damage(stand, mock(Entity.class));
        manager.onEntityDamage(nonplayer);
        verify(nonplayer, never()).setCancelled(true);
        EntityDamageByEntityEvent hanging = damage(frame, player);
        manager.onEntityDamage(hanging);
        verify(hanging, never()).setCancelled(true);
        PlayerInteractEntityEvent click = mock(PlayerInteractEntityEvent.class);
        when(click.getPlayer()).thenReturn(player);
        when(click.getRightClicked()).thenReturn(frame);
        manager.onInteractEntity(click);
        verify(click).setCancelled(true);
        when(click.getRightClicked()).thenReturn(stand);
        manager.onInteractEntity(click);
        verify(click, times(1)).setCancelled(true);
        PlayerArmorStandManipulateEvent manipulate = mock(PlayerArmorStandManipulateEvent.class);
        when(manipulate.getPlayer()).thenReturn(player);
        when(manipulate.getRightClicked()).thenReturn(stand);
        manager.onArmorStandManipulate(manipulate);
        verify(manipulate).setCancelled(true);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        clearInvocations(event, click, manipulate);
        manager.onEntityDamage(event);
        manager.onArmorStandManipulate(manipulate);
        when(click.getRightClicked()).thenReturn(frame);
        manager.onInteractEntity(click);
        verify(event, never()).setCancelled(true);
        verify(manipulate, never()).setCancelled(true);
        verify(click, never()).setCancelled(true);
    }

    @Test
    void hangingBreakCanToggleOwnershipOrDenyRemovalAndDeathCleansLocks() {
        HangingBreakByEntityEvent event = mock(HangingBreakByEntityEvent.class);
        when(event.getEntity()).thenReturn(frame);
        when(event.getRemover()).thenReturn(player);
        when(frame.getAttachedFace()).thenReturn(BlockFace.NORTH);
        manager.onHangingBreak(event);
        verify(event).setCancelled(true);
        when(player.isSneaking()).thenReturn(true);
        lock.setOwner(co(null));
        manager.onHangingBreak(event);
        assertEquals(co(player.getUniqueId()), lock.getOwner());
        manager.onHangingBreak(event);
        assertEquals(LockState.PRIVATE, lock.getLockState());
        when(event.getRemover()).thenReturn(mock(Entity.class));
        manager.onHangingBreak(event);
        verify(store, times(2)).save(lock);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(stand);
        manager.onLockableDeath(death);
        verify(store).delete(stand.getUniqueId());
        HangingBreakEvent removed = mock(HangingBreakEvent.class);
        when(removed.getEntity()).thenReturn(frame);
        manager.onHangingRemoved(removed);
        verify(store).delete(frame.getUniqueId());
    }

    @Test
    void staffCycleForeignDisplayLockStateWithoutTakingOwnership() {
        HangingBreakByEntityEvent event = mock(HangingBreakByEntityEvent.class);
        when(event.getEntity()).thenReturn(frame);
        when(event.getRemover()).thenReturn(player);
        when(player.isSneaking()).thenReturn(true);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        LockOwner placer = lock.getOwner();
        when(frame.getAttachedFace()).thenReturn(BlockFace.NORTH);
        try (MockedStatic<LockStateLog> log = mockStatic(LockStateLog.class);
                var names = mockStatic(net.tfminecraft.thievery.owner.LockOwnerNames.class)) {
            names.when(() -> net.tfminecraft.thievery.owner.LockOwnerNames.staffLabel(placer)).thenReturn("placer");
            manager.onHangingBreak(event);
            log.verify(() -> LockStateLog.record(player, frame.getLocation().getBlock().getRelative(BlockFace.NORTH).getLocation(),
                    LockState.GUILD, true));
        }
        verify(event).setCancelled(true);
        assertEquals(LockState.GUILD, lock.getLockState());
        assertEquals(placer, lock.getOwner());
        verify(store).save(lock);
        verify(player).sendMessage("§eStaff override: lock owned by placer is now Guild.");
        tutorial.verifyNoInteractions();
    }

    @Test
    void lockpickStartRejectsInvalidTargetsExistingAccessMissingCluesCooldownAndWeakTools() {
        List<DisplayLoot.DisplaySlot> slots = lootSlots;
        manager.handleLockpick(null, stand, lock.getOwner(), LockState.PRIVATE, slots);
        manager.handleLockpick(player, null, lock.getOwner(), LockState.PRIVATE, slots);
        when(stand.isValid()).thenReturn(false);
        manager.handleLockpick(player, stand, lock.getOwner(), LockState.PRIVATE, slots);
        verifyNoInteractions(picks);
        when(stand.isValid()).thenReturn(true);
        access.when(() -> LockAccess.canAccess(player, lock.getOwner(), LockState.PRIVATE)).thenReturn(true);
        select(slots);
        verify(player).sendMessage(contains("already open to you"));
        access.when(() -> LockAccess.canAccess(player, lock.getOwner(), LockState.PRIVATE)).thenReturn(false);
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(false);
        select(slots);
        clues.verify(() -> ClueChecker.sendInsufficientCluesMessage(player));
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(true);
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());
        when(picks.isOnCooldown(player.getUniqueId(), target)).thenReturn(true);
        when(picks.getCooldownRemainingSeconds(player.getUniqueId(), target)).thenReturn(15L);
        select(slots);
        verify(player).sendMessage(contains("wait 15s"));
        when(picks.isOnCooldown(player.getUniqueId(), target)).thenReturn(false);
        tools.when(() -> ToolResolver.resolveLockpick(held)).thenReturn(null);
        select(slots);
        tools.when(() -> ToolResolver.resolveLockpick(held)).thenReturn(definition);
        when(definition.getStrength()).thenReturn(0.01);
        select(slots);
        verify(player).sendMessage(contains("too weak"));
        when(definition.getStrength()).thenReturn(0.7);
        loot.when(() -> DisplayLoot.hasAnything(slots, playerData, 40)).thenReturn(false);
        select(slots);
        verify(player).sendMessage(contains("Nothing here"));
        verify(picks, never()).startSession(any(), any(), any(), any(), anyDouble(), anyInt(), anyDouble(), any());
    }

    @Test
    void validDisplayPickRecordsRiskAndStartsEntitySession() {
        List<DisplayLoot.DisplaySlot> slots = lootSlots;
        select(slots);
        verify(playerData).addRiskGain(15, 0.7, RiskSource.DOOR);
        database.verify(() -> Database.savePlayerData(playerData));
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());
        verify(picks).startSession(eq(player), any(DoorLockpick.EntityProximityAnchor.class),
                eq(LockPickManager.SessionKind.DISPLAY), eq(target),
                eq(Parameters.displayLockStrength * (1 - 0.7 * Parameters.lockpickMaxReduction)), eq(15), eq(0.7), isNull());
        Cache.debugAllowOwnChest = true;
        access.when(() -> LockAccess.canAccess(player, lock.getOwner(), LockState.PRIVATE)).thenReturn(true);
        select(slots);
        verify(playerData, times(2)).addRiskGain(15, 0.7, RiskSource.DOOR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offlineOwnerRequiresCachedWindowAndWarnsBeforeStarting(boolean cachedWindow) {
        Cache.requireOwnerOnline = true;
        UUID ownerId = lock.getOwner().id();
        String targetKey = "player:" + ownerId;
        String sessionTarget = DoorLockpick.entityTargetId(stand.getUniqueId());
        try (var windows = mockStatic(LockpickTargetCache.class)) {
            windows.when(() -> LockpickTargetCache.isActive(targetKey)).thenReturn(cachedWindow);
            windows.when(() -> LockpickTargetCache.getRemainingMs(targetKey)).thenReturn(125_000L);

            select(lootSlots);

            if (cachedWindow) {
                verify(player).sendMessage(contains("owners are away - 2m 5s remaining"));
                verify(playerData).addRiskGain(15, 0.7, RiskSource.DOOR);
                database.verify(() -> Database.savePlayerData(playerData));
                verify(picks).startSession(eq(player), any(DoorLockpick.EntityProximityAnchor.class),
                        eq(LockPickManager.SessionKind.DISPLAY), eq(sessionTarget),
                        eq(Parameters.displayLockStrength * (1 - 0.7 * Parameters.lockpickMaxReduction)),
                        eq(15), eq(0.7), isNull());
            } else {
                verify(player).sendMessage(contains("Cannot lockpick - this lock's owners are away."));
                verify(picks, never()).startSession(any(), any(), any(), any(), anyDouble(), anyInt(), anyDouble(), any());
                verifyNoInteractions(playerData);
                database.verifyNoInteractions();
            }
        }
    }

    @Test
    void configuredTraitsRequireAReadyCharacterWithMatchingTrait() {
        Cache.traits = List.of("thief");
        select(lootSlots);
        verify(player, never()).sendMessage(anyString());
        var provider = mock(CharacterIdentity.class);
        var providerPlugin = MockBukkit.createMockPlugin();
        MockBukkit.getMock().getServicesManager().register(CharacterIdentity.class, provider, providerPlugin, ServicePriority.Normal);
        select(lootSlots);
        verify(player, never()).sendMessage(anyString());
        var session = new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 1);
        when(provider.currentSession(player)).thenReturn(Optional.of(session));
        when(provider.isCurrent(session)).thenReturn(true);
        when(provider.info(session.ref().characterId())).thenReturn(Optional.of(
                new CharacterInfo(session.ref(), "Scholar", CharacterStatus.ALIVE, null, Set.of(), 0, null)));
        select(lootSlots);
        verify(player).sendMessage(contains("lack the training to pick locks"));
        verify(picks, never()).startSession(any(), any(), any(), any(), anyDouble(), anyInt(), anyDouble(), any());
        when(provider.info(session.ref().characterId())).thenReturn(Optional.of(
                new CharacterInfo(session.ref(), "Thief", CharacterStatus.ALIVE, null, Set.of("scholar", "thief"), 0, null)));
        select(lootSlots);
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());
        verify(picks).startSession(eq(player), any(DoorLockpick.EntityProximityAnchor.class),
                eq(LockPickManager.SessionKind.DISPLAY), eq(target),
                eq(Parameters.displayLockStrength * (1 - 0.7 * Parameters.lockpickMaxReduction)),
                eq(15), eq(0.7), isNull());
        verify(playerData).addRiskGain(15, 0.7, RiskSource.DOOR);
        database.verify(() -> Database.savePlayerData(playerData));
    }

    @Test
    void placingUnconfiguredDecorationsDoesNotClaimOrCancelThem() {
        Parameters.lockableEntityTypes = EnumSet.of(EntityType.ITEM_FRAME);
        var server = MockBukkit.getMock();
        var decoration = new org.mockbukkit.mockbukkit.entity.ArmorStandMock(server, UUID.randomUUID());
        var painting = new org.mockbukkit.mockbukkit.entity.PaintingMock(server, UUID.randomUUID());
        Block support = player.getLocation().getBlock();
        support.setType(Material.STONE);
        EntityPlaceEvent placed = new EntityPlaceEvent(decoration, player, support,
                BlockFace.UP, EquipmentSlot.HAND);
        HangingPlaceEvent hung = new HangingPlaceEvent(painting, player, support,
                BlockFace.NORTH, EquipmentSlot.HAND, new ItemStack(Material.PAINTING));

        manager.onEntityPlace(placed);
        manager.onHangingPlace(hung);
        EntityDamageByEntityEvent damage = damage(painting, player);
        manager.onEntityDamage(damage);

        assertFalse(placed.isCancelled());
        assertFalse(hung.isCancelled());
        verify(damage, never()).setCancelled(true);
        verifyNoInteractions(store, picks);
        verify(player, never()).sendMessage(anyString());
        tutorial.verifyNoInteractions();
    }

    @Test
    void hangingDamageDefersLockRotationToHangingBreakExactlyOnce() {
        var realFrame = new org.mockbukkit.mockbukkit.entity.ItemFrameMock(MockBukkit.getMock(), UUID.randomUUID());
        realFrame.setFacingDirection(BlockFace.SOUTH, true);
        EntityLockData frameLock = new EntityLockData(realFrame.getUniqueId(),co( player.getUniqueId()));
        frameLock.setLockState(LockState.PRIVATE);
        when(store.load(realFrame.getUniqueId())).thenReturn(frameLock);
        when(player.isSneaking()).thenReturn(true);
        EntityDamageByEntityEvent damage = damage(realFrame, player);

        manager.onEntityDamage(damage);

        verify(damage, never()).setCancelled(true);
        verifyNoInteractions(store);
        assertEquals(LockState.PRIVATE, frameLock.getLockState());
        HangingBreakByEntityEvent broken = new HangingBreakByEntityEvent(realFrame, player);
        manager.onHangingBreak(broken);

        assertTrue(broken.isCancelled());
        assertEquals(LockState.GUILD, frameLock.getLockState());
        verify(store).save(frameLock);
        verify(store, never()).delete(any());
        tutorial.verify(() -> FactionLockTutorial.onLockState(player, LockState.GUILD));
    }

    @Test
    void ordinaryFrameInteractionDeniesStrangersWhileAuthorizedRemovalKeepsLockUntilRemoved() {
        var realFrame = new org.mockbukkit.mockbukkit.entity.ItemFrameMock(MockBukkit.getMock(), UUID.randomUUID());
        ItemStack displayed = new ItemStack(Material.DIAMOND);
        realFrame.setItem(displayed, false);
        UUID owner = UUID.randomUUID();
        EntityLockData frameLock = new EntityLockData(realFrame.getUniqueId(),co( owner));
        frameLock.setLockState(LockState.PRIVATE);
        when(store.load(realFrame.getUniqueId())).thenReturn(frameLock);
        PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(player, realFrame);

        manager.onInteractEntity(click);

        assertTrue(click.isCancelled());
        assertEquals(displayed, realFrame.getItem());
        verify(player).sendMessage("§cIt's locked. Only its owner holds the key, though a steady hand and a pick might manage.");
        access.when(() -> LockAccess.canAccess(player, co(owner), LockState.PRIVATE)).thenReturn(true);
        HangingBreakByEntityEvent allowed = new HangingBreakByEntityEvent(realFrame, player);
        manager.onHangingBreak(allowed);
        assertFalse(allowed.isCancelled());
        verify(store, never()).save(any());
        verify(store, never()).delete(any());
        assertEquals(co(owner), frameLock.getOwner());
        assertEquals(LockState.PRIVATE, frameLock.getLockState());

        manager.onHangingRemoved(allowed);

        verify(store).delete(realFrame.getUniqueId());
        verifyNoInteractions(picks);
    }

    @ParameterizedTest
    @EnumSource(LockPickManager.SelectResult.class)
    void selectionOnlyDumpsOnSuccessAndOnlyBreakConsumesTool(LockPickManager.SelectResult result) {
        held.setAmount(2);
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(target);
        when(picks.handleSelect(player)).thenReturn(result);
        List<DisplayLoot.DisplaySlot> slots = lootSlots;
        select(slots);
        assertEquals(result == LockPickManager.SelectResult.BREAK ? 1 : 2, held.getAmount());
        if (result == LockPickManager.SelectResult.SUCCESS) {
            ArgumentCaptor<StealBudget> budget = ArgumentCaptor.forClass(StealBudget.class);
            loot.verify(() -> DisplayLoot.dump(eq(player), eq(slots), budget.capture(), eq(playerData)));
            assertEquals(40, budget.getValue().getCapacity());
        } else loot.verify(() -> DisplayLoot.dump(any(), anyList(), any(), any()), never());
        if (result != LockPickManager.SelectResult.NOT_IN_SESSION)
            drops.verify(() -> ClueDropper.tryDropDoorClue(player, stand.getLocation(), lock.getOwner(), 15, 0.7));
        else drops.verifyNoInteractions();
    }

    @Test
    void finalBrokenPickIsClearedAndMissingDefinitionUsesFallbackBudgetOnSuccess() {
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(target);
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.BREAK);
        select(lootSlots);
        verify(inventory).setItemInMainHand(null);
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.SUCCESS);
        tools.when(() -> ToolResolver.resolveLockpick(held)).thenReturn(null);
        select(lootSlots);
        ArgumentCaptor<StealBudget> budget = ArgumentCaptor.forClass(StealBudget.class);
        loot.verify(() -> DisplayLoot.dump(eq(player), anyList(), budget.capture(), eq(playerData)));
        assertEquals(30, budget.getValue().getCapacity());
    }

    @Test
    void lockpickInteractionRoutesFramesAndArmorStandsToTheirSlots() {
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(stand.getEquipment()).thenReturn(equipment);
        ItemStack displayedItem = new ItemStack(Material.DIAMOND);
        when(equipment.getItem(EquipmentSlot.HEAD)).thenReturn(displayedItem);
        when(frame.getItem()).thenReturn(displayedItem);
        PlayerArmorStandManipulateEvent armor = mock(PlayerArmorStandManipulateEvent.class);
        when(armor.getPlayer()).thenReturn(player);
        when(armor.getRightClicked()).thenReturn(stand);
        manager.onArmorStandManipulate(armor);
        verify(armor).setCancelled(true);
        PlayerInteractEntityEvent click = mock(PlayerInteractEntityEvent.class);
        when(click.getPlayer()).thenReturn(player);
        when(click.getRightClicked()).thenReturn(frame);
        manager.onInteractEntity(click);
        verify(click).setCancelled(true);
        verify(picks, times(2)).startSession(any(), any(), any(), any(), anyDouble(), anyInt(), anyDouble(), any());
    }

    @Test
    void disabledDisplayTypesDoNotInterceptInteractionOrDeleteStoredLocks() {
        Parameters.lockableEntityTypes = Set.of();
        PlayerArmorStandManipulateEvent armor = mock(PlayerArmorStandManipulateEvent.class);
        when(armor.getRightClicked()).thenReturn(stand);
        when(armor.getPlayer()).thenReturn(player);
        PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(player, frame);
        HangingBreakByEntityEvent broken = new HangingBreakByEntityEvent(frame, player);
        HangingBreakEvent removed = new HangingBreakEvent(frame, HangingBreakEvent.RemoveCause.PHYSICS);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(stand);

        manager.onArmorStandManipulate(armor);
        manager.onInteractEntity(click);
        manager.onHangingBreak(broken);
        manager.onLockableDeath(death);
        manager.onHangingRemoved(removed);

        verify(armor, never()).setCancelled(true);
        assertFalse(click.isCancelled());
        assertFalse(broken.isCancelled());
        assertFalse(removed.isCancelled());
        verifyNoInteractions(store, picks);
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void pickingAnotherDisplayStartsANewTargetInsteadOfSelectingThePreviousSession() {
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        String previousTarget = DoorLockpick.entityTargetId(frame.getUniqueId());
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(previousTarget);
        String target = DoorLockpick.entityTargetId(stand.getUniqueId());

        select(lootSlots);

        verify(picks, never()).handleSelect(any());
        verify(picks).startSession(eq(player), any(DoorLockpick.EntityProximityAnchor.class),
                eq(LockPickManager.SessionKind.DISPLAY), eq(target),
                eq(Parameters.displayLockStrength * (1 - 0.7 * Parameters.lockpickMaxReduction)),
                eq(15), eq(0.7), isNull());
        loot.verify(() -> DisplayLoot.dump(any(), anyList(), any(), any()), never());
        verify(playerData).addRiskGain(15, 0.7, RiskSource.DOOR);
    }

    @Test
    void displaySlotsRemoveOnlyTakenAmountsAndRejectEmptyOrMissingEquipment() {
        assertTrue(DisplayStealManager.slotsForEntity(mock(Entity.class)).isEmpty());
        assertTrue(DisplayStealManager.slotsForEntity(stand).isEmpty());
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(stand.getEquipment()).thenReturn(equipment);
        var slots = DisplayStealManager.slotsForEntity(stand);
        assertEquals(6, slots.size());
        EquipmentSlot[] order = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};
        for (int i = 0; i < order.length; i++) {
            ItemStack current = new ItemStack(Material.DIAMOND, 3);
            when(equipment.getItem(order[i])).thenReturn(current);
            assertSame(current, slots.get(i).get());
            assertTrue(slots.get(i).take(new ItemStack(Material.DIAMOND, 1)));
            ArgumentCaptor<ItemStack> remaining = ArgumentCaptor.forClass(ItemStack.class);
            verify(equipment).setItem(eq(order[i]), remaining.capture());
            assertEquals(2, remaining.getValue().getAmount());
            assertEquals(3, current.getAmount());
            assertTrue(slots.get(i).take(new ItemStack(Material.DIAMOND, 3)));
            verify(equipment).setItem(order[i], null);
        }
        when(equipment.getItem(EquipmentSlot.HEAD)).thenReturn(null);
        assertFalse(slots.getFirst().take(new ItemStack(Material.DIAMOND)));
        when(equipment.getItem(EquipmentSlot.HEAD)).thenReturn(new ItemStack(Material.AIR));
        assertFalse(slots.getFirst().take(new ItemStack(Material.DIAMOND)));
        when(stand.getEquipment()).thenReturn(null);
        assertNull(slots.getFirst().get());
        assertFalse(slots.getFirst().take(new ItemStack(Material.DIAMOND)));
        var slot = DisplayStealManager.slotsForEntity(frame).getFirst();
        when(frame.getItem()).thenReturn(new ItemStack(Material.DIAMOND, 3));
        assertEquals(3, slot.get().getAmount());
        assertTrue(slot.take(new ItemStack(Material.DIAMOND)));
        ArgumentCaptor<ItemStack> remaining = ArgumentCaptor.forClass(ItemStack.class);
        verify(frame).setItem(remaining.capture());
        assertEquals(2, remaining.getValue().getAmount());
        assertTrue(slot.take(new ItemStack(Material.DIAMOND, 3)));
        verify(frame).setItem(null);
        when(frame.getItem()).thenReturn(null);
        assertFalse(slot.take(new ItemStack(Material.DIAMOND)));
        when(frame.getItem()).thenReturn(new ItemStack(Material.AIR));
        assertFalse(slot.take(new ItemStack(Material.DIAMOND)));
    }

    @Test
    void breakingAttachedSupportIsDeniedButUnrelatedNearbyDisplaysDoNotBlockIt() {
        Block support = mock(Block.class);
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        when(support.getWorld()).thenReturn(world);
        when(support.getLocation()).thenReturn(origin);
        Painting painting = mock(Painting.class);
        when(painting.getType()).thenReturn(EntityType.PAINTING);
        when(world.getNearbyEntities(any(Location.class), eq(1.6), eq(1.6), eq(1.6))).thenReturn(List.of(stand, painting, frame));
        Location frameLocation = mock(Location.class);
        Block frameBlock = mock(Block.class);
        when(frame.getLocation()).thenReturn(frameLocation);
        when(frameLocation.getBlock()).thenReturn(frameBlock);
        when(frame.getAttachedFace()).thenReturn(BlockFace.NORTH);
        when(frameBlock.getRelative(BlockFace.NORTH)).thenReturn(mock(Block.class));
        BlockBreakEvent unrelated = new BlockBreakEvent(support, player);
        manager.onSupportBlockBreak(unrelated);
        assertFalse(unrelated.isCancelled());
        verifyNoInteractions(store);
        verify(painting, never()).getLocation();
        when(frameBlock.getRelative(BlockFace.NORTH)).thenReturn(support);
        BlockBreakEvent denied = new BlockBreakEvent(support, player);
        manager.onSupportBlockBreak(denied);
        assertTrue(denied.isCancelled());
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        BlockBreakEvent allowed = new BlockBreakEvent(support, player);
        manager.onSupportBlockBreak(allowed);
        assertFalse(allowed.isCancelled());
    }

    private void select(List<DisplayLoot.DisplaySlot> slots) {
        manager.handleLockpick(player, stand, lock.getOwner(), LockState.PRIVATE, slots);
    }

    private static EntityDamageByEntityEvent damage(Entity target, Entity damager) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(target);
        when(event.getDamager()).thenReturn(damager);
        return event;
    }

    static final String STARTING = "§cLocks are starting up. Try again shortly.";

    @Test
    void placingWithoutACharacterLeavesDisplaysUnownedAndSessionlessStaffStampTheServer() {
        EntityPlaceEvent placed = mock(EntityPlaceEvent.class);
        when(placed.getEntity()).thenReturn(stand);
        when(placed.getPlayer()).thenReturn(player);
        lock.setOwner(null);
        stage5Factions.plays(player, null);
        manager.onEntityPlace(placed);
        assertNull(lock.getOwner());
        verify(store, never()).save(any());
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        manager.onEntityPlace(placed);
        assertEquals(LockOwner.server(), lock.getOwner());
        assertEquals(LockState.PRIVATE, lock.getLockState());
        tutorial.verify(() -> FactionLockTutorial.onLockState(player, LockState.PRIVATE));
    }

    @Test
    void anAltCannotRotateItsMainsDisplayAndClaimsNeedACharacter() {
        EntityDamageByEntityEvent event = damage(stand, player);
        when(player.isSneaking()).thenReturn(true);
        lock.setOwner(co(player.getUniqueId()));
        stage5Factions.plays(player, UUID.randomUUID());
        manager.onEntityDamage(event);
        assertEquals(LockState.PRIVATE, lock.getLockState());
        verify(player).sendMessage(contains("Only the owner can change this lock"));
        lock.setOwner(null);
        stage5Factions.plays(player, null);
        manager.onEntityDamage(event);
        assertNull(lock.getOwner());
        verify(player).sendMessage("§cPlay a character to claim or change locks.");
        verify(store, never()).save(any());
    }

    @Test
    void guardModeCancelsPlacementBreaksAndPicksAndRefusesRotations() {
        net.tfminecraft.thievery.owner.LockGuard.enter("test");
        EntityPlaceEvent placed = mock(EntityPlaceEvent.class);
        when(placed.getEntity()).thenReturn(stand);
        when(placed.getPlayer()).thenReturn(player);
        lock.setOwner(null);
        manager.onEntityPlace(placed);
        verify(placed).setCancelled(true);
        assertNull(lock.getOwner());
        lock.setOwner(co(player.getUniqueId()));

        EntityDamageByEntityEvent hit = damage(stand, player);
        manager.onEntityDamage(hit);
        verify(hit).setCancelled(true);
        when(player.isSneaking()).thenReturn(true);
        EntityDamageByEntityEvent rotate = damage(stand, player);
        manager.onEntityDamage(rotate);
        verify(rotate).setCancelled(true);
        assertEquals(LockState.PRIVATE, lock.getLockState());
        when(player.isSneaking()).thenReturn(false);

        HangingBreakByEntityEvent hanging = mock(HangingBreakByEntityEvent.class);
        when(hanging.getEntity()).thenReturn(frame);
        when(hanging.getRemover()).thenReturn(player);
        manager.onHangingBreak(hanging);
        verify(hanging).setCancelled(true);

        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        manager.handleLockpick(player, stand, lock.getOwner(), lock.getLockState(), lootSlots);
        verifyNoInteractions(picks);
        verify(store, never()).save(any());
        verify(player, atLeast(4)).sendMessage(STARTING);
    }

    @Test
    void guardModeKeepsNonStaffOffLockedDisplaysButLeavesPublicAndUnownedOnesUsable() {
        net.tfminecraft.thievery.owner.LockGuard.enter("test");
        PlayerArmorStandManipulateEvent manipulate = mock(PlayerArmorStandManipulateEvent.class);
        when(manipulate.getRightClicked()).thenReturn(stand);
        when(manipulate.getPlayer()).thenReturn(player);
        manager.onArmorStandManipulate(manipulate);
        verify(manipulate).setCancelled(true);
        PlayerInteractEntityEvent use = mock(PlayerInteractEntityEvent.class);
        when(use.getRightClicked()).thenReturn(frame);
        when(use.getPlayer()).thenReturn(player);
        manager.onInteractEntity(use);
        verify(use).setCancelled(true);
        verify(player, times(2)).sendMessage(STARTING);

        access.when(() -> LockAccess.canAccess(any(), any(), any())).thenReturn(true);
        lock.setLockState(LockState.PUBLIC);
        PlayerInteractEntityEvent open = mock(PlayerInteractEntityEvent.class);
        when(open.getRightClicked()).thenReturn(frame);
        when(open.getPlayer()).thenReturn(player);
        manager.onInteractEntity(open);
        verify(open, never()).setCancelled(true);
        lock.setOwner(null);
        lock.setLockState(LockState.PRIVATE);
        manager.onInteractEntity(open);
        verify(open, never()).setCancelled(true);
        lock.setOwner(co(UUID.randomUUID()));
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        PlayerArmorStandManipulateEvent staff = mock(PlayerArmorStandManipulateEvent.class);
        when(staff.getRightClicked()).thenReturn(stand);
        when(staff.getPlayer()).thenReturn(player);
        manager.onArmorStandManipulate(staff);
        verify(staff, never()).setCancelled(true);
    }

    @Test
    void guardModeProtectsTheSupportBlockOfAnyLockableHangingDisplayEvenForStaff() {
        net.tfminecraft.thievery.owner.LockGuard.enter("test");
        Block support = mock(Block.class);
        World world = mock(World.class);
        when(support.getWorld()).thenReturn(world);
        when(support.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(world.getNearbyEntities(any(Location.class), eq(1.6), eq(1.6), eq(1.6))).thenReturn(List.of(frame));
        Location frameLocation = mock(Location.class);
        Block frameBlock = mock(Block.class);
        when(frame.getLocation()).thenReturn(frameLocation);
        when(frameLocation.getBlock()).thenReturn(frameBlock);
        when(frame.getAttachedFace()).thenReturn(BlockFace.NORTH);
        when(frameBlock.getRelative(BlockFace.NORTH)).thenReturn(support);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        BlockBreakEvent breaking = new BlockBreakEvent(support, player);
        manager.onSupportBlockBreak(breaking);
        assertTrue(breaking.isCancelled());
        verify(player).sendMessage(STARTING);
        verifyNoInteractions(store);
    }

    @Test
    void anErrorOnADisplayCancelsTheEvent() {
        when(store.load(any())).thenThrow(new IllegalStateException("broken"));
        EntityDamageByEntityEvent hit = damage(stand, player);
        manager.onEntityDamage(hit);
        verify(hit).setCancelled(true);
    }
}
