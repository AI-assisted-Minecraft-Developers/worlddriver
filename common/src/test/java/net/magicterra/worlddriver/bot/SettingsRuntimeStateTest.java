package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Some {@link BotConfig} fields are state the bot rewrites every tick, not settings. Accepting a
 * write to one reports {@code applied} for a value that is gone a tick later, so they must not be
 * on the {@code mc.bot.setting} surface at all.
 */
class SettingsRuntimeStateTest {

    private static final List<String> RUNTIME = List.of(
            "fleeActive", "walkerDigActive", "walkerCruiseActive", "pathfinderBoxedEscalate");

    private Map<String, Object> saved;

    @BeforeEach void save() { saved = BotConfig.snapshotAll(); }
    @AfterEach void restore() { BotConfig.restoreAll(saved); }

    @Test
    void perTickStateIsNotASetting() {
        for (String key : RUNTIME) {
            assertFalse(SettingsRegistry.isKnown(key), key + " is advertised as a setting");
            assertFalse(SettingsRegistry.schemaProps().containsKey(key), key + " is in the schema");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SettingsCommand.write(null, Map.of(key, true)), key + " was accepted");
            assertTrue(e.getMessage().contains("unknown key"), e.getMessage());
        }
    }

    /** The annotation is the one mechanism: whatever carries it is off the surface, and nothing else is. */
    @Test
    void theAnnotationDecidesAndNothingElse() throws NoSuchFieldException {
        for (String key : RUNTIME) {
            assertTrue(BotConfig.class.getField(key).isAnnotationPresent(RuntimeState.class), key);
        }
        for (Field f : BotConfig.class.getFields()) {
            if (f.isAnnotationPresent(RuntimeState.class)) {
                assertFalse(SettingsRegistry.isKnown(f.getName()), f.getName());
            }
        }
        assertTrue(SettingsRegistry.isKnown("allowBreak"), "control: an ordinary flag is still a setting");
    }
}
