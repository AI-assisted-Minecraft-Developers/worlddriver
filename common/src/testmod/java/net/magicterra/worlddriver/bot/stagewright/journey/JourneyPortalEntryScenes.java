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
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
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
 * A lit portal whose front is walled up, and a body that has to open one cell to get in.
 *
 * <p>The ladder's rung 13 died here on 2026-08-19 after rung 12 had lit the portal from a fresh
 * world. The evidence it left is worth quoting, because every row of it is true and the conclusion
 * it drew from them was not:
 *
 * <pre>
 * portal.found = 4, 57, 19      stand.at = 2, 58, 19      stand.in = Block{minecraft:air}
 * portal.leg.0 … portal.leg.7   走进去：2, 58, 19 站的是 Block{minecraft:air}，维度 minecraft:overworld
 * 站在传送门里 1200 tick 没被送走：身体在 2, 58, 19
 * </pre>
 *
 * The body was never in the portal — its own {@code stand.in} says so — the scene ran 451 ticks and
 * not the 1200 the message claimed, and the eight legs are byte-identical because each asked the
 * pathfinder the same unanswerable question.
 *
 * <h2>The geometry, and why it is not exotic</h2>
 *
 * Rung 12 casts its frame by pouring lava at it across a flooded alcove. Lava meeting water leaves
 * <b>cobblestone</b>, and some of it lands in the alcove in front of the doorway. Read out of that
 * run's world save, with the portal in the plane {@code x=4} and the alcove at {@code x in [2,3]}:
 *
 * <pre>
 * 3,57,19 = cobblestone   3,58,19 = cobblestone   3,59,19 = air（脚下 3,58,19 是实心的）
 * </pre>
 *
 * So the bottom two rows of the doorway were walled and the top one was open. That is a sandwich
 * with no way out:
 *
 * <ul>
 *   <li>the only portal cell the pathfinder can accept as a GOAL is the bottom one — every other
 *       cell's floor is another portal block, which has no collision — and no route reached it;</li>
 *   <li>the only row with an open front is the TOP one, and a body does not fit there: a portal
 *       interior is three cells tall, a body is 1.8, so its head would be in the frame's obsidian
 *       cap. This arm measured that before {@code JourneyPortalEntry.enterable} existed — sixty
 *       ticks of held forward moved the body to {@code z = cellZ − 0.3} and stopped, {@code dm.z}
 *       exactly {@code 0.000}.</li>
 * </ul>
 *
 * <p>So the way in is to open ONE cell of the alcove wall, in front of the row the body does fit
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
 *       put the portal out, so the answer must be「no way in」— which is what makes the rung report
 *       「到不了传送门方块」rather than「站进去了没被送走」. Its control is the same arena with one
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
                        JourneyPortalEntryScenes::digsIntoTheRowItFits).withRequired(false),
                Scene.of("wd.portalEntryWillNotMineItsOwnFrame", 200,
                        JourneyPortalEntryScenes::willNotMineItsOwnFrame).withRequired(false));
    }

    // ----------------------------------------------------------------------- rig ----

    /** The staged hill's floor, as a dy offset. Twenty above the grid's y=200, like the sibling
     *  journey arenas, which leaves head room over the portal and stays far under the build limit. */
    private static final int BASE = 20;

    /** How many legs the control gets to prove the pre-fix approach cannot get in. Fewer than the
     *  rung's eight only because each one ends the instant the search reports no path. */
    private static final int CONTROL_LEGS = 4;

    /** Synchronous ticks one walk leg may spend. The whole arena is nine blocks across. */
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
         *  proves「no way in」is a reading and not a constant. */
        FRAME_ONLY_WITH_A_WINDOW
    }

    /** The portal's bottom-left interior cell — the one, and only one, a {@code Goal.Block} can
     *  terminate on, because its floor is the frame's own obsidian. */
    private static BlockPos bottomCell(SceneContext ctx) { return ctx.rel(0, BASE + 2, 0); }

    /** The portal's middle-left interior cell: the lowest one whose head cell is also portal, so the
     *  lowest one a 1.8-tall body can stand in with the doorway's front open. */
    private static BlockPos middleCell(SceneContext ctx) { return ctx.rel(0, BASE + 3, 0); }

    /** The cell of the alcove wall that stands in front of {@link #middleCell}. */
    private static BlockPos slagCell(SceneContext ctx) { return ctx.rel(0, BASE + 3, -1); }

    /** The doorstep of {@link #middleCell}: somewhere for the feet, and somewhere for the head. The
     *  frame arm's control turns exactly these two to stone and changes nothing else. */
    private static List<BlockPos> windowCells(SceneContext ctx) {
        return List.of(ctx.rel(0, BASE + 3, -1), ctx.rel(0, BASE + 4, -1));
    }

    /** Where the body starts: on the plateau, four cells north of the doorway. */
    private static BlockPos start(SceneContext ctx) { return ctx.rel(0, BASE + 4, -4); }

    /**
     * Build the arena, and light nothing — the portal blocks are set directly.
     *
     * <p>Staged rather than lit with a flint-and-steel because the ignition has its own scene
     * ({@code wd.serverLightsPortal}) and this one is about the walk. The premise is asserted
     * immediately afterwards instead of assumed: a portal block whose frame vanilla does not accept
     * is removed by {@code NetherPortalBlock.updateShape} on the next neighbour update, and an arm
     * that then walked a body into six cells of air would pass every check it has.
     */
    private static void stage(SceneContext ctx, Front front) {
        clearBox(ctx);

        // The ground, and the hill the doorway is cut into.
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = -6; dz <= 4; dz++)
                ctx.setBlock(dx, BASE, dz, Blocks.STONE);

        // The plateau the body walks in on. SLAG puts its top face at BASE+3, so the standable row in
        // front of the doorway is BASE+4 — the portal's TOP row, which a body does not fit through.
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
            // Both cells of the middle row's front, because a doorstep is TWO cells: a body needs
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
        // dogfood world back empty — a portal left standing would take the next scene's body.
        ctx.cleanup(() -> clearBox(ctx));
    }

    /** A body standing in {@code foot}, settled, with a pickaxe and the cleanup that removes it. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot) {
        ServerWorldDriver driver = ServerWorldDriver.createIsolated(ctx.level(),
                foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        // A pickaxe because the ladder's body has one by rung 13 and because destroyBlock hands the
        // held item to dropResources — a fist opens the cell and drops nothing.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerAvatar av = driver.avatar();
        // Three physics steps with no input, so the body is flush before anything is measured.
        for (int i = 0; i < 3; i++) av.step();
        return driver;
    }

    private static boolean inPortal(ServerWorldDriver driver) {
        ServerPlayer fp = driver.fakePlayer();
        return fp.level().getBlockState(fp.blockPosition()).is(Blocks.NETHER_PORTAL);
    }

    /**
     * Run one process to completion, its budget, or the body entering the portal.
     *
     * <p>That last clause is not a shortcut. This arena's portal is LIT, so a body left standing in
     * it for eighty ticks is taken to the Nether and every reading after that is about a different
     * world — including the arm's own cleanup, which would be airing out a box the body is no longer
     * in. What is under test is the entry, and the entry is finished the moment the body's own cell
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
     * The body to three decimals, with the heading and the ground bit.
     *
     * <p>A cell is too coarse to judge a one-block push by: the difference between「did not move」and
     * 「slid to the lip and stopped」is a tenth of a block, and they are different defects — the second
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

    /** Where the body is and what it is standing in — the one sentence both arms judge on. */
    private static String where(ServerWorldDriver driver) {
        ServerPlayer fp = driver.fakePlayer();
        BlockPos at = fp.blockPosition();
        return at.toShortString() + " 站的是 " + fp.level().getBlockState(at).getBlock();
    }

    /**
     * Open one named cell with an aimed swing — the same three calls {@code
     * JourneyRig.breakItWhereItStands} makes, which is what the rung's {@code mineCellOrGiveUp} tries
     * before it routes anywhere.
     *
     * <p>Judged on the WORLD rather than on the call, because {@code breakHold} returns nothing and
     * refuses silently.
     */
    private static boolean swing(ServerWorldDriver driver, BlockPos cell) {
        ServerPlayerAvatar av = driver.avatar();
        if (!av.canBreak(cell)) return false;
        av.selectTool(cell);
        av.aimAtBlock(cell);
        av.breakHold(true);
        av.breakHold(false);
        return !driver.fakePlayer().level().getBlockState(cell).blocksMotion();
    }

    // ---------------------------------------------------------------------- arms ----

    /**
     * <b>The one open row is too short to walk through: open the row below it and step in there.</b>
     *
     * <h2>The control, and why the subject's criterion needs one</h2>
     *
     * 「the body ends inside a {@code nether_portal} cell」is satisfied by any arena where it was
     * already there, by a portal staged around the start cell, by a step-in across a room with no
     * walls. So the arm FIRST drives the pre-fix approach — {@code Goal.Block(bottomCell)}, the goal
     * the rung asked for eight times — and requires it to come back with the body OUTSIDE the portal.
     * An arm that cannot fail to get in has not earned the right to report that it got in.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>nothing is walkable as staged.</b> The top row's front is open and the search must
     *       still refuse it, because the body does not fit — this is the clause the first cut of the
     *       fix did not have;</li>
     *   <li><b>the way in it does name is the middle row, at the price of exactly one cell</b>, and
     *       that cell is the alcove wall rather than the frame;</li>
     *   <li><b>the dig actually opened it</b> — without this the walk below is measuring a wall that
     *       was never there;</li>
     *   <li><b>the walk arrives somewhere it can step in from</b>, else the step-in is 0 == 0;</li>
     *   <li><b>the body ends inside a portal cell.</b></li>
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
                "Goal.Block(%s) x%d, %d ticks | %s | end=%s err=%s", bottom.toShortString(),
                CONTROL_LEGS, spent, trail, control.botState().mc_goto.endReason,
                control.botState().mc_goto.lastError));
        ctx.record("control.after", controlFaults + " fault(s): " + where(control));
        WorldDriverCommon.LOG.info("[portalEntry] control faults={} at {}", controlFaults, where(control));
        if (controlFaults == 0)
            ctx.fail("THE RIG, not the subject: the pre-fix goal " + bottom.toShortString()
                    + " got the body into the portal on its own, so this arm's 「ended inside a"
                    + " nether_portal cell」criterion cannot tell a fix from a walk that was never"
                    + " blocked — " + trail);
        control.fakePlayer().discard();

        // ---- subject ----
        stage(ctx, Front.SLAG);
        ServerWorldDriver subject = body(ctx, start(ctx));
        BlockPos from = subject.fakePlayer().blockPosition();

        JourneyPortalEntry.Doorstep walkIn = JourneyPortalEntry.find(level, bottom, from);
        ctx.record("subject.walkIn", walkIn == null ? "null（没有现成能走进去的门口）"
                : "站 " + walkIn.stand().toShortString() + " 迈进 " + walkIn.cell().toShortString());
        ctx.check(walkIn).as("A 顶排的门面是开的，但身体 1.8 格高、门洞内高 3 格 —— 站顶排头顶就是"
                + "框顶的黑曜石，所以「现在就能走进去」必须是 null：" + JourneyPortalEntry.survey(level, bottom))
                .isNull();

        JourneyPortalEntry.Doorstep door = JourneyPortalEntry.find(level, bottom, from, true);
        ctx.record("subject.doorstep", door == null ? "null" : "站 " + door.stand().toShortString()
                + " 迈进 " + door.cell().toShortString() + "，先挖开 " + door.clear());
        ctx.check(door == null ? null : door.cell()).as("B 挖得动的那条路要落在中排 "
                + middle.toShortString() + "（身体进得去的最低一排）").isEqualTo(middle);
        ctx.check(door == null ? null : door.clear()).as("C 代价恰好是壁龛墙上那一格 "
                + slag.toShortString() + "，不是门框的黑曜石").isEqualTo(List.of(slag));
        if (door == null) return;   // every check below would be 0 == 0

        boolean opened = swing(subject, slag);
        ctx.record("subject.dig", slag.toShortString() + " → " + level.getBlockState(slag).getBlock()
                + "（canBreak=" + subject.avatar().canBreak(slag) + "，身体在 "
                + subject.fakePlayer().blockPosition().toShortString() + "）");
        ctx.check(opened).as("D 那一格真的挖开了，否则下面的行走是在量一堵不存在的墙："
                + slag.toShortString() + "=" + level.getBlockState(slag).getBlock()).isTrue();
        if (!opened) return;

        JourneyPortalEntry.Doorstep after = JourneyPortalEntry.find(level, bottom, from);
        ctx.record("subject.doorstep.after", after == null ? "null" : "站 " + after.stand().toShortString()
                + " 迈进 " + after.cell().toShortString());
        if (after == null) return;

        int walked = drive(subject, new IntentProcess(new Intent(new Goal.Block(after.stand()))), LEG_TICKS);
        // Let it land. The last edge into a doorstep one row down is a step off a ledge, and the walk
        // ends on the tick the process reports finished — with the body still in the air over the
        // cell it is arriving at. Before this the arm read「不能就地迈进」about a cell the body was a
        // tenth of a second from standing on, and the rung has the same settle for the same reason.
        drive(subject, new HoldStill(10), 20);
        BlockPos atDoor = subject.fakePlayer().blockPosition();
        BlockPos ready = JourneyPortalEntry.stepFrom(level, atDoor, bottom);
        ctx.record("subject.walk", from.toShortString() + " → " + atDoor.toShortString()
                + "（" + walked + " tick）end=" + subject.botState().mc_goto.endReason
                + " err=" + subject.botState().mc_goto.lastError
                + "，就地能迈进的门洞格=" + (ready == null ? "无" : ready.toShortString()));
        ctx.check(ready).as("E 走完要真的站在能迈进去的那一格上，否则下面的「迈进去」是 0==0："
                + "身体在 " + atDoor.toShortString() + "，想去 " + after.stand().toShortString()).isNotNull();
        if (ready == null) return;

        // The walk brakes into its goal with the sneak flag and nothing clears it when the process
        // ends; a shifting body will not step off a ledge, and stepping into a floorless portal cell
        // is exactly that. Recorded at the moment of the push because it is one of the two readings
        // that separate「推了六十 tick 一格没挪」from「顶到东西了」— the other is `exactly`.
        boolean braking = subject.fakePlayer().isShiftKeyDown();
        String before = exactly(subject);
        int pushed = drive(subject, JourneyPortalEntry.stepInto(ready, JourneyPortalEntry.STEP_IN_TICKS),
                JourneyPortalEntry.STEP_IN_TICKS + 20);
        int subjectFaults = inPortal(subject) ? 0 : 1;
        ctx.record("subject.stepIn", atDoor.toShortString() + " 推向 " + ready.toShortString()
                + "（" + pushed + " tick，起步时 shiftKeyDown=" + braking + "）→ " + where(subject)
                + " | 起 " + before + " 止 " + exactly(subject));
        ctx.record("subject.after", subjectFaults + " fault(s): " + where(subject));
        ctx.check(subjectFaults).as("F 身体最后要站在 nether_portal 方块里：" + where(subject))
                .isEqualTo(0);
    }

    /**
     * <b>Obsidian all round the doorway: the answer is「no way in」, because the frame is not a wall
     * this rung may remove.</b>
     *
     * <p>This is the branch the failure message has to get right. A body that never reached a portal
     * cell and a body that stood in one and was not transferred want opposite fixes — the geometry in
     * front of the door, or {@code Entity.handlePortal} — so the rung picks between two messages on
     * exactly this predicate. And the temptation the digging search creates is precise: the frame
     * borders every doorway cell, so it is always the geometrically cheapest thing to remove, and a
     * search that took it would report a green walk into a portal it had just put out.
     *
     * <p>The control is the same arena with ONE obsidian cell swapped for stone. Without it,「walled
     * means null」is satisfied by a search that returns null always.
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
                + (window == null ? "墙上开着两格石头也找不到进路"
                        : "站 " + window.stand().toShortString() + " 迈进 "
                          + window.cell().toShortString() + "，挖开 " + window.clear()));
        if (window == null)
            ctx.fail("THE RIG, not the subject: the middle row's doorstep is stone here ("
                    + windowCells(ctx) + ") and the search still found no way in, so 「obsidian means"
                    + " null」below measures nothing — " + JourneyPortalEntry.survey(level, bottom));

        // ---- subject ----
        stage(ctx, Front.FRAME_ONLY);
        String survey = JourneyPortalEntry.survey(level, bottom);
        JourneyPortalEntry.Doorstep walkIn = JourneyPortalEntry.find(level, bottom, start(ctx));
        JourneyPortalEntry.Doorstep dug = JourneyPortalEntry.find(level, bottom, start(ctx), true);
        ctx.record("subject.doorway", survey);
        ctx.record("subject.after", (walkIn == null && dug == null ? 0 : 1) + " fault(s): "
                + (dug == null ? "四周只剩门框，挖也没有进路 —— 这一趟会报「到不了传送门方块」"
                        : "还找得到进路 " + dug.stand().toShortString() + "，要挖 " + dug.clear()));
        ctx.check(walkIn).as("A 四面全是黑曜石时「现在就能走进去」必须是 null：" + survey).isNull();
        ctx.check(dug).as("B 连「挖开一格」也必须是 null —— 门框永远是最便宜的那块，"
                + "挖了它人是进去了，门却灭了：" + survey).isNull();
        ctx.check(level.getBlockState(slag).is(Blocks.OBSIDIAN))
                .as("C 两臂之间只差中排门口那两格 " + windowCells(ctx) + "（对照是 stone，本体是 obsidian）："
                        + level.getBlockState(slag).getBlock()).isTrue();
    }
}
