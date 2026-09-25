package net.tfminecraft.thievery.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.Thievery;

/**
 * Tells RPCharacters about evil plays so it can start or reset the player's evil RP session.
 *
 * <p>Looked up at runtime because the pinned RPCharacters release predates evil RP sessions.
 * Once the pin includes {@code EvilRpService}, call {@code EvilRpService.recordPlay} directly.
 */
public final class EvilRpPlays {

    private static final String SERVICE_CLASS = "net.tfminecraft.rpcharacters.evilrp.EvilRpService";

    private static MethodHandle recordPlay;
    private static boolean resolved;

    private EvilRpPlays() {}

    public static void record(Player player) {
        MethodHandle handle = resolve();
        if (handle == null || player == null) {
            return;
        }
        try {
            boolean ignored = (boolean) handle.invokeExact(player);
        } catch (Throwable t) {
            Thievery.getInstance().getLogger().warning("Could not record evil RP play for "
                    + player.getName() + ": " + t);
        }
    }

    private static MethodHandle resolve() {
        if (resolved) {
            return recordPlay;
        }
        resolved = true;
        try {
            Class<?> service = Class.forName(SERVICE_CLASS);
            recordPlay = MethodHandles.publicLookup().findStatic(service, "recordPlay",
                    MethodType.methodType(boolean.class, Player.class));
        } catch (ReflectiveOperationException e) {
            Thievery.getInstance().getLogger().warning(
                    "This RPCharacters version has no evil RP sessions; lockpicking and theft won't start them.");
        }
        return recordPlay;
    }
}
