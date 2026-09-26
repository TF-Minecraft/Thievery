package net.tfminecraft.thievery.database;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.thievery.player.PlayerData;

public final class Database {

    private Database() {}

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final File dataDir = new File("plugins/Thievery/playerdata");

    public static void savePlayerData(PlayerData data) {
        if (!dataDir.exists()) dataDir.mkdirs();

        File file = new File(dataDir, data.getId().toString() + ".json");
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static PlayerData loadPlayerData(UUID player) {
        File file = new File(dataDir, player.toString() + ".json");
        if (!file.exists()) {
            return new PlayerData(player);
        }

        try (Reader reader = new FileReader(file)) {
            PlayerData data = gson.fromJson(reader, PlayerData.class);
            if (data == null) return new PlayerData(player);
            if (data.normalizeAfterLoad()) {
                savePlayerData(data);
            }
            return data;
        } catch (IOException e) {
            e.printStackTrace();
            return new PlayerData(player);
        }
    }

    public static boolean hasPlayerData(UUID id) {
        File file = new File(dataDir, id.toString() + ".json");
        return file.exists();
    }
}
