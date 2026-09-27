package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;

class LockStateLogTest {
    private MockedStatic<Thievery> thievery;
    private CoreProtectAPI coreProtect;
    private Player player;
    private Location location;
    private boolean oldCoreProtect;

    @BeforeEach
    void setUp() {
        coreProtect = mock(CoreProtectAPI.class);
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getCoreProtect).thenReturn(coreProtect);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Owner");
        location = mock(Location.class);
        oldCoreProtect = Cache.coreProtect;
        Cache.coreProtect = true;
    }

    @AfterEach
    void tearDown() {
        Cache.coreProtect = oldCoreProtect;
        thievery.close();
    }

    @Test
    void recordsOwnerAndStaffChangesWithTheNewStateInTheUserName() {
        LockStateLog.record(player, location, LockState.FACTION, false);
        LockStateLog.record(player, location, LockState.PUBLIC, true);
        verify(coreProtect).logInteraction("Owner_lock_faction", location);
        verify(coreProtect).logInteraction("Owner_staff_lock_public", location);
        assertEquals("Owner_lock_private", LockStateLog.user("Owner", LockState.PRIVATE, false));
    }

    @Test
    void skipsLoggingWithoutCoreProtectOrMissingDetails() {
        LockStateLog.record(null, location, LockState.GUILD, false);
        LockStateLog.record(player, null, LockState.GUILD, false);
        LockStateLog.record(player, location, null, false);
        thievery.when(Thievery::getCoreProtect).thenReturn(null);
        LockStateLog.record(player, location, LockState.GUILD, false);
        Cache.coreProtect = false;
        LockStateLog.record(player, location, LockState.GUILD, false);
        verifyNoInteractions(coreProtect);
    }
}
