package net.tfminecraft.thievery.owner;

import java.util.UUID;

/** Reads and writes the bare-UUID owner and its {@code ownerKind} marker. */
public final class OwnerCodec {

    /** An owner as stored: the bare UUID and the marker, if any. Both null means unowned. */
    public record Stored(UUID id, LockOwner.Kind marker) {
        public static final Stored NONE = new Stored(null, null);

        public boolean owned() {
            return id != null;
        }

        public boolean markerless() {
            return id != null && marker == null;
        }
    }

    /** A stored owner resolved for use, with whether its classification may be persisted. */
    public record Resolved(LockOwner owner, boolean definitive) {}

    private OwnerCodec() {}

    /**
     * Parses stored owner fields. Throws {@link IllegalArgumentException} when they are unreadable: a bad UUID, an
     * unknown marker, a marker without an owner, or a marker that does not fit the value.
     */
    public static Stored parse(String owner, String kind) {
        if (owner == null) {
            if (kind != null) {
                throw new IllegalArgumentException("ownerKind without an owner");
            }
            return Stored.NONE;
        }
        UUID id = UUID.fromString(owner);
        if (!owner.equalsIgnoreCase(id.toString())) {
            throw new IllegalArgumentException("not a canonical UUID: " + owner);
        }
        if (kind == null) {
            return new Stored(id, null);
        }
        LockOwner.Kind marker = LockOwner.Kind.valueOf(kind);
        boolean nil = LockOwner.NIL.equals(id);
        if (marker == LockOwner.Kind.SERVER ? !nil : marker != LockOwner.Kind.CHARACTER || nil) {
            throw new IllegalArgumentException("ownerKind " + kind + " does not fit " + owner);
        }
        return new Stored(id, marker);
    }

    /** Resolves a stored owner, classifying a markerless value once (rule 3). Null means unowned. */
    public static Resolved resolve(Stored stored) {
        if (!stored.owned()) {
            return new Resolved(null, true);
        }
        if (stored.marker() == LockOwner.Kind.SERVER) {
            return new Resolved(LockOwner.server(), true);
        }
        if (stored.marker() == LockOwner.Kind.CHARACTER) {
            return new Resolved(LockOwner.character(stored.id()), true);
        }
        OwnerClassifier.Classification classification = OwnerClassifier.classify(stored.id());
        return new Resolved(classification.owner(), classification.definitive());
    }

    /** The stored form of an owner. Legacy and unresolved values keep their bare UUID and never get a marker. */
    public static Stored store(LockOwner owner) {
        if (owner == null) {
            return Stored.NONE;
        }
        if (owner.id() == null) {
            // An unreadable record must never be written back as unowned.
            throw new IllegalStateException("An unreadable owner cannot be stored");
        }
        String kind = owner.persistedKind();
        return new Stored(owner.id(), kind == null ? null : LockOwner.Kind.valueOf(kind));
    }
}
