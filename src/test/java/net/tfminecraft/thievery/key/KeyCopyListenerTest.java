package net.tfminecraft.thievery.key;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import net.tfminecraft.thievery.key.KeyCopyHandler.CopyMetadata;
import net.tfminecraft.thievery.loader.KeyCopyLoader;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class KeyCopyListenerTest {
    enum Craft { MOLD, PERMANENT, PAPER_MASTER, PAPER_COPY }
    enum CreatorMutation { CLEAR, AIR, REPLACE }

    private final KeyCopyListener listener = new KeyCopyListener();
    private InventoryClickEvent event;
    private Player player;
    private PlayerInventory inventory;
    private InventoryView view;
    private ItemStack cursor;
    private ItemStack source;
    private ItemStack result;
    private final CopyMetadata metadata = new CopyMetadata("door-id", 0.7, "copper");
    private MockedStatic<KeyCopyHandler> handler;
    private MockedStatic<KeyCopyLoader> loader;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        event = mock(InventoryClickEvent.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        view = mock(InventoryView.class);
        Inventory clicked = mock(Inventory.class);
        when(clicked.getType()).thenReturn(InventoryType.PLAYER);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getView()).thenReturn(view);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        handler = mockStatic(KeyCopyHandler.class);
        loader = mockStatic(KeyCopyLoader.class);
    }

    @AfterEach
    void tearDown() {
        handler.close();
        loader.close();
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @EnumSource(Craft.class)
    void successfulCraftConsumesOneIngredientAndPreservesReusableSource(Craft craft) {
        prepare(craft, 3, 2);
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        verify(inventory).addItem(result);
        ArgumentCaptor<ItemStack> remainingCursor = ArgumentCaptor.forClass(ItemStack.class);
        verify(view).setCursor(remainingCursor.capture());
        assertEquals(2, remainingCursor.getValue().getAmount());
        assertEquals(cursor.getType(), remainingCursor.getValue().getType());
        assertEquals(3, cursor.getAmount());
        if (craft == Craft.PERMANENT) {
            ArgumentCaptor<ItemStack> remainingSource = ArgumentCaptor.forClass(ItemStack.class);
            verify(event).setCurrentItem(remainingSource.capture());
            assertEquals(1, remainingSource.getValue().getAmount());
            assertEquals(source.getType(), remainingSource.getValue().getType());
        } else if (craft == Craft.MOLD || craft == Craft.PAPER_MASTER) {
            verify(event).setCurrentItem(source);
        } else {
            verify(event, never()).setCurrentItem(any());
        }
        assertEquals(2, source.getAmount());
        if (isPaper(craft)) {
            handler.verify(() -> KeyCopyHandler.recordPaperCooldown(player, "door-id"));
            verify(player).sendMessage(contains("Paper key created"));
            verify(player).playSound(player.getLocation(), Sound.BLOCK_WOOL_BREAK, 1f, 1f);
        } else {
            handler.verify(() -> KeyCopyHandler.recordPaperCooldown(any(), any()), never());
            verify(player).sendMessage(contains(craft == Craft.MOLD ? "Key mold created" : "Key copy created"));
            verify(player).playSound(player.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1f, 1f);
        }
    }

    @ParameterizedTest
    @EnumSource(Craft.class)
    void singleIngredientsAreClearedWhenSuccessfullyCrafted(Craft craft) {
        prepare(craft, 1, 1);
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        verify(view).setCursor(null);
        if (craft == Craft.PERMANENT) {
            verify(event).setCurrentItem(null);
        }
    }

    @ParameterizedTest
    @EnumSource(Craft.class)
    void fullInventoryDoesNotConsumeIngredientsOrRecordCooldown(Craft craft) {
        prepare(craft, 3, 2);
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, result);
        when(inventory.addItem(result)).thenReturn(leftover);
        listener.onInventoryClick(event);
        verify(player).sendMessage(contains("Your inventory is full"));
        verify(event, never()).setCancelled(true);
        verify(view, never()).setCursor(any());
        assertEquals(3, cursor.getAmount());
        assertEquals(2, source.getAmount());
        handler.verify(() -> KeyCopyHandler.recordPaperCooldown(any(), any()), never());
    }

    @ParameterizedTest
    @EnumSource(Craft.class)
    void missingSourceMetadataDoesNotCreateItemsOrConsumeIngredients(Craft craft) {
        prepare(craft, 1, 1);
        handler.when(() -> KeyCopyHandler.extractCopyMetadata(source)).thenReturn(null);
        listener.onInventoryClick(event);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(event, never()).setCancelled(true);
        verify(view, never()).setCursor(any());
        if (isPaper(craft)) {
            verify(player).sendMessage(contains("That key has not been used yet"));
        }
    }

    @ParameterizedTest
    @EnumSource(Craft.class)
    void unavailableOutputTemplateLeavesIngredientsAndCooldownUntouched(Craft craft) {
        prepare(craft, 1, 1);
        handler.when(() -> KeyCopyHandler.createMold(metadata)).thenReturn(null);
        handler.when(() -> KeyCopyHandler.createPermanentCopy(metadata)).thenReturn(null);
        handler.when(() -> KeyCopyHandler.createPaperCopy(metadata)).thenReturn(null);
        listener.onInventoryClick(event);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(view, never()).setCursor(any());
        verify(event, never()).setCancelled(true);
        handler.verify(() -> KeyCopyHandler.recordPaperCooldown(any(), any()), never());
    }

    @Test
    void initializationFailureExplainsWhyMasterKeyCannotBeCopied() {
        prepare(Craft.MOLD, 1, 1);
        handler.when(() -> KeyCopyHandler.ensureKeyInitialized(source)).thenReturn(false);
        listener.onInventoryClick(event);
        verify(player).sendMessage(contains("That key cannot be used for a mold"));
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(view, never()).setCursor(any());
        loader.when(() -> KeyCopyLoader.matchesMoldInput(cursor)).thenReturn(false);
        loader.when(() -> KeyCopyLoader.matchesPaperInput(cursor)).thenReturn(true);
        listener.onInventoryClick(event);
        verify(player).sendMessage(contains("That key cannot be copied to paper"));
        verify(event, never()).setCancelled(true);
    }

    @Test
    void activePaperCooldownReportsRemainingMinutesWithoutGivingAnItem() {
        prepare(Craft.PAPER_MASTER, 1, 1);
        handler.when(() -> KeyCopyHandler.canCraftPaper(player, "door-id")).thenReturn(false);
        handler.when(() -> KeyCopyHandler.getPaperCooldownRemainingMinutes(player, "door-id")).thenReturn(12L);
        listener.onInventoryClick(event);
        verify(player).sendMessage(contains("another 12 minute(s)"));
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(view, never()).setCursor(any());
        verify(event, never()).setCancelled(true);
        handler.verify(() -> KeyCopyHandler.recordPaperCooldown(any(), any()), never());
    }

    @Test
    void paperCannotBeCraftedFromAMoldAnotherPaperKeyOrAnUnrecognizedItem() {
        prepare(Craft.PAPER_COPY, 1, 1);
        handler.when(() -> KeyCopyHandler.isPermanentCopy(source)).thenReturn(false);
        handler.when(() -> KeyCopyHandler.isMold(source)).thenReturn(true);
        listener.onInventoryClick(event);
        verify(player, never()).sendMessage(anyString());
        handler.when(() -> KeyCopyHandler.isMold(source)).thenReturn(false);
        handler.when(() -> KeyCopyHandler.isPaperCopy(source)).thenReturn(true);
        listener.onInventoryClick(event);
        verify(player).sendMessage(contains("cannot copy a paper key to paper"));
        handler.when(() -> KeyCopyHandler.isPaperCopy(source)).thenReturn(false);
        listener.onInventoryClick(event);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(event, never()).setCancelled(true);
    }

    @Test
    void validCopyIngredientsStillRequireTheMatchingReusableSource() {
        prepare(Craft.MOLD, 2, 1);
        handler.when(() -> KeyCopyHandler.isMasterKey(source)).thenReturn(false);
        listener.onInventoryClick(event);
        loader.when(() -> KeyCopyLoader.matchesMoldInput(cursor)).thenReturn(false);
        loader.when(() -> KeyCopyLoader.matchesCopyInput(cursor)).thenReturn(true);
        listener.onInventoryClick(event);
        handler.verify(() -> KeyCopyHandler.ensureKeyInitialized(any()), never());
        handler.verify(() -> KeyCopyHandler.extractCopyMetadata(any()), never());
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(view, never()).setCursor(any());
        verify(event, never()).setCurrentItem(any());
        verify(event, never()).setCancelled(true);
        assertEquals(2, cursor.getAmount());
        assertEquals(1, source.getAmount());
    }

    @Test
    void modifiedClicksOutsidePlayerInventoryAndNonplayersAreIgnored() {
        prepare(Craft.MOLD, 1, 1);
        for (ClickType click : new ClickType[] {ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.MIDDLE, ClickType.DOUBLE_CLICK}) {
            when(event.getClick()).thenReturn(click);
            listener.onInventoryClick(event);
        }
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getClickedInventory()).thenReturn(null);
        listener.onInventoryClick(event);
        Inventory chest = mock(Inventory.class);
        when(chest.getType()).thenReturn(InventoryType.CHEST);
        when(event.getClickedInventory()).thenReturn(chest);
        listener.onInventoryClick(event);
        when(event.getWhoClicked()).thenReturn(mock(HumanEntity.class));
        listener.onInventoryClick(event);
        handler.verifyNoInteractions();
        loader.verifyNoInteractions();
        verify(event, never()).setCancelled(true);
    }

    @Test
    void emptySlotsAndUnrelatedIngredientPairsAreIgnored() {
        prepare(Craft.MOLD, 1, 1);
        for (ItemStack empty : new ItemStack[] {null, new ItemStack(Material.AIR)}) {
            when(event.getCursor()).thenReturn(empty);
            listener.onInventoryClick(event);
            when(event.getCursor()).thenReturn(cursor);
            when(event.getCurrentItem()).thenReturn(empty);
            listener.onInventoryClick(event);
            when(event.getCurrentItem()).thenReturn(source);
        }
        handler.verifyNoInteractions();
        loader.verifyNoInteractions();
        loader.when(() -> KeyCopyLoader.matchesMoldInput(cursor)).thenReturn(false);
        listener.onInventoryClick(event);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(event, never()).setCancelled(true);
    }

    @ParameterizedTest
    @EnumSource(CreatorMutation.class)
    void registeredCreatorChangesAreObservedWhenConsumingCursorAndCurrent(CreatorMutation mutation) {
        prepare(Craft.PERMANENT, 3, 2);
        var liveCursor = new java.util.concurrent.atomic.AtomicReference<>(cursor);
        var liveCurrent = new java.util.concurrent.atomic.AtomicReference<>(source);
        when(event.getCursor()).thenAnswer(call -> liveCursor.get());
        when(event.getCurrentItem()).thenAnswer(call -> liveCurrent.get());
        doAnswer(call -> { liveCursor.set(call.getArgument(0)); return null; }).when(view).setCursor(nullable(ItemStack.class));
        doAnswer(call -> { liveCurrent.set(call.getArgument(0)); return null; }).when(event).setCurrentItem(nullable(ItemStack.class));
        Inventory destination = MockBukkit.getMock().createInventory(null, 36);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> destination.addItem(call.getArgument(0, ItemStack.class)));
        var api = new net.tfminecraft.tlibs.objects.api.ItemAPI();
        api.setup(MockBukkit.getMock());
        var created = new java.util.concurrent.atomic.AtomicInteger();
        api.registerPathHandler("callback", path -> {
            assertEquals("callback.key-copy", path);
            ItemStack replacement = switch (mutation) {
                case CLEAR -> null;
                case AIR -> new ItemStack(Material.AIR);
                case REPLACE -> new ItemStack(Material.DIAMOND, 2);
            };
            view.setCursor(replacement);
            event.setCurrentItem(replacement == null ? null : replacement.clone());
            created.incrementAndGet();
            return new ItemStack(Material.PAPER);
        });
        var instance = mock(net.tfminecraft.thievery.Thievery.class);
        when(instance.namespace()).thenReturn("thievery");
        try (var libs = mockStatic(net.tfminecraft.tlibs.TLibs.class);
                var plugin = mockStatic(net.tfminecraft.thievery.Thievery.class)) {
            libs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(api);
            plugin.when(net.tfminecraft.thievery.Thievery::getInstance).thenReturn(instance);
            loader.when(KeyCopyLoader::getCopyOutput).thenReturn("callback.key-copy");
            handler.close();
            handler = mockStatic(KeyCopyHandler.class, CALLS_REAL_METHODS);
            var sourceMeta = source.getItemMeta();
            var data = sourceMeta.getPersistentDataContainer();
            data.set(net.tfminecraft.thievery.utils.Keys.keyMoldMarker, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            data.set(net.tfminecraft.thievery.utils.Keys.keyUUIDKey, org.bukkit.persistence.PersistentDataType.STRING, "door-id");
            data.set(net.tfminecraft.thievery.utils.Keys.keySourceStrength, org.bukkit.persistence.PersistentDataType.DOUBLE, 0.7);
            data.set(net.tfminecraft.thievery.utils.Keys.keySourceKeyId, org.bukkit.persistence.PersistentDataType.STRING, "copper");
            source.setItemMeta(sourceMeta);
            loader.when(() -> KeyCopyLoader.matchesMoldOutput(source)).thenReturn(true);

            listener.onInventoryClick(event);

            assertEquals(1, created.get());
            verify(event).setCancelled(true);
            assertEquals(Material.PAPER, destination.getItem(0).getType());
            assertEquals(1, destination.getItem(0).getAmount());
            for (ItemStack remaining : new ItemStack[]{liveCursor.get(), liveCurrent.get()}) {
                if (mutation == CreatorMutation.CLEAR) assertNull(remaining);
                else if (mutation == CreatorMutation.AIR) assertTrue(remaining.getType().isAir());
                else assertEquals(new ItemStack(Material.DIAMOND), remaining);
            }
            assertEquals(3, cursor.getAmount());
            assertEquals(2, source.getAmount());
        }
    }

    private void prepare(Craft craft, int cursorAmount, int sourceAmount) {
        cursor = new ItemStack(Material.CLAY_BALL, cursorAmount);
        source = new ItemStack(Material.GOLD_NUGGET, sourceAmount);
        result = new ItemStack(Material.PAPER);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getCurrentItem()).thenReturn(source);
        loader.when(() -> KeyCopyLoader.matchesMoldInput(cursor)).thenReturn(craft == Craft.MOLD);
        loader.when(() -> KeyCopyLoader.matchesCopyInput(cursor)).thenReturn(craft == Craft.PERMANENT);
        loader.when(() -> KeyCopyLoader.matchesPaperInput(cursor)).thenReturn(isPaper(craft));
        handler.when(() -> KeyCopyHandler.isMasterKey(source)).thenReturn(craft == Craft.MOLD || craft == Craft.PAPER_MASTER);
        handler.when(() -> KeyCopyHandler.isMold(source)).thenReturn(craft == Craft.PERMANENT);
        handler.when(() -> KeyCopyHandler.isPermanentCopy(source)).thenReturn(craft == Craft.PAPER_COPY);
        handler.when(() -> KeyCopyHandler.ensureKeyInitialized(source)).thenReturn(true);
        handler.when(() -> KeyCopyHandler.extractCopyMetadata(source)).thenReturn(metadata);
        handler.when(() -> KeyCopyHandler.createMold(metadata)).thenReturn(result);
        handler.when(() -> KeyCopyHandler.createPermanentCopy(metadata)).thenReturn(result);
        handler.when(() -> KeyCopyHandler.createPaperCopy(metadata)).thenReturn(result);
        handler.when(() -> KeyCopyHandler.canCraftPaper(player, "door-id")).thenReturn(true);
    }

    private static boolean isPaper(Craft craft) {
        return craft == Craft.PAPER_MASTER || craft == Craft.PAPER_COPY;
    }
}
