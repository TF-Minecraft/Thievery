package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.category.DenarMoney;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.steal.StealGuiHolder;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealIgnoreRules;
import net.tfminecraft.thievery.steal.StealItemDisplay;
import net.tfminecraft.thievery.steal.PlayerSlotMap;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.Keys;

public final class StealGui {

    public static final int ROBBERY_POUCH_GUI_SLOT = 8;

    private StealGui() {}

    // --- Layout (formerly StealGui.Layout) ---

    public static final class Layout {
        private final int logicalSlotCount;
        private final int guiSize;
        private final Map<Integer, Integer> logicalSlotToGuiSlot;
        private final Map<Integer, Integer> guiSlotToLogicalSlot;

        private Layout(int logicalSlotCount, int guiSize,
                Map<Integer, Integer> logicalSlotToGuiSlot, Map<Integer, Integer> guiSlotToLogicalSlot) {
            this.logicalSlotCount = logicalSlotCount;
            this.guiSize = guiSize;
            this.logicalSlotToGuiSlot = logicalSlotToGuiSlot;
            this.guiSlotToLogicalSlot = guiSlotToLogicalSlot;
        }

        public static Layout create(int logicalSlotCount) {
            int guiSize = resolveGuiSize(logicalSlotCount);
            Map<Integer, Integer> logicalToGui = buildRandomLayout(logicalSlotCount, guiSize);
            Map<Integer, Integer> guiToLogical = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : logicalToGui.entrySet()) {
                guiToLogical.put(entry.getValue(), entry.getKey());
            }
            return new Layout(logicalSlotCount, guiSize,
                    Collections.unmodifiableMap(logicalToGui), Collections.unmodifiableMap(guiToLogical));
        }

        public static Layout createRobbery(int logicalSlotCount) {
            int guiSize = 54;
            Map<Integer, Integer> logicalToGui = buildRobberyRandomLayout(logicalSlotCount, guiSize);
            Map<Integer, Integer> guiToLogical = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : logicalToGui.entrySet()) {
                guiToLogical.put(entry.getValue(), entry.getKey());
            }
            return new Layout(logicalSlotCount, guiSize,
                    Collections.unmodifiableMap(logicalToGui), Collections.unmodifiableMap(guiToLogical));
        }

        public static int resolveGuiSize(int needed) {
            if (needed <= 9) {
                return 9;
            }
            if (needed <= 27) {
                return 27;
            }
            return 54;
        }

        public int getLogicalSlotCount() {
            return logicalSlotCount;
        }

        public int getGuiSize() {
            return guiSize;
        }

        public Map<Integer, Integer> getLogicalSlotToGuiSlot() {
            return logicalSlotToGuiSlot;
        }

        public int getGuiSlotForLogical(int logicalSlot) {
            Integer guiSlot = logicalSlotToGuiSlot.get(logicalSlot);
            return guiSlot != null ? guiSlot : -1;
        }

        public Integer getLogicalForGui(int guiSlot) {
            return guiSlotToLogicalSlot.get(guiSlot);
        }

        private static Map<Integer, Integer> buildRandomLayout(int logicalSlotCount, int guiSize) {
            Map<Integer, Integer> layout = new HashMap<>();
            List<Integer> guiSlots = new ArrayList<>();
            for (int i = 0; i < guiSize; i++) {
                guiSlots.add(i);
            }
            Collections.shuffle(guiSlots);

            for (int logicalSlot = 0; logicalSlot < logicalSlotCount; logicalSlot++) {
                if (guiSlots.isEmpty()) {
                    break;
                }
                layout.put(logicalSlot, guiSlots.remove(0));
            }
            return layout;
        }

        private static Map<Integer, Integer> buildRobberyRandomLayout(int logicalSlotCount, int guiSize) {
            Map<Integer, Integer> layout = new HashMap<>();
            List<Integer> guiSlots = new ArrayList<>();
            for (int i = 0; i < guiSize; i++) {
                if (i != ROBBERY_POUCH_GUI_SLOT) {
                    guiSlots.add(i);
                }
            }
            Collections.shuffle(guiSlots);

            for (int logicalSlot = 0; logicalSlot < logicalSlotCount; logicalSlot++) {
                if (guiSlots.isEmpty()) {
                    break;
                }
                layout.put(logicalSlot, guiSlots.remove(0));
            }
            return layout;
        }
    }

    // --- Reveal (formerly StealGuiReveal) ---

    public enum RevealResultType {
        EMPTY,
        IGNORED,
        ITEM
    }

    public record RevealResult(RevealResultType type, ItemStack display) {}

    public static RevealResult revealSlot(ItemStack realItem, StealBudget budget, PlayerData thiefData) {
        return revealSlot(realItem, budget, thiefData, null);
    }

    public static RevealResult revealSlot(ItemStack realItem, StealBudget budget, PlayerData thiefData,
            StealItemDisplay.ChestCluePreviewContext cluePreview) {
        if (realItem == null || realItem.getType().isAir()) {
            return new RevealResult(RevealResultType.EMPTY, createNothingPane());
        }
        if (StealIgnoreRules.isIgnored(realItem)) {
            return new RevealResult(RevealResultType.IGNORED, createNothingPane());
        }
        ItemStack display = StealItemDisplay.buildRepresentation(realItem, budget.getRemaining(), thiefData,
                cluePreview);
        if (display == null) {
            return new RevealResult(RevealResultType.EMPTY, createNothingPane());
        }
        return new RevealResult(RevealResultType.ITEM, display);
    }

    // --- Panes (formerly StealGuiPanes) ---

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack createUnknownPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName("???");
        meta.getPersistentDataContainer().set(Keys.stealUnknown, PersistentDataType.BYTE, (byte) 1);
        pane.setItemMeta(meta);
        return pane;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack createFillerPane() {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(" ");
        meta.getPersistentDataContainer().set(Keys.stealFiller, PersistentDataType.BYTE, (byte) 1);
        filler.setItemMeta(meta);
        return filler;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack createNothingPane() {
        ItemStack pane = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.ERROR + "Nothing found."));
        meta.getPersistentDataContainer().set(Keys.stealNothing, PersistentDataType.BYTE, (byte) 1);
        pane.setItemMeta(meta);
        return pane;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack createHiddenPane() {
        ItemStack hidden = new ItemStack(Material.BARRIER);
        ItemMeta meta = hidden.getItemMeta();
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.MUTED + "HIDDEN!"));
        meta.getPersistentDataContainer().set(Keys.stealHidden, PersistentDataType.BYTE, (byte) 1);
        hidden.setItemMeta(meta);
        return hidden;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack createRobberyPouchPane(double balance) {
        ItemCategory money = CategoryLoader.getMoneyCategory();
        String iconPath = money != null ? money.getIcon() : "m.currency.pouch_of_coins";
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(iconPath);
        if (item == null) {
            item = new ItemStack(Material.GOLD_INGOT);
        }
        item = item.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(ThieveryTexts.gui(ThieveryTexts.GUI_WARN + "Pouch"));
        List<String> lore = new ArrayList<>();
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Balance: "
                + ThieveryTexts.GUI_SUCCESS + String.format("%.2f", balance) + "d"));
        lore.add(" ");
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Click: "
                + ThieveryTexts.GUI_SUCCESS + RobberyLoader.getPouchClickAmount() + "d"));
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Shift-click: "
                + ThieveryTexts.GUI_SUCCESS + RobberyLoader.getPouchShiftAmount() + "d"));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(Keys.stealRobberyPouch, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static void placeRobberyPouchSlot(Inventory gui, Player victim, PlayerData thiefData, StealBudget budget) {
        if (DenarMoney.canStealPouch(thiefData)) {
            double balance = DenarMoney.getPouchBalance(victim);
            if (balance > 0.0) {
                gui.setItem(ROBBERY_POUCH_GUI_SLOT, createRobberyPouchPane(balance));
                return;
            }
        }
        gui.setItem(ROBBERY_POUCH_GUI_SLOT, createFillerPane());
    }

    public static boolean isUnknownPane(ItemStack item) {
        return hasKey(item, Keys.stealUnknown);
    }

    public static boolean isFillerPane(ItemStack item) {
        return hasKey(item, Keys.stealFiller);
    }

    public static boolean isNothingPane(ItemStack item) {
        return hasKey(item, Keys.stealNothing);
    }

    public static boolean isHiddenPane(ItemStack item) {
        return hasKey(item, Keys.stealHidden);
    }

    public static boolean isRobberyPouchPane(ItemStack item) {
        return hasKey(item, Keys.stealRobberyPouch);
    }

    public static boolean isNonInteractivePane(ItemStack item) {
        return isFillerPane(item) || isNothingPane(item);
    }

    private static boolean hasKey(ItemStack item, org.bukkit.NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // --- Title (formerly StealGuiTitle) ---

    public record TitleOptions(Double risk, Double critical, Long timerMs, StealBudget budget, Double breakChance) {}

    public static String formatTitle(TitleOptions opts) {
        StringBuilder title = new StringBuilder();

        if (opts.risk() != null && opts.critical() != null) {
            String riskPart = RiskCalculator.formatRiskTitle(opts.risk(), opts.critical());
            if (!riskPart.isEmpty()) {
                title.append(riskPart);
            }
        }

        if (opts.breakChance() != null) {
            if (title.length() > 0) {
                title.append(" ");
            }
            title.append(ThieveryTexts.ACCENT).append("Break: ")
                    .append(RiskCalculator.formatPercentWhole(opts.breakChance()));
        }

        if (opts.timerMs() != null) {
            if (title.length() > 0) {
                title.append(" ");
            }
            title.append(ThieveryTexts.WARN).append(StealItemDisplay.formatTimeRemaining(opts.timerMs()));
        }

        if (opts.budget() != null) {
            if (title.length() > 0) {
                title.append(" ");
            }
            title.append(ThieveryTexts.SUCCESS)
                    .append(formatTitleValue(opts.budget().getUsed()))
                    .append("/")
                    .append(formatTitleValue(opts.budget().getCapacity()));
        }

        if (title.length() == 0) {
            return " ";
        }
        String raw = title.toString();
        return raw;
    }

    public static String forPickpocket(PlayerData thiefData, int dexterity, StealBudget budget) {
        return formatTitle(new TitleOptions(
                thiefData.getRisk(),
                thiefData.getCriticalChance(dexterity, 0),
                null,
                budget,
                null));
    }

    public static String forChest(PlayerData thiefData, int dexterity, double lockpickStrength, StealBudget budget,
            double successChance, boolean lockpickBroken, boolean criticalRisk) {
        Double breakChance = lockpickBroken ? null : (1.0 - successChance);
        double critical = criticalRisk ? thiefData.getCriticalChance(dexterity, lockpickStrength) : 0.0;
        return formatTitle(new TitleOptions(
                thiefData.getRisk(),
                critical,
                null,
                budget,
                breakChance));
    }

    public static String forRobbery(long remainingMs, StealBudget budget) {
        return formatTitle(new TitleOptions(null, null, remainingMs, budget, null));
    }

    private static String formatTitleValue(double value) {
        return String.valueOf(Math.round(value));
    }

    // --- Builder (formerly StealGuiBuilder) ---

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static Inventory buildHiddenGui(StealGuiHolder holder, Layout layout, String title) {
        int guiSize = layout.getGuiSize();
        Inventory gui = Bukkit.createInventory(holder, guiSize, title);
        ItemStack unkPane = createUnknownPane();
        ItemStack fillerPane = createFillerPane();
        Set<Integer> assignedLootSlots = new HashSet<>(layout.getLogicalSlotToGuiSlot().values());

        for (Integer guiSlot : layout.getLogicalSlotToGuiSlot().values()) {
            gui.setItem(guiSlot, unkPane);
        }

        for (int guiSlot = 0; guiSlot < guiSize; guiSlot++) {
            if (!assignedLootSlots.contains(guiSlot)) {
                gui.setItem(guiSlot, fillerPane);
            }
        }
        return gui;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static Inventory buildRobberyGui(StealGuiHolder holder, Layout layout, String title,
            Player victim, StealBudget budget, PlayerData thiefData) {
        int guiSize = layout.getGuiSize();
        Inventory gui = Bukkit.createInventory(holder, guiSize, title);
        ItemStack fillerPane = createFillerPane();

        for (Map.Entry<Integer, Integer> entry : layout.getLogicalSlotToGuiSlot().entrySet()) {
            int logicalSlot = entry.getKey();
            int guiSlot = entry.getValue();
            ItemStack realItem = PlayerSlotMap.getItem(victim, logicalSlot);
            if (realItem == null || realItem.getType().isAir() || StealIgnoreRules.isIgnored(realItem)) {
                continue;
            }
            ItemStack display = StealItemDisplay.buildRepresentation(realItem, budget.getRemaining(), thiefData);
            if (display != null) {
                gui.setItem(guiSlot, display);
            }
        }

        placeRobberyPouchSlot(gui, victim, thiefData, budget);

        for (int guiSlot = 0; guiSlot < guiSize; guiSlot++) {
            if (gui.getItem(guiSlot) == null) {
                gui.setItem(guiSlot, fillerPane);
            }
        }
        return gui;
    }

    public static void placeRevealedSlot(Inventory gui, int guiSlot, ItemStack realItem,
            StealBudget budget, PlayerData thiefData) {
        placeRevealedSlot(gui, guiSlot, realItem, budget, thiefData, null);
    }

    public static void placeRevealedSlot(Inventory gui, int guiSlot, ItemStack realItem,
            StealBudget budget, PlayerData thiefData, StealItemDisplay.ChestCluePreviewContext cluePreview) {
        RevealResult result = revealSlot(realItem, budget, thiefData, cluePreview);
        gui.setItem(guiSlot, result.display());
    }

    // --- Refresher (formerly StealGuiRefresher) ---

    // Update the active legacy inventory title; opening a new view or reading its original component title changes behavior.
    @SuppressWarnings({"deprecation"})
    public static void updateTitle(Player player, StealGuiHolder holder, String title) {
        if (player == null || !player.isOnline() || holder == null || title == null) {
            return;
        }
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof StealGuiHolder openHolder)) {
            return;
        }
        if (!openHolder.getPlayerId().equals(holder.getPlayerId()) || openHolder.getKind() != holder.getKind()) {
            return;
        }
        if (title.equals(player.getOpenInventory().getTitle())) {
            return;
        }
        player.getOpenInventory().setTitle(title);
    }
}
