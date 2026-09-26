package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.DoorLockpick.ProximityAnchor;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class LockPickManagerTest {
    private ServerMock server;
    private LockPickManager manager;
    private Player player;
    private UUID id;
    private ProximityAnchor anchor;
    private PlayerData data;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<EvilRpPlays> plays;
    private int barLength, maxSuccess, minBreak, maxBreak;
    private double speed, minimumSpeed, jitter, flip, dexReduction;
    private long cooldown;
    private final AtomicReference<String> lastBar = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        plays = mockStatic(EvilRpPlays.class);
        player = mock(Player.class);
        id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        doAnswer(invocation -> { lastBar.set(invocation.getArgument(1)); return null; })
                .when(player).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        anchor = mock(ProximityAnchor.class);
        when(anchor.isInRange(player)).thenReturn(true);
        data = mock(PlayerData.class);
        PlayerManager playerManager = mock(PlayerManager.class);
        when(playerManager.get(id)).thenReturn(data);
        plugin.when(Thievery::getPlayerManager).thenReturn(playerManager);
        manager = new LockPickManager();
        barLength = Parameters.barLength; maxSuccess = Parameters.maxSuccessSlots;
        minBreak = Parameters.minBreakSlots; maxBreak = Parameters.maxBreakSlots;
        speed = Parameters.baseBarSpeed; minimumSpeed = Parameters.minBarSpeed;
        jitter = Parameters.speedJitterFraction; flip = Parameters.randomFlipChance;
        dexReduction = Parameters.dexSpeedReductionPerLevel; cooldown = Parameters.lockpickFailCooldownMs;
        Parameters.barLength = 2; Parameters.maxSuccessSlots = 1;
        Parameters.minBreakSlots = 0; Parameters.maxBreakSlots = 0;
        Parameters.baseBarSpeed = 1; Parameters.minBarSpeed = 1;
        Parameters.speedJitterFraction = 0; Parameters.randomFlipChance = 0;
        Parameters.dexSpeedReductionPerLevel = 0; Parameters.lockpickFailCooldownMs = 60_000;
    }

    @AfterEach
    void tearDown() {
        manager.cancelSession(id);
        Parameters.barLength = barLength; Parameters.maxSuccessSlots = maxSuccess;
        Parameters.minBreakSlots = minBreak; Parameters.maxBreakSlots = maxBreak;
        Parameters.baseBarSpeed = speed; Parameters.minBarSpeed = minimumSpeed;
        Parameters.speedJitterFraction = jitter; Parameters.randomFlipChance = flip;
        Parameters.dexSpeedReductionPerLevel = dexReduction; Parameters.lockpickFailCooldownMs = cooldown;
        plays.close(); plugin.close(); MockBukkit.unmock();
    }

    @Test
    void sessionStartsOnSuccessAndSelectionEndsItWithoutPenalty() {
        assertEquals(LockPickManager.SelectResult.NOT_IN_SESSION, manager.handleSelect(player));
        assertNull(manager.getSessionKind(id));
        assertNull(manager.getSessionTargetId(id));
        start("target");
        assertTrue(manager.isInSession(id));
        assertEquals(LockPickManager.SessionKind.DISPLAY, manager.getSessionKind(id));
        assertEquals("target", manager.getSessionTargetId(id));
        plays.verify(() -> EvilRpPlays.record(player));
        assertEquals(LockPickManager.SelectResult.SUCCESS, manager.handleSelect(player));
        assertFalse(manager.isInSession(id));
        assertFalse(manager.isOnCooldown(id, "target"));
        server.getScheduler().performTicks(2);
        verifyNoInteractions(data);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectingNonSuccessSlotAppliesTargetSpecificPenalty(boolean breakSlot) {
        Parameters.minBreakSlots = breakSlot ? 1 : 0;
        Parameters.maxBreakSlots = breakSlot ? 1 : 0;
        start("target");
        server.getScheduler().performOneTick();
        // Two slots contain exactly one success. If it is hidden by the cursor,
        // advance once to the other slot; otherwise the cursor already marks failure.
        if (!lastBar.get().contains("§a-")) server.getScheduler().performOneTick();
        assertTrue(lastBar.get().contains("§a-"));
        assertEquals(breakSlot ? LockPickManager.SelectResult.BREAK : LockPickManager.SelectResult.FAIL,
                manager.handleSelect(player));
        assertTrue(manager.isOnCooldown(id, "target"));
        assertFalse(manager.isOnCooldown(id, "other"));
        assertTrue(manager.getDebuffFactor(id, "target") > 0.9);
        assertTrue(manager.getCooldownRemainingSeconds(id, "target") >= 58);
        verify(data, atLeastOnce()).applyRiskDecay(10);
    }

    @Test
    void leavingRangeCancelsTaskPenalizesAndNotifiesAnchorAndCaller() {
        Runnable callback = mock(Runnable.class);
        manager.startSession(player, anchor, LockPickManager.SessionKind.DISPLAY, "target", 0.5, 10, 0.5, callback);
        when(anchor.isInRange(player)).thenReturn(false);
        server.getScheduler().performOneTick();
        assertFalse(manager.isInSession(id));
        assertTrue(manager.isOnCooldown(id, "target"));
        verify(anchor).onOutOfRange(player);
        verify(callback).run();
        server.getScheduler().performTicks(2);
        verifyNoMoreInteractions(callback);
    }

    @Test
    void cancellingAnOnlinePlayersAttemptClearsTheBarAndStopsFurtherTitles() {
        start("target");
        server.getScheduler().performOneTick();
        assertFalse(lastBar.get().isEmpty());
        clearInvocations(player);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(id)).thenReturn(player);
            manager.cancelSession(id, false);
            server.getScheduler().performTicks(5);
            verify(player).sendTitle("", "", 0, 1, 0);
            verifyNoMoreInteractions(player);
            assertFalse(manager.isInSession(id));
            assertFalse(manager.isOnCooldown(id, "target"));
        }
    }

    @Test
    void disconnectedPlayerCancelsWithoutPenaltyOrProximityNotification() {
        start("target");
        when(player.isOnline()).thenReturn(false);
        server.getScheduler().performOneTick();
        assertFalse(manager.isInSession(id));
        assertFalse(manager.isOnCooldown(id, "target"));
        verify(anchor, never()).onOutOfRange(any());
    }

    @Test
    void replacingSessionPenalizesOldTargetAndExplicitCancelCanRemainUnpenalized() {
        start("first");
        start("second");
        assertEquals("second", manager.getSessionTargetId(id));
        assertTrue(manager.isOnCooldown(id, "first"));
        manager.cancelSession(id);
        assertFalse(manager.isOnCooldown(id, "second"));
        manager.cancelSession(id, true);
        assertFalse(manager.isOnCooldown(id, "second"));
        manager.clearCooldown(null);
        manager.clearCooldown(id);
        assertFalse(manager.isOnCooldown(id, "first"));
        start("third");
        manager.cancelSession(id, true);
        manager.clearAllCooldowns();
        assertFalse(manager.isOnCooldown(id, "third"));
    }

    @Test
    void blankTargetsAndExpiredCooldownsHaveNoPenalty() {
        for (String target : new String[] {null, ""}) {
            assertEquals(0, manager.getDebuffFactor(id, target));
            assertEquals(0, manager.getCooldownRemainingSeconds(id, target));
            start(target);
            manager.cancelSession(id, true);
            assertFalse(manager.isOnCooldown(id, target));
        }
        assertEquals(0, manager.getCooldownRemainingSeconds(UUID.randomUUID(), "missing"));
        Parameters.lockpickFailCooldownMs = 0;
        start("expired");
        manager.cancelSession(id, true);
        assertFalse(manager.isOnCooldown(id, "expired"));
        assertEquals(0, manager.getCooldownRemainingSeconds(id, "expired"));
        assertEquals(0, manager.getCooldownRemainingSeconds(id, "other"));
    }

    @Test
    void doorSessionsUseCanonicalTargetAndLocationCooldownOverloads() {
        var world = server.addSimpleWorld("door-session");
        Location door = new Location(world, 3, 64, 5);
        when(player.getLocation()).thenReturn(door.clone().add(0.5, 0.5, 0.5));
        manager.startDoorSession(player, door, 0.5, 10, 0.5);
        assertEquals(LockPickManager.SessionKind.DOOR, manager.getSessionKind(id));
        assertEquals(DoorLockpick.doorTargetId(door), manager.getSessionTargetId(id));
        manager.cancelSession(id, true);
        assertTrue(manager.isOnCooldown(id, door));
        assertTrue(manager.getDebuffFactor(id, door) > 0.9);
        assertTrue(manager.getCooldownRemainingSeconds(id, door) >= 58);
    }

    @Test
    void walkingAwayFromDoorStopsItsTimerAndAppliesOnlyThatDoorsCooldown() {
        var world = server.addSimpleWorld("walk-away");
        Location door = new Location(world, 3, 64, 5);
        when(player.getLocation()).thenReturn(DoorLockpick.getDoorCenter(door));
        manager.startDoorSession(player, door, 0.5, 10, 0.5);
        server.getScheduler().performOneTick();
        verify(data).applyRiskDecay(10);

        when(player.getLocation()).thenReturn(door.clone().add(1000, 0, 0));
        server.getScheduler().performOneTick();

        assertFalse(manager.isInSession(id));
        assertTrue(manager.isOnCooldown(id, door));
        assertFalse(manager.isOnCooldown(id, door.clone().add(1, 0, 0)));
        assertTrue(manager.getCooldownRemainingSeconds(id, door) >= 58);
        verify(player).sendMessage(contains("moved too far from the door"));
        assertEquals("", lastBar.get());
        server.getScheduler().performTicks(5);
        verify(data, times(1)).applyRiskDecay(10);
        verify(player, times(1)).sendMessage(anyString());
    }

    @Test
    void cursorMovesThroughInteriorSlotsAndBouncesAtBothEnds() {
        Parameters.barLength = 5;
        Parameters.maxSuccessSlots = 5;
        manager.startSession(player, anchor, LockPickManager.SessionKind.DISPLAY, "target", 0, 10, 0.5, null);
        server.getScheduler().performOneTick();
        int position = org.bukkit.ChatColor.stripColor(lastBar.get()).indexOf('=') - 1;
        int direction = position == 4 ? -1 : 1;
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        visited.add(position);

        for (int tick = 0; tick < 10; tick++) {
            position += direction;
            if (position == 4) direction = -1;
            else if (position == 0) direction = 1;
            server.getScheduler().performOneTick();
            String bar = org.bukkit.ChatColor.stripColor(lastBar.get());
            assertEquals(7, bar.length());
            assertEquals(position, bar.indexOf('=') - 1);
            assertEquals(1, bar.chars().filter(character -> character == '=').count());
            visited.add(position);
        }

        assertEquals(java.util.Set.of(0, 1, 2, 3, 4), visited);
        assertEquals(LockPickManager.SelectResult.SUCCESS, manager.handleSelect(player));
        assertFalse(manager.isOnCooldown(id, "target"));
    }

    @Test
    void configurationReloadDoesNotChangeActiveSelection() {
        start("target");
        server.getScheduler().performOneTick();
        String renderedBar = lastBar.get();
        assertEquals(2, org.bukkit.ChatColor.stripColor(renderedBar).indexOf('='));
        // The other slot reveals whether the cursor is on success or failure.
        var expected = renderedBar.contains("§a-")
                ? LockPickManager.SelectResult.FAIL : LockPickManager.SelectResult.SUCCESS;

        Parameters.barLength = 1;

        assertEquals(expected, manager.handleSelect(player));
        assertFalse(manager.isInSession(id));
        assertEquals(expected == LockPickManager.SelectResult.FAIL, manager.isOnCooldown(id, "target"));
    }


    @Test
    void oversizedBreakAllocationAndDirectionReversalStillRenderAValidBar() {
        Parameters.barLength = 3;
        Parameters.minBreakSlots = 20;
        Parameters.maxBreakSlots = 20;
        Parameters.randomFlipChance = 1;
        start("target");
        server.getScheduler().performTicks(5);
        assertTrue(manager.isInSession(id));
        assertNotNull(lastBar.get());
        assertEquals(5, org.bukkit.ChatColor.stripColor(lastBar.get()).length());
        assertTrue(lastBar.get().contains("§f="));
        assertTrue(lastBar.get().contains("§c-"));
    }

    @Test
    void barShowsSlotsNotAllocatedToSuccessOrBreakAsFailures() {
        Parameters.barLength = 5;
        Parameters.minBreakSlots = 1;
        Parameters.maxBreakSlots = 1;
        start("target");
        server.getScheduler().performOneTick();

        String bar = lastBar.get();
        assertEquals(7, org.bukkit.ChatColor.stripColor(bar).length()); // Brackets around five slots.
        assertEquals(1, bar.split("§f=", -1).length - 1);
        // One success, one break and three failures; the cursor covers at most one failure.
        assertTrue(bar.split("§e-", -1).length - 1 >= 2);
        assertTrue(bar.split("§e-", -1).length - 1 <= 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negativeBreakConfigurationStillAllowsSelectionWhenShuffleOmitsSuccess(boolean failFirst) {
        Parameters.barLength = 1;
        Parameters.minBreakSlots = -1;
        Parameters.maxBreakSlots = -1;
        LockPickManager.SelectResult result;
        // This configuration creates [success, fail] before truncating to one slot.
        // Exercise both valid shuffle orders without depending on random chance.
        try (var randoms = mockConstruction(Random.class, (random, context) ->
                when(random.nextInt(anyInt())).thenAnswer(invocation ->
                        failFirst ? 0 : invocation.<Integer>getArgument(0) - 1))) {
            manager = new LockPickManager();
            start("target");
            result = manager.handleSelect(player);
        }

        assertEquals(failFirst ? LockPickManager.SelectResult.FAIL : LockPickManager.SelectResult.SUCCESS, result);
        assertEquals(failFirst, manager.isOnCooldown(id, "target"));
        assertFalse(manager.isInSession(id));
        server.getScheduler().performTicks(2);
        verifyNoInteractions(data);
    }

    private void start(String target) {
        manager.startSession(player, anchor, LockPickManager.SessionKind.DISPLAY, target, 0.5, 10, 0.5, null);
    }
}
