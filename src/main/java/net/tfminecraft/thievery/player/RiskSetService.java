package net.tfminecraft.thievery.player;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.database.Database;

public class RiskSetService {

    public void setForPlayer(UUID playerId, double risk) {
        PlayerManager playerManager = Thievery.getPlayerManager();
        if (playerManager.exists(playerId)) {
            applyRisk(playerManager.get(playerId), risk);
            return;
        }

        PlayerData data;
        if (Database.hasPlayerData(playerId)) {
            data = Database.loadPlayerData(playerId);
        } else {
            data = new PlayerData(playerId);
        }
        applyRisk(data, risk);
        Database.savePlayerData(data);
    }

    public void setForAll(double risk) {
        PlayerManager playerManager = Thievery.getPlayerManager();
        for (UUID id : playerManager.getLoadedIds()) {
            applyRisk(playerManager.get(id), risk);
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
                applyRisk(data, risk);
                Database.savePlayerData(data);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void applyRisk(PlayerData data, double risk) {
        data.setRisk(risk);
        data.setLastRiskDecayMs(System.currentTimeMillis());
    }

    public static String formatRisk(double risk) {
        return String.format(Locale.ROOT, "%.3f", risk);
    }
}
