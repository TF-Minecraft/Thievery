package net.tfminecraft.thievery.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;

import net.tfminecraft.thievery.clue.ClearCluesResolver;
import net.tfminecraft.thievery.door.ContainerData;
import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.door.DoorData;
import net.tfminecraft.thievery.door.DoorDataManager;
import net.tfminecraft.thievery.door.EntityLockData;
import net.tfminecraft.thievery.door.EntityLockDataManager;
import net.tfminecraft.thievery.owner.CharacterNames;
import net.tfminecraft.thievery.owner.LockAudit;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockOwnerNames;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.steal.DisplayStealManager;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.tlibs.character.CharacterInfo;

/** {@code /thievery locks quarantine} and {@code /thievery locks setowner}, for {@code thievery.admin} only. */
public final class LockAdminCommand {

    static final String USAGE = "Usage: /thievery locks quarantine | /thievery locks setowner <character|uuid|server>";
    private static final int SHOWN = 8;

    private final Path auditFile;
    private final Logger log;
    private final ContainerDataManager containers = new ContainerDataManager();
    private final DoorDataManager doors = new DoorDataManager();

    public LockAdminCommand(Path auditFile, Logger log) {
        this.auditFile = auditFile;
        this.log = log;
    }

    /** {@code args} start after "locks". */
    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thievery.admin")) {
            msg(sender, ThieveryTexts.ERROR + "You don't have permission to use this command.");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("quarantine")) {
            quarantine(sender);
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("setowner")) {
            setOwner(sender, args[1]);
            return;
        }
        msg(sender, ThieveryTexts.ERROR + USAGE);
    }

    public static List<String> complete(String[] args) {
        if (args.length == 1) {
            return filter(List.of("quarantine", "setowner"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setowner")) {
            return filter(List.of("server"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }

    private void quarantine(CommandSender sender) {
        LockQuarantine.Snapshot snapshot = LockQuarantine.snapshot();
        audit(sender, "listed the lock quarantine");
        msg(sender, ThieveryTexts.ACCENT + "[Thievery] Locks: " + ThieveryTexts.WARN + LockGuard.mode()
                + (LockGuard.guarded() ? ThieveryTexts.ERROR + " (" + LockGuard.reason() + ")" : ""));
        if (LockGuildBridge.degraded()) {
            msg(sender, ThieveryTexts.ERROR + "Guild and faction locks deny everyone: SimpleFactions lockGuild is missing.");
        }
        msg(sender, ThieveryTexts.WARN + snapshot.quarantined().size() + " quarantined, " + snapshot.unreadable().size()
                + " unreadable, " + snapshot.missing().size() + " missing after replay, " + snapshot.diverged().size()
                + " double chests with two owners.");
        for (Map.Entry<String, Integer> owner : snapshot.perOwner().entrySet()) {
            msg(sender, ThieveryTexts.MUTED + "  " + ownerLabel(owner.getKey()) + ": " + owner.getValue());
        }
        list(sender, "Quarantined", snapshot.quarantined());
        list(sender, "Unreadable", snapshot.unreadable());
        list(sender, "Missing after replay", snapshot.missing());
        List<LockQuarantine.Entry> diverged = new ArrayList<>();
        snapshot.diverged().forEach(where -> diverged.add(new LockQuarantine.Entry(where, null, "two owners")));
        list(sender, "Two owners", diverged);
    }

    private static String ownerLabel(String stored) {
        try {
            return LockOwnerNames.staffLabel(LockOwner.unresolved(UUID.fromString(stored)));
        } catch (IllegalArgumentException e) {
            return stored;
        }
    }

    private static void list(CommandSender sender, String title, List<LockQuarantine.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        msg(sender, ThieveryTexts.ACCENT + title + ":");
        entries.stream().limit(SHOWN).forEach(entry -> msg(sender, ThieveryTexts.MUTED + "  " + entry.path()
                + " (" + entry.reason() + ")"));
        if (entries.size() > SHOWN) {
            msg(sender, ThieveryTexts.MUTED + "  ...and " + (entries.size() - SHOWN)
                    + " more in plugins/Thievery/migrations/quarantine.json");
        }
    }

    private void setOwner(CommandSender sender, String spec) {
        if (!(sender instanceof Player player)) {
            msg(sender, ThieveryTexts.ERROR + "Look at the lock in game to set its owner.");
            return;
        }
        if (LockGuard.guarded()) {
            msg(player, ThieveryTexts.ERROR + LockGuard.STARTING);
            return;
        }
        LockOwner owner = parseOwner(player, spec);
        if (owner == null) {
            return;
        }
        Block block = player.getTargetBlockExact(5);
        Optional<ClearCluesResolver.ClearCluesTarget> target = ClearCluesResolver.resolve(block);
        if (target.isPresent() && target.get().getKind() == ClearCluesResolver.Kind.DOOR) {
            setDoorOwner(player, target.get().getCanonicalLocation(), owner);
            return;
        }
        if (target.isPresent()) {
            setContainerOwner(player, (Container) block.getState(), owner);
            return;
        }
        Entity entity = player.getTargetEntity(5);
        if (DisplayStealManager.isLockableDisplay(entity)) {
            setEntityOwner(player, entity, owner);
            return;
        }
        msg(player, ThieveryTexts.ERROR + "Look at a container, locked door or lockable display within 5 blocks.");
    }

    private LockOwner parseOwner(Player player, String spec) {
        if (spec.equalsIgnoreCase("server")) {
            return LockOwner.server();
        }
        UUID id = parseUuid(spec);
        if (id != null) {
            if (OwnerIdentity.info(id).isEmpty()) {
                msg(player, ThieveryTexts.ERROR + "No character has the ID " + spec + ".");
                return null;
            }
            return LockOwner.character(id);
        }
        if (!CharacterNames.available()) {
            msg(player, ThieveryTexts.ERROR + "Character names need TLibs 2.5.0. Use the character's UUID instead.");
            return null;
        }
        List<CharacterInfo> matches = CharacterNames.find(spec);
        if (matches.size() != 1) {
            msg(player, ThieveryTexts.ERROR + "No single living character is called " + spec
                    + ". Use the character's UUID instead.");
            return null;
        }
        return LockOwner.character(matches.getFirst().ref().characterId());
    }

    private static UUID parseUuid(String spec) {
        try {
            UUID id = UUID.fromString(spec);
            return id.toString().equalsIgnoreCase(spec) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void setContainerOwner(Player player, Container container, LockOwner owner) {
        List<Location> halves = new ArrayList<>();
        if (container.getInventory() instanceof DoubleChestInventory inventory) {
            DoubleChest doubleChest = (DoubleChest) inventory.getHolder();
            halves.add(((Chest) doubleChest.getLeftSide()).getLocation());
            halves.add(((Chest) doubleChest.getRightSide()).getLocation());
            LockQuarantine.resolved(where(halves.get(0)) + " / " + where(halves.get(1)),
                    containers.getFileForLocation(halves.get(0)).toPath());
        } else {
            halves.add(container.getLocation());
        }
        for (Location half : halves) {
            ContainerData data = containers.loadContainerData(half);
            String before = LockOwnerNames.staffLabel(data.getOwner());
            data.setOwner(owner);
            containers.saveContainerData(data);
            LockQuarantine.resolved(where(half), containers.getFileForLocation(half).toPath());
            done(player, "container at " + where(half), before, owner);
        }
    }

    private void setDoorOwner(Player player, Location canonical, LockOwner owner) {
        DoorData data = doors.loadDoorData(canonical);
        if (data == null) {
            msg(player, ThieveryTexts.ERROR + "That door is not locked.");
            return;
        }
        if (data.getKey() == null) {
            msg(player, ThieveryTexts.ERROR + "This door's record has no readable key. Break the door in Creative to"
                    + " remove the lock.");
            return;
        }
        String before = LockOwnerNames.staffLabel(data.getOwner());
        data.setOwner(owner);
        doors.saveDoorData(data);
        done(player, "door at " + where(canonical), before, owner);
    }

    private void setEntityOwner(Player player, Entity entity, LockOwner owner) {
        EntityLockDataManager entities = new EntityLockDataManager();
        EntityLockData data = entities.load(entity.getUniqueId());
        String before = LockOwnerNames.staffLabel(data.getOwner());
        data.setOwner(owner);
        entities.save(data);
        done(player, "display " + entity.getUniqueId(), before, owner);
    }

    private void done(Player player, String what, String before, LockOwner owner) {
        String after = LockOwnerNames.staffLabel(owner);
        msg(player, ThieveryTexts.SUCCESS + "[Thievery] The " + what + " now belongs to " + ThieveryTexts.WARN + after
                + ThieveryTexts.SUCCESS + " (was " + before + ").");
        audit(player, "set the owner of the " + what + " from " + before + " to " + after);
    }

    private void audit(CommandSender sender, String action) {
        LockAudit.record(auditFile, log, sender.getName(), action);
    }

    private static String where(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " "
                + location.getBlockZ();
    }

    private static void msg(CommandSender sender, String raw) {
        sender.sendMessage(ThieveryTexts.msg(raw));
    }
}
