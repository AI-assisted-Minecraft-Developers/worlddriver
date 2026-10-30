package net.magicterra.worlddriver.game.observe;

import com.google.common.primitives.Ints;

/**
 * The one reading of an entity selector string: all digits is an entity id, anything else matches a display or profile
 * name exactly, or a type id with or without its namespace ({@code zombie}, {@code minecraft:zombie}).
 */
public final class EntitySelector {
    private EntitySelector() {}

    public static boolean isId(String selector) {
        String s = selector == null ? "" : selector.trim();
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    /** The id an {@link #isId id selector} names; null for digits past an int, which name no one. */
    public static Integer id(String selector) {
        return Ints.tryParse(selector.trim());
    }

    public static boolean matches(String selector, int id, String name, String typeId) {
        if (selector == null || selector.isBlank()) return false;
        if (isId(selector)) {
            Integer n = id(selector);
            return n != null && n == id;
        }
        String s = selector.trim();
        return s.equals(name) || RegistryIds.qualified(s).equals(typeId);
    }
}
