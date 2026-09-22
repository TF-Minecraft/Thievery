package net.tfminecraft.thievery.key;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.key.KeyDefinition;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.loader.KeyCopyLoader;
import net.tfminecraft.thievery.loader.KeyLoader;
import net.tfminecraft.thievery.utils.Keys;

public final class KeyCopyHandler {

    public static final class CopyMetadata {
        private final String doorKeyUuid;
        private final double sourceStrength;
        private final String sourceKeyId;

        public CopyMetadata(String doorKeyUuid, double sourceStrength, String sourceKeyId) {
            this.doorKeyUuid = doorKeyUuid;
            this.sourceStrength = sourceStrength;
            this.sourceKeyId = sourceKeyId;
        }

        public String getDoorKeyUuid() {
            return doorKeyUuid;
        }

        public double getSourceStrength() {
            return sourceStrength;
        }

        public String getSourceKeyId() {
            return sourceKeyId;
        }
    }

    private KeyCopyHandler() {}

    public static boolean isMasterKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return KeyLoader.resolve(item) != null && !hasCopyKind(item);
    }

    public static boolean isMold(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(Keys.keyMoldMarker, PersistentDataType.BYTE)) {
            return false;
        }
        if (!meta.getPersistentDataContainer().has(Keys.keyUUIDKey, PersistentDataType.STRING)) {
            return false;
        }
        return KeyCopyLoader.matchesMoldOutput(item);
    }

    public static boolean isPermanentCopy(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String kind = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keyCopyKind, PersistentDataType.STRING);
        return Keys.COPY_KIND_PERMANENT.equals(kind) && KeyCopyLoader.matchesCopyOutput(item);
    }

    public static boolean isPaperCopy(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String kind = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keyCopyKind, PersistentDataType.STRING);
        return Keys.COPY_KIND_PAPER.equals(kind) && KeyCopyLoader.matchesPaperOutput(item);
    }

    public static boolean isCopyItem(ItemStack item) {
        return isPermanentCopy(item) || isPaperCopy(item);
    }

    public static boolean isStorableDoorKey(ItemStack item) {
        return isMasterKey(item) || isPermanentCopy(item) || isPaperCopy(item);
    }

    public static String getDoorKeyUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keyUUIDKey, PersistentDataType.STRING);
    }

    public static double getSourceStrength(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0.0;
        }
        Double stored = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keySourceStrength, PersistentDataType.DOUBLE);
        if (stored != null) {
            return stored;
        }
        KeyDefinition definition = KeyLoader.resolve(item);
        return definition != null ? definition.getStrength() : 0.0;
    }

    public static String getSourceKeyId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.keySourceKeyId, PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        KeyDefinition definition = KeyLoader.resolve(item);
        return definition != null ? definition.getId() : null;
    }

    public static CopyMetadata extractCopyMetadata(ItemStack source) {
        if (source == null) {
            return null;
        }
        String uuid = getDoorKeyUuid(source);
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        double strength = getSourceStrength(source);
        String keyId = getSourceKeyId(source);
        return new CopyMetadata(uuid, strength, keyId);
    }

    /**
     * Assigns a door key UUID to an unused master key. Copies and molds are unchanged.
     */
    public static boolean ensureKeyInitialized(ItemStack item) {
        if (!isMasterKey(item)) {
            return false;
        }
        if (getDoorKeyUuid(item) != null) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING,
                UUID.randomUUID().toString());
        item.setItemMeta(meta);
        return true;
    }

    public static ItemStack createMold(CopyMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(KeyCopyLoader.getMoldOutput());
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return stampMetadata(item, metadata, true, null);
    }

    public static ItemStack createPermanentCopy(CopyMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(KeyCopyLoader.getCopyOutput());
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return stampMetadata(item, metadata, false, Keys.COPY_KIND_PERMANENT);
    }

    public static ItemStack createPaperCopy(CopyMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(KeyCopyLoader.getPaperOutput());
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return stampMetadata(item, metadata, false, Keys.COPY_KIND_PAPER);
    }

    public static boolean canCraftPaper(Player player, String doorKeyUuid) {
        if (player == null || doorKeyUuid == null) {
            return false;
        }
        PlayerData data = Thievery.getPlayerManager().get(player.getUniqueId());
        return !data.isPaperKeyOnCooldown(doorKeyUuid);
    }

    public static long getPaperCooldownRemainingMinutes(Player player, String doorKeyUuid) {
        if (player == null || doorKeyUuid == null) {
            return 0;
        }
        PlayerData data = Thievery.getPlayerManager().get(player.getUniqueId());
        return data.getPaperKeyCooldownRemainingMinutes(doorKeyUuid);
    }

    public static void recordPaperCooldown(Player player, String doorKeyUuid) {
        if (player == null || doorKeyUuid == null) {
            return;
        }
        PlayerData data = Thievery.getPlayerManager().get(player.getUniqueId());
        data.recordPaperKeyCooldown(doorKeyUuid, KeyCopyLoader.getPaperCooldownMinutes());
        Database.savePlayerData(data);
    }

    private static ItemStack stampMetadata(ItemStack item, CopyMetadata metadata, boolean mold,
            String copyKind) {
        ItemStack result = item.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, metadata.getDoorKeyUuid());
        meta.getPersistentDataContainer().set(Keys.keySourceStrength, PersistentDataType.DOUBLE,
                metadata.getSourceStrength());
        if (metadata.getSourceKeyId() != null) {
            meta.getPersistentDataContainer().set(Keys.keySourceKeyId, PersistentDataType.STRING,
                    metadata.getSourceKeyId());
        }
        if (mold) {
            meta.getPersistentDataContainer().set(Keys.keyMoldMarker, PersistentDataType.BYTE, (byte) 1);
        }
        if (copyKind != null) {
            meta.getPersistentDataContainer().set(Keys.keyCopyKind, PersistentDataType.STRING, copyKind);
        }
        result.setItemMeta(meta);
        return result;
    }

    private static boolean hasCopyKind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(Keys.keyCopyKind, PersistentDataType.STRING);
    }
}
