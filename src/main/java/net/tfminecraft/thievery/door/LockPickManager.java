package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.door.DoorLockpick.DoorProximityAnchor;
import net.tfminecraft.thievery.door.DoorLockpick.ProximityAnchor;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class LockPickManager {

    public enum SessionKind {
        DOOR,
        DISPLAY
    }

    public enum SelectResult {
        SUCCESS, FAIL, BREAK, NOT_IN_SESSION
    }

    private final String breakBar = ThieveryTexts.ERROR + "-";
    private final String successBar = ThieveryTexts.SUCCESS + "-";
    private final String failBar = ThieveryTexts.WARN + "-";
    private final String currentBar = ThieveryTexts.WHITE + "=";
    private final Random random = new Random();

    private final Map<UUID, LockpickSession> sessions = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownExpiry = new HashMap<>();

    private static class LockpickSession {
        final ProximityAnchor anchor;
        final SessionKind kind;
        final String targetId;
        final char[] layout;
        final double lockpickStrength;
        final int dexterity;
        final Runnable onProximityLost;
        double position = 0;
        final double speed;
        int direction = 1;
        BukkitRunnable task;

        LockpickSession(ProximityAnchor anchor, SessionKind kind, String targetId,
                char[] layout, double speed, double lockpickStrength, int dexterity, Runnable onProximityLost) {
            this.anchor = anchor;
            this.kind = kind;
            this.targetId = targetId;
            this.layout = layout;
            this.speed = speed;
            this.lockpickStrength = lockpickStrength;
            this.dexterity = dexterity;
            this.onProximityLost = onProximityLost;
        }
    }

    public void startDoorSession(Player player, Location doorLoc, double effectiveStrength, int dexterity,
            double lockpickStrength) {
        DoorProximityAnchor anchor = new DoorLockpick.DoorProximityAnchor(doorLoc);
        startSession(player, anchor, SessionKind.DOOR, DoorLockpick.doorTargetId(doorLoc), effectiveStrength, dexterity,
                lockpickStrength, null);
    }

    public void startSession(Player player, ProximityAnchor anchor, SessionKind kind, String targetId,
            double effectiveStrength, int dexterity, double lockpickStrength, Runnable onProximityLost) {
        cancelSession(player.getUniqueId(), true);
        EvilRpPlays.record(player);

        int barLength = Parameters.barLength;
        int successCount = Math.max(1,
                (int) Math.round(1 + (Parameters.maxSuccessSlots - 1) * (1.0 - effectiveStrength)));
        int breakCount = (int) Math.round(
                Parameters.minBreakSlots + (Parameters.maxBreakSlots - Parameters.minBreakSlots) * effectiveStrength);
        if (successCount + breakCount > barLength) {
            breakCount = barLength - successCount;
        }
        int failCount = barLength - successCount - breakCount;

        double debuffFactor = getDebuffFactor(player.getUniqueId(), targetId);
        int debuffBreaks = (int) Math.round(failCount * debuffFactor);
        failCount -= debuffBreaks;
        breakCount += debuffBreaks;

        List<Character> layoutList = new ArrayList<>(barLength);
        for (int i = 0; i < successCount; i++) {
            layoutList.add('s');
        }
        for (int i = 0; i < breakCount; i++) {
            layoutList.add('b');
        }
        for (int i = 0; i < failCount; i++) {
            layoutList.add('f');
        }
        Collections.shuffle(layoutList);

        char[] layout = new char[barLength];
        for (int i = 0; i < barLength; i++) {
            layout[i] = layoutList.get(i);
        }

        List<Integer> successIndices = new ArrayList<>();
        for (int i = 0; i < barLength; i++) {
            if (layout[i] == 's') {
                successIndices.add(i);
            }
        }
        double startPosition = successIndices.isEmpty() ? 0
                : successIndices.get(random.nextInt(successIndices.size()));

        double speed = Math.max(Parameters.minBarSpeed,
                Parameters.baseBarSpeed * (1.0 - dexterity * Parameters.dexSpeedReductionPerLevel));

        UUID uuid = player.getUniqueId();
        LockpickSession session = new LockpickSession(anchor, kind, targetId, layout, speed,
                lockpickStrength, dexterity, onProximityLost);
        session.position = startPosition;

        BukkitRunnable task = new BukkitRunnable() {
            // Keep the existing legacy text representation, formatting, and exact-string comparisons.
            @SuppressWarnings("deprecation")
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelSession(uuid, false);
                    return;
                }

                if (!session.anchor.isInRange(player)) {
                    cancelSession(uuid, true);
                    player.sendTitle("", "", 0, 1, 0);
                    session.anchor.onOutOfRange(player);
                    if (session.onProximityLost != null) {
                        session.onProximityLost.run();
                    }
                    return;
                }

                if (random.nextDouble() < Parameters.randomFlipChance) {
                    session.direction *= -1;
                }

                double jitter = (random.nextDouble() * 2 - 1) * Parameters.baseBarSpeed
                        * Parameters.speedJitterFraction;
                double tickSpeed = Math.max(Parameters.minBarSpeed, session.speed + jitter);

                session.position += tickSpeed * session.direction;

                if (session.position >= barLength - 1) {
                    session.position = barLength - 1;
                    session.direction = -1;
                } else if (session.position <= 0) {
                    session.position = 0;
                    session.direction = 1;
                }

                int currentSlot = (int) Math.round(session.position);
                if (currentSlot >= barLength) {
                    currentSlot = barLength - 1;
                }

                StringBuilder bar = new StringBuilder(ThieveryTexts.DARK + "[");
                for (int i = 0; i < barLength; i++) {
                    if (i == currentSlot) {
                        bar.append(currentBar);
                    } else {
                        bar.append(switch (session.layout[i]) {
                            case 's' -> successBar;
                            case 'b' -> breakBar;
                            default -> failBar;
                        });
                    }
                }
                bar.append(ThieveryTexts.DARK).append("]");

                PlayerData thiefData = Thievery.getPlayerManager().get(uuid);
                thiefData.applyRiskDecay(session.dexterity);
                double risk = thiefData.getRisk();
                double critical = RiskCalculator.computeCritical(risk, session.dexterity, session.lockpickStrength);
                String riskTitle = RiskCalculator.formatRiskTitle(risk, critical);

                player.sendTitle(riskTitle, ThieveryTexts.formatDisplay(bar.toString()), 0, 3, 0);
            }
        };

        session.task = task;
        sessions.put(uuid, session);
        task.runTaskTimer(Thievery.getInstance(), 0L, 1L);
    }

    public SelectResult handleSelect(Player player) {
        UUID uuid = player.getUniqueId();
        LockpickSession session = sessions.get(uuid);
        if (session == null) {
            return SelectResult.NOT_IN_SESSION;
        }

        int slot = (int) Math.round(session.position);
        if (slot >= Parameters.barLength) {
            slot = Parameters.barLength - 1;
        }
        char type = session.layout[slot];

        boolean penalize = type != 's';
        cancelSession(uuid, penalize);

        return switch (type) {
            case 's' -> SelectResult.SUCCESS;
            case 'b' -> SelectResult.BREAK;
            default -> SelectResult.FAIL;
        };
    }

    public void cancelSession(UUID uuid) {
        cancelSession(uuid, false);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void cancelSession(UUID uuid, boolean penalize) {
        LockpickSession session = sessions.remove(uuid);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        if (penalize) {
            applyCooldown(uuid, session.targetId);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendTitle("", "", 0, 1, 0);
        }
    }

    public boolean isInSession(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public SessionKind getSessionKind(UUID uuid) {
        LockpickSession session = sessions.get(uuid);
        return session != null ? session.kind : null;
    }

    public String getSessionTargetId(UUID uuid) {
        LockpickSession session = sessions.get(uuid);
        return session != null ? session.targetId : null;
    }

    public boolean isOnCooldown(UUID uuid, Location doorLocation) {
        return isOnCooldown(uuid, DoorLockpick.doorTargetId(doorLocation));
    }

    public boolean isOnCooldown(UUID uuid, String targetId) {
        return getDebuffFactor(uuid, targetId) > 0;
    }

    public double getDebuffFactor(UUID uuid, Location doorLocation) {
        return getDebuffFactor(uuid, DoorLockpick.doorTargetId(doorLocation));
    }

    public double getDebuffFactor(UUID uuid, String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            return 0.0;
        }
        Map<String, Long> targetMap = cooldownExpiry.get(uuid);
        if (targetMap == null) {
            return 0.0;
        }
        Long expiry = targetMap.get(targetId);
        if (expiry == null) {
            return 0.0;
        }
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            return 0.0;
        }
        return remaining / (double) Parameters.lockpickFailCooldownMs;
    }

    public long getCooldownRemainingSeconds(UUID uuid, Location doorLocation) {
        return getCooldownRemainingSeconds(uuid, DoorLockpick.doorTargetId(doorLocation));
    }

    public long getCooldownRemainingSeconds(UUID uuid, String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            return 0;
        }
        Map<String, Long> targetMap = cooldownExpiry.get(uuid);
        if (targetMap == null) {
            return 0;
        }
        Long expiry = targetMap.get(targetId);
        if (expiry == null) {
            return 0;
        }
        return Math.max(0, (expiry - System.currentTimeMillis()) / 1000);
    }

    private void applyCooldown(UUID uuid, String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            return;
        }
        cooldownExpiry
                .computeIfAbsent(uuid, ignored -> new HashMap<>())
                .put(targetId, System.currentTimeMillis() + Parameters.lockpickFailCooldownMs);
    }

    public void clearCooldown(UUID uuid) {
        if (uuid != null) {
            cooldownExpiry.remove(uuid);
        }
    }

    public void clearAllCooldowns() {
        cooldownExpiry.clear();
    }
}
