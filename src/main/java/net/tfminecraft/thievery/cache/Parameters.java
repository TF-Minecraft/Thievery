package net.tfminecraft.thievery.cache;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.LockTypeProfile;

/**
 * Central tuning parameters for the door locking and lockpicking system.
 */
public final class Parameters {

    private Parameters() {}

    public static double lockpickMaxReduction = 0.5;

    /** Minimum lockpick strength as a fraction of door lock strength (e.g. 0.5 = 50%). */
    public static double lockpickMinLockStrengthRatio = 0.5;

    public static long lockpickFailCooldownMs = 60_000L;

    public static int barLength = 20;
    public static int maxSuccessSlots = 3;
    public static int minBreakSlots = 3;
    public static int maxBreakSlots = 19;

    public static double baseBarSpeed = 2.5;
    public static double dexSpeedReductionPerLevel = 0.02;
    public static double minBarSpeed = 0.4;
    public static double speedJitterFraction = 0.3;
    public static double randomFlipChance = 0.03;

    public static String lockpickAttribute = "dexterity";

    public static double chestBaseSuccessChance = 1.0;

    /** Per-slot break multiplier step (1st reveal = 1×step, 2nd = 2×step, … capped at full break chance). */
    public static double chestBreakChanceRampPerSlot = 0.1;

    public static double maxSuccessChance = 0.95;

    public static double doorMaxDistance = 3.0;

    public static long doorUnlockWindowMs = 60L * 60L * 1000L;

    public static Set<Material> excludedContainerMaterials = EnumSet.of(Material.ENDER_CHEST);

    public static Set<String> lockableFurnitureIds = new HashSet<>();
    public static Set<EntityType> lockableEntityTypes = EnumSet.noneOf(EntityType.class);
    public static double displayLockStrength = 0.5;

    private static final Map<LockState, LockTypeProfile> lockTypeProfiles = new EnumMap<>(LockState.class);

    public static void clearLockTypeProfiles() {
        lockTypeProfiles.clear();
    }

    public static void putLockTypeProfile(LockState state, LockTypeProfile profile) {
        if (state == null || profile == null) {
            return;
        }
        lockTypeProfiles.put(state, profile);
    }

    public static LockTypeProfile lockTypeProfile(LockState state) {
        if (state == null) {
            return LockTypeProfile.IDENTITY;
        }
        return lockTypeProfiles.getOrDefault(state, LockTypeProfile.IDENTITY);
    }

    public static boolean isLockableFurnitureId(String furnitureId) {
        if (furnitureId == null || furnitureId.isBlank()) {
            return false;
        }
        return lockableFurnitureIds.contains(furnitureId.toLowerCase());
    }

    public static boolean isLockableEntityType(EntityType type) {
        return type != null && lockableEntityTypes.contains(type);
    }
}
