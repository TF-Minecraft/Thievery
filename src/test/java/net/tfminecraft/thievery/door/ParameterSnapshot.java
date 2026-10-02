package net.tfminecraft.thievery.door;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import net.tfminecraft.thievery.cache.Parameters;

/** Saves every tunable in {@link Parameters} so a test can change them freely and put them back. */
final class ParameterSnapshot {
    private final Map<Field, Object> values = new HashMap<>();

    void take() {
        values.clear();
        for (Field field : Parameters.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && !Modifier.isFinal(modifiers)) {
                try {
                    values.put(field, field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    void restore() {
        values.forEach((field, value) -> {
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
