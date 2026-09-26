package net.tfminecraft.thievery.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.player.PickpocketManager;
import net.tfminecraft.thievery.player.TraitChecker;
import net.tfminecraft.thievery.robbery.RobberyManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ActivityCommandsTest {
    private PickpocketManager pickpockets;
    private RobberyManager robberies;
    private PickpocketCommand pick;
    private RobberyCommand robbery;
    private Player player;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        pickpockets = mock(PickpocketManager.class); robberies = mock(RobberyManager.class);
        pick = new PickpocketCommand(pickpockets); robbery = new RobberyCommand(robberies);
        player = mock(Player.class);
        var config = new YamlConfiguration();
        config.set("pickpocket.traits", List.of("thief")); config.set("robbery.traits", List.of("bandit"));
        PickpocketLoader.load(config); RobberyLoader.load(config);
    }
    @AfterEach void tearDown() {
        PickpocketLoader.load(new YamlConfiguration()); RobberyLoader.load(new YamlConfiguration());
        MockBukkit.unmock();
    }

    @Test void consoleIsRejectedWithoutStartingAnActivity() {
        CommandSender console = mock(CommandSender.class);
        assertTrue(pick.onCommand(console, null, "pickpocket", new String[]{"start"}));
        assertTrue(robbery.onCommand(console, null, "robbery", new String[]{"start"}));
        verify(console, times(2)).sendMessage("§cOnly players can use this command.");
        verifyNoInteractions(pickpockets, robberies);
    }

    @Test void emptyOrUnknownActionsDescribeAvailableCommands() {
        for (String[] args : new String[][]{{}, {"unknown"}}) {
            assertTrue(pick.onCommand(player, null, "pickpocket", args));
            assertTrue(robbery.onCommand(player, null, "robbery", args));
        }
        verify(player, times(2)).sendMessage(contains("Pickpocket commands:"));
        verify(player, times(2)).sendMessage(contains("/pickpocket start"));
        verify(player, times(2)).sendMessage(contains("Robbery commands:"));
        verify(player, times(2)).sendMessage(contains("/robbery start"));
        verify(player, times(2)).sendMessage(contains("/robbery accept"));
        verifyNoInteractions(pickpockets, robberies);
    }

    @Test void startRequiresTheActivitySpecificTraitsBeforeTargeting() {
        try (var traits = mockStatic(TraitChecker.class)) {
            assertTrue(pick.onCommand(player, null, "pickpocket", new String[]{"START"}));
            assertTrue(robbery.onCommand(player, null, "robbery", new String[]{"START"}));
            traits.verify(() -> TraitChecker.hasTraits(player, List.of("thief")));
            traits.verify(() -> TraitChecker.hasTraits(player, List.of("bandit")));
            traits.verify(() -> TraitChecker.sendMissingTraitMessage(player, "pickpocketing"));
            traits.verify(() -> TraitChecker.sendMissingTraitMessage(player, "robbery"));
            verifyNoInteractions(pickpockets, robberies);
            traits.when(() -> TraitChecker.hasTraits(player, List.of("thief"))).thenReturn(true);
            traits.when(() -> TraitChecker.hasTraits(player, List.of("bandit"))).thenReturn(true);
            assertTrue(pick.onCommand(player, null, "pickpocket", new String[]{"start"}));
            assertTrue(robbery.onCommand(player, null, "robbery", new String[]{"start"}));
            verify(pickpockets).startAwaitingTarget(player); verify(robberies).startAwaitingTarget(player);
        }
    }

    @Test void robberyAcceptanceDispatchesWithoutRequiringRobberTraits() {
        try (var traits = mockStatic(TraitChecker.class)) {
            assertTrue(robbery.onCommand(player, null, "robbery", new String[]{"ACCEPT"}));
            verify(robberies).acceptRobbery(player); traits.verifyNoInteractions();
        }
    }

    @Test void completionFiltersCaseInsensitivelyAndStopsAfterTheAction() {
        assertEquals(List.of("start"), pick.onTabComplete(player, null, "pickpocket", new String[]{""}));
        assertEquals(List.of("start"), pick.onTabComplete(player, null, "pickpocket", new String[]{"ST"}));
        assertTrue(pick.onTabComplete(player, null, "pickpocket", new String[]{"bad"}).isEmpty());
        assertEquals(List.of("start", "accept"), robbery.onTabComplete(player, null, "robbery", new String[]{""}));
        assertEquals(List.of("start"), robbery.onTabComplete(player, null, "robbery", new String[]{"ST"}));
        assertEquals(List.of("accept"), robbery.onTabComplete(player, null, "robbery", new String[]{"AC"}));
        assertTrue(robbery.onTabComplete(player, null, "robbery", new String[]{"bad"}).isEmpty());
        for (String[] args : new String[][]{{}, {"start", ""}}) {
            assertTrue(pick.onTabComplete(player, null, "pickpocket", args).isEmpty());
            assertTrue(robbery.onTabComplete(player, null, "robbery", args).isEmpty());
        }
    }
}
