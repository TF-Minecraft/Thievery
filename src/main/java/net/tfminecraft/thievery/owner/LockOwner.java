package net.tfminecraft.thievery.owner;

import java.util.Objects;
import java.util.UUID;

/**
 * Who owns a lock. On disk the owner stays a bare UUID with a separate {@code ownerKind}; in memory the two always
 * travel together so a copy cannot split them.
 *
 * <ul>
 *   <li>{@link Kind#CHARACTER}: a character ID. Only that character's current session is the owner.</li>
 *   <li>{@link Kind#SERVER}: staff-placed, stored as the nil UUID. Only the staff override opens it.</li>
 *   <li>{@link Kind#LEGACY}: an account UUID whose reviewed heir is known but which has not been migrated yet.</li>
 *   <li>{@link Kind#UNRESOLVED}: an account UUID without a heir, an unclassifiable value, or an unreadable record
 *       ({@code id} is then null).</li>
 * </ul>
 */
public record LockOwner(Kind kind, UUID id) {

    public enum Kind {
        CHARACTER,
        LEGACY,
        UNRESOLVED,
        SERVER
    }

    /** The owner value stored for {@link Kind#SERVER}. */
    public static final UUID NIL = new UUID(0L, 0L);

    private static final LockOwner SERVER = new LockOwner(Kind.SERVER, NIL);
    private static final LockOwner UNREADABLE = new LockOwner(Kind.UNRESOLVED, null);

    public LockOwner {
        Objects.requireNonNull(kind, "kind");
        if (kind != Kind.UNRESOLVED) {
            Objects.requireNonNull(id, "id");
        }
    }

    public static LockOwner character(UUID characterId) {
        return new LockOwner(Kind.CHARACTER, characterId);
    }

    public static LockOwner server() {
        return SERVER;
    }

    public static LockOwner legacy(UUID accountId) {
        return new LockOwner(Kind.LEGACY, accountId);
    }

    public static LockOwner unresolved(UUID value) {
        return new LockOwner(Kind.UNRESOLVED, value);
    }

    /** The owner of a record that could not be read. */
    public static LockOwner unreadable() {
        return UNREADABLE;
    }

    public boolean isCharacter() {
        return kind == Kind.CHARACTER;
    }

    public boolean isServer() {
        return kind == Kind.SERVER;
    }

    /** Character or server: an owner the current code can act on. Legacy and unresolved owners deny everyone. */
    public boolean isResolved() {
        return kind == Kind.CHARACTER || kind == Kind.SERVER;
    }

    /** The marker written next to the owner, or null for values that are never written with a marker. */
    public String persistedKind() {
        return isResolved() ? kind.name() : null;
    }
}
