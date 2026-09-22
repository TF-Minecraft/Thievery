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
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class FactionLockTutorial {

    private static final String WARNING = "The faction lock is useful but also more risky as it allows thieves "
            + "to steal more at less risk to both discovery and lockpick break chance. "
            + "Use only when necessary and at your own risk";
    private static final String GOT_IT_LABEL = "[Got It]";
    private static final int SEPARATOR_WIDTH = 40;
    private static final long MIN_GAP_MS = 30_000L;

    private static final Map<UUID, Long> lastSentAt = new ConcurrentHashMap<>();

    private FactionLockTutorial() {}

    public static void onLockState(Player player, LockState lockState) {
        if (player == null || lockState != LockState.FACTION) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastSentAt.get(player.getUniqueId());
        if (previous != null && now - previous < MIN_GAP_MS) {
            return;
        }
        lastSentAt.put(player.getUniqueId(), now);
        send(player);
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
                .append(Component.text("\nThis can show again the next time you set a Faction lock.", NamedTextColor.GRAY)
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
