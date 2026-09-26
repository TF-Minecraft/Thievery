package net.tfminecraft.thievery.player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.player.LoadoutSession;
import net.tfminecraft.thievery.player.LoadoutSession.ToggleResult;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.LoadoutHolder;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.Keys;

public class InventoryManager implements Listener {

    private static final int INVENTORY_SIZE = 54;
    private static final int CATEGORIES_PER_PAGE = 45;
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_CANCEL = 48;
    private static final int SLOT_CONFIRM = 49;
    private static final int SLOT_NEXT_PAGE = 53;

    private final Map<UUID, LoadoutSession> sessions = new HashMap<>();

    public void openLoadout(Player player) {
        PlayerData playerData = Thievery.getPlayerManager().get(player);
        LoadoutSession session = LoadoutSession.from(playerData);
        sessions.put(player.getUniqueId(), session);
        renderLoadout(player, session, 0, true);
    }

    // Update the active legacy inventory title; opening a new view or reading its original component title changes behavior.
    @SuppressWarnings({"deprecation"})
    private void renderLoadout(Player player, LoadoutSession session, int page, boolean open) {

        List<ItemCategory> categories = CategoryLoader.getLoadoutCategories();
        int maxPage = Math.max(0, (categories.size() - 1) / CATEGORIES_PER_PAGE);
        int safePage = Math.max(0, Math.min(page, maxPage));

        Inventory inv;
        if (open) {
            inv = Bukkit.createInventory(
                    new LoadoutHolder(player.getUniqueId(), safePage),
                    INVENTORY_SIZE,
                    buildTitle(session)
            );
        } else {
            LoadoutHolder holder = (LoadoutHolder) player.getOpenInventory().getTopInventory().getHolder();
            holder.setPage(safePage);
            inv = player.getOpenInventory().getTopInventory();
            inv.clear();
            player.getOpenInventory().setTitle(buildTitle(session));
        }

        int start = safePage * CATEGORIES_PER_PAGE;
        int end = Math.min(start + CATEGORIES_PER_PAGE, categories.size());
        for (int i = start; i < end; i++) {
            ItemCategory category = categories.get(i);
            boolean active = session.getDraftActive().contains(category.getId());
            ItemStack icon = category.getIconItem(active);
            if (icon != null) {
                inv.setItem(i - start, icon);
            }
        }

        fillBottomBar(inv, safePage, maxPage);
        if (open) {
            player.openInventory(inv);
        }
    }

    private String buildTitle(LoadoutSession session) {
        return ThieveryTexts.gui(ThieveryTexts.DARK + "Loadout " + ThieveryTexts.MUTED + "("
                + ThieveryTexts.GUI_SUCCESS + session.getDraftBank() + ThieveryTexts.MUTED + " bank · "
                + ThieveryTexts.GUI_WARN + session.getDraftAllocated() + ThieveryTexts.MUTED + "/"
                + ThieveryTexts.GUI_SUCCESS + Cache.categoryPoints + ThieveryTexts.MUTED + ")");
    }

    private void fillBottomBar(Inventory inv, int page, int maxPage) {
        for (int slot = 45; slot < INVENTORY_SIZE; slot++) {
            if (slot != SLOT_PREV_PAGE && slot != SLOT_CANCEL && slot != SLOT_CONFIRM && slot != SLOT_NEXT_PAGE) {
                inv.setItem(slot, createFiller());
            }
        }

        inv.setItem(SLOT_CANCEL, createButton(Material.RED_DYE,
                ThieveryTexts.gui(ThieveryTexts.ERROR + "Cancel"),
                ThieveryTexts.gui(ThieveryTexts.MUTED + "Discard changes")));
        inv.setItem(SLOT_CONFIRM, createButton(Material.LIME_DYE,
                ThieveryTexts.gui(ThieveryTexts.SUCCESS + "Confirm"),
                ThieveryTexts.gui(ThieveryTexts.MUTED + "Apply loadout")));

        if (page > 0) {
            inv.setItem(SLOT_PREV_PAGE, createButton(Material.ARROW,
                    ThieveryTexts.gui(ThieveryTexts.GUI_WARN + "Previous Page"), null));
        } else {
            inv.setItem(SLOT_PREV_PAGE, createFiller());
        }

        if (page < maxPage) {
            inv.setItem(SLOT_NEXT_PAGE, createButton(Material.ARROW,
                    ThieveryTexts.gui(ThieveryTexts.GUI_WARN + "Next Page"), null));
        } else {
            inv.setItem(SLOT_NEXT_PAGE, createFiller());
        }
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.DARK + " "));
        item.setItemMeta(meta);
        return item;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createButton(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (loreLine != null) {
            meta.setLore(List.of(loreLine));
        }
        item.setItemMeta(meta);
        return item;
    }

    private void cancelSession(Player player) {
        sessions.remove(player.getUniqueId());
        player.closeInventory();
    }

    private void applySession(Player player, LoadoutSession session) {
        sessions.remove(player.getUniqueId());

        PlayerData playerData = Thievery.getPlayerManager().get(player);
        playerData.setActiveCategories(List.copyOf(session.getDraftActive()));
        playerData.setPoints(session.getDraftBank());
        player.closeInventory();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof LoadoutHolder holder)) return;
        if (!holder.getPlayerId().equals(player.getUniqueId())) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) {
            return;
        }

        LoadoutSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        int slot = e.getSlot();

        if (slot >= 0 && slot < CATEGORIES_PER_PAGE) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            String categoryId = clicked.getItemMeta().getPersistentDataContainer()
                    .get(Keys.categoryId, PersistentDataType.STRING);
            if (categoryId == null) return;

            ToggleResult result = session.toggleCategory(categoryId);
            String error = switch (result) {
                case TOGGLED_ON, TOGGLED_OFF -> null;
                case ALLOCATION_FULL -> "You cannot allocate more than " + Cache.categoryPoints + " points.";
                case NOT_ENOUGH_BANK -> "You do not have enough bank points for that category.";
                case UNKNOWN_CATEGORY -> "Unknown category.";
            };
            if (error == null) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
                renderLoadout(player, session, holder.getPage(), false);
            } else {
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + error));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            return;
        }

        if (slot == SLOT_PREV_PAGE && holder.getPage() > 0) {
            renderLoadout(player, session, holder.getPage() - 1, false);
            return;
        }

        if (slot == SLOT_NEXT_PAGE) {
            List<ItemCategory> categories = CategoryLoader.getLoadoutCategories();
            int maxPage = Math.max(0, (categories.size() - 1) / CATEGORIES_PER_PAGE);
            if (holder.getPage() < maxPage) {
                renderLoadout(player, session, holder.getPage() + 1, false);
            }
            return;
        }

        if (slot == SLOT_CANCEL) {
            cancelSession(player);
            return;
        }

        if (slot == SLOT_CONFIRM) {
            if (!session.canConfirm()) {
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot confirm this loadout."));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
            applySession(player, session);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof LoadoutHolder holder)) return;
        if (!holder.getPlayerId().equals(player.getUniqueId())) return;

        sessions.remove(player.getUniqueId());
    }
}
