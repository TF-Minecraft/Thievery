package net.tfminecraft.thievery.loader;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

public final class KeyCopyLoader {

    private static int paperCooldownMinutes = 240;
    private static String moldInput = "m.utils.clay_ball";
    private static String moldOutput = "m.utils.key_mold";
    private static String copyInput = "m.keys.copper_ingot";
    private static String copyOutput = "m.keys.copper_key";
    private static String paperInput = "m.keys.blank_paper";
    private static String paperOutput = "m.keys.paper_key";

    private KeyCopyLoader() {}

    public static void load(FileConfiguration config) {
        paperCooldownMinutes = config.getInt("key-copy.paper-cooldown-minutes", 240);
        moldInput = config.getString("key-copy.mold.input", moldInput);
        moldOutput = config.getString("key-copy.mold.output", moldOutput);
        copyInput = config.getString("key-copy.copy.input", copyInput);
        copyOutput = config.getString("key-copy.copy.output", copyOutput);
        paperInput = config.getString("key-copy.paper.input", paperInput);
        paperOutput = config.getString("key-copy.paper.output", paperOutput);
    }

    public static int getPaperCooldownMinutes() {
        return paperCooldownMinutes;
    }

    public static String getMoldInput() {
        return moldInput;
    }

    public static String getMoldOutput() {
        return moldOutput;
    }

    public static String getCopyInput() {
        return copyInput;
    }

    public static String getCopyOutput() {
        return copyOutput;
    }

    public static String getPaperInput() {
        return paperInput;
    }

    public static String getPaperOutput() {
        return paperOutput;
    }

    public static boolean matchesMoldInput(ItemStack item) {
        return matchesPath(item, moldInput);
    }

    public static boolean matchesMoldOutput(ItemStack item) {
        return matchesPath(item, moldOutput);
    }

    public static boolean matchesCopyInput(ItemStack item) {
        return matchesPath(item, copyInput);
    }

    public static boolean matchesCopyOutput(ItemStack item) {
        return matchesPath(item, copyOutput);
    }

    public static boolean matchesPaperInput(ItemStack item) {
        return matchesPath(item, paperInput);
    }

    public static boolean matchesPaperOutput(ItemStack item) {
        return matchesPath(item, paperOutput);
    }

    private static boolean matchesPath(ItemStack item, String path) {
        if (item == null || item.getType().isAir() || path.isBlank()) {
            return false;
        }
        return TLibs.getItemAPI().getChecker().checkItemWithPath(item, path);
    }
}
