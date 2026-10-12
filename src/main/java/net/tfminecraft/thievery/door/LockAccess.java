package net.tfminecraft.thievery.door;

import java.util.UUID;

import org.bukkit.Bukkit;
import net.tfminecraft.thievery.utils.FactionAccess;
import org.bukkit.entity.Player;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

public final class LockAccess {

    private LockAccess() {}

    public static String ownerName(UUID owner) {
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name != null ? name : "an unknown player";
    }

    public static boolean canAccess(Player player, UUID owner, LockState lockState) {
        if (lockState == LockState.PUBLIC) {
            return true;
        }
        if (owner == null) {
            return true;
        }
        if (FactionAccess.owns(player, owner)) {
            return true;
        }
        if (lockState == LockState.PRIVATE) {
            return false;
        }

        if (!FactionAccess.ready()) return false;
        Guild ownerGuild = FactionAccess.ownerGuild(owner);
        Guild openerGuild = FactionManager.getGuildByMember(player);
        if (ownerGuild == null || openerGuild == null) {
            return false;
        }
        if (lockState == LockState.GUILD) {
            return ownerGuild.getId().equals(openerGuild.getId());
        }
        if (lockState == LockState.FACTION) {
            if (ownerGuild.getFaction() == null || openerGuild.getFaction() == null) {
                return false;
            }
            return ownerGuild.getFaction().getId().equals(openerGuild.getFaction().getId());
        }
        return false;
    }
}
