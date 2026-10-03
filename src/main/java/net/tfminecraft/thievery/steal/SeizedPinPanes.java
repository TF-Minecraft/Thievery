package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/** Probe menu panes for the chest seized-pin puzzle. */
public final class SeizedPinPanes {

    private static final Material[] COUNT_PANES = {
            Material.WHITE_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE,
            Material.ORANGE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE
    };

    private SeizedPinPanes() {}

    public static String describe(int nearby) {
        if (nearby <= 0) {
            return "No seized pins nearby";
        }
        return nearby + (nearby == 1 ? " seized pin nearby" : " seized pins nearby");
    }

    /** Empty probed slot: the colour and stack size show how many seized pins surround it. */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack countPane(int nearby) {
        int count = Math.max(0, nearby);
        ItemStack pane = new ItemStack(COUNT_PANES[Math.min(count, COUNT_PANES.length - 1)], Math.max(1, count));
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.MUTED + "Nothing here"));
        meta.setLore(List.of(ThieveryTexts.gui(hintColour(count) + describe(count))));
        meta.getPersistentDataContainer().set(Keys.stealNothing, PersistentDataType.BYTE, (byte) 1);
        pane.setItemMeta(meta);
        return pane;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack markedPane() {
        ItemStack pane = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.ERROR + "Marked as seized"));
        meta.setLore(List.of(ThieveryTexts.gui(ThieveryTexts.MUTED + "Right-click to unmark")));
        pane.setItemMeta(meta);
        return pane;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack seizedPane() {
        ItemStack pane = new ItemStack(Material.IRON_BARS);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.ERROR + "Seized pin"));
        meta.getPersistentDataContainer().set(Keys.stealNothing, PersistentDataType.BYTE, (byte) 1);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Adds the seized-pin count to a probed slot after the steal menu has drawn it. */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static void annotate(Inventory gui, int guiSlot, int nearby) {
        ItemStack shown = gui.getItem(guiSlot);
        if (shown == null) {
            return;
        }
        if (StealGui.isNothingPane(shown)) {
            gui.setItem(guiSlot, countPane(nearby));
            return;
        }
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ThieveryTexts.gui(hintColour(nearby) + describe(nearby)));
        if (meta.hasLore()) {
            lore.addAll(meta.getLore());
        }
        meta.setLore(lore);
        shown.setItemMeta(meta);
        gui.setItem(guiSlot, shown);
    }

    private static String hintColour(int nearby) {
        return nearby <= 0 ? ThieveryTexts.MUTED : ThieveryTexts.GUI_WARN;
    }
}
