package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import net.magicterra.agent.bot.util.BlockMatch;
import java.util.Map;
import java.util.Set;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import java.util.Deque;
import java.util.ArrayDeque;

public final class MineProcess implements BotProcess {

    private final Set<String> targetIds;
    // Per-id matchers: each entry is an exact id or a '#tag' selector
    // (e.g. #minecraft:logs matches any log species). A block is a target
    // when ANY matcher accepts it.
    private final List<Predicate<BlockState>> targetMatchers;
    private final int desiredQty;
    private final int searchRadius;
    private final Walker walker = new Walker();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int broken;
    // Position where this mine command began. Targets beyond
    // BotConfig.mineMaxDriftFromStart of this anchor are rejected so a single
    // mine command can't chain hops across the world (e.g. swim an ocean toward
    // scattered red_sand). Set lazily on first tick (attach has no player).
    private BlockPos startAnchor;
    private BlockPos currentTarget;
    private BlockPos currentStand;
    private Direction currentFace;
    // True when currentTarget is a leaf being cleared to open access to a real
    // target (not itself a quota block) — see findClearingTarget.
    private boolean currentTargetClearing;
    private int breakingTicks;
    private String breakStartId = "";
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, BREAKING, COLLECT }
    // Last few break positions — used as goal hints during COLLECT so the
    // bot walks back through where it just mined and lets vanilla's pickup
    // magnet vacuum the drops. Capped to avoid backtracking forever.
    private final Deque<BlockPos> recentBreaks = new ArrayDeque<>();
    private int collectTicks;
    private final Walker collectWalker = new Walker();
    private BlockPos currentCollectGoal;
    private static final int MAX_COLLECT_TICKS = 240;       // ~12 s @ 20 tps — long enough to walk to all 8 break spots
    private static final int COLLECT_SCAN_RADIUS = 8;       // matches vanilla item lifetime drift

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

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        // Quota reached → switch to COLLECT instead of declaring done. The
        // old behaviour left the player wherever the last break completed,
        // so items that fell 2-3 blocks away (typical for trees: trunk
        // breaks at head height, items at foot height) just despawned.
        if (broken >= desiredQty && phase != Phase.COLLECT) {
            if (mc.options != null) mc.options.keyAttack.setDown(false);
            phase = Phase.COLLECT;
            collectTicks = 0;
        }
        LocalPlayer p = mc.player;
        if (p == null) { st.mine.lastError = "player vanished"; st.mine.reset(); return true; }
        // Anchor the command to where it began (first tick with a live player).
        if (startAnchor == null) startAnchor = p.blockPosition();

        // Lava-contact safety net: the instant we're touching lava, stop mining.
        // The lava-near target/stand rejection below should keep us out of it, but
        // a dig can reveal a pocket that floods our own cell — bail before the
        // ~4-dmg/tick spiral instead of walking deeper toward the next target.
        // (This is exactly what killed a naked run: a stone dig opened a hidden
        // pocket and the next-target approach stepped into it.)
        if (p.isInLava()) {
            if (mc.options != null) {
                mc.options.keyAttack.setDown(false);
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
            }
            st.mine.lastError = "aborted: entered lava";
            st.mine.reset();
            return true;
        }

        switch (phase) {
            case SEARCH -> {
                Target t = scanForTarget(mc, p);
                if (t == null) {
                    st.mine.lastError = "no reachable target (broken=" + broken + "/" + desiredQty + ")";
                    st.mine.reset();
                    return true;
                }
                currentTarget = t.block;
                currentStand = t.stand;
                currentFace = t.face;
                currentTargetClearing = t.clearing();
                st.mine.target = currentTarget;
                walker.setGoal(new Goal.Block(t.stand));
                phase = Phase.GOING;
            }
            case GOING -> {
                // Make sure attack isn't lingering from the previous block.
                mc.options.keyAttack.setDown(false);
                // Already standing on the target's stand cell? Then there is nothing
                // to walk — go straight to breaking. This is the straight-up "mine
                // the overhead block from directly below" case (stand == our own
                // foot cell): handing the Walker a zero-length path makes it report
                // FAILED, which would blacklist a perfectly good target. (The normal
                // side/reach-across stand is a DIFFERENT cell, so this never short-
                // circuits a real walk.)
                if (currentStand != null && p.blockPosition().equals(currentStand)) {
                    selectBestTool(mc, currentTarget);
                    faceBlock(p, currentTarget);
                    breakingTicks = 0;
                    breakStartId = currentBlockId(mc);
                    phase = Phase.BREAKING;
                    return false;
                }
                Walker.Step s = walker.tick(mc, w);
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
                        BlockPos foot = new BlockPos((int) Math.floor(p.getX()),
                                (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
                        Target clear = findClearingTarget(mc.level, foot, currentTarget);
                        if (clear != null) {
                            if (BotConfig.walkerDebug)
                                LOG.info("[mine] stand unreachable for {} -> clear leaf {} (stand {})",
                                        currentTarget, clear.block(), clear.stand());
                            currentTarget = clear.block();
                            currentStand = clear.stand();
                            currentFace = clear.face();
                            currentTargetClearing = true;
                            st.mine.target = currentTarget;
                            walker.setGoal(new Goal.Block(clear.stand()));
                            phase = Phase.GOING;
                            return false;
                        }
                    }
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    selectBestTool(mc, currentTarget);
                    faceBlock(p, currentTarget);
                    breakingTicks = 0;
                    // Block id observed at the moment we arrived — used to detect
                    // both successful breaks (id changes) and resyncs (id flickers
                    // to air then back, indicating a rejected predicted destroy).
                    breakStartId = currentBlockId(mc);
                    phase = Phase.BREAKING;
                }
            }
            case BREAKING -> {
                // Release walking keys; let vanilla's tick → continueAttack →
                // gameMode.continueDestroyBlock pipeline drive the break. Calling
                // gameMode methods directly causes client-side prediction to remove
                // the block visually for a tick before server resyncs, which the
                // naive "is the block air now?" check would mis-count as success.
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
                p.setSprinting(false);
                faceBlock(p, currentTarget);
                mc.options.keyAttack.setDown(true);

                breakingTicks++;
                String now = currentBlockId(mc);
                // Robust completion: id changed away from the original block AND
                // is no longer the same kind. Avoids the 1-tick flicker false-positive.
                if (!now.equals(breakStartId) && !isTarget(mc.level.getBlockState(currentTarget))) {
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
                        if (!lavaTouching(mc.level, currentTarget)) {
                            recentBreaks.addLast(currentTarget);
                            while (recentBreaks.size() > 8) recentBreaks.removeFirst();
                        }
                    }
                    mc.options.keyAttack.setDown(false);
                    currentTarget = null;
                    if (broken >= desiredQty) {
                        phase = Phase.COLLECT;
                        collectTicks = 0;
                    } else {
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BotConfig.breakTimeoutTicks) {
                    blacklist.add(currentTarget);
                    mc.options.keyAttack.setDown(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case COLLECT -> {
                // Walk toward the nearest dropped ItemEntity (vanilla's
                // pickup magnet does the rest at ~1 block). Falls back to
                // walking through remembered break positions when no items
                // are visible — handles the chunk-not-loaded case where
                // ClientLevel hasn't received the SpawnEntity packet yet.
                mc.options.keyAttack.setDown(false);
                collectTicks++;
                BlockPos goal = findCollectGoal(mc, p);
                if (goal == null || collectTicks > MAX_COLLECT_TICKS) {
                    st.mine.reset();
                    return true;
                }
                if (!goal.equals(currentCollectGoal)) {
                    currentCollectGoal = goal;
                    collectWalker.setGoal(new Goal.Block(goal));
                }
                collectWalker.tick(mc, w);
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
    private BlockPos findCollectGoal(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl != null) {
            AABB box = p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS);
            var items = lvl.getEntitiesOfClass(ItemEntity.class, box,
                    it -> it.isAlive() && !it.hasPickUpDelay());
            ItemEntity best = null;
            double bestD2 = Double.MAX_VALUE;
            for (var it : items) {
                double d2 = it.distanceToSqr(p);
                if (d2 < bestD2) { bestD2 = d2; best = it; }
            }
            if (best != null) {
                return new BlockPos(
                        (int) Math.floor(best.getX()),
                        (int) Math.floor(best.getY()),
                        (int) Math.floor(best.getZ()));
            }
        }
        // No item visible — sweep through remembered break positions in FIFO
        // order. Pop any we've already reached so we keep moving toward the
        // next spot instead of looping.
        while (!recentBreaks.isEmpty()) {
            BlockPos bp = recentBreaks.peekFirst();
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

    /** Scan candidates within radius, filter by target id + blacklist + stand reachability, pick nearest. */
    private Target scanForTarget(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl == null) return null;
        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        int r = searchRadius;
        Target best = null;
        long bestD2 = Long.MAX_VALUE;
        // Nearest real target we could SEE but not reach — the leaf-clearing
        // fallback (below) tries to open access to it when nothing else is reachable.
        BlockPos nearestUnreachable = null;
        long nuD2 = Long.MAX_VALUE;
        int scanned = 0;
        int targetHits = 0;          // DIAG: cells passing isTarget
        int targetLavaSkips = 0;     // DIAG: targets skipped for lava
        for (int dy = -BotConfig.mineSearchVerticalRadius; dy <= BotConfig.mineSearchVerticalRadius; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanned++;
                    BlockPos bp = foot.offset(dx, dy, dz);
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
            }
            if (scanned > 50_000) break;
        }
        if (best != null) return best;
        // Nothing directly reachable. If a real target is occluded by leaves we can
        // stand-and-break, return one as a clearing target so mining it opens access.
        if (nearestUnreachable != null) {
            Target clear = findClearingTarget(lvl, foot, nearestUnreachable);
            if (BotConfig.walkerDebug)
                LOG.info("[mine] no direct stand; nearestUnreachable={} -> clearing={}",
                        nearestUnreachable, clear == null ? "null" : clear.block());
            return clear;
        }
        if (BotConfig.walkerDebug)
            LOG.info("[mine] scan found no target (scanned={} targetHits={} lavaSkips={} foot={} r={} vR={})",
                    scanned, targetHits, targetLavaSkips, foot, r, BotConfig.mineSearchVerticalRadius);
        return null;
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

        // PILLAR-UP (踮脚): the log is too HIGH for any ground stand — an upper
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
        // and mine across the gap. The break aims via faceBlock's raycast
        // (currentFace is cosmetic), so the non-unit offset mines fine.
        return findReachStand(lvl, block);
    }

    /** Vanilla survival block-interaction reach is 4.5; keep a hair inside it. */
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
                // and mine straight UP (eye→center of a block 4–5 up is within the
                // 4.5 reach): the leaf-encased canopy log / low-ceiling case.
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
                    double ex = cand.getX() + 0.5, ey = cand.getY() + 1.62, ez = cand.getZ() + 0.5;
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

    private boolean canStandHere(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion() && !here.getFluidState().is(Fluids.WATER)) return false;
        if (head.blocksMotion() && !head.getFluidState().is(Fluids.WATER)) return false;
        // Never stand where lava touches the foot or head cell — a freshly-dug
        // pocket can flow into an adjacent cell and roast us. Reject the whole
        // 1-block shell around both body cells.
        if (lavaTouching(lvl, foot) || lavaTouching(lvl, foot.offset(0, 1, 0))) return false;
        return true;
    }

    private static boolean isLava(Level lvl, BlockPos p) {
        return lvl.getBlockState(p).getFluidState().is(Fluids.LAVA);
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

    private void faceBlock(LocalPlayer p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        double tx = block.getX() + 0.5, ty = block.getY() + 0.5, tz = block.getZ() + 0.5;
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    /** Scan inventory for the best correct tool for the block; swap to selected hotbar slot. */
    private void selectBestTool(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) return;
        BlockState bs = lvl.getBlockState(pos);
        Inventory inv = p.getInventory();
        int bestSlot = -1;
        float bestSpeed = inv.getSelected().getDestroySpeed(bs);
        boolean bestCorrect = inv.getSelected().isCorrectToolForDrops(bs);
        // Scan hotbar first (cheaper switch).
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = stk.getDestroySpeed(bs);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
            // Sync to server so attack packets use the new item; client-side
            // ItemStack interactions (destroy speed) already reflect inv.selected.
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
        }
        // Inventory swap (creative-friendly): if no hotbar slot was good but main inventory has one.
        int mainBest = -1;
        float mainBestSpeed = bestSpeed;
        boolean mainBestCorrect = bestCorrect;
        for (int slot = 9; slot < inv.items.size(); slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = stk.getDestroySpeed(bs);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !mainBestCorrect) || (cor == mainBestCorrect && sp > mainBestSpeed)) {
                mainBest = slot;
                mainBestSpeed = sp;
                mainBestCorrect = cor;
            }
        }
        if (mainBest > 0 && mc.gameMode != null) {
            // Swap to selected hotbar slot via creative pickItem path; harmless no-op in survival.
            int target = inv.selected;
            inv.pickSlot(mainBest);
            // pickSlot moves into hotbar slot (selected) when in creative; survival has no equivalent fast-path,
            // so survival agents should pre-stage tools.
        }
    }

    private String currentBlockId(Minecraft mc) {
        Level lvl = mc.level;
        if (lvl == null || currentTarget == null) return "";
        return BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(currentTarget).getBlock()).toString();
    }

    /** A reachable mining target: the block + adjacent stand position + face direction.
     *  {@code clearing} marks an intermediate occluding-leaf break that opens reach/LOS
     *  to a real target — it does NOT count toward the requested quota. */
    private record Target(BlockPos block, BlockPos stand, Direction face, boolean clearing) {}
}
