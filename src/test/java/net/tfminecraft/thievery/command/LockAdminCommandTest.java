package net.tfminecraft.thievery.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.ContainerData;
import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.door.DoorData;
import net.tfminecraft.thievery.door.DoorDataManager;
import net.tfminecraft.thievery.door.EntityLockData;
import net.tfminecraft.thievery.door.EntityLockDataManager;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.CharacterNames;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.thievery.testsupport.FakeGuilds;
import net.tfminecraft.tlibs.character.CharacterStatus;

class LockAdminCommandTest {
    @TempDir Path temp;
    World world;
    Player staff;
    CommandSender console;
    Logger log;
    LockAdminCommand command;
    FactionFixture identity;
    MockedStatic<Thievery> plugin;
    ContainerDataManager containers;
    DoorDataManager doors;
    Location at;
    final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("locks");
        identity = new FactionFixture();
        var instance = mock(Thievery.class);
        when(instance.getDataFolder()).thenReturn(temp.toFile());
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        staff = mock(Player.class);
        when(staff.getName()).thenReturn("Mod");
        when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        when(staff.hasPermission("thievery.admin")).thenReturn(true);
        doAnswer(call -> said.add(call.getArgument(0))).when(staff).sendMessage(anyString());
        console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission("thievery.admin")).thenReturn(true);
        doAnswer(call -> said.add(call.getArgument(0))).when(console).sendMessage(anyString());
        log = mock(Logger.class);
        command = new LockAdminCommand(temp.resolve("logs/locks-audit.log"), log);
        containers = new ContainerDataManager();
        doors = new DoorDataManager();
        at = new Location(world, 100_000 + Math.abs(UUID.randomUUID().hashCode() % 50_000), 70, 3);
    }

    @AfterEach
    void tearDown() {
        containers.deleteContainerData(at);
        containers.deleteContainerData(at.clone().add(1, 0, 0));
        doors.deleteDoorData(at);
        plugin.close();
        identity.close();
        MockBukkit.unmock();
    }

    String last() {
        return said.getLast();
    }

    List<String> audit() throws Exception {
        return Files.readAllLines(temp.resolve("logs/locks-audit.log"));
    }

    @Test
    void onlyStaffMayUseItAndBadArgumentsGetTheUsage() {
        CommandSender player = mock(CommandSender.class);
        command.handle(player, new String[] { "quarantine" });
        verify(player).sendMessage("§cYou don't have permission to use this command.");
        for (String[] args : new String[][] { {}, {"setowner"}, {"rename", "x"} }) {
            command.handle(console, args);
            assertEquals("§c" + LockAdminCommand.USAGE, last());
        }
        command.handle(console, new String[] { "setowner", "server" });
        assertEquals("§cLook at the lock in game to set its owner.", last());
    }

    @Test
    void completionOffersTheSubcommandsAndServer() {
        assertEquals(List.of("quarantine", "setowner"), LockAdminCommand.complete(new String[] { "" }));
        assertEquals(List.of("setowner"), LockAdminCommand.complete(new String[] { "SET" }));
        assertEquals(List.of("server"), LockAdminCommand.complete(new String[] { "setowner", "s" }));
        assertEquals(List.of(), LockAdminCommand.complete(new String[] { "quarantine", "s" }));
        assertEquals(List.of(), LockAdminCommand.complete(new String[] { "setowner", "x", "y" }));
    }

    @Test
    void quarantineListsEverySectionWithPerOwnerCountsAndIsAudited() throws Exception {
        UUID account = UUID.randomUUID();
        LockQuarantine.Contents contents = new LockQuarantine.Contents();
        for (int i = 0; i < 10; i++) {
            contents.quarantined.add(new LockQuarantine.Entry("data/0_0/" + i + "_64_0.json", account.toString(), "no reviewed heir"));
        }
        contents.quarantined.add(new LockQuarantine.Entry("data/0_0/x.json", "not-a-uuid", "no reviewed heir"));
        contents.unreadable.add(new LockQuarantine.Entry("data/0_0/u.json", null, "unreadable: not JSON"));
        contents.missing.add(new LockQuarantine.Entry("door-data/0_0/m.json", account.toString(), "missing after replay"));
        LockQuarantine.replace(temp.resolve("migrations/quarantine.json"), contents);
        LockQuarantine.noteDiverged("locks 1 64 1 / locks 2 64 1");
        LockGuildBridge.set(new FakeGuilds());

        command.handle(console, new String[] { "QUARANTINE" });

        assertTrue(said.contains("§6[Thievery] Locks: §eFULL"));
        assertTrue(said.contains("§e11 quarantined, 1 unreadable, 1 missing after replay, 1 double chests with two owners."));
        assertTrue(said.stream().anyMatch(line -> line.contains("unresolved account") && line.contains(account.toString()) && line.endsWith(": 10")));
        assertTrue(said.contains("§7  not-a-uuid: 1"));
        assertTrue(said.contains("§6Quarantined:"));
        assertTrue(said.contains("§7  data/0_0/0_64_0.json (no reviewed heir)"));
        assertTrue(said.contains("§7  ...and 3 more in plugins/Thievery/migrations/quarantine.json"));
        assertTrue(said.contains("§7  data/0_0/u.json (unreadable: not JSON)"));
        assertTrue(said.contains("§7  door-data/0_0/m.json (missing after replay)"));
        assertTrue(said.contains("§7  locks 1 64 1 / locks 2 64 1 (two owners)"));
        assertFalse(said.stream().anyMatch(line -> line.contains("lockGuild is missing")));
        assertTrue(audit().getFirst().endsWith("CONSOLE listed the lock quarantine"));

        said.clear();
        LockQuarantine.reset();
        LockGuard.enter("restart required");
        LockGuildBridge.set(net.tfminecraft.thievery.owner.LockGuilds.UNAVAILABLE);
        command.handle(console, new String[] { "quarantine" });
        assertTrue(said.contains("§6[Thievery] Locks: §eGUARD§c (restart required)"));
        assertTrue(said.contains("§cGuild and faction locks deny everyone: SimpleFactions lockGuild is missing."));
        assertFalse(said.stream().anyMatch(line -> line.startsWith("§6Quarantined")));
    }

    @Test
    void setOwnerIsRefusedWhileLocksAreStartingUpOrWhenTheTargetIsUnknown() {
        LockGuard.enter("test");
        command.handle(staff, new String[] { "setowner", "server" });
        assertEquals("§c" + LockGuard.STARTING, last());
        LockGuard.forceFull();
        UUID stranger = UUID.randomUUID();
        identity.identity().when(() -> OwnerIdentity.info(stranger)).thenReturn(Optional.empty());
        command.handle(staff, new String[] { "setowner", stranger.toString() });
        assertEquals("§cNo character has the ID " + stranger + ".", last());
        try (var names = mockStatic(CharacterNames.class)) {
            command.handle(staff, new String[] { "setowner", "Kira" });
            assertEquals("§cCharacter names need TLibs 2.5.0. Use the character's UUID instead.", last());
            names.when(CharacterNames::available).thenReturn(true);
            names.when(() -> CharacterNames.find("Kira")).thenReturn(List.of());
            command.handle(staff, new String[] { "setowner", "Kira" });
            assertEquals("§cNo single living character is called Kira. Use the character's UUID instead.", last());
            var one = FactionFixture.info(UUID.randomUUID(), UUID.randomUUID(), CharacterStatus.ALIVE);
            names.when(() -> CharacterNames.find("Twins")).thenReturn(List.of(one, one));
            command.handle(staff, new String[] { "setowner", "Twins" });
            assertTrue(last().startsWith("§cNo single living character is called Twins"));
            // A UUID-shaped name that is not canonical is looked up as a name.
            names.when(() -> CharacterNames.find("1-1-1-1-1")).thenReturn(List.of());
            command.handle(staff, new String[] { "setowner", "1-1-1-1-1" });
            assertTrue(last().startsWith("§cNo single living character is called 1-1-1-1-1"));
        }
        command.handle(staff, new String[] { "setowner", "server" });
        assertEquals("§cLook at a container, locked door or lockable display within 5 blocks.", last());
    }

    @Test
    void setOwnerReplacesAnUnreadableContainerRecordAndClearsItsQuarantineListing() throws Exception {
        Block barrel = at.getBlock();
        barrel.setType(Material.BARREL);
        Path file = containers.getFileForLocation(at).toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        assertTrue(containers.loadContainerData(at).isUnreadable());
        when(staff.getTargetBlockExact(5)).thenReturn(barrel);
        UUID kira = UUID.randomUUID();

        command.handle(staff, new String[] { "setowner", kira.toString() });

        ContainerData data = containers.loadContainerData(at);
        assertEquals(LockOwner.character(kira), data.getOwner());
        assertEquals(LockState.PRIVATE, data.getLockState());
        assertTrue(last().contains("now belongs to"));
        assertTrue(last().contains("(was an unreadable record)"));
        assertTrue(LockQuarantine.snapshot().unreadable().isEmpty());
        assertTrue(audit().getFirst().contains("Mod set the owner of the container at locks " + at.getBlockX() + " 70 3 from an unreadable record to"));

        command.handle(staff, new String[] { "setowner", "SERVER" });
        assertEquals(LockOwner.server(), containers.loadContainerData(at).getOwner());
        try (var names = mockStatic(CharacterNames.class)) {
            UUID rook = UUID.randomUUID();
            names.when(CharacterNames::available).thenReturn(true);
            names.when(() -> CharacterNames.find("Rook")).thenReturn(List.of(FactionFixture.info(rook, UUID.randomUUID(), CharacterStatus.ALIVE)));
            command.handle(staff, new String[] { "setowner", "Rook" });
            assertEquals(LockOwner.character(rook), containers.loadContainerData(at).getOwner());
        }
    }

    @Test
    void setOwnerSetsBothHalvesOfADoubleChestAndClearsTheTwoOwnerListing() {
        Location right = at.clone().add(1, 0, 0);
        containers.saveContainerData(new ContainerData(at, LockOwner.character(UUID.randomUUID())));
        containers.saveContainerData(new ContainerData(right, LockOwner.character(UUID.randomUUID())));
        String pair = "locks " + at.getBlockX() + " 70 3 / locks " + right.getBlockX() + " 70 3";
        LockQuarantine.noteDiverged(pair);
        Block block = mock(Block.class);
        Chest left = mock(Chest.class);
        Chest rightChest = mock(Chest.class);
        DoubleChestInventory inventory = mock(DoubleChestInventory.class);
        DoubleChest holder = mock(DoubleChest.class);
        when(block.getState()).thenReturn(left);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getLocation()).thenReturn(at);
        when(left.getInventory()).thenReturn(inventory);
        when(left.getLocation()).thenReturn(at);
        when(rightChest.getLocation()).thenReturn(right);
        when(inventory.getHolder()).thenReturn(holder);
        when(holder.getLeftSide()).thenReturn(left);
        when(holder.getRightSide()).thenReturn(rightChest);
        when(staff.getTargetBlockExact(5)).thenReturn(block);

        command.handle(staff, new String[] { "setowner", "server" });

        assertEquals(LockOwner.server(), containers.loadContainerData(at).getOwner());
        assertEquals(LockOwner.server(), containers.loadContainerData(right).getOwner());
        assertTrue(LockQuarantine.snapshot().diverged().isEmpty());
        assertEquals(2, said.stream().filter(line -> line.contains("now belongs to §ethe server")).count());
    }

    @Test
    void setOwnerOnDoorsNeedsALockWithAReadableKey() throws Exception {
        Block door = at.getBlock();
        door.setType(Material.OAK_DOOR);
        when(staff.getTargetBlockExact(5)).thenReturn(door);
        command.handle(staff, new String[] { "setowner", "server" });
        assertEquals("§cThat door is not locked.", last());
        Path file = Path.of("plugins/Thievery/door-data").resolve((at.getBlockX() >> 4) + "_" + (at.getBlockZ() >> 4))
                .resolve(at.getBlockX() + "_70_3.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"ownerUUID\":\"nope\"}");
        command.handle(staff, new String[] { "setowner", "server" });
        assertTrue(last().startsWith("§cThis door's record has no readable key."));
        Files.writeString(file, "{\"key\":\"brass\",\"ownerUUID\":\"nope\"}");
        command.handle(staff, new String[] { "setowner", "server" });
        DoorData data = doors.loadDoorData(at);
        assertEquals("brass", data.getKey());
        assertEquals(LockOwner.server(), data.getOwner());
        assertTrue(last().contains("door at locks"));
    }

    @Test
    void setOwnerOnALockableDisplay() {
        var saved = Parameters.lockableEntityTypes;
        Parameters.lockableEntityTypes = java.util.EnumSet.of(EntityType.ARMOR_STAND);
        try {
            ArmorStand stand = mock(ArmorStand.class);
            UUID id = UUID.randomUUID();
            when(stand.getType()).thenReturn(EntityType.ARMOR_STAND);
            when(stand.getUniqueId()).thenReturn(id);
            when(staff.getTargetEntity(5)).thenReturn(stand);
            UUID kira = UUID.randomUUID();
            command.handle(staff, new String[] { "setowner", kira.toString() });
            EntityLockData data = new EntityLockDataManager().load(id);
            assertEquals(LockOwner.character(kira), data.getOwner());
            assertTrue(last().contains("display " + id));
        } finally {
            Parameters.lockableEntityTypes = saved;
        }
    }
}
