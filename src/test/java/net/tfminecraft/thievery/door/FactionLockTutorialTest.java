package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class FactionLockTutorialTest {
    private Map<UUID, Long> sent;
    private Map<UUID, Long> originalSent;
    private UUID playerId;
    private Player player;
    private PlayerData data;
    private PlayerManager manager;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<Database> database;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        Field field = FactionLockTutorial.class.getDeclaredField("lastSentAt");
        field.setAccessible(true);
        sent = (Map<UUID, Long>) field.get(null);
        originalSent = new HashMap<>(sent);
        sent.clear();
        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        data = new PlayerData(playerId);
        manager = mock(PlayerManager.class);
        when(manager.get(playerId)).thenReturn(data);
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getPlayerManager).thenReturn(manager);
        database = mockStatic(Database.class);
    }

    @AfterEach
    void tearDown() {
        database.close();
        thievery.close();
        sent.clear();
        sent.putAll(originalSent);
    }

    @Test
    void ignoresAbsentPlayerAndNonFactionStates() {
        FactionLockTutorial.onLockState(null, LockState.FACTION);
        FactionLockTutorial.onLockState(player, null);
        FactionLockTutorial.onLockState(player, LockState.PUBLIC);
        FactionLockTutorial.onLockState(player, LockState.GUILD);
        FactionLockTutorial.onLockState(player, LockState.PRIVATE);
        verifyNoInteractions(manager);
        verify(player, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(any(Component.class));
        assertTrue(sent.isEmpty());
    }

    @Test
    void firstFactionLockSendsWarningAndClickableDismissalWithStyledHover() {
        FactionLockTutorial.onLockState(player, LockState.FACTION);
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(player, times(2)).sendMessage(lines.capture());
        assertEquals("§7" + "-".repeat(40), lines.getAllValues().get(0));
        assertEquals("§eThe faction lock is useful but also more risky as it allows thieves "
                + "to steal more at less risk to both discovery and lockpick break chance. "
                + "Use only when necessary and at your own risk", lines.getAllValues().get(1));
        ArgumentCaptor<Component> component = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(component.capture());
        List<Component> children = component.getValue().children();
        assertEquals(3, children.size());
        assertEquals("-".repeat(16), ((TextComponent) children.get(0)).content());
        assertEquals(NamedTextColor.GRAY, children.get(0).color());
        assertEquals(children.get(0), children.get(2));
        Component button = children.get(1);
        assertEquals("[Got It]", ((TextComponent) button).content());
        assertEquals(NamedTextColor.GREEN, button.color());
        assertEquals(TextDecoration.State.TRUE, button.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.TRUE, button.decoration(TextDecoration.UNDERLINED));
        assertEquals(ClickEvent.runCommand("/thievery dismissfactionlock"), button.clickEvent());
        assertNotNull(button.hoverEvent());
        assertEquals(HoverEvent.Action.SHOW_TEXT, button.hoverEvent().action());
        Component hover = (Component) button.hoverEvent().value();
        assertEquals("Click to dismiss", ((TextComponent) hover).content());
        assertEquals(NamedTextColor.GREEN, hover.color());
        assertEquals(TextDecoration.State.TRUE, hover.decoration(TextDecoration.BOLD));
        Component secondLine = hover.children().getFirst();
        assertEquals("\nThis warning will not show again.", ((TextComponent) secondLine).content());
        assertEquals(NamedTextColor.GRAY, secondLine.color());
        assertEquals(TextDecoration.State.TRUE, secondLine.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.TRUE, secondLine.decoration(TextDecoration.ITALIC));
        assertTrue(sent.containsKey(playerId));
        database.verifyNoInteractions();
    }

    @Test
    void recentWarningIsSuppressedAndExpiredWarningCanBeSentAgain() {
        sent.put(playerId, Long.MAX_VALUE);
        FactionLockTutorial.onLockState(player, LockState.FACTION);
        verify(player, never()).sendMessage(any(Component.class));
        assertEquals(Long.MAX_VALUE, sent.get(playerId));
        sent.put(playerId, 0L);
        FactionLockTutorial.onLockState(player, LockState.FACTION);
        verify(player).sendMessage(any(Component.class));
        assertTrue(sent.get(playerId) > 0);
    }

    @Test
    void previouslyDismissedWarningIsSuppressedEvenWithoutRecentSend() {
        data.setFactionLockWarningDismissed(true);
        FactionLockTutorial.onLockState(player, LockState.FACTION);
        verify(player, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(any(Component.class));
        assertTrue(sent.isEmpty());
    }

    @Test
    void dismissPersistsAcknowledgementClearsRateLimitAndSuppressesFutureWarnings() {
        sent.put(playerId, 1234L);
        FactionLockTutorial.dismiss(player);
        assertTrue(data.isFactionLockWarningDismissed());
        database.verify(() -> Database.savePlayerData(data));
        assertFalse(sent.containsKey(playerId));
        FactionLockTutorial.onLockState(player, LockState.FACTION);
        verify(player, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(any(Component.class));
    }
}
