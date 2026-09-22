package net.tfminecraft.thievery.key;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.key.KeyCopyHandler;
import net.tfminecraft.thievery.key.KeyCopyHandler.CopyMetadata;
import net.tfminecraft.thievery.loader.KeyCopyLoader;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class KeyCopyListener implements Listener {

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
        if (cursor == null || cursor.getType().isAir() || current == null || current.getType().isAir()) {
            return;
        }

        if (KeyCopyLoader.matchesMoldInput(cursor) && KeyCopyHandler.isMasterKey(current)) {
            handleMoldCraft(event, player, current, cursor);
            return;
        }

        if (KeyCopyLoader.matchesCopyInput(cursor) && KeyCopyHandler.isMold(current)) {
            handlePermanentCopyCraft(event, player, current, cursor);
            return;
        }

        if (KeyCopyLoader.matchesPaperInput(cursor)) {
            if (KeyCopyHandler.isMold(current)) {
                return;
            }
            if (KeyCopyHandler.isPaperCopy(current)) {
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot copy a paper key to paper."));
                return;
            }
            if (KeyCopyHandler.isMasterKey(current) || KeyCopyHandler.isPermanentCopy(current)) {
                handlePaperCraft(event, player, current, cursor);
            }
        }
    }

    private void handleMoldCraft(InventoryClickEvent event, Player player, ItemStack masterKey,
            ItemStack moldInput) {
        if (!KeyCopyHandler.ensureKeyInitialized(masterKey)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That key cannot be used for a mold."));
            return;
        }
        event.setCurrentItem(masterKey);

        CopyMetadata metadata = KeyCopyHandler.extractCopyMetadata(masterKey);
        if (metadata == null) {
            return;
        }

        ItemStack result = KeyCopyHandler.createMold(metadata);
        if (result == null) {
            return;
        }

        if (!giveResult(player, result)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your inventory is full."));
            return;
        }

        event.setCancelled(true);
        consumeOneFromCursor(event);
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Key mold created."));
        player.playSound(player.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1f, 1f);
    }

    private void handlePermanentCopyCraft(InventoryClickEvent event, Player player, ItemStack mold,
            ItemStack copyInput) {
        CopyMetadata metadata = KeyCopyHandler.extractCopyMetadata(mold);
        if (metadata == null) {
            return;
        }

        ItemStack result = KeyCopyHandler.createPermanentCopy(metadata);
        if (result == null) {
            return;
        }

        if (!giveResult(player, result)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your inventory is full."));
            return;
        }

        event.setCancelled(true);
        consumeOneFromCursor(event);
        consumeOneFromCurrent(event);
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Key copy created."));
        player.playSound(player.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1f, 1f);
    }

    private void handlePaperCraft(InventoryClickEvent event, Player player, ItemStack sourceKey,
            ItemStack paperInput) {
        if (KeyCopyHandler.isMasterKey(sourceKey) && !KeyCopyHandler.ensureKeyInitialized(sourceKey)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That key cannot be copied to paper."));
            return;
        }
        if (KeyCopyHandler.isMasterKey(sourceKey)) {
            event.setCurrentItem(sourceKey);
        }

        CopyMetadata metadata = KeyCopyHandler.extractCopyMetadata(sourceKey);
        if (metadata == null) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That key has not been used yet."));
            return;
        }

        if (!KeyCopyHandler.canCraftPaper(player, metadata.getDoorKeyUuid())) {
            long minutes = KeyCopyHandler.getPaperCooldownRemainingMinutes(player, metadata.getDoorKeyUuid());
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot copy that key to paper for another "
                    + minutes + " minute(s)."));
            return;
        }

        ItemStack result = KeyCopyHandler.createPaperCopy(metadata);
        if (result == null) {
            return;
        }

        if (!giveResult(player, result)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your inventory is full."));
            return;
        }

        KeyCopyHandler.recordPaperCooldown(player, metadata.getDoorKeyUuid());
        event.setCancelled(true);
        consumeOneFromCursor(event);
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Paper key created."));
        player.playSound(player.getLocation(), Sound.BLOCK_WOOL_BREAK, 1f, 1f);
    }

    private boolean giveResult(Player player, ItemStack result) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(result);
        return leftover.isEmpty();
    }

    private void consumeOneFromCursor(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        if (cursor.getAmount() <= 1) {
            event.getView().setCursor(null);
            return;
        }
        ItemStack remaining = cursor.clone();
        remaining.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(remaining);
    }

    private void consumeOneFromCurrent(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        if (current.getAmount() <= 1) {
            event.setCurrentItem(null);
            return;
        }
        ItemStack remaining = current.clone();
        remaining.setAmount(current.getAmount() - 1);
        event.setCurrentItem(remaining);
    }
}
