package net.magicterra.worlddriver.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.magicterra.worlddriver.api.methods.setting.SettingDefs;
import net.magicterra.worlddriver.bot.SettingDefaults;
import org.junit.jupiter.api.Test;

/**
 * The contract declares the keys and this side binds them to fields, by wire name; nothing but this test
 * holds the two together. A key with no field answers nothing, and a field with no key cannot be set.
 */
class SettingsBindingTest {

    /** A control with no value behind it: applied through the bot, never read. */
    private static final Set<String> UNBOUND = Set.of("debugFly");

    @Test
    void everyKeyIsBoundToAField() {
        List<String> bad = new ArrayList<>();
        for (SettingDefs.Def d : SettingDefs.all()) {
            boolean bound = SettingsRegistry.readOf(d.key()) == SettingsRegistry.Read.BOT_PAUSED
                    || SettingsRegistry.boundField(d.key()) != null;
            if (bound == UNBOUND.contains(d.key())) bad.add(d.key());
        }
        assertTrue(bad.isEmpty(), "keys bound to no field (or a control bound to one): " + bad);
    }

    @Test
    void everySettingFieldHasExactlyOneKey() {
        Map<String, List<String>> keysOf = new LinkedHashMap<>();
        for (Field f : BotConfig.publicFields()) {
            if (SettingsRegistry.isSetting(f)) keysOf.put(f.getName(), new ArrayList<>());
        }
        for (SettingDefs.Def d : SettingDefs.all()) {
            Field f = SettingsRegistry.boundField(d.key());
            if (f != null) keysOf.get(f.getName()).add(d.key());
        }
        List<String> bad = new ArrayList<>();
        keysOf.forEach((field, keys) -> { if (keys.size() != 1) bad.add(field + " ← " + keys); });
        assertTrue(bad.isEmpty(), "setting fields not bound by exactly one key; declare one in SettingDefs:\n  "
                + String.join("\n  ", bad));
    }

    /** Every compiled-in value decodes as its key's type, so the schema can show it as the default. */
    @Test
    void everyDefaultFitsItsKey() {
        Map<String, Object> defaults = assertDoesNotThrow(SettingDefaults::compiled);
        assertEquals(Boolean.FALSE, defaults.get("autoEat"));
        assertEquals(1.0, defaults.get("pathfinder.heuristicWeight"));
        assertTrue(defaults.size() > 200, "only " + defaults.size() + " defaults");
    }

    /** A default is the field's to state; prose that states one goes stale the day the field changes. */
    @Test
    void noDescriptionStatesADefault() {
        Pattern claim = Pattern.compile("\\bdflt\\b|\\b(?:on|off) by default\\b|\\bdefaults? (?:to )?(?:on|off|true|false|-?\\d)",
                Pattern.CASE_INSENSITIVE);
        List<String> bad = new ArrayList<>();
        for (SettingDefs.Def d : SettingDefs.all()) {
            if (d.description() != null && claim.matcher(d.description()).find()) bad.add(d.key() + ": " + d.description());
        }
        assertTrue(bad.isEmpty(), "descriptions stating a default:\n  " + String.join("\n  ", bad));
    }
}
