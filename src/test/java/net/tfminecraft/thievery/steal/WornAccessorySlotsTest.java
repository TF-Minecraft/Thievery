package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WornAccessorySlotsTest {

    // Main's live tfmc_inventory.yml layout: ring, amulet and two artifacts in the top bag row.
    static final String TFMC_INVENTORY = """
            id: tfmc_inventory
            type: vanilla
            enabled: true
            slots:
              RING:
                type: accessory
                material: DIAMOND_HOE
                name: '&6Ring Slot'
                slot: 9
              AMULET:
                type: accessory
                slot: 10
              ARTIFACT_1:
                type: accessory
                slot: 11
              ARTIFACT_2:
                type: accessory
                slot: 12
            """;

    @TempDir
    Path plugins;

    private final Logger logger = mock(Logger.class);

    @AfterEach
    void reset() {
        WornAccessorySlots.load(plugins.resolve("none").toFile(), logger);
    }

    @Test
    void loadsTheLiveVanillaLayoutAndLogsTheSlotsInOrder() throws Exception {
        write("tfmc_inventory.yml", TFMC_INVENTORY);

        WornAccessorySlots.load(plugins.toFile(), logger);

        for (int slot : new int[] {9, 10, 11, 12}) {
            assertTrue(WornAccessorySlots.isWorn(slot), "slot " + slot);
        }
        for (int slot : new int[] {0, 8, 13, 35}) {
            assertFalse(WornAccessorySlots.isWorn(slot), "slot " + slot);
        }
        verify(logger).info("Pickpocketing skips the worn MMOInventory slots [9, 10, 11, 12].");
    }

    @Test
    void readsOnlyEnabledVanillaInventoriesAndSkipsFillAndOutOfGridSlots() throws Exception {
        write("custom.yml", """
                type: custom
                enabled: true
                slots:
                  HELMET:
                    type: helmet
                    slot: 4
                """);
        write("disabled.yml", """
                type: vanilla
                enabled: false
                slots:
                  AMULET:
                    type: accessory
                    slot: 13
                """);
        write("no-type.yml", """
                slots:
                  RING:
                    slot: 14
                """);
        write("no-slots.yml", "type: vanilla\n");
        write("default-enabled.yml", """
                type: VANILLA
                slots:
                  RING:
                    type: accessory
                    slot: 20
                  FILL:
                    type: FILL
                    slot: 21
                  NOT_A_SECTION: 22
                  NO_INDEX:
                    type: accessory
                  NEGATIVE:
                    type: accessory
                    slot: -3
                  ARMOUR_ROW:
                    type: accessory
                    slot: 36
                  HOTBAR:
                    slot: 0
                  LAST:
                    slot: 35
                """);
        write("notes.txt", "type: vanilla\nslots:\n  RING:\n    slot: 30\n");

        assertEquals(List.of(0, 20, 35), List.copyOf(read()));
    }

    @Test
    void unreadableFilesAreSkippedWithAWarning() throws Exception {
        write("broken.YML", "slots: [unclosed\n");
        write("tfmc_inventory.yml", TFMC_INVENTORY);

        assertEquals(Set.of(9, 10, 11, 12), read());
        verify(logger).warning(startsWith("Could not read the MMOInventory inventory broken.YML"));
    }

    @Test
    void noMmoInventoryFolderMeansNothingIsProtectedAndNothingIsLogged() throws Exception {
        write("tfmc_inventory.yml", TFMC_INVENTORY);
        WornAccessorySlots.load(plugins.toFile(), logger);
        assertTrue(WornAccessorySlots.isWorn(9));
        clearInvocations(logger);

        WornAccessorySlots.load(plugins.resolve("elsewhere").toFile(), logger);

        assertFalse(WornAccessorySlots.isWorn(9));
        verifyNoInteractions(logger);
        assertTrue(WornAccessorySlots.read(new File(plugins.toFile(), "missing"), logger).isEmpty());
    }

    @Test
    void readResultCannotBeChanged() throws Exception {
        write("tfmc_inventory.yml", TFMC_INVENTORY);
        assertThrows(UnsupportedOperationException.class, () -> read().add(1));
    }

    private Set<Integer> read() {
        return WornAccessorySlots.read(plugins.resolve("MMOInventory/inventory").toFile(), logger);
    }

    private void write(String name, String yaml) throws Exception {
        writeInventory(plugins, name, yaml);
    }

    /** Writes an MMOInventory inventory file under {@code <plugins>/MMOInventory/inventory}. */
    static void writeInventory(Path plugins, String name, String yaml) throws Exception {
        Path folder = Files.createDirectories(plugins.resolve("MMOInventory/inventory"));
        Files.writeString(folder.resolve(name), yaml);
    }
}
