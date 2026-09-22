package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.door.DoorLockpick;
import net.tfminecraft.thievery.door.EntityLockData;
import net.tfminecraft.thievery.door.EntityLockDataManager;
import net.tfminecraft.thievery.door.FactionLockTutorial;
import net.tfminecraft.thievery.door.LockAccess;
import net.tfminecraft.thievery.door.LockPickManager;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.player.LockpickDefinition;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.utils.GuildChecker;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;

public class DisplayStealManager implements Listener {

    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    private final EntityLockDataManager lockDataManager = new EntityLockDataManager();
    private final LockPickManager lockPickManager;

    public DisplayStealManager(LockPickManager lockPickManager) {
        this.lockPickManager = lockPickManager;
    }

    static void notifyLockStateChange(Player player, LockState lockState) {
        String displayState = formatLockState(lockState);
        player.sendTitle(
                ThieveryTexts.msg(ThieveryTexts.ACCENT + "Lock State"),
                ThieveryTexts.msg(ThieveryTexts.WARN + displayState), 5, 30, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.0f);
        FactionLockTutorial.onLockState(player, lockState);
    }

    static void notifyStaffBypass(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Bypassing lock due to staff"));
    }

    static String formatLockState(LockState lockState) {
        String value = lockState.name().toLowerCase();
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    static boolean canUse(Player player, UUID owner, LockState lockState, boolean notifyBypass) {
        if (LockAccess.canAccess(player, owner, lockState)) {
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

    static void denyAccess(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You do not have access to this container."));
    }

    static void denyNotOwner(Player player) {
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                + "You can only change the lock state on containers you own."));
    }

    static boolean isLockableDisplay(Entity entity) {
        if (entity == null || !Parameters.isLockableEntityType(entity.getType())) {
            return false;
        }
        return !(entity instanceof ArmorStand stand) || !stand.isInvisible();
    }

    static void applyToggle(Player player, UUID owner, java.util.function.Consumer<UUID> claim,
            java.util.function.Supplier<LockState> rotate) {
        if (owner == null) {
            claim.accept(player.getUniqueId());
            notifyLockStateChange(player, LockState.DEFAULT);
            return;
        }
        if (owner.equals(player.getUniqueId())) {
            notifyLockStateChange(player, rotate.get());
            return;
        }
        denyNotOwner(player);
    }

    void handleLockpick(Player player, Entity entity, UUID owner, LockState lockState,
            List<DisplayLoot.DisplaySlot> slots) {
        if (player == null || entity == null || !entity.isValid()) {
            return;
        }
        String targetId = DoorLockpick.entityTargetId(entity.getUniqueId());
        UUID uuid = player.getUniqueId();
        if (lockPickManager.isInSession(uuid) && targetId.equals(lockPickManager.getSessionTargetId(uuid))) {
            handleSelectResult(player, entity, owner, slots, lockPickManager.handleSelect(player));
            return;
        }
        startLockpicking(player, entity, owner, lockState, slots);
    }

    private void startLockpicking(Player player, Entity entity, UUID owner, LockState lockState,
            List<DisplayLoot.DisplaySlot> slots) {
        if (!hasThiefTrait(player)) {
            return;
        }
        if (canUse(player, owner, lockState, false) && !Cache.debugAllowOwnChest) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You already have access to this container."));
            return;
        }
        if (!ClueChecker.hasEnoughClues(player)) {
            ClueChecker.sendInsufficientCluesMessage(player);
            return;
        }
        GuildChecker.LockpickAccessResult access = GuildChecker.checkLockpickAccess(owner);
        if (access.type == GuildChecker.LockpickAccessResult.Type.DENY) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + access.message));
            return;
        }
        if (access.type == GuildChecker.LockpickAccessResult.Type.WARN) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + access.message));
        }

        String targetId = DoorLockpick.entityTargetId(entity.getUniqueId());
        if (lockPickManager.isOnCooldown(player.getUniqueId(), targetId)) {
            long seconds = lockPickManager.getCooldownRemainingSeconds(player.getUniqueId(), targetId);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                    + "You must wait " + seconds + "s before lockpicking this again."));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        LockpickDefinition lockpickDef = ToolResolver.resolveLockpick(held);
        if (lockpickDef == null) {
            return;
        }
        double lockpickStrength = lockpickDef.getStrength();
        double requiredStrength = Parameters.displayLockStrength * Parameters.lockpickMinLockStrengthRatio;
        if (lockpickStrength < requiredStrength) {
            int requiredPercent = (int) Math.round(Parameters.lockpickMinLockStrengthRatio * 100);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your lockpick is too weak for this lock."
                    + " You need a pick at least " + requiredPercent + "% as strong as the lock."));
            return;
        }

        PlayerData thiefData = Thievery.getPlayerManager().get(player.getUniqueId());
        if (!DisplayLoot.hasAnything(slots, thiefData, lockpickDef.getCapacity())) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Nothing here is worth stealing."));
            return;
        }

        double debuffFactor = lockPickManager.getDebuffFactor(player.getUniqueId(), targetId);
        if (debuffFactor > 0) {
            int penalty = (int) Math.round(debuffFactor * 100);
            long seconds = lockPickManager.getCooldownRemainingSeconds(player.getUniqueId(), targetId);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Lockpicking with " + penalty + "% penalty (" + seconds + "s)"));
        }

        double effectiveStrength = Parameters.displayLockStrength
                * (1.0 - Math.min(1.0, lockpickStrength) * Parameters.lockpickMaxReduction);
        int dexterity = RiskCalculator.getDexterity(player);
        thiefData.addRiskGain(dexterity, lockpickStrength, RiskSource.DOOR);
        Database.savePlayerData(thiefData);

        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Walk away to cancel."));
        lockPickManager.startSession(player, new DoorLockpick.EntityProximityAnchor(entity),
                LockPickManager.SessionKind.DISPLAY, targetId, effectiveStrength, dexterity, lockpickStrength, null);
    }

    private void handleSelectResult(Player player, Entity entity, UUID owner, List<DisplayLoot.DisplaySlot> slots,
            LockPickManager.SelectResult result) {
        int dexterity = RiskCalculator.getDexterity(player);
        double lockpickStrength = ToolResolver.getLockpickStrength(player.getInventory().getItemInMainHand());
        org.bukkit.Location loc = entity.getLocation();
        switch (result) {
            case SUCCESS -> {
                LockpickDefinition def = ToolResolver.resolveLockpick(player.getInventory().getItemInMainHand());
                int capacity = def != null ? def.getCapacity() : 30;
                PlayerData thiefData = Thievery.getPlayerManager().get(player.getUniqueId());
                DisplayLoot.dump(player, slots, new StealBudget(capacity), thiefData);
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Picked!"), "", 5, 40, 10);
                loc.getWorld().playSound(loc, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.15f, 1.2f);
                ClueDropper.tryDropDoorClue(player, loc, owner, dexterity, lockpickStrength);
            }
            case FAIL -> {
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "Failed!"), "", 5, 40, 10);
                loc.getWorld().playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 4.5f, 0.8f);
                ClueDropper.tryDropDoorClue(player, loc, owner, dexterity, lockpickStrength);
            }
            case BREAK -> {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getAmount() > 1) {
                    held.setAmount(held.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "Lockpick broke!"), "", 5, 40, 10);
                loc.getWorld().playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 4.5f, 0.8f);
                loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 4.5f, 1f);
                ClueDropper.tryDropDoorClue(player, loc, owner, dexterity, lockpickStrength);
            }
            default -> {}
        }
    }

    private static boolean hasThiefTrait(Player player) {
        if (Cache.traits.isEmpty()) {
            return true;
        }
        net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
        if (pd == null || !pd.hasActiveCharacter()) {
            return false;
        }
        RPCharacter character = pd.getActiveCharacter();
        for (Trait trait : character.getTraits()) {
            if (Cache.traits.contains(trait.getId())) {
                return true;
            }
        }
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You lack the needed character trait(s) to lockpick!"));
        return false;
    }

    static List<DisplayLoot.DisplaySlot> slotsForEntity(Entity entity) {
        List<DisplayLoot.DisplaySlot> slots = new ArrayList<>();
        if (entity instanceof ArmorStand stand) {
            EntityEquipment equipment = stand.getEquipment();
            if (equipment == null) {
                return slots;
            }
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                slots.add(new ArmorStandSlot(stand, slot));
            }
            return slots;
        }
        if (entity instanceof ItemFrame frame) {
            slots.add(new ItemFrameSlot(frame));
        }
        return slots;
    }

    private static final class ArmorStandSlot implements DisplayLoot.DisplaySlot {
        private final ArmorStand stand;
        private final EquipmentSlot slot;

        ArmorStandSlot(ArmorStand stand, EquipmentSlot slot) {
            this.stand = stand;
            this.slot = slot;
        }

        @Override
        public ItemStack get() {
            return stand.getEquipment() != null ? stand.getEquipment().getItem(slot) : null;
        }

        @Override
        public boolean take(ItemStack taken) {
            EntityEquipment equipment = stand.getEquipment();
            if (equipment == null) {
                return false;
            }
            ItemStack current = equipment.getItem(slot);
            if (current == null || current.getType().isAir()) {
                return false;
            }
            if (current.getAmount() <= taken.getAmount()) {
                equipment.setItem(slot, null);
            } else {
                ItemStack remaining = current.clone();
                remaining.setAmount(current.getAmount() - taken.getAmount());
                equipment.setItem(slot, remaining);
            }
            return true;
        }
    }

    private static final class ItemFrameSlot implements DisplayLoot.DisplaySlot {
        private final ItemFrame frame;

        ItemFrameSlot(ItemFrame frame) {
            this.frame = frame;
        }

        @Override
        public ItemStack get() {
            return frame.getItem();
        }

        @Override
        public boolean take(ItemStack taken) {
            ItemStack current = frame.getItem();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            if (current.getAmount() <= taken.getAmount()) {
                frame.setItem(null);
            } else {
                ItemStack remaining = current.clone();
                remaining.setAmount(current.getAmount() - taken.getAmount());
                frame.setItem(remaining);
            }
            return true;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        Player player = event.getPlayer();
        if (player == null || !isLockableDisplay(entity)) {
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (data.getOwner() != null) {
            return;
        }
        data.setOwner(player.getUniqueId());
        data.setLockState(LockState.DEFAULT);
        lockDataManager.save(data);
        notifyLockStateChange(player, LockState.DEFAULT);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Entity entity = event.getEntity();
        Player player = event.getPlayer();
        if (player == null || !isLockableDisplay(entity)) {
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (data.getOwner() != null) {
            return;
        }
        data.setOwner(player.getUniqueId());
        data.setLockState(LockState.DEFAULT);
        lockDataManager.save(data);
        notifyLockStateChange(player, LockState.DEFAULT);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!isLockableDisplay(entity) || entity instanceof Hanging) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (player.isSneaking()) {
            event.setCancelled(true);
            applyToggle(player, data.getOwner(), owner -> {
                data.setOwner(owner);
                data.setLockState(LockState.DEFAULT);
                lockDataManager.save(data);
            }, () -> {
                LockState next = data.rotateLockState();
                lockDataManager.save(data);
                return next;
            });
            return;
        }
        if (!canUse(player, data.getOwner(), data.getLockState(), true)) {
            event.setCancelled(true);
            denyAccess(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Entity entity = event.getRightClicked();
        if (!isLockableDisplay(entity)) {
            return;
        }
        Player player = event.getPlayer();
        if (ToolResolver.isLockpick(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            EntityLockData data = lockDataManager.load(entity.getUniqueId());
            handleLockpick(player, entity, data.getOwner(), data.getLockState(), slotsForEntity(entity));
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (!canUse(player, data.getOwner(), data.getLockState(), true)) {
            event.setCancelled(true);
            denyAccess(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!isLockableDisplay(entity)) {
            return;
        }
        if (entity.getType() == EntityType.ARMOR_STAND) {
            return;
        }
        Player player = event.getPlayer();
        if (ToolResolver.isLockpick(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            EntityLockData data = lockDataManager.load(entity.getUniqueId());
            handleLockpick(player, entity, data.getOwner(), data.getLockState(), slotsForEntity(entity));
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (!canUse(player, data.getOwner(), data.getLockState(), false)) {
            event.setCancelled(true);
            denyAccess(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!isLockableDisplay(entity)) {
            return;
        }
        if (!(event.getRemover() instanceof Player player)) {
            return;
        }
        EntityLockData data = lockDataManager.load(entity.getUniqueId());
        if (player.isSneaking()) {
            event.setCancelled(true);
            applyToggle(player, data.getOwner(), owner -> {
                data.setOwner(owner);
                data.setLockState(LockState.DEFAULT);
                lockDataManager.save(data);
            }, () -> {
                LockState next = data.rotateLockState();
                lockDataManager.save(data);
                return next;
            });
            return;
        }
        if (!canUse(player, data.getOwner(), data.getLockState(), true)) {
            event.setCancelled(true);
            denyAccess(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSupportBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        for (Entity nearby : block.getWorld().getNearbyEntities(
                block.getLocation().add(0.5, 0.5, 0.5), 1.6, 1.6, 1.6)) {
            if (!(nearby instanceof Hanging hanging) || !Parameters.isLockableEntityType(hanging.getType())) {
                continue;
            }
            Block attached = hanging.getLocation().getBlock().getRelative(hanging.getAttachedFace());
            if (!attached.equals(block)) {
                continue;
            }
            EntityLockData data = lockDataManager.load(hanging.getUniqueId());
            if (!canUse(player, data.getOwner(), data.getLockState(), true)) {
                event.setCancelled(true);
                denyAccess(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLockableDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!isLockableDisplay(entity)) {
            return;
        }
        lockDataManager.delete(entity.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingRemoved(HangingBreakEvent event) {
        Entity entity = event.getEntity();
        if (!isLockableDisplay(entity)) {
            return;
        }
        lockDataManager.delete(entity.getUniqueId());
    }
}
