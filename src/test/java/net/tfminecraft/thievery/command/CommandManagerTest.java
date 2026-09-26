package net.tfminecraft.thievery.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.clue.ClearCluesManager;
import net.tfminecraft.thievery.door.ContainerManager;
import net.tfminecraft.thievery.door.FactionLockTutorial;
import net.tfminecraft.thievery.key.KeychainHandler;
import net.tfminecraft.thievery.player.CooldownResetService;
import net.tfminecraft.thievery.player.InventoryManager;
import net.tfminecraft.thievery.player.RiskSetService;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.player.TraitChecker;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class CommandManagerTest {
    private ServerMock server;
    private ContainerManager containers;
    private InventoryManager inventories;
    private CooldownResetService cooldowns;
    private RiskSetService risks;
    private ClearCluesManager clues;
    private CommandManager commands;
    private Player player;
    private CommandSender console;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        containers = mock(ContainerManager.class); inventories = mock(InventoryManager.class);
        cooldowns = mock(CooldownResetService.class); risks = mock(RiskSetService.class); clues = mock(ClearCluesManager.class);
        commands = new CommandManager(containers, inventories, cooldowns, risks, clues);
        player = mock(Player.class, RETURNS_DEEP_STUBS); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        console = mock(CommandSender.class);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void adminActionsRejectUnprivilegedPlayersWithoutDispatch() {
        for (String[] args : new String[][]{{"itemvalue"}, {"reload"}, {"resetcooldowns", "all"},
                {"setrisk", "all", "0.5"}, {"feedback"}, {"keychain"}}) {
            assertTrue(run(player, args));
        }
        verify(player, times(6)).sendMessage(contains("don't have permission"));
        verifyNoInteractions(containers, inventories, cooldowns, risks, clues);
    }

    @Test void playerOnlyActionsRejectConsoleAndHelpReflectsAdminPermission() {
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        for (String[] args : new String[][]{{"dismissfactionlock"}, {"itemvalue"}, {"clearclues"}, {"loadout"}, {}}) {
            assertTrue(run(console, args));
        }
        verify(console, times(5)).sendMessage(contains("only be used by players"));
        assertTrue(run(player));
        verify(player).sendMessage(contains("/thievery loadout"));
        verify(player).sendMessage(contains("/thievery clearclues"));
        verify(player, never()).sendMessage(contains("/thievery reload"));
        clearInvocations(player);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        assertTrue(run(player));
        for (String action : List.of("feedback", "reload", "resetcooldowns", "setrisk", "itemvalue", "keychain", "loadout", "clearclues")) {
            verify(player).sendMessage(contains("/thievery " + action));
        }
    }

    @Test void dismissAndClueCommandsDispatchWithoutAdminPermission() {
        try (var tutorial = mockStatic(FactionLockTutorial.class)) {
            assertTrue(run(player, "DISMISSFACTIONLOCK"));
            tutorial.verify(() -> FactionLockTutorial.dismiss(player));
            verify(player).sendMessage("§aGot it.");
        }
        assertTrue(run(player, "CLEARCLUES"));
        verify(clues).startAwaiting(player);
    }

    @Test void loadoutRequiresTraitsBeforeOpeningInventory() {
        try (var traits = mockStatic(TraitChecker.class)) {
            assertTrue(run(player, "loadout"));
            traits.verify(() -> TraitChecker.sendMissingTraitMessage(player));
            verifyNoInteractions(inventories);
            traits.when(() -> TraitChecker.hasRequiredTraits(player)).thenReturn(true);
            assertTrue(run(player, "LOADOUT"));
            verify(inventories).openLoadout(player);
        }
    }

    @Test void reloadUsesPlayerFeedbackOrConsoleAcknowledgement() {
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        Thievery plugin = mock(Thievery.class);
        try (var thievery = mockStatic(Thievery.class)) {
            thievery.when(Thievery::getInstance).thenReturn(plugin);
            assertTrue(run(player, "ReLoAd")); verify(plugin).reloadMessage(player);
            assertTrue(run(console, "reload")); verify(plugin).reload();
            verify(console).sendMessage(contains("Reloaded."));
        }
    }

    @Test void cooldownResetDispatchesAllOrTheNamedPlayersUuid() {
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        var target = server.addPlayer("Alice");
        assertTrue(run(console, "resetcooldowns", "ALL"));
        verify(cooldowns).resetAll(); verify(console).sendMessage(contains("all players"));
        assertTrue(run(console, "RESETCOOLDOWNS", "Alice"));
        verify(cooldowns).resetForPlayer(target.getUniqueId()); verify(console).sendMessage(contains("Alice"));
    }

    @Test void riskRejectsInvalidNumbersAndDispatchesValidatedBoundaryAndNamedValues() {
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        assertTrue(run(console, "setrisk", "all", "invalid"));
        verify(console).sendMessage(contains("Invalid risk value"));
        for (String value : List.of("-0.1", "1.1", "Infinity", "-Infinity", "1e309", "-1e309")) assertTrue(run(console, "setrisk", "all", value));
        verify(console, times(6)).sendMessage(contains("Risk must be between"));
        verifyNoInteractions(risks);
        assertTrue(run(console, "setrisk", "ALL", "0")); verify(risks).setForAll(0);
        assertTrue(run(console, "setrisk", "ALL", "1")); verify(risks).setForAll(1);
        var target = server.addPlayer("Alice");
        assertTrue(run(console, "SETRISK", "Alice", "0.5"));
        verify(risks).setForPlayer(target.getUniqueId(), 0.5);
        verify(console).sendMessage(argThat((String message) -> message.contains("0.500") && message.contains("Alice")));
    }


    @Test void nanRiskIsRejectedForAllAndNamedTargetsBeforeMutationIsDispatched() {
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        server.addPlayer("Alice");
        assertTrue(run(console, "setrisk", "all", "NaN"));
        assertTrue(run(console, "setrisk", "Alice", "NaN"));
        verifyNoInteractions(risks);
        verify(console, times(2)).sendMessage(argThat((String message) ->
                message.contains("Risk must be between") || message.contains("Invalid risk value")));
        verify(console, never()).sendMessage(contains("Set risk to"));
    }

    @Test void rejectedNanLeavesLoadedPlayersRiskAndDecayTimestampUnchanged() {
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        UUID id = server.addPlayer("Alice").getUniqueId();
        PlayerData data = new PlayerData(id);
        data.setRisk(0.7);
        data.setLastRiskDecayMs(1234L);
        PlayerManager manager = mock(PlayerManager.class);
        when(manager.exists(id)).thenReturn(true);
        when(manager.get(id)).thenReturn(data);
        commands = new CommandManager(containers, inventories, cooldowns, new RiskSetService(), clues);
        try (var thievery = mockStatic(Thievery.class)) {
            thievery.when(Thievery::getPlayerManager).thenReturn(manager);
            assertTrue(run(console, "setrisk", "Alice", "NaN"));
            assertAll(
                    () -> assertEquals(0.7, data.getRisk(), "Rejected input must not clear existing risk"),
                    () -> assertEquals(1234L, data.getLastRiskDecayMs(), "Rejected input must not reset decay timing"));
        }
    }

    @Test void feedbackTogglePersistsBothStatesAndReportsThem() {
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        when(containers.getFeedbackState(player.getUniqueId())).thenReturn(false, true);
        assertTrue(run(player, "feedback"));
        verify(containers).setFeedbackState(player.getUniqueId(), true);
        verify(player).sendMessage(contains("enabled"));
        assertTrue(run(player, "FEEDBACK"));
        verify(containers).setFeedbackState(player.getUniqueId(), false);
        verify(player).sendMessage(contains("disabled"));
    }

    @Test void itemValueSendsEveryReportLineForTheHeldItem() {
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        ItemStack held = mock(ItemStack.class); when(player.getInventory().getItemInMainHand()).thenReturn(held);
        try (var values = mockStatic(ItemValue.class)) {
            values.when(() -> ItemValue.buildReport(held)).thenReturn(List.of("Value details", "Total: 2"));
            assertTrue(run(player, "ITEMVALUE"));
            verify(player).sendMessage("Value details"); verify(player).sendMessage("Total: 2");
        }
    }

    @Test void keychainIsGivenOrDroppedIfInventoryHasNoRoom() {
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        ItemStack keychain = mock(ItemStack.class);
        when(player.getInventory().addItem(keychain)).thenReturn(new HashMap<>(), new HashMap<>(Map.of(0, keychain)));
        try (var keys = mockStatic(KeychainHandler.class)) {
            keys.when(KeychainHandler::createKeychain).thenReturn(keychain);
            assertTrue(run(player, "keychain"));
            verify(player.getWorld(), never()).dropItemNaturally(any(), any());
            assertTrue(run(player, "KEYCHAIN"));
            verify(player.getWorld()).dropItemNaturally(player.getLocation(), keychain);
            verify(player, times(2)).sendMessage(contains("Keychain given."));
        }
    }

    @Test void unknownAndIncompleteCommandsOfferAUsefulNextAction() {
        for (String[] args : new String[][]{{"unknown"}, {"unknown", "player", "value"}, {"resetcooldowns"}, {"setrisk"}, {"setrisk", "all"}}) assertTrue(run(player, args));
        verify(player, times(5)).sendMessage(argThat((String message) -> message.contains("Unknown subcommand") && message.contains("/thievery loadout")));
        verifyNoInteractions(cooldowns, risks);
    }

    @Test void completionExposesOnlyPermittedCommandsAndFiltersPlayerAndRiskPrefixes() {
        assertEquals(List.of("loadout", "clearclues"), complete(player, ""));
        assertTrue(complete(player, "resetcooldowns", "").isEmpty());
        assertTrue(complete(player, "setrisk", "all", "").isEmpty());
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        assertEquals(List.of("loadout", "clearclues", "feedback", "reload", "resetcooldowns", "setrisk", "itemvalue", "keychain"), complete(player, ""));
        server.addPlayer("Alice"); server.addPlayer("Bob");
        assertEquals(List.of("all", "Alice", "Bob"), complete(player, "resetcooldowns", ""));
        assertEquals(List.of("Alice"), complete(player, "SETRISK", "ALI"));
        assertEquals(List.of("all", "Alice"), complete(player, "resetcooldowns", "a"));
        assertEquals(List.of("0", "0.5", "1"), complete(player, "setrisk", "all", ""));
        assertEquals(List.of("0", "0.5"), complete(player, "setrisk", "all", "0"));
        assertEquals(List.of("1"), complete(player, "setrisk", "all", "1"));
        assertTrue(complete(player).isEmpty());
        assertTrue(complete(player, "unknown", "").isEmpty());
        assertTrue(complete(player, "reload", "all", "").isEmpty());
        assertTrue(complete(player, "setrisk", "all", "0", "extra").isEmpty());
    }

    private boolean run(CommandSender sender, String... args) { return commands.onCommand(sender, null, "thievery", args); }
    private List<String> complete(CommandSender sender, String... args) { return commands.onTabComplete(sender, null, "thievery", args); }
}
