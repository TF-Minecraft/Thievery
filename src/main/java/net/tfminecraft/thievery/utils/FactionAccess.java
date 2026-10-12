package net.tfminecraft.thievery.utils;

import java.util.UUID;
import java.util.Optional;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.entity.Player;

/** Legacy locks still store an account; its reviewed heir holds those rights until lock migration. */
public final class FactionAccess {
    private FactionAccess() {}

    public static boolean ready() { return SimpleFactionsApi.ready() && Characters.legacyHeirsReady(); }

    public static Optional<CharacterRef> heir(UUID owner) {
        return !ready() || owner == null ? Optional.empty() : Characters.legacyHeir(owner);
    }

    public static Guild ownerGuild(UUID owner) {
        return heir(owner)
                .map(ref -> FactionManager.getGuildByMember(ref.characterId())).orElse(null);
    }

    public static boolean owns(Player player, UUID owner) {
        if (!ready() || player == null || owner == null) return false;
        return heir(owner).filter(ref -> Characters.currentSession(player)
                .filter(Characters::isCurrent).map(session -> session.ref().equals(ref)).orElse(false)).isPresent();
    }
}
