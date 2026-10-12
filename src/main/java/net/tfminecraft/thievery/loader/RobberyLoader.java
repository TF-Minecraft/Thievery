package net.tfminecraft.thievery.loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import net.tfminecraft.thievery.player.TraitChecker;
import net.tfminecraft.thievery.steal.StealIgnoreRules;

public final class RobberyLoader {

    private static List<String> traits = new ArrayList<>();
    private static double budget = 300;
    private static int cooldownDays = 3;
    private static int durationSeconds = 120;
    private static double maxDistance = 4;
    private static int acceptTimeoutSeconds = 30;
    private static int pouchClickAmount = 10;
    private static int pouchShiftAmount = 100;
    private static List<String> ignoreNameContains = new ArrayList<>();

    private RobberyLoader() {}

    public static void load(FileConfiguration config) {
        traits = TraitChecker.normalise(config.getStringList("robbery.traits"));
        budget = config.getDouble("robbery.budget", 300);
        cooldownDays = config.getInt("robbery.cooldown-days",
                config.getInt("player-steal.cooldown-days", config.getInt("cooldown", 3)));
        durationSeconds = config.getInt("robbery.duration-seconds", 120);
        maxDistance = config.getDouble("robbery.max-distance", 4);
        acceptTimeoutSeconds = config.getInt("robbery.accept-timeout-seconds", 30);
        ignoreNameContains = config.getStringList("robbery.ignore.name-contains");
        if (ignoreNameContains.isEmpty()) {
            ignoreNameContains = new ArrayList<>(List.of("Slot"));
        }
        StealIgnoreRules.load(ignoreNameContains);
    }

    public static List<String> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    public static double getBudget() {
        return budget;
    }

    public static int getCooldownDays() {
        return cooldownDays;
    }

    public static int getDurationSeconds() {
        return durationSeconds;
    }

    public static double getMaxDistance() {
        return maxDistance;
    }

    public static int getAcceptTimeoutSeconds() {
        return acceptTimeoutSeconds;
    }

    public static int getPouchClickAmount() {
        return pouchClickAmount;
    }

    public static int getPouchShiftAmount() {
        return pouchShiftAmount;
    }

    public static List<String> getIgnoreNameContains() {
        return Collections.unmodifiableList(ignoreNameContains);
    }
}
