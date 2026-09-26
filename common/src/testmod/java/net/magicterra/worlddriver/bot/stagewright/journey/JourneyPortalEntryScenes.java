package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;

/**
 * A lit portal whose front is walled up, and a bot that has to open one cell to get in.
 *
 * <p>The ladder's rung 13 died here on 2026-08-19 after rung 12 had lit the portal from a fresh
 * world. The evidence it left is worth quoting (rendered in English), because every row of it is
 * true and the conclusion it drew from them was not:
 *
 * <pre>
 * portal.found = 4, 57, 19      stand.at = 2, 58, 19      stand.in = Block{minecraft:air}
 * portal.leg.0 … portal.leg.7   walk in: 2, 58, 19 standing in Block{minecraft:air}, dimension minecraft:overworld
 * stood in the portal for 1200 ticks and was not transferred: bot at 2, 58, 19
 * </pre>
 *
 * The bot was never in the portal — its own {@code stand.in} says so — the scene ran 451 ticks and
 * not the 1200 the message claimed, and the eight walks are byte-identical because each asked the
 * pathfinder the same unanswerable question.
 *
 * <h2>The geometry, and why it is not exotic</h2>
 *
 * Rung 12 casts its frame by pouring lava at it across a flooded alcove. Lava meeting water leaves
 * <b>cobblestone</b>, and some of it lands in the alcove in front of the doorway. Read out of that
 * run's world save, with the portal in the plane {@code x=4} and the alcove at {@code x in [2,3]}:
 *
 * <pre>
 * 3,57,19 = cobblestone   3,58,19 = cobblestone   3,59,19 = air (3,58,19 below it is solid)
 * </pre>
 *
 * So the bottom two rows of the doorway were walled and the top one was open. That is a sandwich
 * with no way out:
 *
 * <ul>
 *   <li>the only portal cell the pathfinder can accept as a GOAL is the bottom one — every other
 *       cell's floor is another portal block, which has no collision — and no route reached it;</li>
 *   <li>the only row with an open front is the TOP one, and a player does not fit there: a portal
 *       interior is three cells tall, a player is 1.8, so its head would be in the frame's obsidian
 *       cap. This arm measured that before {@code JourneyPortalEntry.enterable} existed — sixty
 *       ticks of held forward moved the bot to {@code z = cellZ − 0.3} and stopped, {@code dm.z}
 *       exactly {@code 0.000}.</li>
 * </ul>
 *
 * <p>So the way in is to open ONE cell of the alcove wall, in front of the row the bot does fit
 * through, and walk in there.
 *
 * <h2>Two arms</h2>
 *
 * <ul>
 *   <li>{@code wd.portalEntryDigsIntoTheRowItFits} — the shape above. The subject must decline the
 *       open-but-too-short top row, name the middle row and the single cell in its way, and end up
 *       inside the portal.</li>
 *   <li>{@code wd.portalEntryWillNotMineItsOwnFrame} — the same doorway with obsidian all around it.
 *       Now the only removable neighbour of any portal cell is the frame, and mining the frame would
 *       put the portal out, so the answer must be "no way in" — which is what makes the rung report
 *       "could not reach a portal block" rather than "stood in it and was not transferred". Its
 *       control is the same arena with one
 *       obsidian cell swapped for stone, where a way in must be found.</li>
 * </ul>
 *
 * <h2>Breaking is off in the pathfinder, on purpose</h2>
 *
 * {@code BotConfig.applyGameTestBaseline} leaves {@code allowBreak}/{@code allowPlace} off on the
 * dogfood server, and the NETHER rung never calls {@code JourneyRig.generousPathfinding} — so off is
 * what the ladder actually runs with, and it is why a cobblestone in the alcove is a wall rather
 * than something A* tunnels through on its own. The rung's own dig does not go through that flag: it
 * is an aimed swing at a named cell, the way the rest of this suite mines.
 *
 * <h2>Arena footprint</h2>
 *
 * {@code dx in [-3, 4]}, {@code dz in [-6, 4]}, {@code dy in [BASE-1, BASE+10]} around the origin —
 * inside the default one-chunk window ({@code dx, dz in [-16, 31]}), so no {@code withChunkRadius}.
 * Stated here rather than left to be derived because {@code scripts/check_scene_arena.py} scans the
 * {@code scene} package only: these scenes live beside the code they test.
 */
public final class JourneyPortalEntryScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.portalEntryDigsIntoTheRowItFits", 400,
                        JourneyPortalEntryScenes::digsIntoTheRowItFits),
                Scene.of("wd.portalEntryWillNotMineItsOwnFrame", 200,
                        JourneyPortalEntryScenes::willNotMineItsOwnFrame),
                Scene.of("wd.portalEntryWontAskForAColumnItStandsIn", 400,
                        JourneyPortalEntryScenes::wontAskForAColumnItStandsIn));
    }

    // ----------------------------------------------------------------------- rig ----

    /** The staged hill's floor, as a dy offset. Twenty above the grid's y=200, like the sibling
     *  journey arenas, which leaves head room over the portal and stays far under the build limit. */
    private static final int BASE = 20;

    /** How many walks the control gets to prove the pre-fix approach cannot get in. Fewer than the
     *  rung's eight only because each one ends the instant the search reports no path. */
    private static final int CONTROL_LEGS = 4;

    /** Synchronous ticks one walk may spend. The whole arena is nine blocks across. */
    private static final int LEG_TICKS = 200;

    /** What is standing in front of the doorway. One variable per arm; everything else is identical. */
    private enum Front {
        /** The ladder's own shape: the alcove wall covers the bottom two rows, the top row is open
         *  and too short to walk through. One stone cell opens the middle row. */
        SLAG,
        /** The wall one course lower, so the MIDDLE row's front is already open — nothing to dig. */
        OPEN,
        /** Obsidian in front of and behind every row: the only removable neighbour of any portal
         *  cell is the frame, and the frame is not removable. */
        FRAME_ONLY,
        /** {@link #FRAME_ONLY} with the middle row's front cell swapped for stone — the control that
         *  proves "no way in" is a reading and not a constant. */
        FRAME_ONLY_WITH_A_WINDOW
    }

    /** The portal's bottom-left interior cell — the one, and only one, a {@code Goal.Block} can
     *  terminate on, because its floor is the frame's own obsidian. */
    private static BlockPos bottomCell(SceneContext ctx) { return ctx.rel(0, BASE + 2, 0); }

    /** The portal's middle-left interior cell: the lowest one whose head cell is also portal, so the
     *  lowest one a 1.8-tall player can stand in with the doorway's front open. */
    private static BlockPos middleCell(SceneContext ctx) { return ctx.rel(0, BASE + 3, 0); }

    /** The cell of the alcove wall that stands in front of {@link #middleCell}. */
    private static BlockPos slagCell(SceneContext ctx) { return ctx.rel(0, BASE + 3, -1); }

    /** The doorstep of {@link #middleCell}: somewhere for the feet, and somewhere for the head. The
     *  frame arm's control turns exactly these two to stone and changes nothing else. */
    private static List<BlockPos> windowCells(SceneContext ctx) {
        return List.of(ctx.rel(0, BASE + 3, -1), ctx.rel(0, BASE + 4, -1));
    }

    /** Where the bot starts: on the plateau, four cells north of the doorway. */
    private static BlockPos start(SceneContext ctx) { return ctx.rel(0, BASE + 4, -4); }

    /**
     * Build the arena, and light nothing — the portal blocks are set directly.
     *
     * <p>Staged rather than lit with a flint-and-steel because the ignition has its own scene
     * ({@code wd.serverLightsPortal}) and this one is about the walk. The premise is asserted
     * immediately afterwards instead of assumed: a portal block whose frame vanilla does not accept
     * is removed by {@code NetherPortalBlock.updateShape} on the next neighbour update, and an arm
     * that then walked a bot into six cells of air would pass every check it has.
     */
    private static void stage(SceneContext ctx, Front front) {
        clearBox(ctx);

        // The ground, and the hill the doorway is cut into.
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = -6; dz <= 4; dz++)
                ctx.setBlock(dx, BASE, dz, Blocks.STONE);

        // The plateau the bot walks in on. SLAG puts its top face at BASE+3, so the standable row in
        // front of the doorway is BASE+4 — the portal's TOP row, which a player does not fit through.
        // OPEN puts it one lower, so the standable row is the MIDDLE one and nothing needs digging.
        int plateauTop = front == Front.OPEN ? BASE + 2 : BASE + 3;
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = -6; dz <= -1; dz++)
                for (int dy = BASE + 1; dy <= plateauTop; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);

        // Rock behind the frame and either side of it, so the doorway has exactly one open face.
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = 1; dz <= 4; dz++)
                for (int dy = BASE + 1; dy <= BASE + 6; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dy = BASE + 1; dy <= BASE + 6; dy++) {
            ctx.setBlock(-2, dy, 0, Blocks.STONE);
            ctx.setBlock(-3, dy, 0, Blocks.STONE);
            ctx.setBlock(3, dy, 0, Blocks.STONE);
            ctx.setBlock(4, dy, 0, Blocks.STONE);
        }

        // The frame: the same ten cells the ladder casts (corners left out — vanilla's PortalShape
        // never reads them, and ten is what rung 12 can afford).
        ctx.setBlock(0, BASE + 1, 0, Blocks.OBSIDIAN);
        ctx.setBlock(1, BASE + 1, 0, Blocks.OBSIDIAN);
        ctx.setBlock(0, BASE + 5, 0, Blocks.OBSIDIAN);
        ctx.setBlock(1, BASE + 5, 0, Blocks.OBSIDIAN);
        for (int dy = BASE + 2; dy <= BASE + 4; dy++) {
            ctx.setBlock(-1, dy, 0, Blocks.OBSIDIAN);
            ctx.setBlock(2, dy, 0, Blocks.OBSIDIAN);
        }

        if (front == Front.FRAME_ONLY || front == Front.FRAME_ONLY_WITH_A_WINDOW) {
            // Obsidian on BOTH faces of every row, so every horizontal neighbour of every portal
            // cell is frame — the sides already are. Then one cell is swapped back to stone in the
            // control, and that swap is the only difference between the two runs.
            for (int dx = 0; dx <= 1; dx++)
                for (int dy = BASE + 2; dy <= BASE + 4; dy++) {
                    ctx.setBlock(dx, dy, -1, Blocks.OBSIDIAN);
                    ctx.setBlock(dx, dy, 1, Blocks.OBSIDIAN);
                }
            // Both cells of the middle row's front, because a doorstep is TWO cells: a player needs
            // somewhere for its feet and somewhere for its head, and leaving obsidian in the head
            // cell would make the control fail for the reason the subject is supposed to.
            if (front == Front.FRAME_ONLY_WITH_A_WINDOW)
                for (BlockPos c : windowCells(ctx)) ctx.level().setBlockAndUpdate(c, Blocks.STONE.defaultBlockState());
        }

        // The doorway itself. Axis X: the frame lies in a z=const plane, so the portal spans x.
        ServerLevel level = ctx.level();
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = BASE + 2; dy <= BASE + 4; dy++)
                level.setBlockAndUpdate(ctx.rel(dx, dy, 0), Blocks.NETHER_PORTAL.defaultBlockState()
                        .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));
    }

    /** Air out the working box. One cell past the arena on every side, so a re-stage removes
     *  whatever the previous arm left wherever it left it. */
    private static void clearBox(SceneContext ctx) {
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = -6; dz <= 4; dz++)
                for (int dy = BASE - 1; dy <= BASE + 10; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The switches both arms share — see the class note on why the pathfinder's breaking stays off. */
    private static void config(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        // Registered BEFORE anything is built, so an arm that fails mid-drive still hands the shared
        // dogfood world back empty — a portal left standing would take the next scene's bot.
        ctx.cleanup(() -> clearBox(ctx));
    }

    /** A bot standing in {@code foot}, settled, with a pickaxe and the cleanup that removes it. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot) {
        return body(ctx, foot, 0.5);
    }

    /** The same, with the bot's z inside its own cell named — a stance on the LIP of a block is a
     *  different situation from one at its centre, and one arm here needs the lip. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot, double dz) {
        ServerWorldDriver driver = SceneBody.managed(ctx,
                foot.getX() + 0.5, foot.getY(), foot.getZ() + dz);
        ServerPlayer fp = driver.fakePlayer();
        // A pickaxe because the ladder's bot has one by rung 13 and because destroyBlock hands the
        // held item to dropResources — a fist opens the cell and drops nothing.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        // Three physics steps with no input, so the bot is flush before anything is measured.
        for (int i = 0; i < 3; i++) av.step();
        return driver;
    }

    private static boolean inPortal(ServerWorldDriver driver) {
        ServerPlayer fp = driver.fakePlayer();
        return fp.level().getBlockState(fp.blockPosition()).is(Blocks.NETHER_PORTAL);
    }

    /**
     * Run one process to completion, its budget, or the bot entering the portal.
     *
     * <p>That last clause is not a shortcut. This arena's portal is LIT, so a bot left standing in
     * it for eighty ticks is taken to the Nether and every reading after that is about a different
     * world — including the arm's own cleanup, which would be airing out a box the bot is no longer
     * in. What is under test is the entry, and the entry is finished the moment the bot's own cell
     * reads {@code nether_portal}.
     */
    private static int drive(ServerWorldDriver driver, BotProcess process, int budget) {
        driver.runProcess(process);
        int t = 0;
        for (; t < budget && !driver.finished(); t++) {
            driver.tick();
            if (inPortal(driver)) { t++; break; }
        }
        return t;
    }

    /**
     * The bot's position to three decimals, with the heading and the ground bit.
     *
     * <p>A cell is too coarse to judge a one-block push by: the difference between "did not move" and
     * "slid to the lip and stopped" is a tenth of a block, and they are different defects — the second
     * is what named the head-clearance rule this file exists for. Yaw is here because
     * {@code aimAtBlock} is the only thing steering the push, and {@code onGround} because
     * {@code getFrictionInfluencedSpeed} reads it.
     */
    private static String exactly(ServerWorldDriver driver) {
        ServerPlayer fp = driver.fakePlayer();
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f yaw=%.1f onGround=%s dm=%.3f/%.3f/%.3f",
                fp.getX(), fp.getY(), fp.getZ(), fp.getYRot(), fp.onGround(),
                fp.getDeltaMovement().x, fp.getDeltaMovement().y, fp.getDeltaMovement().z);
    }

    /** Where the bot is and what it is standing in — the one sentence both arms judge on. */
    private static String where(ServerWorldDriver driver) {
        ServerPlayer fp = driver.fakePlayer();
        BlockPos at = fp.blockPosition();
        return at.toShortString() + " standing in " + fp.level().getBlockState(at).getBlock();
    }

    /** {@link JourneyHands#swing} — the arena's own break oracle, shared with the pour-line arenas
     *  so there is one of it rather than one per file. */
    private static boolean swing(ServerWorldDriver driver, BlockPos cell) {
        return JourneyHands.swing(driver, cell);
    }

    // ---------------------------------------------------------------------- arms ----

    // ------------------------------------------ the walk that asked a question already answered ----

    /** The perch arm's floor, as a dy offset. Its own {@code BASE} so a re-stage cannot inherit the
     *  portal arms' hill. */
    private static final int LEDGE = 40;

    /** The bot's z inside its own cell. {@code 0.79} leaves {@code 0.6 x 0.09 = 0.054} of sole on the
     *  perch north of it — the ladder's own stance was {@code 0.794} for {@code 0.0563}. Rounded to
     *  the cell centre the bot has no support at all and simply falls, which would answer the
     *  question by accident. */
    private static final double LEDGE_DZ = 0.79;

    /** Ticks one walk gets. The whole move is one cell down; the ladder's walks were over in 11. */
    private static final int LEDGE_TICKS = 80;

    /** Where the bot stands: one row ABOVE the doorstep, in its column, held up by the perch. */
    private static BlockPos ledgePerch(SceneContext ctx) { return ctx.rel(0, LEDGE + 2, 0); }

    /** The doorstep, straight down from {@link #ledgePerch}. */
    private static BlockPos ledgeStep(SceneContext ctx) { return ctx.rel(0, LEDGE + 1, 0); }

    /**
     * <b>{@code Goal.XZ} ignores Y, so a walk that asks for a column the bot is already standing in
     * reports success without moving — and the rung counted that as a walk that did not move.</b>
     *
     * <h2>What the ladder did</h2>
     *
     * Rung 13 alternates its walks between {@code Goal.XZ} and {@code Goal.Block} because a retry
     * that asks the identical question gets the identical answer. On 2026-08-20 15:20 the doorstep was
     * {@code 3,57,20} and the bot ended one row directly above it, on {@code 3,58,20}:
     *
     * <pre>
     * portal.walk.1 = XZ goal 3, 57, 20: 2, 58, 20 → 3, 58, 20 (moved 1 block) end=arrived
     * portal.walk.2 = 3D goal 3, 57, 20: 3, 58, 20 → 3, 58, 20 (moved 0 blocks) end=path-consumed
     * portal.walk.3 = XZ goal 3, 57, 20: 3, 58, 20 → 3, 58, 20 (moved 0 blocks) end=arrived
     * </pre>
     *
     * {@code walk.1} and {@code walk.3} both say {@code arrived} and in neither was the bot ever on the
     * doorstep, because {@code Goal.XZ(3,20,0).reached(3,58,20)} is TRUE — the column matches and the
     * row is not part of the question. {@code walk.3} is a pure no-op that reports success, and
     * because it moved zero cells it also fed the two-stationary-walks terminator that ended the
     * rung. So half of the walk budget was being spent on a shape that could not express "and be on
     * that row", and the shape that could was being interleaved with it.
     *
     * <h2>What this asks, and what it deliberately does not</h2>
     *
     * The subject is {@link JourneyPortalEntry#legGoal}, not the terrain — so unlike
     * {@code wd.serverStepsDownAPlanItSpentInOneTick}, whose subject IS the plan the terrain produces
     * and which therefore copies the region file cell for cell, this arm stages the smallest world in
     * which the situation is real: a doorstep, a perch, and a bot standing on the lip of the perch
     * one row above the doorstep. The stance is not incidental and is checked — a bot at the cell
     * centre has nothing under it and falls onto the doorstep by gravity, which would pass every
     * clause below while measuring nothing.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>the XZ walk is a no-op</b> — drive the goal the old alternation would have issued and
     *       require the bot NOT to reach the doorstep. This is the control: if this arm can descend,
     *       the scene cannot tell a fix from a walk that was never blocked;</li>
     *   <li><b>{@code legGoal} does not issue it</b> on a flat turn from that cell — it must hand back
     *       a {@code Goal.Block};</li>
     *   <li><b>and driving what it does issue lands the bot on the doorstep</b>;</li>
     *   <li><b>the alternation still exists.</b> From a cell OUTSIDE the doorstep's column a flat turn
     *       must still be {@code Goal.XZ}. Without this the fix could have deleted the alternation
     *       outright and every clause above would still be green.</li>
     * </ol>
     *
     * <h2>Arena footprint</h2>
     *
     * {@code dx in [-3, 3]}, {@code dz in [-3, 3]}, {@code dy in [LEDGE, LEDGE + 4]} — inside the
     * default one-chunk window, so no {@code withChunkRadius}.
     */
    private static void wontAskForAColumnItStandsIn(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        ctx.cleanup(() -> clearLedge(ctx));
        stageLedge(ctx);

        BlockPos perch = ledgePerch(ctx);
        BlockPos step = ledgeStep(ctx);
        ctx.record("rig", "bot cell " + perch.toShortString() + " (below it "
                + level.getBlockState(perch.below()).getBlock() + ", held up by the perch block to the"
                + " north), doorstep " + step.toShortString() + " directly below: same column, one row lower");
        ctx.check(JourneyPortalEntry.standable(level, step))
                .as("THE RIG: the doorstep " + step.toShortString() + " must be standable, otherwise"
                        + " this measures something else")
                .isTrue();

        // ---- control: the goal the alternation used to issue on this turn ----
        ServerWorldDriver blind = body(ctx, perch, LEDGE_DZ);
        BlockPos before = blind.fakePlayer().blockPosition();
        Goal.XZ column = new Goal.XZ(step.getX(), step.getZ(), 0);
        ctx.record("blind.premise", "XZ(" + step.getX() + "," + step.getZ() + ",0).reached("
                + before.toShortString() + ") = " + column.reached(before)
                + "; the bot already stands in the target column, so the answer to this question is"
                + " already \"yes\"");
        int blindTicks = drive(blind, new IntentProcess(new Intent(column)), LEDGE_TICKS);
        boolean blindArrived = blind.fakePlayer().blockPosition().equals(step);
        ctx.record("blind.leg", "XZ goal " + step.toShortString() + ": " + before.toShortString()
                + " → " + where(blind) + " (" + blindTicks + " ticks) "
                + JourneyLeg.walkerEnd(blind) + " " + exactly(blind));
        if (blindArrived)
            ctx.fail("THE RIG, not the subject: the XZ walk reached the doorstep " + step.toShortString()
                    + " on its own, so \"only the 3D goal gets there\" cannot tell a fix from an arena"
                    + " that was always walkable: " + where(blind));

        // ---- subject: what legGoal issues on the same turn, from the same cell ----
        ServerWorldDriver seeing = body(ctx, perch, LEDGE_DZ);
        BlockPos here = seeing.fakePlayer().blockPosition();
        Goal picked = JourneyPortalEntry.legGoal(here, step, true);
        ctx.record("picked", JourneyPortalEntry.legShape(here, step, true) + " → " + picked);
        int seeTicks = drive(seeing, new IntentProcess(new Intent(picked)), LEDGE_TICKS);
        ctx.record("subject.leg", "goal " + step.toShortString() + ": " + here.toShortString()
                + " → " + where(seeing) + " (" + seeTicks + " ticks) "
                + JourneyLeg.walkerEnd(seeing) + " " + exactly(seeing));

        // The alternation has to survive the fix, or this is a deletion wearing a fix's clothes.
        BlockPos far = step.offset(4, 1, 4);
        Goal stillXz = JourneyPortalEntry.legGoal(far, step, true);
        ctx.record("alternation", "flat turn asked from " + far.toShortString()
                + " (not in that column) → " + stillXz);

        ctx.check(picked instanceof Goal.Block).as("A When the bot already stands in the doorstep's"
                + " column, the flat turn must not ask XZ again: measured " + picked
                + " (XZ's answer for this cell is " + column.reached(here) + ")").isTrue();
        ctx.check(seeing.fakePlayer().blockPosition()).as("B The replacement goal must actually bring"
                + " the bot to the doorstep " + step.toShortString() + ": measured " + where(seeing))
                .isEqualTo(step);
        ctx.check(stillXz instanceof Goal.XZ).as("C Outside that column the flat turn must still be XZ;"
                + " the alternation exists to ask a different question, not to remove one: measured "
                + stillXz).isTrue();
    }

    /** Floor, doorstep and the one block the bot balances on. Nothing else: the subject is which
     *  goal a walk asks for, so terrain past that would only add ways for the arm to be wrong. */
    private static void stageLedge(SceneContext ctx) {
        clearLedge(ctx);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                ctx.setBlock(dx, LEDGE, dz, Blocks.STONE);
        // The perch: its top face is the bot's floor, one row ABOVE the doorstep, one cell north.
        ctx.setBlock(0, LEDGE + 1, 1, Blocks.STONE);
    }

    private static void clearLedge(SceneContext ctx) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = LEDGE; dy <= LEDGE + 4; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }
    /**
     * <b>The one open row is too short to walk through: open the row below it and step in there.</b>
     *
     * <h2>The control, and why the subject's criterion needs one</h2>
     *
     * "the bot ends inside a {@code nether_portal} cell" is satisfied by any arena where it was
     * already there, by a portal staged around the start cell, by a step-in across a room with no
     * walls. So the arm FIRST drives the pre-fix approach — {@code Goal.Block(bottomCell)}, the goal
     * the rung asked for eight times — and requires it to come back with the bot OUTSIDE the portal.
     * An arm that cannot fail to get in has not earned the right to report that it got in.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>nothing is walkable as staged.</b> The top row's front is open and the search must
     *       still refuse it, because the player does not fit — this is the clause the first cut of the
     *       fix did not have;</li>
     *   <li><b>the way in it does name is the middle row, at the price of exactly one cell</b>, and
     *       that cell is the alcove wall rather than the frame;</li>
     *   <li><b>the dig actually opened it</b> — without this the walk below is measuring a wall that
     *       was never there;</li>
     *   <li><b>the walk arrives somewhere it can step in from</b>, else the step-in is 0 == 0;</li>
     *   <li><b>the bot ends inside a portal cell.</b></li>
     * </ol>
     */
    private static void digsIntoTheRowItFits(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, Front.SLAG);

        BlockPos bottom = bottomCell(ctx);
        BlockPos middle = middleCell(ctx);
        BlockPos slag = slagCell(ctx);
        ctx.record("staged.doorway", JourneyPortalEntry.survey(level, bottom));
        ctx.check(level.getBlockState(bottom).is(Blocks.NETHER_PORTAL))
                .as("THE RIG: the staged doorway is really a portal at " + bottom.toShortString()
                        + ", not the air a rejected NetherPortalBlock.updateShape leaves — "
                        + level.getBlockState(bottom).getBlock()).isTrue();
        ctx.check(JourneyPortalEntry.cells(level, bottom)).as("THE RIG: six doorway cells").hasSize(6);

        // ---- control: the goal the rung used to ask for, and could never reach ----
        ServerWorldDriver control = body(ctx, start(ctx));
        StringBuilder trail = new StringBuilder();
        int spent = 0;
        for (int i = 0; i < CONTROL_LEGS && !inPortal(control); i++) {
            BlockPos before = control.fakePlayer().blockPosition();
            int t = drive(control, new IntentProcess(new Intent(new Goal.Block(bottom))), LEG_TICKS);
            spent += t;
            trail.append(i == 0 ? "" : " ").append(i).append(':').append(before.toShortString())
                    .append("->").append(control.fakePlayer().blockPosition().toShortString())
                    .append('/').append(t).append('t');
        }
        int controlFaults = inPortal(control) ? 0 : 1;
        ctx.record("control.legs", String.format(Locale.ROOT,
                "Goal.Block(%s) x%d, %d ticks | %s | %s", bottom.toShortString(),
                CONTROL_LEGS, spent, trail, JourneyLeg.walkerEnd(control)));
        ctx.record("control.after", controlFaults + " fault(s): " + where(control));
        WorldDriverCommon.LOG.info("[portalEntry] control faults={} at {}", controlFaults, where(control));
        if (controlFaults == 0)
            ctx.fail("THE RIG, not the subject: the pre-fix goal " + bottom.toShortString()
                    + " got the bot into the portal on its own, so this arm's \"ended inside a"
                    + " nether_portal cell\" criterion cannot tell a fix from a walk that was never"
                    + " blocked — " + trail);
        control.fakePlayer().discard();

        // ---- subject ----
        stage(ctx, Front.SLAG);
        ServerWorldDriver subject = body(ctx, start(ctx));
        BlockPos from = subject.fakePlayer().blockPosition();

        JourneyPortalEntry.Doorstep walkIn = JourneyPortalEntry.find(level, bottom, from);
        ctx.record("subject.walkIn", walkIn == null ? "null (no doorstep to walk in from as staged)"
                : "stand at " + walkIn.stand().toShortString() + ", step into " + walkIn.cell().toShortString());
        ctx.check(walkIn).as("A The top row's front is open, but the bot is 1.8 blocks tall and the"
                + " doorway interior is 3 tall; standing in the top row puts the head in the obsidian"
                + " cap, so \"can walk in now\" must be null: " + JourneyPortalEntry.survey(level, bottom))
                .isNull();

        JourneyPortalEntry.Doorstep door = JourneyPortalEntry.find(level, bottom, from, true);
        ctx.record("subject.doorstep", door == null ? "null" : "stand at " + door.stand().toShortString()
                + ", step into " + door.cell().toShortString() + ", dig out " + door.clear() + " first");
        ctx.check(door == null ? null : door.cell()).as("B The diggable way in must be the middle row "
                + middle.toShortString() + " (the lowest row the bot fits through)").isEqualTo(middle);
        ctx.check(door == null ? null : door.clear()).as("C The cost is exactly the one alcove wall cell "
                + slag.toShortString() + ", not the frame's obsidian").isEqualTo(List.of(slag));
        if (door == null) return;   // every check below would be 0 == 0

        boolean opened = swing(subject, slag);
        ctx.record("subject.dig", slag.toShortString() + " → " + level.getBlockState(slag).getBlock()
                + " (canBreak=" + subject.avatar().canBreak(slag) + ", bot at "
                + subject.fakePlayer().blockPosition().toShortString() + ")");
        ctx.check(opened).as("D The cell was actually dug open; otherwise the walk below is measuring a"
                + " wall that does not exist: "
                + slag.toShortString() + "=" + level.getBlockState(slag).getBlock()).isTrue();
        if (!opened) return;

        JourneyPortalEntry.Doorstep after = JourneyPortalEntry.find(level, bottom, from);
        ctx.record("subject.doorstep.after", after == null ? "null" : "stand at " + after.stand().toShortString()
                + ", step into " + after.cell().toShortString());
        if (after == null) return;

        int walked = drive(subject, new IntentProcess(new Intent(new Goal.Block(after.stand()))), LEG_TICKS);
        // Let it land. The last edge into a doorstep one row down is a step off a ledge, and the walk
        // ends on the tick the process reports finished — with the bot still in the air over the
        // cell it is arriving at. Without this settle the arm reads "cannot step in from here" about
        // a cell the bot is a tenth of a second from standing on, and the rung has the same settle
        // for the same reason.
        drive(subject, new HoldStill(10), 20);
        BlockPos atDoor = subject.fakePlayer().blockPosition();
        BlockPos ready = JourneyPortalEntry.stepFrom(level, atDoor, bottom);
        ctx.record("subject.walk", from.toShortString() + " → " + atDoor.toShortString()
                + " (" + walked + " ticks) " + JourneyLeg.walkerEnd(subject)
                + ", doorway cell enterable from here=" + (ready == null ? "none" : ready.toShortString()));
        ctx.check(ready).as("E After the walk the bot must stand on a cell it can step in from,"
                + " otherwise the step-in below is 0==0: bot at " + atDoor.toShortString()
                + ", heading for " + after.stand().toShortString()).isNotNull();
        if (ready == null) return;

        // The walk brakes into its goal with the sneak flag and nothing clears it when the process
        // ends; a sneaking player will not step off a ledge, and stepping into a floorless portal cell
        // is exactly that. Recorded at the moment of the push because it is one of the two readings
        // that separate "pushed for sixty ticks and did not move" from "ran into something" — the
        // other is `exactly`.
        boolean braking = subject.fakePlayer().isShiftKeyDown();
        String before = exactly(subject);
        int pushed = drive(subject, JourneyPortalEntry.stepInto(ready, JourneyPortalEntry.STEP_IN_TICKS),
                JourneyPortalEntry.STEP_IN_TICKS + 20);
        int subjectFaults = inPortal(subject) ? 0 : 1;
        ctx.record("subject.stepIn", atDoor.toShortString() + " pushed toward " + ready.toShortString()
                + " (" + pushed + " ticks, shiftKeyDown at the start=" + braking + ") → " + where(subject)
                + " | start " + before + " end " + exactly(subject));
        ctx.record("subject.after", subjectFaults + " fault(s): " + where(subject));
        ctx.check(subjectFaults).as("F The bot must end up standing in a nether_portal block: "
                + where(subject))
                .isEqualTo(0);
    }

    /**
     * <b>Obsidian all round the doorway: the answer is "no way in", because the frame is not a wall
     * this rung may remove.</b>
     *
     * <p>This is the branch the failure message has to get right. A bot that never reached a portal
     * cell and a bot that stood in one and was not transferred want opposite fixes — the geometry in
     * front of the door, or {@code Entity.handlePortal} — so the rung picks between two messages on
     * exactly this predicate. And the temptation the digging search creates is precise: the frame
     * borders every doorway cell, so it is always the geometrically cheapest thing to remove, and a
     * search that took it would report a green walk into a portal it had just put out.
     *
     * <p>The control is the same arena with ONE obsidian cell swapped for stone. Without it, "walled
     * means null" is satisfied by a search that returns null always.
     */
    private static void willNotMineItsOwnFrame(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);

        // ---- control: two stone cells in the same wall, and a way in must be found ----
        stage(ctx, Front.FRAME_ONLY_WITH_A_WINDOW);
        BlockPos bottom = bottomCell(ctx);
        BlockPos slag = slagCell(ctx);
        JourneyPortalEntry.Doorstep window = JourneyPortalEntry.find(level, bottom, start(ctx), true);
        ctx.record("control.after", (window == null ? 1 : 0) + " fault(s): "
                + (window == null ? "no way in found even with two stone cells in the wall"
                        : "stand at " + window.stand().toShortString() + ", step into "
                          + window.cell().toShortString() + ", dig out " + window.clear()));
        if (window == null)
            ctx.fail("THE RIG, not the subject: the middle row's doorstep is stone here ("
                    + windowCells(ctx) + ") and the search still found no way in, so \"obsidian means"
                    + " null\" below measures nothing — " + JourneyPortalEntry.survey(level, bottom));

        // ---- subject ----
        stage(ctx, Front.FRAME_ONLY);
        String survey = JourneyPortalEntry.survey(level, bottom);
        JourneyPortalEntry.Doorstep walkIn = JourneyPortalEntry.find(level, bottom, start(ctx));
        JourneyPortalEntry.Doorstep dug = JourneyPortalEntry.find(level, bottom, start(ctx), true);
        ctx.record("subject.doorway", survey);
        ctx.record("subject.after", (walkIn == null && dug == null ? 0 : 1) + " fault(s): "
                + (dug == null ? "only the frame remains around the doorway and digging offers no way"
                        + " in; this run will report \"could not reach a portal block\""
                        : "a way in is still found at " + dug.stand().toShortString() + ", digging "
                          + dug.clear()));
        ctx.check(walkIn).as("A With obsidian on every side, \"can walk in now\" must be null: "
                + survey).isNull();
        ctx.check(dug).as("B \"Dig out one cell\" must also be null. The frame is always the cheapest"
                + " block, and digging it gets the bot in but puts the portal out: " + survey).isNull();
        ctx.check(level.getBlockState(slag).is(Blocks.OBSIDIAN))
                .as("C The two arms differ only in the two middle-row doorstep cells " + windowCells(ctx)
                        + " (stone in the control, obsidian in the subject): "
                        + level.getBlockState(slag).getBlock()).isTrue();
    }
}
