package net.tfminecraft.thievery.owner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.tlibs.character.CharacterInfo;

/**
 * Character names for staff commands through TLibs 2.5.0's {@code Characters.findByName}, found by a one-time
 * probe so Thievery still runs on TLibs 2.4.
 */
public final class CharacterNames {

    private static Method findByName;
    private static boolean probed;

    private CharacterNames() {}

    public static boolean available() {
        if (!probed) {
            findByName = probe("net.tfminecraft.tlibs.character.Characters");
            probed = true;
        }
        return findByName != null;
    }

    static Method probe(String className) {
        try {
            return Class.forName(className).getMethod("findByName", String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** For tests: use this lookup instead of probing. */
    static void use(Method lookup) {
        probed = true;
        findByName = lookup;
    }

    /** Living characters with this real name; empty when the lookup is unavailable. */
    public static List<CharacterInfo> find(String name) {
        if (!available() || !OwnerIdentity.available()) {
            return List.of();
        }
        try {
            List<CharacterInfo> matches = new ArrayList<>();
            for (Object match : (List<?>) findByName.invoke(null, name)) {
                if (match instanceof CharacterInfo info && info.alive()) {
                    matches.add(info);
                }
            }
            return matches;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }
}
