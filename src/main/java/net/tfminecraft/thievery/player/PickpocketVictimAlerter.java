package net.tfminecraft.thievery.player;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
                rummage(victim);
                victim.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                        PickpocketLoader.getAlertCritical().replace("{character_name}", character.getName())));
                return;
            }
        }

        if (Math.random() < risk) {
            rummage(victim);
        }
    }

    /** Tells the victim outright, as when a pickpocket fumbles. */
    public static void alert(Player victim) {
        rummage(victim);
    }

    // Only the mark hears someone going through their bag.
    private static void rummage(Player victim) {
        victim.playSound(victim.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 1f, 0.8f);
    }
}
