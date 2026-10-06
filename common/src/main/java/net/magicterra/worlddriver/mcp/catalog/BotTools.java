package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.bot.SettingsDocs;
import net.magicterra.worlddriver.bot.SettingsRegistry;
import net.magicterra.worlddriver.mcp.schema.Schema;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/**
 * {@code mc.bot.*} catalog entries for the verbs not yet on typed contracts. The body tasks (goto,
 * mine, craft, …) carry their own schemas in {@code api/methods}. See {@code ToolCatalog} for ordering.
 */
public final class BotTools {
    private BotTools() {}

    /**
     * The CLOSED {@code mc.bot.setting} input schema, built from the single-source
     * {@link SettingsRegistry} — one prop per known key ({@code additionalProperties(false)}), so
     * {@code SchemaValidator} rejects an unknown key at route() for every transport (#280). The key
     * set + types come from the registry (derived from BotConfig's settable fields), so this schema
     * can never lag a newly-declared flag the way the old hand-written prop list did.
     */
    private static Schema.Obj settingSchema() {
        Schema.Obj obj = object();
        SettingsRegistry.schemaProps().forEach(
            (key, type) -> obj.prop(key, schemaFor(type, SettingsDocs.of(key))));
        return obj
            .additionalProperties(false)
            .desc("Closed key bag: keys are validated against the single-source SettingsRegistry "
                + "(derived from BotConfig's settable fields). ALL-OR-NOTHING — a call containing "
                + "ANY unknown key is rejected outright and NOTHING is applied, so an A/B script "
                + "fails loudly instead of silently half-applying. Out-of-range values are "
                + "soft-rejected into rejected[] with ok:true, so numeric bounds are documented in "
                + "the description above, not enforced here.");
    }

    /**
     * Maps a registry key {@link SettingsRegistry.Type} to its typed {@link Schema} node,
     * carrying that key's documentation ({@code null} for a key with no row yet).
     *
     * <p>The per-key prose lives in {@link SettingsDocs} rather than in this tool's
     * description string, where it was a ~35,000-character hand-maintained ASCII table —
     * generated schema below it, hand-edited table above it, and the table had already
     * fallen behind the key set. Attaching each line to the key it describes is what makes
     * that drift impossible; {@link SettingsRegistry} fails class load on an orphaned row.
     */
    private static Schema schemaFor(SettingsRegistry.Type type, String doc) {
        return switch (type) {
            case BOOLEAN     -> doc == null ? bool() : bool().desc(doc);
            case INTEGER     -> doc == null ? integer() : integer().desc(doc);
            case NUMBER      -> doc == null ? number() : number().desc(doc);
            case STRING      -> doc == null ? string() : string().desc(doc);
            case STRING_LIST -> doc == null ? array(string()) : array(string()).desc(doc);
            case POINT_LIST  -> {
                Schema.Arr a = array(object()
                        .req("x", number()).req("y", number()).req("z", number())
                        .prop("radius", number()));
                yield doc == null ? a : a.desc(doc);
            }
        };
    }

    public static List<ToolSchema> tools() {
        List<ToolSchema> all = List.of(
            wrTool("mc.bot.setting",
                "Read/write bot tuning + Baritone-style toggles. Empty params = read; pass keys " +
                "to write (next tick). EVERY key is listed in this tool's inputSchema with its " +
                "type, range and a one-line explanation — read the schema, not a table here: the " +
                "table this description used to carry was ~35k characters of hand-maintained ASCII " +
                "that had already fallen behind the key set (see SettingsDocs). " +
                "Returns {ok, settings, applied?, rejected?, inert?}. Unknown keys are REJECTED " +
                "(all-or-nothing: a call carrying any key not in the schema applies NOTHING and " +
                "errors, so an A/B script fails loudly instead of silently half-applying).",
                settingSchema()
                ),

            wrTool("mc.bot.playbook",
                "Run a multi-phase boss playbook (Phase G) — a hot-reloadable Rhino script that " +
                "orchestrates combat/goto/equip/setting + boss sensing into a full fight. Runs on a " +
                "background thread (returns immediately) so the multi-minute loop outlives the " +
                "mc.script.eval cap. op='start' (default) needs name (built-ins: 'dragon'|'wither'); returns " +
                "{ok, started, name}. op='status' returns {ok, active, name?, aborting, lastResult?, " +
                "lastError?} — poll lastResult for the fight outcome. op='cancel' asks it to stop " +
                "(honoured at the next loop turn). One playbook at a time. The dragon playbook clears " +
                "the End crystals (hard gate) then perch-melees/bow-kites the dragon; the wither " +
                "playbook gear-gates on Phase F (aborts if not full armor + sword), then summons " +
                "(unless summon:false) and melee-grinds both phases.",
                object()
                    .prop("name", string()
                        .desc("Which playbook to start (op=start). Built-ins: 'dragon', 'wither'; "
                            + "playbooks are hot-reloadable scripts, so any [a-z][a-z0-9_]* name "
                            + "that resolves to a playbook body is accepted (unknown names get a "
                            + "business-layer {ok:false, error:'unknown playbook: …'})."))
                    .prop("op", stringEnum("start", "status", "cancel")
                        .desc("start (default) | status | cancel."))
                    .prop("summon", bool()
                        .desc("Wither only: summon the boss (default true; false = just gear-check)."))
                    .prop("maxRounds", integer()
                        .desc("Safety cap on loop iterations before giving up."))
                    .prop("budgetMs", integer().min(1)
                        .desc("Wall-clock budget for the playbook loop in ms (default 600000)."))
                    .additionalProperties(true)
                    .desc("The whole params object is injected into the script as the PLAYBOOK "
                        + "global, so playbooks may read extra free-form tuning keys.")
                )
        );
        return all;
    }
}
