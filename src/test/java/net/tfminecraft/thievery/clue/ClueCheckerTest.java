package net.tfminecraft.thievery.clue;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ClueCheckerTest {
    @Test void clueEligibilityDelegatesToCharactersIntegration() {
        var player=mock(Player.class); var item=mock(ItemStack.class);
        try(var clues=mockStatic(ClueGiver.class)) {
            assertFalse(ClueChecker.hasEnoughClues(player)); assertFalse(ClueChecker.isClueItem(item));
            clues.when(()->ClueGiver.hasEnoughClues(player)).thenReturn(true); clues.when(()->ClueGiver.isClueItem(item)).thenReturn(true);
            assertTrue(ClueChecker.hasEnoughClues(player)); assertTrue(ClueChecker.isClueItem(item));
        }
    }
    @Test void missingCharacterShowsCreationInstructions() {
        var player=mock(Player.class);
        try(var players=mockStatic(PlayerManager.class)) {
            ClueChecker.sendInsufficientCluesMessage(player); verify(player).sendTitle(" ","§cNo Character!",5,50,5); verify(player).sendMessage("§cCreate one with §e/rpcharacter create");
            clearInvocations(player); var data=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class); players.when(()->PlayerManager.get(player)).thenReturn(data); ClueChecker.sendInsufficientCluesMessage(player); verify(player).sendMessage("§cYou do not have an active character!");
        }
    }
    @Test void existingCharacterShowsCurrentAndRequiredClueCounts() {
        var player=mock(Player.class); var data=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class); var character=mock(net.tfminecraft.rpcharacters.objects.RPCharacter.class);
        try(var players=mockStatic(PlayerManager.class)) {
            players.when(()->PlayerManager.get(player)).thenReturn(data); when(data.hasActiveCharacter()).thenReturn(true); when(data.getActiveCharacter()).thenReturn(character); when(character.getPlayerClues()).thenReturn(List.of("clue")); when(character.getCluesNeeded()).thenReturn(3);
            ClueChecker.sendInsufficientCluesMessage(player); verify(player).sendTitle(" ","§cMore Clues Needed!",5,50,5); verify(player).sendMessage("§cYour character does not have enough clues (§e1§c/§e3§c)."); verify(player).sendMessage("§cAdd clues with §e/rpcharacter clues");
        }
    }
}
