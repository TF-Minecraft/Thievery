package net.tfminecraft.thievery.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.LockTypeProfile;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParametersTest {
    private final Map<LockState, LockTypeProfile> originalProfiles = new EnumMap<>(LockState.class);
    private Set<String> originalFurniture;
    private Set<EntityType> originalEntities;
    private Locale originalLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void saveState() throws Exception {
        Field profiles = Parameters.class.getDeclaredField("lockTypeProfiles");
        profiles.setAccessible(true);
        originalProfiles.putAll((Map<LockState, LockTypeProfile>) profiles.get(null));
        originalFurniture = Parameters.lockableFurnitureIds;
        originalEntities = Parameters.lockableEntityTypes;
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        Parameters.clearLockTypeProfiles();
        Parameters.lockableFurnitureIds = new HashSet<>();
        Parameters.lockableEntityTypes = EnumSet.noneOf(EntityType.class);
    }

    @AfterEach
    void restoreState() {
        Parameters.clearLockTypeProfiles();
        originalProfiles.forEach(Parameters::putLockTypeProfile);
        Parameters.lockableFurnitureIds = originalFurniture;
        Parameters.lockableEntityTypes = originalEntities;
        Locale.setDefault(originalLocale);
    }

    @Test
    void absentProfilesUseIdentityAndValidEntriesCanBeReplacedAndCleared() {
        assertSame(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(null));
        for (LockState state : LockState.values()) {
            assertSame(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(state));
        }
        LockTypeProfile first = new LockTypeProfile(2, 3, false, 4);
        LockTypeProfile second = new LockTypeProfile(0.5, 0.25, true, 0.1);
        Parameters.putLockTypeProfile(LockState.GUILD, first);
        assertSame(first, Parameters.lockTypeProfile(LockState.GUILD));
        assertSame(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(LockState.PRIVATE));
        Parameters.putLockTypeProfile(LockState.GUILD, second);
        assertSame(second, Parameters.lockTypeProfile(LockState.GUILD));
        Parameters.clearLockTypeProfiles();
        assertSame(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(LockState.GUILD));
    }

    @Test
    void invalidProfileEntriesDoNotReplaceExistingConfiguration() {
        LockTypeProfile profile = new LockTypeProfile(2, 3, false, 4);
        Parameters.putLockTypeProfile(LockState.PRIVATE, profile);
        Parameters.putLockTypeProfile(null, profile);
        Parameters.putLockTypeProfile(LockState.PRIVATE, null);
        assertSame(profile, Parameters.lockTypeProfile(LockState.PRIVATE));
        assertSame(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(null));
    }

    @Test
    void furnitureMatchingAcceptsConfiguredIdsCaseInsensitivelyAndRejectsMissingIds() {
        Parameters.lockableFurnitureIds.add("wooden_chest");
        assertTrue(Parameters.isLockableFurnitureId("wooden_chest"));
        assertTrue(Parameters.isLockableFurnitureId("WOODEN_CHEST"));
        assertFalse(Parameters.isLockableFurnitureId("other"));
        assertFalse(Parameters.isLockableFurnitureId(null));
        assertFalse(Parameters.isLockableFurnitureId(" \t "));
    }

    @Test
    void entityMatchingRequiresAConfiguredNonNullType() {
        Parameters.lockableEntityTypes.add(EntityType.ARMOR_STAND);
        assertTrue(Parameters.isLockableEntityType(EntityType.ARMOR_STAND));
        assertFalse(Parameters.isLockableEntityType(EntityType.ITEM_FRAME));
        assertFalse(Parameters.isLockableEntityType(null));
    }
}
