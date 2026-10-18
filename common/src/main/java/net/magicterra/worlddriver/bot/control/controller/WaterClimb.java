package net.magicterra.worlddriver.bot.control.controller;

import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickStep;
import java.util.EnumSet;
import java.util.Set;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.BreakFeasibility;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerTickCtx;

/**
 * Getting a floating bot out of the water onto a bank it cannot swim up: the foothold takeover, which
 * pillars the bot's own column out of the water with blocks from the hand, and the bank dig, which breaks a
 * notch into the bank for a bot with no block to place (or whose foothold proved futile there). Both catch
 * a bob-stall no matter how A* labelled the climb (plain StepUp, StairUpBreak into the bank, …).
 *
 * <p>A floating bot can't gain height onto a bank whose top sits ABOVE the water surface: swim-up tops out
 * AT the surface (~0.6 short of the step-up grab) and a break/pillar can't actuate from deep water either —
 * so it bob-cycles forever (live trace: y6.2 peak → sinks to y4.7, no NET rise). Once stalled with no
 * vertical progress, this takes over. The stall counter only advances while in the climb context, so a
 * working swim-up that leaves the context never trips it. The pathfinder is unchanged by any of it.
 *
 * <p>Its state is its own fields below; what it reads about
 * the climb context each tick is computed in {@link #sense}, whether it runs or not.
 */
public final class WaterClimb implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.allOf(Channel.class);

    /** How far above the engage foot the pillar may climb before bailing to the fallback actuators. Named
     *  so the ceiling and the rise reported by {@link WalkerTelemetry#waterPillarTopRise} cannot drift
     *  apart, and so the reader can see it is a CONSTANT above a FIXED anchor — the whole defect this
     *  replaced was an anchor that moved with the bot. */
    private static final int PILLAR_CEILING_RISE = 5;

    /** The crest gate's bound — {@link #crestClearOf}. Above mud's 0.875 collision top with 0.025 to
     *  spare, and below the 1.0 a full cube needs, which is exactly the discrimination wanted. */
    private static final double CREST_CLEAR = 0.9;

    // ---- this tick's climb context, from sense() ----
    private BlockPos cwp;
    private boolean wantClimbNow, wantClimb, waterClimbing, deepDig, cwpSwims;
    private int digStall;

    // ---- what the climb-out remembers across ticks; StallDetect reads digging, the inspector more ----
    int stall;                // armed flag (>WATER_CLIMB_STALL) once net-displacement window shows a bob-stall climbing out of water
    private int touchRecent;          // sticky countdown: >0 while in a water climb-out, kept latched through bob-peak surface breaches (see WATER_TOUCH_STICKY)
    private int wantClimbRecent;      // sticky countdown: >0 while a higher node sits ahead, latched through bob-peak/repath flicker (see WANT_CLIMB_STICKY)
    public boolean pillaring;        // latched: pillaring up the bot's column to bank stand level
    public boolean digging;          // set the tick the block-less bank-dig actuator swings; OR'd into breakingEdge next tick so the anti-stuck burst can't yank the bot off the riser mid-dig (it has no planned toBreak edge of its own)
    private BlockPos lastDigRiser;    // the riser the dig last aimed at; re-snap the look ONLY when it changes (not every tick) so the camera holds steady instead of juddering off the bobbing eye — the bob keeps the crosshair on the 1-tall block between re-aims
    private double lastDigAimEyeY = Double.NaN;   // eye-Y at the last dig aim-snap; re-snap once the buoyant bob has moved the eye far enough (DIG_REAIM_EYE_DY) that the fixed ray would drift OFF the 1-tall riser face (the ±0.5 once-only assumption fails for a ±1.5 deep-water bob → break never lands, 30s bob-stall)
    public BlockPos digRiser;        // LATCHED bank-dig riser cell — held while still solid so a buoyant bob (foot.y flickering ±1) or lateral drift (foot.z wandering) can't re-target a LOWER block of the same column or a neighbouring column mid-dig; cleared once the block breaks so the next +1 step is chosen fresh (drift-arena over-dig fix)
    private int digCommitTicks;       // ticks spent committed to the CURRENT latched riser; while >0 and <CAP the climb-out won't disengage on a transient wantClimb flicker, so a slow stone-bank break (~750 ticks by hand) finishes instead of being abandoned mid-dig → wander/sink; reset per riser
    public int digFloatTicks;        // consecutive ticks committed to the current latched riser while the bot stayed AFLOAT (onGround=false); reset to 0 on any ground contact. Feeds the futile-overhang early-release (walkerFutileBankDigRelease): a high count + a >=2-above-foot riser that breaks NOTHING = an unreachable overhang, not a legit grounded climb-out (which touches ground and resets this)
    private int futileBankDigCooldown;    // ticks left during which the bank-DIG must NOT re-engage after a futile-overhang early-release (walkerFutileBankDigRelease) — gives the reactive back-off burst time to physically move the bot off the unreachable riser before any dig can re-latch it; decremented per tick
    public boolean pillarGaveUp;     // latched once the pillar takeover proves futile (drifted off its locked column, or bob peak never clears the surface fill cell) → block pillar re-engage + let the bank-DIG take over even with a place block in hand; cleared when the climb context ends
    private BlockPos gaveUpPos;       // where the pillar proved futile (walkerClimbGaveUpSticky) — while the foot stays within 3 blocks and the TTL runs, the gave-up latch survives climb-context resets (repath node swaps) so the proven-futile pillar can't re-engage in a loop
    private int gaveUpTtl;            // ticks left on the sticky gave-up latch (walkerClimbGaveUpSticky); decremented per tick, 0 = expired
    public int pillarNoPlaceTicks;   // ticks the pillar takeover has been engaged without a successful place / height gain — buoyant bob can't lift feet above a surface fill cell, so beyond PILLAR_FUTILE_TICKS the place is hopeless and we fall to the dig
    private BlockPos placeAttemptCell;   // the cell the last climb-out click aimed at, carried to the NEXT tick so the ledger above can ask whether it actually filled. BlockInteraction.place is void — the click has no verdict — and vanilla refuses any placement whose cell still intersects the bot's AABB, so "clicked" and "placed" are different events and only the second one is progress. Cleared once read.
    private int pillarHighWaterY = Integer.MIN_VALUE;   // highest foot.getY() this takeover has reached — the OTHER half of "successful place / height gain". A HIGH-WATER MARK, not a per-tick delta: a buoyant bob crosses a block boundary every cycle, so "higher than last tick" is true forever and would count buoyancy as progress; "higher than ever" stops refreshing the moment the bob settles between two cells. Re-based at engagePillar so a re-locked column cannot inherit the previous segment's height.
    int targetY;              // safety ceiling Y for the pillar (engage foot + a few); bail if exceeded
    int colX, colZ;           // LOCKED column the takeover pillars in (don't chase repathing nodes)
    float yaw;                // LOCKED heading toward the bank at engage (no horizontal chase → no wander)
    private boolean sideRung;         // this takeover has placed a side foothold: keys are anticipatory from here (forward only while wet, jump only wet or settled on the ground); cleared at engage
    private final java.util.Set<BlockPos> placedRungs = new java.util.HashSet<>();   // every cell a climb-out click has aimed at; a rung of our own beside the bot is not an exit, however dry (walkerShallowWaterSideFoothold)
    private int digGroundedStreak;    // consecutive grounded ticks during a committed bank dig — a bob bottom-blip (<=5) must not break the dig commit (walkerBankDigGroundBlip)

    /** The climb-out state a new goal or a replan clears. Not cleared: lastDigAimEyeY (re-snapped per dig
     *  aim), the locked engage column and heading (read only while pillaring), digGroundedStreak (per commit). */
    @Override public void reset(ResetScope scope) {
        stall = 0;
        touchRecent = 0;
        wantClimbRecent = 0;
        pillaring = false;
        digging = false;
        pillarGaveUp = false;
        gaveUpPos = null;
        gaveUpTtl = 0;
        pillarNoPlaceTicks = 0;
        placeAttemptCell = null;
        pillarHighWaterY = Integer.MIN_VALUE;
        lastDigRiser = null;
        digRiser = null;
        digCommitTicks = 0;
        digFloatTicks = 0;
        futileBankDigCooldown = 0;
    }

    @Override public String name() { return "waterClimb"; }
    @Override public Set<Channel> channels() { return CHANNELS; }
    @Override public int priority() { return 3; }
    /** Bounded by its own ledgers: the pillar's futility count and ceiling, the dig's per-riser commit cap. */
    @Override public int maxTicks() { return Integer.MAX_VALUE; }

    @Override public void sense(Walker wk, WalkerTickCtx cx, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = wk.route.edgeAt(wk.route.step());
        cwp = wk.route.path().get(wk.route.step());
        // walkerClimbGaveUpSticky: run down the sticky gave-up TTL; expire the anchor once
        // the foot leaves the futile bank (>3 blocks) or the TTL runs out.
        if (wk.waterClimb.gaveUpTtl > 0) {
            wk.waterClimb.gaveUpTtl--;
            if (wk.waterClimb.gaveUpTtl == 0 || wk.waterClimb.gaveUpPos == null
                    || Math.abs(foot.getX() - wk.waterClimb.gaveUpPos.getX()) > 3
                    || Math.abs(foot.getZ() - wk.waterClimb.gaveUpPos.getZ()) > 3) {
                wk.waterClimb.gaveUpTtl = 0;
                wk.waterClimb.gaveUpPos = null;
            }
        }
        // Detecting a stalled climb-out must survive confounders that reset the old
        // accounting before it armed: (1) the bob peak breaches the surface so
        // `touchingWater` flickers false; (2) at that same peak the foot BLOCK rises
        // to the stepUp target Y, so `cwp.y > foot.y` (the climb intent) flickers
        // false; (3) A* repaths every ~13 ticks while it can't execute the climb,
        // nulling `edge`; (4) the peak-vs-high-water-mark "real rise" reset — and any
        // height-based substitute — is itself tripped by the ~1.5-block buoyant bob.
        // Fix: keep water-contact AND climb-intent STICKY across (1)-(3), then count
        // pure ticks-in-context with NO height reset (4). A genuine climb-out leaves
        // the context within ~10 ticks (grounds on the bank → no higher node ahead →
        // wantClimb false), so it never reaches WATER_CLIMB_STALL; only a real
        // bob-stall sits in-context long enough to arm. (live round76b: net-window
        // armed 0 takeovers; pure tick-count arms reliably.)
        // wantClimbNow normally needs the waypoint ABOVE the foot. But a FLOATING bot ramming a
        // water-bank LIP (walkerFloatingBankBobFreeze, live #47 repro -638,418→-652): the next wp can
        // be at the SAME Y across a 1-block lip, so cwp.y>foot.y is false and waterClimbing never arms
        // — the bot just jumps+rams the lip (hCol, hSpd~0) for 20+ s with no bank-dig/pillar recovery.
        // Treat "afloat + horizontally colliding while touching water" as wanting to climb so the
        // existing climb-out recovery (bank-dig / foothold-pillar) engages over the lip.
        boolean floatingBankRam = ControlConfig.walkerFloatingBankBobFreeze
                && !p.onGround() && p.horizontalCollision
                && (world.isWater(foot) || world.isWater(foot.below()));
        // Water climb-out LATERAL gate (walkerWaterClimbLateralGate, task#91 structural, default ON,
        // baseline-EXEMPT): a floating bot climbs a bank ONLY when the climb waypoint sits horizontally
        // BESIDE it. A higher waypoint that is laterally distant is the routed exit further down an open
        // corridor (riverSheerBank: the low bank +5 EAST across open water, only +1 up) — honoring its
        // +height as a climb-here intent made the block-less dig trench the SHEER wall the bot was merely
        // passing. Reached instead by the swim-drive carrying the bot along the corridor; the climb
        // re-arms once swum adjacent. A genuine bank climb-out has cwp directly beside/below the float
        // (Chebyshev 0-1) so it is unchanged. floatingBankRam (a real in-place wall-ram) is exempt.
        int cwpLatDist = Math.max(Math.abs(cwp.getX() - foot.getX()), Math.abs(cwp.getZ() - foot.getZ()));
        boolean climbTargetBeside = !ControlConfig.walkerWaterClimbLateralGate
                || cwpLatDist <= WATER_CLIMB_LATERAL_MAX;
        boolean touchingWater = p.isInWater() || world.isWater(foot) || world.isWater(foot.below());
        // THE INTENT IS MEASURED AGAINST THE SURFACE, NOT THE BOBBING FOOT. `cwp.y > foot.y` is
        // true at the bottom of a bob and false at its top, and a bot pressed into a bank rides
        // the collision boost through a two-and-a-half-block bob whose top lasts longer than
        // WANT_CLIMB_STICKY — so the context was left and `stall` zeroed once per cycle and the
        // takeover never armed. wd.clientTwoHighBankPlaceOut: 180 ticks of that, no event at all.
        // A dry waypoint beside the bot at or above its column's surface cell is a climb-out
        // whatever the foot reads this tick.
        boolean surfaceClimbIntent = false;
        if (ControlConfig.walkerClimbIntentFromSurface && edge != null && touchingWater && climbTargetBeside
                && !world.isWater(cwp)) {
            int surfY = foot.getY();
            while (world.isWater(new BlockPos(foot.getX(), surfY + 1, foot.getZ()))) surfY++;
            while (surfY > foot.getY() - 3 && !world.isWater(new BlockPos(foot.getX(), surfY, foot.getZ()))) surfY--;
            surfaceClimbIntent = cwp.getY() >= surfY;
        }
        // The surface cruise's dip sinks the foot under its (water) node on purpose; that is not a
        // climb-out, and this takeover's jump + sprint cancel would undo the pose every stroke.
        wantClimbNow = edge != null && !wk.drive.driveLatch.cruiseOn
                && ((cwp.getY() > foot.getY() && climbTargetBeside) || floatingBankRam || surfaceClimbIntent);
        if (touchingWater) wk.waterClimb.touchRecent = WATER_TOUCH_STICKY;
        else if (wk.waterClimb.touchRecent > 0) wk.waterClimb.touchRecent--;
        boolean nearWater = touchingWater || wk.waterClimb.touchRecent > 0;
        if (wantClimbNow) wk.waterClimb.wantClimbRecent = WANT_CLIMB_STICKY;
        else if (wk.waterClimb.wantClimbRecent > 0) wk.waterClimb.wantClimbRecent--;
        wantClimb = wantClimbNow || wk.waterClimb.wantClimbRecent > 0;
        // Durable dig-commit: while we're still mid-breaking a LATCHED riser that is
        // solid and right beside the bot, STAY committed even if A* transiently repaths
        // the climb away (wantClimb flicker). A buoyant bot mines a STONE bank by hand at
        // ~750 ticks/block (×5 not-on-ground); the old code dropped the half-broken riser
        // the instant wantClimb fell for WANT_CLIMB_STICKY ticks, so the bot abandoned
        // each block partway, wandered 10+ columns, and drifted into a deep hole and SANK
        // (live 2026-06-20: one stone block dug 517× then dropped; y62→y27). The commit is
        // capped per-riser (WATER_CLIMB_DIG_COMMIT_CAP, reset on each fresh riser) so a
        // genuinely stuck dig still releases to repath. Held only while afloat and still
        // beside the riser — grounding out (climbed) or drifting >2 off it ends it.
        if (p.onGround()) wk.waterClimb.digGroundedStreak++; else wk.waterClimb.digGroundedStreak = 0;
        boolean digGroundBlipOk = !p.onGround()
                || (ControlConfig.walkerBankDigGroundBlip && wk.waterClimb.digGroundedStreak <= 5);
        boolean digCommitted = wk.waterClimb.digRiser != null
                && world.isSolid(wk.waterClimb.digRiser) && digGroundBlipOk
                && Math.abs(foot.getX() - wk.waterClimb.digRiser.getX()) <= 2
                && Math.abs(foot.getZ() - wk.waterClimb.digRiser.getZ()) <= 2
                && wk.waterClimb.digCommitTicks < WATER_CLIMB_DIG_COMMIT_CAP;
        waterClimbing = (wantClimb && nearWater && !p.onGround()) || digCommitted;
        // Floating over DEEP water (water directly below the foot) with the dig
        // available: a buoyant bot can't swim-jump a +1 bank AND can't clear a surface
        // fill cell to pillar, so the pillar is ALWAYS futile here — pure wasted bob.
        // Skip it and engage the fast dig directly (the live journey's banks are all
        // this case, and it was eating ~50 ticks of futile pillaring per bank before
        // climbPillarGaveUp fell through to the dig). When break is OFF (place-only
        // arena) this is false → the pillar is kept as the only exit.
        // ...and more broadly for ANY buoyant float: the pillar can't clear the +0.9 fill
        // regardless of whether the cell DIRECTLY below is water — the bot may bob over a
        // solid-floored shallow shelf beside the bank yet still be too buoyant to stand a
        // rung, so the old isWater(foot.below()) test missed it and let the futile pillar
        // re-engage (live 2026-06-24 -67x pit: 264 climbout-place ticks, foot.below() solid,
        // pillar↔dig↔repath thrash ~105 s). Ride the isInWater bob-blink with the latch.
        boolean buoyantFloat = !p.onGround() && (p.isInWater() || wk.aim.surfaceWaterLatch > 0);
        deepDig = (world.isWater(foot.below()) || buoyantFloat)
                && wk.mayBreak() && WorldConfig.allowSwimEscapeBreak;   // mayBreak(): honor per-goto forbidDig, not just the global switch
        // The climb context's inputs, one row per wet tick: the takeover and the bank dig are
        // gated on these, and a bob-stall with no event in the log is unreadable without them.
        if (GameConfig.walkerDebug && nearWater)
            LOG.info("[walker] climb-ctx cwp={},{},{} wantNow={} (foot={} ram={} surface={}) want={} near={} climbing={} stall={} deepDig={} pillaring={} gaveUp={} digging={} riser={}",
                    cwp.getX(), cwp.getY(), cwp.getZ(), wantClimbNow, cwp.getY() > foot.getY() && climbTargetBeside,
                    floatingBankRam, surfaceClimbIntent, wantClimb, nearWater, waterClimbing, wk.waterClimb.stall,
                    deepDig, wk.waterClimb.pillaring, wk.waterClimb.pillarGaveUp, cx.stall.dugLastTick, wk.waterClimb.digRiser);
        if ((!wantClimb || !nearWater) && !digCommitted) {
            // Left the climb context (grounded on the bank, or A* now routes
            // down/along) → clear the per-attempt accounting AND the "pillar
            // gave up" latch, so the NEXT genuine climb-out starts fresh.
            // walkerClimbGaveUpSticky: EXCEPT while the sticky anchor is live — a repath
            // that swaps the climb node resets this context every ~2.5s, and clearing the
            // latch here is what let the proven-futile pillar re-engage 26× until the bot
            // drowned (live 2026-06-29). While the foot is still at the futile bank, keep it.
            wk.waterClimb.stall = 0;
            if (!(ControlConfig.walkerClimbGaveUpSticky && wk.waterClimb.gaveUpTtl > 0)) wk.waterClimb.pillarGaveUp = false;
            wk.waterClimb.pillarNoPlaceTicks = 0;
            wk.waterClimb.lastDigRiser = null;
            wk.waterClimb.digRiser = null;
            wk.waterClimb.digCommitTicks = 0;
            wk.waterClimb.digFloatTicks = 0;
        } else {
            wk.waterClimb.stall++;
            if (digCommitted) wk.waterClimb.digCommitTicks++;
            // Track how long this dig has run while the bot stayed AFLOAT. Any ground
            // contact resets it: a LEGIT climb-out bob-jumps onto the freed +1 notch and
            // grounds, so it never accumulates; only a perpetual float on an unreachable
            // riser does (live -784: onGround=false for all ~1000 stall ticks).
            if (digCommitted) {
                if (p.onGround()) wk.waterClimb.digFloatTicks = 0;
                else wk.waterClimb.digFloatTicks++;
            }
            // FUTILE-OVERHANG early-release (ControlConfig.walkerFutileBankDigRelease, default
            // OFF → byte-identical no-op). The dig has committed to one still-fully-solid
            // riser that sits >= FUTILE_BANK_DIG_MIN_RISE above the foot (an overhang the
            // buoyant bob can never reach) and has done so AFLOAT for FUTILE_BANK_DIG_TICKS
            // without ever grounding and without the riser breaking → it is provably
            // hopeless. Release it NOW (well before WATER_CLIMB_DIG_COMMIT_CAP=1000, ~40 s
            // sooner): latch climbPillarGaveUp so the futile pillar/dig don't re-engage at
            // this exact spot, drop the riser + the waterClimbDigging flag so breakingEdge
            // falls THIS tick, and price the pocket cell out — handing the wedge to the
            // already-working reactive churn-charge + anti-stuck back-off burst (both gated
            // !breakingEdge, which is why the unbroken dig kept them suppressed). The legit
            // +1 grounded staircase dig can't reach here: its riser is +1 (below the rise
            // gate), it grounds (resets floatTicks), and the riser breaks (resets the
            // counter via the fresh-riser path).
            if (ControlConfig.walkerFutileBankDigRelease && digCommitted
                    && wk.waterClimb.digRiser != null && world.isSolid(wk.waterClimb.digRiser)
                    && wk.waterClimb.digFloatTicks > FUTILE_BANK_DIG_TICKS
                    && wk.waterClimb.digRiser.getY() - foot.getY() >= FUTILE_BANK_DIG_MIN_RISE) {
                if (GameConfig.walkerDebug)
                    LOG.info("[walker] futile bank-dig release: riser {},{},{} (+{} above foot) unbroken for {} afloat ticks → drop dig, hand to recovery",
                            wk.waterClimb.digRiser.getX(), wk.waterClimb.digRiser.getY(), wk.waterClimb.digRiser.getZ(),
                            wk.waterClimb.digRiser.getY() - foot.getY(), wk.waterClimb.digFloatTicks);
                world.penalizeStuckNode(foot);
                world.penalizeStuckNode(foot.above());
                wk.waterClimb.pillarGaveUp = true;
                wk.waterClimb.digRiser = null;
                wk.waterClimb.lastDigRiser = null;
                wk.waterClimb.digCommitTicks = 0;
                wk.waterClimb.digFloatTicks = 0;
                wk.waterClimb.digging = false;
                wk.waterClimb.futileBankDigCooldown = FUTILE_BANK_DIG_COOLDOWN;
            }
        }
        // Trigger once bob-stalled below a bank we can't mount, with a placeable in
        // hand — then LATCH a pillar-up that runs to completion. Suppressed once the
        // pillar has proven futile here (climbPillarGaveUp): a buoyant bob can't lift
        // its feet above a surface fill cell, so re-engaging just bobs again — the
        // bank-DIG below takes over instead.
        // swimAshore +2 with no toBreak block never commits a dig (waterClimbDigging stays
        // false), so deepDig suppresses the pillar yet the dig never runs → bob-churn. After the
        // ~4 s dig window with NO dig swinging, fall back to the pillar despite deepDig so the
        // placeable lifts the bot onto the bank. Flag-gated; default OFF keeps this byte-identical.
        boolean swimAshorePillarFallback = ControlConfig.walkerSwimAshorePillarDespiteDeepDig
                && deepDig && !cx.stall.dugLastTick && wk.waterClimb.stall > WATER_CLIMB_DIG_STALL;
        // FOOTHOLD FIRST. `deepDig` used to send a bot holding a block to the dig before the
        // pillar, on the belief that a buoyant bob can never lift its feet clear of the surface
        // fill cell. The real client refutes it: a bot pressed INTO the bank rides vanilla's
        // +0.3 collision boost (LivingEntity.travel) to feet ≈ +1.8 above the surface cell, and
        // wd.clientOneHighBankPlaceOut measured the takeover placing its foothold six ticks after
        // engaging — after sixteen seconds of hopeless bare-hand stone digging that deepDig had
        // put in front of it. So the pillar goes first whenever a block is in hand; a bank where
        // the place really cannot land hands over to the dig through `placeFutile` (50 ticks),
        // which is still seven times cheaper than the shortest floating dig.
        if (waterClimbing && wk.waterClimb.stall > WATER_CLIMB_STALL && !wk.waterClimb.pillarGaveUp
                && (!deepDig || swimAshorePillarFallback || ControlConfig.walkerFootholdBeforeBankDig)
                && ControlConfig.allowSwimEscapePlace && wk.equipment.holdPlaceable()) {
            // THIS CONDITION IS SATISFIED ON EVERY TICK OF A PILLAR THAT IS ALREADY RUNNING,
            // so everything latched below has to be gated on the transition rather than on
            // the condition. `wk.waterClimb.stall` is only cleared when the climb context is
            // LEFT (see the reset above: `(!wantClimb || !nearWater) && !digCommitted`); a bot
            // that is busy pillaring is still in the context, so `stall` only grows and this
            // `if` re-enters every tick. Re-entry is the normal case here, not an edge case.
            boolean engaging = !wk.waterClimb.pillaring;
            if (engaging && GameConfig.walkerDebug)
                LOG.info("[walker] water climb-out: pillar takeover engaged (bob-stalled) toward bank node {},{},{}",
                        cwp.getX(), cwp.getY(), cwp.getZ());
            wk.waterClimb.pillaring = true;
            if (engaging) engagePillar(wk, p, foot, cwp);
        }
        digStall = deepDig ? WATER_CLIMB_DIG_DEEP_STALL : WATER_CLIMB_DIG_STALL;
        // Post-release dig lockout (walkerFutileBankDigRelease). Counted on ticks the takeover is
        // not running, the ticks the dig below could have fired on.
        if (!wk.waterClimb.pillaring && wk.waterClimb.futileBankDigCooldown > 0) wk.waterClimb.futileBankDigCooldown--;
        // walkerBankDigSkipWhenCwpSwims: the committed path's NEXT node (cwp) being a WATER cell
        // means the bot should SWIM into it, not dig — the real climb-out (a stepUp onto land)
        // sits FURTHER along the path, not here. Digging at a water-routed waypoint is premature:
        // the omnidirectional exit scan below picks the NEAREST dry exit, which on a tall sheer
        // bank (goal-side wall ~9 blocks) is the perpendicular wall face (dot≈0, passes the
        // forward-hemisphere guard) — so the bot trenches the goal-side wall, aimAtBlock locks the
        // yaw at it, the forward drive rams it, and it bob-stalls forever while A*'s actual path
        // swims west around to a lower climb-out (live 2026-06-27 deadlock @ -646,62,351, stuck
        // 1181 ticks: cwp=-648,62,352 WATER, dug east wall instead of swimming the path). Skipping
        // the dig when cwp is water lets the normal swim-drive follow the path to the real exit.
        // A genuine climb-out HERE routes cwp to a LAND/stepUp node (not water) so the dig still
        // fires for it. Default OFF; validate via replay A/B on the archived deadlock.
        cwpSwims = ControlConfig.walkerBankDigSkipWhenCwpSwims && world.isWater(cwp);
    }

    @Override public String whyNot(Walker wk, WalkerTickCtx cx, WorldView world) {
        if (wk.waterClimb.pillaring) return null;
        return whyNoDig(wk);
    }

    @Override public boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world) {
        return whyNot(wk, cx, world) == null;
    }

    @Override public boolean tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // PILLAR-UP climb-out: place support blocks in the bot's OWN column up to the
        // bank stand level, so the final move onto the bank is a flush WALK — not a
        // fragile in-place +1 jump. A single surface foothold only lifts +1; a +2
        // bank then left an un-runnable +1 step (no running room, water behind) the
        // bot pogo-bobbed forever (live round69: jumped to bank height but z frozen,
        // never translated across).
        if (wk.waterClimb.pillaring) {
            TickStep took = pillarTakeoverTick(wk, a, world, p, foot, cwp, wantClimbNow, wantClimb);
            if (took != null) return true;
        }
        // The takeover let go this tick (or never ran): the bank dig may take it.
        if (whyNoDig(wk) != null) return false;
        return bankDigTick(wk, a, world, p, foot);
    }

    @Override public void stop(String why) {}

    /**
     * Why the bank dig may not fire this tick, or null when it may.
     *
     * <p>Block-less climb-out fallback: a buoyant bot bob-stalled below a +1 bank with NO usable
     * (non-falling) support block can't pillar — but it can DIG. Break the single bank riser toward the
     * climb node at foot level so the +1 mount becomes a flat swim into the notch: the bot enters the freed
     * cell, grounds on whatever's below, and the next step is a normal grounded climb (or a clean repath
     * from the lower cell). Gated identically to the pillar takeover (waterClimbing + bob-stalled) so a
     * grounded land step never triggers it. Fires when there's no place block OR the pillar gave up here
     * (climbPillarGaveUp) — a buoyant bob can't lift its feet above a surface fill cell, so for a bank whose
     * top is above the water the DIG is the reliable primitive whether or not blocks are in hand (the live
     * z1940 deadlock had sand/gravel/cobble yet the place never cleared → 90 s bob; the dig breaks the riser
     * and the bot swims into the notch). (live 2026-06-15: deep-water +1 dirt bank, sand/gravel-only
     * inventory → holdPlaceable false → 12.6s bob-stall, move=diagUp with empty toBreak so neither
     * swimAshore nor the floating-pocket break engaged; bob peak y63.56 sat 0.44 below the y64 ledge, hCol
     * ramming the riser every tick.)
     */
    private String whyNoDig(Walker wk) {
        if (wk.waterClimb.pillaring) return "the foothold takeover is running";
        if (!waterClimbing) return "not climbing out of water";
        if (wk.waterClimb.stall <= digStall) return "not stalled long enough";
        if (wk.waterClimb.futileBankDigCooldown > 0) return "a futile dig was just released here";
        if (cwpSwims) return "the route swims on";
        if (!(wk.mayBreak() && WorldConfig.allowSwimEscapeBreak)) return "may not dig";   // mayBreak(): honor per-goto forbidDig, not just the global switch
        // deepDig no longer jumps the queue past a block in hand — see the foothold-first
        // note at the pillar takeover; the pillar's own `placeFutile` bail sets pillarGaveUp.
        if (!(!wk.equipment.holdPlaceable() || wk.waterClimb.pillarGaveUp
                || (deepDig && !ControlConfig.walkerFootholdBeforeBankDig))) return "a foothold goes first";
        return null;
    }

    /** One tick of the bank dig: pick (or keep) the riser and dig it. False when there is none to dig. */
    private boolean bankDigTick(Walker wk, Body a, WorldView world, LivingEntity p, BlockPos foot) {
        // Keep digging the LATCHED riser while it's still solid — a buoyant bob
        // (foot.y flickering ±1) or lateral drift (foot.z wandering) must NOT
        // re-target a lower block of the same column or a neighbouring column
        // mid-dig. Choose a fresh riser only once the latched one breaks.
        // The fix the drift arena exposed: the old code dug `foot.y` directly, so
        // a low bob dug the foot-level block AND a high bob dug the step block of
        // the SAME column → the bank surface tunnelled DOWN to the water line and
        // the next column stayed a fresh +2 wall (infinite pogo, ashoreTick 162).
        // Fallback anti-wander column LOCK: lock ONE chimney column at engage and dig
        // it straight up. Without it the dig re-derives the target from the live
        // (repathing) cwp every time a riser breaks, so the buoyant bot drifts along
        // the bank digging a fresh column each time and never tops out (live
        // 2026-06-20: 4545 digs across 10+ columns x2320-2342, never grounded). The
        // pillar takeover locks its column the same way; the toolless dig now does too.
        BlockPos riser = wk.waterClimb.digRiser;
        // walkerBankDigForwardExit, latch re-validation: a LATCHED riser is only re-chosen
        // when it goes null/non-solid (below), but the 25× underwater mining penalty means a
        // backward riser can NEVER break — so it stays latched and the forward-hemisphere
        // guard in the re-scan branch never re-applies (live isolation 2026-06-27: 1024 digs
        // all at the backward riser even with the guard ON). Drop a latched riser that now
        // points BACKWARD of cwp so the scan re-runs and re-picks a forward exit (or null →
        // no dig → swim the path). cwp follows the planned path, so this respects a path that
        // legitimately routes backward (its cwp points backward too).
        if (riser != null && ControlConfig.walkerBankDigForwardExit
                && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ())) {
            int rdx = riser.getX() - foot.getX();
            int rdz = riser.getZ() - foot.getZ();
            int gdx0 = Integer.signum(cwp.getX() - foot.getX());
            int gdz0 = Integer.signum(cwp.getZ() - foot.getZ());
            if (rdx * gdx0 + rdz * gdz0 < 0) riser = null;   // latched backward → force re-scan
        }
        if (riser == null || !world.isSolid(riser)) {
            riser = freshRiser(world, p, foot);
            wk.waterClimb.digRiser = riser;
            wk.waterClimb.digCommitTicks = 0;   // fresh riser → fresh per-block commit budget
        }
        // Hopeless-dig gate on the LATCHED riser too: the latch predates the
        // gate (or the stance changed — e.g. the bot sank into deep water), and
        // a hopeless dig only burns drift-released zero-progress laps (live:
        // prog 0.05 per 65-155t lap at a bare-hand stone riser). Poison it so
        // searches route around, drop the latch, and let the scan above pick a
        // diggable column next tick — or none, in which case the bot swims the
        // committed path instead of mining a wall it can never break.
        if (riser != null && WalkerDig.breathInfeasibleDig(p, riser)) {
            BreakFeasibility.poison(riser, WalkerDig.BREATH_POISON_TTL_MS);
            if (GameConfig.walkerDebug)
                LOG.info("[walker] hopeless bank dig {} — poisoned {}s, latch dropped", riser, WalkerDig.BREATH_POISON_TTL_MS / 1000);
            wk.waterClimb.digRiser = null;
            riser = null;
        }
        if (riser == null) return false;
        if (GameConfig.walkerDebug)
            LOG.info("[walker] water climb-out: block-less bank dig (bob-stalled, no place block) riser={},{},{}",
                    riser.getX(), riser.getY(), riser.getZ());
        wk.equipment.selectTool(riser);
        // Re-aim at the riser EVERY tick. The buoyant bot bobs at a bank (and dips underwater),
        // so any tick we SKIP the re-aim the mining ray slips off the riser face and vanilla
        // continueDestroyBlock resets the break — and on the 25× underwater+afloat mining
        // penalty (dirt ≈375 ticks/block) it then NEVER completes (live 2026-06-20: same riser
        // dug 1118 ticks, 0 breaks; a CONDITIONAL re-aim left exactly those gaps). A per-tick
        // re-aim pins the crosshair to the block face through the bob so the break accumulates
        // to completion. Paired with the bob-tame jump below (holds the eye near the riser →
        // near-horizontal ray), the residual camera motion stays small. Reliable digging is the
        // priority at a climb-out.
        // CLAIM FIRST, THEN DIG WHAT WAS CLAIMED. This block used to dig `riser`
        // unconditionally and claim it afterwards, so a claim the travel drive already
        // held was ignored here — the two phases alternated targets every 20–40 ticks
        // and each switch threw away the other's destroyProgress. See
        // StickyDig.engage for the measurement that named it.
        BlockPos digCell = WalkerDig.avatarDig(wk, a, riser, false);
        wk.waterClimb.lastDigRiser = digCell;
        wk.waterClimb.lastDigAimEyeY = p.getEyeY();
        world.signals().digging = true;   // AutoSwim's backstop yields while air is healthy
        // Mark the dig active: next tick's breakingEdge holds the leash and
        // exempts the burst so the dig can finish (see the breakingEdge note).
        // Clear any burst count accrued during the pre-dig bob-stall so the
        // very first dig tick can't fire a stale burst before the exemption.
        wk.waterClimb.digging = true;
        wk.recovery.wedgeRepathsHere = 0;
        // Aim-stabilisation, BALANCE half — the jump (space) is corrected, not
        // held flat-out and not bang-banged to the riser. Both extremes bob:
        // holding jump CONTINUOUSLY over-swims the bot up; bang-banging to the
        // riser centre overshoots on the jump impulse into a 2-block limit cycle
        // (y61↔63 live) that slings the ray off the face. The minimal-bob hold a
        // human uses to tread water: swim up ONLY when the head goes underwater —
        // just enough to STAY AT THE SURFACE. Head out → no jump → it settles
        // with a tiny natural bob; the instant it dips under → one correction
        // pops it back up. The waterline riser then sits just below the steady
        // surface eye → a near-horizontal, bob-tolerant mining ray. (Covers
        // sinking as soon as the jump key is released: it still swims up the moment it submerges.)
        // ...PLUS a controlled climb term: rise when the eye is clearly BELOW
        // the current riser (more than 0.3 under its base). For a riser at eye
        // level this is false → pure tread-water (the stable cycle-4 hold); once
        // a cell breaks and the next riser sits a block higher, the eye drops
        // below it → the bot swims UP to it and re-anchors there → it ascends the
        // staircase instead of treading in place. The 0.3 deadband + no-sprint
        // keep the rise from overshooting back into a bob.
        boolean needRise = p.isUnderWater() || p.getEyeY() < riser.getY() - 0.3;
        wk.keys.jump(a, needRise);
        // No sprinting: a sprinting bot swim-DIVES into the prone pose and dunks
        // its head underwater (the live thrash of sinking to the bottom and digging at
        // nothing while looking up, plus the 25× mining
        // penalty). Upright tread keeps the head out and the dig fast.
        p.setSprinting(false);
        // Press INTO the bank to enter the broken notch — but ONLY once the foot has
        // risen to the notch floor (foot.y ≳ riser.y − 0.6). In DEEP water the bot
        // floats with its foot ~2 below the surface, so pressing forward while still
        // low RAMS the riser's solid floor-cell (riser.below()) and pins the bot below
        // the +1 step — it breaks the block but never steps onto it and slides back
        // into the water (live 2026-06-20: stuck at y61 ramming the y62 bank, falling
        // back into the water after digging through). Below the notch, suppress forward and just SWIM UP (the jump
        // above); once the foot reaches the ledge, press in and ground on it. (Forward
        // while submerged also drops the bot into the prone-swim pose and it sinks.)
        if (!p.isUnderWater() && p.getY() >= riser.getY() - 0.6) Keys.forward(a, true);
        return true;
    }

    /**
     * The riser to dig when none is latched: climb toward the NEAREST dry-standable EXIT (a cell the bot
     * can stand on: solid floor, 2 air above, no water), NOT the far lateral goal (cwp). The old cwp
     * direction made the bot trench the waterline layer SIDEWAYS toward a far goal — tunnelling INTO the
     * bank at one Y and never stepping up onto the land 1-2 blocks above (live 2026-06-20: buried in the
     * hill at y63 for 5 min digging +x toward the 2600 goal instead of the +2 to the y65 land). Aim at the
     * closest way OUT; the onward path resumes once grounded on dry land. Null when no column is diggable.
     */
    private BlockPos freshRiser(WorldView world, LivingEntity p, BlockPos foot) {
        int dx = Integer.signum(cwp.getX() - foot.getX());   // fallback: goal direction
        int dz = Integer.signum(cwp.getZ() - foot.getZ());
        int bestExitD2 = Integer.MAX_VALUE;
        // walkerBankDigForwardExit: the omnidirectional exit scan below picks the NEAREST
        // dry exit in ANY direction — including BEHIND the bot. When the nearest exit is
        // backward (opposite the path's cwp) the climb-out digs AWAY from the goal into a
        // churn — the live -733/-710 "dig the west wall behind me while the goal is east"
        // deadlock (1000+ digs at the backward riser, ~48 s frozen, bot reverses 46 blocks).
        // Bias the scan to the FORWARD hemisphere (dot(offset, cwp-dir) >= 0) so it only
        // digs toward where the planned path actually leads. If NO forward exit exists the
        // dx/dz fallback above (= cwp direction = forward) still drives a forward dig, so
        // the bot never trenches backward. A genuinely backward path routes cwp backward
        // too, so "forward" follows the PATH (the immediate waypoint), not the absolute goal.
        // Independent of walkerBuoyantSearchFromSurface (which fixes the search START): even
        // a correctly forward path can have its exit scan pick a closer backward exit.
        boolean fwdExitGuard = ControlConfig.walkerBankDigForwardExit
                && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ());
        int gdx = Integer.signum(cwp.getX() - foot.getX());
        int gdz = Integer.signum(cwp.getZ() - foot.getZ());
        for (int sx = -4; sx <= 4; sx++)
            for (int sz = -4; sz <= 4; sz++) {
                if (sx == 0 && sz == 0) continue;
                if (fwdExitGuard && (sx * gdx + sz * gdz) < 0) continue;   // skip backward-hemisphere exits
                for (int sy = 1; sy <= 4; sy++) {
                    BlockPos land = new BlockPos(foot.getX() + sx, foot.getY() + sy, foot.getZ() + sz);
                    if (world.isSolid(land.below()) && !world.isSolid(land)
                            && !world.isSolid(land.above()) && !world.isWater(land)
                            && !world.isWater(land.below())) {
                        int d2 = sx * sx + sz * sz;
                        if (d2 < bestExitD2) {
                            bestExitD2 = d2;
                            dx = Integer.signum(sx);
                            dz = Integer.signum(sz);
                        }
                        break;   // nearest (lowest) exit in this column
                    }
                }
            }
        BlockPos[] cands = {
                (dx != 0 || dz != 0) ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ() + dz) : null,
                dx != 0 ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ()) : null,
                dz != 0 ? new BlockPos(foot.getX(), foot.getY(), foot.getZ() + dz) : null};
        // Dig the forward column's LOWEST solid cell ABOVE the waterline — a DRY notch
        // whose floor (the cell below it) stays solid, so the bob-jump can ground on
        // it (the +1 climb-out). Anchored on the bot's water-column surface Y (steady),
        // not the bobbing foot/eye (an eye anchor broke buoyantWallArena). The cands
        // follow the path's cwp, so as the bot climbs they advance +z+y → a DIAGONAL
        // staircase up into the solid hill (a vertical column-lock chimney can't be
        // ascended — the bot digs out its own floor; a real bank is a solid massif the
        // staircase climbs THROUGH).
        int surfY = foot.getY();
        while (world.isWater(new BlockPos(foot.getX(), surfY + 1, foot.getZ()))) surfY++;
        for (BlockPos cand : cands) {
            if (cand == null) continue;
            int ry = surfY + 1;
            while (ry <= surfY + 5 && !world.isSolid(new BlockPos(cand.getX(), ry, cand.getZ()))) ry++;
            BlockPos riserCand = new BlockPos(cand.getX(), ry, cand.getZ());
            // Overhang rejection (walkerBankDigSkipOverhang): the riser must be a genuine
            // bank-face step whose FLOOR (cell below) is solid — the invariant this comment
            // already states ("a DRY notch whose floor stays solid") but the lowest-solid
            // scan above omits. An air-floored riser is a CEILING/overhang the buoyant bob
            // can never ground beside: digging it does nothing, the bot yaw-locks into it
            // and bob-stalls while A*'s real climb-out (a pillarUp ~8 blocks along the
            // water) goes unfollowed. Rejecting it leaves riser null → no dig → the bot
            // swims the committed path to the real exit. The legit staircase-dig tunnels a
            // SOLID massif (floor always solid) so it is unaffected.
            boolean overhang = ControlConfig.walkerBankDigSkipOverhang && !world.isSolid(riserCand.below());
            // Hopeless-dig rejection: skip candidates the current stance/tool
            // can never finish (bare-hand floating stone &c.) so the scan
            // falls through to a diggable column or to no dig at all —
            // the same gate the latched-riser check below applies.
            if (ry <= surfY + 5 && world.isSolid(riserCand) && !overhang
                    && !WalkerDig.breathInfeasibleDig(p, riserCand)) return riserCand;
        }
        return null;
    }

    /**
     * The climb-out's own crest gate: may the bot click NOW, into the cell it is standing in?
     *
     * <p><b>Why {@link WalkerGeometry#feetClearOf} cannot be used here.</b> On the locked column the fill
     * cell IS the bot's foot cell ({@code colFoot == foot}), so "feet entirely above the cell" expands to
     * {@code p.getY() >= floor(p.getY()) + 1.0} — a contradiction. Asking it here does not place
     * fewer blocks, it places NONE, for every bot and every held item; the whole self-column family
     * goes silent. {@code [cell.y+0.9, cell.y+1.0)} is the ONLY window in which a self-column click
     * can exist at all.
     *
     * <p><b>Why a click in that window is not automatically refused.</b> Vanilla intersects the bot
     * against {@code state.getCollisionShape(...)}, and the blocks this takeover actually carries are
     * not full cubes: {@code MudBlock.SHAPE = Block.box(0,0,0,16,14,16)} — 14/16 = 0.875 tall, as are
     * soul sand, farmland and dirt path. (Its {@code getBlockSupportShape} IS the full cube; they are
     * different shapes and only the collision one is asked here.) A bot at {@code cell.y + 0.95}
     * clears mud's top by 0.075 and vanilla accepts. That is not a loophole — it is the mechanism the
     * water-bank climb-out is built on: fill the cell under your own feet, then jump off it, one cell
     * per cycle.
     *
     * <p><b>What it costs when the hand holds a full cube.</b> The click fires and vanilla refuses it.
     * That is correct and, since {@code faa3189}, free: the futility ledger counts LANDINGS, not
     * clicks, so a refused click reads as no progress and the dig fallback still gets the bank on
     * schedule. Before that ledger existed this gate had to be conservative to keep refusals from
     * laundering themselves into progress; it no longer does.
     */
    static boolean crestClearOf(LivingEntity p, BlockPos cell) {
        return p.getY() >= cell.getY() + CREST_CLEAR;
    }

    /**
     * One tick of the climb-out's place attempt, and of the ledger that decides when to stop trying.
     *
     * <p><b>The ledger counts RESULTS, and it has to lag a tick to do it.</b> Only the world can say
     * whether a placement landed, and it cannot say so until the tick after the click. This used to
     * be written as "clicked → zero the counter", which is a different event: {@link BlockInteraction#place}
     * returns {@code void}, so the click never had a verdict to report, and a bot sitting in the
     * band where vanilla refuses every placement pressed the button forever while gaining nothing.
     * The counter is what hands this bank over to the dig ({@code placeFutile} in
     * {@link #pillarTakeoverTick}), so resetting it on an event that is compatible with total failure
     * disabled that fallback outright — it could not fire, ever.
     *
     * <p>Someone else filling the cell also counts, and should: the bot is no worse off for not
     * having done it itself, and the next rung is what matters.
     */
    private static void climboutPlaceTick(Walker wk, Body a, WorldView world, LivingEntity p,
                                          BlockPos fillCell, BlockPos foot, boolean dryGrounded) {
        boolean fcSolid = world.isSolid(fillCell);
        boolean fcSupport = Move.hasPlaceSupport(world, fillCell);
        boolean fcCleared = crestClearOf(p, fillCell);
        if (GameConfig.walkerDebug)
            LOG.info("[walker] climbout-place col={},{} fill={} fcSolid={} support={} cleared={}(p.y={} need={}) dryG={} foot.y={} ceil={}",
                    wk.waterClimb.colX, wk.waterClimb.colZ, fillCell.getY(), fcSolid, fcSupport, fcCleared,
                    String.format("%.2f", p.getY()), fillCell.getY() + CREST_CLEAR,
                    dryGrounded, foot.getY(), wk.waterClimb.targetY);
        // TWO ways to be making progress, and the second one is not a courtesy. The takeover's
        // product is "fill the cell under my own feet, then jump off it", one cell per cycle — the
        // fill and the rise are two readings of ONE event, taken a tick apart, and either may be the
        // one this tick can see. wd.waterLowBank is the whole cycle: click at X.98, the cell turns
        // solid, the bot stands on it, a ground jump fires +0.42, and the bot arrives at (X+1).98
        // to do it again — three times, out of the water.
        // Counting only the click is what broke: BlockInteraction#place returns void, so a refused click and a
        // landed one were the same event, and a bot bobbing in a band it could never place from
        // pressed the button forever while the dig fallback behind it could not fire.
        // The rise half is bounded by the pillar ceiling and monotone (high-water, never a per-tick
        // delta), so it cannot launder buoyancy into progress the way "higher than last tick" would.
        BlockPos tried = wk.waterClimb.placeAttemptCell;
        boolean landed = tried != null && world.isSolid(tried);
        // HIGH-WATER, never a per-tick delta. The bob crosses a block boundary every cycle, so
        // "higher than last tick" is true forever and would launder buoyancy into progress; "higher
        // than ever" stops refreshing as soon as the bob settles between two cells, which is
        // exactly the deadlock this ledger exists to notice.
        boolean rose = foot.getY() > wk.waterClimb.pillarHighWaterY;
        if (rose) wk.waterClimb.pillarHighWaterY = foot.getY();
        if (landed || rose) {
            wk.waterClimb.pillarNoPlaceTicks = 0;
            if (landed) wk.waterClimb.placeAttemptCell = null;
        } else {
            wk.waterClimb.pillarNoPlaceTicks++;
        }
        if (!fcSolid && fcSupport && fcCleared) {     // feet cleared the cell
            wk.tel.waterPillarPlaceCalls++;
            wk.blocks.place(world::isSolid, fillCell);
            // Remember THIS cell, overwriting any older attempt: an earlier cell that filled late
            // must not be allowed to pay for the one being clicked now.
            wk.waterClimb.placeAttemptCell = fillCell;
            wk.waterClimb.placedRungs.add(fillCell);
        }
    }

    /**
     * Latch the pillar takeover's column, heading and safety ceiling — <b>once, at engage</b>.
     *
     * <p>The takeover pillars the bot's own column STRAIGHT UP, pinned to one bank, until it tops
     * out of the water onto dry ground. Following the live (repathing) node instead made the bot
     * wander between columns — chasing dive-to-floor and other-column pillar nodes A* kept
     * replanning — and lose the wall-supported foothold (live round76c: engaged toward y145 pool
     * floor + x2438→2441 drift, never climbed out).
     *
     * <p><b>⚠️ Everything here is a LATCH, and it is a separate method so that it has exactly one
     * call site and that call site is behind the engage transition.</b> The caller's condition is
     * satisfied on every tick of a pillar that is already running — {@code waterClimb.stall} is
     * cleared only when the climb context is LEFT, and a bot busy pillaring is still in the
     * context, so {@code stall} only grows and the branch re-enters every tick. Running these four
     * assignments on each of those ticks is what the missing gate used to do, and it silently
     * undid BOTH of the things they exist for:
     *
     * <ul>
     *   <li>the column/heading re-locked onto wherever the bot had drifted to — which IS the
     *       round76c drift the lock was written to stop; the latch followed the bot instead of
     *       pinning it;</li>
     *   <li>the safety ceiling re-anchored to the current foot, so the caller's
     *       {@code tooHigh = foot.getY() > targetY} read {@code foot.getY() > foot.getY() + 5}
     *       (one {@code foot} per tick, from {@code cx.frame.foot}) and was UNCONDITIONALLY FALSE.
     *       The bail could not fire, and it failed hardest exactly when it was needed most: the
     *       longer the bot stayed wedged, the larger {@code stall} grew and the more reliably the
     *       ceiling was pushed back out of reach.</li>
     * </ul>
     *
     * <p>Both are one defect: <b>a guard whose threshold is computed from the very quantity it is
     * meant to bound can never bind it.</b> Keeping the assignment of
     * {@link #targetY} to a single call site inside a method named for the
     * transition is the structural half of the fix — a future "just refresh it each tick" has to go
     * through this name first.
     */
    private static void engagePillar(Walker wk, LivingEntity p, BlockPos foot, BlockPos cwp) {
        wk.tel.waterPillarEngages++;
        wk.waterClimb.colX = foot.getX();
        wk.waterClimb.colZ = foot.getZ();
        // Re-base the height ledger on THIS segment. A re-locked column that inherited the previous
        // one's high-water would start already "as high as it ever got" and could never register a
        // rise again — the second segment would be declared futile on height no matter how well it
        // climbed, which is the same mistake as counting the click instead of the block, one level up.
        wk.waterClimb.pillarHighWaterY = foot.getY();
        wk.waterClimb.placeAttemptCell = null;
        wk.waterClimb.sideRung = false;
        double ex = (cwp.getX() + 0.5) - p.getX();
        double ez = (cwp.getZ() + 0.5) - p.getZ();
        wk.waterClimb.yaw = (ex * ex + ez * ez > 1e-4)
                ? (float) Math.toDegrees(Math.atan2(-ex, ez)) : p.getYRot();
        // Safety ceiling: a sane bank is +1..+3; never pillar more than +5 above the engage
        // foot, then bail to the fallback actuators.
        wk.waterClimb.targetY = foot.getY() + PILLAR_CEILING_RISE;
    }

    /**
     * One tick of an ENGAGED pillar takeover: decide whether it is over (topped out dry, over the
     * ceiling, no block, drifted, stale, futile) and otherwise drive the rise — heading pinned to
     * the latched column, fill cell chosen, keys pressed, the click made. Returns the step to
     * report when the tick is consumed, {@code null} when the takeover has let go and the caller's
     * ordinary actuators / bank-dig take the tick.
     */
    private static TickStep pillarTakeoverTick(Walker wk, Body a, WorldView world, LivingEntity p,
                                                  BlockPos foot, BlockPos cwp, boolean wantClimbNow, boolean wantClimb) {
                boolean haveBlock = ControlConfig.allowSwimEscapePlace && wk.equipment.holdPlaceable();
                // Done when we've topped out onto DRY solid ground (grounded, clear of
                // water). The old `foot.y >= targetY` test fired the instant targetY was
                // a path node BELOW us (A* dives to the pool floor), declaring success at
                // the water surface before any real climb — round76c. A terrain test is
                // robust to whatever A* planned and to the exact bank height.
                boolean dryGrounded = p.onGround() && !p.isInWater()
                        && !world.isWater(foot) && !world.isWater(foot.below())
                        // ...AND actually topped out — not a MID-WALL rung. On a tall sheer
                        // climb the takeover places a rung, grounds on it dry, and this fired
                        // "topped out" at every +1 → flush-walk → repath → re-engage; without
                        // this the climb-out sometimes never completes (buoyantWallArena run
                        // reached only y-mid-wall, onPlateau=false). While the climb node is
                        // still ≥2 above the foot the wall continues up; only a node at ~foot
                        // level is the real bank top.
                        && !(wantClimbNow && cwp.getY() - foot.getY() >= 2)
                        // ...AND there is somewhere to WALK to. The climb node the takeover latched
                        // can sit BELOW the bank top (a swimAshore break target in the pool wall),
                        // so the rise test above read "topped out" on a 1×1 rung one course under
                        // the rim, A* then asked for a diagonal step-up off that rung, and the bot
                        // walked off it back into the pool (wd.clientOneHighBankPlaceOut: a second
                        // climb-out, 500 ticks). A rung is the top only when a dry cell beside it
                        // can be stepped onto flush or down; otherwise the column keeps going up.
                        && (!ControlConfig.walkerPillarTopsOutAtFlushExit || flushExitBeside(world, foot, wk.waterClimb.placedRungs));
                boolean tooHigh = foot.getY() > wk.waterClimb.targetY;
                // Reported whether or not it fires: this bail was unconditionally false until the
                // engage latch was fixed, and the water-climb family records no evidence at all, so
                // a guard that could not fire changed no colour and nothing in the suite could see
                // it. Rise is kept so "never got near" differs from "never asked". See Walker.
                if (tooHigh) wk.tel.waterPillarCeilingBails++;
                wk.tel.waterPillarTopRise = Math.max(wk.tel.waterPillarTopRise,
                        foot.getY() - (wk.waterClimb.targetY - PILLAR_CEILING_RISE));
                // Self-correction: the latch pins the bot to ONE locked column +
                // heading, which goes stale two ways in a live crossing — (a) the bot
                // DRIFTS off the column (swimming along a continuous bank), so the place
                // targets an unreachable far cell; (b) A* repaths the climb away (now
                // routes down/along → wantClimb gone), pinning the bot to a wall it
                // should swim past. And (c) a buoyant bob simply can't lift its feet
                // above a surface fill cell, so the place never fires. Any of these →
                // release the latch; for (a)/(c)/over-ceiling, remember it
                // (climbPillarGaveUp) so the bank-DIG takes this bank instead of the
                // pillar re-engaging into the same hopeless bob. (live 2026-06-15:
                // latched col z1954, bot drifted to z1940 while the path went diagDown —
                // 90 s deadlock bobbing at an unreachable column.)
                boolean drifted = Math.abs(foot.getX() - wk.waterClimb.colX) > 2
                        || Math.abs(foot.getZ() - wk.waterClimb.colZ) > 2;
                // In side-rung mode the intent is allowed to flicker: the climb node A* named sits
                // at the bot's level as soon as the first rung is mounted, and "no longer
                // climbing" then dropped the takeover one course under the bank, handing a bot on
                // a 1×1 rung in a current to the ordinary walker. The mode ends on its own terms —
                // a flush exit, the ceiling, or futility (wd.clientFlowingTrenchPlaceOut, run 22).
                boolean staleClimb = !wantClimb && !(ControlConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung);
                boolean placeFutile = wk.waterClimb.pillarNoPlaceTicks > PILLAR_FUTILE_TICKS;
                if (dryGrounded || tooHigh || !haveBlock || drifted || staleClimb || placeFutile) {
                    wk.waterClimb.pillaring = false;
                    wk.waterClimb.pillarNoPlaceTicks = 0;
                    if (dryGrounded) {
                        // Out of the water on solid ground → re-plan; a flush walk now.
                        wk.route.drop("water climb-out topped out on dry ground");
                        wk.stuckTicks = 0;
                        wk.totalTicks = 0;
                        wk.waterClimb.stall = 0;
                        // The takeover owned the heading for the whole climb; the aim's low-pass
                        // state went on smoothing toward nodes the bot was never facing. Left as
                        // it was, the first dry walk starts up to 180° off and the dry-land EMA
                        // (0.08) closes on a node bearing that rotates as fast as it turns — the
                        // bot orbits the node a block out, for hundreds of ticks
                        // (wd.clientFlowingChannelPlaceOut, run 22: yaw wound from 69 to -817).
                        if (ControlConfig.walkerClimbOutResyncsAim) wk.aim.aimSmooth.reset();
                        if (GameConfig.walkerDebug)
                            LOG.info("[walker] water climb-out: topped out dry at y={} → flush walk, repath", foot.getY());
                        return TickStep.WALKING;
                    }
                    // Only "give up" the pillar (block re-engage, hand off to the bank-DIG)
                    // when that DIG can actually fire — i.e. breaking is allowed. With break
                    // OFF (no pickaxe: the live +2 mud-bank case) there is NO fallback, so
                    // latching climbPillarGaveUp would strand the bot bobbing forever. Leaving
                    // it false lets the pillar RE-ENGAGE next tick, re-locking the column to the
                    // bot's current foot — which the locked-heading forward press has nudged
                    // toward the bank — so the column RATCHETS to the supported bank-adjacent
                    // cell and the foothold-place finally lands (the pre-faa3189 behavior the
                    // self-correcting latch regressed: waterLowBankArena went red for ~5 days).
                    boolean digFallbackHere = wk.mayBreak() && WorldConfig.allowSwimEscapeBreak;   // mayBreak(): honor per-goto forbidDig, not just the global switch
                    if ((drifted || placeFutile || tooHigh) && digFallbackHere) {
                        wk.waterClimb.pillarGaveUp = true;
                        // walkerClimbGaveUpSticky: anchor the latch to THIS bank so repath-driven
                        // context resets can't clear it while the bot is still here (15s TTL).
                        if (ControlConfig.walkerClimbGaveUpSticky) {
                            wk.waterClimb.gaveUpPos = foot;
                            wk.waterClimb.gaveUpTtl = 300;
                        }
                    }
                    if (GameConfig.walkerDebug)
                        LOG.info("[walker] water climb-out: bail ({}) → fallback",
                                !haveBlock ? "no block" : tooHigh ? "over ceiling"
                                : drifted ? "drifted off column" : placeFutile ? "place futile"
                                : "no longer climbing");
                    return null;   // the normal actuators / bank-dig take the tick
                }
                return pillarDriveTick(wk, a, world, p, foot, dryGrounded);
    }

    /**
     * The rising half of an engaged pillar takeover, one tick: heading pinned to the latched
     * column, the fill cell chosen (surface cell afloat, the cell on the column's solid top when
     * dry), the side-foothold branch for one-deep water, the keys, and the click.
     */
    private static TickStep pillarDriveTick(Walker wk, Body a, WorldView world, LivingEntity p,
                                               BlockPos foot, boolean dryGrounded) {
                // Pin to the LOCKED bank heading + column; look down to aim the place.
                p.setYRot(wk.waterClimb.yaw); p.yHeadRot = wk.waterClimb.yaw; p.yBodyRot = wk.waterClimb.yaw;
                p.setXRot(40f);
                p.setSprinting(false);
                // Standing dry after a side rung, the column is wherever the bot actually
                // stands: it mounts the rung by its edge, centre over the next column, and a
                // fill aimed at the rung's column meets the bot's box while one under its own
                // centre does not. The pillar then grows under the centre — into the water cell
                // beside the rung first, if that is what is there — and the bot ends on a
                // full column of its own.
                if (ControlConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung
                        && p.onGround() && !p.isInWater()) {
                    wk.waterClimb.colX = foot.getX();
                    wk.waterClimb.colZ = foot.getZ();
                }
                // Fill the top water cell of the LOCKED column (floating) or the feet
                // cell (grounded on the fresh rung) — not the live foot column, which
                // drifts off the wall-supported pillar.
                BlockPos colFoot = new BlockPos(wk.waterClimb.colX, foot.getY(), wk.waterClimb.colZ);
                // THE TOP WATER CELL OF THE COLUMN, wherever the bob has carried the foot. This
                // used to fill `foot` whenever the column's foot-level cell was not water — which
                // is every tick the boost lifts the bot INTO THE AIR above the surface, i.e.
                // exactly the ticks the place could land. The target then followed the bob: at
                // y 221.2 it asked for cell 221 and "cleared" at 221.9, back at 220.6 it asked for
                // 220, and the one-tick window where 220 was both the target and cleared was
                // skipped by the rise itself. wd.clientOneHighBankPlaceOut: 130 ticks of that
                // cycle, no block. Reaching DOWN to the water under the foot pins the target to
                // the surface cell; a bot grounded on its fresh rung (solid below, not water)
                // still fills its own foot cell as before.
                BlockPos fillCell = colFoot;
                while (!world.isWater(fillCell) && world.isWater(fillCell.below())
                        && fillCell.getY() > foot.getY() - 2) fillCell = fillCell.below();
                if (!world.isWater(fillCell)) fillCell = foot;
                while (world.isWater(fillCell.above())) fillCell = fillCell.above();
                // DRY COLUMN: the cell to fill is the one standing on the column's solid top, not
                // the airborne foot. Taking `foot` here let the target ride the jump — at y 221.0
                // it asked for 221 and "cleared" at 221.9 — so a bot jumping off its own rung
                // never had a fillable cell under it (wd.clientFlowingTrenchPlaceOut, third run).
                boolean dryColumn = ControlConfig.walkerShallowWaterSideFoothold && !world.isWater(fillCell)
                        && !p.isInWater();
                if (dryColumn) {
                    while (!world.isSolid(fillCell.below()) && !world.isWater(fillCell.below())
                            && fillCell.getY() > foot.getY() - 2) fillCell = fillCell.below();
                }
                // ONE-DEEP WATER WITH A FLOOR, bot afloat: the fill cell is the foot cell and
                // the feet cannot clear it. In a layer higher than 0.4 the jump key swims
                // (LivingEntity.aiStep: in water and not grounded → jumpInLiquid), the bot
                // hovers between y+0.24 and y+0.96 and vanilla refuses a block that meets its
                // box — wd.clientFlowingTrenchPlaceOut: 50 ticks of "cleared" at .95, no rung.
                // The rung that CAN land is the one beside the bot: a neighbour cell at foot
                // level is clear of the box, its floor is the support, and once it is solid
                // the forward press rides the +0.3 collision boost onto it. Standing there,
                // dry, the ordinary ground-jump pillar takes over. The column re-latches onto
                // the foothold so the next fill cell is the foot on it, not the water beside.
                boolean shallowAfloat = ControlConfig.walkerShallowWaterSideFoothold && !p.onGround()
                        && p.isInWater() && fillCell.equals(foot) && world.isSolid(foot.below());
                // A rung already placed beside the bot (the column re-latched onto it) is the
                // one to mount: keep the heading on it while the current carries the bot, and
                // place nothing new. Without this the drift changed `foot` every tick and each
                // tick chose a fresh neighbour — eight dirt in a ring, and the ring then read
                // as a flush exit one course up (wd.clientFlowingTrenchPlaceOut, first run).
                BlockPos rung = new BlockPos(wk.waterClimb.colX, foot.getY(), wk.waterClimb.colZ);
                // The click's block shows up a tick or two later, so the rung just asked for
                // counts while it is still pending; the futility ledger bounds the wait.
                boolean rungPending = rung.equals(wk.waterClimb.placeAttemptCell) && !world.isSolid(rung);
                // A click the server has not honoured within the wait was refused (the bot's
                // box met the cell at the server's copy of it); forget it so a fresh cell is
                // chosen instead of treading water on a rung that will never come.
                if (rungPending && wk.waterClimb.pillarNoPlaceTicks > PENDING_RUNG_WAIT_TICKS) {
                    wk.waterClimb.placeAttemptCell = null;
                    rungPending = false;
                }
                boolean rungBeside = shallowAfloat && !rung.equals(foot)
                        && (world.isSolid(rung) || rungPending)
                        && Math.abs(rung.getX() - foot.getX()) <= 1 && Math.abs(rung.getZ() - foot.getZ()) <= 1;
                // The bot has swum INTO the cell it asked for before the block arrived (the
                // current, or the press toward it): vanilla refuses that placement. Tread water
                // a few ticks for the server's answer — the click was judged at the server's
                // copy of the bot, which lags, and often lands anyway — before asking elsewhere.
                boolean insidePending = shallowAfloat && rung.equals(foot) && rungPending
                        && wk.waterClimb.pillarNoPlaceTicks <= PENDING_RUNG_WAIT_TICKS;
                // Keys. The classic takeover holds forward and jump every tick: the floating bot
                // rams the bank, the +0.3 boost lifts it, the jump-place fills the surface cell.
                // Once a SIDE RUNG is in play the keys turn anticipatory, because a key read this
                // tick is the state set LAST tick: a bot landing on its 1×1 rung with jump still
                // held from the swim jumps again on the landing tick, with the swim's momentum
                // still in it, and clears the rung entirely (wd.clientFlowingTrenchPlaceOut, run
                // 21: mounted at x .42, airborne at .53, over the next column at 1.10). So in
                // side-rung mode: forward only while wet (the press is what mounts the rung; dry
                // it walks off), and never while the rung is pending (pressing swims the bot into
                // the cell and voids the click); jump while wet, or dry once grounded with the
                // momentum gone — released in the air so the landing tick reads it released.
                boolean sideMode = ControlConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung;
                boolean wet = p.isInWater();
                boolean settled = p.getDeltaMovement().horizontalDistanceSqr() < SETTLED_SPEED_SQ;
                Keys.forward(a, sideMode ? (wet && !rungPending && !insidePending) : true);
                wk.keys.jump(a, sideMode ? (wet || (p.onGround() && settled)) : true);
                if (rungBeside) {
                    wk.waterClimb.yaw = (float) Math.toDegrees(Math.atan2(
                            -((rung.getX() + 0.5) - p.getX()), (rung.getZ() + 0.5) - p.getZ()));
                } else if (shallowAfloat && !insidePending) {
                    BlockPos side = sideFoothold(world, foot, wk.waterClimb.yaw, p.getBoundingBox());
                    if (side != null) {
                        if (GameConfig.walkerDebug)
                            LOG.info("[walker] climbout-place side foothold {},{},{} (one-deep water, bot afloat at y={})",
                                    side.getX(), side.getY(), side.getZ(), String.format("%.2f", p.getY()));
                        wk.tel.waterPillarPlaceCalls++;
                        wk.blocks.place(world::isSolid, side);
                        wk.waterClimb.placeAttemptCell = side;
                        wk.waterClimb.placedRungs.add(side);
                        wk.waterClimb.sideRung = true;
                        wk.waterClimb.colX = side.getX();
                        wk.waterClimb.colZ = side.getZ();
                        wk.waterClimb.yaw = (float) Math.toDegrees(Math.atan2(
                                -((side.getX() + 0.5) - p.getX()), (side.getZ() + 0.5) - p.getZ()));
                        return TickStep.WALKING;
                    }
                }
                climboutPlaceTick(wk, a, world, p, fillCell, foot, dryGrounded);
                return TickStep.WALKING;
    }

    /**
     * The neighbour cell a side foothold goes into: at foot level, not yet solid, floored by a solid
     * block (so the rung is a real 1-deep case and the floor is the click support), with head room
     * above it to stand in. The heading's own cardinal is tried first so the locked forward press
     * walks the bot onto the rung; the other three follow. {@code null} when every side is a
     * wall or already filled — the caller then falls back to the ordinary own-column attempt.
     */
    private static BlockPos sideFoothold(WorldView world, BlockPos foot, float yaw, net.minecraft.world.phys.AABB body) {
        double rad = Math.toRadians(yaw);
        double hx = -Math.sin(rad), hz = Math.cos(rad);
        BlockPos first = Math.abs(hx) >= Math.abs(hz)
                ? foot.offset(hx >= 0 ? 1 : -1, 0, 0) : foot.offset(0, 0, hz >= 0 ? 1 : -1);
        BlockPos[] order = { first, foot.east(), foot.west(), foot.north(), foot.south() };
        // Vanilla refuses a block whose box meets the bot's, and the bot is 0.6 wide: standing
        // 0.09 into a neighbour cell already voids the click (the first trench click at z .09 never
        // landed). The margin buys the tick or two of drift before the server answers.
        net.minecraft.world.phys.AABB clearance = body.inflate(SIDE_RUNG_CLEARANCE, 0, SIDE_RUNG_CLEARANCE);
        for (BlockPos n : order) {
            if (world.isSolid(n) || world.isHazard(n)) continue;
            if (!world.isSolid(n.below())) continue;
            if (!world.isPassable(n.above()) || !world.isPassable(n.above(2))) continue;
            if (clearance.intersects(new net.minecraft.world.phys.AABB(n))) continue;
            return n;
        }
        return null;
    }

    /** A dry cell beside {@code foot} that the bot can step onto without climbing: standable at
     *  the same level or one below, and neither it nor its floor is water. */
    private static boolean flushExitBeside(WorldView world, BlockPos foot, java.util.Set<BlockPos> placedRungs) {
        for (BlockPos n : new BlockPos[] { foot.east(), foot.west(), foot.north(), foot.south() }) {
            if (world.isWater(n) || world.isWater(n.below())) continue;
            // A rung this climb-out placed itself is not the shore: two side footholds a tick apart
            // read as "flush exit" one course up and the takeover let go three courses under the bank.
            boolean ownRung = ControlConfig.walkerShallowWaterSideFoothold && placedRungs.contains(n.below());
            if (!ownRung && world.canStandAt(n) && leadsOn(world, foot, n)) return true;
            BlockPos d = n.below();
            boolean ownLowerRung = ControlConfig.walkerShallowWaterSideFoothold && placedRungs.contains(d.below());
            if (!ownLowerRung && !world.isWater(d.below()) && world.canStandAt(d) && leadsOn(world, foot, d)) return true;
        }
        return false;
    }

    /** An exit cell is shore only if it connects onward: one of its own neighbours other than
     *  {@code foot} is dry and standable at its level or one off. A 1×1 island — a stray rung, a
     *  post — is not an exit, however dry (wd.clientFlowingTrenchPlaceOut topped out onto one). */
    private static boolean leadsOn(WorldView world, BlockPos foot, BlockPos exit) {
        if (!ControlConfig.walkerShallowWaterSideFoothold) return true;
        for (BlockPos m : new BlockPos[] { exit.east(), exit.west(), exit.north(), exit.south() }) {
            if (m.getX() == foot.getX() && m.getZ() == foot.getZ()) continue;
            if (world.isWater(m) || world.isWater(m.below())) continue;
            if (world.canStandAt(m) || world.canStandAt(m.above()) || (world.canStandAt(m.below()) && !world.isWater(m.below(2)))) return true;
        }
        return false;
    }
}
