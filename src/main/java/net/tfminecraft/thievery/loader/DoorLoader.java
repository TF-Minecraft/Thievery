package net.tfminecraft.thievery.loader;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

public final class DoorLoader {

    private static String debugToolPath = "";

    private DoorLoader() {}

    public static void load(FileConfiguration config) {
        debugToolPath = config.getString("doors.debug-tool", "");
    }

    public static String getDebugToolPath() {
        return debugToolPath;
    }

    public static boolean matchesDebugTool(ItemStack item) {
        if (item == null || item.getType().isAir() || debugToolPath == null || debugToolPath.isBlank()) {
            return false;
        }
        return TLibs.getItemAPI().getChecker().checkItemWithPath(item, debugToolPath);
    }
}
