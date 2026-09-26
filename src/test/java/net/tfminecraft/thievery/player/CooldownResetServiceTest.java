package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.door.LockPickManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class CooldownResetServiceTest {
    private MockedConstruction<PlayerTargetDataManager> targets;
    private MockedConstruction<ContainerDataManager> containers;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<Database> database;
    private PlayerManager players;
    private LockPickManager picks;
    private CooldownResetService service;

    @BeforeEach
    void setUp() {
        targets = mockConstruction(PlayerTargetDataManager.class);
        containers = mockConstruction(ContainerDataManager.class);
        thievery = mockStatic(Thievery.class);
        database = mockStatic(Database.class);
        players = mock(PlayerManager.class);
        when(players.getLoadedIds()).thenReturn(List.of());
        picks = mock(LockPickManager.class);
        thievery.when(Thievery::getPlayerManager).thenReturn(players);
        service = new CooldownResetService(picks);
    }

    @AfterEach
    void tearDown() {
        database.close();
        thievery.close();
        containers.close();
        targets.close();
    }

    private PlayerData withCooldowns(UUID id) {
        PlayerData data = new PlayerData(id);
        data.recordPaperKeyCooldown("door", 60);
        data.recordCriticalClue("target");
        data.recordClueUsed("clue", "target");
        return data;
    }

    private void assertCleared(PlayerData data) {
        assertTrue(data.getPaperKeyCooldownExpiryByDoorUuid().isEmpty());
        assertTrue(data.getLastCriticalClueAtByTarget().isEmpty());
        assertTrue(data.getRecentClues().isEmpty());
    }

    @Test
    void playerResetClearsEveryAccessAndLoadedPersonalCooldownWithoutReloading() {
        UUID id = UUID.randomUUID();
        PlayerData data = withCooldowns(id);
        when(players.exists(id)).thenReturn(true);
        when(players.get(id)).thenReturn(data);
        service.resetForPlayer(id);
        verify(targets.constructed().getFirst()).removePlayerFromAllAccessMaps(id);
        verify(containers.constructed().getFirst()).removePlayerFromAllAccessMaps(id);
        verify(picks).clearCooldown(id);
        assertCleared(data);
        database.verifyNoInteractions();
    }

    @Test
    void playerResetLoadsClearsAndSavesAnUnloadedProfile() {
        UUID id = UUID.randomUUID();
        PlayerData data = withCooldowns(id);
        database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
        database.when(() -> Database.loadPlayerData(id)).thenReturn(data);
        service.resetForPlayer(id);
        assertCleared(data);
        database.verify(() -> Database.savePlayerData(data));
    }

    @Test
    void playerResetDoesNotCreateAnUnknownProfile() {
        UUID id = UUID.randomUUID();
        service.resetForPlayer(id);
        verify(picks).clearCooldown(id);
        database.verify(() -> Database.hasPlayerData(id));
        database.verify(() -> Database.loadPlayerData(id), never());
        database.verify(() -> Database.savePlayerData(any()), never());
    }

    @Test
    void resetAllClearsLoadedAndStoredProfilesAndIgnoresUnrelatedFiles() throws Exception {
        UUID loadedId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        PlayerData loaded = withCooldowns(loadedId);
        PlayerData stored = withCooldowns(storedId);
        when(players.getLoadedIds()).thenReturn(List.of(loadedId));
        when(players.exists(loadedId)).thenReturn(true);
        when(players.get(loadedId)).thenReturn(loaded);
        database.when(() -> Database.loadPlayerData(storedId)).thenReturn(stored);
        Path folder = Path.of("plugins/Thievery/playerdata");
        Files.createDirectories(folder);
        Path loadedFile = folder.resolve(loadedId + ".json");
        Path storedFile = folder.resolve(storedId + ".json");
        Path invalid = folder.resolve("invalid-" + UUID.randomUUID() + ".json");
        Path notes = folder.resolve(UUID.randomUUID() + ".txt");
        List<Path> files = List.of(loadedFile, storedFile, invalid, notes);
        try {
            for (Path file : files) Files.writeString(file, "fixture");
            service.resetAll();
            assertCleared(loaded);
            assertCleared(stored);
            verify(targets.constructed().getFirst()).clearAllAccessMaps();
            verify(containers.constructed().getFirst()).clearAllAccessMaps();
            verify(picks).clearAllCooldowns();
            database.verify(() -> Database.loadPlayerData(loadedId), never());
            database.verify(() -> Database.savePlayerData(stored));
            database.verify(() -> Database.savePlayerData(loaded), never());
            assertEquals("fixture", Files.readString(notes));
        } finally {
            for (Path file : files) Files.deleteIfExists(file);
        }
    }

    @Test
    void resetAllStillClearsLoadedProfilesWhenStorageDirectoryDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerData data = withCooldowns(id);
        when(players.getLoadedIds()).thenReturn(List.of(id));
        when(players.get(id)).thenReturn(data);
        Path folder = Path.of("plugins/Thievery/playerdata").toAbsolutePath();
        assertTrue(Path.of("").toAbsolutePath().endsWith(Path.of("target", "test-runtime")),
                "Only relocate isolated test fixtures");
        Path backup = folder.resolveSibling("playerdata-backup-" + UUID.randomUUID());
        boolean existed = Files.exists(folder);
        if (existed) Files.move(folder, backup);
        try {
            service.resetAll();
            assertCleared(data);
            verify(picks).clearAllCooldowns();
            database.verifyNoInteractions();
        } finally {
            if (existed) Files.move(backup, folder);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void targetResolutionHandlesAllKnownNamesAndMissingDisplayNames() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertEquals("all players", CooldownResetService.resolveTargetName("ALL"));
            assertNull(CooldownResetService.resolveTargetId("All"));
            bukkit.verifyNoInteractions();
            UUID id = UUID.randomUUID();
            OfflinePlayer offline = mock(OfflinePlayer.class);
            when(offline.getUniqueId()).thenReturn(id);
            when(offline.getName()).thenReturn("CanonicalName");
            bukkit.when(() -> Bukkit.getOfflinePlayer("input")).thenReturn(offline);
            assertEquals("CanonicalName", CooldownResetService.resolveTargetName("input"));
            assertEquals(id, CooldownResetService.resolveTargetId("input"));
            when(offline.getName()).thenReturn(null);
            assertEquals("input", CooldownResetService.resolveTargetName("input"));
        }
    }
}
