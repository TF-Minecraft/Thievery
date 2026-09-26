package net.tfminecraft.rpcharacters.evilrp;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * Compatibility fixture for the optional API absent from the pinned RPCharacters release.
 * Delete it once the pin reaches a release that ships EvilRpService (2.4.0 or later).
 */
public final class EvilRpService {
    public static final Consumer<Player> IGNORE = player -> {};
    public static Consumer<Player> recorder = IGNORE;
    public static boolean recordPlay(Player player) {
        recorder.accept(player);
        return true;
    }
}
