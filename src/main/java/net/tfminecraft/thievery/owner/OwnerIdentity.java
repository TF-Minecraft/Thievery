package net.tfminecraft.thievery.owner;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.player.TraitChecker;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.Characters;

/** Character lookups for lock ownership. Every method runs on the main thread, as TLibs requires. */
public final class OwnerIdentity {

    // Null until probed; TLibs 2.4.0 added the heir lookups that classification and migration need.
    private static Boolean heirApi;

    private OwnerIdentity() {}

    /** Whether TLibs offers {@code legacyHeir} and {@code legacyHeirsReady}. Probed once and cached. */
    public static boolean heirApiPresent() {
        if (heirApi == null) {
            heirApi = probeHeirApi("net.tfminecraft.tlibs.character.Characters");
        }
        return heirApi;
    }

    static boolean probeHeirApi(String className) {
        try {
            Class<?> characters = Class.forName(className);
            characters.getMethod("legacyHeir", UUID.class);
            characters.getMethod("legacyHeirsReady");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    static void resetProbe() {
        heirApi = null;
    }

    /** Whether a character provider is registered. */
    public static boolean available() {
        return TraitChecker.characterApiAvailable() && Characters.available();
    }

    /** Whether heir answers are final: the provider is registered and has loaded the reviewed heirs. */
    public static boolean heirsReady() {
        return heirApiPresent() && available() && Characters.legacyHeirsReady();
    }

    /** The character the player is playing now, if the session is current. */
    public static Optional<UUID> currentCharacter(Player player) {
        if (player == null || !TraitChecker.characterApiAvailable()) {
            return Optional.empty();
        }
        return Characters.currentSession(player).filter(Characters::isCurrent)
                .map(session -> session.ref().characterId());
    }

    public static Optional<CharacterInfo> info(UUID characterId) {
        if (characterId == null || !available()) {
            return Optional.empty();
        }
        return Characters.info(characterId);
    }

    /** The reviewed heir character of an account, empty while heirs are not ready. */
    public static Optional<UUID> heir(UUID accountId) {
        if (accountId == null || !heirsReady()) {
            return Optional.empty();
        }
        return Characters.legacyHeir(accountId).map(ref -> ref.characterId());
    }

    /** Whether the provider could read this account's characters; unknown must never be treated as none. */
    public static boolean accountKnown(UUID accountId) {
        return accountId != null && available() && Characters.characters(accountId).isPresent();
    }

    /** Whether someone online is playing this character right now. */
    public static boolean playing(UUID characterId) {
        if (characterId == null) {
            return false;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (currentCharacter(online).filter(characterId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
