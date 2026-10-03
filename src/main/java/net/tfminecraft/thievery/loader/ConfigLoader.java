package net.tfminecraft.thievery.loader;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.LockTypeProfile;
import net.tfminecraft.thievery.door.PinGrid;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class ConfigLoader {
    public void loadConfig(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return;
        }

        KeyLoader.load(config);
        LockpickLoader.load(config);
        KeychainLoader.load(config);
        KeyCopyLoader.load(config);
        RobberyLoader.load(config);
        PickpocketLoader.load(config);
        DoorLoader.load(config);

        Cache.cooldown = config.getInt("cooldown", 3);
        Cache.radius = config.getInt("lockpick-range", config.getInt("radius", 4));
        Cache.categoryPoints = config.getInt("category_points", 30);
        Cache.pointGainIntervalHours = config.getInt("point_gain_interval", 24);
        Cache.defaultItemValue = config.getDouble("default_item_value",
                config.getDouble("default_value", 0.1));
        loadItemValue(config.getConfigurationSection("item_value"));

        if (config.contains("traits")) Cache.traits = config.getStringList("traits");

        Cache.recentClueMax = config.getInt("clues.recent-max", 6);
        Cache.recentClueCooldownHours = config.getInt("clues.recent-cooldown-hours", 72);
        Cache.criticalCooldownHours = config.getInt("clues.critical-cooldown-hours", 24);
        Cache.minCluesDoor = Math.max(0, config.getInt("clues.min-clues-door", 0));
        Cache.minCluesContainer = Math.max(0, config.getInt("clues.min-clues-container", 1));

        double legacyRiskMin = config.getDouble("clues.risk-gain-min", 0.05);
        double legacyRiskMax = config.getDouble("clues.risk-gain-max", 0.15);
        Cache.riskGainDoorMin = config.getDouble("clues.risk-gain-door.min", legacyRiskMin);
        Cache.riskGainDoorMax = config.getDouble("clues.risk-gain-door.max", legacyRiskMax);
        if (config.contains("clues.risk-gain-chest.min") || config.contains("clues.risk-gain-chest.max")) {
            Cache.riskGainChestMin = config.getDouble("clues.risk-gain-chest.min", legacyRiskMin * 0.5);
            Cache.riskGainChestMax = config.getDouble("clues.risk-gain-chest.max", legacyRiskMax * 0.5);
        } else {
            Cache.riskGainChestMin = Cache.riskGainDoorMin * 0.5;
            Cache.riskGainChestMax = Cache.riskGainDoorMax * 0.5;
        }

        if (config.contains("clues.risk-gain-pickpocket.min") || config.contains("clues.risk-gain-pickpocket.max")) {
            Cache.riskGainPickpocketMin = config.getDouble("clues.risk-gain-pickpocket.min", 0.04);
            Cache.riskGainPickpocketMax = config.getDouble("clues.risk-gain-pickpocket.max", 0.12);
        } else {
            Cache.riskGainPickpocketMin = 0.04;
            Cache.riskGainPickpocketMax = 0.12;
        }

        Cache.riskPickReduction = config.getDouble("clues.risk-pick-reduction", 0.5);
        Cache.riskDecayPerHour = config.getDouble("clues.risk-decay-per-hour", 0.08);
        Cache.criticalBase = config.getDouble("clues.critical-base", 0.0);
        Cache.criticalRiskWeight = config.getDouble("clues.critical-risk-weight", 0.5);
        Cache.criticalDexReduction = config.getDouble("clues.critical-dex-reduction", 0.15);
        Cache.criticalStrengthReduction = config.getDouble("clues.critical-strength-reduction", 0.2);
        Cache.takeValueScale = config.getDouble("clues.take-value-scale", 12.0);
        Cache.takeValueMaxBonus = config.getDouble("clues.take-value-max-bonus", 0.35);
        Cache.takeClueDivisor = Math.max(1.0, config.getDouble("clues.take-clue-divisor", 10.0));
        Cache.criticalClue = ThieveryTexts.formatGui(config.getString("clues.critical-clue",
                "§7This seems to be the work of #d6cf69{character_name}"));

        Parameters.chestBaseSuccessChance = config.getDouble("lockpicking.chest.base-success-chance",
                config.getDouble("lockpicking.chest.base-chance", 1.0));
        Parameters.chestSeizedDensity = Math.max(0.0, config.getDouble("lockpicking.chest.seized-density", 0.3));
        Parameters.chestSeizedPerGridMistake = Math.max(0, config.getInt("lockpicking.chest.seized-per-grid-mistake", 1));
        loadChestMinigame(config);
        loadLockTypeProfiles(config);
        Parameters.maxSuccessChance = config.getDouble("lockpicking.max-success-chance", 0.95);
        if (config.isConfigurationSection("lockpicking.dex-map")) {
            RiskCalculator.loadDexterityLerp(config.getConfigurationSection("lockpicking.dex-map").getValues(false));
        } else {
            RiskCalculator.loadDexterityLerp(null);
        }
        Cache.requireOwnerOnline = config.getBoolean("lockpicking.require-owner-online", false);
        Cache.debugAllowOwnChest = config.getBoolean("lockpicking.debug-allow-own-chest", false);
        Cache.debugCluePreview = config.getBoolean("lockpicking.debug-clue-preview", false);
        Parameters.doorMaxDistance = config.getDouble("lockpicking.door-max-distance", 3.0);
        Parameters.lockpickMaxReduction = config.getDouble("lockpicking.lockpick-max-reduction", 0.5);
        Parameters.lockpickMinLockStrengthRatio = config.getDouble("lockpicking.min-lock-strength-ratio", 0.5);
        Parameters.lockpickFailCooldownMs = config.getLong("lockpicking.fail-cooldown-ms", 60_000L);
        Parameters.doorUnlockWindowMs = config.getLong("lockpicking.door-unlock-window-minutes", 60L)
                * 60L * 1000L;

        Parameters.barLength = Math.max(1, config.getInt("lockpicking.bar.length", 20));
        Parameters.maxSuccessSlots = config.getInt("lockpicking.bar.max-success-slots", 3);
        Parameters.minBreakSlots = config.getInt("lockpicking.bar.min-break-slots", 3);
        Parameters.maxBreakSlots = config.getInt("lockpicking.bar.max-break-slots", 19);
        Parameters.baseBarSpeed = config.getDouble("lockpicking.bar.base-speed", 2.5);
        Parameters.dexSpeedReductionPerLevel = config.getDouble("lockpicking.bar.dex-speed-reduction-per-level", 0.02);
        Parameters.minBarSpeed = config.getDouble("lockpicking.bar.min-speed", 0.4);
        Parameters.speedJitterFraction = config.getDouble("lockpicking.bar.speed-jitter-fraction", 0.3);
        Parameters.randomFlipChance = config.getDouble("lockpicking.bar.random-flip-chance", 0.03);
        Parameters.lockpickAttribute = config.getString("lockpicking.attribute", "dexterity");
        Parameters.excludedContainerMaterials = loadExcludedContainers(config);
        Parameters.lockableFurnitureIds = loadLockableFurnitureIds(config);
        Parameters.lockableEntityTypes = loadLockableEntityTypes(config);
        Parameters.displayLockStrength = Math.min(1.0, Math.max(0.0,
                config.getDouble("lockpicking.display-lock-strength", 0.5)));
    }

    static final int MAX_TUMBLERS = 6;

    private static void loadChestMinigame(FileConfiguration config) {
        String path = "lockpicking.chest.minigame.";
        Parameters.chestMinigameEnabled = config.getBoolean(path + "enabled", true);
        Parameters.chestMinigameRows = Math.max(1, Math.min(PinGrid.MAX_ROWS, config.getInt(path + "rows", 6)));
        Parameters.chestMinigameColumns = Math.max(1,
                Math.min(PinGrid.MAX_COLUMNS, config.getInt(path + "columns", 6)));
        Parameters.chestMinigamePins = Math.max(1, Math.min(Parameters.chestMinigameRows * Parameters.chestMinigameColumns,
                config.getInt(path + "pins", 14)));
        Parameters.chestMinigamePrepareSeconds = Math.max(0.0, config.getDouble(path + "prepare-seconds", 1.0));
        Parameters.chestMinigameMemoriseSeconds = Math.max(0.0, config.getDouble(path + "memorise-seconds", 4.0));
        Parameters.chestMinigameRecallSeconds = Math.max(0.0, config.getDouble(path + "recall-seconds", 6.0));
        Parameters.chestMinigameRecallSecondsPerDexterity = Math.max(0.0,
                config.getDouble(path + "recall-seconds-per-dexterity", 0.05));
        Parameters.chestMinigameMistakesToFail = Math.max(1, config.getInt(path + "mistakes-to-fail", 3));
        Parameters.chestMinigameFailBreakChance = Math.max(0.0,
                Math.min(1.0, config.getDouble(path + "fail-break-chance", 0.5)));
        Parameters.chestDialChance = Math.max(0.0, Math.min(1.0, config.getDouble(path + "dial-chance", 0.5)));
        Parameters.chestGridSprites = config.getBoolean(path + "grid.sprites", true);
        Parameters.chestGridPack = config.getBoolean(path + "grid.pack", false);
        Parameters.chestDialTumblers = Math.max(1, Math.min(MAX_TUMBLERS, config.getInt(path + "dial.tumblers", 4)));
        Parameters.chestDialZoneWidth = Math.max(0.02, Math.min(0.25, config.getDouble(path + "dial.zone-width", 0.14)));
        Parameters.chestDialZoneShrinkPerTumbler = Math.max(0.0,
                Math.min(0.5, config.getDouble(path + "dial.zone-shrink-per-tumbler", 0.15)));
        Parameters.chestDialMinLapSeconds = Math.max(0.5, config.getDouble(path + "dial.min-lap-seconds", 1.44));
        Parameters.chestDialMaxLapSeconds = Math.max(Parameters.chestDialMinLapSeconds,
                config.getDouble(path + "dial.max-lap-seconds", 2.08));
        Parameters.chestDialLapSecondsPerDexterity = Math.max(0.0,
                config.getDouble(path + "dial.lap-seconds-per-dexterity", 0.008));
        Parameters.chestDialMistakesToFail = Math.max(1, config.getInt(path + "dial.mistakes-to-fail", 1));
        Parameters.chestDialAlternate = config.getBoolean(path + "dial.alternate-direction", true);
        Parameters.chestDialMaxLagTicks = Math.max(0, config.getInt(path + "dial.max-lag-ticks", 6));
        Parameters.chestDialDistance = Math.max(1.0, Math.min(4.0, config.getDouble(path + "dial.distance", 2.4)));
    }

    private static void loadLockTypeProfiles(FileConfiguration config) {
        Parameters.clearLockTypeProfiles();
        ConfigurationSection types = config.getConfigurationSection("lockpicking.lock-types");
        if (types == null) {
            return;
        }
        for (String key : types.getKeys(false)) {
            if (key.isBlank()) {
                continue;
            }
            LockState state;
            try {
                state = LockState.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                Thievery.getInstance().getLogger().warning("Unknown lockpicking lock type: " + key);
                continue;
            }
            ConfigurationSection section = types.getConfigurationSection(key);
            double budget = section == null ? 1.0 : section.getDouble("budget-multiplier", 1.0);
            double risk = section == null ? 1.0 : section.getDouble("risk-multiplier", 1.0);
            boolean critical = section == null || section.getBoolean("critical-risk", true);
            double breakChance = section == null ? 1.0 : section.getDouble("break-chance-multiplier", 1.0);
            Parameters.putLockTypeProfile(state, new LockTypeProfile(budget, risk, critical, breakChance));
        }
    }

    private static java.util.Set<Material> loadExcludedContainers(FileConfiguration config) {
        java.util.Set<Material> excluded = java.util.EnumSet.noneOf(Material.class);
        for (String entry : config.getStringList("lockpicking.excluded-containers")) {
            if (entry.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(entry.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                Thievery.getInstance().getLogger().warning(
                        "Unknown lockpicking excluded container material: " + entry);
                continue;
            }
            excluded.add(material);
        }
        if (excluded.isEmpty()) {
            return java.util.EnumSet.of(Material.ENDER_CHEST);
        }
        return excluded;
    }

    private static java.util.Set<String> loadLockableFurnitureIds(FileConfiguration config) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String entry : config.getStringList("lockpicking.lockable-furniture")) {
            if (entry.isBlank()) {
                continue;
            }
            ids.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static java.util.Set<EntityType> loadLockableEntityTypes(FileConfiguration config) {
        java.util.Set<EntityType> types = java.util.EnumSet.noneOf(EntityType.class);
        for (String entry : config.getStringList("lockpicking.lockable-entities")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                types.add(EntityType.valueOf(entry.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                Thievery.getInstance().getLogger().warning(
                        "Unknown lockpicking lockable entity type: " + entry);
            }
        }
        return types;
    }

    private static void loadItemValue(ConfigurationSection section) {
        Cache.putDefaultItemValueTables();
        if (section == null) {
            return;
        }
        ConfigurationSection quality = section.getConfigurationSection("quality");
        if (quality != null && !quality.getKeys(false).isEmpty()) {
            Cache.clearQualityPercents();
            for (String key : quality.getKeys(false)) {
                Cache.putQualityPercent(key, quality.getDouble(key));
            }
        }
        ConfigurationSection aura = section.getConfigurationSection("aura");
        if (aura != null && !aura.getKeys(false).isEmpty()) {
            Cache.clearAuraPercents();
            for (String key : aura.getKeys(false)) {
                try {
                    Cache.putAuraPercent(Integer.parseInt(key.trim()), aura.getDouble(key));
                } catch (NumberFormatException ignored) {
                    Thievery.getInstance().getLogger().warning(
                            "[Thievery] item_value.aura '" + key + "' is not a band number");
                }
            }
        }
        ConfigurationSection mins = section.getConfigurationSection("aura_mins");
        if (mins != null && !mins.getKeys(false).isEmpty()) {
            Cache.clearAuraMins();
            for (String key : mins.getKeys(false)) {
                try {
                    Cache.putAuraMin(Integer.parseInt(key.trim()), mins.getDouble(key));
                } catch (NumberFormatException ignored) {
                    Thievery.getInstance().getLogger().warning(
                            "[Thievery] item_value.aura_mins '" + key + "' is not a band number");
                }
            }
        }
    }
}
