package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.category.DenarMoney;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.door.ChestLockpickSession;
import net.tfminecraft.thievery.door.LockTypeProfile;
import net.tfminecraft.thievery.player.*;
import net.tfminecraft.thievery.robbery.RobberySession;
import net.tfminecraft.thievery.steal.session.HiddenStealSession;
import net.tfminecraft.thievery.steal.session.StealSession;
import net.tfminecraft.thievery.steal.source.StealSource;
import net.tfminecraft.thievery.steal.source.ContainerStealSource;
import net.tfminecraft.thievery.steal.source.PlayerStealSource;
import net.tfminecraft.simplefactions.managers.FactionManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class StealReferencesTest {
    private ServerMock server;
    private PlayerMock thief, victim;
    private PlayerData data;
    private StealManager manager;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<StealManager> managers;
    private MockedStatic<StealGui> gui;
    private MockedStatic<RiskCalculator> risk;
    private MockedStatic<Database> database;
    private MockedStatic<FactionManager> factions;
    private MockedStatic<PickpocketVictimAlerter> alerts;
    private ItemStack unknown, filler, hidden, pouch;
    private boolean oldCoreProtect;
    private double oldBreakRamp;

    @BeforeEach void setUp() {
        server = MockBukkit.mock(); thief = server.addPlayer("Thief"); victim = server.addPlayer("Victim");
        Thievery plugin = mock(Thievery.class); when(plugin.namespace()).thenReturn("thievery");
        when(plugin.getName()).thenReturn("Thievery"); when(plugin.isEnabled()).thenReturn(true);
        thievery = mockStatic(Thievery.class, RETURNS_DEEP_STUBS); thievery.when(Thievery::getInstance).thenReturn(plugin);
        data = mock(PlayerData.class); when(Thievery.getPlayerManager().get(thief.getUniqueId())).thenReturn(data);
        when(data.getRisk()).thenReturn(0.25);
        manager = mock(StealManager.class); managers = mockStatic(StealManager.class); managers.when(StealManager::getInstance).thenReturn(manager);
        unknown = StealGui.createUnknownPane(); filler = StealGui.createFillerPane(); hidden = StealGui.createHiddenPane(); pouch = new ItemStack(Material.PAPER);
        var pouchMeta = pouch.getItemMeta(); pouchMeta.getPersistentDataContainer().set(net.tfminecraft.thievery.utils.Keys.stealRobberyPouch, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1); pouch.setItemMeta(pouchMeta);
        gui = mockStatic(StealGui.class);
        gui.when(() -> StealGui.isUnknownPane(unknown)).thenReturn(true);
        gui.when(() -> StealGui.isNonInteractivePane(filler)).thenReturn(true);
        gui.when(() -> StealGui.isRobberyPouchPane(pouch)).thenReturn(true);
        gui.when(() -> StealGui.forChest(any(), anyInt(), anyDouble(), any(), anyDouble(), anyBoolean(), anyBoolean())).thenReturn("Chest title");
        gui.when(() -> StealGui.forPickpocket(any(), anyInt(), any())).thenReturn("Pockets title");
        gui.when(() -> StealGui.forRobbery(anyLong(), any())).thenReturn("Robbery title");
        risk = mockStatic(RiskCalculator.class); risk.when(() -> RiskCalculator.getDexterity(thief)).thenReturn(7);
        database = mockStatic(Database.class); factions = mockStatic(FactionManager.class); alerts = mockStatic(PickpocketVictimAlerter.class);
        oldCoreProtect = Cache.coreProtect; Cache.coreProtect = false;
        oldBreakRamp = Parameters.chestBreakChanceRampPerSlot; Parameters.chestBreakChanceRampPerSlot = 1.0;
        StealIgnoreRules.load(List.of());
    }
    @AfterEach void tearDown() {
        Cache.coreProtect = oldCoreProtect; Parameters.chestBreakChanceRampPerSlot = oldBreakRamp; StealIgnoreRules.load(List.of());
        for (MockedStatic<?> mocked : new MockedStatic<?>[]{alerts, factions, database, risk, gui, managers, thievery}) if (mocked != null) mocked.close();
        MockBukkit.unmock();
    }

    @Test void robberyRangeRequiresBothPlayersAndIncludesTheExactDistanceBoundary() {
        var origin = thief.getLocation();
        victim.teleport(origin.clone().add(3, 4, 0));

        assertFalse(RobberyUtil.isWithinRange(null, victim, 5));
        assertFalse(RobberyUtil.isWithinRange(thief, null, 5));
        assertTrue(RobberyUtil.isWithinRange(thief, victim, 5));
        assertFalse(RobberyUtil.isWithinRange(thief, victim, 4.99));
        assertFalse(RobberyUtil.isWithinRange(thief, victim, 0));
        victim.teleport(origin);
        assertTrue(RobberyUtil.isWithinRange(thief, victim, 0));
    }

    @Test void negativeRobberyDistanceAllowsFarTargetsButStillRequiresTheSameWorld() {
        victim.teleport(thief.getLocation().clone().add(1000, 0, 1000));
        assertFalse(RobberyUtil.isWithinRange(thief, victim, 4));
        assertTrue(RobberyUtil.isWithinRange(thief, victim, -1));

        victim.teleport(server.addSimpleWorld("other-world").getSpawnLocation());
        assertFalse(RobberyUtil.isWithinRange(thief, victim, -1));
        assertFalse(RobberyUtil.isWithinRange(thief, victim, 10000));
        assertFalse(RobberyUtil.isWithinRange(thief, null, -1));
    }

    @Test void hiddenSlotsRevealOnceOnlyAfterTargetAndRevealChecksSucceed() {
        ProbeReference ref = new ProbeReference(); Inventory inv = inventory(ref); int slot = ref.getLayout().getGuiSlotForLogical(0);
        assertEquals(thief.getUniqueId(), ref.getThiefId()); assertEquals(StealGuiHolder.Kind.CHEST, ref.getKind());
        assertSame(ref.getSession().getBudget(), ref.getBudget());
        ref.valid = false; ref.revealSlot(thief, inv, slot); assertFalse(ref.session.isRevealed(slot));
        ref.valid = true; ref.allow = false; ref.revealSlot(thief, inv, slot); assertFalse(ref.session.isRevealed(slot));
        ref.allow = true;
        var click = click(inv, slot, unknown, ClickType.LEFT);
        ref.handleClick(click, thief); verify(click).setCancelled(true);
        assertTrue(ref.session.isRevealed(slot)); assertEquals(1, ref.after); assertEquals(1, ref.refreshes);
        ref.revealSlot(thief, inv, slot); assertEquals(1, ref.after);
        ref.tick(thief); gui.verify(() -> StealGui.updateTitle(thief, ref.getHolder(), "Probe title"));
    }

    @Test void hiddenClicksIgnoreBottomInventoryPanesUnsupportedButtonsAndUnrevealedItems() {
        ProbeReference ref = new ProbeReference(); Inventory inv = inventory(ref); int slot = ref.getLayout().getGuiSlotForLogical(0);
        ItemStack loot = new ItemStack(Material.DIAMOND);
        try (var takes = mockStatic(StealTakeHandler.class)) {
            var bottom = click(inv, slot, loot, ClickType.LEFT); when(bottom.getClickedInventory()).thenReturn(thief.getInventory());
            ref.handleClick(bottom, thief);
            for (ItemStack item : new ItemStack[]{null, filler, hidden, loot}) ref.handleClick(click(inv, slot, item, ClickType.LEFT), thief);
            ref.session.markRevealed(slot);
            ref.handleClick(click(inv, slot, loot, ClickType.RIGHT), thief);
            ref.valid = false; ref.handleClick(click(inv, slot, loot, ClickType.LEFT), thief);
            ref.valid = true; ref.source = null; ref.handleClick(click(inv, slot, loot, ClickType.LEFT), thief);
            takes.verifyNoInteractions();
        }
    }

    @Test void revealedHiddenLootDelegatesTakingWithTheMappedSourceAndRefreshCallback() {
        ProbeReference ref = new ProbeReference(); Inventory inv = inventory(ref); int slot = ref.getLayout().getGuiSlotForLogical(0);
        ref.session.markRevealed(slot); ItemStack loot = new ItemStack(Material.DIAMOND);
        try (var takes = mockStatic(StealTakeHandler.class)) {
            takes.when(() -> StealTakeHandler.performTake(eq(thief), same(ref.source), same(ref.getBudget()), eq(0), same(inv), eq(slot), eq(ClickType.SHIFT_LEFT), same(loot), any(), isNull()))
                    .thenAnswer(call -> { ((Runnable)call.getArgument(8)).run(); return true; });
            ref.handleClick(click(inv, slot, loot, ClickType.SHIFT_LEFT), thief);
            assertEquals(1, ref.refreshes);
        }
    }

    @Test void chestRevealAddsRiskSavesAndRefreshesActualContainerSlots() {
        Inventory chest = server.createInventory(null, 9); ItemStack loot = new ItemStack(Material.DIAMOND); chest.setItem(0, loot);
        Block block = chestBlock(chest); ChestLockpickSession session = chestSession(block, chest, 1);
        Runnable close = mock(Runnable.class); var ref = new ChestStealReference(session, close);
        Inventory inv = inventory(ref); int slot = session.getLayout().getGuiSlotForLogical(0);
        assertSame(session, ref.getSession()); assertEquals("Chest title", ref.buildTitle(thief));
        ref.onOpen(thief, inv); assertTrue(thief.nextMessage().contains("probe the container"));
        ref.revealSlot(thief, inv, slot);
        assertTrue(session.isRevealed(slot));
        verify(data).addRiskGain(7, 0.6, RiskSource.CHEST, 1.0); database.verify(() -> Database.savePlayerData(data));
        verify(data).applyRiskDecay(7);
        gui.verify(() -> StealGui.placeRevealedSlot(eq(inv), eq(slot), eq(loot), same(session.getBudget()), same(data), argThat(context -> context.dexterity() == 7 && context.sessionRisk() == 0.25)));
        assertInstanceOf(ContainerStealSource.class, ref.getSource(thief));
        ref.onClose(thief); verify(close).run();
    }

    @Test void chestFailureConsumesOnePickAndBlocksFurtherProbes() {
        for (int heldAmount : new int[]{2, 1, 0}) {
            Inventory chest = server.createInventory(null, 9); var session = chestSession(chestBlock(chest), chest, 0);
            var ref = new ChestStealReference(session, () -> {}); Inventory inv = inventory(ref);
            thief.getInventory().setItemInMainHand(heldAmount == 0 ? null : new ItemStack(Material.STICK, heldAmount));
            int slot = session.getLayout().getGuiSlotForLogical(0); ref.revealSlot(thief, inv, slot);
            assertTrue(session.isLockpickBroken()); assertFalse(session.isRevealed(slot));
            if (heldAmount == 2) assertEquals(1, thief.getInventory().getItemInMainHand().getAmount());
            else assertTrue(thief.getInventory().getItemInMainHand().getType().isAir());
            assertTrue(thief.nextMessage().contains("lockpick broke"));
            ref.revealSlot(thief, inv, slot); assertTrue(thief.nextMessage().contains("lockpick is broken"));
        }
    }

    @Test void removedChestEndsSessionAndTakeCallbackLogsAndDropsCluesOnlyForExistingContainer() {
        Inventory chest = server.createInventory(null, 9); Block block = chestBlock(chest);
        var session = chestSession(block, chest, 1); var ref = new ChestStealReference(session, () -> {});
        var callback = ref.getTakeCallback(thief); ItemStack taken = new ItemStack(Material.DIAMOND);
        try (var clues = mockStatic(ClueDropper.class)) {
            callback.onAfterTake(thief, taken, 2.5, false, 0);
            clues.verify(() -> ClueDropper.tryDropChestClue(thief, session, block, 7, 0.6, 2.5, false));
            Cache.coreProtect = true;
            callback.onAfterTake(thief, taken, 1.5, true, 0);
            clues.verify(() -> ClueDropper.tryDropChestClue(thief, session, block, 7, 0.6, 1.5, true));
            verify(Thievery.getCoreProtect()).logContainerTransaction("Thief_lockpick", block.getLocation());
            when(block.getState()).thenReturn(mock(BlockState.class));
            assertFalse(ref.validateTarget(thief)); verify(manager).endSession(thief.getUniqueId(), true);
            assertNull(ref.getSource(thief));
            ref.refreshGui(thief, inventory(ref)); callback.onAfterTake(thief, taken, 8, false, 0);
            clues.verifyNoMoreInteractions();
        }
    }

    @Test void pickpocketRevealTracksRiskAndVictimAlertAndRefreshesMappedInventory() {
        PickpocketSession session = pickSession(); Runnable close = mock(Runnable.class); var ref = new PickpocketReference(session, close);
        Inventory inv = inventory(ref); int slot = session.getLayout().getGuiSlotForLogical(0);
        ItemStack loot = new ItemStack(Material.DIAMOND); victim.getInventory().setItem(PlayerSlotMap.toPlayerSlot(0), loot);
        assertSame(session, ref.getSession()); assertEquals("Pockets title", ref.buildTitle(thief));
        assertEquals(PlayerSlotMap.toPlayerSlot(0), ref.resolveTakeSlot(0)); assertTrue(ref.validateTarget(thief));
        ref.revealSlot(thief, inv, slot);
        assertTrue(session.isRevealed(slot));
        verify(data).addRiskGain(7, 0, RiskSource.PICKPOCKET); database.verify(() -> Database.savePlayerData(data));
        alerts.verify(() -> PickpocketVictimAlerter.tryAlert(thief, victim, data, session.getTargetKey(), 7));
        gui.verify(() -> StealGui.placeRevealedSlot(inv, slot, loot, session.getBudget(), data));
        assertInstanceOf(PlayerStealSource.class, ref.getSource(thief));
        ref.onClose(thief); verify(close).run();
        victim.disconnect(); assertFalse(ref.validateTarget(thief)); assertTrue(thief.nextMessage().contains("no longer available"));
        verify(manager).endSession(thief.getUniqueId(), true);
        assertFalse(ref.onBeforeReveal(thief, inv, slot)); assertNull(ref.getSource(thief)); ref.refreshGui(thief, inv);
    }

    @Test void pickpocketDistanceWatchEndsDistantTargetsAndCancelsAfterSessionEnds() {
        var ref = new PickpocketReference(pickSession(), () -> {}); Inventory inv = inventory(ref);
        when(manager.hasSession(thief.getUniqueId())).thenReturn(true);
        ref.onOpen(thief, inv); assertTrue(thief.nextMessage().contains("probe their pockets"));
        server.getScheduler().performTicks(20); verify(manager, never()).endSession(any(), anyBoolean());
        victim.teleport(thief.getLocation().clone().add(20, 0, 0));
        server.getScheduler().performTicks(20);
        assertTrue(thief.nextMessage().contains("too far away")); verify(manager).endSession(thief.getUniqueId(), false);
        when(manager.hasSession(thief.getUniqueId())).thenReturn(false);
        server.getScheduler().performTicks(20);
        clearInvocations(manager); server.getScheduler().performTicks(40); verifyNoInteractions(manager);
        ref.onClose(thief);
    }

    @Test void pickpocketDistanceWatchEndsWhenTheVictimDisconnects() {
        var ref = new PickpocketReference(pickSession(), () -> {});
        when(manager.hasSession(thief.getUniqueId())).thenReturn(true);
        ref.onOpen(thief, inventory(ref)); assertTrue(thief.nextMessage().contains("probe their pockets"));
        victim.disconnect(); server.getScheduler().performTicks(20);
        assertTrue(thief.nextMessage().contains("too far away"));
        verify(manager).endSession(thief.getUniqueId(), false);
        ref.onClose(thief);
    }

    @Test void pickpocketWatchHandlesMissingVictimAndExplicitClose() {
        var ref = new PickpocketReference(pickSession(), () -> {}); Inventory inv = inventory(ref);
        when(manager.hasSession(thief.getUniqueId())).thenReturn(true);
        ref.onOpen(thief, inv); ref.onOpen(thief, inv);
        server.getScheduler().performTicks(20); verify(manager).hasSession(thief.getUniqueId());
        ref.onClose(thief); clearInvocations(manager); server.getScheduler().performTicks(40); verifyNoInteractions(manager);
        victim.disconnect(); ref.onOpen(thief, inv); server.getScheduler().performTicks(40); verifyNoInteractions(manager);
    }

    @Test void robberyTicksExpireOrEndMissingTargetsAndCloseOnlyActiveSessions() {
        var session = robberySession(); Runnable end = mock(Runnable.class); var ref = new RobberyStealReference(session, end);
        assertSame(session, ref.getSession()); assertEquals("Robbery title", ref.buildTitle(thief));
        session.setState(RobberySession.State.PENDING_ACCEPT); ref.onClose(thief); verifyNoInteractions(end);
        session.setState(RobberySession.State.ACTIVE); session.setActiveEndMs(0); ref.tick(thief);
        assertTrue(thief.nextMessage().contains("window has ended")); verify(end).run();
        clearInvocations(end); session.setActiveEndMs(System.currentTimeMillis() + 60000); victim.disconnect(); ref.tick(thief);
        assertTrue(thief.nextMessage().contains("no longer available")); verify(end).run();
        ref.onClose(thief); verify(end, times(2)).run();
    }

    @Test void robberyClicksRejectInactiveBottomUnsupportedAndDecorativeItems() {
        var session = robberySession(); var ref = new RobberyStealReference(session, () -> {}); Inventory inv = inventory(ref);
        int slot = session.getLayout().getGuiSlotForLogical(0); ItemStack loot = new ItemStack(Material.DIAMOND);
        try (var takes = mockStatic(StealTakeHandler.class)) {
            session.setState(RobberySession.State.PENDING_ACCEPT); ref.handleClick(click(inv, slot, loot, ClickType.LEFT), thief);
            session.setState(RobberySession.State.ACTIVE);
            var bottom = click(inv, slot, loot, ClickType.LEFT); when(bottom.getClickedInventory()).thenReturn(thief.getInventory()); ref.handleClick(bottom, thief);
            ref.handleClick(click(inv, slot, loot, ClickType.RIGHT), thief);
            for (ItemStack item : new ItemStack[]{null, filler, hidden}) ref.handleClick(click(inv, slot, item, ClickType.LEFT), thief);
            takes.verifyNoInteractions();
        }
    }

    @Test void robberyItemClickDelegatesToVictimSourceAndRefreshCallback() {
        var session = robberySession(); Runnable end = mock(Runnable.class); var ref = new RobberyStealReference(session, end); Inventory inv = inventory(ref);
        int slot = session.getLayout().getGuiSlotForLogical(0); ItemStack loot = new ItemStack(Material.DIAMOND);
        try (var takes = mockStatic(StealTakeHandler.class)) {
            takes.when(() -> StealTakeHandler.performTake(eq(thief), any(PlayerStealSource.class), same(session.getBudget()), eq(0), same(inv), eq(slot), eq(ClickType.LEFT), same(loot), any()))
                    .thenAnswer(call -> { ((Runnable)call.getArgument(8)).run(); return true; });
            ref.handleClick(click(inv, slot, loot, ClickType.LEFT), thief);
            gui.verify(() -> StealGui.placeRobberyPouchSlot(inv, victim, data, session.getBudget()));
            victim.disconnect(); ref.handleClick(click(inv, slot, loot, ClickType.LEFT), thief);
            assertTrue(thief.nextMessage().contains("no longer available")); verify(end).run();
        }
    }

    @Test void robberyPouchClicksRespectClickSizeBalanceAndBudgetAndChargeOnlyTransferredValue() {
        var session = robberySession(); Runnable end = mock(Runnable.class); var ref = new RobberyStealReference(session, end); Inventory inv = inventory(ref);
        session.getBudget().addUsed(80);
        try (var money = mockStatic(DenarMoney.class)) {
            money.when(() -> DenarMoney.getPouchBalance(victim)).thenReturn(50.0);
            money.when(() -> DenarMoney.maxStealableDenars(anyDouble())).thenAnswer(call -> (int)Math.floor((double)call.getArgument(0) / 0.5));
            money.when(DenarMoney::amountPerMoney).thenReturn(0.5);
            ref.handleClick(click(inv, StealGui.ROBBERY_POUCH_GUI_SLOT, pouch, ClickType.LEFT), thief);
            money.verify(() -> DenarMoney.transferPouch(victim, thief, 10)); assertEquals(85, session.getBudget().getUsed());
            ref.handleClick(click(inv, StealGui.ROBBERY_POUCH_GUI_SLOT, pouch, ClickType.SHIFT_LEFT), thief);
            money.verify(() -> DenarMoney.transferPouch(victim, thief, 30)); assertEquals(100, session.getBudget().getUsed());
            money.when(() -> DenarMoney.getPouchBalance(victim)).thenReturn(0.0);
            ref.handleClick(click(inv, StealGui.ROBBERY_POUCH_GUI_SLOT, pouch, ClickType.LEFT), thief); assertEquals(100, session.getBudget().getUsed());
            victim.disconnect(); ref.handleClick(click(inv, StealGui.ROBBERY_POUCH_GUI_SLOT, pouch, ClickType.LEFT), thief);
            assertTrue(thief.nextMessage().contains("no longer available")); verify(end).run();
        }
    }

    @Test void robberyRefreshPlacesVisibleLootSkipsEmptyIgnoredOrUnaffordableItemsAndUpdatesTitle() {
        var session = robberySession(); var ref = new RobberyStealReference(session, () -> {}); Inventory inv = inventory(ref);
        ItemStack loot = new ItemStack(Material.DIAMOND); victim.getInventory().setItem(0, loot);
        ItemStack ignored = new ItemStack(Material.PAPER); var meta = ignored.getItemMeta(); meta.setDisplayName("Ignore this"); ignored.setItemMeta(meta); victim.getInventory().setItem(1, ignored);
        ItemStack expensive = new ItemStack(Material.EMERALD); victim.getInventory().setItem(2, expensive); StealIgnoreRules.load(List.of("Ignore"));
        ItemStack display = new ItemStack(Material.DIAMOND, 2);
        try (var displays = mockStatic(StealItemDisplay.class)) {
            displays.when(() -> StealItemDisplay.buildRepresentation(loot, session.getBudget().getRemaining(), data)).thenReturn(display);
            ref.refreshGui(thief, victim, inv);
            assertEquals(display, inv.getItem(session.getLayout().getGuiSlotForLogical(0)));
            assertNull(inv.getItem(session.getLayout().getGuiSlotForLogical(1))); assertNull(inv.getItem(session.getLayout().getGuiSlotForLogical(2)));
            gui.verify(() -> StealGui.placeRobberyPouchSlot(inv, victim, data, session.getBudget()));
            gui.verify(() -> StealGui.updateTitle(thief, ref.getHolder(), "Robbery title"));
            thief.openInventory(inv); ref.tick(thief);
        }
        gui.when(() -> StealGui.buildRobberyGui(ref.getHolder(), session.getLayout(), "Robbery title", victim, session.getBudget(), data)).thenReturn(inv);
        assertSame(inv, ref.buildInventory(thief, victim));
    }

    @Test void offlineVictimHandlesEndRobberyForTicksItemClicksAndPouchClicksWithoutTaking() {
        var session=robberySession();Runnable end=mock(Runnable.class);var ref=new RobberyStealReference(session,end);Inventory inv=inventory(ref);
        ItemStack loot=new ItemStack(Material.DIAMOND,3);victim.getInventory().setItem(0,loot);
        victim.disconnect();assertFalse(victim.isOnline());
        // A platform lookup may retain the handle while disconnect processing finishes.
        try(var bukkit=mockStatic(org.bukkit.Bukkit.class,CALLS_REAL_METHODS);var takes=mockStatic(StealTakeHandler.class);var money=mockStatic(DenarMoney.class)) {
            bukkit.when(()->org.bukkit.Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            ref.tick(thief);assertTrue(thief.nextMessage().contains("no longer available"));
            ref.handleClick(click(inv,session.getLayout().getGuiSlotForLogical(0),loot,ClickType.LEFT),thief);assertTrue(thief.nextMessage().contains("no longer available"));
            ref.handleClick(click(inv,StealGui.ROBBERY_POUCH_GUI_SLOT,pouch,ClickType.LEFT),thief);assertTrue(thief.nextMessage().contains("no longer available"));
            verify(end,times(3)).run();takes.verifyNoInteractions();money.verifyNoInteractions();
            assertEquals(loot,victim.getInventory().getItem(0));assertEquals(0,session.getBudget().getUsed());
        }
    }

    @Test void offlinePickpocketHandleEndsSessionBeforeRevealingOrChargingRisk() {
        var session=pickSession();var ref=new PickpocketReference(session,()->{});Inventory inv=inventory(ref);int slot=session.getLayout().getGuiSlotForLogical(0);
        ItemStack loot=new ItemStack(Material.DIAMOND);victim.getInventory().setItem(9,loot);victim.disconnect();
        try(var bukkit=mockStatic(org.bukkit.Bukkit.class,CALLS_REAL_METHODS)) {
            bukkit.when(()->org.bukkit.Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            ref.revealSlot(thief,inv,slot);
            assertFalse(session.isRevealed(slot));assertTrue(thief.nextMessage().contains("no longer available"));verify(manager).endSession(thief.getUniqueId(),true);
            verifyNoInteractions(data);alerts.verifyNoInteractions();assertEquals(loot,victim.getInventory().getItem(9));
        }
    }

    @Test void distanceWatchEndsDisconnectedThiefWithoutSendingFeedbackOrClosingAgain() {
        Player departing=spy(thief);Runnable closed=mock(Runnable.class);var ref=new PickpocketReference(pickSession(),closed);
        when(manager.hasSession(thief.getUniqueId())).thenReturn(true);ref.onOpen(departing,inventory(ref));
        thief.disconnect();victim.disconnect();assertFalse(departing.isOnline());clearInvocations(departing);
        server.getScheduler().performTicks(20);
        verify(manager).endSession(thief.getUniqueId(),false);verify(departing,never()).sendMessage(anyString());verify(departing,never()).closeInventory();
        verify(departing,never()).playSound(any(org.bukkit.Location.class),any(org.bukkit.Sound.class),anyFloat(),anyFloat());
        ref.onClose(departing);verify(closed).run();clearInvocations(manager);server.getScheduler().performTicks(40);verifyNoInteractions(manager);
    }

    @Test void failedProbeWithAnEmptyHandKeepsOtherItemsAndClosesNormally() {
        Inventory chest=server.createInventory(null,9);ItemStack loot=new ItemStack(Material.DIAMOND);chest.setItem(0,loot);
        var session=chestSession(chestBlock(chest),chest,0);Runnable closed=mock(Runnable.class);var ref=new ChestStealReference(session,closed);Inventory inv=inventory(ref);
        thief.getInventory().setItemInMainHand(new ItemStack(Material.STICK));thief.getInventory().setItem(8,new ItemStack(Material.EMERALD,4));ref.onOpen(thief,inv);thief.nextMessage();
        thief.getInventory().setItemInMainHand(new ItemStack(Material.AIR));ref.revealSlot(thief,inv,session.getLayout().getGuiSlotForLogical(0));
        assertTrue(session.isLockpickBroken());assertTrue(session.getRevealedGuiSlots().isEmpty());assertTrue(thief.getInventory().getItemInMainHand().getType().isAir());
        assertEquals(new ItemStack(Material.EMERALD,4),thief.getInventory().getItem(8));assertEquals(loot,chest.getItem(0));assertTrue(thief.nextMessage().contains("lockpick broke"));ref.onClose(thief);verify(closed).run();
    }

    @Test void robberyRefreshTreatsExplicitAirStorageEntriesAsEmpty() {
        var session=robberySession();var ref=new RobberyStealReference(session,()->{});Inventory inv=inventory(ref);
        Player victimHandle=spy(victim);var inventoryHandle=spy(victim.getInventory());
        ItemStack[] storage=victim.getInventory().getStorageContents();storage[0]=new ItemStack(Material.AIR);storage[1]=new ItemStack(Material.DIAMOND);
        doReturn(storage).when(inventoryHandle).getStorageContents();doReturn(inventoryHandle).when(victimHandle).getInventory();
        ItemStack display=new ItemStack(Material.DIAMOND);
        try(var displays=mockStatic(StealItemDisplay.class)) {
            displays.when(()->StealItemDisplay.buildRepresentation(storage[1],session.getBudget().getRemaining(),data)).thenReturn(display);
            ref.refreshGui(thief,victimHandle,inv);
            assertNull(inv.getItem(session.getLayout().getGuiSlotForLogical(0)));assertEquals(display,inv.getItem(session.getLayout().getGuiSlotForLogical(1)));
            displays.verify(()->StealItemDisplay.buildRepresentation(storage[0],session.getBudget().getRemaining(),data),never());
            gui.verify(()->StealGui.placeRobberyPouchSlot(inv,victimHandle,data,session.getBudget()));assertEquals(0,session.getBudget().getUsed());
        }
    }

    @Test void openingRobberyThroughTheManagerRegistersAndOpensTheSessionUntilTheGuiCloses() throws Exception {
        var singleton=StealManager.class.getDeclaredField("instance");singleton.setAccessible(true);
        Object previous=singleton.get(null);
        try {
            managers.when(StealManager::getInstance).thenCallRealMethod();
            managers.when(()->StealManager.getStealGuiHolder(any(Inventory.class))).thenCallRealMethod();
            StealManager realManager=new StealManager();
            server.getPluginManager().registerEvents(realManager,MockBukkit.createMockPlugin());
            var session=robberySession();Runnable end=mock(Runnable.class);var ref=new RobberyStealReference(session,end);Inventory inv=inventory(ref);
            long deadline=session.getActiveEndMs();

            realManager.openSession(thief,ref,inv);

            assertSame(realManager,StealManager.getInstance());assertTrue(realManager.hasSession(thief.getUniqueId()));assertSame(ref,realManager.getSession(thief.getUniqueId()));
            assertSame(inv,thief.getOpenInventory().getTopInventory());assertSame(ref.getHolder(),thief.getOpenInventory().getTopInventory().getHolder());
            assertEquals(RobberySession.State.ACTIVE,session.getState());assertEquals(deadline,session.getActiveEndMs());assertEquals(0,session.getBudget().getUsed());verifyNoInteractions(end);
            thief.closeInventory();assertFalse(realManager.hasSession(thief.getUniqueId()));verify(end).run();
        } finally {singleton.set(null,previous);managers.when(StealManager::getInstance).thenReturn(manager);}
    }

    @Test void unknownPaneInAnUnmappedGuiSlotCannotRevealOrTakeFromTheSource() {
        var ref=new ProbeReference();Inventory inv=inventory(ref);int staleSlot=unmappedSlot(ref.getLayout());inv.setItem(staleSlot,unknown);
        try(var takes=mockStatic(StealTakeHandler.class)) {
            var event=click(inv,staleSlot,unknown,ClickType.LEFT);ref.handleClick(event,thief);
            verify(event).setCancelled(true);assertTrue(ref.session.getRevealedGuiSlots().isEmpty());assertEquals(0,ref.after);assertEquals(0,ref.refreshes);
            assertEquals(0,ref.getBudget().getUsed());assertEquals(unknown,inv.getItem(staleSlot));verifyNoInteractions(ref.source);takes.verifyNoInteractions();
        }
    }

    @Test void publicUnmappedRevealMarkersCannotEnableChestOrPickpocketTheftOrBreakRefresh() {
        Inventory chest=server.createInventory(null,18);ItemStack loot=new ItemStack(Material.DIAMOND,3);chest.setItem(0,loot);victim.getInventory().setItem(9,loot);
        ChestLockpickSession chestSession=chestSession(chestBlock(chest),chest,1);var chestRef=new ChestStealReference(chestSession,()->{});
        PickpocketSession pockets=pickSession();var pocketRef=new PickpocketReference(pockets,()->{});
        try(var takes=mockStatic(StealTakeHandler.class)) {
            for(HiddenStealReference ref:List.of(chestRef,pocketRef)) {
                Inventory inv=inventory(ref);int staleSlot=unmappedSlot(ref.getLayout());ref.hiddenSession().markRevealed(staleSlot);inv.setItem(staleSlot,loot);
                ref.handleClick(click(inv,staleSlot,loot,ClickType.LEFT),thief);
                assertDoesNotThrow(()->ref.refreshGui(thief,inv));assertEquals(0,ref.getBudget().getUsed());assertEquals(loot,inv.getItem(staleSlot));
            }
            takes.verifyNoInteractions();assertEquals(loot,chest.getItem(0));assertEquals(loot,victim.getInventory().getItem(9));assertTrue(chestSession.getRevealedChestSlots().isEmpty());
            gui.verify(()->StealGui.placeRevealedSlot(any(),anyInt(),any(),any(),any()),never());
            gui.verify(()->StealGui.placeRevealedSlot(any(),anyInt(),any(),any(),any(),any()),never());
        }
    }

    @Test void negativeCustomSourceMappingRejectsTakingWithoutCallingTheSource() {
        var ref=new ProbeReference();ref.takeSlot=-1;Inventory inv=inventory(ref);int slot=ref.getLayout().getGuiSlotForLogical(0);ref.session.markRevealed(slot);
        try(var takes=mockStatic(StealTakeHandler.class)) {
            ref.handleClick(click(inv,slot,new ItemStack(Material.DIAMOND),ClickType.LEFT),thief);
            assertEquals(0,ref.getBudget().getUsed());assertEquals(0,ref.refreshes);assertEquals(Set.of(slot),ref.session.getRevealedGuiSlots());verifyNoInteractions(ref.source);takes.verifyNoInteractions();
        }
    }

    @Test void alreadyRevealedUnknownPaneTakesRealSourceLootWithoutGrantingThePaneOrRevealingAgain() {
        var ref=new ProbeReference();Inventory source=server.createInventory(null,9);source.setItem(0,new ItemStack(Material.DIAMOND));ref.source=new ContainerStealSource(source);
        Inventory inv=inventory(ref);int slot=ref.getLayout().getGuiSlotForLogical(0);ref.session.markRevealed(slot);inv.setItem(slot,unknown);
        try(var values=mockStatic(net.tfminecraft.thievery.category.CategoryHandler.class);var clues=mockStatic(net.tfminecraft.rpcharacters.utils.ClueGiver.class)) {
            values.when(()->net.tfminecraft.thievery.category.CategoryHandler.getPerItemValue(any())).thenReturn(2.0);
            values.when(()->net.tfminecraft.thievery.category.CategoryHandler.getTotalValue(any())).thenReturn(2.0);
            ref.handleClick(click(inv,slot,unknown,ClickType.LEFT),thief);
            assertNull(source.getItem(0));assertEquals(new ItemStack(Material.DIAMOND),thief.getInventory().getItem(0));assertFalse(thief.getInventory().contains(unknown));
            assertEquals(2,ref.getBudget().getUsed());assertEquals(0,ref.after);assertEquals(1,ref.refreshes);assertEquals(Set.of(slot),ref.session.getRevealedGuiSlots());
        }
    }

    @Test void nonPouchItemInReservedRobberySlotCannotAddressAPlayerInventorySlot() {
        var session=robberySession();var ref=new RobberyStealReference(session,()->{});Inventory inv=inventory(ref);ItemStack loot=new ItemStack(Material.DIAMOND);victim.getInventory().setItem(0,loot);
        assertNull(session.getLayout().getLogicalForGui(StealGui.ROBBERY_POUCH_GUI_SLOT));
        try(var takes=mockStatic(StealTakeHandler.class);var money=mockStatic(DenarMoney.class)) {
            var event=click(inv,StealGui.ROBBERY_POUCH_GUI_SLOT,loot,ClickType.LEFT);ref.handleClick(event,thief);
            verify(event).setCancelled(true);takes.verifyNoInteractions();money.verifyNoInteractions();assertEquals(loot,victim.getInventory().getItem(0));assertEquals(0,session.getBudget().getUsed());assertTrue(thief.getInventory().isEmpty());
        }
    }

    private int unmappedSlot(StealGui.Layout layout) {
        return java.util.stream.IntStream.range(0,layout.getGuiSize()).filter(slot->layout.getLogicalForGui(slot)==null).findFirst().orElseThrow();
    }

    private ChestLockpickSession chestSession(Block block, Inventory chest, double chance) {
        var config = new YamlConfiguration(); config.set("strength", 0.6); config.set("capacity", 50);
        return new ChestLockpickSession(thief.getUniqueId(), block, new LockpickDefinition("pick", config), chance, chest, "chest", LockTypeProfile.IDENTITY);
    }
    private Block chestBlock(Inventory chest) {
        Block block = mock(Block.class); Container state = mock(Container.class); when(state.getInventory()).thenReturn(chest);
        when(block.getState()).thenReturn(state); when(block.getLocation()).thenReturn(thief.getLocation()); return block;
    }
    private PickpocketSession pickSession() { return new PickpocketSession(thief.getUniqueId(), victim.getUniqueId(), new StealBudget(50), StealGui.Layout.create(36)); }
    private RobberySession robberySession() {
        var session = new RobberySession(thief.getUniqueId(), victim.getUniqueId(), new StealBudget(100), StealGui.Layout.createRobbery(36));
        session.setState(RobberySession.State.ACTIVE); session.setActiveEndMs(System.currentTimeMillis() + 60000); return session;
    }
    private Inventory inventory(StealReference ref) { return server.createInventory(ref.getHolder(), ref.getLayout().getGuiSize()); }
    private InventoryClickEvent click(Inventory inv, int slot, ItemStack item, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        when(event.getClickedInventory()).thenReturn(inv); when(event.getView().getTopInventory()).thenReturn(inv);
        when(event.getSlot()).thenReturn(slot); when(event.getCurrentItem()).thenReturn(item); when(event.getClick()).thenReturn(type); return event;
    }
    private class ProbeReference extends HiddenStealReference {
        final HiddenStealSession session = new HiddenStealSession(new StealBudget(10), StealGui.Layout.create(1), "test");
        boolean valid = true, allow = true; int after, refreshes, takeSlot; StealSource source = mock(StealSource.class);
        ProbeReference() { super(thief.getUniqueId(), StealGuiHolder.Kind.CHEST); }
        public StealSession getSession() { return session; }
        public String buildTitle(Player player) { return "Probe title"; }
        protected boolean validateTarget(Player player) { return valid; }
        protected boolean onBeforeReveal(Player player, Inventory inv, int slot) { return allow; }
        protected void onAfterReveal(Player player, Inventory inv, int slot) { after++; }
        public void refreshGui(Player player, Inventory inv) { refreshes++; }
        protected StealSource getSource(Player player) { return source; }
        protected int resolveTakeSlot(int logicalSlot) { return takeSlot < 0 ? takeSlot : super.resolveTakeSlot(logicalSlot); }
        public void onClose(Player player) {}
    }
}
