package net.tfminecraft.thievery.player;

import org.bukkit.entity.Player;

import java.util.List;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

public final class TraitChecker {

    private TraitChecker() {}

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
        CharacterSession session = Characters.currentSession(player).orElse(null);
        if (session == null) {
            return false;
        }
        CharacterInfo character = Characters.info(session.ref().characterId()).orElse(null);
        if (character == null || !character.ref().equals(session.ref()) || !character.alive()
                || !Characters.isCurrent(session)) {
            return false;
        }
        for (String trait : character.traits()) {
            if (configuredTraits.contains(trait)) {
                return true;
            }
        }
        if (explainMissing) {
            player.sendMessage("§cYou lack the training to pick locks!");
        }
        return false;
    }

    public static boolean hasRequiredTraits(Player player) {
        return hasTraits(player, Cache.traits);
    }

    public static void sendMissingTraitMessage(Player player, String context) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the training for " + context + "!"));
    }

    public static void sendMissingTraitMessage(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the training for thievery!"));
    }
}
