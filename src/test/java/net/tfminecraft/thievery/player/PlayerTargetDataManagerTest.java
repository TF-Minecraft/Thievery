package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class PlayerTargetDataManagerTest {
    @TempDir Path dir;
    PlayerTargetDataManager manager;
    MockedStatic<Bukkit> bukkit;
    Logger logger;
    @BeforeEach void setup() throws Exception {
        manager = new PlayerTargetDataManager();
        var folder = PlayerTargetDataManager.class.getDeclaredField("dataFolder"); folder.setAccessible(true); folder.set(manager, dir.toFile());
        logger = mock(Logger.class); bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }
    @AfterEach void close() { bukkit.close(); }
    Path file(UUID id) { return dir.resolve(id + ".json"); }
    @Test void roundTripKeepsBothActivityHistoriesAndVictimIdentity() {
        UUID victim = UUID.randomUUID(), attacker = UUID.randomUUID();
        var fresh = manager.load(victim); assertEquals(victim, fresh.getVictimId()); assertTrue(fresh.getRobberyAccessMap().isEmpty());
        fresh.updateRobberyAccess(attacker, "2026-01-01"); fresh.updatePickpocketAccess(attacker, 123L); manager.save(fresh);
        var loaded = manager.load(victim); assertEquals(victim, loaded.getVictimId()); assertEquals("2026-01-01", loaded.getRobberyAccessMap().get(attacker)); assertEquals("123", loaded.getPickpocketAccessMap().get(attacker));
        manager.save(null); manager.save(new PlayerTargetData(null));
        assertEquals(1, Objects.requireNonNull(dir.toFile().list()).length);
    }
    @Test void legacyJsonUsesOldMapOnlyWhenNewRobberyMapIsAbsentOrEmpty() throws Exception {
        UUID victim = UUID.randomUUID(), attacker = UUID.randomUUID(), explicit = UUID.randomUUID();
        Files.writeString(file(victim), "{\"accessMap\":{\""+attacker+"\":\"old\"},\"robberyAccessMap\":null,\"pickpocketAccessMap\":null}");
        assertEquals(Map.of(attacker, "old"), manager.load(victim).getRobberyAccessMap()); assertTrue(manager.load(victim).getPickpocketAccessMap().isEmpty());
        Files.writeString(file(victim), "{\"victimId\":\""+explicit+"\",\"accessMap\":{\""+attacker+"\":\"old\"},\"robberyAccessMap\":{\""+attacker+"\":\"new\"}}");
        var data = manager.load(victim); assertEquals(explicit, data.getVictimId()); assertEquals(Map.of(attacker,"new"),data.getRobberyAccessMap());
        Files.writeString(file(victim), "{\"accessMap\":null}"); assertTrue(manager.load(victim).getRobberyAccessMap().isEmpty());
        Files.writeString(file(victim), "null"); assertEquals(victim, manager.load(victim).getVictimId());
        Files.writeString(file(victim), "{\"accessMap\":{\""+attacker+"\":\"legacy\"}}"); assertEquals("legacy",manager.load(victim).getRobberyAccessMap().get(attacker));
    }
    @Test void removingAttackerClearsBothHistoriesWithoutAffectingOtherPlayers() throws Exception {
        UUID victim = UUID.randomUUID(), attacker = UUID.randomUUID(), other = UUID.randomUUID();
        var data = new PlayerTargetData(victim); data.updateRobberyAccess(attacker,"today"); data.updatePickpocketAccess(attacker,123); data.updateRobberyAccess(other,"yesterday"); data.updatePickpocketAccess(other,456); manager.save(data);
        Files.writeString(dir.resolve("invalid.json"), "{}"); Files.writeString(dir.resolve("notes.txt"), "leave alone");
        manager.removePlayerFromAllAccessMaps(null); manager.removePlayerFromAllAccessMaps(attacker);
        var loaded = manager.load(victim); assertEquals(Map.of(other,"yesterday"), loaded.getRobberyAccessMap()); assertEquals(Map.of(other,"456"), loaded.getPickpocketAccessMap());
        manager.removePlayerFromAllAccessMaps(UUID.randomUUID());
        manager.clearAllAccessMaps(); loaded = manager.load(victim); assertTrue(loaded.getRobberyAccessMap().isEmpty()); assertTrue(loaded.getPickpocketAccessMap().isEmpty());
        assertEquals("leave alone", Files.readString(dir.resolve("notes.txt")));
    }
    @Test void removingAttackerFromOnlyPickpocketHistoryAlsoSaves() {
        UUID victim = UUID.randomUUID(), attacker = UUID.randomUUID(); var data = new PlayerTargetData(victim);
        data.updatePickpocketAccess(attacker,123); manager.save(data); manager.removePlayerFromAllAccessMaps(attacker);
        assertTrue(manager.load(victim).getPickpocketAccessMap().isEmpty());
    }
    @Test void ioErrorsWarnAndReturnFreshState() throws Exception {
        UUID victim = UUID.randomUUID(); Files.createDirectory(file(victim));
        assertEquals(victim, manager.load(victim).getVictimId()); verify(logger).warning(contains("Failed to load"));
        manager.save(new PlayerTargetData(victim)); verify(logger).warning(contains("Failed to save"));
        Files.delete(file(victim)); Files.delete(dir); Files.writeString(dir,"not a directory");
        assertDoesNotThrow(() -> manager.removePlayerFromAllAccessMaps(victim)); assertDoesNotThrow(manager::clearAllAccessMaps);
    }
    @Test void targetDataCompatibilityAccessorsPreserveRobberyAndPickpocketIndependence() {
        UUID id = UUID.randomUUID(), attacker = UUID.randomUUID(); var data = new PlayerTargetData(null); data.setVictimId(id); assertEquals(id,data.getVictimId());
        data.setAccessMap(null); data.setPickpocketAccessMap(null); assertTrue(data.getAccessMap().isEmpty()); assertTrue(data.getPickpocketAccessMap().isEmpty());
        var robbery = new HashMap<UUID,String>(); data.setAccessMap(robbery); data.updateAccess(attacker,"date"); assertSame(robbery,data.getAccessMap()); assertEquals("date",data.getRobberyAccessMap().get(attacker));
        var pick = new HashMap<UUID,String>(); data.setPickpocketAccessMap(pick); data.updatePickpocketAccess(attacker,99); assertSame(pick,data.getPickpocketAccessMap()); assertEquals("99",pick.get(attacker));
    }
}
