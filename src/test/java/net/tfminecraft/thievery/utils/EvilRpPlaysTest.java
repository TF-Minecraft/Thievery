package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.thievery.Thievery;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class EvilRpPlaysTest {
    // A fresh copy of the bridge models a server restart and prevents its one-time
    // API lookup from leaking between the old/new dependency compatibility cases.
    private Method bridge(boolean apiAvailable) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("net.tfminecraft.rpcharacters.evilrp.EvilRpService") && !apiAvailable) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(EvilRpPlays.class.getName())) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (var source = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (source == null) throw new ClassNotFoundException(name);
                        byte[] bytes = source.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length,
                                EvilRpPlays.class.getProtectionDomain());
                    } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        return loader.loadClass(EvilRpPlays.class.getName()).getMethod("record", Player.class);
    }

    @Test void olderDependencyWarnsOnceAndTheftCanContinueWithoutOptionalApi() throws Exception {
        var plugin = mock(Thievery.class);
        var logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        try (var thievery = mockStatic(Thievery.class)) {
            thievery.when(Thievery::getInstance).thenReturn(plugin);
            Method record = bridge(false);
            record.invoke(null, mock(Player.class));
            record.invoke(null, mock(Player.class));
            verify(logger).warning(contains("no evil RP sessions"));
            verifyNoMoreInteractions(logger);
        }
    }

    @Test void compatibleDependencyReceivesEveryPlayAndNullPlayersAreIgnored() throws Exception {
        var plays = new ArrayList<Player>();
        EvilRpService.recorder = plays::add;
        try {
            Method record = bridge(true);
            Player player = mock(Player.class);
            record.invoke(null, player);
            record.invoke(null, player);
            record.invoke(null, new Object[]{null});
            assertEquals(java.util.List.of(player, player), plays);
        } finally { EvilRpService.recorder = EvilRpService.IGNORE; }
    }

    @Test void optionalServiceFailureIsLoggedAndDoesNotAbortTheft() throws Exception {
        var plugin = mock(Thievery.class);
        var logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("thief");
        EvilRpService.recorder = ignored -> { throw new IllegalStateException("service unavailable"); };
        try (var thievery = mockStatic(Thievery.class)) {
            thievery.when(Thievery::getInstance).thenReturn(plugin);
            bridge(true).invoke(null, player);
            verify(logger).warning(contains("Could not record evil RP play for thief: java.lang.IllegalStateException: service unavailable"));
        } finally { EvilRpService.recorder = EvilRpService.IGNORE; }
    }
}
