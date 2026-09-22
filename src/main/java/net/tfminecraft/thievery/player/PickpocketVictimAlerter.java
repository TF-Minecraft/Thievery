package net.tfminecraft.thievery.player;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.loader.PickpocketLoader;

public final class PickpocketVictimAlerter {

    private static final double LOCKPICK_STRENGTH = 0.0;

    private PickpocketVictimAlerter() {}

    public static void tryAlert(Player thief, Player victim, PlayerData thiefData, String targetKey, int dexterity) {
        if (victim == null || !victim.isOnline()) {
            return;
        }

        thiefData.applyRiskDecay(dexterity);
        double risk = thiefData.getRisk();

        net.tfminecraft.rpcharacters.objects.PlayerData rpData = PlayerManager.get(thief);
        if (rpData != null && rpData.hasActiveCharacter()) {
            RPCharacter character = rpData.getActiveCharacter();
            double criticalChance = RiskCalculator.computeCritical(risk, dexterity, LOCKPICK_STRENGTH);
            if (Math.random() < criticalChance && !thiefData.isCriticalOnCooldown(targetKey)) {
                thiefData.recordCriticalClue(targetKey);
                Database.savePlayerData(thiefData);
                String subtitle = PickpocketLoader.getAlertSubtitleCritical()
                        .replace("{character_name}", character.getName());
                alertVictim(victim, subtitle);
                return;
            }
        }

        if (Math.random() < risk) {
            alertVictim(victim, PickpocketLoader.getAlertSubtitle());
        }
    }

    private static void alertVictim(Player victim, String subtitle) {
        victim.sendTitle("", subtitle, 5, 40, 10);
        victim.playSound(victim.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 1f);
    }
}
