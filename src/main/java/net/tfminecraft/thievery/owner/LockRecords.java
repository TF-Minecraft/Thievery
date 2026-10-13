package net.tfminecraft.thievery.owner;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.thievery.door.LockState;

/**
 * The JSON shapes of the three lock stores. Decoding is strict: anything unreadable throws
 * {@link IllegalArgumentException}, and callers load such a record as locked with an unresolved owner (rule 5).
 * Old jars read these files with Gson field binding, so the field names stay as they were and {@code ownerKind}
 * is simply ignored by them.
 */
public final class LockRecords {

    public static final String CONTAINER_OWNER = "owner";
    public static final String DOOR_OWNER = "ownerUUID";
    public static final String ENTITY_OWNER = "owner";
    public static final String OWNER_KIND = "ownerKind";

    private static final Gson GSON = new Gson();

    public record Container(OwnerCodec.Stored owner, LockState state, Map<UUID, String> accessMap) {}

    public record Door(String key, double strength, OwnerCodec.Stored owner, Long unlockExpiryMs) {}

    public record Entity(OwnerCodec.Stored owner, LockState state) {}

    private LockRecords() {}

    public static Container decodeContainer(byte[] bytes) {
        JsonObject json = object(bytes);
        OwnerCodec.Stored owner = owner(json, CONTAINER_OWNER);
        return new Container(owner, state(json, owner), accessMap(json.get("accessMap")));
    }

    public static Entity decodeEntity(byte[] bytes) {
        JsonObject json = object(bytes);
        OwnerCodec.Stored owner = owner(json, ENTITY_OWNER);
        return new Entity(owner, state(json, owner));
    }

    public static Door decodeDoor(byte[] bytes) {
        JsonObject json = object(bytes);
        String key = text(json, "key");
        if (key == null) {
            throw new IllegalArgumentException("door without a key");
        }
        Long expiry = json.has("unlockExpiryMs") && !json.get("unlockExpiryMs").isJsonNull()
                ? number(json.get("unlockExpiryMs")).longValue() : null;
        double strength = json.has("strength") ? number(json.get("strength")).doubleValue() : 0.0;
        return new Door(key, strength, owner(json, DOOR_OWNER), expiry);
    }

    /** The key of a door record whose other fields may be unreadable, or null when even that is lost. */
    public static String salvageDoorKey(byte[] bytes) {
        try {
            return text(object(bytes), "key");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The lock strength of an unreadable door record, or null when it is lost. */
    public static Double salvageDoorStrength(byte[] bytes) {
        try {
            return number(object(bytes).get("strength")).doubleValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The thief cooldowns of an unreadable container record, or none when they are lost too. */
    public static Map<UUID, String> salvageAccessMap(byte[] bytes) {
        try {
            return accessMap(object(bytes).get("accessMap"));
        } catch (RuntimeException e) {
            return new HashMap<>();
        }
    }

    public static byte[] encodeContainer(Container record) {
        JsonObject json = new JsonObject();
        putOwner(json, CONTAINER_OWNER, record.owner());
        json.addProperty("lockState", record.state().name());
        JsonObject access = new JsonObject();
        new TreeMap<>(record.accessMap()).forEach((thief, date) -> access.addProperty(thief.toString(), date));
        json.add("accessMap", access);
        return bytes(json);
    }

    public static byte[] encodeEntity(Entity record) {
        JsonObject json = new JsonObject();
        putOwner(json, ENTITY_OWNER, record.owner());
        json.addProperty("lockState", record.state().name());
        return bytes(json);
    }

    public static byte[] encodeDoor(Door record) {
        JsonObject json = new JsonObject();
        json.addProperty("key", record.key());
        json.addProperty("strength", record.strength());
        putOwner(json, DOOR_OWNER, record.owner());
        if (record.unlockExpiryMs() != null) {
            json.addProperty("unlockExpiryMs", record.unlockExpiryMs());
        }
        return bytes(json);
    }

    /**
     * The migration's rewrite: the original record with only its owner replaced and the marker added, so fields
     * this jar does not know about survive.
     */
    public static byte[] rewriteOwner(byte[] original, String ownerField, LockOwner owner) {
        JsonObject json = object(original);
        OwnerCodec.Stored stored = OwnerCodec.store(owner);
        json.addProperty(ownerField, stored.id().toString());
        json.addProperty(OWNER_KIND, stored.marker().name());
        return bytes(json);
    }

    static JsonObject object(byte[] bytes) {
        JsonElement element;
        try {
            element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not JSON", e);
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static OwnerCodec.Stored owner(JsonObject json, String field) {
        return OwnerCodec.parse(text(json, field), text(json, OWNER_KIND));
    }

    private static LockState state(JsonObject json, OwnerCodec.Stored owner) {
        String state = text(json, "lockState");
        if (state == null) {
            if (owner.owned()) {
                throw new IllegalArgumentException("owned lock without a state");
            }
            return LockState.DEFAULT;
        }
        return LockState.valueOf(state);
    }

    private static Map<UUID, String> accessMap(JsonElement element) {
        Map<UUID, String> map = new HashMap<>();
        if (element == null || element.isJsonNull()) {
            return map;
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("accessMap is not an object");
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            map.put(UUID.fromString(entry.getKey()), string(entry.getValue()));
        }
        return map;
    }

    private static String text(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return element == null || element.isJsonNull() ? null : string(element);
    }

    private static String string(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("expected a string: " + element);
        }
        return element.getAsString();
    }

    private static Number number(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("expected a number: " + element);
        }
        return element.getAsNumber();
    }

    private static void putOwner(JsonObject json, String field, OwnerCodec.Stored owner) {
        if (owner.owned()) {
            json.addProperty(field, owner.id().toString());
            if (owner.marker() != null) {
                json.addProperty(OWNER_KIND, owner.marker().name());
            }
        }
    }

    private static byte[] bytes(JsonObject json) {
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }
}
