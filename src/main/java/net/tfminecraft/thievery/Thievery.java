package net.tfminecraft.thievery;

import java.io.File;

import org.bukkit.Bukkit;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.coreprotect.CoreProtect;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.command.CommandManager;
import net.tfminecraft.thievery.command.PickpocketCommand;
import net.tfminecraft.thievery.command.RobberyCommand;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.loader.ConfigLoader;
import net.tfminecraft.thievery.clue.ClearCluesManager;
import net.tfminecraft.thievery.door.ContainerManager;
import net.tfminecraft.thievery.player.CooldownResetService;
import net.tfminecraft.thievery.door.DoorManager;
import net.tfminecraft.thievery.player.InventoryManager;
import net.tfminecraft.thievery.key.KeyCopyListener;
import net.tfminecraft.thievery.key.KeychainListener;
import net.tfminecraft.thievery.door.LockPickManager;
import net.tfminecraft.thievery.player.PickpocketManager;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.player.RiskSetService;
import net.tfminecraft.thievery.robbery.RobberyManager;
import net.tfminecraft.thievery.steal.DisplayStealManager;
import net.tfminecraft.thievery.steal.FurnitureDisplayListener;
import net.tfminecraft.thievery.steal.GraveStealListener;
import net.tfminecraft.thievery.steal.StealGuiUpdater;
import net.tfminecraft.thievery.steal.StealManager;

public class Thievery extends JavaPlugin {

    private static Thievery instance;
    private ContainerManager containerManager;
    private DoorManager doorManager;
    private RobberyManager robberyManager;
    private PickpocketManager pickpocketManager;
    private LockPickManager lockPickManager;
    private StealManager stealManager;
    private StealGuiUpdater stealGuiUpdater;
    private final ConfigLoader configLoader = new ConfigLoader();
    private final CategoryLoader categoryLoader = new CategoryLoader();
    private final InventoryManager inventoryManager = new InventoryManager();
    private static final PlayerManager playerManager = new PlayerManager();

    @Override
    public void onEnable() {
        instance = this;
        createConfigs();
        loadConfigs();
        setPlugins();
        containerManager = new ContainerManager();
        stealManager = new StealManager();
        lockPickManager = new LockPickManager();
        doorManager = new DoorManager(lockPickManager);
        robberyManager = new RobberyManager();
        pickpocketManager = new PickpocketManager();
        stealGuiUpdater = new StealGuiUpdater(stealManager);
        stealGuiUpdater.start();
        CooldownResetService cooldownResetService = new CooldownResetService(lockPickManager);
        RiskSetService riskSetService = new RiskSetService();
        ClearCluesManager clearCluesManager = new ClearCluesManager();

        CommandManager commandManager = new CommandManager(containerManager, inventoryManager, cooldownResetService,
                riskSetService, clearCluesManager);
        getCommand("thievery").setExecutor(commandManager);
        getCommand("thievery").setTabCompleter(commandManager);
        getCommand("robbery").setExecutor(new RobberyCommand(robberyManager));
        getCommand("robbery").setTabCompleter(new RobberyCommand(robberyManager));
        getCommand("pickpocket").setExecutor(new PickpocketCommand(pickpocketManager));
        getCommand("pickpocket").setTabCompleter(new PickpocketCommand(pickpocketManager));

        getServer().getPluginManager().registerEvents(stealManager, this);
        getServer().getPluginManager().registerEvents(new GraveStealListener(), this);
        getServer().getPluginManager().registerEvents(clearCluesManager, this);
        getServer().getPluginManager().registerEvents(containerManager, this);
        getServer().getPluginManager().registerEvents(doorManager, this);
        getServer().getPluginManager().registerEvents(robberyManager, this);
        getServer().getPluginManager().registerEvents(pickpocketManager, this);
        getServer().getPluginManager().registerEvents(playerManager, this);
        getServer().getPluginManager().registerEvents(inventoryManager, this);
        getServer().getPluginManager().registerEvents(new KeychainListener(), this);
        getServer().getPluginManager().registerEvents(new KeyCopyListener(), this);
        DisplayStealManager displayStealManager = new DisplayStealManager(lockPickManager);
        getServer().getPluginManager().registerEvents(displayStealManager, this);
        if (Cache.interactibleFurniture) {
            getServer().getPluginManager().registerEvents(new FurnitureDisplayListener(displayStealManager), this);
        }

        playerManager.start();

        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.hasPermission("thievery.admin")) {
                containerManager.enableFeedback(player);
            }
        });

        getLogger().info("Thievery Plugin Enabled!");
        if (!net.tfminecraft.advancedcrafting.utils.ThieveryBridge.isPluginReady()) {
            getLogger().warning("AdvancedCrafting is not available; AC categories and valuation will be limited.");
        }
    }

    @Override
    public void onDisable() {
        if (stealGuiUpdater != null) {
            stealGuiUpdater.stop();
        }
        playerManager.unloadAll();
        playerManager.stop();
    }

    public void loadPlayers() {
        playerManager.loadAll();
    }

    public void reload() {
        playerManager.unloadAll();
        loadConfigs();
        setPlugins();
        playerManager.start();
    }

    public void reloadMessage(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "[Thievery]" + ThieveryTexts.WARN + " Reloading plugin..."));
        reload();
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "[Thievery]" + ThieveryTexts.WARN + " Reloading complete!"));
    }

    public static Thievery getInstance() {
        return instance;
    }

    public LockPickManager getLockPickManager() {
        return lockPickManager;
    }

    public ContainerManager getContainerManager() {
        return containerManager;
    }

    public static PlayerManager getPlayerManager() {
        return playerManager;
    }

    public void createConfigs() {
        String[] files = {
                "config.yml",
                "categories.yml"
        };
        for (String s : files) {
            File newConfigFile = new File(getDataFolder(), s);
            if (!newConfigFile.exists()) {
                newConfigFile.getParentFile().mkdirs();
                saveResource(s, false);
            }
        }
    }

    public void loadConfigs() {
        configLoader.loadConfig(new File(getDataFolder(), "config.yml"));
        categoryLoader.load(new File(getDataFolder(), "categories.yml"));
    }

    public void setPlugins() {
        Cache.coreProtect = false;
        Plugin plugin = getServer().getPluginManager().getPlugin("CoreProtect");

        if (plugin != null && plugin.isEnabled() && plugin instanceof CoreProtect) {
            Cache.coreProtect = true;
        }

        Plugin furniture = getServer().getPluginManager().getPlugin("InteractibleFurniture");
        Cache.interactibleFurniture = furniture != null && furniture.isEnabled();
    }

    public static CoreProtectAPI getCoreProtect() {
        Plugin coreProtect = getInstance().getServer().getPluginManager().getPlugin("CoreProtect");

        if (coreProtect == null || !(coreProtect instanceof CoreProtect)) {
            return null;
        }

        CoreProtectAPI CoreProtect = ((CoreProtect) coreProtect).getAPI();
        if (CoreProtect.isEnabled() == false) {
            return null;
        }

        if (CoreProtect.APIVersion() < 10) {
            return null;
        }

        return CoreProtect;
    }
}
