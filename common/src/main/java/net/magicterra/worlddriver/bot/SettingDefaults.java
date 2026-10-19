package net.magicterra.worlddriver.bot;

import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.api.methods.setting.SettingDefs;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.config.SettingsRegistry;

/**
 * Hands the contract each setting's default: the value its field held when this build started. The
 * contract cannot read the settings and the settings cannot name the contract, so the facade, which sees
 * both, joins them. Names no game class, so the artifact generator calls it too.
 */
public final class SettingDefaults {
    private SettingDefaults() {}

    /** Each key with a field behind it, mapped to that field's compiled-in value as the key's type decodes it. */
    public static Map<String, Object> compiled() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SettingDefs.Def d : SettingDefs.all()) {
            Object raw = SettingsRegistry.compiledDefault(d.key());
            if (raw != null) out.put(d.key(), d.type().decode(json(raw)));
        }
        return out;
    }

    /** A field's compiled-in value as the JSON its key's type reads: a scalar, or a list of ids. */
    private static JsonElement json(Object raw) {
        if (raw instanceof List<?> l) {
            JsonArray a = new JsonArray(l.size());
            for (Object o : l) a.add(String.valueOf(o));
            return a;
        }
        if (raw instanceof Number n) return new JsonPrimitive(n);
        if (raw instanceof Boolean b) return new JsonPrimitive(b);
        return new JsonPrimitive(String.valueOf(raw));
    }

    /** Install {@link #compiled()} into the contract, so the schema and declarations show the defaults. */
    public static void install() { SettingsContracts.useDefaults(compiled()); }
}
