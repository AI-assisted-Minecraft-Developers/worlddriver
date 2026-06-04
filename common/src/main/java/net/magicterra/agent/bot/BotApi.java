package net.magicterra.agent.bot;

import java.util.Map;

/**
 * Client-side bot facade exposed through {@code mc.bot.*} MCP routes. Lives in
 * common so dedicated-server JVMs don't resolve it; a Fabric/NeoForge client
 * entrypoint instantiates the platform impl and binds it via {@link BotHooks}.
 *
 * Semantics: every method returns immediately with {@code {started:true, ...}}.
 * Long-running progress is observed via {@link #status()} + the JSON-RPC
 * {@code mc.wait.condition} path. Cancellation is cooperative — calling another
 * action implicitly cancels the previous one of the same kind.
 */
public interface BotApi {
    Map<String, Object> mcGoto(Map<String, Object> params);
    Map<String, Object> mine(Map<String, Object> params);
    Map<String, Object> bunker(Map<String, Object> params);
    Map<String, Object> escape(Map<String, Object> params);
    Map<String, Object> craft(Map<String, Object> params);
    Map<String, Object> smelt(Map<String, Object> params);
    /**
     * Phase C active combat. {@code mode} = "engage" (clear all hostiles in range),
     * "defend" (only retaliate against hostiles actively eyeing the bot), or "kill"
     * (a specific {@code target:{id|type}}). Runs at scheduler priority COMBAT (60),
     * preempting the user task; reports progress via {@code mc.bot.status.combat}.
     */
    Map<String, Object> combat(Map<String, Object> params);
    /**
     * Phase F — equip the best armor on every body slot and (unless
     * {@code armorOnly}) the best weapon in the main hand, scoring material tier
     * then enchantments. Synchronous; returns {equipped, loadout, lowDurability,
     * missing} so the caller (T2) can go repair / craft a missing piece.
     */
    Map<String, Object> equip(Map<String, Object> params);
    Map<String, Object> build(Map<String, Object> params);
    Map<String, Object> clearArea(Map<String, Object> params);
    Map<String, Object> follow(Map<String, Object> params);
    Map<String, Object> explore(Map<String, Object> params);
    Map<String, Object> runAway(Map<String, Object> params);
    Map<String, Object> lookAt(Map<String, Object> params);
    /**
     * Use the held item without targeting a block — eat food, drink potion, draw
     * bow, throw snowball/ender-pearl, etc. Synchronous, instant; no process slot.
     */
    Map<String, Object> useItem(Map<String, Object> params);
    /**
     * Use the held item on a target block face — bone-meal grass, bucket
     * fill/empty, flint-and-steel, shears, dye, or simply place a block at the
     * adjacent face. Synthesizes the BlockHitResult so we don't depend on
     * {@code Minecraft.hitResult} (which is one frame stale from our tick).
     * Synchronous, instant; no process slot.
     */
    Map<String, Object> useItemOn(Map<String, Object> params);
    /**
     * Attack an entity by entity id — the same path as left-clicking a mob
     * in-game. Routes through {@code MultiPlayerGameMode.attack(player, target)}
     * so the server applies vanilla damage (current weapon, attack cooldown,
     * critical hit, sweeping) and the player swings the held arm. One call =
     * one attack; spam by polling. Use {@code mc.query q='entities'} / nearby
     * entity query to pick the target id first.
     * Synchronous, instant; no process slot.
     */
    Map<String, Object> attackEntity(Map<String, Object> params);
    // pause/resume removed — set via mc.bot.setting{paused:bool} instead.
    Map<String, Object> cancel(Map<String, Object> params);
    Map<String, Object> status();
    Map<String, Object> setting(Map<String, Object> params);
    /**
     * Manage named in-memory waypoints. {@code op} = {@code save|list|get|delete|clear}.
     * Stored for the lifetime of the bot impl (no disk persistence); a saved
     * name can be reused as {@code mc.bot.goto{waypoint:"name"}}. With
     * {@code save} and no {@code pos}, the player's current block position is
     * captured. Synchronous, no process slot.
     */
    Map<String, Object> waypoint(Map<String, Object> params);

    /**
     * Walk a 2D rectangular field, harvest mature crops, and replant the seed
     * the crop dropped. Baritone {@code farm} analogue. Long-running; status
     * surfaces under the {@code builder} slot.
     */
    Map<String, Object> farm(Map<String, Object> params);

    /**
     * Find the nearest bed and sleep in it. Baritone {@code SleepBehavior}
     * analogue. Scans the loaded level for a {@link net.minecraft.tags.BlockTags#BEDS}
     * block within {@code radius} (default 16, capped 64) — or pathfinds to an
     * explicit {@code pos} — walks adjacent, then right-clicks the bed.
     * Vanilla applies its own preconditions (must be night/thunder, no nearby
     * monsters, bed not occupied); failures surface as {@code lastError} on
     * the {@code goto} slot. Process completes once {@code player.isSleeping()}
     * or after a short timeout.
     */
    Map<String, Object> sleep(Map<String, Object> params);

    /**
     * Constructive movement — pillar up ({@code mode:"tower"}) or scaffold
     * forward ({@code mode:"bridge"}). Baritone {@code pillar}/{@code bridge}
     * analogues, folded into one verb. Tower: jump, face down, right-click
     * the support block under the apex; repeat until feet reach the target Y.
     * Bridge: sneak-walk forward; at each edge, face the forward face of the
     * current support and right-click to extend the bridge. Both reuse the
     * {@code builder} status slot. Long-running; pass {@code awaitMs} to block.
     */
    Map<String, Object> construct(Map<String, Object> params);

    /**
     * Elytra flight (Baritone elytra-alignment, milestone A). Takes the bot off
     * the ground (jump → deploy elytra) or out of a fall, then flies at a fixed
     * heading, optionally boosting with fireworks. Params:
     * <ul>
     *   <li>{@code pitch} — cruise pitch in degrees, MC sign (+ dives/accelerates,
     *       − climbs/decelerates). Default 0 (level glide).</li>
     *   <li>{@code yaw} — hold this yaw; omit to aim at {@code pos} (if given) or
     *       keep the current heading.</li>
     *   <li>{@code pos} — optional horizontal target {x,y,z}; the flight finishes
     *       once within {@code stopXZDist} (default 3) blocks of it in the XZ
     *       plane and the yaw tracks it each tick.</li>
     *   <li>{@code fireworks} — fire a rocket every {@code fireworkEveryTicks}
     *       (default 40) ticks for boost. Default false.</li>
     *   <li>{@code ticks} — safety duration cap (default 200).</li>
     * </ul>
     * With {@code mc.bot.setting{elytraDebug:true}} the process validates the
     * {@code ElytraPhysics} simulator tick-by-tick against the live client and
     * logs the per-tick / summary error. Long-running; status under the
     * {@code elytra} slot; pass {@code awaitMs} to block.
     */
    Map<String, Object> elytraFly(Map<String, Object> params);
}
