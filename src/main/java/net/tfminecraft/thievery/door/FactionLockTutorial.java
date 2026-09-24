package net.tfminecraft.thievery.door;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class FactionLockTutorial {

    private static final String WARNING = "The faction lock is useful but also more risky as it allows thieves "
            + "to steal more at less risk to both discovery and lockpick break chance. "
            + "Use only when necessary and at your own risk";
    private static final String GOT_IT_LABEL = "[Got It]";
    private static final int SEPARATOR_WIDTH = 40;

    private static final Map<UUID, Long> lastSentAt = new ConcurrentHashMap<>();

    private FactionLockTutorial() {}

    public static void onLockState(Player player, LockState lockState) {
        if (player == null || lockState != LockState.FACTION) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (!FactionLockWarningGate.shouldSend(isDismissed(playerId), lastSentAt.get(playerId), now)) {
            return;
        }
        lastSentAt.put(playerId, now);
        send(player);
    }

    public static void dismiss(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerData data = Thievery.getPlayerManager().get(playerId);
        data.setFactionLockWarningDismissed(true);
        Database.savePlayerData(data);
        lastSentAt.remove(playerId);
    }

    private static boolean isDismissed(UUID playerId) {
        return Thievery.getPlayerManager().get(playerId).isFactionLockWarningDismissed();
    }

    private static void send(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "-".repeat(SEPARATOR_WIDTH)));
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + WARNING));

        int sideDashes = (SEPARATOR_WIDTH - GOT_IT_LABEL.length()) / 2;
        String dashes = "-".repeat(sideDashes);

        Component left = Component.text(dashes, NamedTextColor.GRAY);

        // ComponentBuilder carried bold into both the newline and the italic second line.
        Component hover = Component.text("Click to dismiss", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("\nThis warning will not show again.", NamedTextColor.GRAY)
                        .decorate(TextDecoration.BOLD, TextDecoration.ITALIC));
        Component gotIt = Component.text(GOT_IT_LABEL, NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/thievery dismissfactionlock"))
                .hoverEvent(HoverEvent.showText(hover));

        Component right = Component.text(dashes, NamedTextColor.GRAY);

        Component row = Component.empty().append(left).append(gotIt).append(right);
        player.sendMessage(row);
    }
}
