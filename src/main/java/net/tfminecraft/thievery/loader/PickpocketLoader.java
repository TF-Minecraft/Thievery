package net.tfminecraft.thievery.loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import net.tfminecraft.thievery.steal.StealIgnoreRules;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class PickpocketLoader {

    private static List<String> traits = new ArrayList<>();
    private static double budget = 10;
    private static int cooldownHours = 1;
    private static double maxDistance = 4;
    private static String alertSubtitle = "#d65c5cSomeone is pickpocketing you!";
    private static String alertSubtitleCritical = "#d65c5c{character_name} is pickpocketing you!";
    private static boolean minigameEnabled = true;
    private static double minigameTimeLimitSeconds = 30;

    private PickpocketLoader() {}

    public static void load(FileConfiguration config) {
        traits = config.getStringList("pickpocket.traits");
        if (traits.isEmpty()) {
            traits = new ArrayList<>(List.of("thief"));
        }
        budget = config.getDouble("pickpocket.budget", 10);
        cooldownHours = config.getInt("pickpocket.cooldown-hours", 1);
        maxDistance = config.getDouble("pickpocket.max-distance", 4);
        alertSubtitle = ThieveryTexts.formatGui(config.getString("pickpocket.alert-subtitle",
                "#d65c5cSomeone is pickpocketing you!"));
        alertSubtitleCritical = ThieveryTexts.formatGui(config.getString("pickpocket.alert-subtitle-critical",
                "#d65c5c{character_name} is pickpocketing you!"));
        minigameEnabled = config.getBoolean("pickpocket.minigame.enabled", true);
        minigameTimeLimitSeconds = Math.max(5.0, config.getDouble("pickpocket.minigame.time-limit-seconds", 30));

        List<String> ignoreNameContains = config.getStringList("pickpocket.ignore.name-contains");
        if (!ignoreNameContains.isEmpty()) {
            StealIgnoreRules.load(ignoreNameContains);
        }
    }

    public static List<String> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    public static double getBudget() {
        return budget;
    }

    public static long getCooldownMillis() {
        return cooldownHours * 60L * 60L * 1000L;
    }

    public static double getMaxDistance() {
        return maxDistance;
    }

    public static String getAlertSubtitle() {
        return alertSubtitle;
    }

    public static String getAlertSubtitleCritical() {
        return alertSubtitleCritical;
    }

    public static boolean isMinigameEnabled() {
        return minigameEnabled;
    }

    public static double getMinigameTimeLimitSeconds() {
        return minigameTimeLimitSeconds;
    }
}
