package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.player.TraitChecker;
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
import net.tfminecraft.thievery.utils.EvilRpPlays;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.thievery.utils.GuildChecker;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockOwnerNames;
import net.tfminecraft.thievery.owner.LockQuarantine;

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

            Component message = LegacyComponentSerializer.legacySection().deserialize(baseMessage);
            Component tpButton = clickableText("TP", tpCommand);
            p.sendMessage(Component.empty().append(message).append(tpButton));
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

            Component message = LegacyComponentSerializer.legacySection().deserialize(baseMessage);
            Component tpButton = LegacyComponentSerializer.legacySection()
                    .deserialize(ThieveryTexts.msg(ThieveryTexts.INFO + "[TP]"))
                    .clickEvent(ClickEvent.runCommand(tpCommand))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to player")));
            p.sendMessage(Component.empty().append(message).append(tpButton));
        });
    }


    private void notifyStaffBypass(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Bypassing lock due to staff"));
    }

    static void sendStarting(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + LockGuard.STARTING));
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
        if (!data.isOwned()) {
            return true;
        }
        return canAccessContainer(player, data, notifyBypass);
    }

    private boolean canAccessLockedContainer(Player player, ContainerData data) {
        return canAccessLockedContainer(player, data, true);
    }

    private boolean canAccessLockedDoubleChest(Player player, ContainerData left, ContainerData right, boolean notifyBypass) {
        boolean hasOwner = left.isOwned() || right.isOwned();
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

    /** In guard mode, a half that is owned and not PUBLIC keeps non-staff out. */
    private static boolean lockedDuringGuard(ContainerData... halves) {
        for (ContainerData half : halves) {
            if (half.isOwned() && half.getLockState() != LockState.PUBLIC) {
                return true;
            }
        }
        return false;
    }

    private Component clickableText(String label, String command) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(ThieveryTexts.msg(ThieveryTexts.INFO + "[" + label + "]"))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to thief")));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        LockGuard.failClosed(event, "a container break", () -> handleBlockBreak(event));
    }

    private void handleBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Only handle containers (chests, barrels, etc.)
        if (!(block.getState() instanceof Container)) return;

        Location location = block.getLocation();
        Player breaker = event.getPlayer();

        if (LockGuard.guarded()) {
            event.setCancelled(true);
            sendStarting(breaker);
            return;
        }

        ContainerData data = containerDataManager.loadContainerData(location);

        if (block.getState() instanceof Chest chest) {
            Inventory inventory = chest.getInventory();
            if (inventory instanceof DoubleChestInventory doubleChestInventory) {
                DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
                Chest leftChest = (Chest) doubleChest.getLeftSide();
                Chest rightChest = (Chest) doubleChest.getRightSide();

                ContainerData leftData = containerDataManager.loadContainerData(leftChest.getLocation());
                ContainerData rightData = containerDataManager.loadContainerData(rightChest.getLocation());
                if (!canAccessLockedDoubleChest(breaker, leftData, rightData)) {
                    event.setCancelled(true);
                    LockDenial.send(breaker, LockDenial.deniedState(breaker, leftData, rightData), false);
                    alertAdminsContainerBreak(breaker, location);
                    return;
                }
            }
        }

        // Prevent break if player cannot access this container
        if (!canAccessContainer(breaker, data)) {
            event.setCancelled(true);
            LockDenial.send(breaker, data.getLockState(), false);
            alertAdminsContainerBreak(breaker, location);
            return;
        }

        // Protection listeners may still cancel this event. Keep metadata until
        // the break completes, and preserve any container now at this location.
        Bukkit.getScheduler().runTaskLater(Thievery.getInstance(), () -> {
            if (!event.isCancelled() && !(block.getState() instanceof Container)) {
                containerDataManager.deleteContainerData(location);
            }
        }, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        LockGuard.failClosed(event, "a hopper transfer", () -> handleInventoryMove(event));
    }

    private void handleInventoryMove(InventoryMoveItemEvent event) {
        // Hoppers fire this every transfer tick, so avoid block-state snapshots here.
        Inventory initiator = event.getInitiator();
        if (initiator.getType() != InventoryType.HOPPER) {
            return;
        }
        InventoryHolder initiatorHolder = initiator.getHolder(false);
        if (initiatorHolder instanceof HopperMinecart) {
            // A hopper minecart has no owner, so it may not touch an owned, non-PUBLIC container.
            if (lockedForMinecart(event.getSource(), initiator) || lockedForMinecart(event.getDestination(), initiator)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!(initiatorHolder instanceof Hopper hopper)) {
            return;
        }
        LockOwner hopperOwner = containerDataManager.getOwner(hopper.getLocation());
        if (hopperOwner == null || hopperOwner.id() == null || LockGuard.guarded()) {
            event.setCancelled(true);
            return;
        }
        if (!isHopperTargetAllowed(event.getSource(), initiator, hopperOwner)
                || !isHopperTargetAllowed(event.getDestination(), initiator, hopperOwner)) {
            event.setCancelled(true);
        }
    }

    // In guard mode any owned container counts; otherwise an owned container that is not PUBLIC.
    private boolean lockedForMinecart(Inventory inv, Inventory minecartInventory) {
        if (inv == null || inv == minecartInventory) {
            return false;
        }
        for (ContainerDataManager.CachedLock half : locksOf(inv.getHolder(false))) {
            if (half.owner() != null && (LockGuard.guarded() || half.state() != LockState.PUBLIC)) {
                return true;
            }
        }
        return false;
    }

    // A block hopper may only move items to or from containers with exactly its owner; double chests check both
    // halves, and an unowned half of an owned chest goes with it.
    private boolean isHopperTargetAllowed(Inventory inv, Inventory hopperInventory, LockOwner hopperOwner) {
        if (inv == null || inv == hopperInventory) {
            return true;
        }
        List<ContainerDataManager.CachedLock> halves = locksOf(inv.getHolder(false));
        boolean matched = halves.isEmpty();
        for (ContainerDataManager.CachedLock half : halves) {
            if (half.owner() == null) {
                continue;
            }
            if (!hopperOwner.equals(half.owner())) {
                return false;
            }
            matched = true;
        }
        return matched;
    }

    // The locks of a container inventory's holder: one for a container, both halves for a double chest, none for
    // anything else (entity containers and plugin menus).
    private List<ContainerDataManager.CachedLock> locksOf(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            if (!(doubleChest.getLeftSide(false) instanceof Chest leftChest)
                    || !(doubleChest.getRightSide(false) instanceof Chest rightChest)) {
                return List.of(new ContainerDataManager.CachedLock(LockOwner.unreadable(), LockState.PRIVATE));
            }
            return List.of(containerDataManager.getLock(leftChest.getLocation()),
                    containerDataManager.getLock(rightChest.getLocation()));
        }
        if (holder instanceof Container container) {
            return List.of(containerDataManager.getLock(container.getLocation()));
        }
        return List.of();
    }

    // The owner a pick or clue is keyed to, read from both halves of a double chest.
    private LockOwner pickOwner(Inventory inv) {
        List<ContainerDataManager.CachedLock> locks = locksOf(inv.getHolder(false));
        if (locks.size() == 2) {
            return ChestOwners.of(locks.get(0).owner(), locks.get(1).owner()).effective();
        }
        return locks.getFirst().owner();
    }


    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory inv = event.getInventory();
        // Fires on every inventory click, so avoid block-state snapshots here.
        InventoryHolder holder = inv.getHolder(false);

        if (holder instanceof GuardView) {
            event.setCancelled(true);
            return;
        }

        Location location;

        if (holder instanceof DoubleChest doubleChest) {
            // Always use the left side as the primary location
            if (!(doubleChest.getLeftSide(false) instanceof Chest left)) return;
            location = left.getBlock().getLocation();
        } else if (holder instanceof Container container) {
            location = container.getBlock().getLocation();
        } else {
            return;
        }

        // Only care if they're taking items out
        if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT) {
            if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;

            // The cached owner skips the disk read for unowned containers.
            if (containerDataManager.getOwner(location) == null) return;

            ContainerData data = containerDataManager.loadContainerData(location);

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
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof GuardView) {
            event.setCancelled(true);
        }
    }


    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        LockGuard.failClosed(event, "a container open", () -> handleInventoryOpen(event));
    }

    private void handleInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof DoubleChest doubleChest) {
            Container left = (Container) doubleChest.getLeftSide();
            Container right = (Container) doubleChest.getRightSide();

            Location mainLoc = left.getBlock().getLocation();
            ContainerData mainData = containerDataManager.loadContainerData(mainLoc);
            Location rightLoc = right.getBlock().getLocation();
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            if (LockGuard.guarded()) {
                openDuringGuard(event, player, mainData, rightData);
                return;
            }

            if (!canAccessLockedDoubleChest(player, mainData, rightData)) {
                event.setCancelled(true);
                LockDenial.send(player, LockDenial.deniedState(player, mainData, rightData),
                        LockDenial.chestPickable(left.getBlock().getType()));
                return;
            }

            syncDoubleChest(player, mainData, rightData);

        } else if (holder instanceof Container container) {
            Location location = container.getBlock().getLocation();
            ContainerData data = containerDataManager.loadContainerData(location);
            if (LockGuard.guarded()) {
                openDuringGuard(event, player, data);
                return;
            }
            if (!canAccessLockedContainer(player, data)) {
                event.setCancelled(true);
                LockDenial.send(player, data.getLockState(), LockDenial.chestPickable(container.getBlock().getType()));
                return;
            }
            handleContainerAccess(data, player);
        }
    }

    // Guard mode: owned, non-PUBLIC containers stay shut to players and open read-only for staff. Nothing is claimed.
    private void openDuringGuard(InventoryOpenEvent event, Player player, ContainerData... halves) {
        if (!lockedDuringGuard(halves)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("thievery.admin")) {
            sendStarting(player);
            return;
        }
        Inventory view = GuardView.of(event.getInventory());
        Bukkit.getScheduler().runTask(Thievery.getInstance(), () -> player.openInventory(view));
    }

    /**
     * The open sync fills only an unowned half from the owned one, or claims a wholly unowned chest for the
     * opener's character. Halves with different owners are left alone and listed for staff.
     */
    private void syncDoubleChest(Player player, ContainerData left, ContainerData right) {
        ChestOwners owners = ChestOwners.of(left, right);
        // An unreadable half is never copied onto its partner; staff settle it with setowner.
        if (owners.diverged() || (owners.owner() != null && owners.owner().id() == null)) {
            LockQuarantine.noteDiverged(describe(left.getLocation()) + " / " + describe(right.getLocation()));
            return;
        }
        if (owners.owner() == null) {
            LockOwner claim = LockWriters.claimOwner(player);
            if (claim == null) {
                return;
            }
            left.setOwner(claim);
            right.setOwner(claim);
        } else if (!left.isOwned()) {
            left.setOwner(right.getOwner());
            left.setLockState(right.getLockState());
        } else if (!right.isOwned()) {
            right.setOwner(left.getOwner());
            right.setLockState(left.getLockState());
        }
        containerDataManager.saveContainerData(left);
        containerDataManager.saveContainerData(right);
    }

    static String describe(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " "
                + location.getBlockZ();
    }

    // First open of an unowned container claims it for the opener's character; without one it stays unowned.
    private void handleContainerAccess(ContainerData data, Player player) {
        if (data.isOwned()) {
            return;
        }
        LockOwner claim = LockWriters.claimOwner(player);
        if (claim != null) {
            data.setOwner(claim);
            containerDataManager.saveContainerData(data);
        }
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        LockGuard.failClosed(event, "a container placement", () -> handlePlace(event));
    }

    @SuppressWarnings("deprecation")
    private void handlePlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();

        if (!(block.getState() instanceof Container)) {
            return;
        }
        Player player = event.getPlayer();
        if (LockGuard.guarded()) {
            event.setCancelled(true);
            sendStarting(player);
            return;
        }

        LockOwner owner = LockWriters.placementOwner(player);
        if (wouldMergeWithUnownedSingleChest(block, owner)) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(
                    ThieveryTexts.ERROR + "You cannot connect this to a chest you do not own."));
            return;
        }
        if (owner == null) {
            // Without a current character the container is left unowned. A record left behind by a container that
            // was blown up or otherwise lost without a break event must not hand this one to its old owner.
            containerDataManager.deleteContainerData(block.getLocation());
            return;
        }

        ContainerData data = new ContainerData(block.getLocation(), owner);
        data.setLockState(LockWriters.initialState(owner));
        containerDataManager.saveContainerData(data);
        String displayState = formatLockState(data.getLockState());
        player.sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
    }

    private boolean wouldMergeWithUnownedSingleChest(Block placed, LockOwner placer) {
        if (!(placed.getState() instanceof Chest)) {
            return false;
        }
        Material type = placed.getType();
        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
            Block neighbor = placed.getRelative(face);
            if (neighbor.getType() != type) {
                continue;
            }
            Chest chest = (Chest) neighbor.getState();
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
        LockGuard.failClosed(event, "a lock rotation", () -> handleRotate(event));
    }

    private void handleRotate(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (!event.getPlayer().isSneaking()) return;
        if (event.getClickedBlock() == null) return;
        if (GraveManager.get().isGrave(event.getClickedBlock())) return;
        if (!(event.getClickedBlock().getState() instanceof Container container)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        if (LockGuard.guarded()) {
            sendStarting(player);
            return;
        }

        Inventory inventory = container.getInventory();

        if (inventory instanceof DoubleChestInventory doubleChestInventory) {
            DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();

            Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
            Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();

            ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            ChestOwners owners = ChestOwners.of(leftData, rightData);
            if (owners.diverged()) {
                LockQuarantine.noteDiverged(describe(leftLoc) + " / " + describe(rightLoc));
                player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + (player.hasPermission("thievery.admin")
                        ? "The halves of this chest have different owners. Use /thievery locks setowner first."
                        : LockDenial.NOT_OWNER)));
                return;
            }
            LockOwner owner = owners.owner();
            boolean ownsDoubleChest = LockAccess.isOwner(player, owner);
            boolean staffOverride = !ownsDoubleChest && owner != null && player.hasPermission("thievery.admin");
            if (!ownsDoubleChest && !staffOverride) {
                LockDenial.sendNotOwner(player);
                return;
            }
            if (leftData.isUnreadable() || rightData.isUnreadable()) {
                sendUnreadable(player);
                return;
            }

            LockState nextState = leftData.rotateLockState();
            rightData.setLockState(nextState);

            containerDataManager.saveContainerData(leftData);
            containerDataManager.saveContainerData(rightData);
            LockStateLog.record(player, event.getClickedBlock().getLocation(), nextState, staffOverride);

            if (staffOverride) {
                notifyStaffLockStateChange(player, owner, nextState);
            } else {
                notifyLockStateChange(player, nextState);
            }
            return;
        }

        Location location = container.getBlock().getLocation();
        ContainerData data = containerDataManager.loadContainerData(location);

        boolean owns = LockAccess.isOwner(player, data.getOwner());
        boolean staffOverride = !owns && data.isOwned() && player.hasPermission("thievery.admin");
        if (!owns && !staffOverride) {
            LockDenial.sendNotOwner(player);
            return;
        }
        if (data.isUnreadable()) {
            sendUnreadable(player);
            return;
        }

        LockState nextState = data.rotateLockState();
        containerDataManager.saveContainerData(data);
        LockStateLog.record(player, location, nextState, staffOverride);
        if (staffOverride) {
            notifyStaffLockStateChange(player, data.getOwner(), nextState);
        } else {
            notifyLockStateChange(player, nextState);
        }
    }

    static void sendUnreadable(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                + "This lock's record is unreadable. Use /thievery locks setowner to replace it."));
    }

    @EventHandler
    public void onContainerRightClickAccessCheck(PlayerInteractEvent event) {
        LockGuard.failClosed(event, "a container access check", () -> handleAccessCheck(event));
    }

    private void handleAccessCheck(PlayerInteractEvent event) {
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

            Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
            Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();
            ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
            ContainerData rightData = containerDataManager.loadContainerData(rightLoc);

            if (LockGuard.guarded()) {
                denyDuringGuard(event, player, leftData, rightData);
                return;
            }
            if (!canAccessLockedDoubleChest(player, leftData, rightData, false)) {
                event.setCancelled(true);
                LockDenial.send(player, LockDenial.deniedState(player, leftData, rightData),
                        LockDenial.chestPickable(event.getClickedBlock().getType()));
            }
            return;
        }

        Location location = container.getBlock().getLocation();
        ContainerData data = containerDataManager.loadContainerData(location);
        if (LockGuard.guarded()) {
            denyDuringGuard(event, player, data);
            return;
        }
        if (!canAccessLockedContainer(player, data, false)) {
            event.setCancelled(true);
            LockDenial.send(player, data.getLockState(), LockDenial.chestPickable(event.getClickedBlock().getType()));
        }
    }

    private static void denyDuringGuard(PlayerInteractEvent event, Player player, ContainerData... halves) {
        if (lockedDuringGuard(halves) && !player.hasPermission("thievery.admin")) {
            event.setCancelled(true);
            sendStarting(player);
        }
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void notifyLockStateChange(Player player, LockState lockState) {
        String displayState = formatLockState(lockState);
        player.sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.0f);
        FactionLockTutorial.onLockState(player, lockState);
    }

    // Staff keep the placer as owner, so guild and faction locks still follow the placer's
    // membership. Skip the faction tutorial because it describes the staff member's own faction.
    @SuppressWarnings("deprecation")
    private void notifyStaffLockStateChange(Player player, LockOwner owner, LockState lockState) {
        String displayState = formatLockState(lockState);
        player.sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.0f);
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Staff override: lock owned by "
                + LockOwnerNames.staffLabel(owner) + " is now " + displayState + "."));
    }

    private String formatLockState(LockState lockState) {
        String value = lockState.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void showParticleOutline(Player p, Location loc, Particle particle) {
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
        LockGuard.failClosed(event, "a container lockpick", () -> handleLockpickClick(event));
    }

    private void handleLockpickClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (GraveManager.get().isGrave(event.getClickedBlock())) return;
        if (!(event.getClickedBlock().getState() instanceof Container container)) return;

        Player player = event.getPlayer();
        // Only allow if player is holding a lockpick item
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!ToolResolver.isLockpick(heldItem)) return;
        if (Parameters.excludedContainerMaterials.contains(event.getClickedBlock().getType())) return;

        // Prevent opening container normally
        event.setCancelled(true);
        if (LockGuard.guarded()) {
            sendStarting(player);
            return;
        }

        if (!TraitChecker.hasLockpickingTraits(player)) return;

        // Only allow lockpicking containers the player cannot already access
        Block clickedBlock = event.getClickedBlock();
        Inventory blockInv = container.getInventory();
        if (!Cache.debugAllowOwnChest) {
            if (blockInv instanceof DoubleChestInventory doubleInv) {
                DoubleChest doubleChest = (DoubleChest) doubleInv.getHolder();
                Location leftLoc = ((Chest) doubleChest.getLeftSide()).getLocation();
                Location rightLoc = ((Chest) doubleChest.getRightSide()).getLocation();
                ContainerData leftData = containerDataManager.loadContainerData(leftLoc);
                ContainerData rightData = containerDataManager.loadContainerData(rightLoc);
                if (leftData.canAccess(player) && rightData.canAccess(player)) {
                    LockDenial.sendAlreadyOpen(player);
                    return;
                }
            } else {
                ContainerData data = containerDataManager.loadContainerData(clickedBlock.getLocation());
                if (data.canAccess(player)) {
                    LockDenial.sendAlreadyOpen(player);
                    return;
                }
            }
        }

        GuildChecker.LockpickAccessResult access = GuildChecker.checkLockpickAccess(pickOwner(blockInv));
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

    private void lockpickChest(PlayerInteractEvent e) {
        Block b = lockBlock(e.getClickedBlock());
        Player p = e.getPlayer();

        e.setCancelled(true);

        if (Thievery.getInstance().getLockMinigameManager().isPlaying(p.getUniqueId())) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You're already working a lock."));
            return;
        }
        if (isBeingPicked(b)) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.CRITICAL + "Someone is already picking this lock!"));
            return;
        }

        ItemStack heldLockpick = p.getInventory().getItemInMainHand();
        if (ToolResolver.resolveLockpick(heldLockpick) == null) return;

        if (onAccessCooldown(p, b)) {
            return;
        }

        // The probe menu opens only once the lock minigame is solved.
        Thievery.getInstance().getLockMinigameManager().start(p, b, mistakes -> {
            // A guildmate's pick nearby may have started the access cooldown while this lock was being worked.
            if (!onAccessCooldown(p, b)) {
                openLockpickSession(p, b, mistakes);
            }
        });
    }

    /** Tells the thief and returns true when they or their guild picked this container too recently. */
    private boolean onAccessCooldown(Player p, Block b) {
        ContainerData data = containerDataManager.loadContainerData(b.getLocation());
        if (!GuildAccessCooldown.isOnCooldown(data.getAccessMap(), p, Cache.cooldown)) {
            return false;
        }
        long millisRemaining = GuildAccessCooldown.getMillisRemaining(data.getAccessMap(), p, Cache.cooldown);
        p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You must wait "
                + GuildAccessCooldown.formatRemaining(millisRemaining)
                + " before attempting to lockpick this again."));
        return true;
    }

    /**
     * The block that stands for a container's lock. Both halves of a double chest are one lock, so they share the
     * left half: one fail cooldown, and one thief at a time.
     */
    static Block lockBlock(Block block) {
        if (block.getState() instanceof Container container
                && container.getInventory() instanceof DoubleChestInventory inventory
                && inventory.getHolder() instanceof DoubleChest doubleChest
                && doubleChest.getLeftSide() instanceof Chest left) {
            return left.getBlock();
        }
        return block;
    }

    /**
     * Staff testing: runs the lockpick ring on any container, even with the minigame off, skipping the trait, clue,
     * ownership and access checks, then opens the probe menu as a real pick would.
     */
    public void testPick(Player p, Block target) {
        if (LockGuard.guarded()) {
            sendStarting(p);
            return;
        }
        if (target == null || !(target.getState() instanceof Container)
                || Parameters.excludedContainerMaterials.contains(target.getType())) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Look at a container within 5 blocks."));
            return;
        }
        Block b = lockBlock(target);
        if (ToolResolver.resolveLockpick(p.getInventory().getItemInMainHand()) == null) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Hold a lockpick to test a pick."));
            return;
        }
        if (Thievery.getInstance().getLockMinigameManager().isPlaying(p.getUniqueId())) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You're already working a lock."));
            return;
        }
        if (isBeingPicked(b)) {
            p.sendMessage(ThieveryTexts.msg(ThieveryTexts.CRITICAL + "Someone is already picking this lock!"));
            return;
        }
        Thievery.getInstance().getLockMinigameManager().start(p, b, true, slips -> openLockpickSession(p, b, slips));
    }

    private boolean isBeingPicked(Block b) {
        for (ChestLockpickSession active : lockpickingSessions.values()) {
            if (active.getChestBlock().equals(b)) {
                return true;
            }
        }
        return Thievery.getInstance().getLockMinigameManager().isPicking(b);
    }

    private void openLockpickSession(Player p, Block b, int slips) {
        // The chest or the held lockpick may have changed while the minigame ran.
        if (!(b.getState() instanceof Container container)) return;
        LockpickDefinition lockpickDef = ToolResolver.resolveLockpick(p.getInventory().getItemInMainHand());
        if (lockpickDef == null) return;

        ContainerData data = containerDataManager.loadContainerData(b.getLocation());
        UUID playerId = p.getUniqueId();
        Inventory chestInv = container.getInventory();

        int dexterity = RiskCalculator.getDexterity(p);
        String targetKey = TargetKeyResolver.forLockOwner(pickOwner(chestInv));
        LockTypeProfile lockType = Parameters.lockTypeProfile(data.getLockState());
        int seizedCount = ChestLockpickSession.computeSeizedCount(dexterity, lockpickDef.getStrength(), lockType,
                chestInv.getSize(), slips);
        ChestLockpickSession session = new ChestLockpickSession(playerId, b, lockpickDef, seizedCount, chestInv,
                targetKey, lockType);
        lockpickingSessions.put(playerId, session);
        EvilRpPlays.record(p);

        ChestStealReference reference = new ChestStealReference(session, () -> lockpickingSessions.remove(playerId));
        String title = reference.buildTitle(p);
        Inventory lockpickInv = StealGui.buildHiddenGui(reference.getHolder(), session.getLayout(), title);

        String today = GuildAccessCooldown.today();
        pingNearbyContainers(p, b.getLocation(), today);
        recordContainerAccess(p, container, data, playerId, today);

        StealManager.getInstance().openSession(p, reference, lockpickInv);
    }

    private void recordContainerAccess(Player player, Container container, ContainerData data, UUID playerId, String today) {
        Inventory inventory = container.getInventory();
        if (inventory instanceof DoubleChestInventory doubleChestInventory) {
            DoubleChest doubleChest = (DoubleChest) doubleChestInventory.getHolder();
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();

            ContainerData leftData = containerDataManager.loadContainerData(leftChest.getLocation());
            ContainerData rightData = containerDataManager.loadContainerData(rightChest.getLocation());

            leftData.updateAccess(playerId, today);
            rightData.updateAccess(playerId, today);

            containerDataManager.saveContainerData(leftData);
            containerDataManager.saveContainerData(rightData);
        } else {
            data.updateAccess(playerId, today);
            containerDataManager.saveContainerData(data);
        }
    }
}
