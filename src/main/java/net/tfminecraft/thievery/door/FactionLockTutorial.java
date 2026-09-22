package net.tfminecraft.thievery.door;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
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

        TextComponent left = new TextComponent(dashes);
        left.setColor(ChatColor.GRAY);

        TextComponent gotIt = new TextComponent(GOT_IT_LABEL);
        gotIt.setColor(ChatColor.GREEN);
        gotIt.setBold(true);
        gotIt.setUnderlined(true);
        gotIt.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/thievery dismissfactionlock"));
        gotIt.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(new ComponentBuilder("Click to dismiss")
                        .color(ChatColor.GREEN)
                        .bold(true)
                        .append("\n")
                        .color(ChatColor.GRAY)
                        .italic(true)
                        .append("This can show again the next time you set a Faction lock.")
                        .create())));

        TextComponent right = new TextComponent(dashes);
        right.setColor(ChatColor.GRAY);

        TextComponent row = new TextComponent("");
        row.addExtra(left);
        row.addExtra(gotIt);
        row.addExtra(right);
        player.spigot().sendMessage(row);
    }
}
