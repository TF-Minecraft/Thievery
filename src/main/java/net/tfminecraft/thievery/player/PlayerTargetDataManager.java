package net.tfminecraft.thievery.player;



import java.io.File;

import java.io.FileReader;

import java.io.FileWriter;

import java.io.IOException;

import java.io.Reader;

import java.io.Writer;

import java.util.HashMap;

import java.util.Map;

import java.util.UUID;



import org.bukkit.Bukkit;



import com.google.gson.Gson;



import net.tfminecraft.thievery.player.PlayerTargetData;



public class PlayerTargetDataManager {



    private final File dataFolder;

    private final Gson gson = new Gson();



    private static class PlayerTargetDataJson {

        UUID victimId;

        Map<UUID, String> accessMap = new HashMap<>();

        Map<UUID, String> robberyAccessMap = new HashMap<>();

        Map<UUID, String> pickpocketAccessMap = new HashMap<>();

    }



    public PlayerTargetDataManager() {

        this.dataFolder = new File("plugins/Thievery/player-targets");

        if (!dataFolder.exists()) {

            dataFolder.mkdirs();

        }

    }



    public PlayerTargetData load(UUID victimId) {

        File file = getFile(victimId);

        if (!file.exists()) {

            return new PlayerTargetData(victimId);

        }

        try (Reader reader = new FileReader(file)) {

            PlayerTargetDataJson json = gson.fromJson(reader, PlayerTargetDataJson.class);

            PlayerTargetData data = new PlayerTargetData(victimId);

            if (json != null) {

                data.setVictimId(json.victimId != null ? json.victimId : victimId);

                if (json.robberyAccessMap != null && !json.robberyAccessMap.isEmpty()) {

                    data.setRobberyAccessMap(json.robberyAccessMap);

                } else if (json.accessMap != null) {

                    data.setRobberyAccessMap(json.accessMap);

                }

                if (json.pickpocketAccessMap != null) {

                    data.setPickpocketAccessMap(json.pickpocketAccessMap);

                }

            }

            return data;

        } catch (IOException e) {

            Bukkit.getLogger().warning("Failed to load player target data for " + victimId + ": " + e.getMessage());

            return new PlayerTargetData(victimId);

        }

    }



    public void save(PlayerTargetData data) {

        if (data == null || data.getVictimId() == null) {

            return;

        }

        File file = getFile(data.getVictimId());

        try (Writer writer = new FileWriter(file)) {

            PlayerTargetDataJson json = new PlayerTargetDataJson();

            json.victimId = data.getVictimId();

            json.robberyAccessMap = data.getRobberyAccessMap();

            json.pickpocketAccessMap = data.getPickpocketAccessMap();

            gson.toJson(json, writer);

        } catch (IOException e) {

            Bukkit.getLogger().warning("Failed to save player target data for " + data.getVictimId() + ": "

                    + e.getMessage());

        }

    }



    private File getFile(UUID victimId) {

        return new File(dataFolder, victimId.toString() + ".json");

    }

    public void removePlayerFromAllAccessMaps(UUID playerId) {
        if (playerId == null) {
            return;
        }
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                UUID victimId = UUID.fromString(file.getName().replace(".json", ""));
                PlayerTargetData data = load(victimId);
                boolean robberyChanged = data.getRobberyAccessMap().remove(playerId) != null;
                boolean pickpocketChanged = data.getPickpocketAccessMap().remove(playerId) != null;
                boolean changed = robberyChanged || pickpocketChanged;
                if (changed) {
                    save(data);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void clearAllAccessMaps() {
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                UUID victimId = UUID.fromString(file.getName().replace(".json", ""));
                PlayerTargetData data = load(victimId);
                data.getRobberyAccessMap().clear();
                data.getPickpocketAccessMap().clear();
                save(data);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

}
