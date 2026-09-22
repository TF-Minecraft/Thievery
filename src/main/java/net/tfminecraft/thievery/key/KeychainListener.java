package net.tfminecraft.thievery.key;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.key.KeychainHandler;
import net.tfminecraft.thievery.key.KeychainHandler.AddKeyResult;
import net.tfminecraft.thievery.key.KeychainHandler.RemoveKeyResult;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;

public class KeychainListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return;
        }

        ClickType click = event.getClick();
        if (click.isShiftClick() || click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND
                || click == ClickType.MIDDLE || click == ClickType.DOUBLE_CLICK) {
            return;
        }

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (click == ClickType.RIGHT && KeychainHandler.isKeychain(current) && isEmpty(cursor)) {
            handleRemove(event, player, current);
            return;
        }

        if (KeychainHandler.isKeychainItem(current) && ToolResolver.isDoorKey(cursor)) {
            handleAdd(event, player, current, cursor, true);
            return;
        }

        if (ToolResolver.isDoorKey(current) && KeychainHandler.isKeychainItem(cursor)) {
            handleAdd(event, player, cursor, current, false);
        }
    }

    private void handleRemove(InventoryClickEvent event, Player player, ItemStack keychain) {
        ItemStack toRemove = KeychainHandler.peekLastKey(keychain);
        if (toRemove == null) {
            return;
        }
        if (!KeychainHandler.canFitInInventory(player, toRemove)) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your inventory is full."));
            return;
        }

        RemoveKeyResult result = KeychainHandler.removeLastKey(keychain);
        if (!result.removed()) {
            return;
        }

        player.getInventory().addItem(result.getRemovedKey());
        event.setCancelled(true);
        event.setCurrentItem(result.getKeychain());
    }

    private void handleAdd(InventoryClickEvent event, Player player, ItemStack keychain, ItemStack key,
            boolean keyOnCursor) {
        AddKeyResult result = KeychainHandler.addKey(keychain, key);
        switch (result.getStatus()) {
            case FULL -> player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "This keychain is full."));
            case DUPLICATE -> player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                    + "That key is already on this keychain."));
            case FAIL -> {
                return;
            }
            default -> {
            }
        }
        if (result.getStatus() != AddKeyResult.Status.SUCCESS) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        if (keyOnCursor) {
            consumeOneFromCursor(event, key);
            event.setCurrentItem(result.getKeychain());
        } else {
            consumeOneFromCurrent(event, key);
            event.getView().setCursor(result.getKeychain());
        }
    }

    private void consumeOneFromCursor(InventoryClickEvent event, ItemStack cursor) {
        if (cursor.getAmount() <= 1) {
            event.getView().setCursor(null);
            return;
        }
        ItemStack remaining = cursor.clone();
        remaining.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(remaining);
    }

    private void consumeOneFromCurrent(InventoryClickEvent event, ItemStack current) {
        if (current.getAmount() <= 1) {
            event.setCurrentItem(null);
            return;
        }
        ItemStack remaining = current.clone();
        remaining.setAmount(current.getAmount() - 1);
        event.setCurrentItem(remaining);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
