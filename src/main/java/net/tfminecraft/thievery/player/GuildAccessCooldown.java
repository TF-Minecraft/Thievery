package net.tfminecraft.thievery.player;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

public final class GuildAccessCooldown {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private GuildAccessCooldown() {}

    public static String getMostRecentGuildAccess(Map<UUID, String> accessMap, Player player) {
        if (accessMap == null || player == null) {
            return null;
        }
        String best = accessMap.get(player.getUniqueId());
        Guild guild = FactionManager.getGuildByMember(player.getName());
        if (guild == null) {
            return best;
        }
        Date bestDate = null;
        try {
            if (best != null) {
                bestDate = DATE_FORMAT.parse(best);
            }
        } catch (ParseException ignored) {
        }
        for (String memberName : guild.getMembers()) {
            if (memberName.equalsIgnoreCase(player.getName())) {
                continue;
            }
            var member = Bukkit.getOfflinePlayer(memberName);
            String access = accessMap.get(member.getUniqueId());
            if (access == null) {
                continue;
            }
            try {
                Date date = DATE_FORMAT.parse(access);
                if (bestDate == null || date.after(bestDate)) {
                    bestDate = date;
                    best = access;
                }
            } catch (ParseException ignored) {
            }
        }
        return best;
    }

    public static boolean isOnCooldown(Map<UUID, String> accessMap, Player player, int cooldownDays) {
        String lastAccessDate = getMostRecentGuildAccess(accessMap, player);
        if (lastAccessDate == null) {
            return false;
        }
        try {
            Date lastAccess = DATE_FORMAT.parse(lastAccessDate);
            Duration duration = Duration.between(
                    lastAccess.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(),
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()));
            long daysSince = duration.toDays();
            if (daysSince >= cooldownDays) {
                return false;
            }
            return true;
        } catch (ParseException ex) {
            return false;
        }
    }

    public static long getMillisRemaining(Map<UUID, String> accessMap, Player player, int cooldownDays) {
        String lastAccessDate = getMostRecentGuildAccess(accessMap, player);
        if (lastAccessDate == null) {
            return 0;
        }
        try {
            Date lastAccess = DATE_FORMAT.parse(lastAccessDate);
            long millisElapsed = System.currentTimeMillis() - lastAccess.getTime();
            long totalCooldownMillis = cooldownDays * 24L * 60L * 60L * 1000L;
            return Math.max(0L, totalCooldownMillis - millisElapsed);
        } catch (ParseException ex) {
            return 0;
        }
    }

    public static void recordAccess(Map<UUID, String> accessMap, UUID attackerUuid, String date) {
        if (accessMap == null || attackerUuid == null || date == null) {
            return;
        }
        accessMap.put(attackerUuid, date);
    }

    public static long getMostRecentGuildAccessMillis(Map<UUID, String> accessMap, Player player) {
        if (accessMap == null || player == null) {
            return 0L;
        }
        long best = parseMillis(accessMap.get(player.getUniqueId()));
        Guild guild = FactionManager.getGuildByMember(player.getName());
        if (guild == null) {
            return best;
        }
        for (String memberName : guild.getMembers()) {
            if (memberName.equalsIgnoreCase(player.getName())) {
                continue;
            }
            var member = Bukkit.getOfflinePlayer(memberName);
            long access = parseMillis(accessMap.get(member.getUniqueId()));
            if (access > best) {
                best = access;
            }
        }
        return best;
    }

    public static boolean isOnCooldownMillis(Map<UUID, String> accessMap, Player player, long cooldownMillis) {
        long lastAccess = getMostRecentGuildAccessMillis(accessMap, player);
        if (lastAccess <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastAccess < cooldownMillis;
    }

    public static long getMillisRemainingMillis(Map<UUID, String> accessMap, Player player, long cooldownMillis) {
        long lastAccess = getMostRecentGuildAccessMillis(accessMap, player);
        if (lastAccess <= 0L) {
            return 0L;
        }
        return Math.max(0L, cooldownMillis - (System.currentTimeMillis() - lastAccess));
    }

    public static void recordAccessMillis(Map<UUID, String> accessMap, UUID attackerUuid, long epochMs) {
        if (accessMap == null || attackerUuid == null) {
            return;
        }
        accessMap.put(attackerUuid, Long.toString(epochMs));
    }

    private static long parseMillis(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public static String today() {
        return DATE_FORMAT.format(new Date());
    }

    public static String formatRemaining(long millisRemaining) {
        long daysRemaining = millisRemaining / (24L * 60L * 60L * 1000L);
        long hours = (millisRemaining / (1000L * 60L * 60L)) % 24L;
        long minutes = (millisRemaining / (1000L * 60L)) % 60L;
        return daysRemaining + " day(s), " + hours + " hour(s), and " + minutes + " minute(s)";
    }
}
