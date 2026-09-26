package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class RiskSetServiceTest {
    private MockedStatic<Thievery> thievery;
    private MockedStatic<Database> database;
    private PlayerManager players;
    private final RiskSetService service = new RiskSetService();

    @BeforeEach void setUp() {
        players = mock(PlayerManager.class);
        when(players.getLoadedIds()).thenReturn(List.of());
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getPlayerManager).thenReturn(players);
        database = mockStatic(Database.class);
    }
    @AfterEach void tearDown() { database.close(); thievery.close(); }

    @Test void loadedProfileChangesInMemoryAndRestartsDecayWithoutReloadingOrSaving() {
        UUID id = UUID.randomUUID(); PlayerData data = profile(id);
        when(players.exists(id)).thenReturn(true); when(players.get(id)).thenReturn(data);
        long before = System.currentTimeMillis(); service.setForPlayer(id, 0.4567);
        assertUpdated(data, 0.457, before); assertEquals(List.of("tools"), data.getActiveCategories());
        database.verifyNoInteractions();
    }

    @Test void unloadedProfileIsLoadedUpdatedAndSaved() {
        UUID id = UUID.randomUUID(); PlayerData data = profile(id);
        database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
        database.when(() -> Database.loadPlayerData(id)).thenReturn(data);
        long before = System.currentTimeMillis(); service.setForPlayer(id, 0.25);
        assertUpdated(data, 0.25, before);
        database.verify(() -> Database.savePlayerData(data));
        verify(players, never()).get(id);
    }

    @Test void unknownPlayerGetsANewSavedProfileWithRequestedRiskAndIdentity() {
        UUID id = UUID.randomUUID(); AtomicReference<PlayerData> saved = new AtomicReference<>();
        database.when(() -> Database.savePlayerData(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return null; });
        long before = System.currentTimeMillis(); service.setForPlayer(id, 1);
        assertNotNull(saved.get()); assertEquals(id, saved.get().getId()); assertUpdated(saved.get(), 1, before);
        database.verify(() -> Database.loadPlayerData(id), never());
        verify(players, never()).get(id);
    }

    @Test void allUpdatesLoadedAndStoredProfilesOnceAndSkipsUnrelatedOrMalformedFiles() throws Exception {
        UUID loadedId = UUID.randomUUID(), storedId = UUID.randomUUID(), brokenId = UUID.randomUUID();
        PlayerData loaded = profile(loadedId), stored = profile(storedId);
        when(players.getLoadedIds()).thenReturn(List.of(loadedId));
        when(players.exists(loadedId)).thenReturn(true); when(players.get(loadedId)).thenReturn(loaded);
        database.when(() -> Database.loadPlayerData(storedId)).thenReturn(stored);
        database.when(() -> Database.loadPlayerData(brokenId)).thenThrow(new IllegalArgumentException("Malformed profile"));
        withIsolatedStorage(true, folder -> {
            for (String name : List.of(loadedId + ".json", storedId + ".json", brokenId + ".json", "invalid-id.json", "notes.txt")) {
                Files.writeString(folder.resolve(name), "fixture");
            }
            long before = System.currentTimeMillis(); service.setForAll(0.75);
            assertUpdated(loaded, 0.75, before); assertUpdated(stored, 0.75, before);
            database.verify(() -> Database.loadPlayerData(loadedId), never());
            database.verify(() -> Database.savePlayerData(loaded), never());
            database.verify(() -> Database.savePlayerData(stored));
            assertEquals("fixture", Files.readString(folder.resolve("notes.txt")));
        });
    }

    @Test void allStillUpdatesLoadedProfilesWhenStorageDirectoryIsAbsent() throws Exception {
        UUID id = UUID.randomUUID(); PlayerData data = profile(id);
        when(players.getLoadedIds()).thenReturn(List.of(id)); when(players.get(id)).thenReturn(data);
        withIsolatedStorage(false, folder -> {
            long before = System.currentTimeMillis(); service.setForAll(0);
            assertUpdated(data, 0, before); database.verifyNoInteractions();
        });
    }

    @Test void displayedRiskUsesThreeDecimalPlaces() {
        assertEquals("0.000", RiskSetService.formatRisk(0));
        assertEquals("0.457", RiskSetService.formatRisk(0.4567));
        assertEquals("1.000", RiskSetService.formatRisk(1));
    }

    private PlayerData profile(UUID id) {
        PlayerData data = new PlayerData(id); data.setRisk(0.9); data.setLastRiskDecayMs(1);
        data.setActiveCategories(List.of("tools")); return data;
    }
    private void assertUpdated(PlayerData data, double risk, long before) {
        assertEquals(risk, data.getRisk());
        assertTrue(data.getLastRiskDecayMs() >= before);
        assertTrue(data.getLastRiskDecayMs() <= System.currentTimeMillis());
    }
    private void withIsolatedStorage(boolean create, StorageCheck check) throws Exception {
        assertTrue(Path.of("").toAbsolutePath().endsWith(Path.of("target", "test-runtime")), "Only relocate isolated test fixtures");
        Path folder = Path.of("plugins/Thievery/playerdata").toAbsolutePath();
        Path backup = folder.resolveSibling("risk-test-backup-" + UUID.randomUUID());
        boolean existed = Files.exists(folder);
        if (existed) Files.move(folder, backup);
        try {
            if (create) Files.createDirectories(folder);
            check.run(folder);
        } finally {
            if (Files.exists(folder)) {
                try (var files = Files.list(folder)) {
                    for (Path file : files.toList()) Files.delete(file);
                }
                Files.delete(folder);
            }
            if (existed) Files.move(backup, folder);
        }
    }
    @FunctionalInterface private interface StorageCheck { void run(Path folder) throws Exception; }
}
