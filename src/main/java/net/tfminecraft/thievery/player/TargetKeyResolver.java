package net.tfminecraft.thievery.player;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

public final class TargetKeyResolver {

    public static final String NONE = "none";

    private TargetKeyResolver() {}

    public static String resolve(UUID ownerUUID) {
        if (ownerUUID == null) {
            return NONE;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
        String ownerName = owner.getName();
        if (ownerName == null) {
            return NONE;
        }
        Guild guild = FactionManager.getGuildByMember(ownerName);
        if (guild != null) {
            return "guild:" + guild.getId();
        }
        return "player:" + ownerUUID;
    }
}
