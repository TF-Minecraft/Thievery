package net.tfminecraft.thievery.clue;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.door.ChestLockpickSession;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.TargetKeyResolver;

public final class ClueDropper {

    private ClueDropper() {}

    public static void tryDropChestClue(Player player, ChestLockpickSession session, Block chestBlock,
            int dexterity, double lockpickStrength, double valueTaken) {
        tryDropChestClue(player, session, chestBlock, dexterity, lockpickStrength, valueTaken, false);
    }

    public static void tryDropChestClue(Player player, ChestLockpickSession session, Block chestBlock,
            int dexterity, double lockpickStrength, boolean fromBundleTake) {
        tryDropChestClue(player, session, chestBlock, dexterity, lockpickStrength, 0, fromBundleTake);
    }

    public static void tryDropChestClue(Player player, ChestLockpickSession session, Block chestBlock,
            int dexterity, double lockpickStrength, double valueTaken, boolean fromBundleTake) {
        PlayerData playerData = Thievery.getPlayerManager().get(player.getUniqueId());
        playerData.applyRiskDecay(dexterity);
        double sessionRisk = playerData.getRisk();
        double takeClueChance = RiskCalculator.computeTakeClueChance(sessionRisk, valueTaken);
        if (!shouldDropClue(session.getSuccessfulClueDrops(), Cache.minCluesContainer, takeClueChance)) {
            return;
        }

        String targetKey = session.getTargetKey();
        String clueText = pickClue(player, playerData, targetKey, dexterity, lockpickStrength, sessionRisk,
                session.getLockType().criticalRisk());
        if (clueText == null) return;

        boolean hologramSpawned = trySpawnHologram(player, player.getLocation(), chestBlock.getLocation(), clueText);
        if (!hologramSpawned) return;

        recordSuccess(player, playerData, clueText, targetKey, session);
    }

    public static void tryDropDoorClue(Player player, Location doorCanonical, UUID ownerUUID,
            int dexterity, double lockpickStrength) {
        PlayerData playerData = Thievery.getPlayerManager().get(player.getUniqueId());
        playerData.applyRiskDecay(dexterity);
        double sessionRisk = playerData.getRisk();
        if (!shouldDropClue(0, Cache.minCluesDoor, sessionRisk)) {
            return;
        }

        String targetKey = TargetKeyResolver.resolve(ownerUUID);
        String clueText = pickClue(player, playerData, targetKey, dexterity, lockpickStrength, sessionRisk, true);
        if (clueText == null) return;

        boolean hologramSpawned = trySpawnHologram(player, doorCanonical, doorCanonical, clueText);
        if (!hologramSpawned) return;

        playerData.recordClueUsed(clueText, targetKey);
        Database.savePlayerData(playerData);
    }

    private static boolean shouldDropClue(int cluesAlreadyDropped, int minClues, double clueChance) {
        if (cluesAlreadyDropped < minClues) {
            return true;
        }
        return Math.random() < clueChance;
    }

    private static String pickClue(Player player, PlayerData playerData, String targetKey,
            int dexterity, double lockpickStrength, double effectiveRisk, boolean criticalRisk) {
        net.tfminecraft.rpcharacters.objects.PlayerData rpData = PlayerManager.get(player);
        if (rpData == null || !rpData.hasActiveCharacter()) return null;

        RPCharacter character = rpData.getActiveCharacter();
        double criticalChance = criticalRisk
                ? RiskCalculator.computeCritical(effectiveRisk, dexterity, lockpickStrength)
                : 0.0;
        if (Math.random() < criticalChance && !playerData.isCriticalOnCooldown(targetKey)) {
            playerData.recordCriticalClue(targetKey);
            return Cache.criticalClue.replace("{character_name}", character.getName());
        }

        return ClueGiver.getRandomClueExcluding(character, playerData.getRecentCluesForExclude(targetKey));
    }

    private static boolean trySpawnHologram(Player player, Location anchor, Location targetBlock, String clueText) {
        return ClueGiver.spawnClueWithText(anchor, targetBlock, player, clueText, true) != null;
    }

    private static void recordSuccess(Player player, PlayerData playerData, String clueText,
            String targetKey, ChestLockpickSession session) {
        playerData.recordClueUsed(clueText, targetKey);
        session.incrementSuccessfulClueDrops();
        Database.savePlayerData(playerData);
    }
}
