package net.magicterra.worlddriver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.schema.annotation.Wire;
import org.junit.jupiter.api.Test;

/**
 * The contract declares the keys and this side binds them to fields, by wire name; nothing but this test holds the two
 * together. A key with no field answers nothing, and a field with no key cannot be set.
 */
class SettingsBindingTest {

    /** A control with no value behind it: applied through the bot, never read. */
    private static final Set<String> UNBOUND = Set.of("debugFly");

    @Test
    void everyKeyIsBoundToAField() {
        List<String> bad = new ArrayList<>();
        for (String key : SettingsContracts.TYPES.keySet()) {
            boolean bound = SettingsRegistry.readOf(key) == SettingsRegistry.Read.BOT_PAUSED
                    || SettingsRegistry.boundField(key) != null;
            if (bound == UNBOUND.contains(key)) {
                bad.add(key);
            }
        }
        assertTrue(bad.isEmpty(), "keys bound to no field (or a control bound to one): " + bad);
    }

    @Test
    void everySettingFieldHasExactlyOneKey() {
        Map<String, List<String>> keysOf = new LinkedHashMap<>();
        for (Field f : BotConfig.publicFields()) {
            if (SettingsRegistry.isSetting(f)) {
                keysOf.put(f.getName(), new ArrayList<>());
            }
        }
        for (String key : SettingsContracts.TYPES.keySet()) {
            Field f = SettingsRegistry.boundField(key);
            if (f != null) {
                keysOf.get(f.getName()).add(key);
            }
        }
        List<String> bad = new ArrayList<>();
        keysOf.forEach((field, keys) -> {
            if (keys.size() != 1) {
                bad.add(field + " ← " + keys);
            }
        });
        assertTrue(
                bad.isEmpty(),
                "setting fields not bound by exactly one key; declare one in its layer's settings " + "record:\n  "
                        + String.join("\n  ", bad));
    }

    /** A key's component is named as the field that holds it, in the record of that field's layer. */
    @Test
    void everyComponentNamesItsField() {
        List<String> bad = new ArrayList<>();
        for (Class<? extends Record> layer : SettingsContracts.LAYERS) {
            for (RecordComponent c : layer.getRecordComponents()) {
                Wire wire = c.getAnnotation(Wire.class);
                Field f = SettingsRegistry.boundField(wire == null ? c.getName() : wire.value());
                if (f == null) {
                    continue;
                }
                String owner = f.getDeclaringClass().getSimpleName().replace("Config", "Settings");
                if (!f.getName().equals(c.getName()) || !owner.equals(layer.getSimpleName())) {
                    bad.add(layer.getSimpleName() + "." + c.getName() + " → "
                            + f.getDeclaringClass().getSimpleName() + "." + f.getName());
                }
            }
        }
        assertTrue(
                bad.isEmpty(),
                "components not named as their fields, or in another layer's record:\n  " + String.join("\n  ", bad));
    }

    /** Each field starts from its key's default, so the schema shows what a fresh build runs with. */
    @Test
    void everyFieldStartsFromItsDefault() {
        List<String> bad = new ArrayList<>();
        SettingsContracts.DEFAULTS.forEach((key, d) -> {
            Field f = SettingsRegistry.boundField(key);
            String s = BotConfig.COMPILED_DEFAULTS.get(f.getName());
            Class<?> t = f.getType();
            boolean same = t == float.class
                    ? Float.parseFloat(s) == ((Number) d).floatValue()
                    : t == double.class
                            ? Double.parseDouble(s) == ((Number) d).doubleValue()
                            : t == int.class || t == long.class
                                    ? Long.parseLong(s) == ((Number) d).longValue()
                                    : t == Set.class
                                            ? Set.of(s.isEmpty() ? new String[0] : s.split(","))
                                                    .equals(Set.copyOf((List<?>) d))
                                            : s.equals(String.valueOf(d));
            if (!same) {
                bad.add(key + ": field " + s + ", default " + d);
            }
        });
        assertTrue(bad.isEmpty(), "fields that do not start from their key's default:\n  " + String.join("\n  ", bad));
        assertEquals(Boolean.FALSE, SettingsContracts.DEFAULTS.get("autoEat"));
        assertEquals(1.0, SettingsContracts.DEFAULTS.get("pathfinder.heuristicWeight"));
        assertTrue(SettingsContracts.DEFAULTS.size() > 200, "only " + SettingsContracts.DEFAULTS.size() + " defaults");
    }

    /** A default is the field's to state; prose that states one goes stale the day the field changes. */
    @Test
    void noDescriptionStatesADefault() {
        Pattern claim = Pattern.compile(
                "\\bdflt\\b|\\b(?:on|off) by default\\b|\\bdefaults? (?:to )?(?:on|off|true|false|-?\\d)",
                Pattern.CASE_INSENSITIVE);
        List<String> bad = new ArrayList<>();
        SettingsContracts.DOCS.forEach((key, doc) -> {
            if (claim.matcher(doc).find()) {
                bad.add(key + ": " + doc);
            }
        });
        assertTrue(bad.isEmpty(), "descriptions stating a default:\n  " + String.join("\n  ", bad));
    }
}
