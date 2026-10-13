package net.tfminecraft.thievery.steal;

import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnerClassifier;
import net.tfminecraft.thievery.owner.OwnerCodec;

/**
 * Furniture locks live in the furniture's own variables. They are read both ways until stage 7: a marked owner, or
 * a bare account UUID that is upgraded on first interaction. Unreadable variables load locked with an unresolved
 * owner, like an unreadable lock file.
 */
public final class FurnitureLockHelper {

    public static final String OWNER_KEY = "thievery.owner";
    public static final String OWNER_KIND_KEY = "thievery.ownerKind";
    public static final String LOCK_STATE_KEY = "thievery.lockState";

    /** A furniture lock as read: owner (null if unowned), state, and whether the variables were unreadable. */
    public record FurnitureLock(LockOwner owner, LockState state, boolean unreadable) {}

    private FurnitureLockHelper() {}

    public static boolean isLockable(Furniture furniture) {
        return furniture != null && Parameters.isLockableFurnitureId(furniture.getId());
    }

    public static FurnitureLock read(Furniture furniture) {
        Map<String, Object> variables = furniture.getVariables();
        try {
            OwnerCodec.Stored stored = OwnerCodec.parse(text(variables.get(OWNER_KEY)), text(variables.get(OWNER_KIND_KEY)));
            String rawState = text(variables.get(LOCK_STATE_KEY));
            if (rawState == null && stored.owned()) {
                throw new IllegalArgumentException("owned furniture without a lock state");
            }
            LockState state = rawState == null ? LockState.DEFAULT : LockState.valueOf(rawState.toUpperCase(Locale.ROOT));
            return new FurnitureLock(OwnerCodec.resolve(stored).owner(), state, false);
        } catch (IllegalArgumentException e) {
            LockQuarantine.noteUnreadable(java.nio.file.Path.of("furniture", describe(furniture.getLoc())));
            return new FurnitureLock(LockOwner.unreadable(), LockState.PRIVATE, true);
        }
    }

    private static String text(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static String describe(Location location) {
        return location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_"
                + location.getBlockZ();
    }

    public static LockOwner getOwner(Furniture furniture) {
        return furniture == null ? null : read(furniture).owner();
    }

    public static LockState getLockState(Furniture furniture) {
        return furniture == null ? LockState.DEFAULT : read(furniture).state();
    }

    /**
     * The lazy upgrade on first interaction: a bare value gains its marker, a legacy account moves to its heir.
     * Nothing is written in guard mode, for unreadable variables, or when the classification is not final.
     */
    public static void upgrade(Furniture furniture) {
        if (LockGuard.guarded()) {
            return;
        }
        Map<String, Object> variables = furniture.getVariables();
        OwnerCodec.Stored stored;
        try {
            stored = OwnerCodec.parse(text(variables.get(OWNER_KEY)), text(variables.get(OWNER_KIND_KEY)));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!stored.markerless()) {
            return;
        }
        OwnerClassifier.Classification classification = OwnerClassifier.classify(stored.id());
        if (!classification.definitive()) {
            return;
        }
        switch (classification.owner().kind()) {
            case CHARACTER, SERVER -> setOwner(furniture, classification.owner());
            case LEGACY -> setOwner(furniture, LockOwner.character(classification.heir()));
            case UNRESOLVED -> { }
        }
    }

    public static void setOwner(Furniture furniture, LockOwner owner) {
        if (furniture == null || LockGuard.guarded()) {
            return;
        }
        Map<String, Object> variables = furniture.getVariables();
        OwnerCodec.Stored stored = OwnerCodec.store(owner);
        if (!stored.owned()) {
            variables.remove(OWNER_KEY);
            variables.remove(OWNER_KIND_KEY);
        } else {
            variables.put(OWNER_KEY, stored.id().toString());
            if (stored.marker() == null) {
                variables.remove(OWNER_KIND_KEY);
            } else {
                variables.put(OWNER_KIND_KEY, stored.marker().name());
            }
        }
        persist(furniture);
    }

    public static void setLockState(Furniture furniture, LockState lockState) {
        if (furniture == null || LockGuard.guarded()) {
            return;
        }
        LockState state = lockState == null ? LockState.DEFAULT : lockState;
        furniture.getVariables().put(LOCK_STATE_KEY, state.name());
        persist(furniture);
    }

    public static LockState rotateLockState(Furniture furniture) {
        LockState next = getLockState(furniture).next();
        setLockState(furniture, next);
        return next;
    }

    private static void persist(Furniture furniture) {
        InteractibleFurniture.getInstance().getFurnitureManager().persistFurniture(furniture);
    }
}
