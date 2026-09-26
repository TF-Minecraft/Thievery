package net.tfminecraft.thievery.clue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.door.*;
import net.tfminecraft.thievery.player.*;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.*;

class ClueDropperTest {
    Player player; PlayerData data; RPCharacter character; net.tfminecraft.rpcharacters.objects.PlayerData rpData;
    ChestLockpickSession session; Block block; Location location; int minChest,minDoor; String critical;
    MockedStatic<Thievery> plugins; MockedStatic<net.tfminecraft.rpcharacters.managers.PlayerManager> characters;
    MockedStatic<ClueGiver> clues; MockedStatic<Database> database; MockedStatic<RiskCalculator> risks; MockedStatic<TargetKeyResolver> targets;
    @BeforeEach void setup() {
        minChest=Cache.minCluesContainer; minDoor=Cache.minCluesDoor; critical=Cache.criticalClue; Cache.minCluesContainer=1; Cache.minCluesDoor=1; Cache.criticalClue="Crime by {character_name}";
        player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); location=new Location(null,1,2,3); when(player.getLocation()).thenReturn(location);
        data=mock(PlayerData.class); when(data.getRisk()).thenReturn(.4); when(data.getRecentCluesForExclude("target")).thenReturn(List.of("old"));
        plugins=mockStatic(Thievery.class,RETURNS_DEEP_STUBS); when(Thievery.getPlayerManager().get(player.getUniqueId())).thenReturn(data);
        rpData=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class); character=mock(RPCharacter.class); when(character.getName()).thenReturn("Thief"); when(rpData.hasActiveCharacter()).thenReturn(true); when(rpData.getActiveCharacter()).thenReturn(character);
        characters=mockStatic(net.tfminecraft.rpcharacters.managers.PlayerManager.class); characters.when(()->net.tfminecraft.rpcharacters.managers.PlayerManager.get(player)).thenReturn(rpData);
        clues=mockStatic(ClueGiver.class,RETURNS_DEEP_STUBS); clues.when(()->ClueGiver.getRandomClueExcluding(character,List.of("old"))).thenReturn("footprint");
        database=mockStatic(Database.class); risks=mockStatic(RiskCalculator.class); targets=mockStatic(TargetKeyResolver.class); targets.when(()->TargetKeyResolver.resolve(any())).thenReturn("target");
        session=mock(ChestLockpickSession.class); when(session.getTargetKey()).thenReturn("target"); when(session.getLockType()).thenReturn(LockTypeProfile.IDENTITY); block=mock(Block.class); when(block.getLocation()).thenReturn(location);
    }
    @AfterEach void close() { targets.close(); risks.close(); database.close(); clues.close(); characters.close(); plugins.close(); Cache.minCluesContainer=minChest; Cache.minCluesDoor=minDoor; Cache.criticalClue=critical; }
    @Test void guaranteedChestClueRecordsOnlyAfterHologramSuccessfullySpawns() {
        ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0); verify(data).applyRiskDecay(20); verify(data).recordClueUsed("footprint","target"); verify(session).incrementSuccessfulClueDrops(); database.verify(()->Database.savePlayerData(data));
        clearInvocations(data,session); database.clearInvocations(); clues.when(()->ClueGiver.spawnClueWithText(location,location,player,"footprint",true)).thenReturn(null);
        ClueDropper.tryDropChestClue(player,session,block,20,.5,true); verify(session,never()).incrementSuccessfulClueDrops(); verify(data,never()).recordClueUsed(anyString(),anyString()); database.verifyNoInteractions();
    }
    @Test void chanceAndCharacterEligibilityCanPreventDrops() {
        when(session.getSuccessfulClueDrops()).thenReturn(1); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0); characters.verifyNoInteractions();
        risks.when(()->RiskCalculator.computeTakeClueChance(.4,3)).thenReturn(1.0);
        characters.when(()->net.tfminecraft.rpcharacters.managers.PlayerManager.get(player)).thenReturn(null); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0);
        characters.when(()->net.tfminecraft.rpcharacters.managers.PlayerManager.get(player)).thenReturn(rpData); when(rpData.hasActiveCharacter()).thenReturn(false); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0);
        when(rpData.hasActiveCharacter()).thenReturn(true); clues.when(()->ClueGiver.getRandomClueExcluding(character,List.of("old"))).thenReturn(null); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0);
        verify(data,never()).recordClueUsed(anyString(),anyString()); database.verifyNoInteractions();
    }
    @Test void criticalCluesNameCharacterRespectCooldownAndLockPolicy() {
        risks.when(()->RiskCalculator.computeCritical(.4,20,.5)).thenReturn(1.0);
        ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0); verify(data).recordCriticalClue("target"); verify(data).recordClueUsed("Crime by Thief","target");
        clearInvocations(data); when(data.isCriticalOnCooldown("target")).thenReturn(true); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0); verify(data).recordClueUsed("footprint","target"); verify(data,never()).recordCriticalClue(anyString());
        clearInvocations(data); when(session.getLockType()).thenReturn(new LockTypeProfile(1,1,false,1)); ClueDropper.tryDropChestClue(player,session,block,20,.5,3.0); verify(data).recordClueUsed("footprint","target");
    }
    @Test void doorCluesUseCanonicalAnchorAndOnlyPersistSuccessfulDrops() {
        UUID owner=UUID.randomUUID(); ClueDropper.tryDropDoorClue(player,location,owner,20,.5); targets.verify(()->TargetKeyResolver.resolve(owner)); verify(data).recordClueUsed("footprint","target"); database.verify(()->Database.savePlayerData(data));
        clearInvocations(data); database.clearInvocations(); clues.when(()->ClueGiver.spawnClueWithText(location,location,player,"footprint",true)).thenReturn(null); ClueDropper.tryDropDoorClue(player,location,owner,20,.5); database.verifyNoInteractions();
        clues.when(()->ClueGiver.getRandomClueExcluding(character,List.of("old"))).thenReturn(null); ClueDropper.tryDropDoorClue(player,location,owner,20,.5); verify(data,never()).recordClueUsed(anyString(),anyString());
        Cache.minCluesDoor=0; when(data.getRisk()).thenReturn(0.0); targets.clearInvocations(); ClueDropper.tryDropDoorClue(player,location,owner,20,.5); targets.verifyNoInteractions();
    }
}
