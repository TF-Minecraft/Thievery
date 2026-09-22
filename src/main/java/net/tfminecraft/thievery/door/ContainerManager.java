package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.ChestLockpickSession;
import net.tfminecraft.thievery.door.ContainerData;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.player.LockpickDefinition;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.steal.StealGuiHolder;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.thievery.steal.ChestStealReference;
import net.tfminecraft.thievery.steal.StealManager;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.GuildAccessCooldown;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.player.TargetKeyResolver;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.thievery.utils.GuildChecker;

public class ContainerManager implements Listener {

    private final ContainerDataManager containerDataManager = new ContainerDataManager();

    private final Map<UUID, Boolean> feedbackMap = new HashMap<>();
    private final Map<UUID, Long> lastAlertTimestamps = new HashMap<>();

    private final Map<UUID, ChestLockpickSession> lockpickingSessions = new HashMap<>();

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("thievery.admin")) {
            enableFeedback(player);
        }
    }

    public void enableFeedback(Player admin) {
        feedbackMap.put(admin.getUniqueId(), true);
    }

    public boolean getFeedbackState(UUID uuid) {
        return feedbackMap.getOrDefault(uuid, false);
    }

    public void setFeedbackState(UUID uuid, boolean value) {
        feedbackMap.put(uuid, value);
    }

    private void alertAdmins(Player thief, Location loc) {
        String baseMessage = ThieveryTexts.msg(ThieveryTexts.STAFF + "[Thievery] " + ThieveryTexts.ERROR + thief.getName()
                + " is taking from a chest they do not own at x"
                + loc.getBlockX() + " y" + loc.getBlockY() + " z" + loc.getBlockZ() + " ");

        String tpCommand = "/tp " + thief.getName();

        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!p.hasPermission("thievery.admin")) return;
            if (!feedbackMap.getOrDefault(p.getUniqueId(), false)) return;

            // Create the base message component
            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent(baseMessage);

            // Create the [TP] clickable component
            net.md_5.bungee.api.chat.TextComponent tpButton = new net.md_5.bungee.api.chat.TextComponent(
                    ThieveryTexts.msg(ThieveryTexts.INFO + "[TP]"));
            tpButton.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, tpCommand
            ));
            tpButton.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(new net.md_5.bungee.api.chat.ComponentBuilder("Click to teleport to thief").create())
            ));

            // Combine them
            message.addExtra(tpButton);

            // Send as one message
            p.spigot().sendMessage(message);
        });
    }

    private void alertAdminsContainerBreak(Player breaker, Location loc) {
        String baseMessage = ThieveryTexts.msg(ThieveryTexts.STAFF + "[Thievery] " + ThieveryTexts.ERROR + breaker.getName()
                + " broke a container they do not own at x"
                + loc.getBlockX() + " y" + loc.getBlockY() + " z" + loc.getBlockZ() + " ");

        String tpCommand = "/tp " + breaker.getName();

        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!p.hasPermission("thievery.admin")) return;
            if (!feedbackMap.getOrDefault(p.getUniqueId(), false)) return;

            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent(baseMessage);

            net.md_5.bungee.api.chat.TextComponent tpButton = new net.md_5.bungee.api.chat.TextComponent(
                    ThieveryTexts.msg(ThieveryTexts.INFO + "[TP]"));
            tpButton.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, tpCommand
            ));
            tpButton.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(new net.md_5.bungee.api.chat.ComponentBuilder("Click to teleport to player").create())
            ));

            message.addExtra(tpButton);
            p.spigot().sendMessage(message);
        });
    }


    private void notifyStaffBypass(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Bypassing lock due to staff"));
    }

    private boolean canAccessContainer(Player player, ContainerData data, boolean notifyBypass) {
        if (data.canAccess(player)) {
            return true;
        }
        if (player.hasPermission("thievery.admin")) {
            if (notifyBypass) {
                notifyStaffBypass(player);
            }
            return true;
        }
        return false;
    }

    private boolean canAccessContainer(Player player, ContainerData data) {
        return canAccessContainer(player, data, true);
    }

    private boolean canAccessLockedContainer(Player player, ContainerData data, boolean notifyBypass) {
        if (data.getOwner() == null) {
            return true;
        }
        return canAccessContainer(player, data, notifyBypass);
    }

    private boolean canAccessLockedContainer(Player player, ContainerData data) {
        return canAccessLockedContainer(player, data, true);
    }

    private boolean canAccessLockedDoubleChest(Player player, ContainerData left, ContainerData right, boolean notifyBypass) {
        boolean hasOwner = left.getOwner() != null || right.getOwner() != null;
        if (!hasOwner) {
            return true;
        }
        if (left.canAccess(player) && right.canAccess(player)) {
            return true;
        }
        if (player.hasPermission("thievery.admin")) {
            if (notifyBypass) {
                notifyStaffBypass(player);
            }
            return true;
        }
        return false;
    }

    private boolean canAccessLockedDoubleChest(Player player, ContainerData left, ContainerData right) {
        return canAccessLockedDoubleChest(player, left, right, true);
    }

    private net.md_5.bungee.api.chat.TextComponent clickableText(String label, String command) {
        net.md_5.bungee.api.chat.TextComponent tp = new net.md_5.bungee.api.chat.TextComponent(
                ThieveryTexts.msg(ThieveryTexts.INFO + "[" + label + "]"));
        tp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, command
        ));
        tp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(new net.md_5.bungee.api.chat.ComponentBuilder("Click to teleport to thief").create())
        ));
        return tp;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Only handle containers (chests, barrels, etc.)
        if (!(block.getState() instanceof Container)) return;

        Location location = block.getLocation();
        Player breaker = event.getPlayer();

        ContainerData data = containerDataManager.loadContainerData(location);

        if (block.getState() instanceof Chest chest) {
            Inventory inventory = chest.getInventory();
            if (inventory instanceof DoubleChestInventory doubleChestInventory) {
                DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
                if (doubleChest != null) {
                    if (!(doubleChest.getLeftSide() instanceof Chest leftChest)) return;
                    if (!(doubleChest.getRightSide() instanceof Chest rightChest)) return;

                    ContainerData leftData = containerDataManager.loadContainerData(leftChest.getLocation());
                    ContainerData rightData = containerDataManager.loadContainerData(rightChest.getLocation());
                    if (!canAccessLockedDoubleChest(breaker, leftData, rightData)) {
                        event.setCancelled(true);
                        breaker.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to break this container."));
                        alertAdminsContainerBreak(breaker, location);
                        return;
                    }
                }
            }
        }

        // Prevent break if player cannot access this container
        if (!canAccessContainer(breaker, data)) {
            event.setCancelled(true);
            breaker.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to break this container."));
            alertAdminsContainerBreak(breaker, location);
            return;
        }

        // Delete container data file
        containerDataManager.deleteContainerData(location);
    }

    @EventHandler
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!isBlockHopper(event.getInitiator())) {
            return;
        }
        UUID hopperOwner = getOwnerFromInventory(event.getInitiator());
        if (hopperOwner == null) {
            event.setCancelled(true);
            return;
        }
        if (isTrackedBlockContainer(event.getSource())) {
            UUID sourceOwner = getOwnerFromInventory(event.getSource());
            if (sourceOwner == null || !sourceOwner.equals(hopperOwner)) {
                event.setCancelled(true);
                return;
            }
        }
        if (isTrackedBlockContainer(event.getDestination())) {
            UUID destOwner = getOwnerFromInventory(event.getDestination());
            if (destOwner == null || !destOwner.equals(hopperOwner)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isBlockHopper(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Hopper;
    }

    private boolean isTrackedBlockContainer(Inventory inv) {
        if (inv == null) {
            return false;
        }
        InventoryHolder holder = inv.getHolder();
        return holder instanceof Container || holder instanceof DoubleChest;
    }

    private UUID getOwnerFromInventory(Inventory inv) {
        if (inv == null) {
            return null;
        }
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            if (!(doubleChest.getLeftSide() instanceof Chest leftChest)) {
                return null;
            }
            if (!(doubleChest.getRightSide() instanceof Chest rightChest)) {
                return null;
            }
            ContainerData left = containerDataManager.loadContainerData(leftChest.getLocation());
            if (left.getOwner() != null) {
                return left.getOwner();
            }
            return containerDataManager.loadContainerData(rightChest.getLocation()).getOwner();
        }
        if (holder instanceof Container container) {
            return containerDataManager.loadContainerData(container.getBlock().getLocation()).getOwner();
        }
        return null;
    }


    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();

        Location location;

        if (holder instanceof DoubleChest doubleChest) {
            // Always use the left side as the primary location
            location = ((Chest) doubleChest.getLeftSide()).getBlock().getLocation();
        } else if (holder instanceof Container container) {
            location = container.getBlock().getLocation();
        } else {
            return;
        }

        // Only care if they're taking items out
        if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT) {
            if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;

            ContainerData data = containerDataManager.loadContainerData(location);

            if (data.getOwner() == null) return;

            if (!data.canAccess(player) && !player.hasPermission("thievery.admin")) {
                long now = System.currentTimeMillis();
                long last = lastAlertTimestamps.getOrDefault(player.getUniqueId(), 0L);

                if (now - last >= 30_000) { // 30 seconds
                    alertAdmins(player, location);
                    lastAlertTimestamps.put(player.getUniqueId(), now);
                }
            }
        }
    }


    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof DoubleChest doubleChest) {
            Container left = (Container) doubleChest.getLeftSide();
            Container right = (Container) doubleChest.getRightSide();

            Location mainLoc = left.getBlock().getLocation();
            ContainerData mainData = containerDataManager.loadContainerData(mainLoc);
            Location rightLoc = right.getBlock().getLocation();
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            if (!canAccessLockedDoubleChest(player, mainData, rightData)) {
                event.setCancelled(true);
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to this container."));
                return;
            }

            if (mainData.getOwner() == null) {
                if (rightData.getOwner() != null) {
                    mainData.setOwner(rightData.getOwner());
                    mainData.setLockState(rightData.getLockState());
                } else {
                    mainData.setOwner(player.getUniqueId());
                }
            }

            containerDataManager.saveContainerData(mainData);

            rightData.setOwner(mainData.getOwner());
            rightData.setLockState(mainData.getLockState());
            containerDataManager.saveContainerData(rightData);

        } else if (holder instanceof Container container) {
            Location location = container.getBlock().getLocation();
            ContainerData data = containerDataManager.loadContainerData(location);
            if (!canAccessLockedContainer(player, data)) {
                event.setCancelled(true);
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to this container."));
                return;
            }
            handleContainerAccess(location, player);
        }
    }

    private void handleContainerAccess(Location location, Player player) {
        ContainerData data = containerDataManager.loadContainerData(location);

        // Assign owner if not set
        if (data.getOwner() == null) {
            data.setOwner(player.getUniqueId());
            containerDataManager.saveContainerData(data);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();

        if (!(block.getState() instanceof Container)) {
            return;
        }

        if (wouldMergeWithUnownedSingleChest(block, event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ThieveryTexts.msg(
                    ThieveryTexts.ERROR + "You cannot connect this to a chest you do not own."));
            return;
        }

        Location location = block.getLocation();
        ContainerData data = new ContainerData(location, event.getPlayer().getUniqueId());
        containerDataManager.saveContainerData(data);
        String displayState = formatLockState(data.getLockState());
        event.getPlayer().sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
    }

    private boolean wouldMergeWithUnownedSingleChest(Block placed, UUID placer) {
        if (!(placed.getState() instanceof Chest)) {
            return false;
        }
        Material type = placed.getType();
        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
            Block neighbor = placed.getRelative(face);
            if (neighbor.getType() != type) {
                continue;
            }
            if (!(neighbor.getState() instanceof Chest chest)) {
                continue;
            }
            if (chest.getInventory() instanceof DoubleChestInventory) {
                continue;
            }
            ContainerData neighborData = containerDataManager.loadContainerData(neighbor.getLocation());
            if (!ChestMergeRules.personallyOwns(placer, neighborData.getOwner())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onShiftLeftClickContainer(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (!event.getPlayer().isSneaking()) return;
        if (event.getClickedBlock() == null) return;
        if (GraveManager.get().isGrave(event.getClickedBlock())) return;
        if (!(event.getClickedBlock().getState() instanceof Container container)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        Inventory inventory = container.getInventory();
        UUID playerId = player.getUniqueId();

        if (inventory instanceof DoubleChestInventory doubleChestInventory) {
            DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
            if (doubleChest == null) return;

            Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
            Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();

            ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            UUID leftOwner = leftData.getOwner();
            UUID rightOwner = rightData.getOwner();
            boolean ownsDoubleChest = playerId.equals(leftOwner) || playerId.equals(rightOwner);
            if (!ownsDoubleChest) {
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You can only change the lock state on containers you own."));
                return;
            }

            LockState nextState = leftData.rotateLockState();
            rightData.setLockState(nextState);

            containerDataManager.saveContainerData(leftData);
            containerDataManager.saveContainerData(rightData);

            notifyLockStateChange(player, nextState);
            return;
        }

        Location location = container.getBlock().getLocation();
        ContainerData data = containerDataManager.loadContainerData(location);

        if (!playerId.equals(data.getOwner())) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You can only change the lock state on containers you own."));
            return;
        }

        LockState nextState = data.rotateLockState();
        containerDataManager.saveContainerData(data);
        notifyLockStateChange(player, nextState);
    }

    @EventHandler
    public void onContainerRightClickAccessCheck(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (GraveManager.get().isGrave(event.getClickedBlock())) return;
        if (!(event.getClickedBlock().getState() instanceof Container container)) return;

        Player player = event.getPlayer();
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (ToolResolver.isLockpick(heldItem)) {
            return;
        }

        Inventory inventory = container.getInventory();
        if (inventory instanceof DoubleChestInventory doubleChestInventory) {
            DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
            if (doubleChest == null) return;

            Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
            Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();
            ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            if (!canAccessLockedDoubleChest(player, leftData, rightData, false)) {
                event.setCancelled(true);
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to this container."));
            }
            return;
        }

        Location location = container.getBlock().getLocation();
        ContainerData data = containerDataManager.loadContainerData(location);
        if (!canAccessLockedContainer(player, data, false)) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to this container."));
        }
    }

    private void notifyLockStateChange(Player player, LockState lockState) {
        String displayState = formatLockState(lockState);
        player.sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.0f);
        FactionLockTutorial.onLockState(player, lockState);
    }

    private String formatLockState(LockState lockState) {
        String value = lockState.name().toLowerCase();
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void showParticleOutline(Player p, Location loc, Particle particle) {
        World world = loc.getWorld();
        if (world == null) return;

        double spacing = 0.1;
        double min = 0.0;
        double max = 1.0;

        // Use red particle
        Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.0F);
        Location base = loc.clone();

        // Top and bottom outlines
        for (double yOffset : List.of(0.0, 1.0)) {
            for (double i = min; i <= max; i += spacing) {
                p.spawnParticle(Particle.DUST, base.clone().add(i, yOffset, min), 1, 0, 0, 0, 0, red);
                p.spawnParticle(Particle.DUST, base.clone().add(i, yOffset, max), 1, 0, 0, 0, 0, red);
                p.spawnParticle(Particle.DUST, base.clone().add(min, yOffset, i), 1, 0, 0, 0, 0, red);
                p.spawnParticle(Particle.DUST, base.clone().add(max, yOffset, i), 1, 0, 0, 0, 0, red);
            }
        }

        // Vertical edges
        for (double y = min; y <= max; y += spacing) {
            p.spawnParticle(Particle.DUST, base.clone().add(min, y, min), 1, 0, 0, 0, 0, red);
            p.spawnParticle(Particle.DUST, base.clone().add(min, y, max), 1, 0, 0, 0, 0, red);
            p.spawnParticle(Particle.DUST, base.clone().add(max, y, min), 1, 0, 0, 0, 0, red);
            p.spawnParticle(Particle.DUST, base.clone().add(max, y, max), 1, 0, 0, 0, 0, red);
        }
    }

    private void pingNearbyContainers(Player player, Location origin, String date) {
        int radius = Cache.radius;
        if (radius < 0) {
            return;
        }
        UUID uuid = player.getUniqueId();

        Set<Location> alreadyPinged = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Location checkLoc = origin.clone().add(dx, dy, dz);

                    // Avoid pinging the same container twice
                    if (alreadyPinged.contains(checkLoc)) continue;

                    Block block = checkLoc.getBlock();
                    BlockState state = block.getState();

                    if (!(state instanceof Container container)) continue;

                    Inventory inv = container.getInventory();

                    // Handle double chest
                    if (inv instanceof DoubleChestInventory doubleInv) {
                        DoubleChest doubleChest = (DoubleChest) doubleInv.getHolder();

                        Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
                        Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();

                        // Mark both locations as pinged
                        alreadyPinged.add(leftLoc);
                        alreadyPinged.add(rightLoc);

                        ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
                        ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

                        leftData.updateAccess(uuid, date);
                        rightData.updateAccess(uuid, date);

                        containerDataManager.saveContainerData(leftData);
                        containerDataManager.saveContainerData(rightData);
                        showParticleOutline(player, leftLoc, Particle.DUST);
                        showParticleOutline(player, rightLoc, Particle.DUST);
                    } else {
                        // Single container
                        alreadyPinged.add(checkLoc);

                        ContainerData data = containerDataManager.loadContainerData(checkLoc);
                        data.updateAccess(uuid, date);
                        containerDataManager.saveContainerData(data);
                        showParticleOutline(player, checkLoc, Particle.DUST);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onRightClickChest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (GraveManager.get().isGrave(event.getClickedBlock())) return;
        if (!(event.getClickedBlock().getState() instanceof Container)) return;

        Player player = event.getPlayer();
        // Only allow if player is holding a lockpick item
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!ToolResolver.isLockpick(heldItem)) return;
        if (Parameters.excludedContainerMaterials.contains(event.getClickedBlock().getType())) return;

        // Prevent opening container normally
        event.setCancelled(true);

        if(Cache.traits.size() > 0) {
            net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
            if(!pd.hasActiveCharacter()) return;
            RPCharacter character = pd.getActiveCharacter();

            boolean hasTrait = false;
            for(Trait trait : character.getTraits()) {
                if(Cache.traits.contains(trait.getId())) hasTrait = true;
            }
            if(!hasTrait) {
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the needed character trait(s) to lockpick!"));
                return;
            }
        }

        // Only allow lockpicking containers the player cannot already access
        Block clickedBlock = event.getClickedBlock();
        Inventory blockInv = ((Container) clickedBlock.getState()).getInventory();
        if (!Cache.debugAllowOwnChest) {
            if (blockInv instanceof DoubleChestInventory doubleInv) {
                DoubleChest doubleChest = (DoubleChest) doubleInv.getHolder();
                if (doubleChest != null) {
                    Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
                    Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();
                    ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
                    ContainerData rightData = containerDataManager.loadContainerData(rightLoc);
                    if (leftData.canAccess(player) && rightData.canAccess(player)) {
                        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You already have access to this container."));
                        return;
                    }
                }
            } else {
                ContainerData data = containerDataManager.loadContainerData(clickedBlock.getLocation());
                if (data.canAccess(player)) {
                    player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You already have access to this container."));
                    return;
                }
            }
        }

        var ownerUUID = getContainerOwnerUUID(clickedBlock);
        GuildChecker.LockpickAccessResult access = GuildChecker.checkLockpickAccess(ownerUUID);
        if (access.type == GuildChecker.LockpickAccessResult.Type.DENY) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + access.message));
            return;
        }
        if (access.type == GuildChecker.LockpickAccessResult.Type.WARN) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + access.message));
        }

        if (!ClueChecker.hasEnoughClues(player)) {
            ClueChecker.sendInsufficientCluesMessage(player);
            return;
        }

        lockpickChest(event); // proceed to start the system
    }

    private UUID getContainerOwnerUUID(Block block) {
        if (!(block.getState() instanceof Container container)) {
            return null;
        }
        return getOwnerFromInventory(container.getInventory());
    }

    private void lockpickChest(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();

        e.setCancelled(true);

        for (ChestLockpickSession active : lockpickingSessions.values()) {
            if (active.getChestBlock().equals(b)) {
                p.sendMessage(ThieveryTexts.msg(ThieveryTexts.CRITICAL + "Someone is already lockpicking this container!"));
                return;
            }
        }

        ItemStack heldLockpick = p.getInventory().getItemInMainHand();
        LockpickDefinition lockpickDef = ToolResolver.resolveLockpick(heldLockpick);
        if (lockpickDef == null) return;

        Location chestLoc = b.getLocation();
        ContainerData data = containerDataManager.loadContainerData(chestLoc);
        UUID playerId = p.getUniqueId();

        if (GuildAccessCooldown.isOnCooldown(data.getAccessMap(), p, Cache.cooldown)) {
            long millisRemaining = GuildAccessCooldown.getMillisRemaining(data.getAccessMap(), p, Cache.cooldown);
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You must wait "
                    + GuildAccessCooldown.formatRemaining(millisRemaining)
                    + " before attempting to lockpick this container again."));
            return;
        }

        BlockState state = b.getState();
        if (!(state instanceof Container container)) return;

        Inventory chestInv = container.getInventory();

        int dexterity = RiskCalculator.getDexterity(p);
        double successChance = ChestLockpickSession.computeSuccessChance(dexterity, lockpickDef.getStrength());

        String targetKey = TargetKeyResolver.resolve(getContainerOwnerUUID(b));
        LockTypeProfile lockType = Parameters.lockTypeProfile(data.getLockState());
        ChestLockpickSession session = new ChestLockpickSession(playerId, b, lockpickDef, successChance, chestInv,
                targetKey, lockType);
        lockpickingSessions.put(playerId, session);

        ChestStealReference reference = new ChestStealReference(session, () -> lockpickingSessions.remove(playerId));
        String title = reference.buildTitle(p);
        Inventory lockpickInv = StealGui.buildHiddenGui(reference.getHolder(), session.getLayout(), title);

        String today = GuildAccessCooldown.today();
        pingNearbyContainers(p, b.getLocation(), today);
        recordContainerAccess(p, b, data, playerId, today);

        StealManager.getInstance().openSession(p, reference, lockpickInv);
    }

    private void recordContainerAccess(Player player, Block b, ContainerData data, UUID playerId, String today) {
        BlockState chestState = b.getState();
        if (!(chestState instanceof Container chest)) return;

        Inventory inventory = chest.getInventory();
        if (inventory instanceof DoubleChestInventory doubleChestInventory) {
            DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
            if (doubleChest != null) {
                Chest leftChest = (Chest) doubleChest.getLeftSide();
                Chest rightChest = (Chest) doubleChest.getRightSide();

                ContainerData leftData = containerDataManager.loadContainerData(leftChest.getLocation());
                ContainerData rightData = containerDataManager.loadContainerData(rightChest.getLocation());

                leftData.updateAccess(playerId, today);
                rightData.updateAccess(playerId, today);

                containerDataManager.saveContainerData(leftData);
                containerDataManager.saveContainerData(rightData);
            }
        } else {
            data.updateAccess(playerId, today);
            containerDataManager.saveContainerData(data);
        }
    }
}
