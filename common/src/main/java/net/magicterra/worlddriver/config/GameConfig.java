package net.magicterra.worlddriver.config;

import java.util.Set;
import net.magicterra.worlddriver.api.methods.setting.GameSettings;

/**
 * Settings read by the game access layer ({@code game}): bodies, observation, and the client's screens and input. Each
 * sits in the lowest layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class GameConfig {

    /**
     * Camera smoothing for stream/demo scenarios. When on, the pathfinding Navigator and the {@code mc.bot.lookAt} verb
     * rotate toward their target by at most {@link #smoothLookDegPerTick} per tick instead of snapping instantly. Off
     * by default so headless/scripted behavior (and the validation suite) is unchanged. Functional aiming that gates an
     * immediate raycast — attack, place, break, build face — always snaps regardless, since a lagged crosshair would
     * make those actions miss.
     */
    public static volatile boolean smoothLook = GameSettings.DEFAULTS.smoothLook();

    /**
     * Opt-in: a server body digs with REAL destroy-progress (hardness × tool × the ÷5 not-on-ground and ÷5 underwater
     * penalties) instead of the default instant {@code Level#destroyBlock}. Default OFF so every existing arena keeps
     * its 1-tick break. A climb-out arena that must reproduce the live slow stone-mine (~750 ticks/block by hand
     * afloat) turns it on.
     */
    public static volatile boolean faithfulBreak = GameSettings.DEFAULTS.faithfulBreak();

    /**
     * Max degrees the camera turns per tick while {@link #smoothLook} is on. 20°/tick ≈ 400°/s → a 180° turn takes ~9
     * ticks (~0.45 s). Lower = more cinematic, higher = snappier. Read every tick.
     */
    public static volatile float smoothLookDegPerTick = (float) GameSettings.DEFAULTS.smoothLookDegPerTick();

    /**
     * Stream-grade camera guarantee (AIRI). When on, EVERY bot camera write is rate-limited at a single chokepoint
     * ({@code LookController.apply} at the end of the client tick) so NO actuator — Navigator, swim, pillar look-down,
     * the build/bunker/farm processes, the reflexes — can snap the view; the worst a snap-style write does is begin a
     * smooth multi-tick pan. Unlike {@link #smoothLook} (which only shapes the Navigator/lookAt TARGET and is opt-in),
     * this is a global post-write clamp and is ON by default. Functional exact aims (camera-raycast mine/attack, a
     * leap's takeoff heading) bypass it for one tick via {@code LookController.requestSnap()}. Inert headless (no
     * client tick).
     */
    public static volatile boolean cameraSlew = GameSettings.DEFAULTS.cameraSlew();

    /**
     * Max yaw degrees/tick for the {@link #cameraSlew} clamp. 30°/tick ≈ 600°/s → a 180° turn takes ~6 ticks (~0.3 s):
     * smooth on stream yet responsive.
     */
    public static volatile float cameraSlewDegPerTick = (float) GameSettings.DEFAULTS.cameraSlewDegPerTick();

    /**
     * Max pitch degrees/tick for the {@link #cameraSlew} clamp. Pitch sweeps are smaller (look-down to place/dig ≈
     * 90°), so a slightly gentler rate reads well.
     */
    public static volatile float cameraPitchSlewDegPerTick = (float) GameSettings.DEFAULTS.cameraPitchSlewDegPerTick();

    /**
     * Human/bot mouse coexistence: while the bot drives, release the cursor to the OS so the player's physical mouse
     * stops fighting the bot's aim. The mouse-side companion to the E1 keyboard work ({@code InputReleaseGate}) —
     * {@code MouseHandler} is a shared object exactly like {@code mc.options.keyXXX} was, and a human nudging the mouse
     * writes the same yaw/pitch the Navigator/LookController write every tick. Double-tap ESC takes the cursor back for
     * the rest of the drive burst; the bot going idle hands it back automatically. Client-only — a dedicated server
     * never grabs a cursor, so this is inert there. See {@code MouseYieldGate} for the state machine.
     */
    public static volatile boolean mouseYield = GameSettings.DEFAULTS.mouseYield();

    /**
     * Draw the "bot is driving" badge (top-right) while the bot is driving the player, naming the chain/process and who
     * owns the mouse. Separated from {@link #mouseYield} so a capture/stream run can keep the ownership indicator
     * without the cursor behaviour, or vice versa.
     */
    public static volatile boolean mouseYieldHud = GameSettings.DEFAULTS.mouseYieldHud();

    /**
     * Extra block ids the pathfinder treats as hazardous (in addition to the built-in {@code BotUtil.HAZARD_BLOCKS}).
     * Mutable via {@code mc.bot.setting{blocksToAvoid:[id,...]}}. Read on every WorldView query so changes apply
     * immediately. Stored as an immutable Set; writers replace the whole reference.
     */
    public static volatile Set<String> extraHazardBlocks = Set.copyOf(GameSettings.DEFAULTS.extraHazardBlocks());

    /**
     * Block ids the bot is ALLOWED to place as build/support blocks (pillar, bridge, parkour-place footing). When EMPTY
     * (default) the {@link #isUsableBuildBlock} heuristic applies: any non-falling, non-GUI block whose top face is
     * STURDY (not merely a full cube — see that method). When non-empty it OVERRIDES the heuristic — only these exact
     * ids may be placed, so the Agent can pin building to known-good blocks via
     * {@code mc.bot.setting{buildBlockWhitelist:[id,...]}}. Whole-list replace; pass [] to clear.
     */
    public static volatile Set<String> buildBlockWhitelist = Set.copyOf(GameSettings.DEFAULTS.buildBlockWhitelist());

    /**
     * Diagnostic — when on, the Navigator logs its per-tick decision (flight, onGround, descending, foot, step, branch,
     * repath outcome) to the {@code WorldDriver} logger. Off by default; toggle via
     * {@code mc.bot.setting{navigatorDebug:true}} when chasing a movement bug.
     */
    public static volatile boolean navigatorDebug = GameSettings.DEFAULTS.navigatorDebug();

    /**
     * Keep the game ticking while the BOT is driving and the window loses focus.
     *
     * <p>Vanilla singleplayer pauses on lost focus, which is right for a human who alt-tabbed and wrong for a bot
     * mid-task: the world freezes partway through a goto/mine, and the {@code PauseScreen} it opens sits underneath
     * every subsequent screen assertion — a screen-close check reads "screen should be null" and finds the pause menu
     * instead.
     *
     * <p>Scoped exactly like the {@link MouseYield} handshake: the override is applied only while a process actually
     * owns the tick, and the human's own {@code options.pauseOnLostFocus} value is handed straight back the moment the
     * bot stops. A pause menu the human opened deliberately (Esc) is never touched — only the AUTOMATIC focus-loss
     * pause is suppressed. See {@link FocusPolicy}.
     */
    public static volatile boolean keepTickingUnfocused = GameSettings.DEFAULTS.keepTickingUnfocused();

    // Last, after every field above: see BotConfig.initialize.
    static {
        BotConfig.initialize();
    }

    private GameConfig() {}
}
