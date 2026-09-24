package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import net.magicterra.worlddriver.bot.util.BlockMatch;
import net.magicterra.worlddriver.bot.util.NearestFirstScan;
import java.util.Set;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Deque;
import java.util.ArrayDeque;

public final class MineProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;

    private final Set<String> targetIds;
    // Per-id matchers: each entry is an exact id or a '#tag' selector
    // (e.g. #minecraft:logs matches any log species). A block is a target
    // when ANY matcher accepts it.
    private final List<Predicate<BlockState>> targetMatchers;
    private final int desiredQty;
    private final int searchRadius;
    private final Walker walker = new Walker("mine");
    /** The approach walker, for its {@link Walker#tallies()} (the collect walker's are not summed). */
    public Walker walker() { return walker; }
    {
        // Approach stands live within the ≤64-block scan; embedded/unreachable
        // candidates must price out fast (the 100t no-approach blacklist is the
        // real gate). Full 50k/2s deep searches here only chopped the client
        // (~25 fps for seconds after every block break) and delayed the
        // blacklist — see Walker.setSearchBudget.
        walker.setSearchBudget(8_000, 400);
    }
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int broken;
    // When the last SEARCH returned no usable target ONLY because every in-range
    // candidate needs a tool the bot doesn't have (breaks but drops nothing), this
    // holds the actionable abort signal ("blocked: <block> needs <tool> …"); null
    // when the miss was for the ordinary reasons (no blocks / no stand). Recomputed
    // from scratch on every scanForTarget. See canHarvest / the tool gate below.
    private String noTargetReason;
    // Position where this mine command began. Targets beyond
    // BotConfig.mineMaxDriftFromStart of this anchor are rejected so a single
    // mine command can't chain hops across the world (e.g. swim an ocean toward
    // scattered red_sand). Set lazily on first tick (attach has no player).
    private BlockPos startAnchor;
    /** <b>Write only through {@link #aimAt}.</b> Five sites used to assign this field directly, and
     *  the trunk-tax waiver below has to know about every one of them — a flag maintained beside
     *  five assignments is a flag with five authors. */
    private BlockPos currentTarget;
    private BlockPos currentStand;
    private Direction currentFace;

    /**
     * Which MineProcess owns the trunk-tax waiver, or null when nobody is working a log.
     *
     * <p><b>Why this exists.</b> {@code pathfinderLogBreakTax} ships at 3.0 so A* stops chewing
     * through forests it is merely PASSING, and that is correct everywhere except the one job whose
     * whole purpose is to chew through a tree: reaching the fifth log of a trunk means breaking the
     * four under it, and at 3× those paths price out. Measured on the ladder's wood rung, same seed,
     * one variable: 13 logs / 2 914 ticks at 1.0 against 6 logs / 13 899 ticks at 3.0, with 49 rows
     * of {@code [mine] no approach to stand} naming the mechanism. So the tax is waived for the leg
     * that goes to fetch a log, and left at 3.0 for every leg that is merely travelling.
     *
     * <p><b>Do not grep for that string</b> — it no longer exists. The row that records this
     * failure is {@link #retireTarget}'s unconditional {@code [mine] blacklist <pos> (<why>)}, and
     * the {@code why} for it reads {@code made no progress toward stand … within …t (closest …
     * blocks) — unreachable}. Counting a retired wording on a current run yields zero, which reads
     * as "fixed" and means "the instrument was renamed".
     *
     * <p><b>Why an owner and not a boolean.</b> Clearing on any exit would let a winding-down
     * instance wipe a live one's waiver: old instance's {@code finish()} can run after a new
     * instance's first {@code aimAt}. One textual writer with two calling instances is still two
     * authors. Only the owner may release.
     *
     * <p><b>Why {@link #onCancelled} overrides.</b> {@code BotProcess.onCancelled} is a no-op by
     * default and this class did not override it, while {@code cancelAllProcesses("player-death")}
     * and {@code ChainProcessLifecycle.drop} both end a process WITHOUT running any of the five
     * assignment sites. Dying up a tree would otherwise leave the waiver set for the rest of the
     * session — every subsequent journey silently priced at 1.0, which is the regression the tax
     * was added to fix.
     *
     * <p><b>Where that call actually happens</b>, because grepping {@code BotApiImpl} for
     * {@code onCancelled} returns nothing and reads as "it never gets called": the client funnel is
     * {@code cancelAllProcesses → cancelCurrent → UserTaskChain.cancel}, and the call is on
     * {@code UserTaskChain:47}. Starting a new order funnels there too — {@code setProcess} cancels
     * the outgoing process with {@code "superseded"} before attaching the new one. The server rig's
     * two entry points ({@code ServerWorldDriver.runProcess} / {@code mine}) did NOT, and were given
     * the same door for this waiver's sake.
     */
    private static volatile MineProcess logWaiverOwner;

    /** True while some MineProcess is working a goal whose target block IS a log — the trunk tax is
     *  waived for exactly that stretch. Read by {@code ClientWorldView.breakCost}, which runs once
     *  per candidate node inside the A* loop; that is why this answers from a stored boolean-ish
     *  reference rather than resolving a BlockPos against the level. */
    public static boolean miningALog() { return logWaiverOwner != null; }

    /**
     * The one place {@link #currentTarget} changes, so the waiver is DERIVED from the goal rather
     * than latched beside it: aim at a non-log (or at nothing) and it lapses on the same line.
     *
     * <p>That also gets the sub-goals right for free. The leaf-clearing and overburden sites aim at
     * a leaf or a covering block, not at a log, so the waiver correctly lifts for those stretches
     * and comes back when the log does.
     */
    private void aimAt(BlockPos target, Level lvl) {
        currentTarget = target;
        if (target != null && lvl != null && lvl.getBlockState(target).is(BlockTags.LOGS)) {
            logWaiverOwner = this;
        } else if (logWaiverOwner == this) {
            logWaiverOwner = null;
        }
    }

    /** Release the waiver on the paths that never reach {@link #aimAt} — see {@link #logWaiverOwner}. */
    @Override public void onCancelled(String reason) {
        if (logWaiverOwner == this) logWaiverOwner = null;
    }
    // True when currentTarget is a leaf being cleared to open access to a real
    // target (not itself a quota block) — see findClearingTarget.
    private boolean currentTargetClearing;
    // death#26-followup (07-20 live): per-target no-progress watchdog. The walker
    // never returns FAILED for a log up a sheer DRY face — arc-wedge→fellOffPath just
    // repaths (the known-UNSOLVED steep-dry-climb execution churn, see
    // reference_steep_mountain_limit_cycle_revisit_detection). It rams the wall and
    // takes fall damage forever, and MINE — which blacklists ONLY on FAILED — pins on
    // that one log draining HP (live: HP 20→5.3, 0 logs harvested). Bound it here: if
    // the bot makes no net progress TOWARD the stand for GOING_STALL_TICKS, treat the
    // target as unreachable-in-practice (blacklist + re-scan for a reachable log).
    // Tracks the closest the foot has ever gotten to the stand; a real walk keeps
    // improving that (watchdog never fires), only a churn plateaus it.
    private double goingBestDist = Double.MAX_VALUE;
    private int goingStallTicks;
    private static final int GOING_STALL_TICKS = 100;   // ~5 s of zero net approach
    private static final double GOING_PROGRESS_EPS = 0.5;  // blocks closer = real progress
    // death#26-followup: cumulative-damage abort. The per-target watchdog bounds ONE
    // unreachable log, but a hillside/cliff forest offers MANY high logs (dy 6-8); the
    // bot cycles through them, each dry-steep-climb attempt costing fall damage, and
    // dies CUMULATIVELY (live repro: HP 20→9→dead over 135 s, only 2 logs harvested).
    // A bot must not DIE trying to mine: if HP falls MINE_DAMAGE_ABORT below its peak
    // this command (or reaches MINE_HP_CRITICAL), abort ALIVE — the strategy layer
    // then relocates to flatter terrain. Reachable mining takes no fall damage, so a
    // steady-HP flat mine never trips this (full-HP scenes stay at peak == current).
    private float minePeakHp;                            // max HP seen this command (0 → set on first tick)
    private static final float MINE_DAMAGE_ABORT = 8f;   // net HP lost from peak → abort
    private static final float MINE_HP_CRITICAL = 4f;    // absolute floor backstop
    private int breakingTicks;
    private String breakStartId = "";
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, BREAKING, COLLECT }
    // Last few break positions — used as goal hints during COLLECT so the
    // bot walks back through where it just mined and lets vanilla's pickup
    // magnet vacuum the drops. Capped to avoid backtracking forever.
    private final Deque<BlockPos> recentBreaks = new ArrayDeque<>();
    private int collectTicks;
    private final Walker collectWalker = new Walker("mine.collect");
    { collectWalker.setSearchBudget(8_000, 400); }   // drops are even nearer
    private BlockPos currentCollectGoal;
    /** Drop cells the collect walker reported FAILED on. findCollectGoal returns the NEAREST
     *  drop, so without a skip list a single unreachable one is handed back every tick and
     *  shadows every other drop until the collect cap expires. */
    private final Set<BlockPos> unreachableDrops = new HashSet<>();
    /** What the collect walker last said. Read only by {@link #finish} — a terminal verdict that
     *  cannot name the sweep's own behaviour makes the reader guess between three bugs. */
    private Walker.Step lastCollectStep;
    /** Re-plans spent on the current collect goal after an ARRIVED that was not at it. */
    private int collectRepaths;
    private static final int COLLECT_MAX_REPATHS = 3;
    /** Retirement causes, split. See the COLLECT stuck-handler for why the split matters. */
    private int retiredUnpathable;
    private int retiredArrivedShort;
    /** Mining targets blacklisted this sweep — see {@link #retireTarget}. A different ledger from
     *  the two above, which count DROPS the collect walker gave up on. */
    private int retiredTargets;
    private int pickupWaitTicks;
    /** Ticks spent standing on an ARRIVED collect goal whose item is still there. */
    private int collectStuckTicks;
    /** How long "arrived" may coexist with "the item is still on the ground" before the drop is
     *  written off. A handful of ticks, because vanilla's magnet fires on the very next tick when
     *  the body really is on top of the item — anything longer is the body being somewhere it
     *  cannot reach from. */
    private static final int COLLECT_STUCK_TICKS = 10;
    private static final int MAX_COLLECT_TICKS = 240;       // ~12 s @ 20 tps — long enough to walk to all 8 break spots
    private static final int COLLECT_SCAN_RADIUS = 8;       // matches vanilla item lifetime drift
    /** How long COLLECT will stand still for a drop that is already at its feet but is still
     *  counting down the 10-tick pickup delay every freshly-broken block gives its item. Twice
     *  the delay, so the wait is over long before the cap — the cap only bounds the pathological
     *  case where the delay never expires because nothing is ticking the entity. */
    private static final int PICKUP_DELAY_WAIT_TICKS = 20;
    /** Blocks. Wide enough to cover vanilla's pickup box (bounding box inflated 1.0/0.5/1.0),
     *  narrow enough that a delayed drop we would have to WALK to is not mistaken for one that
     *  will fall into our hands — that one is findCollectGoal's job once it becomes pickable. */
    private static final double PICKUP_WAIT_RADIUS = 2.0;
    // gap#67-⑤: real safety cap on cells visited per scanForTarget call. Applied
    // to NearestFirstScan's nearest-first order (see below), so a cutoff drops
    // the FARTHEST cells, never an entire dy layer — unlike the old dy-outer
    // loop, radius alone no longer determines which height band goes blind.
    private static final int SCAN_BUDGET = 50_000;

    public MineProcess(List<String> ids, int qty, int radius) {
        this.targetIds = new HashSet<>(ids);
        this.targetMatchers = ids.stream().map(BlockMatch::of).toList();
        this.desiredQty = qty;
        this.searchRadius = radius;
    }

    /** True when {@code bs} matches any requested id / tag selector. */
    private boolean isTarget(BlockState bs) {
        for (Predicate<BlockState> m : targetMatchers) if (m.test(bs)) return true;
        return false;
    }

    public String kind() { return "mine"; }

    public void attach(BotState st) {
        st.mine.active = true;
        st.mine.goal = String.join(",", targetIds) + "×" + desiredQty;
        st.mine.startedAtMs = System.currentTimeMillis();
        st.mine.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        if (a.entity() == null) { st.mine.lastError = "player vanished"; finish(st, null, null, "player vanished"); return true; }
        // The drops go into a player's inventory: a body that is not a player has no hands for this.
        Player p = a.asPlayer();
        hands = a.hands().orElse(null);
        if (hands == null || p == null) { st.mine.lastError = BodyReady.Reason.NO_HANDS; finish(st, null, null, BodyReady.Reason.NO_HANDS); return true; }
        Level lvl = p.level();
        // Quota reached → switch to COLLECT instead of declaring done. The
        // old behaviour left the player wherever the last break completed,
        // so items that fell 2-3 blocks away (typical for trees: trunk
        // breaks at head height, items at foot height) just despawned.
        if (broken >= desiredQty && phase != Phase.COLLECT) {
            hands.breakHold(false);
            phase = Phase.COLLECT;
            collectTicks = 0;
        }
        // Anchor the command to where it began (first tick with a live player).
        if (startAnchor == null) startAnchor = p.blockPosition();

        // Lava-contact safety net: the instant we're touching lava, stop mining.
        // The lava-near target/stand rejection below should keep us out of it, but
        // a dig can reveal a pocket that floods our own cell — bail before the
        // ~4-dmg/tick spiral instead of walking deeper toward the next target.
        // (This is exactly what killed a naked run: a stone dig opened a hidden
        // pocket and the next-target approach stepped into it.)
        if (p.isInLava()) {
            hands.breakHold(false);
            a.commandForward(0);
            a.commandJump(false);
            p.setSprinting(false);
            st.mine.lastError = "aborted: entered lava";
            finish(st, p, lvl, "aborted: entered lava");
            return true;
        }

        // death#26-followup: cumulative-damage abort (see minePeakHp javadoc). Don't
        // die cycling unreachable cliff logs — bail alive once mining has clearly cost
        // health. Skipped once COLLECT is underway (quota met; the harvest succeeded).
        float hpNow = p.getHealth();
        if (hpNow > minePeakHp) minePeakHp = hpNow;
        if (phase != Phase.COLLECT
                && (minePeakHp - hpNow >= MINE_DAMAGE_ABORT || hpNow <= MINE_HP_CRITICAL)) {
            hands.breakHold(false);
            a.commandForward(0);
            a.commandJump(false);
            p.setSprinting(false);
            st.mine.lastError = "aborted: taking damage with no safely-reachable target (hp "
                    + String.format("%.0f", hpNow) + ", peak " + String.format("%.0f", minePeakHp)
                    + ", broken=" + broken + "/" + desiredQty + ")";
            finish(st, p, lvl, "aborted: taking damage");
            return true;
        }

        switch (phase) {
            case SEARCH -> {
                Target t = scanForTarget(lvl, p);
                if (t == null) {
                    // Prefer the tool-block signal when every candidate was skipped only
                    // because the bot lacks the harvesting tool — that's the actionable
                    // hand-off ("go craft/relocate"), not the ambiguous "no reachable target".
                    st.mine.lastError = noTargetReason != null ? noTargetReason
                            : "no reachable target (broken=" + broken + "/" + desiredQty + ")";
                    // Coming up short is not a reason to walk away from what was already
                    // mined. Ask for four ores where the vein holds two and this path used to
                    // return straight from SEARCH, so COLLECT never ran and both drops rotted
                    // on the ground — an "I got nothing" that was really "I got two". The
                    // quota decides how long to keep looking, never who owns the harvest.
                    // lastError survives reset(), so the short-quota signal still reaches the
                    // caller after the sweep.
                    if (broken > 0) {
                        phase = Phase.COLLECT;
                        collectTicks = 0;
                        pickupWaitTicks = 0;
                        return false;
                    }
                    finish(st, p, lvl, noTargetReason != null ? "no harvestable target" : "no reachable target");
                    return true;
                }
                aimAt(t.block, lvl);
                currentStand = t.stand;
                currentFace = t.face;
                currentTargetClearing = t.clearing();
                st.mine.target = currentTarget;
                walker.setGoal(new Goal.Block(t.stand));
                phase = Phase.GOING;
                goingBestDist = Double.MAX_VALUE;      // arm the no-progress watchdog
                goingStallTicks = 0;
            }
            case GOING -> {
                // Make sure attack isn't lingering from the previous block.
                hands.breakHold(false);
                // Already standing on the target's stand cell? Then there is nothing
                // to walk — go straight to breaking. This is the straight-up "mine
                // the overhead block from directly below" case (stand == our own
                // foot cell): handing the Walker a zero-length path makes it report
                // FAILED, which would blacklist a perfectly good target. (The normal
                // side/reach-across stand is a DIFFERENT cell, so this never short-
                // circuits a real walk.)
                if (currentStand != null && p.blockPosition().equals(currentStand)) {
                    hands.selectTool(currentTarget);
                    a.aimAtBlock(currentTarget);
                    breakingTicks = 0;
                    breakStartId = currentBlockId(lvl);
                    phase = Phase.BREAKING;
                    return false;
                }
                Walker.Step s = walker.tick(a, w);
                st.mine.pathLen = walker.pathLen();
                st.mine.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    // The stand exists geometrically but the bot can't WALK to it.
                    // For a leaf-encased canopy log this is the common case: the only
                    // stand findReachStand finds has a clear LOS that slips past an
                    // occluding leaf at an angle, yet no foot-path reaches it. Before
                    // blacklisting the log forever (→ "no reachable target"), try to
                    // CLEAR an occluding leaf — breaking it opens a reachable approach
                    // (after the leaf directly below goes, the log becomes mineable
                    // straight-up from the bot's own cell). Only blacklist if even a
                    // clearing leaf is unreachable.
                    if (!currentTargetClearing) {
                        BlockPos foot = blockPosOf(p);
                        Target clear = findClearingTarget(lvl, foot, currentTarget);
                        if (clear != null) {
                            if (BotConfig.walkerDebug)
                                LOG.info("[mine] stand unreachable for {} -> clear leaf {} (stand {})",
                                        currentTarget, clear.block(), clear.stand());
                            aimAt(clear.block(), lvl);
                            currentStand = clear.stand();
                            currentFace = clear.face();
                            currentTargetClearing = true;
                            st.mine.target = currentTarget;
                            walker.setGoal(new Goal.Block(clear.stand()));
                            phase = Phase.GOING;
                            goingBestDist = Double.MAX_VALUE;   // re-arm for the new (clearing) stand
                            goingStallTicks = 0;
                            return false;
                        }
                    }
                    retireTarget("the walker reported FAILED and there are no obstructing leaves to clear");
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    hands.selectTool(currentTarget);
                    a.aimAtBlock(currentTarget);
                    breakingTicks = 0;
                    // Block id observed at the moment we arrived — used to detect
                    // both successful breaks (id changes) and resyncs (id flickers
                    // to air then back, indicating a rejected predicted destroy).
                    breakStartId = currentBlockId(lvl);
                    phase = Phase.BREAKING;
                } else if (currentStand != null) {
                    // Still walking — no-progress watchdog (see goingBestDist javadoc).
                    // The walker repaths forever against an unclimbable dry face rather
                    // than reporting FAILED, so track net approach to the stand: a real
                    // walk keeps setting a new closest distance; a churn plateaus, and
                    // after GOING_STALL_TICKS with no fresh approach we give up on this
                    // target (blacklist + re-scan) instead of draining HP on it.
                    double dx = p.getX() - (currentStand.getX() + 0.5);
                    double dy = p.getY() - currentStand.getY();
                    double dz = p.getZ() - (currentStand.getZ() + 0.5);
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < goingBestDist - GOING_PROGRESS_EPS) {
                        goingBestDist = dist;
                        goingStallTicks = 0;
                    } else if (++goingStallTicks >= GOING_STALL_TICKS) {
                        retireTarget(String.format(java.util.Locale.ROOT,
                                "made no progress toward stand %2$s within %1$dt (closest %3$.1f blocks) — unreachable",
                                GOING_STALL_TICKS, currentStand, goingBestDist));
                        return false;
                    }
                }
            }
            case BREAKING -> {
                // Release walking keys, hold the break action via the Body:
                //  - CLIENT: the dig latch PLUS a direct continueDestroy on the same block.
                //    The latch drives nothing (see Hands#breakHold); the direct call is what
                //    advances the break, and it makes vanilla's own attack pass stand aside for
                //    the tick. It is still PROGRESSIVE, so the id check below stays honest.
                //  - SERVER: hands.breakHold(true) = level.destroyBlock(aimTarget) (instant), and
                //    continueDestroy is an inherited no-op.
                // Either way the SAME completion check below (block id changed away
                // from the original) detects the break — progressive or instant.
                a.commandForward(0);
                a.commandJump(false);
                p.setSprinting(false);
                // ⚠️ The tool is selected on the way IN (the two selectTool calls in GOING), not
                // here, and re-selecting every BREAKING tick was tried and measured WORSE: it
                // fights holdPlaceable for the selected slot all the way down a shaft — each
                // undoes the other — and the same rig that used to reach the ore then failed to
                // reach it at all inside a larger budget, leaving the ore standing. If the hand
                // ever has to be re-armed at break time, the two writers need arbitration (the
                // AutoTool yield-to-external-writer shape), not a third writer of the same slot.
                //
                // Note this is a CLIENT-path concern only. It was first written down as the
                // explanation for the journey's empty iron bag, and that was wrong:
                // ServerPlayerBody breaks through Level#destroyBlock, which drops through
                // Block.dropResources(..., ItemStack.EMPTY) and never reads the hand at all. On
                // the server avatar the held item cannot cost you a drop today (see
                // ServerPlayerBody#DROP_HARVEST) — it would only start to once breaking moves
                // to the faithful gameMode route.
                // Swing at what is IN THE WAY, not at what is wanted. A buried ore is not
                // breakable from a stand on the surface, and before the avatar had a reach gate
                // that did not matter — it mined straight through the overburden and left the drop
                // sealed in a pocket (see ServerPlayerHands#canBreak). With the gate, aiming at
                // the ore is a swing that can never land: the no-progress watchdog eventually
                // blacklists it and the miner reports "no reachable target" about ore it is
                // standing on top of.
                //
                // The overburden becomes a CLEARING target, which is machinery that already
                // exists for leaves occluding a log — it does not count toward the quota, it does
                // not seed COLLECT, and finishing it re-SEARCHes so the now-exposed block below is
                // picked up normally. Peeling one block per pass is what a player does, and it is
                // also what keeps every drop at the bottom of a hole the body can enter.
                BlockPos overburden = currentTargetClearing ? null : firstBreakableToward(hands, lvl, p, currentTarget);
                if (overburden != null) {
                    aimAt(overburden, lvl);
                    currentTargetClearing = true;
                    breakStartId = currentBlockId(lvl);
                    breakingTicks = 0;
                } else if (!hands.canBreak(currentTarget) && isExposed(lvl, currentTarget)) {
                    // Exposed and STILL not breakable means out of range, and range does not
                    // improve by standing here: a canopy log five blocks above the stand, which
                    // needs climbing, not patience. Retire it now and re-SEARCH so the miner takes
                    // the trunk log at eye level instead of staring up at the crown. The
                    // no-progress watchdog would reach the same conclusion a hundred ticks later,
                    // and a tree is a race against the budget — waiting for it cost the journey's
                    // wood rung all six logs.
                    //
                    // The exposure test is what keeps this from eating buried ore. An UNexposed
                    // target is not permanently lost: the peel above breaks what covers it, and
                    // once a neighbour falls the same block becomes reachable. Retiring on
                    // "cannot break right now" without that distinction left the deepest of the
                    // three ores in wd.serverMineHarvestBuried standing.
                    //
                    // This is the honest shape of the limitation, not a workaround for it: what
                    // the bot cannot do is CLIMB to a log, and until it can, "mine the ones you can
                    // reach" is what a player without a ladder does too.
                    retireTarget("exposed but still cannot be broken ⇒ out of reach, and standing still cannot close the distance");
                    return false;
                }
                a.aimAtBlock(currentTarget);
                hands.breakHold(true);
                hands.continueDestroy(currentTarget);

                breakingTicks++;
                String now = currentBlockId(lvl);
                // Robust completion: id changed away from the original block AND
                // is no longer the same kind. Avoids the 1-tick flicker false-positive.
                if (!now.equals(breakStartId) && !isTarget(lvl.getBlockState(currentTarget))) {
                    // A "clearing" break is an occluding leaf removed only to open
                    // reach/LOS to a real target — it must NOT count toward the quota
                    // nor seed COLLECT (leaves rarely drop, and we want COLLECT to
                    // chase the actual log drops). Re-SEARCH: the now-exposed log
                    // becomes reach-mineable on the next scan.
                    if (!currentTargetClearing) {
                        broken++;
                        // Remember where the block stood so COLLECT can walk
                        // back through it. Keep only the last 8 — past that,
                        // the trail is long enough that the drops have likely
                        // despawned anyway. Skip a cell that flooded with lava the
                        // moment we broke it — COLLECT must never path back into it.
                        if (!lavaTouching(lvl, currentTarget)) {
                            recentBreaks.addLast(currentTarget);
                            while (recentBreaks.size() > 8) recentBreaks.removeFirst();
                        }
                    }
                    hands.breakHold(false);
                    aimAt(null, lvl);
                    if (broken >= desiredQty) {
                        phase = Phase.COLLECT;
                        collectTicks = 0;
                    } else {
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BotConfig.breakTimeoutTicks) {
                    retireTarget("still unbroken after " + breakingTicks + "t of mining (limit "
                            + BotConfig.breakTimeoutTicks + "t)");
                }
            }
            case COLLECT -> {
                // Walk toward the nearest dropped ItemEntity (vanilla's
                // pickup magnet does the rest at ~1 block). Falls back to
                // walking through remembered break positions when no items
                // are visible — handles the chunk-not-loaded case where
                // ClientLevel hasn't received the SpawnEntity packet yet.
                hands.breakHold(false);
                collectTicks++;
                BlockPos goal = findCollectGoal(lvl, p);
                // Nothing to walk to — but "nothing to walk to" is not the same as "nothing
                // left". A block broken from arm's length drops its item AT OUR FEET with a
                // 10-tick pickup delay, and findCollectGoal skips delayed items (walking to
                // one is pointless) while the recentBreaks fallback pops the break cell we are
                // already standing on. Both correctly return "no goal", one tick after the
                // break — and completing there abandoned the harvest and reported success.
                // That is how a mine of qty 1 banked nothing: 24 ticks, ore gone, drop on the
                // floor, lastError null. Stand still instead and let vanilla's magnet fire.
                if (goal == null && awaitingPickupDelay(lvl, p)
                        && ++pickupWaitTicks <= PICKUP_DELAY_WAIT_TICKS) {
                    return false;
                }
                if (goal == null || collectTicks > MAX_COLLECT_TICKS) {
                    finish(st, p, lvl, collectTicks > MAX_COLLECT_TICKS
                            ? "collect timed out after " + MAX_COLLECT_TICKS + " ticks"
                            : "collect swept everything it could reach");
                    return true;
                }
                pickupWaitTicks = 0;
                if (!goal.equals(currentCollectGoal)) {
                    if (!goal.equals(st.mine.target)) collectRepaths = 0;   // a different drop, fresh allowance
                    currentCollectGoal = goal;
                    collectStuckTicks = 0;
                    // Stand ON the drop's own cell, which is what a player does. An adjacency goal
                    // was tried and is subtly wrong: "adjacent" is measured to the CELL while the
                    // magnet reaches from the body to the ITEM, and vanilla's reach is only about
                    // 1.4 blocks (bounding box inflated 1.0). Measured on the three-ore rig, the
                    // sweep reported ARRIVED standing 1.6 blocks from a drop and then stood there
                    // for the whole collect budget: a goal satisfied and an item not picked up.
                    //
                    // ⚠️ THE OTHER COLLECT LOOP IN THIS PACKAGE CONCLUDED THE OPPOSITE.
                    // CombatProcess#collectDrops sets Goal.Near(ib, 1) and its comment argues
                    // "pickup touch reaches an adjacent cell" — the exact claim the measurement
                    // above refuted. Goal.Near(t,1).reached is `distSqr <= 1`, satisfied at any of
                    // the six orthogonal neighbours, so it admits the 1.6-block stand this site
                    // saw fail; Goal.Block.reached is `equals`, which cannot. Neither site is
                    // being changed here, because "it failed on the mine rig" is not evidence
                    // about the combat rig — but the two must not be read as independent
                    // opinions. What would settle it is one reading, taken on the COMBAT sweep:
                    // eye-to-item distance on the tick the walker reports its terminal, against
                    // the ~1.4 magnet radius. Until someone takes it, fix one, look at the other.
                    collectWalker.setGoal(new Goal.Block(goal));
                }
                Walker.Step step = collectWalker.tick(a, w);
                lastCollectStep = step;
                // Two ways this drop is not going to happen, and both used to be silently ignored:
                // the walker says it cannot path there, or it says it has ARRIVED and the item is
                // still lying there anyway (arrived-but-out-of-reach — a shaft bottom the body
                // cannot enter). Either way, stop pouring the budget into it. Without a skip list
                // findCollectGoal hands back the same nearest drop every tick, so ONE dead drop
                // used to shadow every reachable one behind it: three ores mined, three drops on
                // the ground, an empty bag and no error.
                boolean cannotPath = step == Walker.Step.FAILED;
                // ARRIVED does not mean "at the goal". The walker reports it when the path it
                // computed runs out, and when A* cannot reach the goal it returns a best-effort
                // partial path — so a sweep can report success standing four blocks from the drop
                // (measured: `retired 2 drop(s): 0 unpathable + 2 arrived-but-short`, distances 4.1
                // and 6.4). Retiring on that throws away drops the body never went to.
                //
                // So an ARRIVED that is not actually AT the cell re-plans, a bounded number of
                // times: the world changes while a mine runs — blocks fall, the body's own shaft
                // opens routes — and the second search often reaches what the first could not.
                // Only after those are spent is the drop genuinely out of reach.
                boolean arrivedShort = step == Walker.Step.ARRIVED
                        && !p.blockPosition().equals(goal)
                        && collectRepaths < COLLECT_MAX_REPATHS;
                if (arrivedShort) {
                    collectRepaths++;
                    currentCollectGoal = null;      // forces a fresh setGoal, hence a fresh search
                    collectStuckTicks = 0;
                    st.mine.target = goal;
                    return false;
                }
                boolean stuck = cannotPath
                        || (step == Walker.Step.ARRIVED && ++collectStuckTicks > COLLECT_STUCK_TICKS);
                if (stuck) {
                    collectRepaths = 0;
                    // Which of the two retired it, counted rather than guessed. "The walker refuses
                    // to path there" and "the walker believes it has arrived and the item is still
                    // on the floor" are opposite bugs — one is the pathfinder's move set, the other
                    // is the goal being satisfied short of vanilla's pickup reach — and both end as
                    // an unretrieved drop. wd.serverWalkIntoAPit proves a two-deep pit IS pathable,
                    // so a FAILED here would mean the collect walker differs from a plain one.
                    if (cannotPath) retiredUnpathable++; else retiredArrivedShort++;
                    unreachableDrops.add(goal);
                    currentCollectGoal = null;
                    collectStuckTicks = 0;
                }
                st.mine.target = goal;
                st.mine.pathLen = collectWalker.pathLen();
                st.mine.pathStep = collectWalker.pathStep();
            }
        }
        return false;
    }

    /**
     * Pick a COLLECT destination: prefer a visible ItemEntity (vanilla magnet
     * will grab it once we're adjacent), otherwise the oldest remembered
     * break position. We pop a break position as soon as we're within 1.5
     * blocks of it so the deque drains and we walk through every spot
     * instead of camping the nearest one. Returns null when there's nothing
     * left to chase — that's COLLECT's natural completion.
     */
    private BlockPos findCollectGoal(Level lvl, Player p) {
        if (lvl != null) {
            AABB box = p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS);
            var items = lvl.getEntitiesOfClass(ItemEntity.class, box,
                    it -> it.isAlive() && !it.hasPickUpDelay());
            BlockPos best = null;
            double bestD2 = Double.MAX_VALUE;
            for (var it : items) {
                BlockPos cell = blockPosOf(it);
                if (unreachableDrops.contains(cell)) continue;   // the walker already said no
                double d2 = it.distanceToSqr(p);
                if (d2 < bestD2) { bestD2 = d2; best = cell; }
            }
            if (best != null) return best;
        }
        // No item visible — sweep through remembered break positions in FIFO
        // (see the COLLECT case for why a null return here does not mean "done").
        // order. Pop any we've already reached so we keep moving toward the
        // next spot instead of looping.
        while (!recentBreaks.isEmpty()) {
            BlockPos bp = recentBreaks.peekFirst();
            // The skip list has to cover THIS path too. It guarded the item scan and not the
            // fallback, so once every visible drop was retired the sweep dropped through to here
            // and handed back the same unreachable break cell — the bottom of a hole it cannot
            // enter — for the rest of the budget. Measured: both drops retired as unreachable and
            // the verdict still read `sweep WALKING`, 240 ticks of walking toward a cell already
            // known to be dead. Same bug as the one fixed on the drop path, one branch over.
            if (unreachableDrops.contains(bp)) { recentBreaks.removeFirst(); continue; }
            double dx = p.getX() - (bp.getX() + 0.5);
            double dy = p.getY() - bp.getY();
            double dz = p.getZ() - (bp.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz < 1.5 * 1.5) {
                recentBreaks.removeFirst();
                continue;
            }
            return bp;
        }
        return null;
    }

    /**
     * End the command with an honest verdict, not just an absence of error.
     *
     * <p>{@code BunkerProcess} and {@code IntentProcess} already stamp {@code goalReached} /
     * {@code endReason} at every terminal exit — "gap#68-R2", because a run that ends with
     * {@code active:false} and no {@code lastError} is indistinguishable from a run that
     * succeeded. Mine was the outlier, and it is the verb where that hurts most: it can meet its
     * quota, sweep, end clean, and have banked nothing, because breaking a block and acquiring it
     * are two different events and only the first one was ever reported. Diagnosing that took
     * three full playthrough runs to tell apart from "the ore was never reached".
     *
     * <p>So the verdict carries the count that was missing — how many drops were still lying in
     * the collect radius when the sweep ended. It is reported through {@code endReason} rather
     * than {@code lastError} because leftovers are not necessarily a failure (an unreachable drop
     * behind a wall is a fact about the world), and {@code goalReached} stays the plain
     * quota question.
     */
    private void finish(BotState st, Player p, Level lvl, String reason) {
        // Release the trunk-tax waiver here too, not only on the assignment sites: a run that ends
        // mid-BREAKING (budget spent, quota met on the last swing) leaves currentTarget pointing at
        // a log, and the waiver would outlive the order that earned it.
        aimAt(null, lvl);
        int left = p == null || lvl == null ? 0 : looseDrops(lvl, p);
        st.mine.goalReached = broken >= desiredQty;
        st.mine.endReason = reason + " (broke " + broken + "/" + desiredQty
                + (left > 0 ? ", left " + left + " drop(s) on the ground" : "")
                // What the sweep was DOING when it ran out of budget. "Left 2 drops" says the
                // harvest was incomplete; it does not say whether the walker was refusing to
                // path, insisting it had arrived, or still searching — three different bugs that
                // all end with items on the floor, and the count alone sent two investigations
                // to the wrong file.
                + (lastCollectStep != null
                        ? ", sweep " + lastCollectStep + " toward " + currentCollectGoal
                          + ", retired " + unreachableDrops.size() + " drop(s): "
                          + retiredUnpathable + " unpathable + " + retiredArrivedShort
                          + " arrived-but-short"
                        : "")
                // The TARGET ledger, which the drop ledger above is not. `retired N drop(s)` counts
                // items COLLECT gave up on; a target retired in GOING or BREAKING never becomes a
                // drop at all, and until this line the summary could not tell "there was nothing to
                // mine" from "everything I picked, I then blacklisted".
                + (retiredTargets > 0 ? ", blacklisted " + retiredTargets + " target(s)" : "")
                + ")";
        st.mine.reset();
        // The quota is the ask; drops left on the ground stay in endReason and do not fail it.
        failure = st.mine.goalReached ? null
                : st.mine.lastError != null ? st.mine.lastError : st.mine.endReason;
    }

    private String failure;

    @Override public String failure() { return failure; }

    /**
     * Retire {@code currentTarget} and go back to SEARCH — the ONE door out of a target this
     * sweep cannot finish, and the only place that says which door it was.
     *
     * <p>Four call sites used to inline these lines. THREE OF THEM SAID NOTHING AT ALL and the
     * fourth spoke only behind {@code walkerDebug}, which no journey run sets — so a blacklist,
     * which is a PERMANENT retirement that immediately re-enters SEARCH, was invisible. That is
     * the loop Q7 measured from the other end: 174 searches with an identical start and goal,
     * {@code owner=mine}, and nothing in the log naming the cell that had just been retired or
     * why. Unconditional is right on volume too — each line costs one target forever, so the
     * count is bounded by the candidates, not by the ticks.
     *
     * <p>{@code breakHold(false)} runs on every door, including the two that could not have been
     * holding one. Releasing a hold nobody took is a no-op; forgetting it on the door that DID
     * take one leaves the avatar swinging at a cell it has stopped tracking.
     */
    private void retireTarget(String why) {
        retiredTargets++;
        LOG.info("[mine] blacklist {} ({}) — retired target #{}, back to SEARCH",
                currentTarget, why, retiredTargets);
        blacklist.add(currentTarget);
        hands.breakHold(false);
        aimAt(null, null);          // no Level here, and none is needed: a null target is never a log
        phase = Phase.SEARCH;
    }

    /** True when at least one of the six faces is open — i.e. some ray could reach this block.
     *  The same test {@code ServerPlayerBody} gates breaking on, asked here so the miner can
     *  tell "too far" (permanent from this stand) from "walled in" (the peel will fix it). */
    private static boolean isExposed(Level lvl, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!lvl.getBlockState(n).isSolidRender(lvl, n)) return true;
        }
        return false;
    }

    /**
     * The block to swing at on the way to {@code target}, or null when {@code target} itself is
     * already reachable (the ordinary case, and the only one before the reach gate existed).
     *
     * <p>Walks the segment from the eye to the target's centre and returns the FIRST solid block
     * along it that the avatar can actually break. That is the overburden: the thing standing
     * between a stand and the ore. Returning null when nothing on the line qualifies is
     * deliberate — a target that is neither reachable nor approachable through anything breakable
     * belongs to the no-progress watchdog and the blacklist, not to an infinite peel.
     *
     * <p>Sampled rather than voxel-traversed at 0.2 blocks — a fifth of a block cannot skip a full
     * cube, and the segment here is bounded by the player's own interaction range, so this is a
     * couple of dozen samples and not a raycast worth optimising.
     */
    private static BlockPos firstBreakableToward(Hands hands, Level lvl, Player p, BlockPos target) {
        if (hands.canBreak(target)) return null;
        Vec3 eye = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        double span = eye.distanceTo(centre);
        if (span <= 0.001) return null;
        Vec3 stride = centre.subtract(eye).scale(0.2 / span);
        BlockPos last = null;
        for (int i = 1; i * 0.2 <= span; i++) {
            Vec3 at = eye.add(stride.scale(i));
            BlockPos cell = BlockPos.containing(at);
            if (cell.equals(last)) continue;
            last = cell;
            if (cell.equals(target)) break;
            if (lvl.getBlockState(cell).isAir()) continue;
            if (hands.canBreak(cell)) return cell;
        }
        return null;
    }

    /** Item entities still lying inside the collect radius — what the sweep did not get. */
    private static int looseDrops(Level lvl, Player p) {
        return lvl.getEntitiesOfClass(ItemEntity.class,
                p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS), ItemEntity::isAlive).size();
    }

    /**
     * True when a drop is close enough that standing still will collect it, but is not
     * pickable yet — the {@code setDefaultPickUpDelay()} every block drop is born with.
     * Deliberately narrow: only drops within {@link #PICKUP_WAIT_RADIUS} count, so this
     * waits for the item at our feet and never for one across the arena.
     */
    private static boolean awaitingPickupDelay(Level lvl, Player p) {
        if (lvl == null) return false;
        AABB box = p.getBoundingBox().inflate(PICKUP_WAIT_RADIUS);
        return !lvl.getEntitiesOfClass(ItemEntity.class, box,
                it -> it.isAlive() && it.hasPickUpDelay()).isEmpty();
    }

    /** Scan candidates within radius, filter by target id + blacklist + stand reachability, pick nearest. */
    private Target scanForTarget(Level lvl, Player p) {
        if (lvl == null) return null;
        noTargetReason = null;
        BlockPos foot = blockPosOf(p);
        int r = searchRadius;
        Target best = null;
        long bestD2 = Long.MAX_VALUE;
        // Nearest real target we could SEE but not reach — the leaf-clearing
        // fallback (below) tries to open access to it when nothing else is reachable.
        BlockPos nearestUnreachable = null;
        long nuD2 = Long.MAX_VALUE;
        // Nearest candidate skipped ONLY because the bot owns no tool that would
        // harvest it (breaks but drops nothing). Kept so we can emit an actionable
        // abort when nothing reachable-and-harvestable remains.
        BlockPos toolBlocked = null;
        String toolBlockedTool = null;
        int scanned = 0;
        int targetHits = 0;          // DIAG: cells passing isTarget
        int targetLavaSkips = 0;     // DIAG: targets skipped for lava
        // gap#67-⑤: nearest-first order (shared with GoalResolver.findNearestStandForBlock)
        // so SCAN_BUDGET drops the FARTHEST cells instead of truncating the top of the
        // vertical band — the old dy-outer loop silently never reached dy in [+4,+8] once
        // a wide horizontal radius blew the budget on the low layers (a jungle-canopy log
        // sat in plain sight and was reported unreachable).
        BlockPos[] offsets = NearestFirstScan.offsetsNearestFirst(r, BotConfig.mineSearchVerticalRadius);
        int budget = Math.min(offsets.length, SCAN_BUDGET);
        for (int i = 0; i < budget; i++) {
            scanned++;
            BlockPos bp = foot.offset(offsets[i]);
            // Cap horizontal drift from where the command began so a chain of
            // SEARCH hops can't walk the bot across the world / an ocean.
            if (BotConfig.mineMaxDriftFromStart > 0 && startAnchor != null) {
                long hx = bp.getX() - startAnchor.getX();
                long hz = bp.getZ() - startAnchor.getZ();
                long cap = BotConfig.mineMaxDriftFromStart;
                if (hx * hx + hz * hz > cap * cap) continue;
            }
            if (blacklist.contains(bp)) continue;
            BlockState bs = lvl.getBlockState(bp);
            if (!isTarget(bs)) continue;
            targetHits++;
            // Don't dig a block that walls off lava: breaking it lets the
            // pocket flood toward us. Lava is loaded in the world model even
            // when hidden behind a solid face, so a face-neighbour scan
            // catches the pocket BEFORE the dig opens it.
            if (lavaTouching(lvl, bp)) { targetLavaSkips++; continue; }
            // Tool gate: a block that needs a correct tool for its drop, when the
            // bot holds/owns none, BREAKS but drops NOTHING — mining it is pure
            // futility (the bare-hand stone grind that spun the campaign soft-lock:
            // block.break fires forever, inventory never fills). Skip it, but keep
            // one so the caller emits an actionable "needs <tool>" abort.
            if (!canHarvest(p, bs)) {
                if (toolBlocked == null) { toolBlocked = bp; toolBlockedTool = requiredToolName(bs); }
                continue;
            }
            // Find a standable adjacent position (incl. a pillar-up
            // stand for an otherwise-too-high log, relative to our feet).
            long d2 = (long) bp.distSqr(foot);
            BlockPos stand = findStandableAdjacent(lvl, bp, foot.getY());
            if (stand == null) {
                // Real target, but no stand reaches it (the leaf-encased
                // floating-canopy oak: leaves wall it in and block the reach
                // raycast). Remember the nearest so we can clear its leaves.
                if (d2 < nuD2) { nuD2 = d2; nearestUnreachable = bp; }
                continue;
            }
            if (d2 < bestD2) {
                bestD2 = d2;
                best = new Target(bp, stand, faceFromStandToBlock(stand, bp), false);
            }
        }
        if (best != null) return best;
        // Nothing directly reachable. If a real target is occluded by leaves we can
        // stand-and-break, return one as a clearing target so mining it opens access.
        if (nearestUnreachable != null) {
            Target clear = findClearingTarget(lvl, foot, nearestUnreachable);
            if (BotConfig.walkerDebug)
                LOG.info("[mine] no direct stand; nearestUnreachable={} -> clearing={}",
                        nearestUnreachable, clear == null ? "null" : clear.block());
            if (clear != null) return clear;
            // gap#60 — buried target (an ore fully encased in rock has NO standable
            // adjacent cell, so the geometric stand test rejects it wholesale and the
            // whole command aborts "no reachable target" while a pickaxe sits in hand).
            // The stand test only knows the CURRENT world; reachability through
            // diggable cover is the pathfinder's call — every other verb already digs
            // via the Walker's priced break-route A*. So hand the Walker the
            // face-adjacent cell nearest the bot as the goal and let it carve the
            // tunnel. A genuinely unreachable ore (out of budget, lava-walled) makes
            // the Walker FAIL, which blacklists the ore — still a clean abort.
            if (BotConfig.allowBreak) {
                Target dig = findDigStand(lvl, foot, nearestUnreachable);
                if (BotConfig.walkerDebug)
                    LOG.info("[mine] no clearing leaf; buried target {} -> digStand={}",
                            nearestUnreachable, dig == null ? "null" : dig.stand());
                if (dig != null) return dig;
            }
        }
        // No reachable-and-harvestable target and no leaf we can clear to open one.
        // If the ONLY candidates we saw were tool-blocked, surface that as the reason so
        // the bot aborts with an actionable "needs <tool>" (→ the planner crafts/relocates)
        // instead of grinding for zero yield or reporting a misleading "no reachable target".
        if (toolBlocked != null) {
            noTargetReason = "blocked: "
                    + BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(toolBlocked).getBlock())
                    + " needs " + toolBlockedTool + " — none held or in inventory";
        }
        if (BotConfig.walkerDebug)
            LOG.info("[mine] scan found no target (scanned={} targetHits={} lavaSkips={} toolBlocked={} foot={} r={} vR={})",
                    scanned, targetHits, targetLavaSkips, toolBlocked, foot, r, BotConfig.mineSearchVerticalRadius);
        return null;
    }

    /**
     * True when breaking {@code bs} would actually yield its drop with a tool the bot can
     * bring to hand — i.e. the block needs no correct tool (dirt/gravel/sand/logs mine
     * fine bare-handed), OR some item across the full main inventory (0-35, the reach of
     * {@code selectBestToolFor}) is the correct tool for it. A block that
     * {@code requiresCorrectToolForDrops} with no such tool owned breaks but drops
     * NOTHING, so mining it is futile — the gate keeps it out of the scan.
     */
    private static boolean canHarvest(Player p, BlockState bs) {
        if (!bs.requiresCorrectToolForDrops()) return true;
        var items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stk = items.get(i);
            if (!stk.isEmpty() && stk.isCorrectToolForDrops(bs)) return true;
        }
        return false;
    }

    /** Human-readable name of the tool class a block wants, for the abort signal. */
    private static String requiredToolName(BlockState bs) {
        if (bs.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "a pickaxe";
        if (bs.is(BlockTags.MINEABLE_WITH_AXE)) return "an axe";
        if (bs.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "a shovel";
        if (bs.is(BlockTags.MINEABLE_WITH_HOE)) return "a hoe";
        return "the correct tool";
    }

    /** Per-axis radius around an unreachable target searched for an occluding leaf
     *  we can stand-and-break. Small so we chip the canopy around the log instead
     *  of stripping a forest; one leaf per SEARCH, re-scanning after each break. */
    private static final int LEAF_CLEAR_RADIUS = 3;

    /**
     * The leaf-encased floating-canopy case (badlands azalea oak): the target log has
     * no reachable stand because leaves wall it in AND occlude the reach line of sight
     * (leaves have full collision, so the reach raycast stops on them). Find the
     * reachable leaf NEAREST the log (Chebyshev rings outward) and return it as a
     * non-counting "clearing" target. Mining it — then re-SEARCHing — progressively
     * opens the column until the log itself becomes reach-mineable. A naked,
     * block-less bot breaks leaves by hand, so this needs no tools or pillar blocks.
     * Returns null if no occluding leaf is reachable either (genuinely unreachable).
     */
    private Target findClearingTarget(Level lvl, BlockPos foot, BlockPos log) {
        for (int rad = 1; rad <= LEAF_CLEAR_RADIUS; rad++) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dy = -rad; dy <= rad; dy++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        // Only the cells on THIS Chebyshev ring (nearer rings already done).
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != rad) continue;
                        BlockPos lp = log.offset(dx, dy, dz);
                        if (blacklist.contains(lp)) continue;
                        if (!isLeaf(lvl.getBlockState(lp))) continue;
                        if (lavaTouching(lvl, lp)) continue;
                        BlockPos stand = findStandableAdjacent(lvl, lp, foot.getY());
                        if (BotConfig.walkerDebug)
                            LOG.info("[mine] clearing-candidate leaf {} -> stand {}", lp, stand);
                        if (stand == null) continue;
                        return new Target(lp, stand, faceFromStandToBlock(stand, lp), true);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isLeaf(BlockState bs) {
        return bs.is(BlockTags.LEAVES);
    }

    /**
     * gap#60 — walk goal for a fully buried target: the face-adjacent cell nearest
     * the bot (same-Y cardinals only — the natural tunnel head; above/below stands
     * of an encased ore invite digging past it). The cell is usually SOLID right
     * now; that's the point — the Walker's break-route A* prices and digs the
     * approach, and BREAKING then mines the target from a true face-adjacent cell.
     * Fluid-flooded cells are skipped (never send the tunnel head into a pocket).
     */
    private Target findDigStand(Level lvl, BlockPos foot, BlockPos block) {
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dxz) {
            BlockPos cand = block.offset(d[0], 0, d[1]);
            if (!lvl.getFluidState(cand).isEmpty()) continue;
            double d2 = cand.distSqr(foot);
            if (d2 < bestD2) { bestD2 = d2; best = cand; }
        }
        if (best == null) return null;
        return new Target(block, best, faceFromStandToBlock(best, block), false);
    }

    /** How many blocks above the bot's own feet a pillar-up stand may sit. The bot
     *  climbs a trunk one log at a time (each break re-SEARCHes from the new, higher
     *  pillar top), so this only bounds the FIRST reach — keeping A* from committing
     *  to one giant floating goal up a whole 6+ tall canopy in a single edge. */
    private static final int MAX_PILLAR_RISE = 4;

    private BlockPos findStandableAdjacent(Level lvl, BlockPos block, int footY) {
        // OVERHEAD log (well above our feet): a ground-level reach stand directly
        // below — that the bot can actually walk to and mine straight up from — is
        // what we want, NOT a stand sitting on TOP of an adjacent block at the log's
        // own level (the dy=+1 side case below). Those upper stands are standable
        // but unreachable for a bot on the ground, and returning one as a direct
        // target makes the Walker fail and blacklist a perfectly mineable log. So
        // for an overhead block, try the reach-from-below stand FIRST; only if it is
        // out of reach / LOS-blocked do we fall through to the side/pillar cases
        // (the genuine climb-the-trunk situations). LOS-blocked here is the
        // leaf-encased canopy log — the GOING leaf-clearing fallback opens it up.
        if (block.getY() >= footY + 2) {
            BlockPos reach = findReachStand(lvl, block);
            if (reach != null) return reach;
            // Reach-from-below is blocked. If a LEAF occludes the straight-up column
            // below the log, return null so the caller's leaf-clearing fallback opens
            // it — rather than falling through to a side/on-top stand a ground bot
            // can't path to (which makes the Walker drag the bot around, then
            // blacklist the log). Once the occluding leaf is cleared, reach-from-below
            // returns our own cell and the log is mined straight up. A NON-leaf
            // occlusion (too tall / solid above) still falls through to pillar-up.
            for (int y = footY + 1; y < block.getY(); y++) {
                if (isLeaf(lvl.getBlockState(new BlockPos(block.getX(), y, block.getZ())))) return null;
            }
        }
        // Try same-Y 4 cardinals, then Y-1, then Y+1.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] dyTry = {0, -1, 1};
        for (int dy : dyTry) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHere(lvl, cand)) return cand;
            }
        }
        // Stand DIRECTLY BELOW and mine UPWARD — the tree-trunk / overhead-log
        // case the side checks miss: a vertical trunk's neighbours at its own
        // level are all trunk/leaves, so no side-stand exists, yet the bot at the
        // base can just look up and break it. Foot 2 below puts the block at
        // head+1 (reach ~2) with the head cell (block.below(1)) clear for line of
        // sight. canStandHere already requires that head cell passable.
        BlockPos below2 = block.offset(0, -2, 0);
        if (canStandHere(lvl, below2)) return below2;

        // Standing on top of the block (mining downward).
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHere(lvl, above)) return above;

        // PILLAR-UP (to raise the bot): the log is too HIGH for any ground stand — an upper
        // trunk/canopy log directly overhead is the tree itself (can't pillar
        // into it). If a CLEAR vertical column sits BESIDE the log, return an
        // elevated side-stand level with it. The pathfinder's PillarUp chain
        // climbs that offset column (placing a held block under the feet each
        // jump) and the bot then breaks the log from the side. We only offer it
        // when the log is above our feet but within MAX_PILLAR_RISE, so the bot
        // climbs a trunk one log per SEARCH rather than one huge floating goal.
        int rise = block.getY() - footY;
        if (rise >= 1 && rise <= MAX_PILLAR_RISE) {
            for (int[] d : dxz) {
                BlockPos stand = block.offset(d[0], 0, d[1]);   // beside the log, same Y
                // The stand + head cells beside the log must be open to occupy,
                // and the support cell directly below the stand must be open too
                // (it's where PillarUp builds the top of the pillar).
                if (lvl.getBlockState(stand).blocksMotion()) continue;
                if (lvl.getBlockState(stand.above()).blocksMotion()) continue;
                if (lvl.getBlockState(stand.below()).blocksMotion()) continue;
                // The offset column must be clear from our feet up to the stand so
                // the pillar can rise through it (A* re-validates; this prunes the
                // obvious misses so we don't hand A* an unreachable floating goal).
                boolean columnClear = true;
                for (int y = footY; y < stand.getY(); y++) {
                    if (lvl.getBlockState(new BlockPos(stand.getX(), y, stand.getZ())).blocksMotion()) {
                        columnClear = false; break;
                    }
                }
                if (columnClear) return stand;
            }
        }

        // REACH-ACROSS (over-water / over-gap logs): nothing adjacent, below, on
        // top, or a pillar-up column exists — the classic swamp oak whose trunk
        // rises straight out of water, with the nearest solid footing a couple of
        // blocks away ACROSS the water. The bot must NOT stand in the water (that
        // is the drown risk this whole path exists to avoid) and cannot seed a
        // pillar from water. But vanilla block reach is 4.5: if a DRY standable
        // cell sits within reach with clear line of sight to the log, stand there
        // and mine across the gap. The break aims with `a.aimAtBlock(currentTarget)`
        // in BREAKING — a snap onto the cell itself, not a step off a face — so
        // `currentFace` is cosmetic here and the non-unit offset mines fine.
        return findReachStand(lvl, block);
    }

    /**
     * Eye-to-block-CENTRE budget for a reach-across stand — not the vanilla 4.5.
     *
     * <p>Vanilla measures to the block's nearest SURFACE:
     * {@code canInteractWithBlock} tests {@code new AABB(pos).distanceToSqr(eye)} against
     * {@code blockInteractionRange() + padding}. This scan measures to {@code pos + 0.5}
     * (see {@code findReachStand}), which is up to ~0.87 further for the same block, so
     * 4.4-to-centre is roughly 3.9-to-face: about 0.6 TIGHTER than vanilla, not "a hair
     * inside" it. Kept deliberately conservative — a stand that only just reaches works
     * until the body's own bob moves the eye — but do not raise it toward 4.5 believing
     * that merely restores parity; the two numbers measure different distances.
     */
    private static final double MAX_REACH = 4.4;
    /** Horizontal disk radius scanned for a dry reach-across stand. */
    private static final int REACH_SCAN_H = 4;

    /**
     * Find a DRY, solid, standable cell within vanilla block reach of {@code block}
     * with a clear collider line of sight to the block centre — used to mine an
     * over-water/over-gap log without entering the water. Returns the nearest such
     * stand, or null if none (then the target is genuinely unreachable).
     */
    private BlockPos findReachStand(Level lvl, BlockPos block) {
        double tx = block.getX() + 0.5, ty = block.getY() + 0.5, tz = block.getZ() + 0.5;
        BlockPos bestStand = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dx = -REACH_SCAN_H; dx <= REACH_SCAN_H; dx++) {
            for (int dz = -REACH_SCAN_H; dz <= REACH_SCAN_H; dz++) {
                // dy reaches -5 so a bot can stand directly under an overhead block
                // and mine straight UP (eye→centre of a block 4–5 up is within
                // MAX_REACH — see its javadoc; that is a centre distance, not the
                // vanilla 4.5 face distance): the canopy log / low-ceiling case.
                for (int dy = -5; dy <= 2; dy++) {
                    // Same-column candidates are valid ONLY below the target (stand
                    // under it, mine up). A same-column stand at/above the target is
                    // meaningless here — the dedicated above/side cases cover those.
                    if (dx == 0 && dz == 0 && dy >= 0) continue;
                    BlockPos cand = block.offset(dx, dy, dz);
                    // Dry footing only: solid (non-water) support, no water at foot.
                    if (lvl.getBlockState(cand.below()).getFluidState().is(Fluids.WATER)) continue;
                    if (lvl.getBlockState(cand).getFluidState().is(Fluids.WATER)) continue;
                    if (!canStandHere(lvl, cand)) continue;
                    Vec3 eye = standingEye(cand);
                    double ex = eye.x, ey = eye.y, ez = eye.z;
                    double d2 = (ex - tx) * (ex - tx) + (ey - ty) * (ey - ty) + (ez - tz) * (ez - tz);
                    if (d2 > MAX_REACH * MAX_REACH || d2 >= bestD2) continue;
                    if (!reachLineOfSight(lvl, ex, ey, ez, tx, ty, tz, block)) continue;
                    bestD2 = d2;
                    bestStand = cand;
                }
            }
        }
        return bestStand;
    }

    /** Collider raycast from the eye to the block centre lands on the target block. */
    private boolean reachLineOfSight(Level lvl, double ex, double ey, double ez,
                                     double tx, double ty, double tz, BlockPos target) {
        BlockHitResult hit = lvl.clip(new ClipContext(
                new Vec3(ex, ey, ez), new Vec3(tx, ty, tz),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    /** The shared stand test ({@link net.magicterra.worlddriver.bot.util.BotUtil#canStandHereStatic})
     *  plus one clause only mining needs. Written as "the shared answer AND …" on purpose: a digger's
     *  extra refusal must be visibly extra, or it reads as a second opinion about what standing is
     *  and the three processes drift apart. */
    private boolean canStandHere(Level lvl, BlockPos foot) {
        if (!canStandHereStatic(lvl, foot)) return false;
        // Never stand where lava touches the foot or head cell — a freshly-dug
        // pocket can flow into an adjacent cell and roast us. Reject the whole
        // 1-block shell around both body cells.
        return !lavaTouching(lvl, foot) && !lavaTouching(lvl, foot.offset(0, 1, 0));
    }

    private static boolean isLava(Level lvl, BlockPos p) {
        return lvl.getBlockState(p).getFluidState().is(FluidTags.LAVA);   // tag: source AND flowing
    }

    /** True if {@code pos} itself or any of its 6 face-neighbours holds lava.
     *  Lava is present in the world model even when hidden behind a solid block
     *  face, so this catches a pocket about to be opened by a dig. */
    private static boolean lavaTouching(Level lvl, BlockPos pos) {
        if (isLava(lvl, pos)) return true;
        for (Direction d : Direction.values()) if (isLava(lvl, pos.relative(d))) return true;
        return false;
    }

    private Direction faceFromStandToBlock(BlockPos stand, BlockPos block) {
        int dx = block.getX() - stand.getX();
        int dy = block.getY() - stand.getY();
        int dz = block.getZ() - stand.getZ();
        if (dx == 1) return Direction.WEST;
        if (dx == -1) return Direction.EAST;
        if (dz == 1) return Direction.NORTH;
        if (dz == -1) return Direction.SOUTH;
        if (dy == -1) return Direction.UP;
        if (dy == 1) return Direction.DOWN;
        return Direction.UP;
    }

    private String currentBlockId(Level lvl) {
        if (lvl == null || currentTarget == null) return "";
        return BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(currentTarget).getBlock()).toString();
    }

    /**
     * Read-only post-mortem of the APPROACH walk, for a scene to embed in {@code ctx.fail}.
     *
     * <p>{@code st.mine.lastError} is this process's generic abort text and says the same thing
     * ("no reachable target") for every way the approach can end — the pre-filter never handing over
     * a goal, the walk failing, the walk arriving somewhere that is not the stand. The walker has
     * carried the readings that separate those for a while ({@link Walker#lastEndReason},
     * {@link Walker#lastGoalReached}, {@link Walker#lastFinalDist}, {@link Walker#goalSnapped()},
     * {@link Walker#progressProbe()}, {@link Walker#planProbe()}) and their javadoc says they exist
     * to be embedded in a failure — but they are package-private-adjacent state on a private field,
     * so no scene could reach them and {@code wd.buriedOre} has been failing on the bare abort text.
     *
     * <p>Deliberately one STRING and not the walker itself: a scene must not be able to steer the
     * process's walker, and the log stream drops lines under end-of-suite load, so the value of
     * these readings is that they land in {@code results.jsonl} beside the verdict.
     */
    public String approachProbe() {
        return "phase=" + phase + " broken=" + broken + "/" + desiredQty
                + " target=" + currentTarget + " stand=" + currentStand
                + " endReason=" + walker.lastEndReason
                + " goalReached=" + walker.lastGoalReached
                + " finalDist=" + String.format(java.util.Locale.ROOT, "%.3f", walker.lastFinalDist)
                + " goalSnapped=" + walker.goalSnapped()
                + " walkerErr=" + walker.lastError
                + " | " + walker.progressProbe()
                + " | " + walker.planProbe();
    }

    /**
     * {@link #approachProbe}'s world-side companion: the same plan, but every node read against the
     * blocks that are actually there — see {@link Walker#planCellAudit} for what the three cells per
     * node separate (plan wants the feet in stone / node is legal but unreachable / node has no
     * floor).
     *
     * <p>A separate call rather than a suffix on {@code approachProbe} because it needs a level and
     * that probe deliberately takes none — its callers include paths with no level in hand. Same
     * one-string, no-walker-handle rule as its sibling: a scene must be able to READ this walker's
     * plan without being able to steer it.
     */
    public String approachPlanAudit(BlockGetter lvl) {
        return walker.planCellAudit(lvl);
    }

    /** A reachable mining target: the block + adjacent stand position + face direction.
     *  {@code clearing} marks an intermediate occluding-leaf break that opens reach/LOS
     *  to a real target — it does NOT count toward the requested quota. */
    private record Target(BlockPos block, BlockPos stand, Direction face, boolean clearing) {}
}
