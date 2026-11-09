package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.aimAt;
import static net.magicterra.worlddriver.game.body.BotUtil.blockPosOf;
import static net.magicterra.worlddriver.game.body.BotUtil.canStandHereStatic;
import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.LogWaiver;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.observe.BlockMatch;
import net.magicterra.worlddriver.game.observe.CellText;
import net.magicterra.worlddriver.game.observe.NearestFirstScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Mines blocks of the target ids within a radius, breaking them and collecting the drops. */
public final class MineProcess implements BotProcess, Navigating {
    private static final int GOING_STALL_TICKS = 100; // ~5 s of zero net approach

    /**
     * BREAKING ticks a dig may be refused for the crosshair being off the target before the miner stops waiting for its
     * aim to land. An aim at a visible point lands on the next tick at the latest; ten is for a body still settling at
     * its stand.
     */
    private static final int CROSSHAIR_WAIT_TICKS = 10;

    private static final double GOING_PROGRESS_EPS = 0.5; // blocks closer = real progress

    private static final float MINE_DAMAGE_ABORT = 8f; // net HP lost from peak → abort

    private static final float MINE_HP_CRITICAL = 4f; // absolute floor backstop

    private static final int COLLECT_MAX_REPATHS = 3;

    /**
     * How long "arrived" may coexist with "the item is still on the ground" before the drop is written off. A handful
     * of ticks, because vanilla's magnet fires on the very next tick when the bot really is on top of the item —
     * anything longer is the bot being somewhere it cannot reach from.
     */
    private static final int COLLECT_STUCK_TICKS = 10;

    private static final int MAX_COLLECT_TICKS = 240; // ~12 s @ 20 tps — long enough to walk to all 8 break spots

    private static final int COLLECT_SCAN_RADIUS = 8; // matches vanilla item lifetime drift

    /**
     * How long COLLECT will stand still for a drop that is already at its feet but is still counting down the 10-tick
     * pickup delay every freshly-broken block gives its item. Twice the delay, so the wait is over long before the cap
     * — the cap only bounds the pathological case where the delay never expires because nothing is ticking the entity.
     */
    private static final int PICKUP_DELAY_WAIT_TICKS = 20;

    /**
     * Blocks. Wide enough to cover vanilla's pickup box (bounding box inflated 1.0/0.5/1.0), narrow enough that a
     * delayed drop we would have to WALK to is not mistaken for one that will fall into our hands — that one is
     * findCollectGoal's job once it becomes pickable.
     */
    private static final double PICKUP_WAIT_RADIUS = 2.0;

    /**
     * Vanilla's item pickup box is the player's bounding box inflated by 1.0 sideways and 0.5 up and down
     * ({@code Player.aiStep}), tested against the item's own 0.25 box.
     */
    private static final double PICKUP_INFLATE_XZ = 1.0;

    private static final double PICKUP_INFLATE_Y = 0.5;

    /** Half the player's width: how far a body's centre stays from the walls of a one-wide cell. */
    private static final double BODY_HALF_WIDTH = 0.3;

    /**
     * Largest per-axis offset between the body's centre and the drop for the pickup box to touch it: 0.3 + 1.0 + 0.125
     * = 1.425, less a tenth for the body not being exactly where it was steered.
     */
    private static final double PICKUP_REACH_XZ = BODY_HALF_WIDTH + PICKUP_INFLATE_XZ + 0.125 - 0.1;

    /** How many of the nearest targets the walk ranking compares; the scan stops once it has them. */
    private static final int WALK_RANKED_CANDIDATES = 6;

    /** How many of the nearest targets the vein route plans over. */
    private static final int ROUTE_CANDIDATES = 12;

    /** Largest vein the route counts; a bigger one already holds more than a mine order usually wants. */
    private static final int VEIN_MAX_BLOCKS = 32;

    /** Horizontal reach of the walk ranking around the bot, in blocks. */
    private static final int WALK_RANK_RADIUS = 20;

    /** Cells the walk ranking settles at most before it gives up on the stands it has not reached. */
    private static final int WALK_RANK_MAX_CELLS = 4000;

    /** An item entity's box: 0.25 wide and 0.25 tall. */
    private static final double ITEM_HALF_WIDTH = 0.125;

    private static final double ITEM_HEIGHT = 0.25;

    /**
     * How much of the pickup box's sideways reach is held back for where a drop comes to rest. A drop spawns up to a
     * quarter block off its cell's centre, so 0.25 would cover every drop; 0.15 still admits the face ore a block in
     * front of a bot standing against the face (1.19 blocks to the cell's centre), at the price of a rare drop landing
     * just outside the box, which the next drop pickup within reach of it or the final sweep then fetches.
     */
    private static final double DROP_SCATTER = 0.15;

    /** How far from a just-broken block its drop is looked for. A drop pops out at most a cell from where it broke. */
    private static final double OWN_DROP_RADIUS = 2.5;

    /**
     * Ticks to wait for a just-broken block's drop to appear: the client sees the item a tick or two after the break.
     */
    private static final int OWN_DROP_APPEAR_TICKS = 6;

    /** Budget for fetching one block's drop before going on; a drop left over falls to the sweep after the quota. */
    private static final int OWN_DROP_MAX_TICKS = 100;

    // gap#67-⑤: real safety cap on cells visited per scanForTarget call. Applied
    // to NearestFirstScan's nearest-first order (see below), so a cutoff drops
    // the FARTHEST cells, never an entire dy layer — unlike the old dy-outer
    // loop, radius alone no longer determines which height band goes blind.
    private static final int SCAN_BUDGET = 50_000;

    /**
     * Per-axis radius around an unreachable target searched for an occluding leaf we can stand-and-break. Small so we
     * chip the canopy around the log instead of stripping a forest; one leaf per SEARCH, re-scanning after each break.
     */
    private static final int LEAF_CLEAR_RADIUS = 3;

    /**
     * How many blocks above the bot's own feet a pillar-up stand may sit. The bot climbs a trunk one log at a time
     * (each break re-SEARCHes from the new, higher pillar top), so this only bounds the FIRST reach — keeping A* from
     * committing to one giant floating goal up a whole 6+ tall canopy in a single edge.
     */
    private static final int MAX_PILLAR_RISE = 4;

    /**
     * Eye-to-block-CENTRE budget for a reach-across stand — not the vanilla 4.5.
     *
     * <p>Vanilla measures to the block's nearest SURFACE: {@code canInteractWithBlock} tests {@code new
     * AABB(pos).distanceToSqr(eye)} against {@code blockInteractionRange() + padding}. This scan measures to {@code pos
     * + 0.5} (see {@code findReachStand}), which is up to ~0.87 further for the same block, so 4.4-to-centre is roughly
     * 3.9-to-face: about 0.6 TIGHTER than vanilla, not "a hair inside" it. Kept deliberately conservative — a stand
     * that only just reaches works until the player's own bob moves the eye — but do not raise it toward 4.5 believing
     * that merely restores parity; the two numbers measure different distances.
     */
    private static final double MAX_REACH = 4.4;

    /** Horizontal disk radius scanned for a dry reach-across stand. */
    private static final int REACH_SCAN_H = 4;

    private BlockInteraction blocks;

    private final Set<String> targetIds;

    // Per-id matchers: each entry is an exact id or a '#tag' selector
    // (e.g. #minecraft:logs matches any log species). A block is a target
    // when ANY matcher accepts it.
    private final List<Predicate<BlockState>> targetMatchers;

    private final int desiredQty;

    private final int searchRadius;

    // Approach stands live within the ≤64-block scan; embedded/unreachable
    // candidates must price out fast (the 100t no-approach blacklist is the
    // real gate). Full 50k/2s deep searches here only chopped the client
    // (~25 fps for seconds after every block break) and delayed the
    // blacklist — see NavigatorSettings.withSearchBudget.
    private final Navigator navigator =
            new Navigator(NavigatorSettings.named("mine").withSearchBudget(8_000, 400));

    private final Set<BlockPos> blacklist = new HashSet<>();

    private int broken;

    // When the last SEARCH returned no usable target ONLY because every in-range
    // candidate needs a tool the bot doesn't have (breaks but drops nothing), this
    // holds the actionable abort signal ("blocked: <block> needs <tool> …"); null
    // when the miss was for the ordinary reasons (no blocks / no stand). Recomputed
    // from scratch on every scanForTarget. See canHarvest / the tool gate below.
    private String noTargetReason;

    // Position where this mine command began. Targets beyond
    // BehaviorConfig.mineMaxDriftFromStart of this anchor are rejected so a single
    // mine command can't chain hops across the world (e.g. swim an ocean toward
    // scattered red_sand). Set lazily on first tick (attach has no player).
    private BlockPos startAnchor;

    /**
     * <b>Write only through {@link #aimAt}.</b> Five sites used to assign this field directly, and the trunk-tax waiver
     * below has to know about every one of them — a flag maintained beside five assignments is a flag with five
     * authors.
     */
    private BlockPos currentTarget;

    private BlockPos currentStand;

    // True when currentTarget is a leaf being cleared to open access to a real
    // target (not itself a quota block) — see findClearingTarget.
    private boolean currentTargetClearing;

    // death#26-followup (07-20 live): per-target no-progress watchdog. The navigator
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

    // death#26-followup: cumulative-damage abort. The per-target watchdog bounds ONE
    // unreachable log, but a hillside/cliff forest offers MANY high logs (dy 6-8); the
    // bot cycles through them, each dry-steep-climb attempt costing fall damage, and
    // dies CUMULATIVELY (live repro: HP 20→9→dead over 135 s, only 2 logs harvested).
    // A bot must not DIE trying to mine: if HP falls MINE_DAMAGE_ABORT below its peak
    // this command (or reaches MINE_HP_CRITICAL), abort ALIVE — the strategy layer
    // then relocates to flatter terrain. Reachable mining takes no fall damage, so a
    // steady-HP flat mine never trips this (full-HP scenes stay at peak == current).
    private float minePeakHp; // max HP seen this command (0 → set on first tick)

    private int breakingTicks;

    /** Consecutive BREAKING ticks whose drive was refused because the crosshair was not on the target. */
    private int offCrosshairTicks;

    private String breakStartId = "";

    private Phase phase = Phase.SEARCH;

    // Last few break positions — used as goal hints during COLLECT so the
    // bot walks back through where it just mined and lets vanilla's pickup
    // magnet vacuum the drops. Capped to avoid backtracking forever.
    private final Deque<BlockPos> recentBreaks = new ArrayDeque<>();

    private int collectTicks;

    private final Navigator collectNavigator = // drops are even nearer
            new Navigator(NavigatorSettings.named("mine.collect").withSearchBudget(8_000, 400));

    private BlockPos currentCollectGoal;

    /** The drop the collect goal was chosen for, or null when the goal is a remembered break cell. */
    private ItemEntity collectItem;

    /**
     * Set while COLLECT is fetching the drop of the block just broken ({@link BehaviorConfig#mineCollectAsYouGo}), to
     * that block's cell; null in the sweep after the quota.
     */
    private BlockPos ownDropOrigin;

    private int ownDropTicks;

    /** Whether the drop being fetched was seen, so its disappearance means it was picked up. */
    private boolean ownDropSeen;

    /** Game time the current target was chosen, and the time its dig began: for the per-block timing row. */
    private long targetChosenAt;

    private long breakBeganAt;

    /** Whether the next block was already looked for while this drop waits out its pickup delay. */
    private boolean nextTargetPeeked;

    /** The goal the collect navigator walks to under {@link BehaviorConfig#mineCollectPickupReach}. */
    private Goal collectGoal;

    /**
     * Drop cells the collect navigator reported FAILED on. findCollectGoal returns the NEAREST drop, so without a skip
     * list a single unreachable one is handed back every tick and shadows every other drop until the collect cap
     * expires.
     */
    private final Set<BlockPos> unreachableDrops = new HashSet<>();

    /**
     * What the collect navigator last said. Read only by {@link #finish} — a terminal verdict that cannot name the
     * sweep's own behaviour makes the reader guess between three bugs.
     */
    private Locomotor.Status lastCollectStep;

    /** Re-plans spent on the current collect goal after an ARRIVED that was not at it. */
    private int collectRepaths;

    /** Retirement causes, split. See the COLLECT stuck-handler for why the split matters. */
    private int retiredUnpathable;

    private int retiredArrivedShort;

    /**
     * Mining targets blacklisted this sweep — see {@link #retireTarget}. A different ledger from the two above, which
     * count DROPS the collect navigator gave up on.
     */
    private int retiredTargets;

    private int pickupWaitTicks;

    /** Ticks spent standing on an ARRIVED collect goal whose item is still there. */
    private int collectStuckTicks;

    private String failure;

    /**
     * The approach navigator, for its {@link net.magicterra.worlddriver.bot.control.NavigatorInspector#tallies()} (the
     * collect navigator's are not summed).
     */
    @Override
    public Navigator navigator() {
        return navigator;
    }

    /**
     * The one place {@link #currentTarget} changes, so the {@link LogWaiver} is DERIVED from the goal rather than
     * latched beside it: aim at a non-log (or at nothing) and it lapses on the same line.
     *
     * <p>That also gets the sub-goals right for free. The leaf-clearing and overburden sites aim at a leaf or a
     * covering block, not at a log, so the waiver correctly lifts for those stretches and comes back when the log does.
     */
    private void aimAt(BlockPos target, Level lvl) {
        currentTarget = target;
        if (target != null && lvl != null && lvl.getBlockState(target).is(BlockTags.LOGS)) {
            LogWaiver.claim(this);
        } else {
            LogWaiver.release(this);
        }
    }

    /**
     * Release the waiver on the paths that never reach {@link #aimAt}.
     *
     * <p>{@code BotProcess.onCancelled} is a no-op by default, while {@code cancelAllProcesses("player-death")} and
     * {@code ChainProcessLifecycle.drop} both end a process WITHOUT running any of the five assignment sites. Dying up
     * a tree would otherwise leave the waiver set for the rest of the session — every subsequent journey silently
     * priced at 1.0, which is the regression the tax was added to fix.
     *
     * <p><b>Where this call actually happens</b>, because grepping {@code BotBrain} for {@code onCancelled} returns
     * nothing and reads as "it never gets called": the funnel is {@code BotBrain.onDeath / cancel →
     * UserTaskChain.cancel}, and the call is on {@code UserTaskChain:47}. Starting a new order funnels there too —
     * {@code setProcess} cancels the outgoing process with {@code "superseded"} before attaching the new one. The
     * server rig's two entry points ({@code PlayerBot.runProcess} / {@code mine}) did NOT, and were given the same door
     * for this waiver's sake.
     */
    @Override
    public void onCancelled(String reason) {
        LogWaiver.release(this);
    }

    private enum Phase {
        SEARCH,
        GOING,
        BREAKING,
        COLLECT
    }

    /** Creates a mine of {@code qty} blocks whose ids are in {@code ids}, searching within {@code radius}. */
    public MineProcess(List<String> ids, int qty, int radius) {
        this.targetIds = new HashSet<>(ids);
        this.targetMatchers = ids.stream().map(BlockMatch::of).toList();
        this.desiredQty = qty;
        this.searchRadius = radius;
    }

    /** True when {@code bs} matches any requested id / tag selector. */
    private boolean isTarget(BlockState bs) {
        for (Predicate<BlockState> m : targetMatchers) {
            if (m.test(bs)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String kind() {
        return "mine";
    }

    /** Blocks destroyed so far: what the action's result reports, not what reached the inventory. */
    public int broken() {
        return broken;
    }

    @Override
    public void attach(BotState st) {
        st.mine.beginRun(String.join(",", targetIds) + "×" + desiredQty, null);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        if (a.entity() == null) {
            st.mine.lastError = "player vanished";
            finish(st, null, null, "player vanished");
            return true;
        }
        // The drops go into a player's inventory: a controlled entity that is not a player has no
        // hands for this.
        Player p = a.asPlayer();
        // This tick's hands, bound here, the one place they can be absent.
        final Equipment equipment = a.equipment().orElse(null);
        blocks = a.blockInteraction().orElse(null);
        if (equipment == null || blocks == null || p == null) {
            st.mine.lastError = Readiness.Reason.NO_HANDS;
            finish(st, null, null, Readiness.Reason.NO_HANDS);
            return true;
        }
        Level lvl = p.level();
        // Quota reached → switch to COLLECT instead of declaring done. The
        // old behaviour left the player wherever the last break completed,
        // so items that fell 2-3 blocks away (typical for trees: trunk
        // breaks at head height, items at foot height) just despawned.
        if (broken >= desiredQty && phase != Phase.COLLECT) {
            blocks.breakHold(false);
            phase = Phase.COLLECT;
            collectTicks = 0;
        }
        // Anchor the command to where it began (first tick with a live player).
        if (startAnchor == null) {
            startAnchor = p.blockPosition();
        }

        // Lava-contact safety net: the instant we're touching lava, stop mining.
        // The lava-near target/stand rejection below should keep us out of it, but
        // a dig can reveal a pocket that floods our own cell — bail before the
        // ~4-dmg/tick spiral instead of walking deeper toward the next target.
        // (This is exactly what killed a naked run: a stone dig opened a hidden
        // pocket and the next-target approach stepped into it.)
        if (p.isInLava()) {
            blocks.breakHold(false);
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
        if (hpNow > minePeakHp) {
            minePeakHp = hpNow;
        }
        boolean quotaSweep = phase == Phase.COLLECT && ownDropOrigin == null;
        if (!quotaSweep && (minePeakHp - hpNow >= MINE_DAMAGE_ABORT || hpNow <= MINE_HP_CRITICAL)) {
            blocks.breakHold(false);
            a.commandForward(0);
            a.commandJump(false);
            p.setSprinting(false);
            st.mine.lastError = "aborted: taking damage with no safely-reachable target (hp "
                    + String.format("%.0f", hpNow) + ", peak " + String.format("%.0f", minePeakHp)
                    + ", broken=" + brokenOfDesired() + ")";
            finish(st, p, lvl, "aborted: taking damage");
            return true;
        }

        // Choosing a block, starting the walk to it and starting the dig take no game time, so with
        // mineNoIdleTicks a phase that hands over to one of those runs it in the same tick. One tick per hand-over
        // was two to three idle ticks a block, a second and a half over the ore block. A tick that ran BREAKING
        // stops there: a second dig drive in one tick would count vanilla's post-break cooldown down twice.
        for (int hops = 0; ; hops++) {
            Phase before = phase;
            if (tickPhase(a, w, st, p, lvl, equipment)) {
                return true;
            }
            boolean handedOver = phase != before && before != Phase.BREAKING && phase != Phase.COLLECT;
            if (!BehaviorConfig.mineNoIdleTicks || !handedOver || hops >= 2) {
                return false;
            }
        }
    }

    /** One tick of the current phase; true when the command ended. */
    private boolean tickPhase(Body a, WorldView w, BotState st, Player p, Level lvl, Equipment equipment) {
        switch (phase) {
            case SEARCH -> {
                return tickSearch(st, p, lvl);
            }
            case GOING -> tickGoing(a, w, st, p, lvl, equipment);
            case BREAKING -> tickBreaking(a, st, p, lvl);
            case COLLECT -> {
                return tickCollect(a, w, st, p, lvl);
            }
            default -> throw new IllegalStateException("mine phase " + phase + " has no tick");
        }
        return false;
    }

    /** SEARCH: pick the next target and start walking to its stand; true when the command ended. */
    private boolean tickSearch(BotState st, Player p, Level lvl) {
        Target t = pickTarget(lvl, p);
        if (t == null) {
            // Prefer the tool-block signal when every candidate was skipped only
            // because the bot lacks the harvesting tool — that's the actionable
            // hand-off ("go craft/relocate"), not the ambiguous "no reachable target".
            st.mine.lastError =
                    noTargetReason == null ? "no reachable target (broken=" + brokenOfDesired() + ")" : noTargetReason;
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
            finish(st, p, lvl, noTargetReason == null ? "no reachable target" : "no harvestable target");
            return true;
        }
        adoptTarget(st, t, lvl);
        return false;
    }

    /** Make {@code t} the current target and start walking to its stand. */
    private void adoptTarget(BotState st, Target t, Level lvl) {
        targetChosenAt = lvl.getGameTime();
        aimAt(t.block, lvl);
        currentStand = t.stand;
        currentTargetClearing = t.clearing();
        st.mine.target = currentTarget;
        navigator.start(new Goal.Block(t.stand));
        phase = Phase.GOING;
        goingBestDist = Double.MAX_VALUE; // arm the no-progress watchdog
        goingStallTicks = 0;
    }

    /** GOING: walk to the stand, then hand over to BREAKING; watches for a walk that makes no net progress. */
    private void tickGoing(Body a, WorldView w, BotState st, Player p, Level lvl, Equipment equipment) {
        // Make sure attack isn't lingering from the previous block.
        blocks.breakHold(false);
        // Already standing on the target's stand cell? Then there is nothing
        // to walk — go straight to breaking. This is the straight-up "mine
        // the overhead block from directly below" case (stand == our own
        // foot cell): handing the Navigator a zero-length path makes it report
        // FAILED, which would blacklist a perfectly good target. (The normal
        // side/reach-across stand is a DIFFERENT cell, so this never short-
        // circuits a real walk.)
        if (p.blockPosition().equals(currentStand)) {
            beginBreaking(a, equipment, lvl);
            return;
        }
        Locomotor.Status s = navigator.tick(a, w);
        st.mine.pathLen = navigator.pathLen();
        st.mine.pathStep = navigator.pathStep();
        if (s == Locomotor.Status.FAILED) {
            onApproachFailed(st, p, lvl);
        } else if (s.ended()) {
            beginBreaking(a, equipment, lvl);
        } else if (currentStand != null) {
            watchApproachProgress(p);
            if (BehaviorConfig.mineHoldDigOnTheWay) {
                blocks.burnDigCooldown(currentTarget);
            }
        }
    }

    /**
     * Arm the break of {@link #currentTarget}. {@link #breakStartId} is the block id observed at the moment we arrived
     * — used to detect both successful breaks (id changes) and resyncs (id flickers to air then back, indicating a
     * rejected predicted destroy).
     */
    private void beginBreaking(Body a, Equipment equipment, Level lvl) {
        equipment.selectTool(currentTarget);
        a.aimAtBlock(currentTarget);
        breakingTicks = 0;
        offCrosshairTicks = 0;
        breakStartId = currentBlockId(lvl);
        breakBeganAt = lvl == null ? 0 : lvl.getGameTime();
        phase = Phase.BREAKING;
    }

    /** The navigator reported FAILED on the way to the stand: clear an occluding leaf, or retire the target. */
    private void onApproachFailed(BotState st, Player p, Level lvl) {
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
                if (GameConfig.navigatorDebug) {
                    LOG.info(
                            "[mine] stand unreachable for {} -> clear leaf {} (stand {})",
                            currentTarget,
                            clear.block(),
                            clear.stand());
                }
                aimAt(clear.block(), lvl);
                currentStand = clear.stand();
                currentTargetClearing = true;
                st.mine.target = currentTarget;
                navigator.start(new Goal.Block(clear.stand()));
                phase = Phase.GOING;
                goingBestDist = Double.MAX_VALUE; // re-arm for the new (clearing) stand
                goingStallTicks = 0;
                return;
            }
        }
        retireTarget("the navigator reported FAILED and there are no obstructing leaves to clear");
    }

    /** Still walking — no-progress watchdog (see goingBestDist javadoc). */
    private void watchApproachProgress(Player p) {
        // The navigator repaths forever against an unclimbable dry face rather
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
            retireTarget(String.format(
                    java.util.Locale.ROOT,
                    "made no progress toward stand %2$s within %1$dt (closest %3$.1f blocks) — unreachable",
                    GOING_STALL_TICKS,
                    CellText.of(currentStand),
                    goingBestDist));
        }
    }

    /** BREAKING: hold the dig on the target (peeling overburden first) until it changes, then COLLECT or re-SEARCH. */
    private void tickBreaking(Body a, BotState st, Player p, Level lvl) {
        // Release walking keys, hold the break action via the bot interface:
        //  - CLIENT: the dig latch PLUS a direct continueDestroy on the same block.
        //    The latch drives nothing (see BlockInteraction#breakHold); the direct call is what
        //    advances the break, and it makes vanilla's own attack pass stand aside for
        //    the tick. It is still PROGRESSIVE, so the id check below stays honest.
        //  - SERVER: blocks.breakHold(true) = level.destroyBlock(aimTarget) (instant), and
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
        // also what keeps every drop at the bottom of a hole the bot can enter.
        //
        // A client body peels the same way: its canBreak is whether any point of the target shows to the eye, since
        // its dig lands only under the crosshair (GameConfig.digOnlyUnderCrosshair).
        BlockPos overburden = currentTargetClearing ? null : firstBreakableToward(blocks, lvl, p, currentTarget);
        if (overburden != null) {
            aimAt(overburden, lvl);
            currentTargetClearing = true;
            breakStartId = currentBlockId(lvl);
            breakingTicks = 0;
            offCrosshairTicks = 0;
        } else if (!blocks.canBreak(currentTarget) && isExposed(lvl, currentTarget)) {
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
            retireTarget(
                    "exposed but still cannot be broken ⇒ out of reach, and standing still cannot close the distance");
            return;
        }
        a.aimAtBlock(currentTarget);
        blocks.breakHold(true);
        blocks.continueDestroy(currentTarget);
        if (crosshairWaitRanOut(a, p, lvl)) {
            return;
        }

        breakingTicks++;
        String now = currentBlockId(lvl);
        // Robust completion: id changed away from the original block AND
        // is no longer the same kind. Avoids the 1-tick flicker false-positive.
        boolean gone = !now.equals(breakStartId) && !isTarget(lvl.getBlockState(currentTarget));
        if (gone) {
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
                    while (recentBreaks.size() > 8) {
                        recentBreaks.removeFirst();
                    }
                }
            }
            blocks.breakHold(false);
            BlockPos brokenCell = currentTarget;
            boolean counted = !currentTargetClearing;
            // One row a block, so a run's time can be split into choosing, walking and digging.
            long brokeAt = lvl.getGameTime();
            LOG.info(
                    "[mine] broke {} {} at t={}: chosen {}t ago, dug {}t, stand {}",
                    counted ? "target" : "clearing",
                    CellText.of(brokenCell),
                    brokeAt,
                    brokeAt - targetChosenAt,
                    brokeAt - breakBeganAt,
                    CellText.of(currentStand));
            aimAt(null, lvl);
            if (counted && BehaviorConfig.mineCollectAsYouGo) {
                // Fetch this block's drop now, while the bot stands at the hole it fell in. Left for the sweep
                // after the quota, the drops of a vein lay one and two cells deep in holes the bot had walked away
                // from, and the sweep ran out of time with half of them on the ground.
                if (!startNextBlockInPlace(st, p, lvl, brokenCell)) {
                    beginOwnDropPickup(brokenCell);
                }
            } else if (broken >= desiredQty) {
                phase = Phase.COLLECT;
                collectTicks = 0;
            } else {
                phase = Phase.SEARCH;
            }
        } else if (breakingTicks > ControlConfig.breakTimeoutTicks) {
            retireTarget("still unbroken after " + breakingTicks + "t of mining (limit "
                    + ControlConfig.breakTimeoutTicks + "t)");
        }
    }

    /**
     * Count a drive refused for the crosshair being off the target; once {@link #CROSSHAIR_WAIT_TICKS} have run out,
     * hand over to {@link #breakWhatTheCrosshairIsOn} and answer true, since this tick's target has changed.
     */
    private boolean crosshairWaitRanOut(Body a, Player p, Level lvl) {
        if (!blocks.digRefused()) {
            offCrosshairTicks = 0;
            return false;
        }
        if (++offCrosshairTicks < CROSSHAIR_WAIT_TICKS) {
            return false;
        }
        breakWhatTheCrosshairIsOn(a, p, lvl);
        return true;
    }

    /**
     * The aim has not brought the crosshair onto the target for {@link #CROSSHAIR_WAIT_TICKS} ticks: break the block
     * the crosshair is on instead, as a person does with what is in the way, when that block lies between the eye and
     * the target and is one this miner may break. A target block in the way is taken as a target; anything else is a
     * clearing that does not count. Otherwise the target is retired, so a dig never waits on a crosshair it cannot get.
     */
    private void breakWhatTheCrosshairIsOn(Body a, Player p, Level lvl) {
        BlockPos seen = a.lookingAtBlock();
        String why = obstructionRefusal(p, lvl, seen);
        if (why != null) {
            retireTarget("the crosshair stayed off it for " + offCrosshairTicks + "t, on "
                    + (seen == null ? "no block" : CellText.of(seen)) + ", which " + why);
            offCrosshairTicks = 0;
            return;
        }
        BlockState bs = lvl.getBlockState(seen);
        LOG.info(
                "[mine] crosshair stayed on {} for {}t, not on {}; breaking it first",
                CellText.of(seen),
                offCrosshairTicks,
                CellText.of(currentTarget));
        aimAt(seen, lvl);
        currentTargetClearing = !isTarget(bs);
        breakStartId = currentBlockId(lvl);
        breakingTicks = 0;
        offCrosshairTicks = 0;
    }

    /**
     * Null when {@code seen} may be broken on the way to {@link #currentTarget}: a block nearer the eye than the
     * target, inside the drift cap, not retired, dry, breakable and not walling off lava. Otherwise why not.
     */
    private String obstructionRefusal(Player p, Level lvl, BlockPos seen) {
        if (seen == null || seen.equals(currentTarget)) {
            return "is no other block";
        }
        Vec3 eye = p.getEyePosition();
        BlockState bs = lvl.getBlockState(seen);
        if (eye.distanceToSqr(Vec3.atCenterOf(seen)) >= eye.distanceToSqr(Vec3.atCenterOf(currentTarget))) {
            return "is not on the way";
        }
        if (beyondDriftCap(seen) || blacklist.contains(seen)) {
            return "is outside the area";
        }
        boolean unbreakable = bs.isAir() || !bs.getFluidState().isEmpty() || bs.getDestroySpeed(lvl, seen) < 0;
        if (unbreakable || lavaTouching(lvl, seen)) {
            return "cannot be broken safely";
        }
        return null;
    }

    /** COLLECT: sweep the drops (and remembered break cells); true when the command ended. */
    private boolean tickCollect(Body a, WorldView w, BotState st, Player p, Level lvl) {
        // Walk toward the nearest dropped ItemEntity (vanilla's
        // pickup magnet does the rest at ~1 block). Falls back to
        // walking through remembered break positions when no items
        // are visible — handles the chunk-not-loaded case where
        // ClientLevel hasn't received the SpawnEntity packet yet.
        blocks.breakHold(false);
        if (ownDropOrigin != null) {
            return tickOwnDropPickup(a, w, st, p, lvl);
        }
        collectTicks++;
        BlockPos goal = findCollectGoal(lvl, p);
        if (goal != null && collectItem != null && BehaviorConfig.mineCollectPickupReach) {
            if (collectTicks > MAX_COLLECT_TICKS) {
                finish(st, p, lvl, "collect timed out after " + MAX_COLLECT_TICKS + " ticks");
                return true;
            }
            pickupWaitTicks = 0;
            walkToPickup(a, w, st, p, lvl, goal);
            return false;
        }
        // Nothing to walk to — but "nothing to walk to" is not the same as "nothing
        // left". A block broken from arm's length drops its item AT OUR FEET with a
        // 10-tick pickup delay, and findCollectGoal skips delayed items (walking to
        // one is pointless) while the recentBreaks fallback pops the break cell we are
        // already standing on. Both correctly return "no goal", one tick after the
        // break — and completing there abandoned the harvest and reported success.
        // That is how a mine of qty 1 banked nothing: 24 ticks, ore gone, drop on the
        // floor, lastError null. Stand still instead and let vanilla's magnet fire.
        if (goal == null && awaitingPickupDelay(lvl, p) && ++pickupWaitTicks <= PICKUP_DELAY_WAIT_TICKS) {
            return false;
        }
        if (goal == null || collectTicks > MAX_COLLECT_TICKS) {
            finish(
                    st,
                    p,
                    lvl,
                    collectTicks > MAX_COLLECT_TICKS
                            ? "collect timed out after " + MAX_COLLECT_TICKS + " ticks"
                            : "collect swept everything it could reach");
            return true;
        }
        pickupWaitTicks = 0;
        if (!goal.equals(currentCollectGoal)) {
            if (!goal.equals(st.mine.target)) {
                collectRepaths = 0; // a different drop, fresh allowance
            }
            currentCollectGoal = goal;
            collectStuckTicks = 0;
            // Stand ON the drop's own cell, which is what a player does. An adjacency goal
            // was tried and is subtly wrong: "adjacent" is measured to the CELL while the
            // magnet reaches from the player to the ITEM, and vanilla's reach is only about
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
            // eye-to-item distance on the tick the navigator reports its terminal, against
            // the ~1.4 magnet radius. Until someone takes it, fix one, look at the other.
            collectNavigator.start(new Goal.Block(goal));
        }
        Locomotor.Status step = collectNavigator.tick(a, w);
        lastCollectStep = step;
        // Two ways this drop is not going to happen, and both used to be silently ignored:
        // the navigator says it cannot path there, or it says it has ARRIVED and the item is
        // still lying there anyway (arrived-but-out-of-reach — a shaft bottom the bot
        // cannot enter). Either way, stop pouring the budget into it. Without a skip list
        // findCollectGoal hands back the same nearest drop every tick, so ONE dead drop
        // used to shadow every reachable one behind it: three ores mined, three drops on
        // the ground, an empty bag and no error.
        boolean cannotPath = step == Locomotor.Status.FAILED;
        // ARRIVED does not mean "at the goal". The navigator reports it when the path it
        // computed runs out, and when A* cannot reach the goal it returns a best-effort
        // partial path — so a sweep can report success standing four blocks from the drop
        // (measured: `retired 2 drop(s): 0 unpathable + 2 arrived-but-short`, distances 4.1
        // and 6.4). Retiring on that throws away drops the bot never went to.
        //
        // So an ARRIVED that is not actually AT the cell re-plans, a bounded number of
        // times: the world changes while a mine runs — blocks fall, the bot's own shaft
        // opens routes — and the second search often reaches what the first could not.
        // Only after those are spent is the drop genuinely out of reach.
        boolean arrivedShort = step.ended() && !p.blockPosition().equals(goal) && collectRepaths < COLLECT_MAX_REPATHS;
        if (arrivedShort) {
            collectRepaths++;
            currentCollectGoal = null; // forces a fresh start, hence a fresh search
            collectStuckTicks = 0;
            st.mine.target = goal;
            return false;
        }
        boolean stuck = cannotPath || (step.ended() && ++collectStuckTicks > COLLECT_STUCK_TICKS);
        if (stuck) {
            retireDrop(goal, cannotPath);
        }
        st.mine.target = goal;
        st.mine.pathLen = collectNavigator.pathLen();
        st.mine.pathStep = collectNavigator.pathStep();
        return false;
    }

    /** Give up on the drop at {@code goal}, counting which of the two failure modes retired it. */
    private void retireDrop(BlockPos goal, boolean cannotPath) {
        collectRepaths = 0;
        // Which of the two retired it, counted rather than guessed. "The navigator refuses
        // to path there" and "the navigator believes it has arrived and the item is still
        // on the floor" are opposite bugs — one is the pathfinder's move set, the other
        // is the goal being satisfied short of vanilla's pickup reach — and both end as
        // an unretrieved drop. wd.serverWalkIntoPit proves a two-deep pit IS pathable,
        // so a FAILED here would mean the collect navigator differs from a plain one.
        if (cannotPath) {
            retiredUnpathable++;
        } else {
            retiredArrivedShort++;
        }
        unreachableDrops.add(goal);
        currentCollectGoal = null;
        collectStuckTicks = 0;
    }

    /** Start fetching the drop of the block just broken at {@code cell}, before the next block. */
    private void beginOwnDropPickup(BlockPos cell) {
        phase = Phase.COLLECT;
        ownDropOrigin = cell;
        ownDropTicks = 0;
        ownDropSeen = false;
        nextTargetPeeked = false;
        collectItem = null;
        currentCollectGoal = null;
        collectStuckTicks = 0;
        collectRepaths = 0;
    }

    /**
     * Stop fetching the last block's drop and go on: to the next block, or to the sweep once the quota is met. A drop
     * that was picked up takes its cell off the sweep's trail, which would otherwise walk the bot back to it.
     */
    private void endOwnDropPickup(boolean pickedUp) {
        LOG.info(
                "[mine] drop of {} {} after {}t",
                CellText.of(ownDropOrigin),
                pickedUp ? "picked up" : ownDropSeen ? "left on the ground" : "never seen",
                ownDropTicks);
        if (pickedUp) {
            recentBreaks.remove(ownDropOrigin);
        }
        ownDropOrigin = null;
        collectItem = null;
        currentCollectGoal = null;
        collectStuckTicks = 0;
        collectRepaths = 0;
        if (broken >= desiredQty) {
            phase = Phase.COLLECT;
            collectTicks = 0;
            pickupWaitTicks = 0;
        } else {
            phase = Phase.SEARCH;
        }
    }

    /** COLLECT while fetching the drop of the block just broken ({@link BehaviorConfig#mineCollectAsYouGo}). */
    private boolean tickOwnDropPickup(Body a, WorldView w, BotState st, Player p, Level lvl) {
        ownDropTicks++;
        BlockPos goal = findOwnDrop(lvl);
        if (goal == null) {
            // Not seen yet: the client hears of the item a tick or two after the break. Seen and gone: picked up.
            if (!ownDropSeen && ownDropTicks <= OWN_DROP_APPEAR_TICKS) {
                a.commandMove(0f, 0f);
                if (BehaviorConfig.mineHoldDigOnTheWay) {
                    burnCooldownNear(lvl, ownDropOrigin);
                }
                return false;
            }
            endOwnDropPickup(ownDropSeen);
            return false;
        }
        ownDropSeen = true;
        if (ownDropTicks > OWN_DROP_MAX_TICKS) {
            endOwnDropPickup(false);
            return false;
        }
        if (BehaviorConfig.mineCollectPickupReach) {
            walkToPickup(a, w, st, p, lvl, goal);
            return false;
        }
        if (!goal.equals(currentCollectGoal)) {
            currentCollectGoal = goal;
            collectNavigator.start(new Goal.Block(goal));
        }
        Locomotor.Status step = collectNavigator.tick(a, w);
        lastCollectStep = step;
        if (step == Locomotor.Status.FAILED || (step.ended() && ++collectStuckTicks > PICKUP_DELAY_WAIT_TICKS)) {
            retireDrop(goal, step == Locomotor.Status.FAILED);
            endOwnDropPickup(false);
        }
        return false;
    }

    /**
     * The nearest live drop within {@link #OWN_DROP_RADIUS} of the block just broken, still in its pickup delay or not,
     * as its cell; null when there is none. Sets {@link #collectItem}.
     */
    private BlockPos findOwnDrop(Level lvl) {
        Vec3 origin = Vec3.atCenterOf(ownDropOrigin);
        ItemEntity best = null;
        double bestD2 = Double.MAX_VALUE;
        AABB near = new AABB(origin, origin).inflate(OWN_DROP_RADIUS);
        for (ItemEntity it : lvl.getEntitiesOfClass(ItemEntity.class, near, ItemEntity::isAlive)) {
            if (unreachableDrops.contains(blockPosOf(it))) {
                continue;
            }
            double d2 = it.distanceToSqr(origin);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = it;
            }
        }
        collectItem = best;
        return best == null ? null : blockPosOf(best);
    }

    /**
     * One collect tick toward {@link #collectItem}, whose cell is {@code goal}, judged by vanilla's pickup box
     * ({@link BehaviorConfig#mineCollectPickupReach}).
     */
    private void walkToPickup(Body a, WorldView w, BotState st, Player p, Level lvl, BlockPos goal) {
        ItemEntity item = collectItem;
        st.mine.target = goal;
        if (BehaviorConfig.mineHoldDigOnTheWay) {
            burnCooldownNear(lvl, goal);
        }
        if (inPickupReach(p, item)) {
            // In reach: vanilla takes it on the tick its pickup delay ends, so stop here rather than walk on into
            // the hole. While the delay runs, a next block that can be broken from where the bot stands is
            // started now; the bot stays put for that break, which outlasts the delay.
            a.commandMove(0f, 0f);
            a.commandJump(false);
            if (ownDropOrigin != null && item.hasPickUpDelay() && startNextTargetInPlace(st, p, lvl)) {
                return;
            }
            if (++collectStuckTicks > PICKUP_DELAY_WAIT_TICKS + COLLECT_STUCK_TICKS) {
                retireDrop(goal, false);
            }
            return;
        }
        if (!goal.equals(currentCollectGoal) || collectGoal == null) {
            currentCollectGoal = goal;
            collectStuckTicks = 0;
            collectGoal = pickupGoal(lvl, item);
            collectNavigator.start(collectGoal);
        }
        Locomotor.Status step = collectNavigator.tick(a, w);
        lastCollectStep = step;
        st.mine.pathLen = collectNavigator.pathLen();
        st.mine.pathStep = collectNavigator.pathStep();
        if (step == Locomotor.Status.FAILED) {
            retireDrop(goal, true);
        } else if (step.ended()) {
            BlockPos foot = p.blockPosition();
            if (collectGoal.reached(foot)) {
                // On a pickup cell but outside the box: the body stopped at the cell's far side. The point of the
                // cell nearest the drop is in reach by construction, and it keeps the body inside the cell, so the
                // step cannot bump a wall.
                a.commandToward(nearestInCell(foot.getX(), item.getX()), nearestInCell(foot.getZ(), item.getZ()), 0.5f);
                if (++collectStuckTicks > COLLECT_STUCK_TICKS) {
                    retireDrop(goal, false);
                }
            } else if (collectRepaths < COLLECT_MAX_REPATHS) {
                collectRepaths++;
                currentCollectGoal = null;
            } else {
                retireDrop(goal, false);
            }
        }
    }

    /**
     * While a drop in reach waits out its pickup delay, start the next block if its stand is the cell the bot is in.
     * Scans once per drop. True when it started one; the drop's cell then leaves the sweep's trail, since the bot stays
     * in reach for the whole break.
     */
    private boolean startNextTargetInPlace(BotState st, Player p, Level lvl) {
        if (nextTargetPeeked || broken >= desiredQty) {
            return false;
        }
        nextTargetPeeked = true;
        Target t = pickTarget(lvl, p);
        boolean here = t != null && t.stand().equals(p.blockPosition());
        LOG.info(
                "[mine] drop of {} in reach after {}t; next {} from {} ({})",
                CellText.of(ownDropOrigin),
                ownDropTicks,
                t == null ? "none" : CellText.of(t.block()),
                t == null ? "-" : CellText.of(t.stand()),
                here ? "started in place" : "waits for the pickup");
        if (!here) {
            return false;
        }
        recentBreaks.remove(ownDropOrigin);
        ownDropOrigin = null;
        collectItem = null;
        currentCollectGoal = null;
        adoptTarget(st, t, lvl);
        return true;
    }

    /**
     * The coordinate nearest {@code target} that a body's centre can take in the cell starting at {@code cell} with its
     * box a twentieth of a block clear of the cell's sides, so a step there does not press into a wall.
     */
    private static double nearestInCell(int cell, double target) {
        double inset = BODY_HALF_WIDTH + 0.05;
        return Math.clamp(target, cell + inset, cell + 1 - inset);
    }

    /**
     * Every feet cell from which vanilla's pickup box reaches {@code item}: a body inside the cell can get within
     * {@link #PICKUP_REACH_XZ} of it on each horizontal axis, the box (feet - 0.5 to feet + 2.3) overlaps it in height,
     * and the cell has a floor now. Measured from the cell's centre instead, a drop lying a little past the middle of
     * the next cell left out the cell the bot stood in, and the planner dug one cell deeper than a person would. The
     * floor clause keeps the planner from digging down to stand under a drop; the cells themselves may be solid, which
     * is how a drop one cell into a one-high hole is reached: the planner breaks the block in the hole's mouth and
     * stands there, as a person does.
     */
    private static Goal pickupGoal(Level lvl, ItemEntity item) {
        List<Goal> cells = new ArrayList<>();
        BlockPos at = blockPosOf(item);
        int lowY = (int) Math.floor(item.getY() - 1.8 - PICKUP_INFLATE_Y) + 1;
        int highY = (int) Math.floor(item.getY() + item.getBbHeight() + PICKUP_INFLATE_Y);
        for (int y = lowY; y <= highY; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos f = new BlockPos(at.getX() + dx, y, at.getZ() + dz);
                    if (Math.abs(nearestInCell(f.getX(), item.getX()) - item.getX()) > PICKUP_REACH_XZ
                            || Math.abs(nearestInCell(f.getZ(), item.getZ()) - item.getZ()) > PICKUP_REACH_XZ
                            || !lvl.getBlockState(f.below()).blocksMotion()) {
                        continue;
                    }
                    cells.add(new Goal.Block(f));
                }
            }
        }
        return cells.isEmpty() ? new Goal.Block(at) : new Goal.Composite(cells.toArray(Goal[]::new));
    }

    /**
     * Count the post-break dig cooldown down on {@code cell} or the first of its neighbours that is a block in reach.
     */
    private void burnCooldownNear(Level lvl, BlockPos cell) {
        if (blocks.burnDigCooldown(cell)) {
            return;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!lvl.getBlockState(n).isAir() && blocks.burnDigCooldown(n)) {
                return;
            }
        }
    }

    /**
     * Right after a counted break, start the next block on this tick when the drop will land inside the pickup box and
     * that block's stand is the cell the bot is in ({@link BehaviorConfig#mineNextBlockInPlace}). The dig outlasts the
     * drop's pickup delay, so vanilla takes it while the bot works. True when it started one.
     */
    private boolean startNextBlockInPlace(BotState st, Player p, Level lvl, BlockPos brokenCell) {
        if (!BehaviorConfig.mineNextBlockInPlace || broken >= desiredQty || !dropLandsInReach(lvl, p, brokenCell)) {
            return false;
        }
        Target t = pickTarget(lvl, p);
        if (t == null || !t.stand().equals(p.blockPosition())) {
            return false;
        }
        LOG.info(
                "[mine] drop of {} lands in reach; next {} started in place",
                CellText.of(brokenCell),
                CellText.of(t.block()));
        recentBreaks.remove(brokenCell);
        adoptTarget(st, t, lvl);
        return true;
    }

    /**
     * Whether the drop of the block broken at {@code cell} will come to rest inside the pickup box. The item spawns at
     * the cell's centre, up to a quarter block off on each axis and with a small kick, and falls to the first floor
     * below; the box is shrunk by {@link #DROP_SCATTER} to allow for that.
     */
    private static boolean dropLandsInReach(Level lvl, Player p, BlockPos cell) {
        BlockPos floor = cell;
        for (int i = 0; i < 4 && !lvl.getBlockState(floor.below()).blocksMotion(); i++) {
            floor = floor.below();
        }
        Vec3 rest = new Vec3(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
        AABB reach = p.getBoundingBox()
                .inflate(PICKUP_INFLATE_XZ - DROP_SCATTER, PICKUP_INFLATE_Y, PICKUP_INFLATE_XZ - DROP_SCATTER);
        AABB item = new AABB(rest, rest)
                .inflate(ITEM_HALF_WIDTH, 0, ITEM_HALF_WIDTH)
                .expandTowards(0, ITEM_HEIGHT, 0);
        return reach.intersects(item);
    }

    /**
     * The next target, with {@link BehaviorConfig#minePeelInReach}: a target the eye reaches but cannot see, because
     * one block cuts the line, becomes that block to break from here, as a clearing target that does not count.
     */
    private Target pickTarget(Level lvl, Player p) {
        Target t = scanForTarget(lvl, p);
        if (t == null || t.clearing() || !BehaviorConfig.minePeelInReach) {
            return t;
        }
        BlockPos foot = blockPosOf(p);
        if (t.stand().equals(foot)) {
            return t;
        }
        BlockPos lip = soleBlockInSight(lvl, p, t.block());
        if (lip == null) {
            return t;
        }
        LOG.info(
                "[mine] {} is in reach behind {}; breaking that from {} instead of walking to {}",
                CellText.of(t.block()),
                CellText.of(lip),
                CellText.of(foot),
                CellText.of(t.stand()));
        return new Target(lip, foot, faceFromStandToBlock(foot, lip), true);
    }

    /**
     * The one block between the eye and {@code target}'s centre, or null when the target is out of reach, in plain
     * sight, or behind more than one block. The block must be at or above the feet (never the floor), dry, breakable,
     * not itself a target and not walling off lava.
     */
    private BlockPos soleBlockInSight(Level lvl, Player p, BlockPos target) {
        Vec3 eye = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        double span = eye.distanceTo(centre);
        if (span > MAX_REACH || span <= 0.001) {
            return null;
        }
        Vec3 stride = centre.subtract(eye).scale(0.1 / span);
        BlockPos sole = null;
        BlockPos last = null;
        for (int i = 1; i * 0.1 <= span; i++) {
            BlockPos cell = BlockPos.containing(eye.add(stride.scale(i)));
            if (cell.equals(last) || cell.equals(target)) {
                continue;
            }
            last = cell;
            BlockState bs = lvl.getBlockState(cell);
            if (bs.getCollisionShape(lvl, cell).isEmpty()) {
                continue;
            }
            if (sole != null && !sole.equals(cell)) {
                return null;
            }
            sole = cell;
        }
        if (sole == null
                || sole.getY() < blockPosOf(p).getY()
                || blacklist.contains(sole)
                || !isLip(lvl, sole, target)) {
            return null;
        }
        BlockState bs = lvl.getBlockState(sole);
        boolean breakable = bs.getFluidState().isEmpty() && bs.getDestroySpeed(lvl, sole) >= 0;
        return breakable && !isTarget(bs) && !lavaTouching(lvl, sole) ? sole : null;
    }

    /**
     * Whether {@code block} is the lip of an opening onto {@code target}: it borders an open cell that borders the
     * target. That is the block a person breaks to reach into a hole, and the one whose removal lets the bot mine the
     * target from where it stands. Without the test a target three blocks along a face, seen past the face's own
     * corner, had a block of the face broken and was then walked to anyway.
     */
    private static boolean isLip(Level lvl, BlockPos block, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos open = block.relative(d);
            if (open.distManhattan(target) == 1
                    && lvl.getBlockState(open).getCollisionShape(lvl, open).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Vanilla's pickup test: the player's box inflated by 1, 0.5, 1 against the item's box. */
    private static boolean inPickupReach(Player p, ItemEntity item) {
        return p.getBoundingBox()
                .inflate(PICKUP_INFLATE_XZ, PICKUP_INFLATE_Y, PICKUP_INFLATE_XZ)
                .intersects(item.getBoundingBox());
    }

    /**
     * Pick a COLLECT destination: prefer a visible ItemEntity (vanilla magnet will grab it once we're adjacent),
     * otherwise the oldest remembered break position. We pop a break position as soon as we're within 1.5 blocks of it
     * so the deque drains and we walk through every spot instead of camping the nearest one. Returns null when there's
     * nothing left to chase — that's COLLECT's natural completion.
     */
    private BlockPos findCollectGoal(Level lvl, Player p) {
        if (lvl != null) {
            AABB box = p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS);
            var items = lvl.getEntitiesOfClass(ItemEntity.class, box, it -> it.isAlive() && !it.hasPickUpDelay());
            BlockPos best = null;
            ItemEntity bestItem = null;
            double bestD2 = Double.MAX_VALUE;
            for (var it : items) {
                BlockPos cell = blockPosOf(it);
                if (unreachableDrops.contains(cell)) {
                    continue; // the navigator already said no
                }
                double d2 = it.distanceToSqr(p);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = cell;
                    bestItem = it;
                }
            }
            collectItem = bestItem;
            if (best != null) {
                return best;
            }
        }
        collectItem = null;
        // No item visible — sweep through remembered break positions in FIFO
        // (see the COLLECT case for why a null return here does not mean "done").
        // order. Pop any we've already reached so we keep moving toward the
        // next spot instead of looping.
        BlockPos bp = recentBreaks.peekFirst();
        // The skip list has to cover THIS path too. It guarded the item scan and not the
        // fallback, so once every visible drop was retired the sweep dropped through to here
        // and handed back the same unreachable break cell — the bottom of a hole it cannot
        // enter — for the rest of the budget. Measured: both drops retired as unreachable and
        // the verdict still read `sweep WALKING`, 240 ticks of walking toward a cell already
        // known to be dead. Same bug as the one fixed on the drop path, one branch over.
        while (bp != null && (unreachableDrops.contains(bp) || withinPopRadius(p, bp))) {
            recentBreaks.removeFirst();
            bp = recentBreaks.peekFirst();
        }
        return bp;
    }

    /** True when {@code p} is within 1.5 blocks of the break cell {@code bp}, so the cell counts as visited. */
    private static boolean withinPopRadius(Player p, BlockPos bp) {
        double dx = p.getX() - (bp.getX() + 0.5);
        double dy = p.getY() - bp.getY();
        double dz = p.getZ() - (bp.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz < 1.5 * 1.5;
    }

    /**
     * End the command with an honest verdict, not just an absence of error.
     *
     * <p>{@code BunkerProcess} and {@code IntentProcess} already stamp {@code goalReached} / {@code endReason} at every
     * terminal exit — "gap#68-R2", because a run that ends with {@code active:false} and no {@code lastError} is
     * indistinguishable from a run that succeeded. Mine was the outlier, and it is the verb where that hurts most: it
     * can meet its quota, sweep, end clean, and have banked nothing, because breaking a block and acquiring it are two
     * different events and only the first one was ever reported. Diagnosing that took three full playthrough runs to
     * tell apart from "the ore was never reached".
     *
     * <p>So the verdict carries the count that was missing — how many drops were still lying in the collect radius when
     * the sweep ended. It is reported through {@code endReason} rather than {@code lastError} because leftovers are not
     * necessarily a failure (an unreachable drop behind a wall is a fact about the world), and {@code goalReached}
     * stays the plain quota question.
     */
    private void finish(BotState st, Player p, Level lvl, String reason) {
        // Release the trunk-tax waiver here too, not only on the assignment sites: a run that ends
        // mid-BREAKING (budget spent, quota met on the last swing) leaves currentTarget pointing at
        // a log, and the waiver would outlive the order that earned it.
        aimAt(null, lvl);
        int left = p == null || lvl == null ? 0 : looseDrops(lvl, p);
        st.mine.goalReached = broken >= desiredQty;
        st.mine.endReason = reason + " (broke " + brokenOfDesired()
                + (left > 0 ? ", left " + left + " drop(s) on the ground" : "")
                // What the sweep was DOING when it ran out of budget. "Left 2 drops" says the
                // harvest was incomplete; it does not say whether the navigator was refusing to
                // path, insisting it had arrived, or still searching — three different bugs that
                // all end with items on the floor, and the count alone sent two investigations
                // to the wrong file.
                + (lastCollectStep != null
                        ? ", sweep " + lastCollectStep + " toward " + CellText.of(currentCollectGoal)
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
        failure = st.mine.goalReached ? null : st.mine.lastError != null ? st.mine.lastError : st.mine.endReason;
    }

    @Override
    public String failure() {
        return failure;
    }

    /**
     * Retire {@code currentTarget} and go back to SEARCH — the ONE door out of a target this sweep cannot finish, and
     * the only place that says which door it was.
     *
     * <p>Four call sites used to inline these lines. THREE OF THEM SAID NOTHING AT ALL and the fourth spoke only behind
     * {@code navigatorDebug}, which no journey run sets — so a blacklist, which is a PERMANENT retirement that
     * immediately re-enters SEARCH, was invisible. That is the loop Q7 measured from the other end: 174 searches with
     * an identical start and goal, {@code owner=mine}, and nothing in the log naming the cell that had just been
     * retired or why. Unconditional is right on volume too — each line costs one target forever, so the count is
     * bounded by the candidates, not by the ticks.
     *
     * <p>{@code breakHold(false)} runs on every door, including the two that could not have been holding one. Releasing
     * a hold nobody took is a no-op; forgetting it on the door that DID take one leaves the avatar swinging at a cell
     * it has stopped tracking.
     */
    private void retireTarget(String why) {
        retiredTargets++;
        LOG.info("[mine] blacklist {} ({}) — retired target #{}, back to SEARCH", currentTarget, why, retiredTargets);
        blacklist.add(currentTarget);
        blocks.breakHold(false);
        aimAt(null, null); // no Level here, and none is needed: a null target is never a log
        phase = Phase.SEARCH;
    }

    /**
     * True when at least one of the six faces is open — i.e. some ray could reach this block. The same test
     * {@code ServerPlayerBody} gates breaking on, asked here so the miner can tell "too far" (permanent from this
     * stand) from "walled in" (the peel will fix it).
     */
    private static boolean isExposed(Level lvl, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!lvl.getBlockState(n).isSolidRender(lvl, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block to swing at on the way to {@code target}, or null when {@code target} itself is already reachable (the
     * ordinary case, and the only one before the reach gate existed).
     *
     * <p>Walks the segment from the eye to the target's centre and returns the FIRST solid block along it that the
     * avatar can actually break. That is the overburden: the thing standing between a stand and the ore. Returning null
     * when nothing on the line qualifies is deliberate — a target that is neither reachable nor approachable through
     * anything breakable belongs to the no-progress watchdog and the blacklist, not to an infinite peel.
     *
     * <p>Sampled rather than voxel-traversed at 0.2 blocks — a fifth of a block cannot skip a full cube, and the
     * segment here is bounded by the player's own interaction range, so this is a couple of dozen samples and not a
     * raycast worth optimising.
     */
    private static BlockPos firstBreakableToward(BlockInteraction blocks, Level lvl, Player p, BlockPos target) {
        if (blocks.canBreak(target)) {
            return null;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        double span = eye.distanceTo(centre);
        if (span <= 0.001) {
            return null;
        }
        Vec3 stride = centre.subtract(eye).scale(0.2 / span);
        BlockPos last = null;
        for (int i = 1; i * 0.2 <= span; i++) {
            Vec3 at = eye.add(stride.scale(i));
            BlockPos cell = BlockPos.containing(at);
            if (cell.equals(last)) {
                continue;
            }
            last = cell;
            if (cell.equals(target)) {
                break;
            }
            if (lvl.getBlockState(cell).isAir()) {
                continue;
            }
            if (blocks.canBreak(cell)) {
                return cell;
            }
        }
        return null;
    }

    /** Item entities still lying inside the collect radius — what the sweep did not get. */
    private static int looseDrops(Level lvl, Player p) {
        return lvl.getEntitiesOfClass(
                        ItemEntity.class, p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS), ItemEntity::isAlive)
                .size();
    }

    /**
     * True when a drop is close enough that standing still will collect it, but is not pickable yet — the
     * {@code setDefaultPickUpDelay()} every block drop is born with. Deliberately narrow: only drops within
     * {@link #PICKUP_WAIT_RADIUS} count, so this waits for the item at our feet and never for one across the arena.
     */
    private static boolean awaitingPickupDelay(Level lvl, Player p) {
        if (lvl == null) {
            return false;
        }
        AABB box = p.getBoundingBox().inflate(PICKUP_WAIT_RADIUS);
        return !lvl.getEntitiesOfClass(ItemEntity.class, box, it -> it.isAlive() && it.hasPickUpDelay())
                .isEmpty();
    }

    /** Scan candidates within radius, filter by target id + blacklist + stand reachability, pick nearest. */
    private Target scanForTarget(Level lvl, Player p) {
        if (lvl == null) {
            return null;
        }
        noTargetReason = null;
        BlockPos foot = blockPosOf(p);
        int r = searchRadius;
        // Nearest real target we could SEE but not reach — the leaf-clearing
        // fallback (below) tries to open access to it when nothing else is reachable.
        BlockPos nearestUnreachable = null;
        // Nearest candidate skipped ONLY because the bot owns no tool that would
        // harvest it (breaks but drops nothing). Kept so we can emit an actionable
        // abort when nothing reachable-and-harvestable remains.
        BlockPos toolBlocked = null;
        String toolBlockedTool = null;
        int scanned = 0;
        int targetHits = 0; // DIAG: cells passing isTarget
        int targetLavaSkips = 0; // DIAG: targets skipped for lava
        // gap#67-⑤: nearest-first order (shared with GoalResolver.findNearestStandForBlock)
        // so SCAN_BUDGET drops the FARTHEST cells instead of truncating the top of the
        // vertical band — the old dy-outer loop silently never reached dy in [+4,+8] once
        // a wide horizontal radius blew the budget on the low layers (a jungle-canopy log
        // sat in plain sight and was reported unreachable).
        BlockPos[] offsets = NearestFirstScan.offsetsNearestFirst(r, BehaviorConfig.mineSearchVerticalRadius);
        int budget = Math.min(offsets.length, SCAN_BUDGET);
        // Nearest-first order makes the first usable target the nearest one
        // (see NearestFirstScan), so the rest of the scan cannot beat it.
        Target found = null;
        List<Target> nearest =
                BehaviorConfig.mineNearestByWalk || BehaviorConfig.mineRouteByVeins ? new ArrayList<>() : null;
        for (int i = 0; i < budget && found == null; i++) {
            scanned++;
            BlockPos bp = foot.offset(offsets[i]);
            if (beyondDriftCap(bp) || blacklist.contains(bp)) {
                continue;
            }
            BlockState bs = lvl.getBlockState(bp);
            if (!isTarget(bs)) {
                continue;
            }
            targetHits++;
            // Don't dig a block that walls off lava: breaking it lets the
            // pocket flood toward us. Lava is loaded in the world model even
            // when hidden behind a solid face, so a face-neighbour scan
            // catches the pocket BEFORE the dig opens it.
            if (lavaTouching(lvl, bp)) {
                targetLavaSkips++;
                continue;
            }
            // Tool gate: a block that needs a correct tool for its drop, when the
            // bot holds/owns none, BREAKS but drops NOTHING — mining it is pure
            // futility (the bare-hand stone grind that spun the campaign soft-lock:
            // block.break fires forever, inventory never fills). Skip it, but keep
            // one so the caller emits an actionable "needs <tool>" abort.
            if (!canHarvest(p, bs)) {
                if (toolBlocked == null) {
                    toolBlocked = bp;
                    toolBlockedTool = requiredToolName(bs);
                }
                continue;
            }
            // Find a standable adjacent position (incl. a pillar-up
            // stand for an otherwise-too-high log, relative to our feet).
            BlockPos stand = findStandableAdjacent(lvl, bp, foot.getY());
            if (stand == null) {
                // Real target, but no stand reaches it (the leaf-encased
                // floating-canopy oak: leaves wall it in and block the reach
                // raycast). The first one met is the nearest; clear its leaves.
                if (nearestUnreachable == null) {
                    nearestUnreachable = bp;
                }
                continue;
            }
            Target candidate = new Target(bp, stand, faceFromStandToBlock(stand, bp), false);
            if (nearest == null) {
                found = candidate;
            } else {
                nearest.add(candidate);
                if (nearest.size() >= (BehaviorConfig.mineRouteByVeins ? ROUTE_CANDIDATES : WALK_RANKED_CANDIDATES)) {
                    break;
                }
            }
        }
        found = nearest == null || nearest.isEmpty() ? found : rankByWalk(lvl, foot, nearest);
        if (found != null) {
            return found;
        }
        String diag = GameConfig.navigatorDebug
                ? String.format(
                        java.util.Locale.ROOT,
                        "scanned=%d targetHits=%d lavaSkips=%d toolBlocked=%s foot=%s r=%d vR=%d",
                        scanned,
                        targetHits,
                        targetLavaSkips,
                        toolBlocked,
                        foot,
                        r,
                        BehaviorConfig.mineSearchVerticalRadius)
                : null;
        return noDirectTarget(lvl, foot, nearestUnreachable, toolBlocked, toolBlockedTool, diag);
    }

    /**
     * The scan found no target it can walk up to: a clearing or dig target for the nearest one it could not reach, or
     * null with {@link #noTargetReason} set. {@code diag} is the scan's tally, logged when it is not null.
     */
    private Target noDirectTarget(
            Level lvl, BlockPos foot, BlockPos nearestUnreachable, BlockPos toolBlocked, String tool, String diag) {
        // Nothing directly reachable. If a real target is occluded by leaves we can
        // stand-and-break, return one as a clearing target so mining it opens access.
        if (nearestUnreachable != null) {
            Target fallback = clearingOrDigTarget(lvl, foot, nearestUnreachable);
            if (fallback != null) {
                return fallback;
            }
        }
        // No reachable-and-harvestable target and no leaf we can clear to open one.
        // If the ONLY candidates we saw were tool-blocked, surface that as the reason so
        // the bot aborts with an actionable "needs <tool>" (→ the planner crafts/relocates)
        // instead of grinding for zero yield or reporting a misleading "no reachable target".
        noTargetReason = toolBlockedReason(lvl, toolBlocked, tool);
        if (diag != null) {
            LOG.info("[mine] scan found no target ({})", diag);
        }
        return null;
    }

    /** The next target among {@code candidates}: by the vein route when that is on, else by the walk to it. */
    private Target rankByWalk(Level lvl, BlockPos foot, List<Target> candidates) {
        return BehaviorConfig.mineRouteByVeins
                ? firstOfShortestRoute(lvl, foot, candidates)
                : nearestByWalk(lvl, foot, candidates);
    }

    /**
     * The first target of the shortest walk that collects the rest of the quota
     * ({@link BehaviorConfig#mineRouteByVeins}).
     *
     * <p>Each candidate stands for its vein: the target blocks joined to it face to face, the hidden ones included,
     * since breaking the first opens the way to the rest. From each candidate a route is completed greedily, always
     * walking to the nearest vein not yet taken, until the veins taken hold the blocks still wanted; the candidate
     * whose route walks least is taken. Greedy from the bot alone took the ore nearest the first hole, then the one
     * nearest that, and ended with a walk round the whole block to a vein a person would simply have left: two of
     * twelve were spare, and which two decides the walk.
     */
    private Target firstOfShortestRoute(Level lvl, BlockPos foot, List<Target> candidates) {
        int wanted = desiredQty - broken;
        List<Set<BlockPos>> veins = new ArrayList<>();
        Set<BlockPos> stands = new HashSet<>();
        for (Target t : candidates) {
            veins.add(veinOf(lvl, t.block()));
            stands.add(t.stand());
        }
        Map<BlockPos, Map<BlockPos, Double>> walks = new HashMap<>();
        walks.put(foot, walkDistances(lvl, foot, stands));
        Target best = null;
        double bestWalk = Double.MAX_VALUE;
        for (int s = 0; s < candidates.size(); s++) {
            double walk = routeWalk(lvl, foot, s, wanted, candidates, veins, stands, walks);
            if (walk < bestWalk) {
                bestWalk = walk;
                best = candidates.get(s);
            }
        }
        return best != null ? best : candidates.get(0);
    }

    /**
     * The walk of the greedy route that starts with candidate {@code start} and takes veins until {@code wanted} blocks
     * are in them; {@link Double#MAX_VALUE} when the start's stand is not reached. Walk tables are filled into
     * {@code walks} per source stand as the routes need them.
     */
    private double routeWalk(
            Level lvl,
            BlockPos foot,
            int start,
            int wanted,
            List<Target> candidates,
            List<Set<BlockPos>> veins,
            Set<BlockPos> stands,
            Map<BlockPos, Map<BlockPos, Double>> walks) {
        double total = walks.get(foot).getOrDefault(candidates.get(start).stand(), Double.MAX_VALUE);
        if (total == Double.MAX_VALUE) {
            return total;
        }
        Set<BlockPos> taken = new HashSet<>(veins.get(start));
        BlockPos at = candidates.get(start).stand();
        while (taken.size() < wanted) {
            Map<BlockPos, Double> from = walks.computeIfAbsent(at, src -> walkDistances(lvl, src, stands));
            int next = -1;
            double nextWalk = Double.MAX_VALUE;
            for (int c = 0; c < candidates.size(); c++) {
                double w = from.getOrDefault(candidates.get(c).stand(), Double.MAX_VALUE);
                if (w < nextWalk && java.util.Collections.disjoint(taken, veins.get(c))) {
                    nextWalk = w;
                    next = c;
                }
            }
            if (next < 0) {
                break;
            }
            total += nextWalk;
            taken.addAll(veins.get(next));
            at = candidates.get(next).stand();
        }
        return total;
    }

    /** The target blocks joined to {@code block} face to face, itself included, up to {@link #VEIN_MAX_BLOCKS}. */
    private Set<BlockPos> veinOf(Level lvl, BlockPos block) {
        Set<BlockPos> vein = new HashSet<>();
        Deque<BlockPos> todo = new ArrayDeque<>();
        vein.add(block);
        todo.add(block);
        while (!todo.isEmpty() && vein.size() < VEIN_MAX_BLOCKS) {
            BlockPos at = todo.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = at.relative(d);
                if (!vein.contains(n) && !blacklist.contains(n) && isTarget(lvl.getBlockState(n))) {
                    vein.add(n);
                    todo.add(n);
                }
            }
        }
        return vein;
    }

    /**
     * Of {@code candidates}, in nearest-first order, the one whose stand is the shortest walk from {@code foot}
     * ({@link BehaviorConfig#mineNearestByWalk}). A straight line goes through the rock: from inside a hole on one face
     * of an ore block, the ore round the corner looked nearer than the one along the same face, and the run ended with
     * a walk round half the block. The walk is a Dijkstra over cells the bot can stand in, one block up or down a step,
     * diagonals only past two open sides. A candidate whose stand it does not reach keeps its place behind every one it
     * does; when it reaches none, the nearest-first order stands.
     */
    private Target nearestByWalk(Level lvl, BlockPos foot, List<Target> candidates) {
        Set<BlockPos> stands = new HashSet<>();
        for (Target t : candidates) {
            stands.add(t.stand());
        }
        Map<BlockPos, Double> dist = walkDistances(lvl, foot, stands);
        Target best = candidates.get(0);
        double bestWalk = dist.getOrDefault(best.stand(), Double.MAX_VALUE);
        for (Target t : candidates) {
            double walk = dist.getOrDefault(t.stand(), Double.MAX_VALUE);
            if (walk < bestWalk) {
                bestWalk = walk;
                best = t;
            }
        }
        return best;
    }

    /**
     * Walking distance from {@code foot} to the standable cells around it, settled until every one of {@code stands} is
     * or {@link #WALK_RANK_MAX_CELLS} cells are. A straight step costs 1, a diagonal √2, a step up or down half a block
     * more.
     */
    private Map<BlockPos, Double> walkDistances(Level lvl, BlockPos foot, Set<BlockPos> stands) {
        Map<BlockPos, Double> dist = new HashMap<>();
        PriorityQueue<Map.Entry<BlockPos, Double>> frontier = new PriorityQueue<>(Map.Entry.comparingByValue());
        dist.put(foot, 0.0);
        frontier.add(Map.entry(foot, 0.0));
        int settled = 0;
        int standsLeft = stands.size();
        while (!frontier.isEmpty() && standsLeft > 0 && settled < WALK_RANK_MAX_CELLS) {
            Map.Entry<BlockPos, Double> head = frontier.poll();
            BlockPos at = head.getKey();
            if (head.getValue() > dist.get(at)) {
                continue;
            }
            settled++;
            if (stands.contains(at)) {
                standsLeft--;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean diagonal = dx != 0 && dz != 0;
                    boolean cutsCorner =
                            diagonal && !(passable(lvl, at.offset(dx, 0, 0)) && passable(lvl, at.offset(0, 0, dz)));
                    if ((dx == 0 && dz == 0) || cutsCorner) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos next = at.offset(dx, dy, dz);
                        if (!walkStep(lvl, foot, at, next, dy)) {
                            continue;
                        }
                        double d = head.getValue() + (diagonal ? Math.sqrt(2) : 1) + (dy != 0 ? 0.5 : 0);
                        if (d < dist.getOrDefault(next, Double.MAX_VALUE)) {
                            dist.put(next, d);
                            frontier.add(Map.entry(next, d));
                        }
                    }
                }
            }
        }
        return dist;
    }

    /** Whether the walk ranking may step from {@code at} to {@code next}, {@code dy} up or down, near {@code foot}. */
    private boolean walkStep(Level lvl, BlockPos foot, BlockPos at, BlockPos next, int dy) {
        return Math.abs(next.getX() - foot.getX()) <= WALK_RANK_RADIUS
                && Math.abs(next.getZ() - foot.getZ()) <= WALK_RANK_RADIUS
                && canStandHere(lvl, next)
                && !(dy > 0 && lvl.getBlockState(at.above(2)).blocksMotion())
                && !(dy < 0 && lvl.getBlockState(next.above(2)).blocksMotion());
    }

    /** Whether the feet and head cells at {@code foot} let a body through, for the walk ranking's diagonal test. */
    private static boolean passable(Level lvl, BlockPos foot) {
        return !lvl.getBlockState(foot).blocksMotion()
                && !lvl.getBlockState(foot.above()).blocksMotion();
    }

    /** The actionable abort text for a candidate skipped only for want of {@code tool}, or null when there was none. */
    private static String toolBlockedReason(Level lvl, BlockPos toolBlocked, String tool) {
        if (toolBlocked == null) {
            return null;
        }
        return "blocked: "
                + BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(toolBlocked).getBlock())
                + " needs " + tool + " — none held or in inventory";
    }

    /**
     * True when {@code bp} is farther from where the command began than the configured drift cap, so a chain of SEARCH
     * hops can't walk the bot across the world / an ocean.
     */
    private boolean beyondDriftCap(BlockPos bp) {
        if (BehaviorConfig.mineMaxDriftFromStart <= 0 || startAnchor == null) {
            return false;
        }
        long hx = bp.getX() - startAnchor.getX();
        long hz = bp.getZ() - startAnchor.getZ();
        long cap = BehaviorConfig.mineMaxDriftFromStart;
        return hx * hx + hz * hz > cap * cap;
    }

    /** Access to an occluded real target: a leaf to clear, else (when digging is allowed) a stand to tunnel to. */
    private Target clearingOrDigTarget(Level lvl, BlockPos foot, BlockPos nearestUnreachable) {
        Target clear = findClearingTarget(lvl, foot, nearestUnreachable);
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[mine] no direct stand; nearestUnreachable={} -> clearing={}",
                    nearestUnreachable,
                    clear == null ? "null" : clear.block());
        }
        if (clear != null) {
            return clear;
        }
        // gap#60 — buried target (an ore fully encased in rock has NO standable
        // adjacent cell, so the geometric stand test rejects it wholesale and the
        // whole command aborts "no reachable target" while a pickaxe sits in hand).
        // The stand test only knows the CURRENT world; reachability through
        // diggable cover is the pathfinder's call — every other verb already digs
        // via the Navigator's priced break-route A*. So hand the Navigator the
        // face-adjacent cell nearest the bot as the goal and let it carve the
        // tunnel. A genuinely unreachable ore (out of budget, lava-walled) makes
        // the Navigator FAIL, which blacklists the ore — still a clean abort.
        if (!WorldConfig.allowBreak) {
            return null;
        }
        Target dig = findDigStand(lvl, foot, nearestUnreachable);
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[mine] no clearing leaf; buried target {} -> digStand={}",
                    nearestUnreachable,
                    dig == null ? "null" : dig.stand());
        }
        return dig;
    }

    /**
     * True when breaking {@code bs} would actually yield its drop with a tool the bot can bring to hand — i.e. the
     * block needs no correct tool (dirt/gravel/sand/logs mine fine bare-handed), OR some item across the full main
     * inventory (0-35, the reach of {@code selectBestToolFor}) is the correct tool for it. A block that
     * {@code requiresCorrectToolForDrops} with no such tool owned breaks but drops NOTHING, so mining it is futile —
     * the gate keeps it out of the scan.
     */
    private static boolean canHarvest(Player p, BlockState bs) {
        if (!bs.requiresCorrectToolForDrops()) {
            return true;
        }
        var items = p.getInventory().items;
        for (ItemStack stk : items) {
            if (!stk.isEmpty() && stk.isCorrectToolForDrops(bs)) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable name of the tool class a block wants, for the abort signal. */
    private static String requiredToolName(BlockState bs) {
        if (bs.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return "a pickaxe";
        }
        if (bs.is(BlockTags.MINEABLE_WITH_AXE)) {
            return "an axe";
        }
        if (bs.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return "a shovel";
        }
        if (bs.is(BlockTags.MINEABLE_WITH_HOE)) {
            return "a hoe";
        }
        return "the correct tool";
    }

    /**
     * The leaf-encased floating-canopy case (badlands azalea oak): the target log has no reachable stand because leaves
     * wall it in AND occlude the reach line of sight (leaves have full collision, so the reach raycast stops on them).
     * Find the reachable leaf NEAREST the log (Chebyshev rings outward) and return it as a non-counting "clearing"
     * target. Mining it — then re-SEARCHing — progressively opens the column until the log itself becomes
     * reach-mineable. A naked, block-less bot breaks leaves by hand, so this needs no tools or pillar blocks. Returns
     * null if no occluding leaf is reachable either (genuinely unreachable).
     */
    private Target findClearingTarget(Level lvl, BlockPos foot, BlockPos log) {
        for (int rad = 1; rad <= LEAF_CLEAR_RADIUS; rad++) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dy = -rad; dy <= rad; dy++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        // Only the cells on THIS Chebyshev ring (nearer rings already done).
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != rad) {
                            continue;
                        }
                        Target t = clearingTargetAt(lvl, foot, log.offset(dx, dy, dz));
                        if (t != null) {
                            return t;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The clearing target for the leaf at {@code lp}, or null when it is blacklisted, not a leaf, or has no stand. */
    private Target clearingTargetAt(Level lvl, BlockPos foot, BlockPos lp) {
        if (blacklist.contains(lp) || !isLeaf(lvl.getBlockState(lp)) || lavaTouching(lvl, lp)) {
            return null;
        }
        BlockPos stand = findStandableAdjacent(lvl, lp, foot.getY());
        if (GameConfig.navigatorDebug) {
            LOG.info("[mine] clearing-candidate leaf {} -> stand {}", lp, stand);
        }
        if (stand == null) {
            return null;
        }
        return new Target(lp, stand, faceFromStandToBlock(stand, lp), true);
    }

    private static boolean isLeaf(BlockState bs) {
        return bs.is(BlockTags.LEAVES);
    }

    /**
     * The walk goal for a fully buried target: the face-adjacent cell nearest the bot (same-Y cardinals only — the
     * natural tunnel head; above/below stands of an encased ore invite digging past it). The cell is usually SOLID
     * right now; that's the point — the Navigator's break-route A* prices and digs the approach, and BREAKING then
     * mines the target from a true face-adjacent cell. Fluid-flooded cells are skipped (never send the tunnel head into
     * a pocket).
     */
    private Target findDigStand(Level lvl, BlockPos foot, BlockPos block) {
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dxz) {
            BlockPos cand = block.offset(d[0], 0, d[1]);
            if (!lvl.getFluidState(cand).isEmpty()) {
                continue;
            }
            double d2 = cand.distSqr(foot);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = cand;
            }
        }
        if (best == null) {
            return null;
        }
        return new Target(block, best, faceFromStandToBlock(best, block), false);
    }

    private BlockPos findStandableAdjacent(Level lvl, BlockPos block, int footY) {
        // OVERHEAD log (well above our feet): a ground-level reach stand directly
        // below — that the bot can actually walk to and mine straight up from — is
        // what we want, NOT a stand sitting on TOP of an adjacent block at the log's
        // own level (the dy=+1 side case below). Those upper stands are standable
        // but unreachable for a bot on the ground, and returning one as a direct
        // target makes the Navigator fail and blacklist a perfectly mineable log. So
        // for an overhead block, try the reach-from-below stand FIRST; only if it is
        // out of reach / LOS-blocked do we fall through to the side/pillar cases
        // (the genuine climb-the-trunk situations). LOS-blocked here is the
        // leaf-encased canopy log — the GOING leaf-clearing fallback opens it up.
        if (block.getY() >= footY + 2) {
            BlockPos reach = findReachStand(lvl, block);
            if (reach != null) {
                return reach;
            }
            // Reach-from-below is blocked. If a LEAF occludes the straight-up column
            // below the log, return null so the caller's leaf-clearing fallback opens
            // it — rather than falling through to a side/on-top stand a ground bot
            // can't path to (which makes the Navigator drag the bot around, then
            // blacklist the log). Once the occluding leaf is cleared, reach-from-below
            // returns our own cell and the log is mined straight up. A NON-leaf
            // occlusion (too tall / solid above) still falls through to pillar-up.
            for (int y = footY + 1; y < block.getY(); y++) {
                if (isLeaf(lvl.getBlockState(new BlockPos(block.getX(), y, block.getZ())))) {
                    return null;
                }
            }
        }
        // Try same-Y 4 cardinals, then Y-1, then Y+1.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] dyTry = {0, -1, 1};
        for (int dy : dyTry) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHere(lvl, cand)) {
                    return cand;
                }
            }
        }
        // Stand DIRECTLY BELOW and mine UPWARD — the tree-trunk / overhead-log
        // case the side checks miss: a vertical trunk's neighbours at its own
        // level are all trunk/leaves, so no side-stand exists, yet the bot at the
        // base can just look up and break it. Foot 2 below puts the block at
        // head+1 (reach ~2) with the head cell (block.below(1)) clear for line of
        // sight. canStandHere already requires that head cell passable.
        BlockPos below2 = block.offset(0, -2, 0);
        if (canStandHere(lvl, below2)) {
            return below2;
        }

        // Standing on top of the block (mining downward).
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHere(lvl, above)) {
            return above;
        }

        // PILLAR-UP (to raise the bot): the log is too HIGH for any ground stand — an upper
        // trunk/canopy log directly overhead is the tree itself (can't pillar
        // into it). If a CLEAR vertical column sits BESIDE the log, return an
        // elevated side-stand level with it. The pathfinder's PillarUp chain
        // climbs that offset column (placing a held block under the feet each
        // jump) and the bot then breaks the log from the side. We only offer it
        // when the log is above our feet but within MAX_PILLAR_RISE, so the bot
        // climbs a trunk one log per SEARCH rather than one huge floating goal.
        int rise = block.getY() - footY;
        // A pillar is built of placed blocks. With placing forbidden this stand floats: the navigator snaps it to
        // some standable cell nearby or reports FAILED, and the walk ranking (mineNearestByWalk, mineRouteByVeins)
        // cannot price it, so under that ranking the reach-across scan below finds the floor cell a person mines such
        // a block from.
        boolean pillarStands =
                WorldConfig.allowPlace || !(BehaviorConfig.mineNearestByWalk || BehaviorConfig.mineRouteByVeins);
        if (pillarStands && rise >= 1 && rise <= MAX_PILLAR_RISE) {
            for (int[] d : dxz) {
                BlockPos stand = block.offset(d[0], 0, d[1]); // beside the log, same Y
                // The stand + head cells beside the log must be open to occupy,
                // and the support cell directly below the stand must be open too
                // (it's where PillarUp builds the top of the pillar).
                if (lvl.getBlockState(stand).blocksMotion()) {
                    continue;
                }
                if (lvl.getBlockState(stand.above()).blocksMotion()) {
                    continue;
                }
                if (lvl.getBlockState(stand.below()).blocksMotion()) {
                    continue;
                }
                // The offset column must be clear from our feet up to the stand so
                // the pillar can rise through it (A* re-validates; this prunes the
                // obvious misses so we don't hand A* an unreachable floating goal).
                boolean columnClear = true;
                for (int y = footY; y < stand.getY(); y++) {
                    if (lvl.getBlockState(new BlockPos(stand.getX(), y, stand.getZ()))
                            .blocksMotion()) {
                        columnClear = false;
                        break;
                    }
                }
                if (columnClear) {
                    return stand;
                }
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
        // the non-unit offset mines fine.
        return findReachStand(lvl, block);
    }

    /**
     * Find a DRY, solid, standable cell within vanilla block reach of {@code block} with a clear collider line of sight
     * to the block centre — used to mine an over-water/over-gap log without entering the water. Returns the nearest
     * such stand, or null if none (then the target is genuinely unreachable).
     */
    private BlockPos findReachStand(Level lvl, BlockPos block) {
        double tx = block.getX() + 0.5;
        double ty = block.getY() + 0.5;
        double tz = block.getZ() + 0.5;
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
                    if (dx == 0 && dz == 0 && dy >= 0) {
                        continue;
                    }
                    BlockPos cand = block.offset(dx, dy, dz);
                    // Dry footing only: solid (non-water) support, no water at foot.
                    if (lvl.getBlockState(cand.below()).getFluidState().is(Fluids.WATER)) {
                        continue;
                    }
                    if (lvl.getBlockState(cand).getFluidState().is(Fluids.WATER)) {
                        continue;
                    }
                    if (!canStandHere(lvl, cand)) {
                        continue;
                    }
                    Vec3 eye = standingEye(cand);
                    double ex = eye.x;
                    double ey = eye.y;
                    double ez = eye.z;
                    double d2 = (ex - tx) * (ex - tx) + (ey - ty) * (ey - ty) + (ez - tz) * (ez - tz);
                    if (d2 > MAX_REACH * MAX_REACH || d2 >= bestD2) {
                        continue;
                    }
                    if (!reachLineOfSight(lvl, ex, ey, ez, tx, ty, tz, block)) {
                        continue;
                    }
                    bestD2 = d2;
                    bestStand = cand;
                }
            }
        }
        return bestStand;
    }

    /** Collider raycast from the eye to the block centre lands on the target block. */
    private boolean reachLineOfSight(
            Level lvl, double ex, double ey, double ez, double tx, double ty, double tz, BlockPos target) {
        BlockHitResult hit = lvl.clip(new ClipContext(
                new Vec3(ex, ey, ez),
                new Vec3(tx, ty, tz),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    /**
     * The shared stand test ({@link net.magicterra.worlddriver.game.body.BotUtil#canStandHereStatic}) plus one clause
     * only mining needs. Written as "the shared answer AND …" on purpose: a digger's extra refusal must be visibly
     * extra, or it reads as a second opinion about what standing is and the three processes drift apart.
     */
    private boolean canStandHere(Level lvl, BlockPos foot) {
        if (!canStandHereStatic(lvl, foot)) {
            return false;
        }
        // Never stand where lava touches the foot or head cell — a freshly-dug
        // pocket can flow into an adjacent cell and roast us. Reject the whole
        // 1-block shell around both of the bot's cells.
        return !lavaTouching(lvl, foot) && !lavaTouching(lvl, foot.offset(0, 1, 0));
    }

    private static boolean isLava(Level lvl, BlockPos p) {
        return lvl.getBlockState(p).getFluidState().is(FluidTags.LAVA); // tag: source AND flowing
    }

    /**
     * True if {@code pos} itself or any of its 6 face-neighbours holds lava. Lava is present in the world model even
     * when hidden behind a solid block face, so this catches a pocket about to be opened by a dig.
     */
    private static boolean lavaTouching(Level lvl, BlockPos pos) {
        if (isLava(lvl, pos)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (isLava(lvl, pos.relative(d))) {
                return true;
            }
        }
        return false;
    }

    private Direction faceFromStandToBlock(BlockPos stand, BlockPos block) {
        int dx = block.getX() - stand.getX();
        if (dx == 1) {
            return Direction.WEST;
        }
        if (dx == -1) {
            return Direction.EAST;
        }
        int dz = block.getZ() - stand.getZ();
        if (dz == 1) {
            return Direction.NORTH;
        }
        if (dz == -1) {
            return Direction.SOUTH;
        }
        int dy = block.getY() - stand.getY();
        if (dy == -1) {
            return Direction.UP;
        }
        if (dy == 1) {
            return Direction.DOWN;
        }
        return Direction.UP;
    }

    private String currentBlockId(Level lvl) {
        if (lvl == null || currentTarget == null) {
            return "";
        }
        return BuiltInRegistries.BLOCK
                .getKey(lvl.getBlockState(currentTarget).getBlock())
                .toString();
    }

    /**
     * Read-only post-mortem of the APPROACH walk, for a scene to embed in {@code ctx.fail}.
     *
     * <p>{@code st.mine.lastError} is this process's generic abort text and says the same thing ("no reachable target")
     * for every way the approach can end — the pre-filter never handing over a goal, the walk failing, the walk
     * arriving somewhere that is not the stand. The navigator has carried the readings that separate those for a while
     * ({@link Navigator#result()}, {@link net.magicterra.worlddriver.bot.control.NavigatorInspector#progressProbe()},
     * {@link net.magicterra.worlddriver.bot.control.NavigatorInspector#planProbe()}) and their javadoc says they exist
     * to be embedded in a failure — but they are package-private-adjacent state on a private field, so no scene could
     * reach them and {@code wd.buriedOre} has been failing on the bare abort text.
     *
     * <p>Deliberately one STRING and not the navigator itself: a scene must not be able to steer the process's
     * navigator, and the log stream drops lines under end-of-suite load, so the value of these readings is that they
     * land in {@code results.jsonl} beside the verdict.
     */
    public String approachProbe() {
        return "phase=" + phase + " broken=" + brokenOfDesired()
                + " target=" + CellText.of(currentTarget) + " stand=" + CellText.of(currentStand)
                + " endReason=" + navigator.result().endReason()
                + " goalReached=" + navigator.result().goalReached()
                + " finalDist="
                + String.format(
                        java.util.Locale.ROOT, "%.3f", navigator.result().finalDist())
                + " goalSnapped=" + navigator.result().goalSnapped()
                + " navigatorErr=" + navigator.result().reason()
                + " | " + navigator.inspect().progressProbe()
                + " | " + navigator.inspect().planProbe();
    }

    /** The {@code broken/desired} progress text every status line of this process shares. */
    private String brokenOfDesired() {
        return broken + "/" + desiredQty;
    }

    /**
     * {@link #approachProbe}'s world-side companion: the same plan, but every node read against the blocks that are
     * actually there — see {@link net.magicterra.worlddriver.bot.control.NavigatorInspector#planCellAudit} for what the
     * three cells per node separate (plan wants the feet in stone / node is legal but unreachable / node has no floor).
     *
     * <p>A separate call rather than a suffix on {@code approachProbe} because it needs a level and that probe
     * deliberately takes none — its callers include paths with no level in hand. Same one-string, no-navigator-handle
     * rule as its sibling: a scene must be able to READ this navigator's plan without being able to steer it.
     */
    public String approachPlanAudit(BlockGetter lvl) {
        return navigator.inspect().planCellAudit(lvl);
    }

    /**
     * A reachable mining target: the block + adjacent stand position + face direction. {@code clearing} marks an
     * intermediate occluding-leaf break that opens reach/LOS to a real target — it does NOT count toward the requested
     * quota.
     */
    private record Target(BlockPos block, BlockPos stand, Direction face, boolean clearing) {}
}
