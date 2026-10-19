package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * The arbitration layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that
 * holds the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every
 * default, and each field starts from it; a key with no field of its own is optional and has none.
 */
public record ArbitrationSettings(
        @Doc("Flee to safety when HP drops below retreatHpThreshold (in-engine reflex; the only reliable mob "
                + "defense — an MCP round-trip is too slow to react to a swarm)")
        @Default("false") boolean autoRetreat,
        @Doc("HP at/below which autoRetreat flees.")
        @Default("6.0") @Range(min = 0, max = 20) double retreatHpThreshold,
        @Doc("\"dig three, fill one\" emergency dig-in: when CORNERED (HP≤bunkerHpThreshold AND ≥bunkerMinHostiles "
                + "hostiles within bunkerTriggerRadius) dig straight down bunkerDepth blocks and seal the roof with "
                + "the dug block — the no-gear survival move vs a swarm. Modifies the world.")
        @Default("false") boolean autoBunker,
        @Doc("HP at/below which the bunker reflex may trigger (needs buffer to finish digging under fire)")
        @Default("10.0") @Range(min = 0, max = 20) double bunkerHpThreshold,
        @Doc("A hostile within this many blocks counts as 'surrounding' (a moving swarm clusters at 5-8)")
        @Default("7.0") @Range(min = 1, max = 16) double bunkerTriggerRadius,
        @Doc("How many surrounding hostiles before digging in.")
        @Default("2") @Range(min = 1, max = 10) int bunkerMinHostiles,
        @Doc("Blocks to dig straight down before sealing.")
        @Default("2") @Range(min = 1, max = 5) int bunkerDepth,
        @Doc("Sidestep creeper detonations and incoming projectiles.")
        @Default("false") boolean autoDodge,
        @Doc("A swelling creeper this close makes the bot sprint away.")
        @Default("3.5") @Range(min = 1, max = 16) double creeperKeepDistance,
        @Doc("An incoming projectile predicted to hit within this triggers a sidestep.")
        @Default("12.0") @Range(min = 1, max = 32) double projectileDodgeRadius,
        @Doc("Auto-attack nearby hostiles scoring above autoFightThreatThreshold (CombatChain)")
        @Default("false") boolean autoFight,
        @Doc("Threat score a hostile must reach to trigger autoFight.")
        @Default("0.05") @Range(min = 0, max = 1) double autoFightThreatThreshold,
        @Doc("Idle-only dusk shelter: when sky-exposed at dusk/night with no user task, dig a \"dig three, fill "
                + "one\" bunker (DuskSecureChain, priority IDLE_SECURE=40). Enable for fully autonomous survival "
                + "runs.")
        @Default("false") boolean autoSecureAtDusk,
        @Doc("ACTIVE-process drowning reflex (DrownEscapeChain, priority 500): underwater with air <= "
                + "drownEscapeAirThreshold PREEMPTS the running process (mine/goto/...) and floats straight up — "
                + "pure vertical, breaks a solid lid overhead when allowBreak is on — until air >= "
                + "drownEscapeReleaseAir or the head surfaces and air recovers. Complements the idle-only "
                + "autoFloatWhenDrowning (gap#76)")
        @Default("true") boolean autoDrownEscape,
        @Doc("Air ticks at/above which DrownEscapeChain hands the channel back (wide hysteresis; values >300 clamp "
                + "to vanilla max)")
        @Default("280") int drownEscapeReleaseAir,
        @Default("6.0") double combatFrailThreshold,
        @Default("true") boolean duskUrgent,
        @Default("false") boolean duskUrgentDryRun) {

    public static final ArbitrationSettings DEFAULTS =
            ApiTypes.record(ArbitrationSettings.class).decode(new JsonObject());
}
