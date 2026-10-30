package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Locale;
import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.PlanCursor;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * <b>The plan said step down, the pointer stepped down, and the bot stayed where it was.</b>
 *
 * <h2>The cell this is a copy of, and why it is a copy rather than an invention</h2>
 *
 * Journey rung 14's Nether crossing spent its last four hops — 3,517 of the run's 3,551 ticks with no plan at all —
 * standing at one coordinate, {@code 159,53,187}, re-asking A* about twenty times a second. The terrain of that pocket
 * is the whole story, so it is <b>reproduced</b>: {@link #BOX} is a verbatim 17×17×13 block copy read out of the run's
 * region file, world {@code x∈[151,167] z∈[179,195] y∈[48,60]}, mapped so the bot's foot cell lands on the plot's own
 * {@code (0, +13, 0)}. Every netherrack wall, the shaft the bot dug itself, the two-block notch its foot cell hangs
 * over and the lava are the ones that were actually there; two tidyings of this copy each destroyed the defect.
 *
 * <h2>What was standing there</h2>
 *
 * The bot finished a dug descent perched on a cell whose OWN floor is air, held up by {@code 0.125} of {@code 0.36} of
 * sole on the corner of its western neighbour, with a neighbouring column dropping into lava.
 * {@code Safety#footingGuard} sneak-pinned it and was right to. A* answered with a three-node way out, every cell
 * standable, and the navigator spent all three in a single tick without moving a block:
 *
 * <pre>{@code
 * advance seq=2/8 cause=within oldStep=2 newStep=3 w=159,52,187 nx=159,51,188 bot=(159.092,53.000,187.700)
 *      cur2=0.207 |w.y-p.y|=1.000 onGround=true soleOnSolid=0.1250
 * }</pre>
 *
 * {@code within}'s vertical clause is {@code |dyNode| < 1.2}, so a waypoint a full block under the feet read as
 * reached.
 *
 * <h2>What it asks</h2>
 *
 * <p><b>It must take the step down the plan gave it</b>, it must have a plan to walk while doing so, and both must be
 * the hold's doing. Standing still is otherwise a full-marks answer — it is exactly what the ladder did. <b>Lava is
 * recorded, not asserted</b>: whether an unwedged bot then routes around the fall is the lava-shore tests' subject.
 *
 * <p>A descent clause that reads "the foot cell changed" is passed by the control arm sliding 0.30 blocks west onto the
 * block that was holding it up, so the reading is whether the step down was taken ({@code minY ≤ perchY − 1}).
 *
 * <h2>The arm that must go red</h2>
 *
 * Both arms stage the identical box and drive the identical goto; {@code navigatorDescentNodeHold} is the only
 * difference. The control (hold OFF = the ladder's build) must reproduce the burn, and the rig <b>hard-fails</b> if it
 * does not: an arena whose control walks out has not earned the right to report that the subject did.
 *
 * <h2>Driven by the navigator, not by the fixture</h2>
 *
 * Nothing here re-imposes a heading or an impulse between bot ticks: the subject IS the step pointer. Each arm is an
 * {@code mc.bot.goto} ticked one whole bot tick at a time inside one server task, asked again whenever it ends.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class DescentNodeTest {

    /** Half-width of the copied box, in cells: {@code dx, dz ∈ [-BOX_R, BOX_R]}. */
    private static final int BOX_R = 8;
    /** Layers of the copied box, {@code dy ∈ [BOX_Y0, BOX_Y0 + BOX_H - 1]} ↔ world {@code y ∈ [48,60]}. */
    private static final int BOX_H = 13;
    /** The copied box's bottom layer, plot-relative. */
    private static final int BOX_Y0 = 8;
    /** Layer index within the box that holds the bot's foot cell — world {@code y=53} is layer 5. */
    private static final int PERCH_LAYER = 5;

    /**
     * The bot's exact stance on the ladder, to three decimals, relative to the foot cell's corner. Both offsets are
     * load-bearing — the {@code 0.092} leaves only {@code 0.208 × 0.6 = 0.125} of the footprint on the neighbouring
     * block. Rounding it to {@code 0.5} stands the bot in mid-air.
     */
    private static final double PERCH_DX = 0.092;

    private static final double PERCH_DZ = 0.700;

    /**
     * Hop 11's goal, as an offset from the bot. Outside the copied box on purpose: what this measures is whether the
     * bot leaves the pocket, not whether it arrives.
     */
    private static final int GOAL_DX = 17;

    private static final int GOAL_DZ = 17;
    /** Hop 11's own tolerance. */
    private static final int GOAL_R = 6;

    /** Ticks per arm: two thirds of the ladder's 900-tick hop. */
    private static final int DRIVE_TICKS = 260;

    /** Idle ticks the stance must survive untouched before the navigator is allowed to act. */
    private static final int SETTLE_TICKS = 20;

    /**
     * The rung-14 pocket, verbatim, run-length encoded. Cells in {@code dy} (13 layers, world {@code y=48} first) then
     * {@code dz} ({@code -8..8}) then {@code dx} ({@code -8..8}). {@code #} netherrack, {@code .} air, {@code ~} lava,
     * {@code o} nether gold ore, {@code q} nether quartz ore.
     *
     * <p><b>All 34 lava cells are copied, including the 32 that were FLOWING.</b> Copying only the two sources and
     * letting the falls re-form was tried: nothing re-forms within the synchronous drive, and the CONTROL arm then
     * walked 19.61 blocks out of the pocket. The lava is the wall that makes the pocket a pocket.
     */
    private static final String BOX =
            "103#2q15#2q320#.16#2~15#2~15#.84#2q14#.16#.16#.16#.16#.68#2.6~9#.8~8#2.7~8#2.83#2q13#2.q14#"
                    + "2.16#.16#.16#.53#.14#12.5#8.4~2.3#14.3#2.3#9.71#q6#q7#2.15#2.15#2.16#.16#.59#.9#12.4#11.~2."
                    + "3#15.7#11.12#6.13#4.14#2.24#2q7#2.15#2.15#2.16#.16#.16#.41#2.13#4.#3.8#7.~2.8#11.11#7.11#6."
                    + "12#5.13#4.15#5.12#2.15#2.15#2.15#2.16#.16#.41#2.36#~.15#4.13#6.12#5.13#4.13#4.15#8.9#5.12#2."
                    + "15#2.7#~7#2.16#.16#.41#.37#~37#.15#3.13#4.12#2o3.15#10.7#9.8#5.3#.8#2.6#.o7#2.6#.9#.6#.9#."
                    + "41#.108#2o.16#.17#7.8#6.2#.8#2.6#.8#2.6#.2o6#2.6#.2o7#.6#.#o7#.6#.16#.16#2.146#5.9#2.15#2.8#"
                    + "2q5#2.7#3q5#2.6#2qo7#.6#.q8#.6#.16#.16#2.57#o16#o85#2.15#2.8#2q5#2.7#3q5#2.6#3q7#.7#q8#.99#o"
                    + "15#2o15#2o68#2.15#2.15#2.15#2.16#.16#.132#2o53#";

    /** How one drive ended. */
    private record Leg(
            int ticks,
            int noPlan,
            int longestNoPlan,
            double minY,
            double moved,
            boolean lava,
            boolean descended,
            long holds,
            String ended) {}

    // ---------------------------------------------------------------- the copy of rung 14's pocket

    @Test
    void serverStepsDownAPerchItPlanned(
            Game game, @Plot.Centered @Plot.Size(x = 24, y = 48, z = 24) Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game).set("navigatorDebug", true);

        game.runOnServer(s -> stageBox(plot));
        reporter.publishEntry("perch", game.onServer(s -> perchRow(plot, s.getLevel(plot.dimension()))));

        Leg control = drive(game, plot, settings, reporter, "control", false);
        if (control.descended()) {
            fail("THE RIG, not the subject: with navigatorDescentNodeHold off the bot still stepped down, so \"the"
                    + " subject stepped down\" cannot distinguish \"fixed\" from \"this arena never traps the bot\": "
                    + control.ended());
        }
        if (control.holds() != 0) {
            fail("THE RIG, not the subject: the switch is off, yet the hold counter rose by " + control.holds()
                    + "; the two arms no longer differ in this one variable only");
        }

        game.runOnServer(s -> stageBox(plot));
        Leg subject = drive(game, plot, settings, reporter, "subject", true);
        reporter.publishEntry(
                "delta",
                "without a plan " + control.noPlan() + "/" + control.ticks() + " ticks -> "
                        + subject.noPlan() + "/" + subject.ticks() + " ticks; lowest y "
                        + String.format(Locale.ROOT, "%.2f -> %.2f", control.minY(), subject.minY())
                        + "; moved "
                        + String.format(Locale.ROOT, "%.2f -> %.2f blocks", control.moved(), subject.moved())
                        + "; step advances held " + control.holds() + " -> " + subject.holds() + " times");
        // NOT a clause: whether an unwedged bot then routes around the fall this pocket opens onto belongs to the lava
        // shore and rim tests. Published every run because it is a finding either way.
        reporter.publishEntry(
                "lava",
                "control arm " + (control.lava() ? "entered the lava" : "did not enter the lava") + ", subject "
                        + (subject.lava() ? "entered the lava" : "did not enter the lava"));

        game.check(
                subject.descended(),
                "A: the bot must actually step down out of that cell; the planned descent step"
                        + " is the one the pointer spent, and the real ladder stood still for 900 ticks because of it: measured "
                        + subject.ended());
        game.check(
                control.noPlan() > subject.noPlan(),
                "B: and the bot must have a plan to follow: control arm "
                        + control.noPlan() + "/" + control.ticks() + " ticks without a plan, subject "
                        + subject.noPlan() + "/"
                        + subject.ticks() + " ticks");
        game.check(
                subject.holds() > 0,
                "C: and all of this must be the hold's doing: the subject held " + subject.holds()
                        + " step advances where the bot was still standing and the node was below its feet");
    }

    /** Re-derive the perch off the LEVEL, so the staging and the guard cannot disagree about it. Server thread. */
    private static String perchRow(Plot plot, ServerLevel level) {
        BlockPos foot = plot.at(0, BOX_Y0 + PERCH_LAYER, 0);
        int solidAt = -1;
        for (int i = 1; i <= 8 && solidAt < 0; i++) {
            if (level.getBlockState(foot.below(i)).blocksMotion()) {
                solidAt = i;
            }
        }
        int lavaAt = -1;
        BlockPos lavaCol = null;
        for (int dx = -1; dx <= 1 && lavaAt < 0; dx++) {
            for (int dz = -1; dz <= 1 && lavaAt < 0; dz++) {
                BlockPos col = foot.offset(dx, 0, dz);
                for (int i = 1; i <= 8 && lavaAt < 0; i++) {
                    if (level.getBlockState(col.below(i)).getFluidState().is(FluidTags.LAVA)) {
                        lavaAt = i;
                        lavaCol = col;
                    }
                }
            }
        }
        return "the cell below the feet " + foot.below().toShortString() + "=" + name(level, foot.below()) + ", "
                + (solidAt < 0
                        ? "no solid block within 8 blocks below"
                        : "the first solid block is " + solidAt + " blocks down")
                + "; held up by the cell to the west " + foot.offset(-1, -1, 0).toShortString() + "="
                + name(level, foot.offset(-1, -1, 0))
                + "; in the surrounding 3×3 columns "
                + (lavaAt < 0
                        ? "no lava was found"
                        : "lava is found " + lavaAt + " blocks below " + lavaCol.toShortString());
    }

    private static String cell(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static String name(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getBlock().getName().getString();
    }

    /**
     * Stand a fresh bot on the perch and let the navigator have it, inside one server task. The goal is hop 11's, at
     * hop 11's bearing and tolerance. The bot leaves when the drive is over, so the next arm's staging does not build
     * around it.
     */
    private static Leg drive(Game game, Plot plot, Settings settings, TestReporter reporter, String arm, boolean hold) {
        settings.set("navigatorDescentNodeHold", hold);
        BlockPos foot = plot.at(0, BOX_Y0 + PERCH_LAYER, 0);
        double x0 = foot.getX() + PERCH_DX;
        double z0 = foot.getZ() + PERCH_DZ;
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), x0, foot.getY(), z0);
        GotoContracts.Target goal = Targets.column(foot.getX() + GOAL_DX, foot.getZ() + GOAL_DZ, GOAL_R);
        Leg leg = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            // The crossing arrives carrying blocks and bridges with them; a bot with an empty inventory would be
            // refused moves this one had.
            fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            bot.step(SETTLE_TICKS);
            double settledY = fp.getY();
            if (settledY < foot.getY() - 0.5) {
                fail("THE RIG, not the subject: vanilla itself did not hold the staged stand (y="
                        + String.format(Locale.ROOT, "%.2f", settledY) + " after " + SETTLE_TICKS + " idle ticks)");
            }
            double sole = bot.body().footing().soleOnSolid();
            if (sole <= 0.0 || sole >= 0.36) {
                fail("THE RIG, not the subject: sole on solid " + String.format(Locale.ROOT, "%.4f", sole)
                        + " is not the real ladder's stance of standing on a single corner (requires 0 < sole < 0.36)");
            }

            long holds0 = bot.navigator().counter("descentHolds");
            double minY = fp.getY();
            int noPlan = 0;
            int run = 0;
            int longest = 0;
            int t = 0;
            boolean lava = false;
            for (; t < DRIVE_TICKS; t++) {
                askAgain(bot, goal);
                bot.tick();
                if (planNode(bot.navigator()).isEmpty()) {
                    noPlan++;
                    longest = Math.max(longest, ++run);
                } else {
                    run = 0;
                }
                minY = Math.min(minY, fp.getY());
                if (fp.isInLava()) {
                    lava = true;
                    break;
                }
            }
            double moved = Math.hypot(fp.getX() - x0, fp.getZ() - z0);
            long holds = bot.navigator().counter("descentHolds") - holds0;
            double soleEnd = bot.body().footing().soleOnSolid();
            bot.cancel();
            // DESCENDED, not "the foot cell changed": see the class note.
            boolean descended = minY <= foot.getY() - 1.0;
            String ended = String.format(
                    Locale.ROOT,
                    "navigatorDescentNodeHold=%s -> %d ticks, bot=(%.2f,%.2f,%.2f) cell=%s, lowest y=%.2f (perch y=%d),"
                            + " moved %.2f blocks, without a plan %d/%d ticks (longest continuous %d), sole on solid=%.4f,"
                            + " step advances held %d times%s",
                    hold,
                    t,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    fp.blockPosition().toShortString(),
                    minY,
                    foot.getY(),
                    moved,
                    noPlan,
                    t,
                    longest,
                    soleEnd,
                    holds,
                    lava ? ", submerged in lava" : (descended ? ", stepped down" : ", did not step down"));
            bot.remove();
            return new Leg(t, noPlan, longest, minY, moved, lava, descended, holds, ended);
        });
        reporter.publishEntry(arm + ".drive", leg.ended());
        return leg;
    }

    /**
     * Start the walk, and start it again whenever the last one ended. The ladder's hop kept asking the same question
     * for its whole budget; a goto ends at its first terminal status and leaves the bot idle, which would turn "it kept
     * failing to step down" into "it stopped".
     */
    private static void askAgain(SceneBot bot, GotoContracts.Target goal) {
        if (!bot.busy()) {
            bot.goTo(goal);
        }
    }

    /** The node the walk is heading for, absent while it holds no plan. */
    private static Optional<BlockPosition> planNode(NavigatorReading nav) {
        return nav.plan().flatMap(PlanCursor::node);
    }

    /**
     * Paint the copied box, then wall it in. Server thread. Solid first, then everything else, so the lava is put back
     * into a bed that already exists. The faces are then made solid — the one place this stops being a copy: the
     * ladder's cave carries on past the copy's edge and its lava with it. The goal is outside the box and was never
     * reachable from this pocket on the ladder either, and the step down the plan asks for is two cells from the perch.
     */
    private static void stageBox(Plot plot) {
        plot.fill(-BOX_R, BOX_Y0, -BOX_R, BOX_R, BOX_Y0 + BOX_H - 1, BOX_R, Blocks.AIR);
        String cells = decode(BOX);
        for (int pass = 0; pass < 2; pass++) {
            for (int dx = -BOX_R; dx <= BOX_R; dx++) {
                for (int dz = -BOX_R; dz <= BOX_R; dz++) {
                    for (int k = 0; k < BOX_H; k++) {
                        char c = cells.charAt(((k * (2 * BOX_R + 1)) + (dz + BOX_R)) * (2 * BOX_R + 1) + (dx + BOX_R));
                        boolean solid = c != '.' && c != '~';
                        if (solid != (pass == 0)) {
                            continue;
                        }
                        plot.place(dx, BOX_Y0 + k, dz, block(c));
                    }
                }
            }
        }
        for (int dx = -BOX_R; dx <= BOX_R; dx++) {
            for (int dz = -BOX_R; dz <= BOX_R; dz++) {
                for (int k = 0; k < BOX_H; k++) {
                    if (dx == -BOX_R || dx == BOX_R || dz == -BOX_R || dz == BOX_R || k == 0 || k == BOX_H - 1) {
                        plot.place(dx, BOX_Y0 + k, dz, Blocks.NETHERRACK);
                    }
                }
            }
        }
    }

    private static Block block(char c) {
        return switch (c) {
            case '#' -> Blocks.NETHERRACK;
            case '~' -> Blocks.LAVA;
            case 'o' -> Blocks.NETHER_GOLD_ORE;
            case 'q' -> Blocks.NETHER_QUARTZ_ORE;
            default -> Blocks.AIR;
        };
    }

    /** Expand a run-length encoded copy. A token is an optional decimal count followed by one symbol. */
    private static String decode(String rle) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rle.length(); ) {
            int j = i;
            while (Character.isDigit(rle.charAt(j))) {
                j++;
            }
            int n = j > i ? Integer.parseInt(rle.substring(i, j)) : 1;
            char c = rle.charAt(j);
            sb.append(String.valueOf(c).repeat(n));
            i = j + 1;
        }
        return sb.toString();
    }

    // -------------------------------------------------- the LAST node of the plan (rung 13) ----

    /** Columns of the doorway copy, {@code dx ∈ [DOOR_X0, DOOR_X0 + DOOR_NX - 1]} ↔ world {@code x ∈ [1,6]}. */
    private static final int DOOR_NX = 6;

    private static final int DOOR_X0 = -2;
    /** Rows, {@code dz ∈ [dw.z0(), dw.z0() + DOOR_NZ - 1]} ↔ world {@code z ∈ [16,22]}. */
    private static final int DOOR_NZ = 7;
    /** Layers, {@code dy ∈ [DOOR_Y0, DOOR_Y0 + DOOR_NL - 1]} ↔ world {@code y ∈ [55,61]}. */
    private static final int DOOR_NL = 7;

    private static final int DOOR_Y0 = 8;
    /** How many opening ticks each arm records node by node. */
    private static final int TRACE_TICKS = 8;
    /**
     * Ticks per arm. The whole move is one cell; kept well under the 80-tick portal delay so the LIT portal two cells
     * away cannot take the bot to the Nether in the middle of a measurement.
     */
    private static final int DOOR_TICKS = 60;

    /**
     * One verbatim copy of rung 13's doorway, and where the bot stood in it.
     *
     * @param cells run-length encoded, same scheme as {@link #BOX}: cells in {@code dy} (7 layers) then {@code dz} then
     *     {@code dx}. {@code .} air, {@code #} stone, {@code d} dirt, {@code c} cobblestone, {@code o} obsidian,
     *     {@code p} nether_portal, {@code g} granite, {@code a} andesite, {@code u} copper
     * @param z0 dz of the copy's north row
     * @param layer layer holding the bot's foot cell
     * @param dx the bot's exact offset inside its foot cell, x — load-bearing to three decimals
     * @param dz ditto, z
     * @param stepDz the doorstep's dz from the bot's own cell; it is always one row DOWN
     * @param sole the {@code soleOnSolid} the ladder printed for this stance — the rig's premise
     */
    private record Doorway(
            String cells, int z0, int layer, double dx, double dz, int stepDz, double sole, String when) {}

    /**
     * The 15:20 doorway: the doorstep is STRAIGHT DOWN, and A* answers with TWO nodes. The bot's cell is one whose own
     * floor is air; it is held up by {@code 0.056} of sole on the north lip of the cobblestone south of it. A* routes
     * sideways first and then down, and the navigator spent both nodes in ONE tick.
     */
    private static final Doorway DOOR_B = new Doorway(
            "6a36#a4#g#2c2#g#2.2#g#2.o#g#.co3#d.14#g#.c2#g#.co#g.dcp#g#2.p3#dco4#u5#4d#2.3d#.co2d2.cp2d#"
                    + "2.p2d#2.o15d2.4d2.o2d2.cp3d2.p3d2.o15d2.4d2.4d2.o3d2.o3d2.16d2.4d2.4d3.3d3.3d2.9d",
            -4,
            3,
            0.700,
            0.794,
            0,
            0.0564,
            "2026-08-20 15:20");

    /** How one doorway drive ended. */
    private record Door(
            int ticks,
            int noPlan,
            double minY,
            double moved,
            boolean onDoorstep,
            long holds,
            String end,
            String ended) {}

    private static BlockPos doorPerch(Plot plot, Doorway dw) {
        return plot.at(0, DOOR_Y0 + dw.layer(), 0);
    }

    /** The goal: one row DOWN and {@code stepDz} along. Standable by construction. */
    private static BlockPos doorstep(Plot plot, Doorway dw) {
        return plot.at(0, DOOR_Y0 + dw.layer() - 1, dw.stepDz());
    }

    /**
     * <b>The same defect through a TWO-node plan, which is why the first fix did not hold it.</b>
     *
     * <pre>{@code
     * advance seq=1 cause=passed oldStep=1 newStep=2 w=3,58,21 nx=3,57,20 bot=(3.700,58.000,20.794) soleOnSolid=0.0563
     * advance seq=2 cause=within oldStep=2 newStep=3 w=3,57,20 nx=none(last node) bot=(3.700,58.000,20.794) |w.y-p.y|=1.000
     * }</pre>
     *
     * <p><b>{@code oldStep=2} on the deciding line is the whole finding.</b> A final-node hold that asks
     * {@code nav.step == 1} — a POINTER index standing in for "the bot has not walked this plan" — is defeated by a
     * plan whose first node is consumed in the same tick. The predicate therefore asks where the bot actually stands:
     * {@code foot.equals(path.get(0))}, true on both lines above.
     */
    @Test
    void serverStepsDownAPlanItSpentInOneTick(
            Game game, @Plot.Centered @Plot.Size(x = 24, y = 48, z = 24) Plot plot, TestReporter reporter) {
        Doorway dw = DOOR_B;
        // The pathfinder's own breaking/placing is OFF: the alcove's cobblestone is a wall, not something A* tunnels
        // through.
        Settings settings = Settings.pin(game)
                .set("navigatorDebug", true)
                .set("allowBreak", false)
                .set("allowPlace", false);

        game.runOnServer(s -> stageDoorway(plot, s.getLevel(plot.dimension()), dw));
        reporter.publishEntry("doorway", game.onServer(s -> doorRow(plot, s.getLevel(plot.dimension()), dw)));

        Door control = doorDrive(game, plot, settings, reporter, dw, "control", false);
        if (control.onDoorstep()) {
            fail("THE RIG, not the subject: with navigatorDescentNodeHold off the bot still reached the doorstep cell,"
                    + " so \"the subject reached the doorstep\" cannot distinguish \"fixed\" from \"this arena never"
                    + " blocks the bot\": " + control.ended());
        }
        if (control.holds() != 0) {
            fail("THE RIG, not the subject: the switch is off, yet the hold counter rose by " + control.holds());
        }

        game.runOnServer(s -> stageDoorway(plot, s.getLevel(plot.dimension()), dw));
        Door subject = doorDrive(game, plot, settings, reporter, dw, "subject", true);
        reporter.publishEntry(
                "delta",
                "moved " + String.format(Locale.ROOT, "%.2f -> %.2f blocks", control.moved(), subject.moved())
                        + "; lowest y " + String.format(Locale.ROOT, "%.2f -> %.2f", control.minY(), subject.minY())
                        + "; end reason " + control.end() + " -> " + subject.end() + "; step advances held "
                        + control.holds() + " -> " + subject.holds() + " times");

        game.check(
                subject.onDoorstep(),
                "A: the subject must actually stand on the doorstep cell "
                        + doorstep(plot, dw).toShortString()
                        + "; that descent step is the one the pointer spent: measured "
                        + subject.ended());
        game.check(
                subject.holds() > 0,
                "B: and this must be the hold's doing: the subject held " + subject.holds()
                        + " step advances, control arm " + control.holds() + " times");
        // Without this the arm cannot tell "the fix let it walk in" from "the arena was walkable all along and the
        // control failed for some third reason".
        game.check(
                "path-consumed".equals(control.end()),
                "C: and the control arm must end for the same reason as the"
                        + " real ladder (end=path-consumed): measured " + control.end() + ": " + control.ended());
    }

    /** Re-derive the doorway off the LEVEL rather than off the staging constants. Server thread. */
    private static String doorRow(Plot plot, ServerLevel level, Doorway dw) {
        BlockPos perch = doorPerch(plot, dw);
        BlockPos step = doorstep(plot, dw);
        return "the bot's cell " + perch.toShortString() + "=" + name(level, perch)
                + ", below it " + name(level, perch.below())
                + "; doorstep " + step.toShortString() + "=" + name(level, step)
                + " (below " + name(level, step.below()) + ", above " + name(level, step.above()) + ")"
                + "; portal " + step.east().toShortString() + "=" + name(level, step.east())
                + "; the doorstep is one block lower than the bot"
                + (dw.stepDz() == 0
                        ? " and directly beneath it (so the plan has two nodes)"
                        : " and offset by " + dw.stepDz() + " blocks (so the plan has only one node)");
    }

    /** Stand a fresh bot on the ladder's perch and let the navigator have the plan, inside one server task. */
    private static Door doorDrive(
            Game game, Plot plot, Settings settings, TestReporter reporter, Doorway dw, String arm, boolean hold) {
        settings.set("navigatorDescentNodeHold", hold);
        BlockPos perch = doorPerch(plot, dw);
        BlockPos step = doorstep(plot, dw);
        double x0 = perch.getX() + dw.dx();
        double z0 = perch.getZ() + dw.dz();
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), x0, perch.getY(), z0);
        Door door = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            // Rung 13 walks at the doorway with blocks in its inventory.
            fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            bot.step(SETTLE_TICKS);
            double settledY = fp.getY();
            if (settledY < perch.getY() - 0.5) {
                fail("THE RIG, not the subject: vanilla itself did not hold the staged stand (y="
                        + String.format(Locale.ROOT, "%.2f", settledY) + " after " + SETTLE_TICKS + " idle ticks)");
            }
            double sole = bot.body().footing().soleOnSolid();
            // The 0.01 window is for a loader's floating point tail, not for a different stance.
            if (Math.abs(sole - dw.sole()) > 0.01) {
                fail("THE RIG, not the subject: sole on solid " + String.format(Locale.ROOT, "%.4f", sole)
                        + ", but the real ladder printed " + dw.sole() + " on that tick; the stance is wrong");
            }

            GotoContracts.Target goal = Targets.block(step);
            long holds0 = bot.navigator().counter("descentHolds");
            double minY = fp.getY();
            int t = 0;
            int noPlan = 0;
            boolean onDoorstep = false;
            String end = "none";
            // THE PLAN, tick by tick, for the first few ticks: the claim is about what the navigator does with a plan
            // on the tick it gets one, and none of that is visible from outside.
            StringBuilder trace = new StringBuilder();
            for (; t < DOOR_TICKS; t++) {
                askAgain(bot, goal);
                bot.tick();
                NavigatorReading nav = bot.navigator();
                Optional<BlockPosition> node = planNode(nav);
                if (node.isEmpty()) {
                    noPlan++;
                }
                if (t < TRACE_TICKS) {
                    trace.append(t == 0 ? "" : " ")
                            .append(t)
                            .append(':')
                            .append(cell(fp.blockPosition()))
                            .append(" step")
                            .append(nav.plan().map(PlanCursor::step).orElse(0))
                            .append('/')
                            .append(nav.plan().map(PlanCursor::len).orElse(0))
                            .append(" node")
                            .append(node.map(n -> n.x() + "," + n.y() + "," + n.z())
                                    .orElse("none"))
                            .append(" held")
                            .append(nav.counter("descentHolds") - holds0);
                }
                if (!bot.busy()) {
                    end = bot.gotoSlot().endReason().orElse("none");
                }
                minY = Math.min(minY, fp.getY());
                if (fp.blockPosition().equals(step)) {
                    onDoorstep = true;
                    t++;
                    break;
                }
                // A second safeguard: the portal two cells east is LIT.
                if (level.getBlockState(fp.blockPosition()).is(Blocks.NETHER_PORTAL)) {
                    t++;
                    break;
                }
            }
            reporter.publishEntry(arm + ".trace", trace.toString());
            NavigatorReading last = bot.navigator();
            double moved = Math.hypot(fp.getX() - x0, fp.getZ() - z0);
            long holds = last.counter("descentHolds") - holds0;
            bot.cancel();
            String ended = String.format(
                    Locale.ROOT,
                    "navigatorDescentNodeHold=%s -> %d ticks, bot=(%.2f,%.2f,%.2f) cell=%s, lowest y=%.2f (perch y=%d),"
                            + " moved %.2f blocks, without a plan %d/%d ticks, end=%s, step advances held %d times%s",
                    hold,
                    t,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    fp.blockPosition().toShortString(),
                    minY,
                    perch.getY(),
                    moved,
                    noPlan,
                    t,
                    end,
                    holds,
                    onDoorstep ? ", reached the doorstep" : ", did not reach the doorstep " + step.toShortString());
            bot.remove();
            return new Door(t, noPlan, minY, moved, onDoorstep, holds, end, ended);
        });
        reporter.publishEntry(arm + ".drive", door.ended());
        return door;
    }

    /**
     * Paint the copied doorway, seal around it, then light the portal. Server thread. Three passes, and the order is
     * what makes this work: solid first, then the air, then the {@code nether_portal} cells through
     * {@code setBlockAndUpdate}. A portal block whose frame vanilla does not accept is removed on the next neighbour
     * update, so setting them last is what lets the rig CHECK the doorway rather than assume it. The seal stands one
     * cell OUTSIDE the copy and the top is left open: the ladder's doorway has sky over it.
     */
    private static void stageDoorway(Plot plot, ServerLevel level, Doorway dw) {
        plot.fill(
                DOOR_X0 - 1,
                DOOR_Y0 - 1,
                dw.z0() - 1,
                DOOR_X0 + DOOR_NX,
                DOOR_Y0 + DOOR_NL,
                dw.z0() + DOOR_NZ,
                Blocks.AIR);
        String cells = decode(dw.cells());
        for (int pass = 0; pass < 2; pass++) {
            for (int dx = DOOR_X0; dx < DOOR_X0 + DOOR_NX; dx++) {
                for (int dz = dw.z0(); dz < dw.z0() + DOOR_NZ; dz++) {
                    for (int k = 0; k < DOOR_NL; k++) {
                        char c = cells.charAt(((k * DOOR_NZ) + (dz - dw.z0())) * DOOR_NX + (dx - DOOR_X0));
                        boolean solid = c != '.' && c != 'p';
                        if (solid != (pass == 0)) {
                            continue;
                        }
                        plot.place(dx, DOOR_Y0 + k, dz, doorBlock(c));
                    }
                }
            }
        }
        for (int dx = DOOR_X0 - 1; dx <= DOOR_X0 + DOOR_NX; dx++) {
            for (int dz = dw.z0() - 1; dz <= dw.z0() + DOOR_NZ; dz++) {
                for (int dy = DOOR_Y0 - 1; dy < DOOR_Y0 + DOOR_NL; dy++) {
                    if (dx == DOOR_X0 - 1
                            || dx == DOOR_X0 + DOOR_NX
                            || dz == dw.z0() - 1
                            || dz == dw.z0() + DOOR_NZ
                            || dy == DOOR_Y0 - 1) {
                        plot.place(dx, dy, dz, Blocks.STONE);
                    }
                }
            }
        }
        // AXIS is not in the copy because a block name does not carry it. The frame lies in one x plane, so the
        // interior spans z — the only axis vanilla's own PortalShape would have produced.
        int lit = 0;
        int stood = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int dx = DOOR_X0; dx < DOOR_X0 + DOOR_NX; dx++) {
                for (int dz = dw.z0(); dz < dw.z0() + DOOR_NZ; dz++) {
                    for (int k = 0; k < DOOR_NL; k++) {
                        BlockPos at = plot.at(dx, DOOR_Y0 + k, dz);
                        if (pass == 0) {
                            char c = cells.charAt(((k * DOOR_NZ) + (dz - dw.z0())) * DOOR_NX + (dx - DOOR_X0));
                            if (c != 'p') {
                                continue;
                            }
                            level.setBlockAndUpdate(
                                    at,
                                    Blocks.NETHER_PORTAL
                                            .defaultBlockState()
                                            .setValue(NetherPortalBlock.AXIS, Direction.Axis.Z));
                            lit++;
                        } else if (level.getBlockState(at).is(Blocks.NETHER_PORTAL)) {
                            stood++;
                        }
                    }
                }
            }
        }
        if (stood != lit) {
            fail("THE RIG, not the subject: placed " + lit + " portal blocks, but only " + stood + " remained; the"
                    + " invalid ones were removed, so this arena's doorway is not genuine");
        }
    }

    private static Block doorBlock(char c) {
        return switch (c) {
            case '#' -> Blocks.STONE;
            case 'd' -> Blocks.DIRT;
            case 'c' -> Blocks.COBBLESTONE;
            case 'o' -> Blocks.OBSIDIAN;
            case 'g' -> Blocks.GRANITE;
            case 'a' -> Blocks.ANDESITE;
            case 'u' -> Blocks.COPPER_ORE;
            default -> Blocks.AIR;
        };
    }
}
