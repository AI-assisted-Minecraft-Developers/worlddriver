package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.EnumSet;
import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.PathSmoothing;
import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ColumnRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoWater;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YFloor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ShorelineHug;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 3, the Bias family</b>: the 13
 * {@code AgentGameTestBias} deterministic PLANNER arenas (per-intent cost bias +
 * hard-constraint gates: {@code AvoidRegion} / {@code ColumnRadius} / {@code YFloor} /
 * {@code LeashHardRadius} / {@code NoWater} / {@code NoBreak} / {@code ShorelineHug},
 * plus the dig-up/dig-down reuse probes and the two escape-farthest churn guards),
 * migrated verbatim to testkit {@code wd.*} scenes and their legacy twins retired in the
 * same commit.
 *
 * <p><b>Porting is by the canonical pattern established in {@link WorldDriverScenes} and
 * {@link WorldDriverTerrainScenes}</b> (study their class javadocs for the full rationale
 * — this class applies the same mechanical substitutions and does not re-explain them):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code x0/z0} → {@link SceneContext#origin()} X/Z (arena math
 *       byte-identical, just relocated to the harness grid cell);</li>
 *   <li>absolute Y constants → {@code origin.y + (legacy_Y − 200)}. Every grid origin sits
 *       at {@code GRID_Y = 200}, so the mapped ABSOLUTE Y equals the legacy absolute Y — the
 *       vertical geometry is literally unchanged (and A* is integer-cell, so a planner scene
 *       is position-invariant regardless);</li>
 *   <li>{@code ServerPlayerBody.create(...)} → {@link ServerPlayerBody#createUnique}
 *       (per-scene player) + {@code ctx.cleanup(() -> fp.discard())};</li>
 *   <li>the neoforge {@code FakePlayer} handle → the common {@link ServerPlayer} handle
 *       ({@link ServerPlayerBody#fakePlayer()} return type; inventory ops identical);</li>
 *   <li>{@code try/finally} per-key {@code BotConfig} save/restore → the ONLY scenes that
 *       flip config ({@code digUpY}, {@code digDownY}, {@code columnRadius}, {@code forbidDig},
 *       {@code escapeFarthestNoRockDrill}, {@code budgetAwayTunnelChurn}, {@code shorelineSmoother})
 *       use {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)} registered
 *       FIRST (LIFO → closes LAST, after the avatar discard); the six pure-constraint scenes
 *       that touched no config are ported without a pin (faithful — the harness restores
 *       baseline between scenes, so each planner sees clean defaults);</li>
 *   <li>{@code AgentGameTestSupport.maxPathY} → the inlined {@link #maxPathY} helper below
 *       (promoted-into-class faithful copy, not imported across the neoforge testmod
 *       source-set boundary);</li>
 *   <li>the per-arena private geometry helpers ({@code pathEntersZone}, {@code minPathY},
 *       {@code maxPathXZDist}, {@code pathEntersWater}, {@code minPathZRelative},
 *       {@code distToWaterLE}, {@code firstOutOfBand[Smoothed]}) → carried over verbatim as
 *       private static helpers;</li>
 *   <li>{@code throw new GameTestAssertException(msg)} → {@link SceneContext#fail(String)}
 *       (prefixed with the scene's short name for multi-scene log attribution);</li>
 *   <li>{@code helper.succeed()} → normal return;</li>
 *   <li>the {@code gtOnlySkips(...)} probe first line → deleted (the testkit gate
 *       reconciles itself).</li>
 * </ul>
 *
 * <p><b>All 13 are PLANNER-ONLY.</b> Unlike wave 2 (Terrain), no Bias arena drives a
 * {@link net.magicterra.worlddriver.bot.movement.Walker} or a registered driver — each builds an
 * immutable {@link LevelWorldView} and runs one or two {@link PathFinder} searches, then
 * asserts on the returned {@code Result}. So there is no executor flakiness, no per-tick
 * stepping, and no {@code SimProbes.grantWaterEffects} (nothing ever moves or takes damage).
 * The brief's "likely driver-mode members (createIsolated)" hypothesis did not hold — the
 * densest {@code AgentGameTestSupport} coupling was 21 {@code gtOnlySkips}/{@code maxPathY}
 * call sites, all of which drop out or inline. The scene method runs synchronously on its
 * first RUN tick and returns (no await steps), so the old/new-shell A/B compares like with
 * like.
 *
 * <p><b>Origin slots.</b> All 13 take AUTO slots at the default radius, with two
 * exceptions widened to {@code .withChunkRadius(2)} because their footprint exceeds the
 * default window's +31 edge (radius-2 window = dx/dz [−32,+47]):
 * {@code wd.shorelineHug} (clear span reaches dx +41) and {@code wd.shorelineSmoother}
 * (basin reaches dx/dz +32). No scene is pinned to a fixed slot: every Bias gate is a
 * discrete planner OUTCOME (goalReached / node-inside-zone / straight-shaft / minPathY),
 * integer-cell and position-invariant, so registry-growth relocation cannot flip it (the
 * {@code wd.buriedOre} auto-slot precedent applies).
 */
public final class WorldDriverBiasScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.avoidRegionDetour", 200, WorldDriverBiasScenes::avoidRegionDetour),
                Scene.of("wd.digUpY", 200, WorldDriverBiasScenes::digUpY),
                Scene.of("wd.digDownY", 200, WorldDriverBiasScenes::digDownY),
                Scene.of("wd.columnRadius", 200, WorldDriverBiasScenes::columnRadius),
                Scene.of("wd.parkourGate", 200, WorldDriverBiasScenes::parkourGate),
                Scene.of("wd.yFloorConstraint", 200, WorldDriverBiasScenes::yFloorConstraint),
                Scene.of("wd.leashHard", 200, WorldDriverBiasScenes::leashHard),
                Scene.of("wd.forbidWater", 200, WorldDriverBiasScenes::forbidWater),
                Scene.of("wd.forbidDig", 200, WorldDriverBiasScenes::forbidDig),
                Scene.of("wd.shorelineHug", 200, WorldDriverBiasScenes::shorelineHug).withChunkRadius(2),
                Scene.of("wd.shorelineSmoother", 200, WorldDriverBiasScenes::shorelineSmoother).withChunkRadius(2),
                Scene.of("wd.escapeFarthestNoRockDrill", 200, WorldDriverBiasScenes::escapeFarthestNoRockDrill),
                Scene.of("wd.budgetAwayTunnelChurn", 200, WorldDriverBiasScenes::budgetAwayTunnelChurn));
    }

    /** Ported from {@code AgentGameTestBias#avoidRegionDetourArena}: A4b per-intent
     *  {@code AvoidRegion}. A 7-wide flat stone lane; PLAIN must pass straight through a
     *  mid-lane zone, {@code AvoidRegion} must route every node clear of it. Planner-only. */
    private static void avoidRegionDetour(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40
        for (int dx = -1; dx <= 21; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 20, y + 1, cz);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w).findPath(start, new Goal.Block(goal));

        double zoneCx = cx + 10 + 0.5, zoneCy = y + 1, zoneCz = cz + 0.5, r = 3.0;
        AvoidRegion avoid = new AvoidRegion(zoneCx, zoneCy, zoneCz, r, 1000.0);
        PathFinder.Result detour = new PathFinder(w, List.of((CostModifier) avoid))
                .findPath(start, new Goal.Block(goal));

        boolean plainThrough = pathEntersZone(plain, zoneCx, zoneCy, zoneCz, r);
        boolean detourClear = !pathEntersZone(detour, zoneCx, zoneCy, zoneCz, r);
        WorldDriverCommon.LOG.info("[wd.avoidRegionDetour] plainThrough={} detourClear={} plainLen={} detourLen={}",
                plainThrough, detourClear, plain.path().size(), detour.path().size());
        WorldDriverCommon.LOG.info("[wd.avoidRegionDetour] plain.finalCost={} goalReached={} | detour.finalCost={} goalReached={}",
                plain.finalCost(), plain.goalReached(), detour.finalCost(), detour.goalReached());

        if (!plainThrough)
            ctx.fail("avoidRegionDetour: baseline: plain path did NOT pass through the zone — arena geometry wrong");
        if (!detourClear)
            ctx.fail("avoidRegionDetour: AvoidRegion did NOT detour: a planned node is still inside the zone");
    }

    /** Ported from {@code AgentGameTestBias#digUpYArena}: A2b dig-UP. A 2-high chamber inside
     *  a solid slab, cobblestone in hand, {@code Goal.YLevel} above the slab — the plan must
     *  chain PillarUp to reach the level. Break+place ON. Planner-only. */
    private static void digUpY(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 30, top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 41;   // legacy 230/240/241

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, base + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, base + 2, cz), Blocks.AIR.defaultBlockState());

        BlockPos start = new BlockPos(cx, base + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, base + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // pillar blocks → canPlace()=true
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;

        PathFinder.Result r = new PathFinder(w, SearchProfile.NONE)
                .findPath(start, new Goal.YLevel(targetY));
        WorldDriverCommon.LOG.info(
                "[wd.digUpY] goalReached={} maxPathY={} pathLen={} expanded={} finalCost={}",
                r.goalReached(), maxPathY(r), r.path().size(), r.expanded(), r.finalCost());
        if (!r.goalReached() || maxPathY(r) < targetY)
            ctx.fail("digUpY: chained PillarUp did NOT plan a dig-up to YLevel(" + targetY + "): reached="
                    + r.goalReached() + " maxY=" + maxPathY(r) + " expanded=" + r.expanded());
    }

    /** Ported from {@code AgentGameTestBias#digDownYArena}: A2b reuse-first — "dig down to Y=N"
     *  must plan with the existing {@code DownBreak} move. Iron pickaxe on top of a solid slab,
     *  {@code Goal.YLevel} inside it; the plan must reach the level down a STRAIGHT shaft (no
     *  sideways dig). Break ON (pinned). Planner-only. */
    private static void digDownY(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 34, slabLo = ctx.origin().getY() + 28;   // legacy 240/234/228

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = slabLo; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, top + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, top + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;

        double probeCost = w.breakCost(new BlockPos(cx, top, cz), start);
        WorldDriverCommon.LOG.info("[wd.digDownY] probe allowBreak={} breakCost(topBlock)={}",
                BotConfig.allowBreak, probeCost);

        PathFinder.Result r = new PathFinder(w, SearchProfile.NONE)
                .findPath(start, new Goal.YLevel(targetY));

        boolean straightShaft = true;
        for (BlockPos p : r.path())
            if (p.getX() != cx || p.getZ() != cz) { straightShaft = false; break; }
        WorldDriverCommon.LOG.info(
                "[wd.digDownY] goalReached={} minPathY={} pathLen={} straightShaft={} finalCost={}",
                r.goalReached(), minPathY(r), r.path().size(), straightShaft, r.finalCost());

        if (!r.goalReached() || minPathY(r) != targetY)
            ctx.fail("digDownY: DownBreak chain did NOT plan a dig-down to YLevel(" + targetY + "): reached="
                    + r.goalReached() + " minY=" + minPathY(r) + " breakCost=" + probeCost);
        if (!straightShaft)
            ctx.fail("digDownY: dig-down plan wandered out of the start column (expected a straight DownBreak shaft): "
                    + r.path());
    }

    /** Ported from {@code AgentGameTestBias#columnRadiusArena}: the {@code ColumnRadius} hard
     *  XZ-cylinder constraint (ascent-drift fix). PLAIN planning to a {@code Goal.YLevel} takes a
     *  cheap staircase and DRIFTS +8 in X; planning WITH {@code ColumnRadius} must still reach the
     *  level up a straight start-column pillar and every node must stay within the radius. Break+place
     *  ON (pinned). Planner-only. */
    private static void columnRadius(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 30, rungs = 8, targetY = base + rungs + 1;   // legacy base 230

        for (int dx = -2; dx <= rungs + 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= rungs + 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, base + dy, cz + dz), Blocks.AIR.defaultBlockState());

        level.setBlockAndUpdate(new BlockPos(cx, base, cz), Blocks.STONE.defaultBlockState());
        for (int k = 1; k <= rungs; k++)
            level.setBlockAndUpdate(new BlockPos(cx + k, base + k, cz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, base + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, base + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // pillar blocks → canPlace()
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        double colCx = cx + 0.5, colCz = cz + 0.5;
        final double radius = 0.9;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE)
                .findPath(start, new Goal.YLevel(targetY));
        SearchProfile column = new SearchProfile(List.of(), CapabilityProfile.ALL,
                List.of(new ColumnRadius(colCx, colCz, radius)));
        PathFinder.Result held = new PathFinder(w, column)
                .findPath(start, new Goal.YLevel(targetY));

        double plainMaxXZ = maxPathXZDist(plain, colCx, colCz);
        double heldMaxXZ = maxPathXZDist(held, colCx, colCz);
        WorldDriverCommon.LOG.info(
                "[wd.columnRadius] plain.goalReached={} plainMaxXZ={} maxPathY(plain)={} | "
                + "held.goalReached={} heldMaxXZ={} maxPathY(held)={}",
                plain.goalReached(), plainMaxXZ, maxPathY(plain),
                held.goalReached(), heldMaxXZ, maxPathY(held));

        if (!plain.goalReached() || plainMaxXZ <= radius)
            ctx.fail("columnRadius: baseline: PLAIN plan did not drift out of the start column (reached="
                    + plain.goalReached() + " maxXZ=" + plainMaxXZ + " radius=" + radius
                    + ") — arena geometry wrong, the ascent-drift bug is not reproduced");
        if (!held.goalReached() || maxPathY(held) < targetY)
            ctx.fail("columnRadius: ColumnRadius over-pruned: constrained plan did NOT reach YLevel(" + targetY
                    + ") up the start column (reached=" + held.goalReached()
                    + " maxY=" + maxPathY(held) + ")");
        if (heldMaxXZ > radius + 1e-6)
            ctx.fail("columnRadius: ColumnRadius did NOT contain the ascent: a planned node strayed "
                    + heldMaxXZ + " from the start column (radius=" + radius + ") — path=" + held.path());
    }

    /** Ported from {@code AgentGameTestBias#parkourGateArena}: A2a per-intent {@code CapabilityProfile}
     *  move-type gate. Two platforms across a full-width missing-floor column; the only crossing is a
     *  Parkour2 leap (inventory cleared so no place-bypass). PLAIN must cross, PARKOUR-forbidden must
     *  NOT. Planner-only. */
    private static void parkourGate(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40

        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 5; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 7, y + 1, cz);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();   // CRITICAL: no placeable block in the hotbar
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        SearchProfile walkOnly = new SearchProfile(List.of(),
                new CapabilityProfile(EnumSet.of(Capability.PARKOUR)), List.of());
        PathFinder.Result noParkour = new PathFinder(w, walkOnly).findPath(start, new Goal.Block(goal));

        WorldDriverCommon.LOG.info(
                "[wd.parkourGate] plain.goalReached={} plain.finalCost={} | noParkour.goalReached={} noParkour.finalCost={}",
                plain.goalReached(), plain.finalCost(), noParkour.goalReached(), noParkour.finalCost());

        if (!plain.goalReached())
            ctx.fail("parkourGate: baseline: PLAIN plan did NOT cross the gap — arena geometry wrong (no parkour move fired)");
        if (noParkour.goalReached())
            ctx.fail("parkourGate: capability gate failed: walk-only (PARKOUR forbidden) plan still crossed the gap");
    }

    /** Ported from {@code AgentGameTestBias#yFloorConstraintArena}: A2a {@code YFloor} hard constraint.
     *  A platform edge drops 3 blocks into an enclosed pit; PLAIN reaches the pit, {@code YFloor(pitY+1)}
     *  prunes the only descent edge so the goal becomes unreachable. Planner-only. */
    private static void yFloorConstraint(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40

        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -6; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        int pitFloorY = y - 3;
        for (int dz = -1; dz <= 1; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, pitFloorY, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx - 1, y + 1, cz);
        int dipY = pitFloorY + 1;                 // standing cell on the pit floor
        BlockPos goal = new BlockPos(cx + 1, dipY, cz);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        SearchProfile constrained = new SearchProfile(List.of(), CapabilityProfile.ALL,
                List.of(new YFloor(dipY + 1)));
        PathFinder.Result held = new PathFinder(w, constrained).findPath(start, new Goal.Block(goal));

        WorldDriverCommon.LOG.info(
                "[wd.yFloorConstraint] dipY={} plain.goalReached={} minPathY(plain)={} | held.goalReached={} minPathY(held)={}",
                dipY, plain.goalReached(), minPathY(plain), held.goalReached(),
                held.path().isEmpty() ? -1 : minPathY(held));

        if (!plain.goalReached() || minPathY(plain) > dipY)
            ctx.fail("yFloorConstraint: baseline: PLAIN plan did not reach/dip into the pit — arena geometry wrong");
        if (held.goalReached())
            ctx.fail("yFloorConstraint: YFloor(dipY+1) did NOT prune the pit descent: goal still reached with the hard floor set");
    }

    /** Ported from {@code AgentGameTestBias#leashHardArena}: A2a {@code LeashHardRadius} hard
     *  constraint. One anchor, one radius R shared verbatim between the constraint and the goals: a
     *  goal inside R must be reached, an identical leash to a goal beyond R must NOT. Planner-only. */
    private static void leashHard(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40

        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 18; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        double sx = cx + 0.5, sy = y + 1, sz = cz + 0.5;
        final double radius = 10.0;

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos insideGoal = new BlockPos(cx + 8, y + 1, cz);    // dist 8  < R
        BlockPos outsideGoal = new BlockPos(cx + 16, y + 1, cz);  // dist 16 > R

        ServerPlayerBody av = SceneBody.avatar(ctx, level, sx, sy, sz);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);

        SearchProfile leashed = new SearchProfile(List.of(), CapabilityProfile.ALL,
                List.of(new LeashHardRadius(sx, sy, sz, radius)));

        PathFinder.Result inside = new PathFinder(w, leashed).findPath(start, new Goal.Block(insideGoal));
        PathFinder.Result outside = new PathFinder(w, leashed).findPath(start, new Goal.Block(outsideGoal));

        WorldDriverCommon.LOG.info(
                "[wd.leashHard] anchor=({},{},{}) R={} inside.goalReached={} outside.goalReached={}",
                sx, sy, sz, radius, inside.goalReached(), outside.goalReached());

        if (!inside.goalReached())
            ctx.fail("leashHard: baseline: goal INSIDE the hard leash radius was not reached — arena geometry wrong");
        if (outside.goalReached())
            ctx.fail("leashHard: LeashHardRadius did NOT hold: a goal OUTSIDE the radius was still reached");
    }

    /** Ported from {@code AgentGameTestBias#forbidWaterArena}: A2b-c {@code NoWater} hard constraint.
     *  A dry stone lane walled in bedrock with a full-width foot-layer wading pool mid-lane; PLAIN
     *  wades through (only route), {@code NoWater} prunes every water-foot edge so the goal becomes
     *  unreachable. Planner-only. */
    private static void forbidWater(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40
        final int stripLo = 5, stripHi = 6;

        for (int dx = -2; dx <= 14; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 12; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                if (dx >= stripLo && dx <= stripHi)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());
            }
            for (int dz : new int[]{-2, 2})
                for (int dy = 0; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());
        }

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 10, y + 1, cz);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();   // no placeable block — no PillarUp/BridgePlace bypass
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        SearchProfile noWater = new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new NoWater()));
        PathFinder.Result held = new PathFinder(w, noWater).findPath(start, new Goal.Block(goal));

        boolean plainEntersWater = pathEntersWater(w, plain);
        WorldDriverCommon.LOG.info(
                "[wd.forbidWater] plain.goalReached={} plainEntersWater={} plainLen={} finalCost={} | held.goalReached={} heldLen={}",
                plain.goalReached(), plainEntersWater, plain.path().size(), plain.finalCost(),
                held.goalReached(), held.path().size());

        if (!plain.goalReached() || !plainEntersWater)
            ctx.fail("forbidWater: baseline: PLAIN plan did not cross the water strip — arena geometry wrong (goalReached="
                    + plain.goalReached() + " enteredWater=" + plainEntersWater + ")");
        if (held.goalReached())
            ctx.fail("forbidWater: NoWater did NOT hold: constrained plan still reached the goal (a route avoided the strip "
                    + "without going through it — geometry allows a bypass) path=" + held.path());
    }

    /** Ported from {@code AgentGameTestBias#forbidDigArena}: A2b-c {@code NoBreak} hard constraint.
     *  Reuses the digDownY recipe: a solid slab, iron pickaxe, {@code Goal.YLevel} inside. PLAIN
     *  digs down (baseline), {@code NoBreak} prunes every break-edge so the goal is unreachable even
     *  with global {@code allowBreak} ON (pinned). Planner-only. */
    private static void forbidDig(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 34, slabLo = ctx.origin().getY() + 28;   // legacy 240/234/228

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = slabLo; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, top + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, top + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE)
                .findPath(start, new Goal.YLevel(targetY));
        SearchProfile noBreak = new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new NoBreak()));
        PathFinder.Result held = new PathFinder(w, noBreak).findPath(start, new Goal.YLevel(targetY));

        WorldDriverCommon.LOG.info(
                "[wd.forbidDig] allowBreak={} plain.goalReached={} minPathY(plain)={} plainLen={} | "
                + "held.goalReached={} heldLen={}",
                BotConfig.allowBreak, plain.goalReached(), minPathY(plain), plain.path().size(),
                held.goalReached(), held.path().size());

        if (!plain.goalReached() || minPathY(plain) != targetY)
            ctx.fail("forbidDig: baseline: PLAIN plan did NOT dig down to YLevel(" + targetY + "): reached="
                    + plain.goalReached() + " minY=" + minPathY(plain));
        if (held.goalReached())
            ctx.fail("forbidDig: NoBreak did NOT hold: constrained plan still reached YLevel(" + targetY
                    + ") with allowBreak=" + BotConfig.allowBreak + " — path=" + held.path());
    }

    /** Ported from {@code AgentGameTestBias#shorelineHugArena}: A3b {@link ShorelineHug} cost-bias.
     *  A slab with a receding shoreline and a straight dry lane; PLAIN stays on the lane (baseline,
     *  no node past z0-2), {@code ShorelineHug(30)}+{@code NoWater} must dip south to hug the
     *  receding shore (some node z<=z0-5) while every node stays within 2 of water. Planner-only.
     *  Needs {@code .withChunkRadius(2)}: clear span reaches dx +41. */
    private static void shorelineHug(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40

        for (int dx = -3; dx <= 41; dx++)
            for (int dz = -12; dz <= 7; dz++)
                for (int dy = 0; dy <= 8; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 38; dx++)
            for (int dz = -9; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        for (int dx = -1; dx <= 38; dx++) {
            int shoreDz = (dx >= 12 && dx < 24) ? -7 : -1;
            for (int dz = -9; dz <= shoreDz; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());
        }

        for (int dx = -1; dx <= 38; dx++)
            for (int dy = 0; dy <= 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + 4), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz - 10), Blocks.BEDROCK.defaultBlockState());
            }

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 36, y + 1, cz);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();   // no placeable block — no bridge/pillar bypass
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w).findPath(start, new Goal.Block(goal));
        SearchProfile hug = new SearchProfile(List.of(new ShorelineHug(30.0)),
                CapabilityProfile.ALL, List.of(new NoWater()));
        PathFinder.Result hugged = new PathFinder(w, hug).findPath(start, new Goal.Block(goal));

        int plainMinZ = minPathZRelative(plain, cz);
        int huggedMinZ = minPathZRelative(hugged, cz);
        WorldDriverCommon.LOG.info(
                "[wd.shorelineHug] plain.goalReached={} plainMinZ(rel)={} plainLen={} | "
                + "hugged.goalReached={} huggedMinZ(rel)={} huggedLen={}",
                plain.goalReached(), plainMinZ, plain.path().size(),
                hugged.goalReached(), huggedMinZ, hugged.path().size());

        if (!plain.goalReached() || !hugged.goalReached())
            ctx.fail("shorelineHug: baseline: PLAIN and/or HUGGED plan did not reach the goal — plain="
                    + plain.goalReached() + " hugged=" + hugged.goalReached());

        if (plainMinZ < -2)
            ctx.fail("shorelineHug: baseline: PLAIN plan cut away from the straight lane past z0-2 (minZ(rel)="
                    + plainMinZ + ") — no bias should ever leave it; arena geometry wrong");

        if (huggedMinZ > -5)
            ctx.fail("shorelineHug: ShorelineHug did NOT dip to the receding shoreline: worst minZ(rel)="
                    + huggedMinZ + " (expected <= -5 somewhere in the middle third)");

        BlockPos worst = null;
        for (BlockPos p : hugged.path())
            if (!distToWaterLE(p, 2, w)) { worst = p; break; }
        if (worst != null)
            ctx.fail("shorelineHug: ShorelineHug did NOT stay within 2 of water at every node — worst offender="
                    + worst + " path=" + hugged.path());
    }

    /** Ported from {@code AgentGameTestBias#shorelineSmootherArena}: A3b smoother regression — the
     *  live red where {@code stringPull} straightened a bank-hugging bow across the taxed dry interior.
     *  An L-shaped water channel whose straight chord crosses only dry slab: smoothing WITHOUT the bias
     *  must collapse out of the shoreline band (proves the arena discriminates), smoothing WITH the bias
     *  must keep every node in-band. Pins {@code walkerDiagonalStringPull=true}. Planner-only. Needs
     *  {@code .withChunkRadius(2)}: basin reaches dx/dz +32. */
    private static void shorelineSmoother(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;   // legacy y 240 = origin.y+40
        WorldDriverCommon.LOG.info("[wd.shorelineSmoother] START");   // entry probe

        for (int dx = -2; dx <= 32; dx++)
            for (int dz = -2; dz <= 32; dz++) {
                for (int dy = 2; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = 0; dx <= 30; dx++)
            for (int dz = 5; dz <= 7; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());
        for (int dx = 28; dx <= 30; dx++)
            for (int dz = 5; dz <= 30; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());

        BlockPos start = new BlockPos(cx + 10, y + 2, cz + 8);   // south bank of the horizontal strip
        BlockPos goal = new BlockPos(cx + 27, y + 2, cz + 25);   // west bank of the vertical strip

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 10.5, y + 2, cz + 8.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        List<CostModifier> bias = List.of(new ShorelineHug(30.0));
        SearchProfile hug = new SearchProfile(bias, CapabilityProfile.ALL, List.of(new NoWater()));

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDiagonalStringPull = true;

        PathFinder.Result hugged = new PathFinder(w, hug).findPath(start, new Goal.Block(goal));
        BlockPos rawWorst = firstOutOfBand(hugged, 2, w);
        PathSmoothing.SmoothResult noBias = PathSmoothing.stringPull(w, hugged.path(), hugged.edges());
        PathSmoothing.SmoothResult withBias =
                PathSmoothing.stringPull(w, hugged.path(), hugged.edges(), bias);
        BlockPos noBiasWorst = firstOutOfBandSmoothed(noBias.path(), 2, w);
        BlockPos withBiasWorst = firstOutOfBandSmoothed(withBias.path(), 2, w);
        WorldDriverCommon.LOG.info(
                "[wd.shorelineSmoother] hugged.goalReached={} rawLen={} rawWorst={} | "
                + "noBiasLen={} noBiasWorst={} | withBiasLen={} withBiasWorst={}",
                hugged.goalReached(), hugged.path().size(), rawWorst,
                noBias.path().size(), noBiasWorst, withBias.path().size(), withBiasWorst);

        if (!hugged.goalReached())
            ctx.fail("shorelineSmoother: baseline: hugged plan did not reach the goal");
        if (rawWorst != null)
            ctx.fail("shorelineSmoother: baseline: the RAW hugged path already leaves the shoreline band at " + rawWorst
                    + " — geometry wrong (the L bank route should be optimal)");
        if (noBiasWorst == null)
            ctx.fail("shorelineSmoother: discrimination lost: bias-BLIND smoothing kept every node in-band — the dry "
                    + "chord no longer tempts the collapse (geometry or diagonal-string-pull pin wrong)");
        if (withBiasWorst != null)
            ctx.fail("shorelineSmoother: REGRESSION: bias-AWARE smoothing still collapsed out of the shoreline band at "
                    + withBiasWorst + " — the straight chord's bias cost must reject the collapse");
    }

    /** Ported from {@code AgentGameTestBias#escapeFarthestNoRockDrillArena}: gap#59. A budget-capped
     *  search from a chamber sealed in rock toward a {@code Goal.Block} ABOVE must never commit an
     *  escape-farthest best-effort segment that DIGS AWAY DOWNWARD. Break+place ON (pinned).
     *  Planner-only. */
    private static void escapeFarthestNoRockDrill(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 18, top = ctx.origin().getY() + 40;   // legacy base 218 / top 240

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        final int footY = top - 4;
        level.setBlockAndUpdate(new BlockPos(cx, footY, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, footY + 1, cz), Blocks.AIR.defaultBlockState());

        BlockPos start = new BlockPos(cx, footY, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, footY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;

        PathFinder.Result r = new PathFinder(w, 200, 100, SearchProfile.NONE)
                .findPath(start, new Goal.Block(new BlockPos(cx, top + 2, cz)));
        int minY = r.hasPath() ? minPathY(r) : start.getY();
        WorldDriverCommon.LOG.info(
                "[wd.escapeFarthestNoRockDrill] goalReached={} hasPath={} pathLen={} minPathY={} startY={} expanded={}",
                r.goalReached(), r.hasPath(), r.hasPath() ? r.path().size() : 0, minY, start.getY(), r.expanded());
        if (!r.goalReached() && r.hasPath() && minY < start.getY() - 2)
            ctx.fail("escapeFarthestNoRockDrill: escape-farthest committed a downward rock drill (goal is ABOVE): minPathY=" + minY
                    + " startY=" + start.getY() + " pathLen=" + r.path().size());
    }

    /** Ported from {@code AgentGameTestBias#budgetAwayTunnelChurnArena}: gap#63. A budget-capped
     *  search from a chamber sealed in rock toward a goal ABOVE must not commit a best-effort segment
     *  that runs a walkable tunnel AWAY from the goal. GREEN = goal reached, a partial that does not
     *  end farther from the goal (small slack), or NO path. Break+place ON, walkerDebug ON (pinned).
     *  Planner-only. */
    private static void budgetAwayTunnelChurn(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 18, top = ctx.origin().getY() + 40;   // legacy base 218 / top 240

        for (int dx = -3; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        final int footY = top - 4;
        for (int dx = 0; dx <= 16; dx++) {           // chamber cell + away tunnel
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY, cz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY + 1, cz), Blocks.AIR.defaultBlockState());
        }

        BlockPos start = new BlockPos(cx, footY, cz);
        BlockPos goalPos = new BlockPos(cx, top + 2, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, footY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;   // STOP line names the committing branch

        PathFinder.Result r = new PathFinder(w, 200, 100, SearchProfile.NONE)
                .findPath(start, new Goal.Block(goalPos));
        double startDist = Math.sqrt(start.distSqr(goalPos));
        double endDist = r.hasPath()
                ? Math.sqrt(r.path().get(r.path().size() - 1).distSqr(goalPos))
                : startDist;
        WorldDriverCommon.LOG.info(
                "[wd.budgetAwayTunnelChurn] goalReached={} hasPath={} pathLen={} startDist={} endDist={} expanded={}",
                r.goalReached(), r.hasPath(), r.hasPath() ? r.path().size() : 0,
                String.format("%.1f", startDist), String.format("%.1f", endDist), r.expanded());
        if (!r.goalReached() && r.hasPath() && endDist > startDist + 2.0)
            ctx.fail("budgetAwayTunnelChurn: budget-capped partial path runs AWAY from the goal (the gap#63 drift-churn seed): "
                    + "startDist=" + String.format("%.1f", startDist)
                    + " endDist=" + String.format("%.1f", endDist)
                    + " pathLen=" + r.path().size());
    }

    // ---- private geometry helpers (faithful copies of the legacy AgentGameTestBias helpers) ----

    /** Inlined from {@code AgentGameTestSupport#maxPathY}: highest Y of any cell on the planned
     *  path (−1 for an empty path). */
    private static int maxPathY(PathFinder.Result r) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : r.path()) max = Math.max(max, p.getY());
        return r.path().isEmpty() ? -1 : max;
    }

    /** Euclidean cell-centre check: any path node within {@code rad} of the zone centre. */
    private static boolean pathEntersZone(PathFinder.Result r, double zx, double zy, double zz, double rad) {
        if (r == null || r.path() == null) return false;
        for (BlockPos p : r.path()) {
            double dx = (p.getX() + 0.5) - zx, dy = p.getY() - zy, dz = (p.getZ() + 0.5) - zz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < rad) return true;
        }
        return false;
    }

    /** Lowest Y of any cell on the planned path ({@code Integer.MAX_VALUE} for an empty path). */
    private static int minPathY(PathFinder.Result r) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : r.path()) min = Math.min(min, p.getY());
        return min;
    }

    /** Max Euclidean XZ distance of any planned node from the column centre (cx,cz) — the
     *  containment metric for {@link #columnRadius}, matching {@code ColumnRadius}'s cell-centre
     *  (x+0.5, z+0.5) convention. */
    private static double maxPathXZDist(PathFinder.Result r, double cx, double cz) {
        double max = 0;
        if (r == null || r.path() == null) return max;
        for (BlockPos p : r.path()) {
            double dx = (p.getX() + 0.5) - cx, dz = (p.getZ() + 0.5) - cz;
            max = Math.max(max, Math.sqrt(dx * dx + dz * dz));
        }
        return max;
    }

    /** Any planned path node whose foot cell is water — the water-crossing baseline check for
     *  {@link #forbidWater}. */
    private static boolean pathEntersWater(LevelWorldView w, PathFinder.Result r) {
        if (r == null || r.path() == null) return false;
        for (BlockPos p : r.path())
            if (w.isWater(p)) return true;
        return false;
    }

    /** First RAW-path node farther than {@code rad} (Chebyshev, own Y and one below) from any water —
     *  null when the whole path stays in the shoreline band. */
    private static BlockPos firstOutOfBand(PathFinder.Result r, int rad, LevelWorldView w) {
        return firstOutOfBandSmoothed(r.path(), rad, w);
    }

    /** Same, over an explicit waypoint list, checking the STRAIGHT LINES between consecutive
     *  smoothed waypoints cell by cell — a collapsed chord's violation lives between the endpoints,
     *  not at them. */
    private static BlockPos firstOutOfBandSmoothed(List<BlockPos> path, int rad, LevelWorldView w) {
        for (int i = 0; i < path.size(); i++) {
            BlockPos a = i == 0 ? path.get(i) : path.get(i - 1), b = path.get(i);
            int steps = Math.max(1, Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                BlockPos c = new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * t),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * t),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
                if (!distToWaterLE(c, rad, w)) return c;
            }
        }
        return null;
    }

    /** Lowest Z RELATIVE to {@code z0} (negative = south, toward the water) of any node on the path —
     *  {@code Integer.MAX_VALUE} for an empty path. */
    private static int minPathZRelative(PathFinder.Result r, int z0) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : r.path()) min = Math.min(min, p.getZ() - z0);
        return min;
    }

    /** Chebyshev 5x5-ring probe (rad=2) at the node's own Y and one below — the shoreline
     *  "close enough to the water" band check for {@link #shorelineHug}, looser than
     *  {@link ShorelineHug}'s own rad=1 tax-free adjacency probe. */
    private static boolean distToWaterLE(BlockPos p, int rad, LevelWorldView w) {
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = 0; dy >= -1; dy--)
                    if (w.isWater(p.offset(dx, dy, dz))) return true;
        return false;
    }
}
