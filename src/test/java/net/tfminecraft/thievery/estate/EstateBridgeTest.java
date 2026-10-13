package net.tfminecraft.thievery.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class EstateBridgeTest {

    @Test
    void tlibsWithTheEstateApiInstallsTheTLibsSource() {
        assertTrue(EstateBridge.probe());
        assertInstanceOf(TLibsLockEstates.class, EstateBridge.get());
    }

    @Test
    void anOlderTLibsLeavesLocksOutOfEstates() {
        EstateBridge.set(new TLibsLockEstates());
        assertFalse(EstateBridge.probe("net.tfminecraft.tlibs.character.estate.Missing", TLibsLockEstates::new));
        LockEstates none = EstateBridge.get();
        assertSame(LockEstates.NONE, none);
        assertEquals(List.of(), none.decided());
        assertEquals(Optional.empty(), none.current(UUID.randomUUID()));
        assertEquals(Optional.empty(), none.ofOwner(UUID.randomUUID()));
        assertFalse(none.report(null));
        Plugin plugin = mock(Plugin.class);
        EstateBridge.register(plugin, mock(Logger.class));
        verifyNoInteractions(plugin);

        assertFalse(EstateBridge.probe("net.tfminecraft.tlibs.character.estate.Estates", () -> {
            throw new NoClassDefFoundError("net/tfminecraft/tlibs/character/estate/Outcome");
        }));
        assertSame(LockEstates.NONE, EstateBridge.get());
    }

    @Test
    void aFailedRegistrationIsLogged() {
        LockEstates broken = mock(LockEstates.class);
        doThrow(new IllegalStateException("services closed")).when(broken).register(any());
        EstateBridge.set(broken);
        Logger log = mock(Logger.class);
        EstateBridge.register(mock(Plugin.class), log);
        verify(log).log(eq(Level.WARNING), contains("could not join estates"), any(IllegalStateException.class));
    }
}
