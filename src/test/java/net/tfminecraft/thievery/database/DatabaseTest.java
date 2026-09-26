package net.tfminecraft.thievery.database;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.player.PlayerData;
import org.junit.jupiter.api.*;

class DatabaseTest {
    private final UUID id = UUID.randomUUID();
    private final Path file = Path.of("plugins/Thievery/playerdata", id + ".json");
    @AfterEach void cleanup() throws Exception { Files.deleteIfExists(file); }
    @Test void missingProfileCreatesFreshDataThenRoundTripsJson() {
        assertFalse(Database.hasPlayerData(id)); var data = Database.loadPlayerData(id); assertEquals(id, data.getId()); assertEquals(Cache.categoryPoints, data.getPoints());
        data.setPoints(7); data.setRisk(.123); data.setFactionLockWarningDismissed(true); data.recordCriticalClue("chest");
        Database.savePlayerData(data); assertTrue(Database.hasPlayerData(id));
        var loaded = Database.loadPlayerData(id); assertEquals(id, loaded.getId()); assertEquals(7, loaded.getPoints()); assertEquals(.123, loaded.getRisk()); assertTrue(loaded.isFactionLockWarningDismissed()); assertTrue(loaded.isCriticalOnCooldown("chest"));
    }
    @Test void nullJsonFallsBackAndOutOfRangeLoadedPointsArePersistedAfterNormalization() throws Exception {
        Files.createDirectories(file.getParent()); Files.writeString(file,"null"); assertEquals(id,Database.loadPlayerData(id).getId());
        Files.writeString(file,"{\"id\":\""+id+"\",\"points\":99999,\"risk\":-2}");
        var loaded = Database.loadPlayerData(id); assertEquals(Cache.categoryPoints,loaded.getPoints()); assertEquals(0,loaded.getRisk()); assertFalse(Files.readString(file).contains("99999"));
    }
    @Test void unreadableProfileFallsBackAndUnwritableDestinationDoesNotThrow() throws Exception {
        Files.createDirectories(file);
        assertEquals(id,Database.loadPlayerData(id).getId());
        assertDoesNotThrow(() -> Database.savePlayerData(new PlayerData(id)));
    }
    @Test void firstSaveCreatesTheStorageDirectoryAndPersistsTheProfile() throws Exception {
        Path folder=file.getParent().toAbsolutePath().normalize();
        assertTrue(folder.startsWith(Path.of("").toAbsolutePath().resolve("plugins")));
        assertTrue(Path.of("").toAbsolutePath().endsWith("target/test-runtime"));
        Path backup=folder.resolveSibling("playerdata-backup-"+UUID.randomUUID());boolean existed=Files.exists(folder);
        if(existed)Files.move(folder,backup);
        try {
            assertFalse(Files.exists(folder));var data=new PlayerData(id);data.setRisk(.42);
            Database.savePlayerData(data);
            assertTrue(Files.isDirectory(folder));assertTrue(Database.hasPlayerData(id));assertEquals(.42,Database.loadPlayerData(id).getRisk());
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(folder);
            if(existed)Files.move(backup,folder);
        }
    }

}
