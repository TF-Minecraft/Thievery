package net.tfminecraft.thievery.player;

import java.io.File;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.door.LockPickManager;
import net.tfminecraft.thievery.database.Database;

public class CooldownResetService {

    private final PlayerTargetDataManager targetDataManager = new PlayerTargetDataManager();
    private final ContainerDataManager containerDataManager = new ContainerDataManager();
    private final LockPickManager lockPickManager;

    public CooldownResetService(LockPickManager lockPickManager) {
        this.lockPickManager = lockPickManager;
    }

    public void resetForPlayer(UUID playerId) {
        targetDataManager.removePlayerFromAllAccessMaps(playerId);
        containerDataManager.removePlayerFromAllAccessMaps(playerId);
        lockPickManager.clearCooldown(playerId);
        resetPersonalCooldowns(playerId);
    }

    public void resetAll() {
        targetDataManager.clearAllAccessMaps();
        containerDataManager.clearAllAccessMaps();
        lockPickManager.clearAllCooldowns();
        resetAllPersonalCooldowns();
    }

    private void resetPersonalCooldowns(UUID playerId) {
        PlayerManager playerManager = Thievery.getPlayerManager();
        if (playerManager.exists(playerId)) {
            playerManager.get(playerId).clearCooldowns();
            return;
        }
        if (Database.hasPlayerData(playerId)) {
            PlayerData data = Database.loadPlayerData(playerId);
            data.clearCooldowns();
            Database.savePlayerData(data);
        }
    }

    private void resetAllPersonalCooldowns() {
        PlayerManager playerManager = Thievery.getPlayerManager();
        for (UUID id : playerManager.getLoadedIds()) {
            playerManager.get(id).clearCooldowns();
        }

        File dataDir = new File("plugins/Thievery/playerdata");
        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                UUID id = UUID.fromString(file.getName().replace(".json", ""));
                if (playerManager.exists(id)) {
                    continue;
                }
                PlayerData data = Database.loadPlayerData(id);
                data.clearCooldowns();
                Database.savePlayerData(data);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // Existing configuration identifies offline profiles by player name, not UUID.
    @SuppressWarnings("deprecation")
    public static String resolveTargetName(String arg) {
        if (arg.equalsIgnoreCase("all")) {
            return "all players";
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(arg);
        if (offline.getName() != null) {
            return offline.getName();
        }
        return arg;
    }

    // Existing configuration identifies offline profiles by player name, not UUID.
    @SuppressWarnings("deprecation")
    public static UUID resolveTargetId(String arg) {
        if (arg.equalsIgnoreCase("all")) {
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(arg);
        return offline.getUniqueId();
    }
}
