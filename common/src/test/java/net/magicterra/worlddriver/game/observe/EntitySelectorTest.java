package net.magicterra.worlddriver.game.observe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntitySelectorTest {

    private static final String CODE_42 = "42";

    private static final String MINECRAFT_ZOMBIE = "minecraft:zombie";

    @Test
    void digitsNameAnIdAndNothingElse() {
        assertTrue(EntitySelector.matches(CODE_42, 42, CODE_42, MINECRAFT_ZOMBIE));
        assertFalse(EntitySelector.matches(CODE_42, 7, CODE_42, MINECRAFT_ZOMBIE));
    }

    @Test
    void digitsPastAnIntNameNoOneInsteadOfThrowing() {
        assertTrue(EntitySelector.isId("99999999999"));
        assertNull(EntitySelector.id("99999999999"));
        assertFalse(EntitySelector.matches("99999999999", Integer.MAX_VALUE, "", MINECRAFT_ZOMBIE));
    }

    @Test
    void wordIsNameOrTypeWithOrWithoutItsNamespace() {
        assertTrue(EntitySelector.matches("Steve", 1, "Steve", "minecraft:player"));
        assertTrue(EntitySelector.matches("zombie", 1, "Zombie", MINECRAFT_ZOMBIE));
        assertTrue(EntitySelector.matches(MINECRAFT_ZOMBIE, 1, "Zombie", MINECRAFT_ZOMBIE));
        assertFalse(EntitySelector.matches("skeleton", 1, "Zombie", MINECRAFT_ZOMBIE));
        assertFalse(EntitySelector.matches(" ", 1, " ", MINECRAFT_ZOMBIE));
    }
}
