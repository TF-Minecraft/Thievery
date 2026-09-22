package net.tfminecraft.thievery.key;

import net.tfminecraft.thievery.util.LegacyModelData;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.key.KeyDefinition;
import net.tfminecraft.thievery.loader.KeychainLoader;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;

public final class KeychainHandler {

    public enum DoorKeyPurpose {
        OPEN,
        UNLOCK_OR_BREAK
    }

    public static final class DoorKeyMatch {
        private final String doorKeyUuid;
        private final boolean paper;

        public DoorKeyMatch(String doorKeyUuid, boolean paper) {
            this.doorKeyUuid = doorKeyUuid;
            this.paper = paper;
        }

        public String getDoorKeyUuid() {
            return doorKeyUuid;
        }

        public boolean isPaper() {
            return paper;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type KEY_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    private KeychainHandler() {}

    /** Initialized keychain (marker set — may be empty until first key is added). */
    public static boolean isKeychain(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .has(Keys.keychainMarker, PersistentDataType.BYTE);
    }

    /** Config keychain item, initialized or not (e.g. fresh MMOItems brick). */
    public static boolean isKeychainItem(ItemStack item) {
        return isKeychain(item) || KeychainLoader.matchesItem(item);
    }

    public static ItemStack createKeychain() {
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(KeychainLoader.getItemPath());
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.BRICK);
        }
        return initialize(item.clone());
    }

    public static ItemStack initialize(ItemStack item) {
        ItemStack updated = item.clone();
        ItemMeta meta = updated.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(Keys.keychainMarker, PersistentDataType.BYTE, (byte) 1);
        if (!meta.getPersistentDataContainer().has(Keys.keychainKeys, PersistentDataType.STRING)) {
            meta.getPersistentDataContainer().set(Keys.keychainKeys, PersistentDataType.STRING, "[]");
        }
        updated.setItemMeta(meta);
        return refreshDisplay(updated);
    }

    public static List<ItemStack> getStoredKeys(ItemStack keychain) {
        List<ItemStack> keys = new ArrayList<>();
        if (!isKeychain(keychain)) {
            return keys;
        }
        ItemMeta meta = keychain.getItemMeta();
        if (meta == null) {
            return keys;
        }
        String json = meta.getPersistentDataContainer().get(Keys.keychainKeys, PersistentDataType.STRING);
        if (json == null || json.isBlank()) {
            return keys;
        }
        List<Map<String, Object>> serialized = GSON.fromJson(json, KEY_LIST_TYPE);
        if (serialized == null) {
            return keys;
        }
        for (Map<String, Object> map : serialized) {
            if (map == null) continue;
            try {
                ItemStack key = ItemStack.deserialize(map);
                if (key != null && !key.getType().isAir()) {
                    keys.add(key);
                }
            } catch (Exception ignored) {
            }
        }
        return keys;
    }

    public static ItemStack setStoredKeys(ItemStack keychain, List<ItemStack> keys) {
        ItemStack updated = keychain.clone();
        ItemMeta meta = updated.getItemMeta();
        if (meta == null) {
            return keychain;
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ItemStack key : keys) {
            if (key == null || key.getType().isAir()) continue;
            serialized.add(key.serialize());
        }
        meta.getPersistentDataContainer().set(Keys.keychainKeys, PersistentDataType.STRING, GSON.toJson(serialized));
        updated.setItemMeta(meta);
        return refreshDisplay(updated);
    }

    public static AddKeyResult addKey(ItemStack keychain, ItemStack key) {
        if (!isKeychainItem(keychain)) {
            return AddKeyResult.fail(keychain);
        }
        if (!ToolResolver.isDoorKey(key)) {
            return AddKeyResult.fail(keychain);
        }
        ItemStack working = isKeychain(keychain) ? keychain : initialize(keychain);
        List<ItemStack> stored = getStoredKeys(working);
        if (stored.size() >= KeychainLoader.getMaxKeys()) {
            return AddKeyResult.full(working);
        }
        ItemStack toStore = key.clone();
        toStore.setAmount(1);
        String newUuid = getKeyUuid(toStore);
        if (newUuid != null) {
            for (ItemStack existing : stored) {
                String existingUuid = getKeyUuid(existing);
                if (newUuid.equals(existingUuid)) {
                    return AddKeyResult.duplicate(working);
                }
            }
        }
        stored.add(toStore);
        return AddKeyResult.success(setStoredKeys(working, stored));
    }

    public static ItemStack peekLastKey(ItemStack keychain) {
        List<ItemStack> stored = getStoredKeys(keychain);
        if (stored.isEmpty()) {
            return null;
        }
        return stored.get(stored.size() - 1);
    }

    public static boolean canFitInInventory(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        ItemStack probe = item.clone();
        probe.setAmount(1);
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                return true;
            }
            if (slot.isSimilar(probe) && slot.getAmount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public static RemoveKeyResult removeLastKey(ItemStack keychain) {
        if (!isKeychain(keychain)) {
            return RemoveKeyResult.empty(keychain);
        }
        List<ItemStack> stored = getStoredKeys(keychain);
        if (stored.isEmpty()) {
            return RemoveKeyResult.empty(keychain);
        }
        ItemStack removed = stored.remove(stored.size() - 1);
        return RemoveKeyResult.success(setStoredKeys(keychain, stored), removed);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack refreshDisplay(ItemStack keychain) {
        if (!isKeychain(keychain)) {
            return keychain;
        }
        ItemStack updated = keychain.clone();
        ItemMeta meta = updated.getItemMeta();
        if (meta == null) {
            return keychain;
        }
        List<ItemStack> stored = getStoredKeys(keychain);
        List<String> lore = new ArrayList<>();
        List<String> existing = meta.getLore();
        int reservedLines = KeychainLoader.getLoreLineStart() - 1;
        if (existing != null) {
            for (int i = 0; i < reservedLines && i < existing.size(); i++) {
                lore.add(existing.get(i));
            }
        }
        lore.add(ThieveryTexts.gui(ThieveryTexts.WHITE + "Keys " + ThieveryTexts.GUI_WARN + stored.size()
                + "/" + KeychainLoader.getMaxKeys()));
        for (ItemStack key : stored) {
            lore.add(ThieveryTexts.gui(ThieveryTexts.WHITE + formatKeyName(key)));
        }
        meta.setLore(lore);
        LegacyModelData.set(meta, KeychainLoader.resolveModelData(stored.size()));
        updated.setItemMeta(meta);
        return updated;
    }

    public static String findMatchingDoorUuid(ItemStack item, String doorKeyUuid) {
        DoorKeyMatch match = resolveDoorMatch(item, doorKeyUuid, DoorKeyPurpose.OPEN);
        return match != null ? match.getDoorKeyUuid() : null;
    }

    public static boolean matchesDoor(ItemStack item, String doorKeyUuid, DoorKeyPurpose purpose) {
        return resolveDoorMatch(item, doorKeyUuid, purpose) != null;
    }

    public static DoorKeyMatch resolveDoorMatch(ItemStack item, String doorKeyUuid, DoorKeyPurpose purpose) {
        if (item == null || item.getType().isAir() || doorKeyUuid == null) {
            return null;
        }
        if (ToolResolver.isDoorKey(item)) {
            return matchesSingleItem(item, doorKeyUuid, purpose);
        }
        if (isKeychain(item)) {
            for (ItemStack key : getStoredKeys(item)) {
                DoorKeyMatch match = matchesSingleItem(key, doorKeyUuid, purpose);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    public static ItemStack consumePaperKeyForDoor(ItemStack keychain, String doorKeyUuid) {
        if (!isKeychain(keychain) || doorKeyUuid == null) {
            return keychain;
        }
        List<ItemStack> stored = getStoredKeys(keychain);
        for (int i = stored.size() - 1; i >= 0; i--) {
            ItemStack key = stored.get(i);
            if (KeyCopyHandler.isPaperCopy(key) && doorKeyUuid.equals(getKeyUuid(key))) {
                stored.remove(i);
                return setStoredKeys(keychain, stored);
            }
        }
        return keychain;
    }

    private static DoorKeyMatch matchesSingleItem(ItemStack item, String doorKeyUuid, DoorKeyPurpose purpose) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String uuid = getKeyUuid(item);
        if (!doorKeyUuid.equals(uuid)) {
            return null;
        }
        if (KeyCopyHandler.isPaperCopy(item)) {
            if (purpose == DoorKeyPurpose.UNLOCK_OR_BREAK) {
                return null;
            }
            return new DoorKeyMatch(uuid, true);
        }
        if (ToolResolver.isLockingKey(item) || ToolResolver.isMasterKey(item)) {
            return new DoorKeyMatch(uuid, false);
        }
        return null;
    }

    private static String getKeyUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keyUUIDKey, PersistentDataType.STRING);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static String formatKeyName(ItemStack key) {
        if (key.hasItemMeta()) {
            ItemMeta meta = key.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return ChatColor.stripColor(meta.getDisplayName());
            }
        }
        if (KeyCopyHandler.isPaperCopy(key)) {
            String sourceId = KeyCopyHandler.getSourceKeyId(key);
            if (sourceId != null) {
                return "Paper " + humanizeId(sourceId);
            }
            return "Paper Key";
        }
        if (KeyCopyHandler.isPermanentCopy(key)) {
            String sourceId = KeyCopyHandler.getSourceKeyId(key);
            if (sourceId != null) {
                return humanizeId(sourceId) + " Copy";
            }
            return "Key Copy";
        }
        KeyDefinition definition = ToolResolver.resolveKey(key);
        if (definition != null) {
            return humanizeId(definition.getId());
        }
        return "Key";
    }

    private static String humanizeId(String id) {
        String[] parts = id.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.length() > 0 ? builder.toString() : id;
    }

    public static final class AddKeyResult {
        private final ItemStack keychain;
        private final Status status;

        private AddKeyResult(ItemStack keychain, Status status) {
            this.keychain = keychain;
            this.status = status;
        }

        public static AddKeyResult success(ItemStack keychain) {
            return new AddKeyResult(keychain, Status.SUCCESS);
        }

        public static AddKeyResult fail(ItemStack keychain) {
            return new AddKeyResult(keychain, Status.FAIL);
        }

        public static AddKeyResult full(ItemStack keychain) {
            return new AddKeyResult(keychain, Status.FULL);
        }

        public static AddKeyResult duplicate(ItemStack keychain) {
            return new AddKeyResult(keychain, Status.DUPLICATE);
        }

        public ItemStack getKeychain() {
            return keychain;
        }

        public Status getStatus() {
            return status;
        }

        public enum Status {
            SUCCESS, FAIL, FULL, DUPLICATE
        }
    }

    public static final class RemoveKeyResult {
        private final ItemStack keychain;
        private final ItemStack removedKey;

        private RemoveKeyResult(ItemStack keychain, ItemStack removedKey) {
            this.keychain = keychain;
            this.removedKey = removedKey;
        }

        public static RemoveKeyResult success(ItemStack keychain, ItemStack removedKey) {
            return new RemoveKeyResult(keychain, removedKey);
        }

        public static RemoveKeyResult empty(ItemStack keychain) {
            return new RemoveKeyResult(keychain, null);
        }

        public ItemStack getKeychain() {
            return keychain;
        }

        public ItemStack getRemovedKey() {
            return removedKey;
        }

        public boolean removed() {
            return removedKey != null;
        }
    }
}
