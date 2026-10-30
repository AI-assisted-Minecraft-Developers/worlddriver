package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.Routes;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The navigator's and the server body's whole-walk measurements on a headless bot: diagonal ascent speed, the descent
 * yaw-thrash gauge, the self-shaft dig-up, the gear scope, the buried ore and the entity leash.
 *
 * <p>Each arena is built at absolute coordinates taken from its plot ({@code cx}, {@code cz}, {@code floorY}), so the
 * old geometry carries over line for line. A walk runs inside one {@code game.runOnServer}: the bot is ticked by the
 * test alone, with no world tick between its ticks, as the old scene body ran. The old fixed origin slots of
 * {@code descentYaw} and {@code selfShaftDigUp} only served goldens nothing asserts (the ceilings have ~30% margin);
 * they are gone, and the goldens below are where the old runs measured them.
 */
@Scene("wd.scenes")
class WorldDriverTest {

    /**
     * A goto up a 45° slope must top out at no less than 2.5 b/s. Baseline 3.04 b/s (partial sprint; forcing more
     * sprint rams the diagonal corner — A/B-disproven 2026-06-20); the floor guards against a real collapse below it.
     * Golden on both loaders: {@code diagBps=3.00 ascSprint%=43 ascHcol%=5 ascTicks=149}.
     */
    @Test
    void diagonalAscentSpeed(Game game, @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        BlockPos o = plot.at(10, 2, 10);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int baseY = o.getY();
        final int steps = 8;
        final int span = 2 * steps; // dx,dz 0..16
        final int topSurf = baseY + 1 + span; // NE corner surface
        BlockPos goal = new BlockPos(cx + span, topSurf + 1, cz + span);
        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            // Flat NE run-up SW of the slope (surface baseY → walk baseY+1).
            for (int dx = -8; dx <= -1; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Diagonal slope: surface = baseY+1 + (dx+dz)/2 — rises +1 every 2 NE blocks.
            for (int dx = 0; dx <= span; dx++) {
                for (int dz = 0; dz <= span; dz++) {
                    int surf = baseY + 1 + (dx + dz) / 2;
                    for (int y = baseY; y <= surf; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        });
        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx - 7 + 0.5, baseY + 1, cz + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            // Measure horizontal (diagonal) progress + sprint% while ON the slope (x >= cx).
            double startX = Double.NaN;
            double startZ = 0;
            double endX = 0;
            double endZ = 0;
            int ascTicks = 0;
            int ascSprint = 0;
            int ascHcol = 0;
            boolean walking = true;
            for (int t = 0; t < 900 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                if (fp.getX() >= cx) {
                    if (Double.isNaN(startX)) {
                        startX = fp.getX();
                        startZ = fp.getZ();
                    }
                    endX = fp.getX();
                    endZ = fp.getZ();
                    ascTicks++;
                    if (fp.isSprinting()) {
                        ascSprint++;
                    }
                    if (fp.horizontalCollision) {
                        ascHcol++;
                    }
                }
            }
            double dist = Double.isNaN(startX)
                    ? 0
                    : Math.sqrt((endX - startX) * (endX - startX) + (endZ - startZ) * (endZ - startZ));
            double ascBps = ascTicks > 0 ? dist / (ascTicks * 0.05) : 0;
            int ascSprintPct = ascTicks > 0 ? 100 * ascSprint / ascTicks : 0;
            int ascHcolPct = ascTicks > 0 ? 100 * ascHcol / ascTicks : 0;
            String step = NavWalk.step(bot);
            boolean reachedTop =
                    fp.getY() >= topSurf + 1 - 0.6 && fp.getX() > cx + span - 2.5 && fp.getZ() > cz + span - 2.5;
            String reading = String.format(
                    Locale.ROOT,
                    "step=%s pos=(%.1f,%.1f,%.1f) diagBps=%.2f ascSprint%%=%d " + "ascHcol%%=%d ascTicks=%d",
                    step,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    ascBps,
                    ascSprintPct,
                    ascHcolPct,
                    ascTicks);
            TestMod.LOG.info("[wd.diagonalAscentSpeed] {} reachedTop={}", reading, reachedTop);
            if (!reachedTop) {
                fail("diagonalAscentSpeed: did not top out: " + reading);
            }
            if (ascBps < 2.5) {
                fail("diagonalAscentSpeed: diagonal ascent collapsed to " + ascBps + " b/s: " + reading);
            }
        });
    }

    /**
     * The DIAGONAL DESCENT yaw-thrash gauge (the live symptom: the bot spinning while walking downhill): a 45°
     * staircase descends −2 every diagonal step; the bot walks down it and the loop sums total {@code |Δyaw|} over the
     * descent (a steady heading sums to about the one initial turn; a carrot-chase winds up hundreds of degrees).
     * Asserts reached-bottom, {@code sumAbsDyaw} under a 1200° ceiling and backward hops under 90. The yaw metric is
     * double-precision-physics-sensitive: after any harness change that touches tick ordering, IO or scheduling, a
     * shifted reading here is the first alarm that determinism broke. Golden on both loaders, isolated player:
     * {@code sumAbsDyaw=871° backSteps=53}.
     */
    @Test
    void descentYaw(Game game, @Plot.Size(x = 48, y = 48, z = 48) Plot plot) {
        BlockPos o = plot.at(8, 44, 4);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int topY = o.getY();
        final int steps = 9;
        final int span = 2 * steps; // dx,dz 0..18
        final int goalSurf = topY - 2 * span; // NE corner surface (surf = topY-(dx+dz))
        BlockPos goal = new BlockPos(cx + span, goalSurf + 1, cz + span);
        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            // Flat start pad at the SW (high) corner.
            for (int dx = -6; dx <= 0; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, topY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Diagonal slope DESCENDING NE, STEEP: surface = topY - (dx+dz) (-2 every diagonal step) so the bot drops
            // fast — that speed is what makes the close-node bearing sweep (the carrot chase). dx/dz are CLAMPED to
            // span so the plane continues FLAT past the slope for 8 cells (run-out plateau): without it the
            // sprint-momentum zigzag walked off the strip and the metric became a fall-timing lottery.
            for (int dx = 0; dx <= span + 8; dx++) {
                for (int dz = 0; dz <= span + 8; dz++) {
                    int surf = topY - (Math.min(dx, span) + Math.min(dz, span));
                    for (int y = surf - 3; y <= surf; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        });
        // The yaw metric is config-sensitive: the pin snapshots every setting so no leaked flag can shift it.
        Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, topY + 1, cz + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            double prevYaw = Double.NaN;
            double sumAbsDyaw = 0;
            double maxDyaw = 0;
            int onSlope = 0;
            int reversals = 0;
            double lastSign = 0;
            // Backward hops: the goal is the NE corner, so every tick's net horizontal motion should project >= 0
            // onto the NE direction; a tick that projects negative drove AWAY from the goal.
            final double gdx = 1.0 / Math.sqrt(2.0);
            final double gdz = 1.0 / Math.sqrt(2.0);
            double prevX = fp.getX();
            double prevZ = fp.getZ();
            int backSteps = 0;
            double worstBack = 0;
            boolean walking = true;
            for (int t = 0; t < 700 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                double ddx = fp.getX() - prevX;
                double ddz = fp.getZ() - prevZ;
                if (fp.getX() >= cx && ddx * ddx + ddz * ddz > 1e-4) { // moved, on the slope
                    double proj = ddx * gdx + ddz * gdz;
                    if (proj < -0.02) {
                        backSteps++;
                        worstBack = Math.min(worstBack, proj);
                    }
                }
                prevX = fp.getX();
                prevZ = fp.getZ();
                if (fp.getX() >= cx) {
                    double yaw = fp.getYRot();
                    if (!Double.isNaN(prevYaw)) {
                        double d = Mth.wrapDegrees(yaw - prevYaw);
                        sumAbsDyaw += Math.abs(d);
                        if (Math.abs(d) > maxDyaw) {
                            maxDyaw = Math.abs(d);
                        }
                        if (Math.abs(d) > 2) {
                            double sg = Math.signum(d);
                            if (lastSign != 0 && sg != lastSign) {
                                reversals++;
                            }
                            lastSign = sg;
                        }
                    }
                    prevYaw = yaw;
                    onSlope++;
                }
            }
            // The lower y bound matters: a bot that fell off the strip still crossed the corner thresholds mid-air.
            String step = NavWalk.step(bot);
            boolean reached = fp.getX() > cx + span - 3
                    && fp.getZ() > cz + span - 3
                    && fp.getY() <= goalSurf + 2
                    && fp.getY() >= goalSurf - 1;
            String reading = String.format(
                    Locale.ROOT,
                    "step=%s pos=(%.1f,%.1f,%.1f) sumAbsDyaw=%.0f° maxDyaw=%.0f° "
                            + "reversals=%d onSlope=%d backSteps=%d worstBack=%.2f",
                    step,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    sumAbsDyaw,
                    maxDyaw,
                    reversals,
                    onSlope,
                    backSteps,
                    worstBack);
            TestMod.LOG.info("[wd.descentYaw] {} reached={}", reading, reached);
            if (!reached) {
                fail("descentYaw: did not reach the bottom: " + reading);
            }
            // The ceiling is over the unsolved carrot-swing baseline, not a smoothness pass: a regression gate.
            if (sumAbsDyaw > 1200) {
                fail("descentYaw: yaw thrash blew up (baseline 871°): " + reading);
            }
            if (backSteps > 90) {
                fail("descentYaw: backward hops regressed (baseline 53): " + reading);
            }
        });
    }

    /**
     * The gap #53 self-shaft dig-up: a bare-hand climb to a level 22 up from a sealed chamber must not fall back down
     * the hollow column it digs and pillars behind itself. Near the top A* used to re-plan a {@code parkourAscend2}
     * leap from the 1-wide pillar top onto the slab; a stationary pillar top has no run-up, so the executor launched
     * into the void and fell ~20 blocks down its own column. {@code pathfinderParkourAscendNeedRunway} (default ON)
     * makes A* reject the runway-less leap. Post-fix golden: {@code worstBackslide=1.2522034151012633} (the normal
     * pillar-jump arc); the bound is {@code pathfinderMaxDryFall + 1}, and a shaft fall blows it by fifteen.
     */
    @Test
    void selfShaftDigUp(Game game, @Plot.Size(x = 16, y = 32, z = 16) Plot plot) {
        BlockPos o = plot.at(8, 1, 8);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int baseY = o.getY();
        final int top = baseY + 20;
        final int targetY = top + 2;
        // Solid 7x7 stone slab base..top, air above, sealed 2-high chamber at the centre.
        plot.fill(5, 1, 5, 11, 21, 11, Blocks.STONE);
        plot.fill(8, 2, 8, 8, 3, 8, Blocks.AIR);
        Settings settings = Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("navigatorDebug", true);
        int maxDryFall = settings.integer("pathfinderMaxDryFall");
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, baseY + 1, cz + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64)); // pillar/plug stock; NO pickaxe (live parity)
            fp.getInventory().selected = 0;
            bot.goTo(Targets.level(targetY));
            double maxY = fp.getY();
            double worstBackslide = 0;
            boolean walking = true;
            for (int t = 0; t < 4000 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
                worstBackslide = Math.max(worstBackslide, maxY - fp.getY());
            }
            String reading = "step=" + NavWalk.step(bot) + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") maxY=" + maxY + " worstBackslide=" + worstBackslide;
            TestMod.LOG.info("[wd.selfShaftDigUp] {}", reading);
            if (worstBackslide > maxDryFall + 1) {
                fail("selfShaftDigUp: dig-up FELL back down its own shaft (> maxDryFall+1=" + (maxDryFall + 1)
                        + ") — the gap #53 death, reproduced: " + reading);
            }
            if (fp.getY() < targetY - 1.5) {
                fail("selfShaftDigUp: did not reach the level " + targetY + ": " + reading);
            }
        });
    }

    /**
     * The gap #46 gear scope: how much of a server body's held and worn gear is inert. A bare fist against an iron
     * sword on a fresh NoAI zombie ({@link SimProbes#probeSwing}), a fixed 10-point hit bare against full diamond
     * ({@link SimProbes#probeHurt}). Asserts sword damage ≥ 3× fist, ATTACK_SPEED 1.6 and ATTACK_DAMAGE 6.0 — outcomes,
     * not mirrored attributes: reading back the attribute a fix writes proves only that the fix calls its own API; a
     * zombie losing more health to a sword than to a fist is what an agent pays for. Golden: {@code bareHand=0.94000053
     * ironSword=5.9040003}, full-diamond {@code ARMOR=20.0}.
     */
    @Test
    void gearScope(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        plot.fill(-2, 0, -2, 4, 0, 2, Blocks.STONE);
        Settings.pin(game).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);

        float bare = SimProbes.probeSwing(game, bot, ItemStack.EMPTY, cx, floorY, cz);
        float sword = SimProbes.probeSwing(game, bot, new ItemStack(Items.IRON_SWORD), cx, floorY, cz);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            float tookBare = SimProbes.probeHurt(fp, false);
            float tookArmored = SimProbes.probeHurt(fp, true);

            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            fp.getInventory().selected = 0;
            bot.step(); // the gear must land through the NORMAL tick, not a special API
            double atk = fp.getAttributeValue(Attributes.ATTACK_DAMAGE);
            double spd = fp.getAttributeValue(Attributes.ATTACK_SPEED);

            // Is the body hurtable at all? Measured rather than assumed: an invulnerable body makes "armor does
            // nothing" moot and the blast radius offense-only.
            fp.getInventory().clearContent();
            fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
            bot.step();
            String reading = "dealt bare=" + bare + " sword=" + sword + "; taken (10pt) bare=" + tookBare
                    + " fullDiamond=" + tookArmored + "; holding the sword ATTACK_DAMAGE=" + atk + " ATTACK_SPEED="
                    + spd + "; wearing diamond ARMOR=" + fp.getAttributeValue(Attributes.ARMOR) + " invulnerable="
                    + fp.isInvulnerableTo(fp.damageSources().generic());
            TestMod.LOG.warn("[wd.gearScope] {}", reading);
            if (bare <= 0f) {
                fail("gearScope: rig broken: a bare-handed swing dealt no damage at all: " + reading);
            }
            // Vanilla: fist = 1 damage, iron sword = 6; measured 0.94 vs 0.94 before the fix.
            if (sword < bare * 3.0f) {
                fail("gearScope: an iron sword deals no more than a bare fist: the body's held item never reaches "
                        + "its attributes, so server-mode melee swings a weapon it does not benefit from: " + reading);
            }
            if (Math.round(spd * 10) != 16) {
                fail("gearScope: ATTACK_SPEED with an iron sword should be 1.6 — CombatProcess would pace its swings "
                        + "by the wrong weapon: " + reading);
            }
            // 1.21 iron sword = 6 total attack damage (1.0 player base + a +5 modifier).
            if (Math.abs(atk - 6.0) > 0.001) {
                fail("gearScope: ATTACK_DAMAGE with an iron sword should be 6.0: " + reading);
            }
        });
    }

    /**
     * The gap #60 buried ore: an IRON_ORE fully encased in harvestable stone has no standable adjacent cell, so the
     * geometric stand test alone rejected it and {@code mc.bot.mine} aborted "no reachable target", although the bot
     * holds a pickaxe and the navigator digs tunnels for every other verb. Reachability through diggable cover is A*'s
     * job, not a pre-filter's.
     *
     * <p>Two halves, and only the first is the pre-filter's: (1) it hands the navigator a DIG GOAL (one
     * {@code [pathfinder] search-begin owner=mine} line), and (2) the navigator CLIMBS the staircase it digs into the
     * cube. Half 2's green rode a defect until 2026-08-18 (a ground jump taken off a support already gone; gating the
     * jump on the player's own sole removed it). So a red is ambiguous by construction and the two readings want
     * opposite fixes: it is not evidence the pre-filter regressed unless the {@code search-begin} line is missing. Do
     * not "fix" a red by relaxing this assertion, pre-cutting the staircase or restoring the jump.
     */
    @Test
    void buriedOre(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        plot.fill(-1, 0, -2, 9, 0, 2, Blocks.DIRT);
        plot.fill(3, 1, -1, 7, 3, 1, Blocks.STONE);
        BlockPos ore = new BlockPos(cx + 5, floorY + 2, cz);
        plot.place(5, 2, 0, Blocks.IRON_ORE);
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            ServerPlayer fp = bot.player();
            // Stone pickaxe harvests iron_ore AND digs the stone cover.
            fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            fp.getInventory().selected = 0;
            bot.mine(List.of("minecraft:iron_ore"), 1, 16);
            bot.tickUntilIdle(800);

            boolean oreMined = !level.getBlockState(ore).is(Blocks.IRON_ORE);
            boolean finished = !bot.busy();
            String err = bot.status().slots().mine().lastError().orElse(null);
            var nav = bot.navigator();
            String approach = "walk=" + NavWalk.trace(nav, "walkResult") + " | " + NavWalk.trace(nav, "progressProbe")
                    + " | " + NavWalk.trace(nav, "planProbe");
            String stairs = buriedOreStairAudit(level, cx, cz, floorY);
            // The cells the PLANNER chose, beside the arena's hand-derived ones at z=cz: the node the bot could not
            // reach once sat at z=cz−1, where the fixed window was blind.
            String planCells = NavWalk.trace(nav, "planCellAudit");
            TestMod.LOG.info(
                    "[wd.buriedOre] pos=({},{},{}) finished={} oreMined={} lastError={} {} {} {}",
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    finished,
                    oreMined,
                    err,
                    stairs,
                    planCells,
                    approach);
            if (!oreMined) {
                fail("buriedOre: buried ore not mined. Separate the two causes by the '[pathfinder] search-begin "
                        + "owner=mine' lines in this window: NONE = gap#60 proper (the stand pre-filter refused to hand "
                        + "the navigator a dig goal); ONE OR MORE = the pre-filter did its job and the navigator failed "
                        + "to CLIMB the staircase it digs — check for '[avatar] dug out its own footing' in the same "
                        + "window. lastError=" + err + " ;; " + stairs + " ;; " + planCells + " ;; " + approach);
            }
            if (!finished) {
                fail("buriedOre: the buried-ore mine task did not end ;; " + stairs + " ;; " + planCells + " ;; "
                        + approach);
            }
        });
    }

    /**
     * The seven cells of the staircase the navigator digs into the cube, as one {@code stairs=} fragment: block plus
     * the {@code blocksMotion} verdict, the predicate the server world view steers by. Read it as a two-way test on
     * {@code (cx+4, floorY+3)}: EMPTY ⇒ the self-destroying staircase is real (the goal snapped onto the cube's top has
     * that cell as its floor, and the climbing {@code StairUpBreak} breaks it); SOLID ⇒ the bot never got up the second
     * step and the defect is on the first riser.
     */
    private static String buriedOreStairAudit(ServerLevel level, int cx, int cz, int floorY) {
        int[][] cells = {{3, 1}, {3, 2}, {3, 3}, {4, 2}, {4, 3}, {5, 2}, {5, 3}};
        StringBuilder sb = new StringBuilder("stairs=");
        for (int[] c : cells) {
            BlockPos p = new BlockPos(cx + c[0], floorY + c[1], cz);
            BlockState st = level.getBlockState(p);
            sb.append('[')
                    .append(p.getX())
                    .append(',')
                    .append(p.getY())
                    .append(']')
                    .append(st.blocksMotion() ? "solid " : "empty ")
                    .append(st.getBlock());
        }
        return sb.toString();
    }

    /**
     * The dynamic entity-anchor leash: a real {@code mc.bot.goto} whose route leash centres on an armor stand, proving
     * the whole re-solve chain (find → dirty → rebuild profile → replan), not just the static leash math. Phase 1: the
     * stand is AT the bot's start; the goal is 24 blocks away but the HARD leash (radius 8) prunes every route node
     * farther than 8 from it, so the best-effort fallback only reaches the radius edge and the navigator holds there —
     * the goto stays busy for the whole 200-tick window. Phase 2: the stand moves 4 blocks PAST the goal; within the
     * leash's 20-tick re-solve limit the goto notices, rebuilds the search around the new anchor and arrives.
     *
     * <p>A fresh entity is not visible to the leash's scan until the level has indexed it, which takes real server
     * ticks; the bot is parked across each of those waits, so the test's own loops are its only driver. Golden: phase 1
     * {@code standDist=4.187857529833143}, phase 2 reached.
     */
    @Test
    void entityLeash(Game game, @Plot.Size(x = 16, y = 16, z = 32) Plot plot) {
        BlockPos o = plot.at(8, 1, 1);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int goalDz = 24;
        final double leashRadius = 8.0;
        // Side rails keep the navigator ON the lane, so the arena tests the leash, not edge-clipping churn.
        plot.fill(6, 1, 0, 10, 1, 1 + goalDz + 2, Blocks.STONE);
        plot.fill(6, 2, 0, 6, 2, 1 + goalDz + 2, Blocks.STONE);
        plot.fill(10, 2, 0, 10, 2, 1 + goalDz + 2, Blocks.STONE);
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + goalDz);
        // The phase-2 anchor: 4 blocks PAST the goal (NoGravity, floats past the lane end).
        BlockPos p2anchor = new BlockPos(goal.getX(), floorY + 1, goal.getZ() + 4);
        Settings.pinUntimed(game).set("navigatorDebug", false);
        ArmorStand stand = game.onServer(s -> {
            ArmorStand a = new ArmorStand(s.overworld(), cx + 0.5, floorY + 1, cz + 0.5); // AT the bot's start
            a.setNoGravity(true);
            s.overworld().addFreshEntity(a);
            return a;
        });
        game.afterTest(s -> stand.discard());
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);
        game.runOnServer(s -> {
            // Near(1), not Block: "reach the destination AREA" — exact-cell parking is not this arena's gate.
            bot.goTo(
                    Targets.near(goal, 1),
                    Routes.plain()
                            .leashEntity("minecraft:armor_stand", leashRadius, true, 0)
                            .build());
            // Parked at once: starting the goto put the bot on the tick list, and the server must not drive it
            // during the wait for the stand to be indexed.
            bot.park();
        });

        // The leash's scan: every entity within 96 of the player, the nearest of the type winning.
        game.await()
                .within(180)
                .as("the leash's scan sees the fresh armor stand")
                .until(() -> game.onServer(s -> {
                    ServerPlayer fp = bot.player();
                    return !fp.level()
                            .getEntities(fp, fp.getBoundingBox().inflate(96.0), e -> e instanceof ArmorStand)
                            .isEmpty();
                }));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.unpark();
            for (int t = 0; t < 200 && bot.busy(); t++) {
                bot.tick();
            }
            boolean finished1 = !bot.busy();
            double sdx = fp.getX() - (cx + 0.5);
            double sdz = fp.getZ() - (cz + 0.5);
            double standDist1 = Math.sqrt(sdx * sdx + sdz * sdz);
            boolean arrivedTrueGoal1 =
                    Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            TestMod.LOG.info(
                    "[wd.entityLeash] phase1 pos=({},{},{}) finished={} standDist={} arrivedTrueGoal={}",
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    finished1,
                    standDist1,
                    arrivedTrueGoal1);
            if (finished1 || arrivedTrueGoal1) {
                fail("entityLeash: phase1: the goto reached the true goal before the anchor moved — the hard leash did "
                        + "not hold the bot back: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") finished="
                        + finished1);
            }
            if (standDist1 > leashRadius + 3.0) {
                fail("entityLeash: phase1: bot strayed beyond the leash radius+slack: standDist=" + standDist1
                        + " radius=" + leashRadius);
            }
            // 4 PAST the goal, beyond the navigator's overshoot band: a +2 stand was rammed by the carrot-drive
            // overshoot, and its collision shoved the bot onto the rails.
            stand.teleportTo(p2anchor.getX() + 0.5, floorY + 1, p2anchor.getZ() + 0.5);
            bot.park();
        });

        game.await()
                .within(180)
                .as("the armor stand is indexed at its new anchor")
                .until(() -> game.onServer(s -> !s.overworld()
                        .getEntitiesOfClass(ArmorStand.class, new AABB(p2anchor).inflate(2.0))
                        .isEmpty()));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.unpark();
            for (int t = 0; t < 600 && bot.busy(); t++) {
                bot.tick();
            }
            boolean finished2 = !bot.busy();
            boolean reached =
                    Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            TestMod.LOG.info(
                    "[wd.entityLeash] phase2 pos=({},{},{}) finished={} reached={}",
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    finished2,
                    reached);
            if (!finished2) {
                fail("entityLeash: phase2: the leash re-solve goto did not end after the anchor moved");
            }
            if (!reached) {
                fail("entityLeash: phase2: bot did not ARRIVE at the goal after the anchor moved: pos=(" + fp.getX()
                        + "," + fp.getY() + "," + fp.getZ() + ")");
            }
        });
    }
}
