package net.tfminecraft.thievery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.coreprotect.CoreProtect;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.command.CommandManager;
import net.tfminecraft.thievery.command.PickpocketCommand;
import net.tfminecraft.thievery.command.RobberyCommand;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.emote.EmoteSkinListener;
import net.tfminecraft.thievery.door.ContainerManager;
import net.tfminecraft.thievery.door.DoorManager;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.loader.ConfigLoader;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.player.TraitChecker;
import net.tfminecraft.thievery.robbery.RobberyCharacterListener;
import net.tfminecraft.thievery.robbery.RobberyManager;
import net.tfminecraft.thievery.steal.FurnitureDisplayListener;
import net.tfminecraft.thievery.steal.GraveStealListener;
import net.tfminecraft.thievery.steal.StealManager;
import net.tfminecraft.thievery.steal.StealGuiUpdater;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class ThieveryTest {
    private ServerMock server;
    private Thievery plugin;
    private MockedConstruction<ConfigLoader> configs;
    private MockedConstruction<CategoryLoader> categories;
    private MockedConstruction<ContainerManager> containers;
    private MockedStatic<Database> database;
    private MockedStatic<ThieveryBridge> crafting;
    private boolean previousCoreProtect;
    private boolean previousFurniture;
    private Object previousPlugin;
    private Object previousStealManager;
    private Map<UUID, PlayerData> playerMap;
    private Map<UUID, PlayerData> previousPlayers;

    private Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        previousPlugin = field(Thievery.class, "instance").get(null);
        previousStealManager = field(StealManager.class, "instance").get(null);
        playerMap = (Map<UUID, PlayerData>) field(PlayerManager.class, "data").get(Thievery.getPlayerManager());
        previousPlayers = new HashMap<>(playerMap);
        previousCoreProtect = Cache.coreProtect;
        previousFurniture = Cache.interactibleFurniture;
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin("MMOCore");
        MockBukkit.createMockPlugin("TLibs");
        MockBukkit.createMockPlugin("RPCharacters");
        configs = mockConstruction(ConfigLoader.class);
        categories = mockConstruction(CategoryLoader.class);
        containers = mockConstruction(ContainerManager.class);
        database = mockStatic(Database.class);
        crafting = mockStatic(ThieveryBridge.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            crafting.close();
            database.close();
            containers.close();
            categories.close();
            configs.close();
            playerMap.clear();
            playerMap.putAll(previousPlayers);
            field(Thievery.class, "instance").set(null, previousPlugin);
            field(StealManager.class, "instance").set(null, previousStealManager);
            Cache.coreProtect = previousCoreProtect;
            Cache.interactibleFurniture = previousFurniture;
        }
    }

    private Thievery load() {
        plugin = MockBukkit.load(Thievery.class);
        return plugin;
    }

    private List<Integer> pluginTasks() throws Exception {
        // MockBukkit cannot enumerate pending tasks; observe the service handles and query their IDs.
        Object updater = field(Thievery.class, "stealGuiUpdater").get(plugin);
        BukkitTask guiTask = (BukkitTask) field(StealGuiUpdater.class, "task").get(updater);
        BukkitRunnable pointTask = (BukkitRunnable) field(PlayerManager.class, "pointTask").get(Thievery.getPlayerManager());
        List<Integer> ids = new ArrayList<>();
        if (guiTask != null) ids.add(guiTask.getTaskId());
        if (pointTask != null) ids.add(pointTask.getTaskId());
        for (int id : ids) assertTrue(server.getScheduler().isQueued(id));
        return ids;
    }

    @Test
    void oldTLibsKeepsProtectionRunningAndWarnsThatTraitActionsAreDenied() {
        List<String> warnings = new ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) warnings.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Logger.getLogger("").addHandler(capture);
        try (var unavailable = mockStatic(net.tfminecraft.tlibs.character.Characters.class)) {
            unavailable.when(net.tfminecraft.tlibs.character.Characters::available)
                    .thenThrow(new NoClassDefFoundError("CharacterIdentity"));
            load();
            assertTrue(plugin.isEnabled());
            assertFalse(TraitChecker.characterApiAvailable());
            assertTrue(warnings.stream().anyMatch(message -> message.contains("older than 2.3.0")));
            Set<Class<?>> listeners = new HashSet<>();
            HandlerList.getRegisteredListeners(plugin).forEach(listener -> listeners.add(listener.getListener().getClass()));
            assertTrue(listeners.contains(DoorManager.class));
            assertTrue(listeners.contains(RobberyManager.class));
            assertFalse(listeners.contains(RobberyCharacterListener.class));
            assertEquals(1, containers.constructed().size());
        } finally {
            java.util.logging.Logger.getLogger("").removeHandler(capture);
            TraitChecker.detectCharacterApi();
        }
    }

    @Test
    void locksStayInGuardModeWithTheirListenersRegisteredWhenHeirsAreNotReadyAtEnable() {
        // MockBukkit has no RPCharacters provider, so heirs are never ready here.
        load();
        assertTrue(net.tfminecraft.thievery.owner.LockGuard.guarded());
        assertEquals("restart required", net.tfminecraft.thievery.owner.LockGuard.reason());
        assertTrue(HandlerList.getRegisteredListeners(plugin).stream()
                .anyMatch(listener -> listener.getListener() instanceof DoorManager));
        assertFalse(new File(plugin.getDataFolder(), "migrations").exists());
        assertFalse(HandlerList.getRegisteredListeners(plugin).stream()
                .anyMatch(listener -> listener.getListener() instanceof net.tfminecraft.thievery.estate.LockBlocks),
                "no lock kinds are learnt or saved while the index is empty");
        plugin.onDisable();
        assertFalse(new File(plugin.getDataFolder(), "owned-lock-kinds.json").exists());
    }

    @Test
    void locksMigrateAndLeaveGuardModeWhenHeirsAreReadyAtEnable() throws Exception {
        try (var identity = new net.tfminecraft.thievery.testsupport.FactionFixture()) {
            load();
            assertFalse(net.tfminecraft.thievery.owner.LockGuard.guarded());
            assertTrue(new File(plugin.getDataFolder(), "migrations/quarantine.json").exists());
            // With the locks loaded, Thievery joins estates as a participant that settles at restart.
            var participants = server.getServicesManager()
                    .getRegistrations(net.tfminecraft.tlibs.character.estate.EstateParticipant.class);
            assertEquals(1, participants.size());
            assertEquals("thievery", participants.getFirst().getProvider().id());
            assertSame(plugin, participants.getFirst().getPlugin());
            // Chunk loads teach the index block kinds, which are saved on disable.
            assertTrue(HandlerList.getRegisteredListeners(plugin).stream()
                    .anyMatch(listener -> listener.getListener() instanceof net.tfminecraft.thievery.estate.LockBlocks));
            java.nio.file.Path chest = java.nio.file.Path.of("plugins/Thievery/data/0_0/1_64_1.json");
            net.tfminecraft.thievery.owner.OwnedLocks.put(net.tfminecraft.thievery.owner.LockStores.Store.CONTAINERS,
                    chest, new net.tfminecraft.thievery.owner.OwnerCodec.Stored(UUID.randomUUID(),
                            net.tfminecraft.thievery.owner.LockOwner.Kind.CHARACTER), null, null);
            var lock = net.tfminecraft.thievery.owner.OwnedLocks.inChunk(0, 0).getFirst();
            lock = net.tfminecraft.thievery.owner.OwnedLocks.seen(lock, "world", "Chest", null);
            File kinds = new File(plugin.getDataFolder(), "owned-lock-kinds.json");
            server.getScheduler().performTicks(1200);
            server.getScheduler().waitAsyncTasksFinished();
            assertTrue(kinds.exists(), "the minute's save runs in the background");
            net.tfminecraft.thievery.owner.OwnedLocks.seen(lock, "world", "Barrel", null);
            plugin.onDisable();
            assertTrue(Files.readString(kinds.toPath()).contains("Barrel"), "disable saves at once");
        }
    }

    @Test
    void lockWritesRunOnTheWriterThreadWhileEnabledAndFailureCleanupComesBackToTheMainThread() throws Exception {
        load();
        var disk = net.tfminecraft.thievery.owner.HeldLockDisk.install();
        disk.release();
        try {
            java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("writer-check.json");
            net.tfminecraft.thievery.owner.LockFileWriter.write(file, new byte[] {1}, false, "Failed: ", null);
            net.tfminecraft.thievery.owner.LockFileWriter.flush();
            assertEquals("Thievery lock writer", disk.threads.getLast());

            // A failed write logs on the writer thread and hands its cleanup to the main thread.
            List<String> ran = new ArrayList<>();
            java.nio.file.Path failing = plugin.getDataFolder().toPath().resolve("writer-fail.json");
            disk.fail("writer-fail.json");
            net.tfminecraft.thievery.owner.LockFileWriter.write(failing, new byte[] {1}, true, "Failed: ",
                    () -> ran.add("cleanup on " + Thread.currentThread().getName()));
            net.tfminecraft.thievery.owner.LockFileWriter.flush();
            assertTrue(ran.isEmpty(), "the cleanup waits for the main thread");
            server.getScheduler().performOneTick();
            assertEquals(List.of("cleanup on " + Thread.currentThread().getName()), ran);
            assertTrue(disk.done.contains("failed write writer-fail.json \u0001 true"), disk.done.toString());
            ran.clear();

            plugin.onMainThread(() -> ran.add("enabled"));
            assertTrue(ran.isEmpty(), "scheduled, not run on the calling thread");
            server.getScheduler().performOneTick();
            assertEquals(List.of("enabled"), ran);

            server.getPluginManager().disablePlugin(plugin);
            plugin.onMainThread(() -> ran.add("disabled"));
            server.getScheduler().performOneTick();
            assertEquals(List.of("enabled"), ran, "nothing is scheduled once disabled");
            net.tfminecraft.thievery.owner.LockFileWriter.write(file, new byte[] {2}, false, "Failed: ", null);
            assertEquals(Thread.currentThread().getName(), disk.threads.getLast(), "disable stopped the writer");
        } finally {
            net.tfminecraft.thievery.owner.HeldLockDisk.uninstall(disk);
        }
    }

    @Test
    void disableDrainsTheLockWriterEvenWhenAnEarlierStepFails() throws Exception {
        load();
        var disk = net.tfminecraft.thievery.owner.HeldLockDisk.install();
        disk.release();
        Field minigames = field(Thievery.class, "lockMinigameManager");
        Object real = minigames.get(plugin);
        var failing = mock(net.tfminecraft.thievery.door.LockMinigameManager.class);
        doThrow(new IllegalStateException("cancel failed")).when(failing).cancelAll();
        minigames.set(plugin, failing);
        try {
            java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("disable-check.json");
            net.tfminecraft.thievery.owner.LockFileWriter.write(file, new byte[] {1}, false, "Failed: ", null);
            assertThrows(IllegalStateException.class, plugin::onDisable);
            assertTrue(Files.exists(file), "queued before disable, on disk after it");
            net.tfminecraft.thievery.owner.LockFileWriter.write(file, new byte[] {2}, false, "Failed: ", null);
            assertEquals(Thread.currentThread().getName(), disk.threads.getLast(), "the writer was stopped");
        } finally {
            minigames.set(plugin, real);
            net.tfminecraft.thievery.owner.HeldLockDisk.uninstall(disk);
        }
    }

    @Test
    void configurationFailureCanDisableBeforeServicesAreCreated() throws Exception {
        configs.close();
        configs = mockConstruction(ConfigLoader.class, (loader, context) ->
                doThrow(new IllegalStateException("configuration unavailable"))
                        .when(loader).loadConfig(any(File.class)));
        assertThrows(RuntimeException.class, this::load);
        plugin = Thievery.getInstance();
        assertNotNull(plugin);
        assertNull(field(Thievery.class, "stealGuiUpdater").get(plugin));
        assertDoesNotThrow(plugin::onDisable);
        assertTrue(containers.constructed().isEmpty());
    }

    @Test
    void enablingCopiesConfigRegistersCommandsListenersAndStartsServices() throws Exception {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        PlayerMock ordinary = server.addPlayer();
        load();
        assertSame(plugin, Thievery.getInstance());
        assertSame(containers.constructed().getFirst(), plugin.getContainerManager());
        assertNotNull(plugin.getLockPickManager());
        assertNotNull(plugin.getLockMinigameManager());
        assertTrue(Files.readString(new File(plugin.getDataFolder(), "config.yml").toPath()).contains("category_points"));
        assertTrue(new File(plugin.getDataFolder(), "categories.yml").isFile());
        verify(configs.constructed().getFirst()).loadConfig(new File(plugin.getDataFolder(), "config.yml"));
        verify(categories.constructed().getFirst()).load(new File(plugin.getDataFolder(), "categories.yml"));
        assertEquals("/<command>", plugin.getCommand("thievery").getUsage());
        assertInstanceOf(CommandManager.class, plugin.getCommand("thievery").getExecutor());
        assertSame(plugin.getCommand("thievery").getExecutor(), plugin.getCommand("thievery").getTabCompleter());
        assertInstanceOf(RobberyCommand.class, plugin.getCommand("robbery").getExecutor());
        assertInstanceOf(RobberyCommand.class, plugin.getCommand("robbery").getTabCompleter());
        assertInstanceOf(PickpocketCommand.class, plugin.getCommand("pickpocket").getExecutor());
        assertInstanceOf(PickpocketCommand.class, plugin.getCommand("pickpocket").getTabCompleter());
        assertNull(plugin.getCommand("handsup"));
        Set<Class<?>> listeners = new HashSet<>();
        HandlerList.getRegisteredListeners(plugin).forEach(listener -> listeners.add(listener.getListener().getClass()));
        assertTrue(listeners.contains(GraveStealListener.class));
        assertTrue(listeners.contains(DoorManager.class));
        assertTrue(listeners.contains(PlayerManager.class));
        assertTrue(listeners.contains(RobberyCharacterListener.class));
        assertFalse(listeners.contains(FurnitureDisplayListener.class));
        assertFalse(listeners.contains(EmoteSkinListener.class));
        assertEquals(2, pluginTasks().size());
        assertTrue(Thievery.getPlayerManager().exists(admin));
        assertTrue(Thievery.getPlayerManager().exists(ordinary));
        verify(plugin.getContainerManager()).enableFeedback(admin);
        verify(plugin.getContainerManager(), never()).enableFeedback(ordinary);
    }

    @Test
    void itemsAdderRegistersTheEmoteSkinListener() {
        MockBukkit.createMockPlugin("ItemsAdder");
        load();
        assertTrue(HandlerList.getRegisteredListeners(plugin).stream()
                .anyMatch(listener -> listener.getListener() instanceof EmoteSkinListener));
    }

    @Test
    void configCreationPreservesExistingUserFilesAndReloadPassesBothPathsToLoaders() throws Exception {
        load();
        File config = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(config.toPath(), "custom: preserve-me\n");
        plugin.createConfigs();
        assertEquals("custom: preserve-me\n", Files.readString(config.toPath()));
        File categoryFile = new File(plugin.getDataFolder(), "categories.yml");
        Files.delete(categoryFile.toPath());
        plugin.createConfigs();
        assertTrue(categoryFile.isFile());
        plugin.loadConfigs();
        verify(configs.constructed().getFirst(), times(2)).loadConfig(config);
        verify(categories.constructed().getFirst(), times(2)).load(categoryFile);
    }

    @Test
    void reloadPersistsLoadedPlayersRefreshesConfigurationAndKeepsOneTimerPerService() throws Exception {
        PlayerMock online = server.addPlayer();
        load();
        PlayerData original = Thievery.getPlayerManager().get(online);
        original.setRisk(0.4);
        List<Integer> previousTasks = pluginTasks();
        plugin.reload();
        List<Integer> currentTasks = pluginTasks();
        assertEquals(1, previousTasks.stream().filter(currentTasks::contains).count());
        for (int old : previousTasks) {
            if (!currentTasks.contains(old)) assertFalse(server.getScheduler().isQueued(old));
        }
        database.verify(() -> Database.savePlayerData(original));
        assertNotSame(original, Thievery.getPlayerManager().get(online));
        verify(configs.constructed().getFirst(), times(2)).loadConfig(any(File.class));
        verify(categories.constructed().getFirst(), times(2)).load(any(File.class));
        assertEquals(2, pluginTasks().size());
        Player sender = mock(Player.class);
        plugin.reloadMessage(sender);
        var ordered = inOrder(sender);
        ordered.verify(sender).sendMessage(contains("Reloading plugin..."));
        ordered.verify(sender).sendMessage(contains("Reloading complete!"));
        assertEquals(2, pluginTasks().size());
    }

    @Test
    void loadPlayersRefreshesOnlineProfilesAndDisablePersistsThemAndCancelsTasks() throws Exception {
        PlayerMock online = server.addPlayer();
        load();
        UUID id = online.getUniqueId();
        PlayerData loaded = new PlayerData(id);
        loaded.setRisk(0.7);
        database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
        database.when(() -> Database.loadPlayerData(id)).thenReturn(loaded);
        plugin.loadPlayers();
        assertSame(loaded, Thievery.getPlayerManager().get(id));
        assertEquals(0.7, Thievery.getPlayerManager().get(id).getRisk());
        List<Integer> running = pluginTasks();
        plugin.onDisable();
        for (int task : running) assertFalse(server.getScheduler().isQueued(task));
        database.verify(() -> Database.savePlayerData(loaded));
        assertFalse(Thievery.getPlayerManager().getLoadedIds().iterator().hasNext());
        assertTrue(pluginTasks().isEmpty());
        assertDoesNotThrow(plugin::onDisable);
    }

    @Test
    void furnitureIntegrationRegistersItsListenerOnlyWhenAvailableAtStartup() {
        MockBukkit.createMockPlugin("InteractibleFurniture");
        crafting.when(ThieveryBridge::isPluginReady).thenReturn(true);
        load();
        assertTrue(Cache.interactibleFurniture);
        assertTrue(HandlerList.getRegisteredListeners(plugin).stream()
                .anyMatch(listener -> listener.getListener() instanceof FurnitureDisplayListener));
        server.getPluginManager().disablePlugin(server.getPluginManager().getPlugin("InteractibleFurniture"));
        plugin.setPlugins();
        assertFalse(Cache.interactibleFurniture);
    }

    @Test
    void unrelatedPluginUsingCoreProtectNameDoesNotEnableIntegration() {
        MockBukkit.createMockPlugin("CoreProtect");
        load();
        assertFalse(Cache.coreProtect);
        assertNull(Thievery.getCoreProtect());
    }

    @Test
    void coreProtectIntegrationRequiresEnabledPluginAndSupportedEnabledApi() {
        CoreProtect coreProtect = mock(CoreProtect.class);
        when(coreProtect.getName()).thenReturn("CoreProtect");
        when(coreProtect.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(coreProtect.getDescription()).thenReturn(new PluginDescriptionFile("CoreProtect", "test", CoreProtect.class.getName()));
        when(coreProtect.isEnabled()).thenReturn(true);
        CoreProtectAPI api = mock(CoreProtectAPI.class);
        when(coreProtect.getAPI()).thenReturn(api);
        server.getPluginManager().registerLoadedPlugin(coreProtect);
        load();
        assertTrue(Cache.coreProtect);
        assertNull(Thievery.getCoreProtect());
        when(api.isEnabled()).thenReturn(true);
        when(api.APIVersion()).thenReturn(9);
        assertNull(Thievery.getCoreProtect());
        when(api.APIVersion()).thenReturn(10);
        assertSame(api, Thievery.getCoreProtect());
        when(coreProtect.isEnabled()).thenReturn(false);
        plugin.setPlugins();
        assertFalse(Cache.coreProtect);
    }

    @Test
    void absentIntegrationsResetPreviouslyEnabledFlags() {
        Cache.coreProtect = true;
        Cache.interactibleFurniture = true;
        load();
        assertFalse(Cache.coreProtect);
        assertFalse(Cache.interactibleFurniture);
        assertNull(Thievery.getCoreProtect());
    }
}
