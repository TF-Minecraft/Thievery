package net.tfminecraft.thievery.category;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.player.PlayerData;

public final class DenarMoney {

    private DenarMoney() {}

    public static boolean present() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("DenarEconomy");
        return plugin != null && plugin.isEnabled();
    }

    public static Coin coin(ItemStack stack) {
        if (!present() || stack == null || stack.getType().isAir()) {
            return null;
        }
        return DenarEconomy.getMoneyManager().getCoin(stack);
    }

    public static boolean isMoney(ItemStack stack) {
        return coin(stack) != null;
    }

    public static double amountPerMoney() {
        ItemCategory money = CategoryLoader.getMoneyCategory();
        return money != null ? money.getAmountPerMoney() : 0.0;
    }

    public static double stealPerItem(ItemStack stack) {
        Coin c = coin(stack);
        if (c == null) {
            return 0.0;
        }
        return c.getValue() * amountPerMoney();
    }

    public static boolean canStealPouch(PlayerData thiefData) {
        if (!present() || thiefData == null) {
            return false;
        }
        ItemCategory money = CategoryLoader.getMoneyCategory();
        return money != null && thiefData.isCategoryActive(money.getId());
    }

    public static double getPouchBalance(Player player) {
        if (!present() || player == null) {
            return 0.0;
        }
        return DenarEconomy.getPlayerManager().get(player).getPouch().getBal();
    }

    public static int maxStealableDenars(double budgetRemaining) {
        double per = amountPerMoney();
        if (per <= 0.0) {
            return 0;
        }
        return (int) Math.floor(budgetRemaining / per);
    }

    public static void transferPouch(Player from, Player to, double amount) {
        if (!present() || from == null || to == null || amount <= 0.0) {
            return;
        }
        var playerManager = DenarEconomy.getPlayerManager();
        MoneyManager.transfer(playerManager.get(from).getPouch(), playerManager.get(to).getPouch(), amount);
    }
}
