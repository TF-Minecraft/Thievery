package net.tfminecraft.thievery.key;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.key.KeychainHandler.DoorKeyPurpose;
import net.tfminecraft.thievery.loader.KeychainLoader;
import net.tfminecraft.thievery.util.LegacyModelData;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

import org.junit.jupiter.api.Test;

class KeychainHandlerTest {
    private MockedStatic<Thievery> plugin;
    private MockedStatic<ToolResolver> tools;
    private MockedStatic<LegacyModelData> models;
    private MockedStatic<KeyCopyHandler> copies;
    private MockedStatic<TLibs> libs;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.namespace()).thenReturn("thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        models = mockStatic(LegacyModelData.class);
        tools = mockStatic(ToolResolver.class);
        tools.when(() -> ToolResolver.isKeychain(any())).thenCallRealMethod();
        tools.when(() -> ToolResolver.isKeychainItem(any())).thenCallRealMethod();
        tools.when(() -> ToolResolver.isDoorKey(any())).thenAnswer(i -> isKey(i.getArgument(0)));
        tools.when(() -> ToolResolver.isLockingKey(any())).thenAnswer(i -> hasType(i.getArgument(0), Material.COPPER_INGOT));
        tools.when(() -> ToolResolver.isMasterKey(any())).thenAnswer(i -> hasType(i.getArgument(0), Material.GOLD_NUGGET));
        copies = mockStatic(KeyCopyHandler.class);
        copies.when(() -> KeyCopyHandler.isPaperCopy(any())).thenAnswer(i -> hasType(i.getArgument(0), Material.PAPER));
        copies.when(() -> KeyCopyHandler.isPermanentCopy(any())).thenAnswer(i -> hasType(i.getArgument(0), Material.COPPER_INGOT));
        libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
        when(TLibs.getItemAPI().getChecker().checkItemWithPath(any(ItemStack.class), anyString()))
                .thenAnswer(i -> hasType(i.getArgument(0), Material.BRICK));
        var config = new YamlConfiguration();
        config.set("keychain.max-keys", 3);
        config.set("keychain.lore-line-start", 3);
        config.set("keychain.model-by-count.0", 100);
        config.set("keychain.model-by-count.2", 102);
        KeychainLoader.load(config);
    }

    @AfterEach
    void tearDown() {
        KeychainLoader.load(new YamlConfiguration());
        libs.close(); copies.close(); tools.close(); models.close(); plugin.close();
        MockBukkit.unmock();
    }

    private static boolean hasType(ItemStack item, Material type) {
        return item != null && item.getType() == type;
    }

    private static boolean isKey(ItemStack item) {
        return hasType(item, Material.GOLD_NUGGET) || hasType(item, Material.COPPER_INGOT)
                || hasType(item, Material.PAPER);
    }

    private static ItemStack key(Material material, String uuid) {
        ItemStack key = new ItemStack(material);
        if (uuid != null) key.editMeta(meta -> meta.getPersistentDataContainer()
                .set(Keys.keyUUIDKey, PersistentDataType.STRING, uuid));
        return key;
    }

    @Test
    void storedKeysRoundTripTheirUuidDisplayNameAndAmount() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        ItemStack first = key(Material.GOLD_NUGGET, UUID.randomUUID().toString());
        first.setAmount(5);
        first.editMeta(meta -> meta.setDisplayName("§aVault Key"));
        var added = KeychainHandler.addKey(chain, first);
        assertEquals(KeychainHandler.AddKeyResult.Status.SUCCESS, added.getStatus());
        List<ItemStack> stored = KeychainHandler.getStoredKeys(added.getKeychain());
        assertEquals(1, stored.size());
        assertEquals(1, stored.getFirst().getAmount());
        assertEquals("§aVault Key", stored.getFirst().getItemMeta().getDisplayName());
        assertEquals(first.getItemMeta().getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING),
                stored.getFirst().getItemMeta().getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING));
        assertEquals(5, first.getAmount());
        assertTrue(KeychainHandler.getStoredKeys(chain).isEmpty());
    }


    @Test
    void initializePreservesTemplateLoreAndExistingKeysAndSelectsModelThreshold() {
        ItemStack template = new ItemStack(Material.BRICK);
        template.editMeta(meta -> meta.setLore(List.of("Template", "Instructions", "Discarded")));
        ItemStack chain = KeychainHandler.initialize(template);
        assertFalse(net.tfminecraft.thievery.utils.ToolResolver.isKeychain(template));
        assertTrue(KeychainHandler.isKeychain(chain));
        assertEquals(List.of("Template", "Instructions", "Keys 0/3"), plainLore(chain));
        models.verify(() -> LegacyModelData.set(any(), eq(100)));
        chain = KeychainHandler.setStoredKeys(chain, List.of(key(Material.GOLD_NUGGET, "a"), key(Material.PAPER, "b")));
        assertEquals(2, KeychainHandler.getStoredKeys(KeychainHandler.initialize(chain)).size());
        assertEquals("Keys 2/3", plainLore(chain).get(2));
        models.verify(() -> LegacyModelData.set(any(), eq(102)), atLeastOnce());
        assertEquals(5, plainLore(KeychainHandler.refreshDisplay(chain)).size());
    }

    @Test
    void factoryUsesConfiguredTemplateAndFallsBackToBrickWhenUnavailable() {
        ItemStack template = new ItemStack(Material.STICK);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("v.brick")).thenReturn(template);
        ItemStack chain = KeychainHandler.createKeychain();
        assertEquals(Material.STICK, chain.getType());
        assertTrue(KeychainHandler.isKeychain(chain));
        assertFalse(net.tfminecraft.thievery.utils.ToolResolver.isKeychain(template));
        for (ItemStack unavailable : new ItemStack[] {null, new ItemStack(Material.AIR)}) {
            when(TLibs.getItemAPI().getCreator().getItemFromPath("v.brick")).thenReturn(unavailable);
            chain = KeychainHandler.createKeychain();
            assertEquals(Material.BRICK, chain.getType());
            assertTrue(KeychainHandler.isKeychain(chain));
        }
    }

    @Test
    void addRejectsInvalidItemsDuplicateDoorIdsAndFullChainsWithoutMutatingInputs() {
        ItemStack key = key(Material.GOLD_NUGGET, "door");
        ItemStack invalid = new ItemStack(Material.STONE);
        assertEquals(KeychainHandler.AddKeyResult.Status.FAIL, KeychainHandler.addKey(invalid, key).getStatus());
        ItemStack chain = new ItemStack(Material.BRICK);
        assertTrue(net.tfminecraft.thievery.utils.ToolResolver.isKeychainItem(chain));
        assertEquals(KeychainHandler.AddKeyResult.Status.FAIL, KeychainHandler.addKey(chain, invalid).getStatus());
        chain = KeychainHandler.addKey(chain, key).getKeychain();
        assertTrue(KeychainHandler.isKeychain(chain));
        var duplicate = KeychainHandler.addKey(chain, key(Material.PAPER, "door"));
        assertEquals(KeychainHandler.AddKeyResult.Status.DUPLICATE, duplicate.getStatus());
        assertSame(chain, duplicate.getKeychain());
        chain = KeychainHandler.addKey(chain, key(Material.GOLD_NUGGET, "second")).getKeychain();
        chain = KeychainHandler.addKey(chain, key(Material.GOLD_NUGGET, null)).getKeychain();
        var full = KeychainHandler.addKey(chain, key(Material.GOLD_NUGGET, "fourth"));
        assertEquals(KeychainHandler.AddKeyResult.Status.FULL, full.getStatus());
        assertEquals(3, KeychainHandler.getStoredKeys(full.getKeychain()).size());
        assertEquals(3, KeychainHandler.getStoredKeys(chain).size());
    }

    @Test
    void storedKeysFilterEmptyItemsAndRemovalIsLastInFirstOut() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        ItemStack first = key(Material.GOLD_NUGGET, "first");
        ItemStack last = key(Material.PAPER, "last");
        chain = KeychainHandler.setStoredKeys(chain, Arrays.asList(null, new ItemStack(Material.AIR), first, last));
        assertEquals(2, KeychainHandler.getStoredKeys(chain).size());
        assertEquals(Material.PAPER, KeychainHandler.peekLastKey(chain).getType());
        var result = KeychainHandler.removeLastKey(chain);
        assertTrue(result.removed());
        assertEquals(Material.PAPER, result.getRemovedKey().getType());
        assertEquals(1, KeychainHandler.getStoredKeys(result.getKeychain()).size());
        assertEquals(2, KeychainHandler.getStoredKeys(chain).size());
        var empty = KeychainHandler.removeLastKey(KeychainHandler.initialize(new ItemStack(Material.BRICK)));
        assertFalse(empty.removed());
        assertNull(empty.getRemovedKey());
        assertNull(KeychainHandler.peekLastKey(empty.getKeychain()));
        assertFalse(KeychainHandler.removeLastKey(new ItemStack(Material.STONE)).removed());
    }

    @Test
    void missingNullAndUnusableStoredEntriesAreIgnored() {
        assertTrue(KeychainHandler.getStoredKeys(null).isEmpty());
        assertFalse(KeychainHandler.isKeychain(new ItemStack(Material.AIR)));
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        chain.editMeta(meta -> meta.getPersistentDataContainer().remove(Keys.keychainKeys));
        assertTrue(KeychainHandler.getStoredKeys(chain).isEmpty());
        for (String json : List.of(" ", "null", "[null, {}, {\"type\":\"AIR\"}, {\"type\":\"not-real\"}]")) {
            chain.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keychainKeys, PersistentDataType.STRING, json));
            assertTrue(KeychainHandler.getStoredKeys(chain).isEmpty());
        }
        ItemStack ordinary = new ItemStack(Material.STONE);
        assertSame(ordinary, KeychainHandler.refreshDisplay(ordinary));
    }

    @Test
    void malformedStoredPayloadCannotCrashKeychainInspectionOrOverwriteTheOriginalData() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        for (String json : List.of("[", "{\"old-format\":true}", "[1]")) {
            chain.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keychainKeys, PersistentDataType.STRING, json));
            assertTrue(KeychainHandler.getStoredKeys(chain).isEmpty());
            assertEquals(List.of("Keys 0/3"), plainLore(KeychainHandler.refreshDisplay(chain)));
            assertEquals(json, chain.getItemMeta().getPersistentDataContainer()
                    .get(Keys.keychainKeys, PersistentDataType.STRING));
        }
    }

    @Test
    void anUntaggedVanillaItemIsNotAnInitializedKeychain() {
        // Paper reports no metadata for a vanilla stack; MockBukkit currently creates empty metadata eagerly.
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.BRICK);
        when(item.hasItemMeta()).thenReturn(false);
        assertFalse(KeychainHandler.isKeychain(item));
        assertTrue(KeychainHandler.getStoredKeys(item).isEmpty());
        verify(item, never()).getItemMeta();
    }

    @Test
    void displayNamesPreferCustomNamesThenCopySourceAndKeyDefinition() {
        ItemStack named = key(Material.GOLD_NUGGET, "named");
        named.editMeta(meta -> meta.setDisplayName("§aVault Key"));
        ItemStack paper = key(Material.PAPER, "paper");
        ItemStack copy = key(Material.COPPER_INGOT, "copy");
        copies.when(() -> KeyCopyHandler.getSourceKeyId(any())).thenAnswer(i -> {
            ItemStack item = i.getArgument(0);
            return item.getItemMeta().getPersistentDataContainer().get(Keys.keySourceKeyId, PersistentDataType.STRING);
        });
        ItemStack sourcedPaper = key(Material.PAPER, "sourced-paper");
        sourcedPaper.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, "silver_key"));
        ItemStack sourcedCopy = key(Material.COPPER_INGOT, "sourced-copy");
        sourcedCopy.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, "iron_key"));
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        chain = KeychainHandler.setStoredKeys(chain, List.of(named, paper, copy, sourcedPaper, sourcedCopy));
        assertEquals(List.of("Keys 5/3", "Vault Key", "Paper Key", "Key Copy", "Paper Silver Key", "Iron Key Copy"), plainLore(chain));
        var definition = new KeyDefinition("bronze__key", new YamlConfiguration());
        tools.when(() -> ToolResolver.resolveKey(any())).thenReturn(definition);
        chain = KeychainHandler.setStoredKeys(chain, List.of(key(Material.GOLD_NUGGET, null)));
        assertEquals(List.of("Keys 1/3", "Bronze Key"), plainLore(chain));
        tools.when(() -> ToolResolver.resolveKey(any())).thenReturn(null);
        chain = KeychainHandler.setStoredKeys(chain, List.of(key(Material.GOLD_NUGGET, null)));
        assertEquals(List.of("Keys 1/3", "Key"), plainLore(chain));
    }

    @Test
    void matchingHonorsDoorIdentityAndPaperKeysCannotUnlockOrBreakLocks() {
        var open = KeychainHandler.DoorKeyPurpose.OPEN;
        var unlock = KeychainHandler.DoorKeyPurpose.UNLOCK_OR_BREAK;
        ItemStack master = key(Material.GOLD_NUGGET, "door");
        ItemStack permanent = key(Material.COPPER_INGOT, "door");
        ItemStack paper = key(Material.PAPER, "door");
        assertEquals("door", KeychainHandler.findMatchingDoorUuid(master, "door"));
        assertNull(KeychainHandler.findMatchingDoorUuid(master, "wrong"));
        assertTrue(KeychainHandler.matchesDoor(permanent, "door", unlock));
        assertFalse(KeychainHandler.resolveDoorMatch(master, "door", open).isPaper());
        assertTrue(KeychainHandler.resolveDoorMatch(paper, "door", open).isPaper());
        assertFalse(KeychainHandler.matchesDoor(paper, "door", unlock));
        assertFalse(KeychainHandler.matchesDoor(null, "door", open));
        assertFalse(KeychainHandler.matchesDoor(new ItemStack(Material.AIR), "door", open));
        assertFalse(KeychainHandler.matchesDoor(master, null, open));
        assertFalse(KeychainHandler.matchesDoor(new ItemStack(Material.STONE), "door", open));
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        chain = KeychainHandler.setStoredKeys(chain, List.of(key(Material.GOLD_NUGGET, "other"), paper));
        assertTrue(KeychainHandler.matchesDoor(chain, "door", open));
        assertFalse(KeychainHandler.matchesDoor(chain, "door", unlock));
        assertFalse(KeychainHandler.matchesDoor(chain, "missing", open));
    }

    @Test
    void storedKeyWhoseDefinitionWasRemovedCannotUnlockAndDoesNotHideLaterValidKeys() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        ItemStack obsolete = key(Material.IRON_NUGGET, "door");
        chain = KeychainHandler.setStoredKeys(chain, List.of(obsolete));
        assertEquals(1, KeychainHandler.getStoredKeys(chain).size());
        assertFalse(KeychainHandler.matchesDoor(chain, "door", DoorKeyPurpose.UNLOCK_OR_BREAK));
        chain = KeychainHandler.setStoredKeys(chain,
                List.of(obsolete, key(Material.COPPER_INGOT, "door")));
        assertTrue(KeychainHandler.matchesDoor(chain, "door", DoorKeyPurpose.UNLOCK_OR_BREAK));
        assertEquals(2, KeychainHandler.getStoredKeys(chain).size());
    }

    @Test
    void consumingPaperKeyRemovesOnlyLastMatchingPaperAndKeepsPermanentKeys() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        chain = KeychainHandler.setStoredKeys(chain, List.of(key(Material.PAPER, "door"),
                key(Material.COPPER_INGOT, "door"), key(Material.PAPER, "door"), key(Material.PAPER, "other")));
        ItemStack result = KeychainHandler.consumePaperKeyForDoor(chain, "door");
        List<ItemStack> remaining = KeychainHandler.getStoredKeys(result);
        assertEquals(3, remaining.size());
        assertEquals(Material.PAPER, remaining.get(0).getType());
        assertEquals(Material.COPPER_INGOT, remaining.get(1).getType());
        assertEquals("other", remaining.get(2).getItemMeta().getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING));
        assertEquals(4, KeychainHandler.getStoredKeys(chain).size());
        assertSame(result, KeychainHandler.consumePaperKeyForDoor(result, "absent"));
        assertSame(result, KeychainHandler.consumePaperKeyForDoor(result, null));
        ItemStack ordinary = new ItemStack(Material.STONE);
        assertSame(ordinary, KeychainHandler.consumePaperKeyForDoor(ordinary, "door"));
    }

    @Test
    void inventoryCapacityAllowsEmptySlotsAndPartialMatchingStacksOnly() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack[] slots = new ItemStack[36];
        when(inventory.getStorageContents()).thenReturn(slots);
        ItemStack key = key(Material.GOLD_NUGGET, "door");
        assertTrue(KeychainHandler.canFitInInventory(player, null));
        assertTrue(KeychainHandler.canFitInInventory(player, new ItemStack(Material.AIR)));
        assertTrue(KeychainHandler.canFitInInventory(player, key));
        Arrays.fill(slots, new ItemStack(Material.STONE, 64));
        assertFalse(KeychainHandler.canFitInInventory(player, key));
        slots[35] = new ItemStack(Material.AIR);
        assertTrue(KeychainHandler.canFitInInventory(player, key));
        slots[35] = key.clone();
        slots[35].setAmount(64);
        assertFalse(KeychainHandler.canFitInInventory(player, key));
        slots[35].setAmount(63);
        assertTrue(KeychainHandler.canFitInInventory(player, key));
        assertEquals(63, slots[35].getAmount());
    }

    private static List<String> plainLore(ItemStack item) {
        return item.getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();
    }

    @Test
    void emptyStackInputsRemainUnchangedAndCannotStoreKeys() {
        ItemStack empty = new ItemStack(Material.AIR);
        assertSame(empty, KeychainHandler.initialize(empty));
        assertSame(empty, KeychainHandler.setStoredKeys(empty, List.of(key(Material.GOLD_NUGGET, "door"))));
        assertFalse(KeychainHandler.isKeychain(empty));
        assertTrue(KeychainHandler.getStoredKeys(empty).isEmpty());
    }

    @Test
    void versionedLegacyPayloadSkipsAirAndKeepsUsableUnmarkedItemsWithoutInventingDoorIdentity() {
        ItemStack chain = KeychainHandler.initialize(new ItemStack(Material.BRICK));
        ItemStack unmarked = new ItemStack(Material.IRON_NUGGET);
        var emptyPayload = new java.util.HashMap<>(new ItemStack(Material.AIR).serialize());
        // Paper accepts count on AIR; MockBukkit requires it even though its AIR serializer omits it.
        emptyPayload.put("count", 0);
        assertTrue(ItemStack.deserialize(emptyPayload).getType().isAir());
        String json = new com.google.gson.Gson().toJson(List.of(emptyPayload, unmarked.serialize()));
        chain.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keychainKeys, PersistentDataType.STRING, json));
        assertEquals(List.of(unmarked), KeychainHandler.getStoredKeys(chain));
        assertFalse(KeychainHandler.matchesDoor(chain, "door", DoorKeyPurpose.OPEN));
        assertEquals(List.of("Keys 1/3", "Key"), plainLore(KeychainHandler.refreshDisplay(chain)));
        assertEquals(json, chain.getItemMeta().getPersistentDataContainer().get(Keys.keychainKeys, PersistentDataType.STRING));
    }

    @Test
    void shortTemplateLoreAndUnusualLegacySourceIdsRemainStableAcrossRefreshes() {
        var config = new YamlConfiguration(); config.set("keychain.max-keys", 3);config.set("keychain.lore-line-start", 5);KeychainLoader.load(config);
        ItemStack template = new ItemStack(Material.BRICK);template.editMeta(meta -> meta.setLore(List.of("Template")));
        ItemStack chain = KeychainHandler.initialize(template);
        assertEquals(List.of("Template", "Keys 0/3"), plainLore(chain));
        copies.when(() -> KeyCopyHandler.getSourceKeyId(any())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            return item.getItemMeta().getPersistentDataContainer().get(Keys.keySourceKeyId, PersistentDataType.STRING);
        });
        ItemStack initialed = key(Material.PAPER, "one");initialed.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, "a_b"));
        ItemStack legacy = key(Material.COPPER_INGOT, "two");legacy.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING, "___"));
        chain=KeychainHandler.setStoredKeys(chain,List.of(initialed,legacy));
        assertEquals(List.of("Template", "Keys 2/3", "Paper A B", "___ Copy"),plainLore(chain));
        assertEquals(plainLore(chain),plainLore(KeychainHandler.refreshDisplay(chain)));
        assertEquals(List.of("Template"),plainLore(template));
    }

    @Test
    void templateLoreStopsAtPreviousKeyHeader() {
        List<String> existing = List.of("§cKey", "", "§7§oCan hold up to five keys.",
                "§fKeys §x§d§6§c§f§6§90/5", "§fKeys §x§d§6§c§f§6§92/5", "§fCopper Key");

        assertEquals(List.of("§cKey", "", "§7§oCan hold up to five keys."),
                KeychainHandler.templateLore(existing, 4));
    }

    @Test
    void templateLoreKeepsReservedLinesWithoutHeader() {
        List<String> existing = List.of("a", "b", "c", "d", "e");

        assertEquals(List.of("a", "b", "c", "d"), KeychainHandler.templateLore(existing, 4));
    }

    @Test
    void templateLoreHandlesMissingLore() {
        assertEquals(List.of(), KeychainHandler.templateLore(null, 4));
    }
}
