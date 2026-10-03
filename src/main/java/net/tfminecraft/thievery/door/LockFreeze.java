package net.tfminecraft.thievery.door;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.utils.Keys;

/**
 * Holds a thief still while they work the lockpick ring, so the movement keys can be read without moving
 * anything on screen. A walk speed of 0 also leaves the client's field of view unchanged.
 *
 * <p>The original walk speed is stored on the player, so a crash or restart mid-pick is undone on their next
 * join. The jump block is a transient modifier, which is never saved.
 */
public final class LockFreeze {

    private LockFreeze() {}

    public static void freeze(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (!data.has(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT)) {
            data.set(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT, player.getWalkSpeed());
        }
        player.setWalkSpeed(0f);
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(Keys.lockpickJump) == null) {
            jump.addTransientModifier(new AttributeModifier(Keys.lockpickJump, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        if (player.isFlying()) {
            data.set(Keys.lockpickFlying, PersistentDataType.BYTE, (byte) 1);
            player.setFlying(false);
        }
    }

    public static boolean isFrozen(Player player) {
        return player.getPersistentDataContainer().has(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT);
    }

    /** Puts back whatever {@link #freeze} changed. Safe to call on a player who was never frozen. */
    public static void release(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Float walkSpeed = data.get(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT);
        if (walkSpeed != null) {
            player.setWalkSpeed(walkSpeed);
            data.remove(Keys.lockpickWalkSpeed);
        }
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(Keys.lockpickJump);
        }
        if (data.has(Keys.lockpickFlying, PersistentDataType.BYTE)) {
            data.remove(Keys.lockpickFlying);
            if (player.getAllowFlight()) {
                player.setFlying(true);
            }
        }
    }
}
