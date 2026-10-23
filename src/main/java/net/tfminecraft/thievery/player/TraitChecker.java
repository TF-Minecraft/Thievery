package net.tfminecraft.thievery.player;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

public final class TraitChecker {

    // Cleared at startup when TLibs predates the character API; trait checks then deny.
    private static boolean characterApi = true;

    private TraitChecker() {}

    /** Looks for the TLibs 2.3.0 character API and records the result for later checks. */
    public static boolean detectCharacterApi() {
        try {
            Characters.available();
            characterApi = true;
        } catch (LinkageError error) {
            characterApi = false;
        }
        return characterApi;
    }

    public static boolean characterApiAvailable() {
        return characterApi;
    }

    /** Lower-cases configured trait IDs to match {@link CharacterInfo#traits()}. */
    public static List<String> normalise(List<String> traits) {
        List<String> normalised = new ArrayList<>(traits.size());
        for (String trait : traits) {
            normalised.add(trait.toLowerCase(Locale.ROOT));
        }
        return normalised;
    }

    /** The player's current character session, or null when none is active or the API is missing. */
    public static CharacterSession currentSession(Player player) {
        return characterApi ? Characters.currentSession(player).orElse(null) : null;
    }

    public static boolean hasTraits(Player player, List<String> configuredTraits) {
        return hasTraits(player, configuredTraits, false);
    }

    public static boolean hasLockpickingTraits(Player player) {
        return hasTraits(player, Cache.traits, true);
    }

    private static boolean hasTraits(Player player, List<String> configuredTraits, boolean explainMissing) {
        if (configuredTraits == null || configuredTraits.isEmpty()) {
            return true;
        }
        CharacterSession session = currentSession(player);
        if (session == null) {
            return false;
        }
        CharacterInfo character = Characters.info(session.ref().characterId()).orElse(null);
        if (character == null || !character.alive()) {
            return false;
        }
        for (String trait : character.traits()) {
            if (configuredTraits.contains(trait)) {
                return true;
            }
        }
        if (explainMissing) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the training to pick locks!"));
        }
        return false;
    }

    /**
     * The loadout's categories feed lockpicking, robbery and pickpocketing, so a trait for any of them opens it.
     * An activity with no configured traits is open to everyone, and so is the loadout then.
     */
    public static boolean hasLoadoutTraits(Player player) {
        LinkedHashSet<String> traits = new LinkedHashSet<>();
        for (List<String> gate : Arrays.asList(Cache.traits, RobberyLoader.getTraits(), PickpocketLoader.getTraits())) {
            if (gate == null || gate.isEmpty()) {
                return true;
            }
            traits.addAll(gate);
        }
        return hasTraits(player, List.copyOf(traits));
    }

    public static void sendMissingTraitMessage(Player player, String context) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the training for " + context + "!"));
    }

    public static void sendMissingTraitMessage(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the training for thievery!"));
    }
}
