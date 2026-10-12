package net.tfminecraft.thievery.player;

import java.util.UUID;

import org.bukkit.Bukkit;
import net.tfminecraft.thievery.utils.FactionAccess;
import net.tfminecraft.tlibs.character.Characters;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

public final class TargetKeyResolver {

    public static final String NONE = "none";

    private TargetKeyResolver() {}

    public static String resolve(UUID ownerUUID) {
        if (ownerUUID == null) {
            return NONE;
        }
        if (!FactionAccess.ready() || FactionAccess.heir(ownerUUID).isEmpty()) return NONE;
        Guild guild = FactionAccess.ownerGuild(ownerUUID);
        if (guild != null) {
            return "guild:" + guild.getId();
        }
        return "player:" + ownerUUID;
    }
}
