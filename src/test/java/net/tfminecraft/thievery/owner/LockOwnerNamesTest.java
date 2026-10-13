package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;

class LockOwnerNamesTest {
    FactionFixture identity;
    MockedStatic<Bukkit> bukkit;
    final UUID account = UUID.randomUUID();
    final UUID kira = UUID.randomUUID();

    @BeforeEach void setUp() {
        identity = new FactionFixture();
        bukkit = mockStatic(Bukkit.class);
        OfflinePlayer steve = mock(OfflinePlayer.class);
        when(steve.getName()).thenReturn("Steve");
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
        bukkit.when(() -> Bukkit.getOfflinePlayer(account)).thenReturn(steve);
    }
    @AfterEach void tearDown() { bukkit.close(); identity.close(); }

    CharacterInfo kira(CharacterStatus status) {
        return new CharacterInfo(new CharacterRef(account, kira), "Kira", status, null, java.util.Set.of(), 0, null);
    }

    @Test void staffSeeTheCharacterAndItsAccount() {
        identity.identity().when(() -> OwnerIdentity.info(kira)).thenReturn(Optional.of(kira(CharacterStatus.ALIVE)));
        assertEquals("Kira (Steve)", LockOwnerNames.staffLabel(LockOwner.character(kira)));
        identity.identity().when(() -> OwnerIdentity.info(kira)).thenReturn(Optional.of(kira(CharacterStatus.DEAD)));
        assertEquals("Kira (Steve, dead)", LockOwnerNames.staffLabel(LockOwner.character(kira)));
        bukkit.when(() -> Bukkit.getOfflinePlayer(account)).thenReturn(mock(OfflinePlayer.class));
        assertEquals("Kira (" + account + ", dead)", LockOwnerNames.staffLabel(LockOwner.character(kira)));
        identity.identity().when(() -> OwnerIdentity.info(kira)).thenReturn(Optional.empty());
        assertEquals("unknown character " + kira, LockOwnerNames.staffLabel(LockOwner.character(kira)));
    }

    @Test void otherOwnersAreDescribedPlainly() {
        UUID stranger = UUID.randomUUID();
        assertEquals("nobody", LockOwnerNames.staffLabel(null));
        assertEquals("the server", LockOwnerNames.staffLabel(LockOwner.server()));
        assertEquals("an unreadable record", LockOwnerNames.staffLabel(LockOwner.unreadable()));
        assertEquals("legacy account Steve (" + account + ")", LockOwnerNames.staffLabel(LockOwner.legacy(account)));
        assertEquals("unresolved account " + stranger, LockOwnerNames.staffLabel(LockOwner.unresolved(stranger)));
    }
}
