package net.magicterra.worlddriver.bot;

import java.util.Map;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.world.WorldModel;

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

    /** {@code mc.bot.holdItem} — put a specific inventory item ({@code item} id) into
     *  the main hand: hotbar select or main-inventory swap. Synchronous;
     *  returns {@code {ok, held}} or {@code {ok:false, error, held}}. */
    Map<String, Object> holdItem(Map<String, Object> params);
    /**
     * Use the held item on a target block face — bone-meal grass, bucket
     * fill/empty, flint-and-steel, shears, dye, or simply place a block at the
     * adjacent face. Synthesizes the BlockHitResult so we don't depend on
     * {@code Minecraft.hitResult} (which is one frame stale from our tick).
     * Synchronous, instant; no process slot.
     */
    Map<String, Object> useItemOn(Map<String, Object> params);
    /** Right-click an entity (mount / trade / shear / milk / feed / leash). */
    Map<String, Object> useItemOnEntity(Map<String, Object> params);
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
     * {@code mc.test.reset} client-pool entry reset (testkit P2b). Runs on the
     * client thread and brings the entry back to a neutral state between pooled
     * reuse runs: releases held movement keys, closes any open screen
     * ({@code setScreen(null)}), clears the chat readback log, and cancels a
     * residual smooth-look process if the {@code look} slot is active. Returns
     * {@code {ok:true, reset:[...]}} listing what it actually did. Client-only —
     * the verb throws loudly on a dedicated server (no bot impl registered).
     */
    Map<String, Object> resetClientEntry();
    /**
     * {@code mc.test.input.heldKeys} — instrument-grade held-key readback (task#90).
     * Reads {@link net.minecraft.client.KeyMapping#isDown()} on the client thread for
     * exactly the eight movement/action keymappings {@code BotInteract.releaseKeys()}
     * clears — {@code keyUp/Down/Left/Right/Jump/Sprint/Attack/Shift} — and returns
     * {@code {ok:true, keys:{up,down,left,right,jump,sprint,attack,shift:bool}}}. Pure
     * observation (touches no player/world state); the readback the {@code reset.behavior}
     * keys sub-assertion lacked (the unconditional {@code reset[]} "keys" token proves
     * {@code releaseKeys()} RAN, not that any key was actually down and got cleared).
     * Client-only — the verb throws loudly on a dedicated server.
     */
    Map<String, Object> heldKeys();
    /**
     * {@code mc.test.input.useOnBlock} — instrument-grade world right-click (task#90).
     * Synthesizes a {@link net.minecraft.world.phys.BlockHitResult} at the given block
     * coords (face nearest the player's eye, hit at that face's centre — same shape as
     * {@code mc.bot.useItemOn}) and calls {@code gameMode.useItemOn(player, hand, hit)}
     * on the client thread. Deliberately NOT the behaviour face: no movement, no aiming
     * (no yaw/pitch mutation), no sneak toggle — just the single right-click, so a test
     * can open a block-entity container screen (e.g. a furnace) with no path/aim pipeline.
     * Params {@code {x:int, y:int, z:int, hand?:"main"|"off"}}; returns
     * {@code {ok:bool, result:string(InteractionResult), hand, face}}. Client-only —
     * throws loudly on a dedicated server.
     */
    Map<String, Object> useOnBlock(Map<String, Object> params);
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
     * Per-tick derived-facts blackboard updated on the client tick. Returns the
     * client-authoritative {@link WorldModel} so that {@code mc.client.scene}
     * can read the latest snapshot off-thread without blocking the tick.
     */
    WorldModel worldModel();

    // ---- the object seam: hand the client a process, not a verb ----------------
    // Everything above takes a verb plus params, because everything above is an MCP
    // route and an LLM has no way to hand over an object. The three below are NOT
    // routes and must never become routes; they exist so in-JVM code that has already
    // BUILT a {@link BotProcess} can run it on the real player.
    //
    // Why that matters: {@code BotProcess.tick(Minecraft,...)} default-bridges to
    // {@code tick(Avatar,...)} over a {@code ClientPlayerAvatar}, so the SAME process
    // object drives a client {@code LocalPlayer} here and a headless {@code FakePlayer}
    // under {@code ServerWorldDriver}. That is the whole point of the Avatar seam, and
    // without these three the only in-JVM caller of it — the playthrough ladder — had to
    // spawn a fake body even on a topology that has a real player standing right there.

    /**
     * Run {@code process} as the foreground user task on the real player.
     *
     * <p><b>Never blocks.</b> Marshals onto the client thread and returns
     * {@code {started:true, kind, seq}} immediately — a caller on the server thread of an
     * integrated server must not wait for the client thread, and every other method on
     * this interface does exactly that. Supersedes whatever the user task was holding,
     * the same way a {@code mc.bot.goto} would.
     *
     * <p>Observe completion with {@link #userTaskLeg()}, never by sleeping.
     */
    Map<String, Object> runProcess(BotProcess process);

    /**
     * One self-consistent reading of the leg {@link #runProcess} started:
     * {@code {seq:long, busy:bool, kind:String|null, error:String|null}}.
     *
     * <p><b>One call, not two, and that is the point.</b> "Is it still running" and "how did
     * it end" are separate volatiles updated by the client thread while the caller reads
     * from the server thread, and two atomic reads do not compose into an atomic pair: a
     * poll landing between them sees {@code busy=false} beside the PREVIOUS leg's ending, so
     * 「这一腿刚跑完」and「上一腿早跑完、这一腿还没装上」render identically. This repo has
     * already paid for that exact shape — a {@code null} that meant「还没算过」read as
     * 「算出来是零」. So the whole reading is published as one immutable snapshot and handed
     * over in one field read.
     *
     * <p>{@code busy} is true from the instant {@code runProcess} returns and goes false only
     * on the client thread, and only once the process object it installed has actually left
     * the chain — so the window between "enqueued" and "installed" can never be read as
     * "already finished".
     *
     * <p>{@code error} is non-null when the chain let go for a reason other than running to
     * completion: it threw, or a higher-priority chain (panic / dodge / combat) cancelled it.
     * A caller that ignores this cannot tell a leg a creeper interrupted from a leg that
     * finished, because {@code busy} goes false for both.
     *
     * <p>{@code seq} increments once per {@code runProcess}, so a caller can tell a stale
     * snapshot from a current one.
     */
    Map<String, Object> userTaskLeg();

    /**
     * The {@link net.magicterra.worlddriver.bot.movement.Avatar} over this client's own
     * {@code LocalPlayer} — the single-shot actuator face of the same seam {@link #runProcess}
     * gives the per-tick one.
     *
     * <p><b>Why in-JVM code cannot just build one.</b> {@code ClientPlayerAvatar} imports
     * {@code net.minecraft.client}, so merely NAMING it from code that also runs headless makes the
     * JVM resolve it there — the {@code NoClassDefFoundError} shape StageWright's {@code DriverFeed}
     * documents, where a client half died on the tick after arming and the server then ran a suite
     * with no player. Returning it through this interface keeps the reference on the client side of
     * the boundary, exactly like every other method here.
     *
     * <p><b>Why it matters that this exists at all.</b> Measured by
     * {@code wd.actuatorSplitOnAnAdoptedBody} on the integrated topology, driving the adopted player
     * through a server-side {@code ServerPlayerAvatar} instead: the server's selected slot went to 4
     * and the client's stayed at 0; the server's aim went to (−55.32, 29.55) and the client's stayed
     * at (283.23, 0.00) — identical ten ticks later, so nothing propagated in either direction. The
     * two sides simply hold unrelated values, and every such write lands on a body nobody is
     * steering. {@code ClientPlayerAvatar} does the same operations the way vanilla requires:
     * {@code setSelectedSlot} sends {@code ServerboundSetCarriedItemPacket}, and the aim moves the
     * player the server is receiving movement packets from.
     *
     * <p><b>Never blocks</b>, and callers must keep it that way: the returned avatar's methods touch
     * client state, so a caller on an integrated server's SERVER thread must invoke them from a
     * client-thread hop it does not wait on. Null when no {@code LocalPlayer} exists yet.
     */
    net.magicterra.worlddriver.bot.movement.Avatar clientAvatar();

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
