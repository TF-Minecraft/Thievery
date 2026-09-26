package net.tfminecraft.thievery.command;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.clue.ClearCluesManager;
import net.tfminecraft.thievery.door.ContainerManager;
import net.tfminecraft.thievery.door.FactionLockTutorial;
import net.tfminecraft.thievery.player.CooldownResetService;
import net.tfminecraft.thievery.player.InventoryManager;
import net.tfminecraft.thievery.player.RiskSetService;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.key.KeychainHandler;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.player.TraitChecker;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final ContainerManager containerManager;
    private final InventoryManager inventoryManager;
    private final CooldownResetService cooldownResetService;
    private final RiskSetService riskSetService;
    private final ClearCluesManager clearCluesManager;

    public CommandManager(ContainerManager containerManager, InventoryManager inventoryManager,
            CooldownResetService cooldownResetService, RiskSetService riskSetService,
            ClearCluesManager clearCluesManager) {
        this.containerManager = containerManager;
        this.inventoryManager = inventoryManager;
        this.cooldownResetService = cooldownResetService;
        this.riskSetService = riskSetService;
        this.clearCluesManager = clearCluesManager;
    }

    private static void msg(CommandSender sender, String raw) {
        sender.sendMessage(ThieveryTexts.msg(raw));
    }

    // Existing configuration identifies offline profiles by player name, not UUID.
    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("dismissfactionlock")) {
            if (!(sender instanceof Player player)) {
                msg(sender, ThieveryTexts.ERROR + "This command can only be used by players.");
                return true;
            }
            FactionLockTutorial.dismiss(player);
            msg(player, ThieveryTexts.SUCCESS + "Got it.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("itemvalue")) {
            if (!sender.hasPermission("thievery.admin")) {
                msg(sender, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                msg(sender, ThieveryTexts.ERROR + "This command can only be used by players.");
                return true;
            }
            ItemStack item = player.getInventory().getItemInMainHand();
            for (String line : ItemValue.buildReport(item)) {
                player.sendMessage(line);
            }
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("thievery.admin")) {
                msg(sender, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            if (sender instanceof Player player) {
                Thievery.getInstance().reloadMessage(player);
            } else {
                Thievery.getInstance().reload();
                msg(sender, ThieveryTexts.SUCCESS + "[Thievery] " + ThieveryTexts.WARN + "Reloaded.");
            }
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("resetcooldowns")) {
            if (!sender.hasPermission("thievery.admin")) {
                msg(sender, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            if (args[1].equalsIgnoreCase("all")) {
                cooldownResetService.resetAll();
                msg(sender, ThieveryTexts.SUCCESS + "[Thievery] Reset cooldowns for " + ThieveryTexts.WARN
                        + "all players" + ThieveryTexts.SUCCESS + ".");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            cooldownResetService.resetForPlayer(target.getUniqueId());
            msg(sender, ThieveryTexts.SUCCESS + "[Thievery] Reset cooldowns for " + ThieveryTexts.WARN
                    + CooldownResetService.resolveTargetName(args[1]) + ThieveryTexts.SUCCESS + ".");
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("setrisk")) {
            if (!sender.hasPermission("thievery.admin")) {
                msg(sender, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            double risk;
            try {
                risk = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                msg(sender, ThieveryTexts.ERROR + "[Thievery] Invalid risk value. Use a number from "
                        + ThieveryTexts.WARN + "0.0 " + ThieveryTexts.ERROR + "to " + ThieveryTexts.WARN + "1.0"
                        + ThieveryTexts.ERROR + ".");
                return true;
            }
            if (!Double.isFinite(risk) || risk < 0.0 || risk > 1.0) {
                msg(sender, ThieveryTexts.ERROR + "[Thievery] Risk must be between " + ThieveryTexts.WARN + "0.0 "
                        + ThieveryTexts.ERROR + "and " + ThieveryTexts.WARN + "1.0" + ThieveryTexts.ERROR + ".");
                return true;
            }
            if (args[1].equalsIgnoreCase("all")) {
                riskSetService.setForAll(risk);
                msg(sender, ThieveryTexts.SUCCESS + "[Thievery] Set risk to " + ThieveryTexts.WARN
                        + RiskSetService.formatRisk(risk) + " " + ThieveryTexts.SUCCESS + "for " + ThieveryTexts.WARN
                        + "all players" + ThieveryTexts.SUCCESS + ".");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            riskSetService.setForPlayer(target.getUniqueId(), risk);
            msg(sender, ThieveryTexts.SUCCESS + "[Thievery] Set risk to " + ThieveryTexts.WARN
                    + RiskSetService.formatRisk(risk) + " " + ThieveryTexts.SUCCESS + "for " + ThieveryTexts.WARN
                    + CooldownResetService.resolveTargetName(args[1]) + ThieveryTexts.SUCCESS + ".");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("clearclues")) {
            if (!(sender instanceof Player player)) {
                msg(sender, ThieveryTexts.ERROR + "This command can only be used by players.");
                return true;
            }
            clearCluesManager.startAwaiting(player);
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, ThieveryTexts.ERROR + "This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            if (player.hasPermission("thievery.admin")) {
                msg(player, ThieveryTexts.WARN + "/thievery feedback " + ThieveryTexts.MUTED + "- Toggle feedback alerts");
                msg(player, ThieveryTexts.WARN + "/thievery reload " + ThieveryTexts.MUTED + "- Reload plugin config");
                msg(player, ThieveryTexts.WARN + "/thievery resetcooldowns <player|all> "
                        + ThieveryTexts.MUTED + "- Reset thievery cooldowns");
                msg(player, ThieveryTexts.WARN + "/thievery setrisk <player|all> <0.0-1.0> "
                        + ThieveryTexts.MUTED + "- Set thievery risk level");
                msg(player, ThieveryTexts.WARN + "/thievery itemvalue " + ThieveryTexts.MUTED + "- Inspect held item steal value");
                msg(player, ThieveryTexts.WARN + "/thievery keychain " + ThieveryTexts.MUTED + "- Get an empty keychain");
            }
            msg(player, ThieveryTexts.WARN + "/thievery loadout " + ThieveryTexts.MUTED + "- Open thievery category loadout");
            msg(player, ThieveryTexts.WARN + "/thievery clearclues " + ThieveryTexts.MUTED + "- Clear clues on a door or container");
            return true;
        }

        if (args[0].equalsIgnoreCase("loadout")) {
            if (!TraitChecker.hasRequiredTraits(player)) {
                TraitChecker.sendMissingTraitMessage(player);
                return true;
            }
            inventoryManager.openLoadout(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("feedback")) {
            if (!player.hasPermission("thievery.admin")) {
                msg(player, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            UUID uuid = player.getUniqueId();
            boolean current = containerManager.getFeedbackState(uuid);
            containerManager.setFeedbackState(uuid, !current);
            msg(player, ThieveryTexts.WARN + "[Thievery] Feedback "
                    + (!current ? ThieveryTexts.SUCCESS + "enabled" : ThieveryTexts.ERROR + "disabled"));
            return true;
        }

        if (args[0].equalsIgnoreCase("keychain")) {
            if (!player.hasPermission("thievery.admin")) {
                msg(player, ThieveryTexts.ERROR + "You don't have permission to use this command.");
                return true;
            }
            ItemStack keychain = KeychainHandler.createKeychain();
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(keychain);
            if (!leftovers.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), keychain);
            }
            msg(player, ThieveryTexts.SUCCESS + "[Thievery] " + ThieveryTexts.WARN + "Keychain given.");
            return true;
        }

        msg(player, ThieveryTexts.ERROR + "Unknown subcommand. Try " + ThieveryTexts.WARN + "/thievery loadout");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("loadout");
            options.add("clearclues");
            if (sender.hasPermission("thievery.admin")) {
                options.add("feedback");
                options.add("reload");
                options.add("resetcooldowns");
                options.add("setrisk");
                options.add("itemvalue");
                options.add("keychain");
            }
            return options;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("resetcooldowns") || args[0].equalsIgnoreCase("setrisk"))
                && sender.hasPermission("thievery.admin")) {
            List<String> options = new ArrayList<>();
            options.add("all");
            String prefix = args[1].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(prefix)) {
                    options.add(online.getName());
                }
            }
            return options.stream()
                    .filter(option -> option.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("setrisk")
                && sender.hasPermission("thievery.admin")) {
            List<String> options = new ArrayList<>();
            options.add("0");
            options.add("0.5");
            options.add("1");
            String prefix = args[2].toLowerCase();
            return options.stream()
                    .filter(option -> option.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
