package net.tfminecraft.thievery.key;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.key.KeyCopyHandler.CopyMetadata;
import net.tfminecraft.thievery.loader.KeyCopyLoader;
import net.tfminecraft.thievery.loader.KeyLoader;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class KeyCopyHandlerTest {
    private MockedStatic<Thievery> thievery;
    private MockedStatic<KeyLoader> keys;
    private MockedStatic<KeyCopyLoader> copies;
    private MockedStatic<TLibs> libs;
    private KeyDefinition definition;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery plugin = mock(Thievery.class);
        when(plugin.namespace()).thenReturn("thievery");
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
        var config = new YamlConfiguration();
        config.set("strength", 0.75);
        definition = new KeyDefinition("copper", config);
        keys = mockStatic(KeyLoader.class);
        keys.when(() -> KeyLoader.resolve(any())).thenAnswer(invocation -> {
            ItemStack item = invocation.getArgument(0);
            return item != null && item.getType() == Material.GOLD_NUGGET ? definition : null;
        });
        copies = mockStatic(KeyCopyLoader.class);
        copies.when(KeyCopyLoader::getMoldOutput).thenReturn("mold");
        copies.when(KeyCopyLoader::getCopyOutput).thenReturn("copy");
        copies.when(KeyCopyLoader::getPaperOutput).thenReturn("paper");
        copies.when(KeyCopyLoader::getPaperCooldownMinutes).thenReturn(15);
        copies.when(() -> KeyCopyLoader.matchesMoldOutput(any())).thenAnswer(i -> ((ItemStack) i.getArgument(0)).getType() == Material.CLAY_BALL);
        copies.when(() -> KeyCopyLoader.matchesCopyOutput(any())).thenAnswer(i -> ((ItemStack) i.getArgument(0)).getType() == Material.COPPER_INGOT);
        copies.when(() -> KeyCopyLoader.matchesPaperOutput(any())).thenAnswer(i -> ((ItemStack) i.getArgument(0)).getType() == Material.PAPER);
        libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
    }

    @AfterEach
    void tearDown() {
        libs.close();
        copies.close();
        keys.close();
        thievery.close();
        MockBukkit.unmock();
    }

    @Test
    void unusedMasterKeyReceivesStableUuidAndDefinitionMetadata() {
        ItemStack master = new ItemStack(Material.GOLD_NUGGET);
        assertTrue(KeyCopyHandler.isMasterKey(master));
        assertTrue(ToolResolver.isMasterKey(master));
        assertTrue(ToolResolver.isLockingKey(master));
        assertEquals(0.75, ToolResolver.getKeyStrength(master));
        assertTrue(KeyCopyHandler.isStorableDoorKey(master));
        assertNull(KeyCopyHandler.extractCopyMetadata(master));
        assertTrue(KeyCopyHandler.ensureKeyInitialized(master));
        String uuid = KeyCopyHandler.getDoorKeyUuid(master);
        assertNotNull(UUID.fromString(uuid));
        assertTrue(KeyCopyHandler.ensureKeyInitialized(master));
        assertEquals(uuid, KeyCopyHandler.getDoorKeyUuid(master));
        CopyMetadata metadata = KeyCopyHandler.extractCopyMetadata(master);
        assertEquals(uuid, metadata.getDoorKeyUuid());
        assertEquals(0.75, metadata.getSourceStrength());
        assertEquals("copper", metadata.getSourceKeyId());
        assertFalse(KeyCopyHandler.ensureKeyInitialized(new ItemStack(Material.STONE)));
    }

    @Test
    void emptyAndUnmarkedItemsDoNotQualifyAsKeysOrMetadataSources() {
        for (ItemStack item : new ItemStack[] {null, new ItemStack(Material.AIR), new ItemStack(Material.STONE)}) {
            assertFalse(KeyCopyHandler.isMasterKey(item));
            assertFalse(KeyCopyHandler.isMold(item));
            assertFalse(KeyCopyHandler.isPermanentCopy(item));
            assertFalse(KeyCopyHandler.isPaperCopy(item));
            assertFalse(KeyCopyHandler.isCopyItem(item));
            assertFalse(KeyCopyHandler.isStorableDoorKey(item));
            assertFalse(ToolResolver.isKey(item));
            assertFalse(ToolResolver.isLockingKey(item));
            assertEquals(0, ToolResolver.getKeyStrength(item));
            assertNull(KeyCopyHandler.getDoorKeyUuid(item));
            assertEquals(0, KeyCopyHandler.getSourceStrength(item));
            assertNull(KeyCopyHandler.getSourceKeyId(item));
            assertNull(KeyCopyHandler.extractCopyMetadata(item));
        }
        ItemStack named = new ItemStack(Material.STONE);
        named.editMeta(meta -> meta.setDisplayName("Ordinary stone"));
        assertFalse(KeyCopyHandler.isMold(named));
        assertFalse(KeyCopyHandler.isPermanentCopy(named));
        assertFalse(KeyCopyHandler.isPaperCopy(named));
        assertEquals(0, KeyCopyHandler.getSourceStrength(named));
        assertNull(KeyCopyHandler.getSourceKeyId(named));
    }

    @Test
    void vanillaCraftingMaterialsWithoutMetadataCannotImpersonateCopiesOrMolds() {
        for (Material material : new Material[] {Material.CLAY_BALL, Material.COPPER_INGOT, Material.PAPER}) {
            ItemStack vanilla = mock(ItemStack.class);
            when(vanilla.getType()).thenReturn(material);
            when(vanilla.hasItemMeta()).thenReturn(false);
            assertFalse(KeyCopyHandler.isMold(vanilla));
            assertFalse(KeyCopyHandler.isPermanentCopy(vanilla));
            assertFalse(KeyCopyHandler.isPaperCopy(vanilla));
            assertFalse(KeyCopyHandler.isStorableDoorKey(vanilla));
            verify(vanilla, never()).getItemMeta();
        }
        ItemStack freshMaster = mock(ItemStack.class);
        when(freshMaster.getType()).thenReturn(Material.GOLD_NUGGET);
        when(freshMaster.hasItemMeta()).thenReturn(false);
        assertTrue(KeyCopyHandler.isMasterKey(freshMaster));
        verify(freshMaster, never()).getItemMeta();
    }

    @Test
    void factoriesPreserveSourceIdentityAndStrengthWithoutMutatingTemplates() {
        ItemStack moldTemplate = new ItemStack(Material.CLAY_BALL, 8);
        ItemStack copyTemplate = new ItemStack(Material.COPPER_INGOT, 12);
        ItemStack paperTemplate = new ItemStack(Material.PAPER, 16);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("mold")).thenReturn(moldTemplate);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("copy")).thenReturn(copyTemplate);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("paper")).thenReturn(paperTemplate);
        CopyMetadata metadata = new CopyMetadata(UUID.randomUUID().toString(), 0.65, "silver");
        ItemStack mold = KeyCopyHandler.createMold(metadata);
        ItemStack copy = KeyCopyHandler.createPermanentCopy(metadata);
        ItemStack paper = KeyCopyHandler.createPaperCopy(metadata);
        for (ItemStack item : new ItemStack[] {mold, copy, paper}) {
            assertEquals(1, item.getAmount());
            assertEquals(metadata.getDoorKeyUuid(), KeyCopyHandler.getDoorKeyUuid(item));
            assertEquals(0.65, KeyCopyHandler.getSourceStrength(item));
            assertEquals(0.65, ToolResolver.getKeyStrength(item));
            assertEquals("silver", KeyCopyHandler.getSourceKeyId(item));
        }
        assertTrue(KeyCopyHandler.isMold(mold));
        assertFalse(KeyCopyHandler.isCopyItem(mold));
        assertFalse(KeyCopyHandler.isStorableDoorKey(mold));
        assertTrue(KeyCopyHandler.isPermanentCopy(copy));
        assertTrue(KeyCopyHandler.isCopyItem(copy));
        assertTrue(KeyCopyHandler.isStorableDoorKey(copy));
        assertTrue(KeyCopyHandler.isPaperCopy(paper));
        assertTrue(KeyCopyHandler.isCopyItem(paper));
        assertTrue(KeyCopyHandler.isStorableDoorKey(paper));
        assertFalse(ToolResolver.isDoorKey(mold));
        assertFalse(ToolResolver.isLockingKey(mold));
        assertTrue(ToolResolver.isDoorKey(copy));
        assertTrue(ToolResolver.isLockingKey(copy));
        assertTrue(ToolResolver.isKey(paper));
        assertFalse(ToolResolver.isLockingKey(paper), "Disposable paper copies must not re-lock property");
        assertFalse(KeyCopyHandler.ensureKeyInitialized(mold));
        assertFalse(KeyCopyHandler.ensureKeyInitialized(copy));
        assertEquals(8, moldTemplate.getAmount());
        assertEquals(12, copyTemplate.getAmount());
        assertEquals(16, paperTemplate.getAmount());
        assertNull(KeyCopyHandler.getDoorKeyUuid(moldTemplate));
        assertNull(KeyCopyHandler.getDoorKeyUuid(copyTemplate));
        assertNull(KeyCopyHandler.getDoorKeyUuid(paperTemplate));
    }

    @Test
    void classificationRequiresBothMarkersAndMatchingConfiguredItem() {
        ItemStack mold = new ItemStack(Material.CLAY_BALL);
        mold.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keyMoldMarker, PersistentDataType.BYTE, (byte) 1));
        assertFalse(KeyCopyHandler.isMold(mold));
        mold.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, "door"));
        assertTrue(KeyCopyHandler.isMold(mold));
        mold.setType(Material.STONE);
        assertFalse(KeyCopyHandler.isMold(mold));
        ItemStack copy = new ItemStack(Material.GOLD_NUGGET);
        copy.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keyCopyKind, PersistentDataType.STRING, Keys.COPY_KIND_PERMANENT));
        assertFalse(KeyCopyHandler.isMasterKey(copy));
        assertFalse(KeyCopyHandler.isPermanentCopy(copy));
        copy.setType(Material.COPPER_INGOT);
        assertTrue(KeyCopyHandler.isPermanentCopy(copy));
        copy.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keyCopyKind, PersistentDataType.STRING, Keys.COPY_KIND_PAPER));
        assertFalse(KeyCopyHandler.isPaperCopy(copy));
        copy.setType(Material.PAPER);
        assertTrue(KeyCopyHandler.isPaperCopy(copy));
    }

    @Test
    void storedMetadataOverridesDefinitionsAndBlankSourceIdFallsBack() {
        ItemStack master = new ItemStack(Material.GOLD_NUGGET);
        master.editMeta(meta -> {
            meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, " ");
            meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, " ");
        });
        assertNull(KeyCopyHandler.extractCopyMetadata(master));
        assertEquals("copper", KeyCopyHandler.getSourceKeyId(master));
        master.editMeta(meta -> {
            meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, "door-id");
            meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, "original");
            meta.getPersistentDataContainer().set(Keys.keySourceStrength, PersistentDataType.DOUBLE, 0.2);
        });
        assertEquals("original", KeyCopyHandler.getSourceKeyId(master));
        assertEquals(0.2, KeyCopyHandler.getSourceStrength(master));
    }

    @Test
    void missingTemplatesAndMetadataProduceNoOutputAndOptionalSourceIdCanBeAbsent() {
        assertNull(KeyCopyHandler.createMold(null));
        assertNull(KeyCopyHandler.createPermanentCopy(null));
        assertNull(KeyCopyHandler.createPaperCopy(null));
        CopyMetadata metadata = new CopyMetadata("door", 0.5, null);
        for (ItemStack unavailable : new ItemStack[] {null, new ItemStack(Material.AIR)}) {
            when(TLibs.getItemAPI().getCreator().getItemFromPath("mold")).thenReturn(unavailable);
            when(TLibs.getItemAPI().getCreator().getItemFromPath("copy")).thenReturn(unavailable);
            when(TLibs.getItemAPI().getCreator().getItemFromPath("paper")).thenReturn(unavailable);
            assertNull(KeyCopyHandler.createMold(metadata));
            assertNull(KeyCopyHandler.createPermanentCopy(metadata));
            assertNull(KeyCopyHandler.createPaperCopy(metadata));
        }
        ItemStack template = new ItemStack(Material.PAPER);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("paper")).thenReturn(template);
        ItemStack paper = KeyCopyHandler.createPaperCopy(metadata);
        assertEquals("door", KeyCopyHandler.getDoorKeyUuid(paper));
        assertNull(KeyCopyHandler.getSourceKeyId(paper));
    }

    @Test
    void paperCooldownIsPerPlayerAndDoorAndIsPersistedAfterRecording() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        PlayerData data = new PlayerData(id);
        PlayerManager manager = mock(PlayerManager.class);
        when(manager.get(id)).thenReturn(data);
        thievery.when(Thievery::getPlayerManager).thenReturn(manager);
        try (MockedStatic<Database> database = mockStatic(Database.class)) {
            assertTrue(KeyCopyHandler.canCraftPaper(player, "door"));
            assertEquals(0, KeyCopyHandler.getPaperCooldownRemainingMinutes(player, "door"));
            KeyCopyHandler.recordPaperCooldown(player, "door");
            assertFalse(KeyCopyHandler.canCraftPaper(player, "door"));
            assertEquals(15, KeyCopyHandler.getPaperCooldownRemainingMinutes(player, "door"));
            assertTrue(KeyCopyHandler.canCraftPaper(player, "other-door"));
            database.verify(() -> Database.savePlayerData(data));
            assertFalse(KeyCopyHandler.canCraftPaper(null, "door"));
            assertFalse(KeyCopyHandler.canCraftPaper(player, null));
            assertEquals(0, KeyCopyHandler.getPaperCooldownRemainingMinutes(null, "door"));
            assertEquals(0, KeyCopyHandler.getPaperCooldownRemainingMinutes(player, null));
            KeyCopyHandler.recordPaperCooldown(null, "door");
            KeyCopyHandler.recordPaperCooldown(player, null);
            database.verifyNoMoreInteractions();
        }
    }
}
