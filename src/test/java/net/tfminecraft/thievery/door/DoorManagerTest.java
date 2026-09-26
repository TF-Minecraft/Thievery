package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.LockpickTargetCache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.key.KeyCopyHandler;
import net.tfminecraft.thievery.key.KeychainHandler;
import net.tfminecraft.thievery.key.KeychainHandler.DoorKeyMatch;
import net.tfminecraft.thievery.key.KeychainHandler.DoorKeyPurpose;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.World;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class DoorManagerTest {
    private ServerMock server;
    private World world;
    private DoorManager manager;
    private DoorDataManager store;
    private LockPickManager picks;
    private Player player;
    private PlayerInventory inventory;
    private PlayerData playerData;
    private Block door;
    private ItemStack held;
    private DoorData lock;
    private boolean requireOwner;
    private MockedConstruction<DoorDataManager> stores;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<ToolResolver> tools;
    private MockedStatic<KeychainHandler> keychains;
    private MockedStatic<KeyCopyHandler> copies;
    private MockedStatic<ClueChecker> clues;
    private MockedStatic<ClueDropper> drops;
    private MockedStatic<Database> database;
    private MockedStatic<RiskCalculator> risk;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("doors");
        Thievery instance = mock(Thievery.class);
        when(instance.namespace()).thenReturn("thievery");
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        tools = mockStatic(ToolResolver.class);
        keychains = mockStatic(KeychainHandler.class);
        copies = mockStatic(KeyCopyHandler.class);
        clues = mockStatic(ClueChecker.class);
        drops = mockStatic(ClueDropper.class);
        database = mockStatic(Database.class);
        risk = mockStatic(RiskCalculator.class);
        stores = mockConstruction(DoorDataManager.class);
        picks = mock(LockPickManager.class);
        manager = new DoorManager(picks);
        store = stores.constructed().getFirst();
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        playerData = mock(PlayerData.class);
        PlayerManager players = mock(PlayerManager.class);
        when(players.get(player.getUniqueId())).thenReturn(playerData);
        plugin.when(Thievery::getPlayerManager).thenReturn(players);
        door = world.getBlockAt(10, 64, 10);
        door.setType(Material.OAK_DOOR);
        setHalf(door, Bisected.Half.BOTTOM);
        door.getRelative(BlockFace.UP).setType(Material.OAK_DOOR);
        setHalf(door.getRelative(BlockFace.UP), Bisected.Half.TOP);
        when(player.getLocation()).thenReturn(door.getLocation().clone().add(0.5, 0.5, 0.5));
        held = new ItemStack(Material.GOLD_NUGGET);
        when(inventory.getItemInMainHand()).thenReturn(held);
        lock = new DoorData(door.getLocation(), "door-id", 0.8, UUID.randomUUID());
        requireOwner = Cache.requireOwnerOnline;
        Cache.requireOwnerOnline = false;
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(true);
        tools.when(() -> ToolResolver.getLockpickStrength(any())).thenReturn(0.6);
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(12);
    }

    @AfterEach
    void tearDown() {
        Cache.requireOwnerOnline = requireOwner;
        risk.close(); database.close(); drops.close(); clues.close(); copies.close();
        keychains.close(); tools.close(); stores.close(); plugin.close(); MockBukkit.unmock();
    }

    @Test
    void irrelevantActionsBlocksAndIronDoorsAreUntouched() {
        PlayerInteractEvent event = interaction(door);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
        manager.onPlayerInteract(event);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(null);
        manager.onPlayerInteract(event);
        for (Material type : new Material[] {Material.STONE, Material.IRON_DOOR, Material.IRON_TRAPDOOR}) {
            door.setType(type);
            manager.onPlayerInteract(interaction(door));
        }
        verifyNoInteractions(store);
    }

    @Test
    void sneakingWithAnOrdinaryItemDoesNotChangeOrBypassTheLock() {
        locked();
        when(player.isSneaking()).thenReturn(true);
        PlayerInteractEvent event = interaction(door);

        manager.onPlayerInteract(event);

        verify(event).setCancelled(true);
        verify(player).sendTitle(contains("This door is locked."), eq(""), eq(5), eq(30), eq(10));
        verify(store, never()).saveDoorData(any());
        verify(store, never()).deleteDoorData(any());
        verifyNoInteractions(picks);
    }

    @Test
    void vanillaMasterKeyWithoutMetadataReceivesTheNewLocksUuid() {
        ItemStack vanilla = spy(new ItemStack(Material.GOLD_NUGGET));
        // Paper reports no metadata for this plain item; MockBukkit reports an empty meta object.
        doAnswer(call -> !vanilla.getItemMeta().getPersistentDataContainer().isEmpty())
                .when(vanilla).hasItemMeta();
        assertFalse(vanilla.hasItemMeta());
        when(inventory.getItemInMainHand()).thenReturn(vanilla);
        when(player.isSneaking()).thenReturn(true);
        tools.when(() -> ToolResolver.isLockingKey(vanilla)).thenReturn(true);
        tools.when(() -> ToolResolver.isMasterKey(vanilla)).thenReturn(true);
        tools.when(() -> ToolResolver.getKeyStrength(vanilla)).thenReturn(0.75);

        manager.onPlayerInteract(interaction(door));

        String key = vanilla.getItemMeta().getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING);
        assertNotNull(UUID.fromString(key));
        assertTrue(vanilla.hasItemMeta());
        ArgumentCaptor<DoorData> saved = ArgumentCaptor.forClass(DoorData.class);
        verify(store).saveDoorData(saved.capture());
        assertEquals(key, saved.getValue().getKey());
        assertEquals(player.getUniqueId(), saved.getValue().getOwnerUUID());
        assertEquals(door.getLocation(), saved.getValue().getLocation());
        assertEquals(0.75, saved.getValue().getStrength());
    }

    @Test
    void sneakingMasterKeyLocksCanonicalBottomOnceAndKeepsExistingUuid() {
        when(player.isSneaking()).thenReturn(true);
        tools.when(() -> ToolResolver.isLockingKey(held)).thenReturn(true);
        tools.when(() -> ToolResolver.isMasterKey(held)).thenReturn(true);
        tools.when(() -> ToolResolver.getKeyStrength(held)).thenReturn(0.75);
        PlayerInteractEvent event = interaction(door.getRelative(BlockFace.UP));
        manager.onPlayerInteract(event);
        ArgumentCaptor<DoorData> saved = ArgumentCaptor.forClass(DoorData.class);
        verify(store).saveDoorData(saved.capture());
        assertEquals(door.getLocation(), saved.getValue().getLocation());
        assertEquals(player.getUniqueId(), saved.getValue().getOwnerUUID());
        assertEquals(0.75, saved.getValue().getStrength());
        String uuid = held.getItemMeta().getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING);
        assertNotNull(UUID.fromString(uuid));
        assertEquals(uuid, saved.getValue().getKey());
        verify(event).setCancelled(true);
        manager.onPlayerInteract(event);
        verify(store, times(1)).saveDoorData(any());
    }

    @Test
    void lockingCopyUsesExistingUuidButCannotInventOne() {
        when(player.isSneaking()).thenReturn(true);
        tools.when(() -> ToolResolver.isLockingKey(held)).thenReturn(true);
        manager.onPlayerInteract(interaction(door));
        verify(store, never()).saveDoorData(any());
        held.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, "existing"));
        // A different player has no interaction debounce from the rejected attempt.
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        manager.onPlayerInteract(interaction(door));
        ArgumentCaptor<DoorData> saved = ArgumentCaptor.forClass(DoorData.class);
        verify(store).saveDoorData(saved.capture());
        assertEquals("existing", saved.getValue().getKey());
    }

    @Test
    void sneakingUnlockRequiresMatchingPermanentKeyAndPaperCannotChangeLocks() {
        locked();
        when(player.isSneaking()).thenReturn(true);
        tools.when(() -> ToolResolver.isLockingKey(held)).thenReturn(true);
        manager.onPlayerInteract(interaction(door));
        verify(store, never()).deleteDoorData(any());
        verify(player).sendTitle(contains("does not fit"), eq(""), eq(5), eq(30), eq(10));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        keychains.when(() -> KeychainHandler.matchesDoor(held, "door-id", DoorKeyPurpose.UNLOCK_OR_BREAK)).thenReturn(true);
        manager.onPlayerInteract(interaction(door));
        verify(store).deleteDoorData(door.getLocation());
        tools.when(() -> ToolResolver.isLockingKey(held)).thenReturn(false);
        copies.when(() -> KeyCopyHandler.isPaperCopy(held)).thenReturn(true);
        PlayerInteractEvent event = interaction(door);
        manager.onPlayerInteract(event);
        verify(event).setCancelled(true);
        verify(player).sendTitle(contains("Paper keys can only open"), eq(""), eq(5), eq(30), eq(10));
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"OAK_DOOR", "OAK_FENCE_GATE", "OAK_TRAPDOOR"})
    void openDoorsAndGatesCanCloseButOpenLockedTrapdoorsStillRequireAKey(Material type) {
        door.setType(type);
        setOpen(door, true);
        locked();
        PlayerInteractEvent event = interaction(door);
        manager.onPlayerInteract(event);
        if (type == Material.OAK_TRAPDOOR) verify(event).setCancelled(true);
        else verify(event, never()).setCancelled(true);
    }

    @Test
    void lockedDoorRequiresKeyUnlessUnlockWindowIsActiveAndExpiredWindowsAreCleared() {
        locked();
        PlayerInteractEvent blocked = interaction(door);
        manager.onPlayerInteract(blocked);
        verify(blocked).setCancelled(true);
        lock.setUnlockExpiryMs(System.currentTimeMillis() + 60_000);
        PlayerInteractEvent allowed = interaction(door);
        manager.onPlayerInteract(allowed);
        verify(allowed, never()).setCancelled(true);
        lock.setUnlockExpiryMs(1L);
        manager.onPlayerInteract(interaction(door));
        assertNull(lock.getUnlockExpiryMs());
        verify(store).saveDoorData(lock);
        keychains.when(() -> KeychainHandler.resolveDoorMatch(held, "door-id", DoorKeyPurpose.OPEN))
                .thenReturn(new DoorKeyMatch("door-id", false));
        allowed = interaction(door);
        manager.onPlayerInteract(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void paperOpeningConsumesExactlyOneHeldKey(int amount) {
        locked();
        held.setAmount(amount);
        copies.when(() -> KeyCopyHandler.isPaperCopy(held)).thenReturn(true);
        keychains.when(() -> KeychainHandler.resolveDoorMatch(held, "door-id", DoorKeyPurpose.OPEN))
                .thenReturn(new DoorKeyMatch("door-id", true));
        manager.onPlayerInteract(interaction(door));
        if (amount == 1) verify(inventory).setItemInMainHand(null);
        else assertEquals(amount - 1, held.getAmount());
    }

    @Test
    void paperOpeningFromKeychainReplacesTheHeldChain() {
        locked();
        ItemStack updated = new ItemStack(Material.BRICK);
        keychains.when(() -> KeychainHandler.resolveDoorMatch(held, "door-id", DoorKeyPurpose.OPEN))
                .thenReturn(new DoorKeyMatch("door-id", true));
        keychains.when(() -> KeychainHandler.isKeychain(held)).thenReturn(true);
        keychains.when(() -> KeychainHandler.consumePaperKeyForDoor(held, "door-id")).thenReturn(updated);
        manager.onPlayerInteract(interaction(door));
        verify(inventory).setItemInMainHand(updated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unlockedDoorCanOpenWhileHoldingPaperKeyOrLockpick(boolean lockpick) {
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(lockpick);
        copies.when(() -> KeyCopyHandler.isPaperCopy(held)).thenReturn(!lockpick);
        PlayerInteractEvent event = interaction(door);

        manager.onPlayerInteract(event);

        verify(event, never()).setCancelled(true);
        verifyNoInteractions(picks, playerData);
        verify(inventory, never()).setItemInMainHand(any());
        assertEquals(1, held.getAmount());
        verify(store, never()).saveDoorData(any());
        verify(store, never()).deleteDoorData(any());
        database.verifyNoInteractions();
    }

    @Test
    void lockpickStartChecksRangeCluesCooldownAndStrengthBeforeRecordingRisk() {
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(player.getLocation()).thenReturn(door.getLocation().clone().add(100, 0, 0));
        manager.onPlayerInteract(interaction(door));
        verify(player).sendMessage(contains("too far"));
        when(player.getLocation()).thenReturn(door.getLocation());
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(false);
        manager.onPlayerInteract(interaction(door));
        clues.verify(() -> ClueChecker.sendInsufficientCluesMessage(player));
        clues.when(() -> ClueChecker.hasEnoughClues(player)).thenReturn(true);
        when(picks.isOnCooldown(player.getUniqueId(), door.getLocation())).thenReturn(true);
        when(picks.getCooldownRemainingSeconds(player.getUniqueId(), door.getLocation())).thenReturn(30L);
        manager.onPlayerInteract(interaction(door));
        verify(player).sendMessage(contains("wait 30s"));
        when(picks.isOnCooldown(player.getUniqueId(), door.getLocation())).thenReturn(false);
        tools.when(() -> ToolResolver.getLockpickStrength(held)).thenReturn(0.1);
        manager.onPlayerInteract(interaction(door));
        verify(player).sendMessage(contains("too weak"));
        verify(picks, never()).startDoorSession(any(), any(), anyDouble(), anyInt(), anyDouble());
        tools.when(() -> ToolResolver.getLockpickStrength(held)).thenReturn(0.6);
        manager.onPlayerInteract(interaction(door));
        verify(playerData).addRiskGain(12, 0.6, RiskSource.DOOR);
        database.verify(() -> Database.savePlayerData(playerData));
        verify(picks).startDoorSession(player, door.getLocation(),
                0.8 * (1 - 0.6 * Parameters.lockpickMaxReduction), 12, 0.6);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offlineOwnerRequiresCachedWindowAndWarnsBeforeStarting(boolean cachedWindow) {
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        Cache.requireOwnerOnline = true;
        UUID ownerId = lock.getOwnerUUID();
        OfflinePlayer owner = mock(OfflinePlayer.class);
        when(owner.getName()).thenReturn("Owner");
        when(owner.isOnline()).thenReturn(false);
        String targetKey = "player:" + ownerId;
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                var factions = mockStatic(FactionManager.class);
                var windows = mockStatic(LockpickTargetCache.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(ownerId)).thenReturn(owner);
            windows.when(() -> LockpickTargetCache.isActive(targetKey)).thenReturn(cachedWindow);
            windows.when(() -> LockpickTargetCache.getRemainingMs(targetKey)).thenReturn(125_000L);

            manager.onPlayerInteract(interaction(door));

            if (cachedWindow) {
                verify(player).sendMessage(contains("Owner has no members online - 2m 5s remaining"));
                verify(playerData).addRiskGain(12, 0.6, RiskSource.DOOR);
                database.verify(() -> Database.savePlayerData(playerData));
                verify(picks).startDoorSession(player, door.getLocation(),
                        0.8 * (1 - 0.6 * Parameters.lockpickMaxReduction), 12, 0.6);
            } else {
                verify(player).sendMessage(contains("Cannot lockpick - Owner is not online."));
                verify(picks, never()).startDoorSession(any(), any(), anyDouble(), anyInt(), anyDouble());
                verifyNoInteractions(playerData);
                database.verifyNoInteractions();
            }
        }
    }

    @Test
    void openDoorCannotBePickedAndSwitchingTargetsCancelsTheOldSession() {
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        setOpen(door, true);
        manager.onPlayerInteract(interaction(door));
        verifyNoInteractions(picks);
        setOpen(door, false);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn("other-target");
        manager.onPlayerInteract(interaction(door));
        verify(picks).cancelSession(player.getUniqueId(), true);
        verify(picks).startDoorSession(eq(player), eq(door.getLocation()), anyDouble(), eq(12), eq(0.6));
    }

    @ParameterizedTest
    @EnumSource(value = LockPickManager.SelectResult.class, names = {"SUCCESS", "FAIL", "BREAK", "NOT_IN_SESSION"})
    void selectingExistingSessionAppliesOutcomeAndOnlyBreakConsumesTool(LockPickManager.SelectResult outcome) {
        locked();
        held.setAmount(2);
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(DoorLockpick.doorTargetId(door.getLocation()));
        when(picks.handleSelect(player)).thenReturn(outcome);
        manager.onPlayerInteract(interaction(door));
        if (outcome == LockPickManager.SelectResult.SUCCESS) {
            assertTrue(((Openable) door.getBlockData()).isOpen());
            assertTrue(((Openable) door.getRelative(BlockFace.UP).getBlockData()).isOpen());
            assertTrue(lock.getUnlockExpiryMs() > System.currentTimeMillis());
            verify(store).saveDoorData(lock);
        } else assertFalse(((Openable) door.getBlockData()).isOpen());
        assertEquals(outcome == LockPickManager.SelectResult.BREAK ? 1 : 2, held.getAmount());
        if (outcome != LockPickManager.SelectResult.NOT_IN_SESSION)
            drops.verify(() -> ClueDropper.tryDropDoorClue(player, door.getLocation(), lock.getOwnerUUID(), 12, 0.6));
        else drops.verifyNoInteractions();
    }

    @Test
    void finalBrokenLockpickIsRemovedFromHand() {
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(DoorLockpick.doorTargetId(door.getLocation()));
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.BREAK);
        manager.onPlayerInteract(interaction(door));
        verify(inventory).setItemInMainHand(null);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"OAK_TRAPDOOR", "OAK_FENCE_GATE"})
    void successfulPickOpensSingleBlockLocksWithoutChangingTheBlockAbove(Material type) {
        door.setType(type);
        Block above = door.getRelative(BlockFace.UP);
        above.setType(Material.STONE);
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(DoorLockpick.doorTargetId(door.getLocation()));
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.SUCCESS);
        PlayerInteractEvent event = interaction(door);

        manager.onPlayerInteract(event);

        verify(event).setCancelled(true);
        assertTrue(((Openable) door.getBlockData()).isOpen());
        assertEquals(Material.STONE, above.getType());
        assertTrue(lock.getUnlockExpiryMs() > System.currentTimeMillis());
        verify(store).saveDoorData(lock);
        drops.verify(() -> ClueDropper.tryDropDoorClue(player, door.getLocation(), lock.getOwnerUUID(), 12, 0.6));
    }

    @Test
    void lockDeletedBetweenSelectionReadsIsNotRecreatedBySuccessfulPick() {
        // The data manager rereads disk; an external deletion can occur between these reads.
        when(store.loadDoorData(door.getLocation())).thenReturn(lock, null);
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(DoorLockpick.doorTargetId(door.getLocation()));
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.SUCCESS);
        PlayerInteractEvent event = interaction(door);

        manager.onPlayerInteract(event);

        verify(event).setCancelled(true);
        assertTrue(((Openable) door.getBlockData()).isOpen());
        assertTrue(((Openable) door.getRelative(BlockFace.UP).getBlockData()).isOpen());
        assertNull(lock.getUnlockExpiryMs());
        verify(store, times(2)).loadDoorData(door.getLocation());
        verify(store, never()).saveDoorData(any());
        verify(player).sendTitle(contains("Picked!"), eq(""), eq(5), eq(40), eq(10));
        drops.verify(() -> ClueDropper.tryDropDoorClue(player, door.getLocation(), null, 12, 0.6));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulPickOfPartialDoorPreservesMissingOrReplacedHalf(boolean missingBottom) {
        Block top = door.getRelative(BlockFace.UP);
        if (missingBottom) door.setType(Material.STONE);
        else top.setType(Material.AIR);
        locked();
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        when(picks.isInSession(player.getUniqueId())).thenReturn(true);
        when(picks.getSessionTargetId(player.getUniqueId())).thenReturn(DoorLockpick.doorTargetId(door.getLocation()));
        when(picks.handleSelect(player)).thenReturn(LockPickManager.SelectResult.SUCCESS);
        PlayerInteractEvent event = interaction(missingBottom ? top : door);

        manager.onPlayerInteract(event);

        verify(event).setCancelled(true);
        if (missingBottom) {
            assertEquals(Material.STONE, door.getType());
            assertFalse(((Openable) top.getBlockData()).isOpen());
        } else {
            assertTrue(((Openable) door.getBlockData()).isOpen());
            assertEquals(Material.AIR, top.getType());
        }
        assertTrue(lock.getUnlockExpiryMs() > System.currentTimeMillis());
        verify(store).saveDoorData(lock);
        drops.verify(() -> ClueDropper.tryDropDoorClue(player, door.getLocation(), lock.getOwnerUUID(), 12, 0.6));
    }

    @Test
    void ironDoorSupportIsOutsideWoodenDoorLockProtection() {
        door.setType(Material.IRON_DOOR);
        setHalf(door, Bisected.Half.BOTTOM);
        Block support = door.getRelative(BlockFace.DOWN);
        support.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(support, player);

        manager.onBlockBreak(event);
        server.getScheduler().performTicks(5);

        assertFalse(event.isCancelled());
        verifyNoInteractions(store);
    }

    @Test
    void survivalBreakProtectsDoorAndSupportWhileMatchingKeyRemovesLock() {
        locked();
        BlockBreakEvent denied = new BlockBreakEvent(door.getRelative(BlockFace.UP), player);
        manager.onBlockBreak(denied);
        assertTrue(denied.isCancelled());
        Block support = door.getRelative(BlockFace.DOWN);
        support.setType(Material.STONE);
        BlockBreakEvent deniedSupport = new BlockBreakEvent(support, player);
        manager.onBlockBreak(deniedSupport);
        assertTrue(deniedSupport.isCancelled());
        keychains.when(() -> KeychainHandler.matchesDoor(held, "door-id", DoorKeyPurpose.UNLOCK_OR_BREAK)).thenReturn(true);
        BlockBreakEvent allowed = new BlockBreakEvent(door, player);
        manager.onBlockBreak(allowed);
        assertFalse(allowed.isCancelled());
        verify(store, never()).deleteDoorData(any());
        door.setType(Material.AIR);
        server.getScheduler().performTicks(5);
        verify(store).deleteDoorData(door.getLocation());
    }

    @Test
    void breakingBlockBelowOrphanUpperHalfDoesNotTreatItAsADoorSupport() {
        locked();
        door.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(door, player);

        manager.onBlockBreak(event);
        server.getScheduler().performTicks(5);

        assertFalse(event.isCancelled());
        verifyNoInteractions(store);
        assertEquals(Bisected.Half.TOP,
                ((Bisected) door.getRelative(BlockFace.UP).getBlockData()).getHalf());
    }

    @Test
    void breakingUnlockedDoorSupportDoesNotDeleteDoorMetadata() {
        Block support = door.getRelative(BlockFace.DOWN);
        support.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(support, player);

        manager.onBlockBreak(event);
        server.getScheduler().performTicks(5);

        assertFalse(event.isCancelled());
        verify(store).loadDoorData(door.getLocation());
        verify(store, never()).deleteDoorData(any());
        verify(store, never()).saveDoorData(any());
        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void unlockedAndUnrelatedBlocksCanBreakAndCreativeCleanupWaitsForActualDoorRemoval() {
        manager.onBlockBreak(new BlockBreakEvent(door, player));
        verify(store).deleteDoorData(door.getLocation());
        clearInvocations(store);
        manager.onBlockBreak(new BlockBreakEvent(world.getBlockAt(100, 60, 100), player));
        verifyNoInteractions(store);
        locked();
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        manager.onBlockBreak(new BlockBreakEvent(door, player));
        server.getScheduler().performTicks(5);
        verify(store, never()).deleteDoorData(any());
        manager.onBlockBreak(new BlockBreakEvent(door, player));
        door.setType(Material.AIR);
        server.getScheduler().performTicks(5);
        verify(store).deleteDoorData(door.getLocation());
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SURVIVAL"})
    void cancelledSupportBreakPreservesExistingDoorLock(GameMode mode) {
        locked();
        when(player.getGameMode()).thenReturn(mode);
        keychains.when(() -> KeychainHandler.matchesDoor(held, "door-id", DoorKeyPurpose.UNLOCK_OR_BREAK)).thenReturn(true);
        Block support = door.getRelative(BlockFace.DOWN);
        support.setType(Material.STONE);
        server.getPluginManager().registerEvents(manager, MockBukkit.createMockPlugin());
        BlockBreakEvent event = new BlockBreakEvent(support, player);
        event.setCancelled(true); // A protection listener has already rejected this break.
        server.getPluginManager().callEvent(event);
        server.getScheduler().performTicks(5);
        assertTrue(event.isCancelled());
        assertEquals(Material.OAK_DOOR, door.getType());
        verify(store, never()).deleteDoorData(door.getLocation());
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SURVIVAL"})
    void cancellationAfterDoorHandlerPreservesLockEvenIfBlockChanged(GameMode mode) {
        locked();
        when(player.getGameMode()).thenReturn(mode);
        keychains.when(() -> KeychainHandler.matchesDoor(held, "door-id", DoorKeyPurpose.UNLOCK_OR_BREAK)).thenReturn(true);
        BlockBreakEvent event = new BlockBreakEvent(door, player);
        manager.onBlockBreak(event);
        // A later protection listener can cancel after Thievery schedules cleanup.
        event.setCancelled(true);
        door.setType(Material.AIR);
        server.getScheduler().performTicks(5);
        verify(store, never()).deleteDoorData(any());
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SURVIVAL"})
    void supportRemovalDoesNotEraseLockIfCanonicalDoorSurvives(GameMode mode) {
        locked();
        when(player.getGameMode()).thenReturn(mode);
        keychains.when(() -> KeychainHandler.matchesDoor(held, "door-id", DoorKeyPurpose.UNLOCK_OR_BREAK)).thenReturn(true);
        Block support = door.getRelative(BlockFace.DOWN);
        support.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(support, player);
        manager.onBlockBreak(event);
        support.setType(Material.AIR);
        server.getScheduler().performTicks(5);
        assertFalse(event.isCancelled());
        assertEquals(Material.OAK_DOOR, door.getType());
        verify(store, never()).deleteDoorData(any());
    }

    @Test
    void debugToolReportsMissingAndOwnedLocks() {
        tools.when(() -> ToolResolver.isDebugTool(held)).thenReturn(true);
        PlayerInteractEvent event = interaction(door);
        manager.onPlayerInteract(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage(contains("not locked"));
        locked();
        manager.onPlayerInteract(interaction(door));
        verify(player).sendMessage(contains("Door was locked by"));
    }

    @Test
    void debugToolHandlesLegacyLockWithoutOwnerWithoutChangingIt() {
        lock = new DoorData(door.getLocation(), "legacy-key", 0.8, null);
        locked();
        tools.when(() -> ToolResolver.isDebugTool(held)).thenReturn(true);
        PlayerInteractEvent event = interaction(door);

        manager.onPlayerInteract(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(contains("This door is not locked."));
        verify(store, never()).saveDoorData(any());
        verify(store, never()).deleteDoorData(any());
        verifyNoInteractions(picks);
        assertEquals("legacy-key", lock.getKey());
        assertNull(lock.getOwnerUUID());
    }

    @Test
    void debugToolUsesOwnerUuidWhenTheirOfflineNameIsUnavailable() {
        locked();
        tools.when(() -> ToolResolver.isDebugTool(held)).thenReturn(true);
        UUID ownerId = lock.getOwnerUUID();
        OfflinePlayer owner = mock(OfflinePlayer.class);
        when(owner.getName()).thenReturn(null);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(ownerId)).thenReturn(owner);
            PlayerInteractEvent event = interaction(door);

            manager.onPlayerInteract(event);

            verify(event).setCancelled(true);
            verify(player).sendMessage(contains(ownerId.toString()));
            verify(store, never()).saveDoorData(any());
            verifyNoInteractions(picks);
        }
    }

    private void locked() {
        when(store.loadDoorData(door.getLocation())).thenReturn(lock);
    }

    private PlayerInteractEvent interaction(Block clicked) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(clicked);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static void setOpen(Block block, boolean open) {
        Openable data = (Openable) block.getBlockData();
        data.setOpen(open);
        block.setBlockData(data);
    }

    private static void setHalf(Block block, Bisected.Half half) {
        Bisected data = (Bisected) block.getBlockData();
        data.setHalf(half);
        block.setBlockData(data);
    }
}
