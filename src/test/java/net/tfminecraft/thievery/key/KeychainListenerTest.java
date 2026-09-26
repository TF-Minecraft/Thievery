package net.tfminecraft.thievery.key;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import net.tfminecraft.thievery.key.KeychainHandler.AddKeyResult;
import net.tfminecraft.thievery.key.KeychainHandler.RemoveKeyResult;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.Material;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class KeychainListenerTest {
    private final KeychainListener listener = new KeychainListener();
    private InventoryClickEvent event;
    private Player player;
    private PlayerInventory inventory;
    private InventoryView view;
    private ItemStack chain;
    private ItemStack updated;
    private ItemStack key;
    private MockedStatic<KeychainHandler> handler;
    private MockedStatic<ToolResolver> tools;

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
        chain = new ItemStack(Material.BRICK);
        updated = new ItemStack(Material.BRICK);
        updated.editMeta(meta -> meta.setDisplayName("Updated keychain"));
        key = new ItemStack(Material.GOLD_NUGGET);
        handler = mockStatic(KeychainHandler.class);
        tools = mockStatic(ToolResolver.class);
        handler.when(() -> KeychainHandler.isKeychain(chain)).thenReturn(true);
        handler.when(() -> KeychainHandler.isKeychainItem(chain)).thenReturn(true);
        tools.when(() -> ToolResolver.isDoorKey(key)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        handler.close(); tools.close(); MockBukkit.unmock();
    }

    @ParameterizedTest
    @CsvSource({"true, 1", "true, 3", "false, 1", "false, 3"})
    void addingConsumesExactlyOneKeyInEitherClickOrientation(boolean keyOnCursor, int amount) {
        key.setAmount(amount);
        tools.when(() -> ToolResolver.isDoorKey(key)).thenReturn(true);
        when(event.getCurrentItem()).thenReturn(keyOnCursor ? chain : key);
        when(event.getCursor()).thenReturn(keyOnCursor ? key : chain);
        handler.when(() -> KeychainHandler.addKey(chain, key)).thenReturn(AddKeyResult.success(updated));
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        ArgumentCaptor<ItemStack> changed = ArgumentCaptor.forClass(ItemStack.class);
        if (keyOnCursor) {
            verify(event).setCurrentItem(updated);
            verify(view).setCursor(changed.capture());
        } else {
            verify(view).setCursor(updated);
            verify(event).setCurrentItem(changed.capture());
        }
        if (amount == 1) assertNull(changed.getValue());
        else {
            assertEquals(amount - 1, changed.getValue().getAmount());
            assertEquals(Material.GOLD_NUGGET, changed.getValue().getType());
        }
        assertEquals(amount, key.getAmount());
    }

    @ParameterizedTest
    @EnumSource(value = AddKeyResult.Status.class, names = {"FULL", "DUPLICATE", "FAIL"})
    void rejectedAddPreservesBothItemsAndExplainsCapacityOrDuplicates(AddKeyResult.Status status) {
        when(event.getCurrentItem()).thenReturn(chain);
        when(event.getCursor()).thenReturn(key);
        AddKeyResult result = switch (status) {
            case FULL -> AddKeyResult.full(chain);
            case DUPLICATE -> AddKeyResult.duplicate(chain);
            default -> AddKeyResult.fail(chain);
        };
        handler.when(() -> KeychainHandler.addKey(chain, key)).thenReturn(result);
        listener.onInventoryClick(event);
        verify(event, never()).setCurrentItem(any());
        verify(view, never()).setCursor(any());
        if (status == AddKeyResult.Status.FAIL) {
            verify(event, never()).setCancelled(true);
            verify(player, never()).sendMessage(anyString());
        } else {
            verify(event).setCancelled(true);
            verify(player).sendMessage(contains(status == AddKeyResult.Status.FULL ? "keychain is full" : "already on this keychain"));
        }
    }

    @Test
    void rightClickWithEmptyCursorReturnsLastKeyAndUpdatesChain() {
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(chain);
        when(event.getCursor()).thenReturn(new ItemStack(Material.AIR));
        handler.when(() -> KeychainHandler.peekLastKey(chain)).thenReturn(key);
        handler.when(() -> KeychainHandler.canFitInInventory(player, key)).thenReturn(true);
        handler.when(() -> KeychainHandler.removeLastKey(chain)).thenReturn(RemoveKeyResult.success(updated, key));
        listener.onInventoryClick(event);
        verify(inventory).addItem(key);
        verify(event).setCurrentItem(updated);
        verify(event).setCancelled(true);
    }

    @Test
    void fullInventoryPreventsRemovalAndCancelsTheClick() {
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(chain);
        handler.when(() -> KeychainHandler.peekLastKey(chain)).thenReturn(key);
        handler.when(() -> KeychainHandler.canFitInInventory(player, key)).thenReturn(false);
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage(contains("Your inventory is full"));
        handler.verify(() -> KeychainHandler.removeLastKey(chain), never());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void emptyKeychainAndFailedRemovalLeaveInventoryUntouched() {
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(chain);
        listener.onInventoryClick(event);
        handler.verify(() -> KeychainHandler.canFitInInventory(any(), any()), never());
        handler.when(() -> KeychainHandler.peekLastKey(chain)).thenReturn(key);
        handler.when(() -> KeychainHandler.canFitInInventory(player, key)).thenReturn(true);
        handler.when(() -> KeychainHandler.removeLastKey(chain)).thenReturn(RemoveKeyResult.empty(chain));
        listener.onInventoryClick(event);
        verify(event, never()).setCancelled(true);
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void modifiedClicksOtherInventoriesAndNonplayersAreIgnored() {
        for (ClickType click : new ClickType[] {ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.NUMBER_KEY,
                ClickType.SWAP_OFFHAND, ClickType.MIDDLE, ClickType.DOUBLE_CLICK}) {
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
        handler.verifyNoInteractions(); tools.verifyNoInteractions();
        verify(event, never()).setCancelled(true);
    }

    @Test
    void rightClickingAKeyWithAnOrdinaryCursorKeepsNormalInventoryBehavior() {
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(key);
        when(event.getCursor()).thenReturn(new ItemStack(Material.STONE));
        listener.onInventoryClick(event);
        handler.verify(() -> KeychainHandler.peekLastKey(any()), never());
        handler.verify(() -> KeychainHandler.addKey(any(), any()), never());
        verify(event, never()).setCancelled(true);
        verify(event, never()).setCurrentItem(any());
        verify(view, never()).setCursor(any());
        verifyNoInteractions(inventory);
    }

    @Test
    void rightClickWithUnrelatedCursorDoesNotRemoveAKey() {
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(chain);
        when(event.getCursor()).thenReturn(new ItemStack(Material.STONE));
        listener.onInventoryClick(event);
        handler.verify(() -> KeychainHandler.peekLastKey(any()), never());
        verify(event, never()).setCancelled(true);
    }
}
