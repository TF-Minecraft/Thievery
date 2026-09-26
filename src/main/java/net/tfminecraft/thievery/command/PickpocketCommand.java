package net.tfminecraft.thievery.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.player.PickpocketManager;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.player.TraitChecker;

public class PickpocketCommand implements CommandExecutor, TabCompleter {

    private final PickpocketManager pickpocketManager;

    public PickpocketCommand(PickpocketManager pickpocketManager) {
        this.pickpocketManager = pickpocketManager;
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

        if (args[0].equalsIgnoreCase("start")) {
            if (!TraitChecker.hasTraits(player, PickpocketLoader.getTraits())) {
                TraitChecker.sendMissingTraitMessage(player, "pickpocketing");
                return true;
            }
            pickpocketManager.startAwaitingTarget(player);
            return true;
        }

        sendUsage(player);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Pickpocket commands:"));
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "/pickpocket start" + ThieveryTexts.WHITE
                + " - Begin targeting a player"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("start".startsWith(prefix)) {
                options.add("start");
            }
            return options;
        }
        return Collections.emptyList();
    }
}
