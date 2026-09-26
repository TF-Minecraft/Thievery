package net.tfminecraft.thievery.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.robbery.RobberyManager;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.player.TraitChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class RobberyCommand implements CommandExecutor, TabCompleter {

    private final RobberyManager robberyManager;

    public RobberyCommand(RobberyManager robberyManager) {
        this.robberyManager = robberyManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Only players can use this command."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("start")) {
            if (!TraitChecker.hasTraits(player, RobberyLoader.getTraits())) {
                TraitChecker.sendMissingTraitMessage(player, "robbery");
                return true;
            }
            robberyManager.startAwaitingTarget(player);
            return true;
        }

        if (sub.equals("accept")) {
            robberyManager.acceptRobbery(player);
            return true;
        }

        sendUsage(player);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Robbery commands:"));
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "/robbery start" + ThieveryTexts.WHITE
                + " - Begin targeting a player"));
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "/robbery accept" + ThieveryTexts.WHITE
                + " - Accept a robbery request"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : List.of("start", "accept")) {
                if (option.startsWith(prefix)) {
                    options.add(option);
                }
            }
            return options;
        }
        return Collections.emptyList();
    }
}
