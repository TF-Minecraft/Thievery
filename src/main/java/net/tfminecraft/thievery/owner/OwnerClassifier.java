package net.tfminecraft.thievery.owner;

import java.util.Optional;
import java.util.UUID;

/**
 * Classifies a bare owner UUID that has no {@code ownerKind} marker (rule 3): a character first, then an account
 * with a reviewed heir, otherwise unresolved. The nil UUID is the server and is recognised before any lookup.
 */
public final class OwnerClassifier {

    /**
     * @param owner      the owner to use in memory
     * @param heir       for {@link LockOwner.Kind#LEGACY}, the heir character the migration moves the lock to
     * @param definitive whether the answer may be persisted: the provider was available and, for unresolved
     *                   values, the account's characters were known
     */
    public record Classification(LockOwner owner, UUID heir, boolean definitive) {}

    private OwnerClassifier() {}

    public static Classification classify(UUID value) {
        if (LockOwner.NIL.equals(value)) {
            return new Classification(LockOwner.server(), null, true);
        }
        if (!OwnerIdentity.available()) {
            return new Classification(LockOwner.unresolved(value), null, false);
        }
        if (OwnerIdentity.info(value).isPresent()) {
            return new Classification(LockOwner.character(value), null, true);
        }
        Optional<UUID> heir = OwnerIdentity.heir(value);
        if (heir.isPresent()) {
            return new Classification(LockOwner.legacy(value), heir.get(), true);
        }
        boolean definitive = OwnerIdentity.heirsReady() && OwnerIdentity.accountKnown(value);
        return new Classification(LockOwner.unresolved(value), null, definitive);
    }
}
