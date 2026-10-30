package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugBlockingApi;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ReplayParams;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The Core family, headless half: the body's jump and travel physics, node facts, the path archive's record and replay,
 * and the two {@code mc.observe.player} surfaces. The client-face half is {@link CoreClientTest}.
 *
 * <p>The physics arenas pose a parked {@link SceneBot} and drive it by held keys and body steps only (no perceiving,
 * reflexes or task), each arm inside one {@code game.runOnServer}, so no world tick falls between a staging and the
 * measurement it is for. Arenas are built at absolute coordinates taken from the plot ({@code cx}, {@code cz},
 * {@code floorY}), so the old geometry carries over line for line.
 */
@Scene("wd.scenes")
class CoreTest {

    // ==================================================================================
    // Node facts, read through mc.debug.cells.
    // ==================================================================================

    /**
     * The pose-fit and hazard facts {@code mc.debug.cells} answers for a node: an open 2-high cell, a 1-high capped
     * pocket, lava underfoot, and the flips a planned dig ({@code toBreak}) and placement ({@code toPlace}) make to the
     * pose fit.
     */
    @Test
    void nodePhysics(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();
        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            // Cell 1: open 2-high stand cell — floor at floorY, air above.
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
            // Cell 2: 1-high capped pocket — floor + solid cap at floorY+2.
            level.setBlockAndUpdate(new BlockPos(cx + 2, floorY, cz), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + 2, cz), Blocks.STONE.defaultBlockState());
            // Cell 3: lava underfoot. Read in the same server task and taken out again, so it never flows.
            BlockPos lava = new BlockPos(cx + 4, floorY, cz);
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            try {
                BlockPos stand = new BlockPos(cx, floorY + 1, cz);
                BotDebugContracts.NodeFacts f = nodeFacts(game, stand, null, List.of(), List.of());
                if (!(f.fitStand() && "none".equals(f.ceilingForces())))
                    fail("nodePhysics: open cell should stand; fitStand=" + f.fitStand() + " ceilingForces="
                            + f.ceilingForces());

                BlockPos pocket = new BlockPos(cx + 2, floorY + 1, cz);
                BotDebugContracts.NodeFacts g = nodeFacts(game, pocket, stand, List.of(), List.of());
                if (!(!g.fitStand()
                        && ("crouch".equals(g.ceilingForces())
                                || "crawl".equals(g.ceilingForces())
                                || "suffocate".equals(g.ceilingForces()))))
                    fail("nodePhysics: 1-cap pocket should not stand; fitStand=" + g.fitStand() + " ceilingForces="
                            + g.ceilingForces());

                BotDebugContracts.NodeFacts h =
                        nodeFacts(game, new BlockPos(cx + 4, floorY + 1, cz), null, List.of(), List.of());
                if (!"lava".equals(h.footHazard()))
                    fail("nodePhysics: lava underfoot should be footHazard=lava; got=" + h.footHazard());

                // Edit-aware: the planned toBreak/toPlace change the pose-fit verdict.
                BlockPos cap = new BlockPos(cx + 2, floorY + 2, cz);
                BotDebugContracts.NodeFacts gBroke = nodeFacts(game, pocket, stand, List.of(cap), List.of());
                if (!(gBroke.fitStand() && "none".equals(gBroke.ceilingForces())))
                    fail("nodePhysics: pocket with toBreak{cap} should stand; fitStand=" + gBroke.fitStand()
                            + " ceilingForces=" + gBroke.ceilingForces());

                BotDebugContracts.NodeFacts fPlaced = nodeFacts(game, stand, null, List.of(), List.of(stand.above()));
                if (fPlaced.fitStand())
                    fail("nodePhysics: open cell with toPlace{head} should not stand; fitStand=" + fPlaced.fitStand());
            } finally {
                level.setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState());
            }
        });
    }

    /**
     * The facts {@code mc.debug.cells} answers for the node at {@code foot}, entered from {@code prev} (or nowhere),
     * with the entering edge's digs and placements done. Read from the server's overworld.
     */
    private static BotDebugContracts.NodeFacts nodeFacts(
            Game game, BlockPos foot, BlockPos prev, List<BlockPos> toBreak, List<BlockPos> toPlace) {
        BotDebugContracts.NodeQuery q = new BotDebugContracts.NodeQuery(
                Targets.pos(foot),
                Optional.ofNullable(prev).map(Targets::pos),
                Optional.empty(),
                toBreak.stream().map(Targets::pos).toList(),
                toPlace.stream().map(Targets::pos).toList());
        BotDebugContracts.CellsReading r = game.api(DebugBlockingApi::of)
                .cells(new BotDebugContracts.CellsParams(Optional.empty(), List.of(), Optional.of(q)));
        return r.node().orElseThrow(() -> new AssertionError("mc.debug.cells answered no node facts: " + r));
    }

    // ==================================================================================
    // Physics arenas driven by a parked bot's body step and held keys.
    // ==================================================================================

    /**
     * Physics parity of the server body: driven only by held keys and body steps, it must reproduce vanilla movement
     * (horizontal travel, a jumped +1 step-up, a standing-jump apex ~1.25).
     */
    @Test
    void physicsParity(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY(), standY = floorY + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        Settings.pin(game);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);
        double disp = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            bot.press(bot.keys().move(0, 0));
            bot.step(3);
            double startY = fp.getY(), z0 = fp.getZ();
            fp.setSprinting(true);
            bot.press(bot.keys().move(0, 1));
            for (int i = 0; i < 20; i++) {
                fp.setYRot(0f);
                bot.step();
            }
            bot.release();
            double d = fp.getZ() - z0;
            fp.setSprinting(false);
            if (d <= 2.0) fail("physicsParity: flat travel too small: dz=" + d + " (expected >2)");
            if (Math.abs(fp.getY() - startY) > 0.4)
                fail("physicsParity: the body left the floor: dy=" + (fp.getY() - startY));
            return d;
        });

        // 2) Standing jump apex ~1.25.
        SceneBot bot2 = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);
        double apex = game.onServer(s -> {
            ServerPlayer fp2 = bot2.player();
            bot2.step(3);
            double jy0 = fp2.getY(), maxY = jy0;
            for (int i = 0; i < 30; i++) {
                bot2.press(bot2.keys().jump(i == 0));
                bot2.step();
                maxY = Math.max(maxY, fp2.getY());
                if (i > 3 && fp2.onGround()) break;
            }
            double a = maxY - jy0;
            if (a < 1.0 || a > 1.5) fail("physicsParity: jump apex off: " + a + " (expected ~1.25)");
            return a;
        });

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump. Judged by the player STANDING on the
        //    block, not by where it is after a fixed tick count: a vanilla sprint-jump carries it over the one-block
        //    row and off the ±5 floor well inside 30 ticks.
        plot.fill(-1, 1, 3, 1, 1, 3, Blocks.STONE);
        SceneBot bot3 = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);
        game.runOnServer(s -> {
            ServerPlayer fp3 = bot3.player();
            bot3.step(3);
            double su0 = fp3.getY();
            double climbed = 0;
            fp3.setSprinting(true);
            for (int i = 0; i < 30 && climbed < 0.9; i++) {
                fp3.setYRot(0f);
                bot3.press(bot3.keys().move(0, 1).jump(fp3.onGround()));
                bot3.step();
                if (fp3.onGround()) climbed = Math.max(climbed, fp3.getY() - su0);
            }
            bot3.release();
            TestMod.LOG.info("[wd.physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
            if (climbed < 0.9) fail("physicsParity: jumped +1 step-up failed: climbed=" + climbed);
        });
    }

    /**
     * The negative half of the ground-jump gate: a player in mid-air that is asking to jump must not rise. Nothing in
     * the suite asserted this before, which is why the gate could be changed with no way to see the new one failing in
     * the permissive direction.
     *
     * <p>The lift is followed by ONE ungated tick before the watch begins, on purpose: {@code setPos} moves a player
     * without a {@code move()}, so vanilla's collision bookkeeping still describes where the player USED to be. Ten
     * ticks is a fall of about 4.9 blocks from +8, so every tick of the watch is a real airborne tick. The bound is
     * exact: a jump is +0.42 and gravity only subtracts, so ANY positive step is an impulse a mid-air player was given.
     */
    @Test
    void airborneJumpInert(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, -4, 0);
        final int cx = o.getX(), cz = o.getZ(), standY = o.getY() + 1;
        SceneArena.buildFloor(plot, 0, -4, 0);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.step(3);
            fp.setPos(cx + 0.5, standY + 8, cz + 0.5);
            fp.setDeltaMovement(Vec3.ZERO);
            bot.step();
            double prev = fp.getY(), worstRise = 0, riseAt = -1;
            for (int i = 0; i < 10; i++) {
                bot.press(bot.keys().jump(true)); // every tick: a step clears the press
                bot.step();
                double rise = fp.getY() - prev;
                if (rise > worstRise) {
                    worstRise = rise;
                    riseAt = i;
                }
                prev = fp.getY();
            }
            if (worstRise > 1.0E-9) {
                fail("airborneJumpInert: a mid-air player was given an upward impulse: worstRise=" + worstRise
                        + " at t=" + riseAt + " (jump held every tick, floor 8+ blocks below)");
            }
            if (fp.getY() >= standY + 8)
                fail("airborneJumpInert: the player never fell, so the watch proved nothing: y=" + fp.getY());
        });
    }

    /**
     * A player standing on solid rock beside — and then inside — a climbable must still be able to jump while the
     * navigator holds jump, and must not take further ground jumps from the hover the climbable rewrite holds it in.
     *
     * <p><b>The mechanism.</b> {@code LivingEntity.handleRelativeFrictionAndCalculateMovement} (1.21.1) rewrites the
     * post-move vertical component to {@code +0.2} whenever {@code (horizontalCollision || jumping) && onClimbable()};
     * {@code travel()}'s tail then leaves {@code +0.1176}. {@code jumping} is the jump input itself, so merely ASKING
     * for a jump arms that rewrite. Held jump on a ladder IS climbing: the player climbs out of the cell, falls a
     * fraction, re-enters and is pushed up again, hovering at the cell's ceiling — it never lands, so "jumped twice"
     * was a demand on physics, not on the driver.
     *
     * <ul>
     *   <li><b>adjacent</b> — ladder one cell to the side; {@code onClimbable()} reads the FEET cell, so a neighbour
     *       does not arm the rewrite: ≥ 2 ground jumps. A failure here is the arena, not the gate.
     *   <li><b>underfoot</b> — the ladder in the player's own cell: the first tick must rise ≥ 0.4 (standing in a
     *       climbable cell is still standing), and no later 0.42 from the hover (not footed). If it does come back to
     *       the floor it MUST jump again: that keeps the original self-lock detectable.
     * </ul>
     *
     * A single-tick rise {@code > 0.3} can only be the 0.42 ground jump: the rewrite tops out at 0.1176 per tick.
     */
    @Test
    void climbableGroundJump(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX(), cz = o.getZ(), standY = o.getY() + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            // ADJACENT: wall at dx+2, ladder at dx+1 facing away from it; the player's own cell stays air.
            level.setBlockAndUpdate(new BlockPos(cx + 2, standY, cz - 3), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz - 3), ladderFacingWest());
            // UNDERFOOT: wall at dx+1, ladder in the very cell the player stands in.
            level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz + 3), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx, standY, cz + 3), ladderFacingWest());
        });
        Settings.pin(game);
        SceneBot a = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz - 3 + 0.5);
        HeldJump adjacent = game.onServer(s -> heldJump(a, "adjacent"));
        SceneBot u = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 3 + 0.5);
        HeldJump underfoot = game.onServer(s -> heldJump(u, "underfoot"));
        String note = "adjacent=" + adjacent + " underfoot=" + underfoot + " floor=" + standY;

        if (adjacent.rises() < 2)
            fail("climbableGroundJump: adjacent arm jumped " + adjacent.rises()
                    + " time(s), expected >=2. A ladder one "
                    + "cell away must not reach the player at all (onClimbable reads the FEET cell) — so this is the "
                    + "arena or the jump gate, not the climbable path. " + note);
        if (!underfoot.climbable())
            fail(
                    "climbableGroundJump: the underfoot player is not on a climbable at all — the ladder did not place, or "
                            + "the player left its cell. This arm measured plain ground. " + note);
        if (underfoot.first() < 0.4)
            fail(
                    "climbableGroundJump: a player standing on rock inside a ladder cell did not take the 0.42 ground jump "
                            + "on its first held tick. Standing in a climbable cell is still standing; refusing every jump on a "
                            + "climbable is an over-correction, not a fix. " + note);
        boolean landedAgain = underfoot.minY() <= standY + 0.1;
        if (underfoot.later() > 0 && !landedAgain)
            fail("climbableGroundJump: the underfoot player launched " + underfoot.later() + " further ground jump(s) "
                    + "without ever returning to the floor. Hovering inside the climbable rewrite is not standing — the "
                    + "support test is answering for a player whose sole is flush against nothing. " + note);
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() >= 59)
            fail("climbableGroundJump: the underfoot player only returned to the floor on the last tick of the window, "
                    + "so no jump could follow it. Lengthen the window — this says nothing about the gate. " + note);
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() < 59)
            fail("climbableGroundJump: the underfoot player came back to the floor and never jumped again in the "
                    + (59 - underfoot.minTick()) + " tick(s) that followed. That is the ground gate self-locking on a "
                    + "climbable, the defect this arm exists to catch. Fix the gate, do not relax this arena. " + note);
    }

    /**
     * Water jump-threshold parity, held from both failure directions. Vanilla's rule ({@code LivingEntity.aiStep})
     * never asks about support first: with {@code g = getFluidHeight(WATER)} and {@code h = getFluidJumpThreshold()},
     * {@code g > h} sends the player to {@code jumpInLiquid} (+0.04) whether or not it stands on anything, and only
     * {@code onGround() || (inWater && g <= h)} reaches {@code jumpFromGround()} (0.42).
     *
     * <ul>
     *   <li><b>afloat</b> — five blocks of water over rock, released at the surface, jump held: bob-sized rises only. A
     *       support test widened to "solid somewhere below" makes this arm launch a 0.42.
     *   <li><b>bottomed</b> — a flowing level-3 state (height 3/9, under the 0.4 threshold) over rock: it must still
     *       make a 0.42. A fix that refused all jumps in water passes afloat and fails here. It was once staged on a
     *       source block, which is 8/9 and already over the threshold: the arm demanded a privilege no player has.
     *   <li><b>bottomedDeep</b> — afloat's pool, released on its BOTTOM: supported AND deeply submerged, where vanilla
     *       bobs; a controlled A/B once had the server player take +0.420 out of a two-deep swamp cell while the client
     *       player in the byte-identical cell took +0.035 (see {@code docs/dev/fake-player-parity.md}).
     * </ul>
     *
     * Each arm's staging is checked before its verdict, so an arena that never built the stance cannot be scored on it.
     * All water is placed and measured inside one server task: fluid ticks never run, so the level-3 state cannot
     * settle and the pools cannot spill before the arms read them.
     */
    @Test
    void buoyantJumpStaysABob(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, -4, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY(), standY = floorY + 1;
        SceneArena.buildFloor(plot, 0, -4, 0);
        Settings.pin(game);
        // Order is load-bearing: afloat and bottomed run first and in their original order.
        SceneBot afloatBot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY + 4, cz - 3 + 0.5);
        SceneBot bottomedBot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 3 + 0.5);
        SceneBot deepBot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz - 2 + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            // AFLOAT (and bottomedDeep one column over): a 5-deep pool.
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -5; dz <= -1; dz++)
                    for (int dy = 1; dy <= 5; dy++)
                        level.setBlockAndUpdate(
                                new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.WATER.defaultBlockState());
            // BOTTOMED: genuinely SHALLOW water, a flowing level-3 state under vanilla's 0.4 jump threshold.
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = 1; dz <= 5; dz++)
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, floorY + 1, cz + dz),
                            Fluids.FLOWING_WATER.getFlowing(3, false).createLegacyBlock());

            HeldJump afloatArm = heldJump(afloatBot, "afloat");
            HeldJump bottomedArm = heldJump(bottomedBot, "bottomed");
            HeldJump deepArm = heldJump(deepBot, "bottomedDeep");
            double threshold = deepArm.jumpThreshold();
            String note = "afloat=" + afloatArm + " bottomed=" + bottomedArm + " bottomedDeep=" + deepArm
                    + " jumpThreshold=" + threshold;

            // The instrument checking itself: vanilla fills the fluid-height cache and sets wasTouchingWater from one
            // call, so a non-zero height with the flag false at one instant is unreachable.
            for (HeldJump h : List.of(afloatArm, bottomedArm, deepArm))
                if (h.inWaterAtRest() != (h.fluidAtRest() > 0.0))
                    fail("buoyantJumpStaysABob: an arm reported inWaterAtRest=" + h.inWaterAtRest()
                            + " with fluidAtRest="
                            + h.fluidAtRest()
                            + ", a pair vanilla cannot produce at a single instant: the instrument, not "
                            + "the gate, is wrong. " + note);
            if (afloatArm.rises() > 0)
                fail("buoyantJumpStaysABob: a player floating in deep water launched " + afloatArm.rises() + " ground "
                        + "jump(s). Afloat is not standing: the support test must read the row the sole SITS on, not "
                        + "'solid somewhere below'. " + note);
            if (!(bottomedArm.fluidAtRest() > 0.0 && bottomedArm.fluidAtRest() <= threshold))
                fail("buoyantJumpStaysABob/bottomed: this arm needs water BELOW vanilla's jump threshold; above it "
                        + "vanilla bobs, so demanding a 0.42 would demand a privilege no player has. The arena, not the "
                        + "gate, is wrong. " + note);
            if (bottomedArm.rises() < 1)
                fail("buoyantJumpStaysABob: a player resting on rock in water shallower than vanilla's jump threshold "
                        + "never jumped. Below the threshold vanilla reaches jumpFromGround, so the 0.42 is required here; "
                        + "refusing every jump in water is an over-correction, not a fix. " + note);
            if (Math.abs(deepArm.restY() - standY) > 1.0E-6)
                fail("buoyantJumpStaysABob/bottomedDeep: the player did not come to rest on the pool floor before the "
                        + "jump was held (expected restY " + standY
                        + "); a player that floated here duplicates afloat. "
                        + "The arena, not the gate, is wrong. " + note);
            if (deepArm.fluidAtRest() <= threshold)
                fail("buoyantJumpStaysABob/bottomedDeep: the player was not deep enough for the question to exist. The "
                        + "arena, not the gate, is wrong. " + note);
            if (deepArm.rises() > 0)
                fail("buoyantJumpStaysABob/bottomedDeep: a player standing on the bottom of deep water launched "
                        + deepArm.rises()
                        + " ground jump(s). Above the threshold vanilla takes jumpInLiquid (+0.04) and "
                        + "never reaches jumpFromGround: support does not enter the decision until the water is "
                        + "shallower than the threshold. That is the server player being MORE permissive than a real "
                        + "player: see docs/dev/fake-player-parity.md. " + note);
        });
    }

    /** A ladder hung on a wall to its EAST (so it faces west). */
    private static BlockState ladderFacingWest() {
        return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
    }

    /**
     * What one arm of a held-jump arena measured. {@code first} and {@code later} are kept apart because they answer
     * opposite questions: whether a jump is ALLOWED from the starting stance, and whether one is allowed from a stance
     * reached by moving. {@code minY} (over the ticks AFTER the first jump — seeding it with the start would make "came
     * back to the floor" true before a tick ran) keeps a silent arm honest. Every field names the instant it was taken
     * at: {@code inWaterAtRest} and {@code fluidAtRest} are one fact read in one breath before the loop,
     * {@code inWaterAtEnd} where 60 ticks of holding jump left it. {@code fluidAtRest} is vanilla's own input to the
     * water jump rule, a submersion depth of the player (its own two rows), not a depth of the water.
     */
    private record HeldJump(
            double first,
            int later,
            double minY,
            int minTick,
            boolean climbable,
            boolean inWaterAtRest,
            boolean inWaterAtEnd,
            double fluidAtRest,
            double restY,
            double jumpThreshold) {
        int rises() {
            return (first > 0.3 ? 1 : 0) + later;
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "{rises=%d first=%.4f later=%d minY=%.4f minTick=%d climbable=%s "
                            + "inWaterAtRest=%s inWaterAtEnd=%s fluidAtRest=%.4f restY=%.4f}",
                    rises(),
                    first,
                    later,
                    minY,
                    minTick,
                    climbable,
                    inWaterAtRest,
                    inWaterAtEnd,
                    fluidAtRest,
                    restY);
        }
    }

    /**
     * Hold jump for 60 ticks and measure the single-tick rises only a 0.42 ground jump can produce (the buoyant bob
     * adds 0.04 and the climbable rewrite tops out at 0.1176, so 0.3 separates them). Server thread.
     */
    private static HeldJump heldJump(SceneBot bot, String arm) {
        ServerPlayer fp = bot.player();
        // Three settle steps: the body's baseTick runs inside the step, which is what keeps getFluidHeight live.
        bot.step(3);
        double fluidAtRest = fp.getFluidHeight(FluidTags.WATER);
        double restY = fp.getY();
        boolean inWaterAtRest = fp.isInWater();
        StringBuilder head = new StringBuilder();
        double prev = fp.getY(), first = 0.0, minY = Double.POSITIVE_INFINITY;
        int later = 0, minTick = -1;
        for (int i = 0; i < 60; i++) {
            // Held: a step clears a jump press out of water, so it is pressed again each step.
            bot.press(bot.keys().jump(true));
            bot.step();
            double rise = fp.getY() - prev;
            if (i == 0) first = rise;
            else if (rise > 0.3) later++;
            if (i > 0 && fp.getY() < minY) {
                minY = fp.getY();
                minTick = i;
            }
            if (i < 10) head.append(String.format(Locale.ROOT, " t%d:y=%.4f dy=%.4f", i, fp.getY(), rise));
            prev = fp.getY();
        }
        // Vanilla's own threshold, read off the player: it is derived from eye height.
        HeldJump out = new HeldJump(
                first,
                later,
                minY,
                minTick,
                fp.onClimbable(),
                inWaterAtRest,
                fp.isInWater(),
                fluidAtRest,
                restY,
                fp.getFluidJumpThreshold());
        TestMod.LOG.info("[held-jump] {} {}{}", arm, out, head);
        return out;
    }

    /**
     * The ground jump waits for {@code onGround}, as vanilla's does, and a refused press costs one step.
     * {@code onGround} means "the move I asked for last was downward and got clipped"; {@code setOnGround(false)}
     * reproduces a flush landing directly. Two presses: the first is refused and the player does not rise; its own move
     * clips the next bit of fall, so the bit is true again and the second press jumps. A player that jumps on the first
     * press reads something other than {@code onGround}; one that refuses the second holds a cooldown a refused press
     * must not start.
     */
    @Test
    void jumpWaitsForOnGround(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX(), cz = o.getZ(), standY = o.getY() + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.step(3);
            double y0 = fp.getY();
            if (Math.abs(y0 - standY) > 1.0E-6)
                fail("jumpWaitsForOnGround: the player did not settle flush on the floor: y=" + y0 + " (expected "
                        + standY + ") — the arena, not the gate, is wrong");
            fp.setOnGround(false);
            bot.press(bot.keys().jump(true));
            bot.step();
            double refused = fp.getY() - y0;
            boolean groundAfterRefusal = fp.onGround();
            bot.press(bot.keys().jump(true)); // the step cleared the first press
            bot.step();
            double jumped = fp.getY() - y0;
            if (Math.abs(refused) > 1.0E-6)
                fail("jumpWaitsForOnGround: onGround was false and the player still rose: rise=" + refused
                        + " — the jump is not reading vanilla's gate");
            if (jumped < 0.3)
                fail("jumpWaitsForOnGround: the press after the refused one did not jump: rise=" + jumped
                        + " onGroundAfterRefusal=" + groundAfterRefusal + " (a ground jump is +0.42)");
        });
    }

    // ==================================================================================
    // Path-archive record / replay round trip.
    // ==================================================================================

    /** Where the recorder writes, relative to the server's working directory. */
    private static final Path REPLAYS = Path.of("config", "worlddriver", "replays");

    private static final String PLAN_ARCHIVE = "replay-", REPLAY_RUN = "replay-run-";

    /**
     * End-to-end proof of record → replay: record a goto archive (segments, and the header's dimension), replay its
     * stored plan on a fresh bot through {@code mc.debug.replay}, and assert the bot reaches the same goal (±2 XZ) with
     * bounded per-tick deviation. The walks are ticked by the test; the recorder writes each archive on a thread of its
     * own after the run ends, so those two waits are real.
     */
    @Test
    void replayRoundTrip(Game game, @Plot.Centered @Plot.Size(x = 48, y = 16, z = 16) Plot plot) {
        BlockPos o = plot.at(-10, 0, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();
        plot.fill(-12, 0, -3, 10, 0, 3, Blocks.STONE);
        Settings.pinUntimed(game)
                .set("pathArchive", true)
                .set("allowBreak", false)
                .set("allowPlace", false);
        BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);

        // Phase 1: RECORD a real goto from A to B.
        Set<String> plansBefore = archives(PLAN_ARCHIVE);
        SceneBot recorder = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);
        String recorded = game.onServer(s -> {
            recorder.goTo(Targets.block(goal));
            int ticks = recorder.tickUntilIdle(1000);
            return "end=" + recorder.gotoSlot().endReason().orElse("-") + " ticks=" + ticks + " arrival="
                    + recorder.player().blockPosition().toShortString();
        });
        Archive plan = awaitArchive(game, PLAN_ARCHIVE, plansBefore);
        if (size(plan.json(), "segments") < 1)
            fail("replayRoundTrip: plan archive has no segments; record " + recorded);
        // Folded in from the old wd.pathArchiveCapture: the header names the dimension the plan was made in.
        JsonElement header = plan.json().get("header");
        game.check(
                header != null
                        && header.isJsonObject()
                        && header.getAsJsonObject().has("dimension"),
                "the plan archive's header names its dimension: " + header);

        // Phase 2: REPLAY the stored plan on a fresh bot, which the replay places at the start. The capture is pinned
        // to the replaying bot, so the recorder standing at the goal cannot leak samples into the deviation gate.
        Set<String> runsBefore = archives(REPLAY_RUN);
        SceneBot replayer = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);
        game.runOnServer(s -> {
            replayer.debug().replay(ReplayParams.plan(replayer.ref(), plan.file()));
            int maxTicks = 1500;
            int rt = replayer.tickUntilIdle(maxTicks);
            BlockPos arrival = replayer.player().blockPosition();
            String walk = "end=" + replayer.gotoSlot().endReason().orElse("-") + " ticks=" + rt + " arrival="
                    + arrival.toShortString();
            if (rt >= maxTicks)
                fail("replayRoundTrip: replay did not terminate within " + maxTicks + " ticks: " + walk);
            int dgx = Math.abs(arrival.getX() - goal.getX()), dgz = Math.abs(arrival.getZ() - goal.getZ());
            if (!(dgx <= 2 && dgz <= 2))
                fail("replayRoundTrip: replay arrival not within +/-2 XZ of goal " + goal.toShortString() + ": "
                        + walk);
        });

        Archive run = awaitArchive(game, REPLAY_RUN, runsBefore);
        String kind = run.json().has("kind") ? run.json().get("kind").getAsString() : null;
        assertEquals("replay", kind, "replayRoundTrip: the replay-run archive's kind");
        if (size(run.json(), "trajectory") == 0) fail("replayRoundTrip: replay-run trajectory is empty");
        double maxDev = 0.0;
        for (JsonElement tk : run.json().getAsJsonArray("trajectory")) {
            JsonElement d = tk.getAsJsonObject().get("deviation");
            if (d != null && !d.isJsonNull()) maxDev = Math.max(maxDev, d.getAsDouble());
        }
        if (maxDev > 4.0) fail("replayRoundTrip: max per-tick deviation " + maxDev + " exceeds bound 4.0");
    }

    private record Archive(String file, JsonObject json) {}

    /** The archive files under {@link #REPLAYS} of one kind, as they stand now. */
    private static Set<String> archives(String kind) {
        if (!Files.isDirectory(REPLAYS)) return Set.of();
        try (Stream<Path> files = Files.list(REPLAYS)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json")
                            && n.startsWith(kind)
                            && (kind.equals(REPLAY_RUN) || !n.startsWith(REPLAY_RUN)))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /** The archive of {@code kind} written since {@code before} was listed, once it reads as whole JSON. */
    private static Archive awaitArchive(Game game, String kind, Set<String> before) {
        AtomicReference<Archive> found = new AtomicReference<>();
        game.await().within(200).as("a " + kind + "*.json archive is written").until(() -> {
            for (String n : archives(kind)) {
                if (before.contains(n)) continue;
                try {
                    found.set(new Archive(
                            n,
                            JsonParser.parseString(Files.readString(REPLAYS.resolve(n)))
                                    .getAsJsonObject()));
                    return true;
                } catch (Exception partial) {
                    return false;
                }
            }
            return false;
        });
        return found.get();
    }

    /** The length of {@code key}'s array in {@code o}, 0 when it is absent. */
    private static int size(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key).size() : 0;
    }

    // ==================================================================================
    // mc.observe.player surfaces.
    // ==================================================================================

    /**
     * All 36 inventory slots reach {@code mc.observe.player}, not just the hotbar — the #41 permanent assertion. The
     * bug it exists for showed 9 slots of 36: the hotbar, and none of the three quarters behind it. So the staging is
     * entirely in the MAIN inventory, and each slot is asserted by id and count rather than by a total, which would
     * pass with the items in the wrong places. On a joined server bot rather than the old real player: the verb reads
     * any player in the player list by name.
     */
    @Test
    void fullInventoryVisible(Game game, @Plot.Centered Plot plot) {
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        String name = game.onServer(s -> {
            ServerPlayer p = bot.player();
            p.getInventory().items.set(9, new ItemStack(Items.DIAMOND, 5));
            p.getInventory().items.set(20, new ItemStack(Items.EMERALD, 7));
            p.getInventory().items.set(35, new ItemStack(Items.GOLD_INGOT, 3));
            return p.getGameProfile().getName();
        });
        Map<String, Object> obs = Calls.byName(game.registry(), "mc.observe.player", Map.of("name", name));
        Object rows = obs.get("player") instanceof Map<?, ?> p ? p.get("inventory") : null;
        assertTrue(rows instanceof List<?>, "observe.player carries an inventory list: " + obs);
        Map<Integer, Map<?, ?>> bySlot = new LinkedHashMap<>();
        for (Object row : (List<?>) rows)
            if (row instanceof Map<?, ?> e && e.get("slot") instanceof Number n) bySlot.put(n.intValue(), e);
        expectSlot(game, bySlot, 9, "minecraft:diamond", "5");
        expectSlot(game, bySlot, 20, "minecraft:emerald", "7");
        expectSlot(game, bySlot, 35, "minecraft:gold_ingot", "3");
    }

    /** One staged slot, named by number so a regression says which quarter of the bag went missing. */
    private static void expectSlot(Game game, Map<Integer, Map<?, ?>> bySlot, int slot, String id, String count) {
        Map<?, ?> e = bySlot.get(slot);
        game.check(e != null, "main-inventory slot " + slot + " is listed (the 9-of-36 bug): " + bySlot.keySet());
        if (e == null) return;
        game.check(() -> assertEquals(id, String.valueOf(e.get("id")), "slot " + slot + " id"));
        game.check(() -> assertEquals(count, String.valueOf(e.get("count")), "slot " + slot + " count"));
    }

    /**
     * The melee attack-cooldown surface exists, reads consistently at idle, and follows the weapon — the #45 permanent
     * assertion. The cooldown {@code CombatProcess} gates every swing on used to reach the agent as zero bytes, so what
     * is asserted is the SURFACE: the four fields are present, an idle read is internally consistent, and
     * {@code fullCooldownTicks} is derived from the held weapon's attack-speed attribute rather than a constant. That
     * {@code strengthScale} dips right after a swing is deliberately not asserted: racy against the ~5-tick bare-hand
     * recharge. The bot is unparked so the server ticks its body, as the old real player's connection did.
     */
    @Test
    void attackCooldownSurface(Game game, @Plot.Centered Plot plot) {
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        String name = game.onServer(s -> bot.player().getGameProfile().getName());
        bot.unpark();

        game.await()
                .within(60)
                .as("the bare-hand bar reads ready")
                .until(() -> Boolean.TRUE.equals(attackSnap(game, name).get("ready")));
        Map<?, ?> snap = attackSnap(game, name);
        for (String k : new String[] {"strengthScale", "ready", "cooldownTicks", "fullCooldownTicks"})
            game.check(snap.containsKey(k), "AttackSnap carries " + k + ": " + snap);
        game.check(() -> assertEquals("0", String.valueOf(snap.get("cooldownTicks")), "idle cooldownTicks"));
        game.check(
                snap.get("strengthScale") instanceof Number n && n.doubleValue() >= 1.0,
                "idle strengthScale is fully recharged: " + snap);
        int bare = snap.get("fullCooldownTicks") instanceof Number n ? n.intValue() : -1;
        // A live attribute read, not a hardcoded constant: bare-hand attack speed 4.0/s means ceil(20/4) = 5 ticks.
        game.check(() -> assertEquals(5, bare, "bare-hand fullCooldownTicks (attack speed 4.0 -> ceil(20/4))"));

        game.runOnServer(s -> {
            ServerPlayer p = bot.player();
            p.getInventory().items.set(p.getInventory().selected, new ItemStack(Items.NETHERITE_SWORD));
        });
        game.await()
                .within(60)
                .as("a slower weapon lengthens fullCooldownTicks")
                .until(() ->
                        attackSnap(game, name).get("fullCooldownTicks") instanceof Number n && n.intValue() > bare);
    }

    /** {@code mc.observe.player}'s AttackSnap for one named player, or empty when absent. */
    private static Map<?, ?> attackSnap(Game game, String name) {
        Map<String, Object> obs = Calls.byName(game.registry(), "mc.observe.player", Map.of("name", name));
        Object attack = obs.get("player") instanceof Map<?, ?> p ? p.get("attack") : null;
        return attack instanceof Map<?, ?> a ? a : Map.of();
    }
}
