package net.magicterra.worlddriver.bot.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntitySelectorTest {

    @Test
    void digitsNameAnIdAndNothingElse() {
        assertTrue(EntitySelector.matches("42", 42, "42", "minecraft:zombie"));
        assertFalse(EntitySelector.matches("42", 7, "42", "minecraft:zombie"));
    }

    @Test
    void digitsPastAnIntNameNoOneInsteadOfThrowing() {
        assertTrue(EntitySelector.isId("99999999999"));
        assertNull(EntitySelector.id("99999999999"));
        assertFalse(EntitySelector.matches("99999999999", Integer.MAX_VALUE, "", "minecraft:zombie"));
    }

    @Test
    void aWordIsANameOrATypeWithOrWithoutItsNamespace() {
        assertTrue(EntitySelector.matches("Steve", 1, "Steve", "minecraft:player"));
        assertTrue(EntitySelector.matches("zombie", 1, "Zombie", "minecraft:zombie"));
        assertTrue(EntitySelector.matches("minecraft:zombie", 1, "Zombie", "minecraft:zombie"));
        assertFalse(EntitySelector.matches("skeleton", 1, "Zombie", "minecraft:zombie"));
        assertFalse(EntitySelector.matches(" ", 1, " ", "minecraft:zombie"));
    }
}
