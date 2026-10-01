package net.tfminecraft.thievery.door;

import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/** In-world lines for a lock that turns a player away, shared by containers and displays. */
public final class LockDenial {

    static final String PRIVATE = "It's locked. Only its owner holds the key.";
    static final String PRIVATE_PICKABLE = "It's locked. Only its owner holds the key, though a steady hand and a pick might manage.";
    static final String GUILD = "It's sealed for members of its guild alone.";
    static final String FACTION = "It's barred to all outside its faction.";
    static final String NOT_OWNER = "Only the owner can change this lock.";
    static final String ALREADY_OPEN = "No need for picks, it's already open to you.";

    private static final Map<LockState, String> LINES = Map.of(
            LockState.PRIVATE, PRIVATE,
            LockState.GUILD, GUILD,
            LockState.FACTION, FACTION);

    private LockDenial() {}

    /** The line for a lock in this state; the pick hint only applies to personal locks a thief can pick. */
    public static String line(LockState state, boolean pickable) {
        if (pickable && state == LockState.PRIVATE) {
            return PRIVATE_PICKABLE;
        }
        return LINES.getOrDefault(state, PRIVATE);
    }

    public static void send(Player player, LockState state, boolean pickable) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + line(state, pickable)));
    }

    public static void sendNotOwner(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + NOT_OWNER));
    }

    public static void sendAlreadyOpen(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + ALREADY_OPEN));
    }

    /** Chest lockpicking skips the configured excluded container materials. */
    public static boolean chestPickable(Material type) {
        return !Parameters.excludedContainerMaterials.contains(type);
    }

    /** Stricter of the halves that turn the player away: private, then guild, then faction. */
    static LockState deniedState(Player player, ContainerData left, ContainerData right) {
        LockState state = LockState.PUBLIC;
        for (ContainerData half : new ContainerData[] { left, right }) {
            if (!half.canAccess(player) && half.getLockState().ordinal() < state.ordinal()) {
                state = half.getLockState();
            }
        }
        return state;
    }
}
