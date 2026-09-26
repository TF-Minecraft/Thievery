package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;

import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.steal.*;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class PickpocketManagerTest {
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedConstruction<PlayerTargetDataManager> targets;
    private MockedConstruction<PickpocketReference> references;
    private final List<PickpocketSession> sessions = new ArrayList<>();
    private final List<Runnable> endCallbacks = new ArrayList<>();
    private MockedStatic<PlayerInteractCooldown> interactions;
    private MockedStatic<GuildAccessCooldown> guilds;
    private MockedStatic<RobberyUtil> range;
    private MockedStatic<EvilRpPlays> evil;
    private Player pickpocket;
    private Player victim;
    private PlayerTargetData data;
    private StealManager steals;
    private Inventory gui;
    private PickpocketManager manager;

    private <T> MockedStatic<T> statics(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    @BeforeEach
    void setUp() {
        pickpocket = player("thief");
        victim = player("victim");
        data = new PlayerTargetData(victim.getUniqueId());
        steals = mock(StealManager.class);
        gui = mock(Inventory.class);
        targets = mockConstruction(PlayerTargetDataManager.class, (mock, context) -> when(mock.load(victim.getUniqueId())).thenReturn(data));
        references = mockConstruction(PickpocketReference.class, (mock, context) -> {
            sessions.add((PickpocketSession) context.arguments().get(0));
            endCallbacks.add((Runnable) context.arguments().get(1));
            when(mock.buildTitle(pickpocket)).thenReturn("Pickpocket preview");
        });
        statics(StealManager.class).when(StealManager::getInstance).thenReturn(steals);
        statics(StealGui.class).when(() -> StealGui.buildHiddenGui(any(), any(), eq("Pickpocket preview"))).thenReturn(gui);
        MockedStatic<PickpocketLoader> config = statics(PickpocketLoader.class);
        config.when(PickpocketLoader::getMaxDistance).thenReturn(4.0);
        config.when(PickpocketLoader::getBudget).thenReturn(20.0);
        config.when(PickpocketLoader::getCooldownMillis).thenReturn(60_000L);
        interactions = statics(PlayerInteractCooldown.class);
        interactions.when(() -> PlayerInteractCooldown.tryAcquire(pickpocket.getUniqueId())).thenReturn(true);
        range = statics(RobberyUtil.class);
        range.when(() -> RobberyUtil.isWithinRange(pickpocket, victim, 4)).thenReturn(true);
        guilds = statics(GuildAccessCooldown.class);
        evil = statics(EvilRpPlays.class);
        String targetKey = victim.getName();
        statics(TargetKeyResolver.class).when(() -> TargetKeyResolver.resolve(victim.getUniqueId())).thenReturn(targetKey);
        manager = new PickpocketManager();
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        references.close();
        targets.close();
    }

    private void click(Entity target) {
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(pickpocket);
        when(event.getRightClicked()).thenReturn(target);
        manager.onPlayerInteractEntity(event);
    }

    @Test
    void targetSelectionEndsPreviousSessionAndIgnoresUnarmedOrNonPlayerInteractions() {
        click(victim);
        verifyNoInteractions(targets.constructed().getFirst());
        assertFalse(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        manager.startAwaitingTarget(pickpocket);
        assertTrue(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        verify(steals).endSession(pickpocket.getUniqueId(), false);
        verify(pickpocket).sendMessage(contains("within 4.0 blocks"));
        click(mock(Entity.class));
        interactions.verifyNoInteractions();
        assertTrue(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        verifyNoInteractions(targets.constructed().getFirst());
    }

    @Test
    void interactionCooldownSelfTargetAndOutOfRangeKeepSelectionPending() {
        manager.startAwaitingTarget(pickpocket);
        interactions.when(() -> PlayerInteractCooldown.tryAcquire(pickpocket.getUniqueId())).thenReturn(false);
        click(victim);
        verifyNoInteractions(targets.constructed().getFirst());
        interactions.when(() -> PlayerInteractCooldown.tryAcquire(pickpocket.getUniqueId())).thenReturn(true);
        click(pickpocket);
        verify(pickpocket).sendMessage(contains("cannot pickpocket yourself"));
        range.when(() -> RobberyUtil.isWithinRange(pickpocket, victim, 4)).thenReturn(false);
        click(victim);
        verify(pickpocket).sendMessage(contains("too far away"));
        assertTrue(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        assertTrue(references.constructed().isEmpty());
    }

    @Test
    void guildCooldownExplainsWaitWithoutRecordingOrOpeningSession() {
        manager.startAwaitingTarget(pickpocket);
        guilds.when(() -> GuildAccessCooldown.isOnCooldownMillis(data.getPickpocketAccessMap(), pickpocket, 60_000)).thenReturn(true);
        guilds.when(() -> GuildAccessCooldown.getMillisRemainingMillis(data.getPickpocketAccessMap(), pickpocket, 60_000)).thenReturn(45_000L);
        guilds.when(() -> GuildAccessCooldown.formatRemaining(45_000)).thenReturn("45 seconds");
        click(victim);
        verify(pickpocket).sendMessage(contains("wait 45 seconds"));
        verify(targets.constructed().getFirst(), never()).save(any());
        assertTrue(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        assertTrue(references.constructed().isEmpty());
    }

    @Test
    void validTargetRecordsCooldownAndOpensBoundedHiddenSessionExactlyOnce() {
        manager.startAwaitingTarget(pickpocket);
        click(victim);
        assertFalse(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        assertEquals(1, sessions.size());
        PickpocketSession session = sessions.getFirst();
        assertEquals(pickpocket.getUniqueId(), session.getPickpocketId());
        assertEquals(victim.getUniqueId(), session.getVictimId());
        assertEquals(20, session.getBudget().getCapacity());
        assertEquals(PlayerSlotMap.MAIN_INV_SLOT_COUNT, session.getLayout().getLogicalSlotCount());
        UUID pickpocketId = pickpocket.getUniqueId();
        guilds.verify(() -> GuildAccessCooldown.recordAccessMillis(eq(data.getPickpocketAccessMap()), eq(pickpocketId), anyLong()));
        verify(targets.constructed().getFirst()).save(data);
        evil.verify(() -> EvilRpPlays.record(pickpocket));
        verify(steals).openSession(pickpocket, references.constructed().getFirst(), gui);
        click(victim);
        assertEquals(1, references.constructed().size());
        endCallbacks.getFirst().run();
        manager.startAwaitingTarget(pickpocket);
        click(victim);
        assertEquals(2, references.constructed().size());
    }

    @Test
    void quitClearsSelectionInteractionCooldownAndActiveSession() {
        manager.startAwaitingTarget(pickpocket);
        click(victim);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(pickpocket);
        clearInvocations(steals);
        manager.onPlayerQuit(event);
        assertFalse(manager.isAwaitingTarget(pickpocket.getUniqueId()));
        interactions.verify(() -> PlayerInteractCooldown.clear(pickpocket.getUniqueId()));
        verify(steals).endSession(pickpocket.getUniqueId(), false);
        manager.startAwaitingTarget(pickpocket);
        manager.onPlayerQuit(event);
        assertFalse(manager.isAwaitingTarget(pickpocket.getUniqueId()));
    }
}
