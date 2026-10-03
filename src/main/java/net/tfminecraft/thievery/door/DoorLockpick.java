package net.tfminecraft.thievery.door;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class DoorLockpick {

    private DoorLockpick() {}

    public static Location getDoorCenter(Location doorCanonical) {
        return doorCanonical.clone().add(0.5, 0.5, 0.5);
    }

    public static boolean isWithinDoorRange(Player player, Location doorCanonical, double maxDistance) {
        return player.getLocation().distance(getDoorCenter(doorCanonical)) <= maxDistance;
    }

    public static String doorTargetId(Location canonical) {
        if (canonical == null || canonical.getWorld() == null) {
            return "";
        }
        return "door:" + canonical.getWorld().getName()
                + ":" + canonical.getBlockX()
                + ":" + canonical.getBlockY()
                + ":" + canonical.getBlockZ();
    }

    public static String entityTargetId(UUID entityId) {
        if (entityId == null) {
            return "";
        }
        return "entity:" + entityId;
    }

    public static double computeSuccessChance(int dexterity, double lockpickStrength, double baseChance) {
        double strength = Math.min(1.0, Math.max(0.0, lockpickStrength));
        double value = baseChance * RiskCalculator.getDexterityLerpValue(dexterity) * strength;
        return Math.min(Parameters.maxSuccessChance, Math.max(0.0, value));
    }

    public interface ProximityAnchor {

        boolean isInRange(Player actor);

        void onOutOfRange(Player actor);
    }

    public static final class DoorProximityAnchor implements ProximityAnchor {

        private final Location doorLocation;

        public DoorProximityAnchor(Location doorLocation) {
            this.doorLocation = doorLocation;
        }

        @Override
        public boolean isInRange(Player actor) {
            return isWithinDoorRange(actor, doorLocation, Parameters.doorMaxDistance);
        }

        @Override
        public void onOutOfRange(Player actor) {
            actor.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Lockpicking cancelled - you moved too far from the door."));
        }

        public Location getDoorLocation() {
            return doorLocation;
        }
    }

    public static final class EntityProximityAnchor implements ProximityAnchor {

        private final Entity entity;

        public EntityProximityAnchor(Entity entity) {
            this.entity = entity;
        }

        @Override
        public boolean isInRange(Player actor) {
            if (entity == null || !entity.isValid()) {
                return false;
            }
            return actor.getLocation().distance(entity.getLocation()) <= Parameters.doorMaxDistance;
        }

        @Override
        public void onOutOfRange(Player actor) {
            actor.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Lockpicking cancelled - you moved too far."));
        }

        public Entity getEntity() {
            return entity;
        }
    }
}
