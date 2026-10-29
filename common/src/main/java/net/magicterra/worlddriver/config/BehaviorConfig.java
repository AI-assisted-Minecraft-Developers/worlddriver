package net.magicterra.worlddriver.config;

import net.magicterra.worlddriver.api.methods.setting.BehaviorSettings;

/**
 * Settings read by the verb processes, reflexes, combat and flight ({@code bot/behavior}).
 * Each sits in the lowest layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class BehaviorConfig {
    private BehaviorConfig() {}

    /** Break back a crafting table {@code mc.bot.craft} placed itself, once the craft ends
     *  (gap #276). Without this the table is abandoned where it stood: 4 planks burned at
     *  every craft site, and the world littered with tables. Only ever breaks a table THIS
     *  craft placed — a table found already standing (village, player base) is borrowed and
     *  left exactly as it was. Reclaim is best-effort: if it can't finish, the craft's own
     *  result stands unchanged. Default ON; turn OFF to leave placed tables as landmarks. */
    public static volatile boolean craftReclaimTable = BehaviorSettings.DEFAULTS.craftReclaimTable();

    /** Vertical band (+/-) of mine scans around the player's foot Y. */
    public static volatile int mineSearchVerticalRadius = BehaviorSettings.DEFAULTS.mineSearchVerticalRadius();

    /** Max horizontal distance (blocks) a single mine command may DRIFT from the
     *  position where it started. Each SEARCH phase re-scans within searchRadius of
     *  the bot's CURRENT cell, so without this cap the bot chains 16-block hops
     *  toward scattered matches and can walk dozens of blocks — even straight across
     *  open ocean chasing red_sand — stranding a survival bot far from start. The
     *  scan rejects any candidate beyond this radius from the start anchor, bounding
     *  a whole mine command to a fixed bubble. Set <=0 to disable the cap. */
    public static volatile int mineMaxDriftFromStart = BehaviorSettings.DEFAULTS.mineMaxDriftFromStart();

    /** Food level at or below which autoEat will hold useItem on a food item.
     *  Stops when food fills to 20. Baritone default = 18 (one bite of room). */
    public static volatile int autoEatFoodThreshold = BehaviorSettings.DEFAULTS.autoEatFoodThreshold();

    /** HP (half-hearts) at or below which {@link BotConfig#autoHeal} consumes a healing
     *  item (healing/regen potion, golden apple). Default 12 (6 hearts). */
    public static volatile float healHpThreshold = (float) BehaviorSettings.DEFAULTS.healHpThreshold();

    /** Melee engage distance (blocks, centre-to-centre). The combat loop closes to
     *  within this before swinging; vanilla attack reach is ~3.0. */
    public static volatile double combatReach = BehaviorSettings.DEFAULTS.combatReach();

    /** Ranged kite distance (blocks). With a bow/crossbow the combat loop keeps the
     *  target near this range — backs up when closer, advances when farther. */
    public static volatile double kiteDistance = BehaviorSettings.DEFAULTS.kiteDistance();

    /** Jump before a melee swing so the descending hit lands a 1.5× critical
     *  (vanilla crit needs the attacker airborne + falling). On by default — it's
     *  free extra damage and the pre-jump is timed to the attack cooldown so it
     *  doesn't stall the swing rhythm. */
    public static volatile boolean combatCrit = BehaviorSettings.DEFAULTS.combatCrit();

    /** Post-kill drop sweep (live 2026-07-21 01:32): melee kites away from the
     *  kill spot, so a "successful" hunt left its meat rotting blocks behind the
     *  bot — six kills banked one porkchop. When a KILL/ENGAGE combat ends with
     *  the area clear, walk over the {@code ItemEntity} drops within 8 blocks of
     *  the last kill position (vanilla pickup is automatic), hard-capped at 100
     *  ticks so an unreachable drop can't wedge the suspended user task. DEFEND
     *  skips the sweep — it exists to resume the task fast, not to loot. */
    public static volatile boolean combatCollectDrops = BehaviorSettings.DEFAULTS.combatCollectDrops();

    /** Suffocation backstop (sibling of {@link BotConfig#autoSwim}): when a solid block —
     *  classically falling SAND collapsing into the head while digging up/down a
     *  disturbed pit — overlaps the bot's eyes, break that block so it can't be
     *  suffocated to death mid-dig (a naked bot went HP 17→11 in seconds this way,
     *  dig-out oscillating with no progress). Needs {@link WorldConfig#allowBreak}. Default ON.
     *  Read only by the {@code AntiSuffocate} client-tick reflex — never the planner. */
    public static volatile boolean antiSuffocate = BehaviorSettings.DEFAULTS.antiSuffocate();

    /** Contact-damage escape reflex (death #14, live 2026-07-20): a goto across
     *  desert hugged a cactus cluster and contact damage ground a healthy bot
     *  11→0 HP in ~20 s — the block re-damages every ~10 ticks, faster than any
     *  LLM/planner turn, and the entity-attribution reflexes (hurt-entry
     *  retreat, gap#55/#65) never fire for BLOCK damage sources. When the last
     *  damage is a fresh step-away-able contact type (cactus / sweet berry /
     *  in-fire / magma floor), the {@code ContactDamageEscape} client-tick
     *  reflex faces away from the touching hazard block and walks out of
     *  contact. Default ON. Read only by the reflex — never the planner. */
    public static volatile boolean contactDamageEscape = BehaviorSettings.DEFAULTS.contactDamageEscape();
    /** Walk away from an adjacent FLOWING lava front (devil-bench deaths #27/#29/#30). */
    public static volatile boolean lavaProximityEscape = BehaviorSettings.DEFAULTS.lavaProximityEscape();
    /** How far (blocks, horizontally) {@link #lavaProximityEscape} looks for water for a bot still burning
     *  once it is clear of lava. Leaving lava does not put a bot out, and walking further from the lava does
     *  not either; 0 hands control back while the bot still burns. */
    public static volatile int lavaEscapeWaterRadius = BehaviorSettings.DEFAULTS.lavaEscapeWaterRadius();

    /** gap#70 (live death #18): an IDLE bot (no movement process) that sinks in
     *  deep water gets ZERO self-rescue — {@link BotConfig#autoSwim}'s lift/beach steer is
     *  deliberately idle-gated OFF ({@code AutoSwim.tick}'s own doc: "with NO
     *  command … force-surfacing … is exactly what must NOT happen"), and
     *  Navigator's {@code drowningEscape} only runs inside an active Navigator, which an
     *  idle bot has none of. A bot tp'd into a ~29-block-deep river with no task
     *  drowned air 16→0 in ~40 s with both flags on and zero action taken.
     *  <p><b>Controller ruling (written into code, not just this comment — see
     *  {@code AutoSwim.drowningSentinel}/{@code AutoSwim.tick}):</b> the "driver
     *  idle must be passive" contract was always about forbidding UNCOMMANDED
     *  HORIZONTAL movement/beaching, never about letting the bot drown. P1
     *  already established that the reflex layer (lethal-threat response) stays
     *  active while idle (hurt-entry retreat reacts to being hit at rest); a pure
     *  VERTICAL float-to-surface is the same kind of survival reflex, not
     *  "autonomous movement" — no forward key, no turning, no beach-steer, ever.
     *  Independent of {@link BotConfig#autoSwim} on purpose: this is a bare survival
     *  reflex (AntiSuffocate's pattern), not the autoSwim movement/beach feature,
     *  so it can be toggled without touching autoSwim's active-process
     *  lift/beach semantics. Default ON. */
    public static volatile boolean autoFloatWhenDrowning = BehaviorSettings.DEFAULTS.autoFloatWhenDrowning();

    /** Air-supply threshold (ticks; vanilla max 300, drown damage starts at 0)
     *  at/below which {@link #autoFloatWhenDrowning} takes over — 240 ≈ 12s of air
     *  still in reserve when the float starts (raised from 100, final-review M2):
     *  the death-#18 reproducer was a 29-block-deep river, where a 100-tick (5s)
     *  reserve left only ~15s total survival budget against a 15-29s ascent — marginal
     *  to insufficient at the incident's own depth. The float is pure-vertical and
     *  harmless (matrix case (b) still protects "merely diving with plenty of air"),
     *  so triggering earlier costs nothing and buys real margin at depth. gap#70. */
    public static volatile int drownFloatAirThreshold = BehaviorSettings.DEFAULTS.drownFloatAirThreshold();
    /** {@code route.detour} fires when a route is longer than this × the straight line ({@code RouteEvents}). */
    public static volatile double detourAlarmRatio = BehaviorSettings.DEFAULTS.detourAlarmRatio();
    /** Debounce of the route events: one per (event, culprit) within this many process ticks. */
    public static volatile int routeEventCooldownTicks = BehaviorSettings.DEFAULTS.routeEventCooldownTicks();

    /** gap#68-⑪: ticks {@link net.magicterra.worlddriver.bot.behavior.reflex.AutoTool} yields the hotbar
     *  selection after detecting an external actor (setHotbarSlot RPC / human scroll)
     *  changed the slot it last wrote — without this, AutoTool re-clobbers the slot on
     *  the very next tick whenever the crosshair is on a breakable block, so an external
     *  slot switch right before a placement/useItem call silently loses the race.
     *  Values <=1 degrade to a single-tick grace (the arm clamps to >=1, so a zero or
     *  negative live setting can never wedge the yield loop). */
    public static volatile int manualSlotGraceTicks = BehaviorSettings.DEFAULTS.manualSlotGraceTicks();

    /** Block id used when {@link BotConfig#autoBackfill} fires. Must be a vanilla block the player has in their
     *  inventory (creative skips the inventory check via auto-pickItem). */
    public static volatile String autoBackfillBlock = BehaviorSettings.DEFAULTS.autoBackfillBlock();

    /** Chebyshev radius around the player within which {@link BotConfig#autoBackfill} considers tracked-air
     *  cells for filling. Larger = more aggressive but more pathing per tick. */
    public static volatile int autoBackfillRadius = BehaviorSettings.DEFAULTS.autoBackfillRadius();

    /** Diagnostic — when on, the elytra flight process validates the
     *  {@link net.magicterra.worlddriver.bot.rules.ElytraPhysics} simulator
     *  tick-by-tick against the live client (predicted vs observed
     *  {@code deltaMovement}) and logs per-tick error plus a summary on stop.
     *  Used to prove the simulator is tick-exact before the reactive controller
     *  is built on it. Off by default; toggle via
     *  {@code mc.bot.setting{elytraDebug:true}}. */
    public static volatile boolean elytraDebug = BehaviorSettings.DEFAULTS.elytraDebug();

    // Last, after every field above: see BotConfig.initialize.
    static { BotConfig.initialize(); }
}
