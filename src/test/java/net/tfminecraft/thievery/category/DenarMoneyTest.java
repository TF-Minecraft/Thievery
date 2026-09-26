package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class DenarMoneyTest {
    private ServerMock server;
    @BeforeEach void setUp() { server = MockBukkit.mock(); CategoryLoader.get().clear(); }
    @AfterEach void tearDown() { CategoryLoader.get().clear(); MockBukkit.unmock(); }

    @Test void absentAndDisabledIntegrationSafelyRejectsMoneyOperations() {
        var player = server.addPlayer();
        var item = new ItemStack(Material.GOLD_NUGGET);
        try (var economy = mockStatic(DenarEconomy.class); var transfers = mockStatic(MoneyManager.class)) {
            assertFalse(DenarMoney.present());
            assertNull(DenarMoney.coin(item));
            assertFalse(DenarMoney.isMoney(item));
            assertFalse(DenarMoney.canStealPouch(new PlayerData(UUID.randomUUID())));
            assertEquals(0, DenarMoney.getPouchBalance(player));
            DenarMoney.transferPouch(player, player, 5);
            var plugin = MockBukkit.createMockPlugin("DenarEconomy");
            server.getPluginManager().disablePlugin(plugin);
            assertFalse(DenarMoney.present());
            economy.verifyNoInteractions();
            transfers.verifyNoInteractions();
        }
    }

    @Test void coinsUseConfiguredMoneyConversionAndUnrecognizedItemsAreWorthZero() {
        MockBukkit.createMockPlugin("DenarEconomy");
        assertTrue(DenarMoney.present());
        var item = new ItemStack(Material.GOLD_NUGGET);
        Coin coin = mock(Coin.class);
        when(coin.getValue()).thenReturn(4.0);
        try (var economy = mockStatic(DenarEconomy.class, RETURNS_DEEP_STUBS)) {
            when(DenarEconomy.getMoneyManager().getCoin(item)).thenReturn(coin);
            assertNull(DenarMoney.coin(null));
            assertNull(DenarMoney.coin(new ItemStack(Material.AIR)));
            assertSame(coin, DenarMoney.coin(item));
            assertTrue(DenarMoney.isMoney(item));
            assertEquals(0, DenarMoney.amountPerMoney());
            assertEquals(0, DenarMoney.stealPerItem(item));
            money(0.25);
            assertEquals(0.25, DenarMoney.amountPerMoney());
            assertEquals(1, DenarMoney.stealPerItem(item));
            ItemStack ordinary = new ItemStack(Material.STICK);
            when(DenarEconomy.getMoneyManager().getCoin(ordinary)).thenReturn(null);
            assertEquals(0, DenarMoney.stealPerItem(ordinary));
        }
    }

    @Test void pouchPermissionRequiresAnActiveConfiguredMoneyCategoryAndBudgetsRoundDown() {
        MockBukkit.createMockPlugin("DenarEconomy");
        PlayerData player = new PlayerData(UUID.randomUUID());
        assertFalse(DenarMoney.canStealPouch(null));
        assertFalse(DenarMoney.canStealPouch(player));
        assertEquals(0, DenarMoney.maxStealableDenars(10));
        money(0.4);
        assertFalse(DenarMoney.canStealPouch(player));
        player.setActiveCategories(List.of("money"));
        assertTrue(DenarMoney.canStealPouch(player));
        assertEquals(2, DenarMoney.maxStealableDenars(1));
        assertEquals(0, DenarMoney.maxStealableDenars(0.39));
        assertEquals(3, DenarMoney.maxStealableDenars(1.2 + 1e-9));
        money(-1);
        assertEquals(0, DenarMoney.maxStealableDenars(10));
    }

    @Test void balancesAndTransfersUseTheTwoPlayersPouchAccounts() {
        MockBukkit.createMockPlugin("DenarEconomy");
        var from = server.addPlayer();
        var to = server.addPlayer();
        try (var economy = mockStatic(DenarEconomy.class, RETURNS_DEEP_STUBS);
                var transfers = mockStatic(MoneyManager.class)) {
            var fromPouch = DenarEconomy.getPlayerManager().get(from).getPouch();
            var toPouch = DenarEconomy.getPlayerManager().get(to).getPouch();
            when(fromPouch.getBal()).thenReturn(12.5);
            assertEquals(0, DenarMoney.getPouchBalance(null));
            assertEquals(12.5, DenarMoney.getPouchBalance(from));
            DenarMoney.transferPouch(null, to, 1);
            DenarMoney.transferPouch(from, null, 1);
            DenarMoney.transferPouch(from, to, 0);
            DenarMoney.transferPouch(from, to, -1);
            transfers.verifyNoInteractions();
            DenarMoney.transferPouch(from, to, 3.5);
            transfers.verify(() -> MoneyManager.transfer(fromPouch, toPouch, 3.5));
            transfers.verifyNoMoreInteractions();
        }
    }

    private void money(double conversion) {
        var config = new YamlConfiguration();
        config.set("type", "money"); config.set("amount_per_money", conversion);
        CategoryLoader.get().put("money", new ItemCategory("money", config));
    }
}
