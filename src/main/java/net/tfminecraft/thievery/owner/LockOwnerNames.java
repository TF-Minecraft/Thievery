package net.tfminecraft.thievery.owner;

import java.util.Optional;

import org.bukkit.Bukkit;

import net.tfminecraft.tlibs.character.CharacterInfo;

/** Owner names for staff. Thief-facing text never names an owner. */
public final class LockOwnerNames {

    private LockOwnerNames() {}

    /** For example "Kira (Steve)", "the server" or "unresolved account Steve". */
    public static String staffLabel(LockOwner owner) {
        if (owner == null) {
            return "nobody";
        }
        return switch (owner.kind()) {
            case SERVER -> "the server";
            case CHARACTER -> characterLabel(owner);
            case LEGACY -> "legacy account " + accountName(owner);
            case UNRESOLVED -> owner.id() == null ? "an unreadable record" : "unresolved account " + accountName(owner);
        };
    }

    private static String characterLabel(LockOwner owner) {
        Optional<CharacterInfo> info = OwnerIdentity.info(owner.id());
        if (info.isEmpty()) {
            return "unknown character " + owner.id();
        }
        String account = Bukkit.getOfflinePlayer(info.get().ref().ownerId()).getName();
        String dead = info.get().alive() ? "" : ", " + info.get().status().name().toLowerCase(java.util.Locale.ROOT);
        return info.get().name() + " (" + (account == null ? info.get().ref().ownerId() : account) + dead + ")";
    }

    private static String accountName(LockOwner owner) {
        String name = Bukkit.getOfflinePlayer(owner.id()).getName();
        return name == null ? owner.id().toString() : name + " (" + owner.id() + ")";
    }
}
