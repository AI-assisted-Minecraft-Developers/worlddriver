package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * <b>One order, many courses</b> — the shape of {@link TowerProcess} the suite has never executed.
 *
 * <h2>Why this family exists</h2>
 *
 * The manifest registers 253 scenes (202 {@code wd.*}). Exactly one of them ever puts a
 * {@link TowerProcess} on a body — {@code wd.serverTowersOutOfADeepShaft} — and it drives the
 * process <b>one course at a time</b>, inside a recursion that lands the body with a
 * {@code HoldStill} before every single call, in a shaft with stone on all four sides. So the
 * number of scenes that have ever asked one {@code TowerProcess} call to climb from a low place to
 * a high one and then checked that the body got there is <b>zero</b>. {@code wd.digUpY} and
 * {@code wd.columnRadius} are planner scenes and never build; {@code wd.serverPillarsOutOfAPit}
 * asserts only「出了坑」, which the Walker's own pillar branch satisfies with the builder entirely
 * broken.
 *
 * <p>The production caller is not shaped like the covered scene at all.
 * {@code JourneyEndRungs.smashCrystal} — the ladder's rung 20, and the <b>only</b> site in the tree
 * that asks for more than one course from a single call — issues {@code new TowerProcess(top,
 * pillar)} where {@code top} is dozens of blocks up, and it issues it in the continuation of a walk,
 * with <b>no {@code HoldStill} and no landing wait</b>. Every property that call depends on is
 * therefore unmeasured: that N courses produce N blocks of climb, that the blocks land in the
 * column the body started in, that a body still moving when the order arrives climbs at all.
 *
 * <h2>What these five arms are</h2>
 *
 * <b>Sensors. Nothing in this file touches the product.</b> Every arm's expected colour is written
 * down HERE, before the first run — a criterion chosen after seeing the number it has to accept is
 * not a criterion. They shipped {@code withRequired(false)} under this repo's promote-on-first-green
 * rule and are all required now, each having gone green on both loaders.
 *
 * <ol>
 *   <li><b>{@code wd.serverTowersTwelveCourses} — expected GREEN.</b> The missing base case: flat
 *       floor, no walls, 40 clear cells overhead, one call, twelve courses. If this is RED then the
 *       verb does not work at all in the open and the deep-shaft scene has been passing on its
 *       walls.</li>
 *   <li><b>{@code wd.serverTowersAfterAWalk} — expected RED, and the red is the point.</b> It is
 *       {@code JourneyEndRungs.smashCrystal}'s own ordering: walk, then tower from the continuation
 *       with nothing in between. {@code TowerProcess:PLACING} recomputes the support cell from the
 *       body's <b>current</b> x/z while {@code jumpFromY} stays latched at the jump, so a body still
 *       carrying the walk's momentum fills a column it is no longer over — and the READY phase's
 *       {@code if (!p.onGround()) return false} burns stuck-ticks while it settles. Neither is
 *       observable through a rig that lands the body first, which is why the covered scene lands
 *       it.</li>
 *   <li><b>{@code wd.serverTowersOnAFreePillar} — expected GREEN, and it is the drift sensor.</b>
 *       A 1x1 pillar with five clear cells of air on every side and a long drop under it: any
 *       horizontal slip at all shows up as a block placed off-column, and there is no floor to hide
 *       it. Arm 1 cannot see drift because arm 1 has a floor to drift onto.</li>
 *   <li><b>{@code wd.serverTowersToWhereItStands} — RED BY DESIGN. The criterion is written first
 *       and the engine is expected to be fixed afterwards; do NOT loosen this arm to make it
 *       green.</b> Ordering a tower to the cell the body already occupies, or to one below it,
 *       exits on {@code TowerProcess:104} with {@code "done (placed=0, feetY=…)"} — the SAME verdict
 *       shape a finished tower reports. A caller cannot tell「从未需要垒」from「垒完了」, which is
 *       exactly the ambiguity the client entry point refuses to create: {@code BotApiImpl} rejects
 *       {@code finalTargetY <= startY} with {@code "target Y must be > current feet Y"} before it
 *       ever constructs the process. The constructor has no such guard, and every server-side
 *       caller in the tree — including this file — goes through the constructor. The positive half
 *       of the arm (nothing placed, done on the first tick) is green today and is kept as the
 *       control reading.</li>
 *   <li><b>{@code wd.serverTowersWithAFullBackpack} — expected GREEN, and it is the anti-overfit
 *       arm.</b> Without it,「让 {@code ensureHoldingPlaceable} 也扫背包」is a full-marks answer to
 *       any red above, and it would be the wrong fix twice over: it would silently spend blocks the
 *       caller reserved for something else, and it would delete the one honest diagnostic this
 *       process has. {@code TowerProcess:177-209} scans slots 0..8 only outside creative, so a body
 *       whose cobblestone has settled into the bag must fail, and must fail SAYING SO. The arm
 *       therefore asserts the message itself: {@code "no placeable block in hotbar"} and NOT
 *       {@code "…out of blocks?"} — a body holding 64 cobblestone that is told it ran out of blocks
 *       has been sent to debug the wrong subsystem, and this repo has paid for that misdirection
 *       before.</li>
 * </ol>
 *
 * <h2>Rig rules this family keeps</h2>
 *
 * <ul>
 *   <li><b>Three readings between staging and driving, on every arm.</b> Where the feet actually
 *       are, that the target column is <b>air right now</b>, and how many blocks the body carries.
 *       Without them every criterion below is at risk of being {@code 0 == 0}: a column that was
 *       already stone reports twelve placed blocks for a tower that placed none, and a body that
 *       never spawned where the scene thinks reports its climb against the wrong datum. A staging
 *       mismatch is a hard {@code ctx.fail} that names the rig, never a soft check — a scene that
 *       measures the subject through a broken rig is worse than a scene that does not run.</li>
 *   <li><b>Criteria are equalities, not inequalities.</b> {@code climbed == 12} and
 *       {@code spent == 12}, never {@code >= 1} or {@code > 0}. A tower that manages one course out
 *       of twelve is a defect and passes every {@code > 0} form of this family's criteria.</li>
 *   <li><b>Three independent readings of the same climb, kept separate.</b> The body's end cell, the
 *       inventory delta, and the blocks actually standing in the start column. They disagree in
 *       informative ways — a lean spends blocks and gains height while leaving the start column
 *       empty; a rejected place gains nothing and spends nothing; a body that fell back down leaves
 *       a full column and no height — and merging them into one criterion prints three different
 *       failures as the same line.</li>
 *   <li><b>Every evidence row is {@link SceneContext#record}ed, so a PASS carries it too.</b> The
 *       harness prints the evidence map into the log only on FAIL; a green run's readings land in
 *       {@code stagewright-results.jsonl} and nowhere else. {@code placeTally()} is on every arm
 *       because it is the only reading that splits「没调过 / 无面 / 无块」— and this process never
 *       calls {@code exAlarms.notePlace}, so a place it silently loses is invisible everywhere
 *       else.</li>
 *   <li><b>Only arm 2 plans.</b> The other four drive {@code ServerWorldDriver.tick()} synchronously
 *       inside the scene body, the way the sibling {@code wd.parkourVoid*} scenes drive their
 *       Walker: TowerProcess never touches the pathfinder, so a few hundred of its ticks inside one
 *       server tick costs nothing. Arm 2's walk DOES plan, so it runs over
 *       {@code ServerAvatarManager} on real server ticks and only its tower half is synchronous.</li>
 * </ul>
 */
public final class WorldDriverTowerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // The missing base case. PROMOTE TO REQUIRED on its first green.
                Scene.of("wd.serverTowersTwelveCourses", 200,
                        WorldDriverTowerScenes::serverTowersTwelveCourses),
                // JourneyEndRungs.smashCrystal's own ordering. Expected RED.
                Scene.of("wd.serverTowersAfterAWalk", 600,
                        WorldDriverTowerScenes::serverTowersAfterAWalk),
                // The drift sensor for TowerProcess:155-157. Expected GREEN.
                Scene.of("wd.serverTowersOnAFreePillar", 200,
                        WorldDriverTowerScenes::serverTowersOnAFreePillar),
                // ⚠️ RED BY DESIGN: the criterion is written before the fix. See the class javadoc —
                // do not relax it to get a green, the whole value of the arm is that it is wrong
                // today and says which line is wrong.
                Scene.of("wd.serverTowersToWhereItStands", 200,
                        WorldDriverTowerScenes::serverTowersToWhereItStands),
                // The anti-overfit arm. Expected GREEN today, and it must STAY green through every
                // fix the five arms above provoke.
                Scene.of("wd.serverTowersWithAFullBackpack", 200,
                        WorldDriverTowerScenes::serverTowersWithAFullBackpack),
                // The three phase=JUMPING rows of 2026-08-19, isolated. Each carries its own
                // control, and the control is the arm with the fault — see the two javadocs.
                Scene.of("wd.serverTowersUnderALowCeiling", 200,
                        WorldDriverTowerScenes::serverTowersUnderALowCeiling),
                Scene.of("wd.serverTowersUnderTheNeighboursCeiling", 200,
                        WorldDriverTowerScenes::serverTowersUnderTheNeighboursCeiling));
    }

    // ---------------------------------------------------------------- rig ----

    /** The one block every arm builds with, in all three forms the code needs it in. Keeping them
     *  beside each other is not tidiness: the process is told an item ID, the inventory holds an
     *  Item and the world holds a Block, and a family that asserted about a different one of the
     *  three than it stocked would pass while measuring nothing. */
    private static final String BLOCK_ID = "minecraft:cobblestone";
    private static final Item ITEM = Items.COBBLESTONE;
    private static final Block BLOCK = Blocks.COBBLESTONE;

    /** Clear cells kept above every floor. Well past the tallest arm (12 courses) so no arm can be
     *  stopped by its own ceiling — a tower that hits rock reports the stuck guard, and that would
     *  read like a verdict on the process instead of on the arena. */
    private static final int HEADROOM = 40;

    /** Synchronous ticks any single tower drive may spend. Twelve courses cost about ten ticks each
     *  (jump, three or four airborne, place, fall back), so this is roughly three times the longest
     *  honest run and still well under the stuck guard's patience times the number of courses. */
    private static final int DRIVE_BUDGET = 400;

    /** Same altitude band the sibling process scenes use. {@code GRID_Y} is 200, so a floor here and
     *  {@link #HEADROOM} above it stays far under the overworld build limit. */
    private static int floorY(SceneContext ctx) {
        return ctx.origin().getY() + 20;
    }

    /** The switches every arm shares. {@code allowBreak} is OFF deliberately: a body that can dig has
     *  a second way up, and a scene about placing blocks that accepts a staircase is measuring
     *  something else. */
    private static void towerConfig() {
        BotConfig.allowPlace = true;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
    }

    private static void clearBox(ServerLevel level, int cx, int cz, int y0,
                                 int dxLo, int dxHi, int dyLo, int dyHi, int dzLo, int dzHi) {
        for (int dx = dxLo; dx <= dxHi; dx++)
            for (int dz = dzLo; dz <= dzHi; dz++)
                for (int dy = dyLo; dy <= dyHi; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y0 + dy, cz + dz),
                            Blocks.AIR.defaultBlockState());
    }

    /** Air out the working box, then lay a {@code (2*half+1)} square of stone at {@code floorY} with
     *  {@link #HEADROOM} clear above it and <b>no walls</b>. The cleanup is registered BEFORE the
     *  build, so an arm that fails mid-drive still hands the shared dogfood world back empty. */
    private static void stageFlatArena(SceneContext ctx, int half, int floorY) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int pad = half + 2;
        ctx.cleanup(() -> clearBox(level, cx, cz, floorY, -pad, pad, -3, HEADROOM + 4, -pad, pad));
        clearBox(level, cx, cz, floorY, -pad, pad, -3, HEADROOM + 4, -pad, pad);
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        Blocks.STONE.defaultBlockState());
    }

    /**
     * Three physics steps with no input, so the body is standing flush before anything is measured.
     *
     * <p>{@code wd.jumpWaitsForOnGround} settles the same way and for the same reason: a body
     * that has never moved reports {@code onGround() == false}, and vanilla's jump refuses a
     * press on that. An unsettled start would spend its first ticks landing and
     * charge them to the tower, which is a reading about the spawn and not about the verb.
     *
     * <p>⚠️ {@link #serverTowersAfterAWalk} deliberately does NOT settle. Not being landed is the
     * one variable that arm exists to hold.
     */
    private static void settle(ServerPlayerBody av) {
        for (int i = 0; i < 3; i++) av.step();
    }

    /** Total count of an item across the WHOLE inventory, not the hotbar — a place spends a block
     *  whichever slot it came out of, and arm 5 stocks the bag on purpose. */
    private static int carrying(ServerPlayer fp, Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }

    /** Cell-by-cell readback of a column, as {@code y221=cobblestone y222=air …}. The direct reading
     *  of what a tower built, which neither the inventory delta nor the body's end cell can give. */
    private static String column(ServerLevel level, int x, int y0, int n, int z) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append('y').append(y0 + i).append('=').append(BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(new BlockPos(x, y0 + i, z)).getBlock()).getPath());
        }
        return sb.toString();
    }

    private static int columnOf(ServerLevel level, int x, int y0, int n, int z, Block want) {
        int c = 0;
        for (int i = 0; i < n; i++)
            if (level.getBlockState(new BlockPos(x, y0 + i, z)).is(want)) c++;
        return c;
    }

    private static int columnSolid(ServerLevel level, int x, int y0, int n, int z) {
        int c = 0;
        for (int i = 0; i < n; i++)
            if (!level.getBlockState(new BlockPos(x, y0 + i, z)).isAir()) c++;
        return c;
    }

    /**
     * The three readings every arm takes BETWEEN staging and driving, recorded and then enforced.
     *
     * <p>Enforced with {@link SceneContext#fail} rather than {@link SceneContext#check} on purpose:
     * these are statements about the rig, and a rig that is wrong makes every criterion downstream
     * meaningless rather than merely unmet. The message says so, so nobody reads a staging fault as
     * a TowerProcess defect.
     *
     * @param wantFeet the foot cell the body must be standing in, or {@code null} to skip that half
     *                 (the walk arm latches its tower's start from where the walk actually left the
     *                 body, and checks THAT against the runway end itself)
     */
    private static void requireStaged(SceneContext ctx, String arm, ServerPlayer fp, BlockPos wantFeet,
                                      int colX, int colY0, int colN, int colZ, int wantCarry) {
        ServerLevel level = (ServerLevel) fp.level();
        BlockPos feet = fp.blockPosition();
        int solid = columnSolid(level, colX, colY0, colN, colZ);
        int carry = carrying(fp, ITEM);
        String row = String.format(Locale.ROOT,
                "feet=%s%s | target column x=%d z=%d y=%d..%d -> %s | carrying %d %s (expected %d)",
                feet.toShortString(),
                wantFeet == null ? "" : " (expected " + wantFeet.toShortString() + ")",
                colX, colZ, colY0, colY0 + colN - 1, column(level, colX, colY0, colN, colZ),
                carry, BuiltInRegistries.ITEM.getKey(ITEM), wantCarry);
        ctx.record(arm + ".staged", row);
        if (wantFeet != null && !feet.equals(wantFeet))
            ctx.fail(arm + ": THE RIG, not the subject — the body is not standing where the scene"
                    + " staged it, so every climb measured against that datum is meaningless: " + row);
        if (solid != 0)
            ctx.fail(arm + ": THE RIG, not the subject — " + solid + " of the " + colN + " target"
                    + " cells are already solid, so 'the tower filled this column' would be true"
                    + " before the tower ran: " + row);
        if (carry != wantCarry)
            ctx.fail(arm + ": THE RIG, not the subject — the inventory does not hold what the scene"
                    + " stocked, so the spend delta measures the staging: " + row);
    }

    // -------------------------------------------------------------- drive ----

    /** Everything one tower order produced, read three independent ways. */
    private static final class Run {
        int startX, startZ, startFeetY, endX, endZ, endFeetY;
        int ticks, spent, carriedBefore, carriedAfter, columnPlaced, columnSolid;
        boolean finished;
        String lastError, placeTally, columnAfter;

        int climbed() { return endFeetY - startFeetY; }
    }

    /**
     * Latch the start, run ONE {@link TowerProcess} order to completion (or to {@code budget}), and
     * record every reading it produced.
     *
     * <p>The process is constructed directly, the way {@code JourneyEndRungs.smashCrystal} does,
     * NOT through {@code BotApiImpl}: the API layer holds guards the constructor does not (see the
     * class javadoc on arm 4), and routing through it would test the guards instead of the process.
     *
     * @param columnCells how many cells of the START column to read back afterwards — the reading
     *                    that catches a tower which gained height by leaning into another column
     * @param beforeEachTick run immediately before every {@code driver.tick()}; the seam
     *                       {@link #apexWatch} samples through, and a no-op everywhere else
     */
    private static Run runTower(SceneContext ctx, String arm, ServerWorldDriver driver,
                                int targetY, int columnCells, int budget, Runnable beforeEachTick) {
        ServerLevel level = ctx.level();
        ServerPlayer fp = driver.fakePlayer();
        Run r = new Run();
        BlockPos start = fp.blockPosition();
        r.startX = start.getX(); r.startZ = start.getZ(); r.startFeetY = start.getY();
        r.carriedBefore = carrying(fp, ITEM);

        driver.runProcess(new TowerProcess(targetY, BLOCK_ID));
        int t = 0;
        for (; t < budget && !driver.finished(); t++) {
            beforeEachTick.run();
            driver.tick();
        }
        r.ticks = t;
        r.finished = driver.finished();

        BlockPos end = fp.blockPosition();
        r.endX = end.getX(); r.endZ = end.getZ(); r.endFeetY = end.getY();
        r.carriedAfter = carrying(fp, ITEM);
        r.spent = r.carriedBefore - r.carriedAfter;
        r.lastError = driver.botState().builder.lastError;
        r.placeTally = driver.avatar().placeTally();
        r.columnAfter = column(level, r.startX, r.startFeetY, columnCells, r.startZ);
        r.columnPlaced = columnOf(level, r.startX, r.startFeetY, columnCells, r.startZ, BLOCK);
        r.columnSolid = columnSolid(level, r.startX, r.startFeetY, columnCells, r.startZ);

        // Recorded on PASS as well as on FAIL — the harness only prints the evidence map when a
        // scene fails, and「绿的那一趟垒了几块、花了几块」is as much of the conclusion as the colour.
        ctx.record(arm + ".order", "TowerProcess(targetY=" + targetY + ", " + BLOCK_ID + ")"
                + " from feet y=" + r.startFeetY + " (" + (targetY - r.startFeetY) + " courses asked)");
        ctx.record(arm + ".drive", "ran " + r.ticks + " ticks of " + budget
                + " | finished=" + r.finished
                + (r.finished ? "" : " ⚠️ the budget ran out — the process never reported a verdict"));
        ctx.record(arm + ".lastError", r.lastError == null ? "null (the process never set one)" : r.lastError);
        ctx.record(arm + ".feet", "start " + r.startX + "," + r.startFeetY + "," + r.startZ
                + " -> end " + r.endX + "," + r.endFeetY + "," + r.endZ
                + " | climbed " + r.climbed()
                + " | drift " + (r.endX - r.startX) + "," + (r.endZ - r.startZ));
        ctx.record(arm + ".stock", r.carriedBefore + " -> " + r.carriedAfter
                + " " + BuiltInRegistries.ITEM.getKey(ITEM) + " (spent " + r.spent + ")");
        ctx.record(arm + ".column", "start column x=" + r.startX + " z=" + r.startZ
                + " y=" + r.startFeetY + ".." + (r.startFeetY + columnCells - 1)
                + " -> " + r.columnAfter + " (" + r.columnPlaced + " placed, " + r.columnSolid + " solid)");
        // The only reading that splits「没调过 / 无面 / 无块」. TowerProcess never calls
        // exAlarms.notePlace, so a place it loses is silent in every other channel.
        ctx.record(arm + ".placeTally", r.placeTally);
        WorldDriverCommon.LOG.info("[tower:{}] ticks={} climbed={} spent={} columnPlaced={}"
                        + " drift={},{} lastError={}", arm, r.ticks, r.climbed(), r.spent,
                r.columnPlaced, r.endX - r.startX, r.endZ - r.startZ, r.lastError);
        return r;
    }

    /** A body, its driver, and the cleanup that removes both. */
    private static ServerWorldDriver body(SceneContext ctx, double x, double y, double z) {
        return SceneBody.managed(ctx, x, y, z);
    }

    // ---------------------------------------------------------------- arms ----

    /**
     * <b>The missing base case: twelve courses from one order, on open ground.</b>
     *
     * <p>Flat 7x7 stone, the body in the middle, no walls, 40 clear cells overhead, 64 cobblestone
     * in hotbar slot 0. The covered scene ({@code wd.serverTowersOutOfADeepShaft}) differs in three
     * ways at once — one course per call, a {@code HoldStill} before each, and stone on all four
     * sides — so nothing it reports transfers to this shape.
     *
     * <h2>判据 — four, all equalities, and none of them redundant</h2>
     *
     * <ol>
     *   <li><b>{@code endFeetY == startFeetY + 12}</b>, not {@code >=}. A tower that manages one
     *       course out of twelve is a defect, and passes every {@code > 0} form of this.</li>
     *   <li><b>exactly 12 blocks spent.</b> Fewer means courses that never cost anything (a climb
     *       that came from somewhere else); more means placements that did not become the tower.</li>
     *   <li><b>all 12 cells of the START column are cobblestone.</b> Height and spend together still
     *       admit a tower that leaned: blocks placed, height gained, and the column the body began
     *       in left empty. This is the only reading that sees it.</li>
     *   <li><b>the verdict reads {@code done (placed=12}.</b> The process's own count is verified
     *       against the world at {@code TowerProcess:164}, so a disagreement between it and criteria
     *       2/3 localises the defect to the place actuator rather than to the state machine.</li>
     * </ol>
     */
    private static void serverTowersTwelveCourses(SceneContext ctx) {
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int courses = 12, stock = 64;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        stageFlatArena(ctx, 3, floorY);

        ServerWorldDriver driver = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        // Slot 0, because that is the constraint TowerProcess actually has outside creative — see
        // arm 5, which holds the other side of it.
        fp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
        settle(driver.avatar());

        requireStaged(ctx, "tower", fp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);

        final int startFeetY = fp.blockPosition().getY();
        Run r = runTower(ctx, "tower", driver, startFeetY + courses, courses, DRIVE_BUDGET, () -> {});

        ctx.check(r.endFeetY).as("A 塔要垒到定高: end feet y (start " + startFeetY + " + " + courses
                + " courses = " + (startFeetY + courses) + "; an equality on purpose — one course out"
                + " of twelve is a defect and would pass any '>' form of this)")
                .isEqualTo(startFeetY + courses);
        ctx.check(r.spent).as("B 恰好花掉 " + courses + " 块: cobblestone spent (started with " + stock
                + ", ended with " + r.carriedAfter + "); fewer means height that cost nothing, more"
                + " means placements that did not become the tower").isEqualTo(courses);
        ctx.check(r.columnPlaced).as("C 世界侧那 " + courses + " 格全是 " + BLOCK_ID + ": " + r.columnAfter
                + " — the only reading that catches a tower which gained height by leaning into"
                + " another column").isEqualTo(courses);
        ctx.check(r.lastError != null && r.lastError.startsWith("done (placed=" + courses))
                .as("D 进程自己的判词要是 'done (placed=" + courses + "': got »" + r.lastError
                        + "«; its count is verified against the world at TowerProcess:164, so a"
                        + " disagreement with B/C localises the defect to the place actuator").isTrue();
    }

    /**
     * <b>{@code JourneyEndRungs.smashCrystal}'s own ordering, and the arm expected to be RED.</b>
     *
     * <p>A twelve-cell runway, an {@link IntentProcess} to its far end, and then — from the walk's
     * continuation, with <b>no {@code HoldStill} and no {@code onGround} wait</b> — an eight-course
     * tower. That is verbatim the shape the only multi-course production caller uses, and it is the
     * one shape the covered scene structurally cannot produce: {@code towerOneCourse} lands the body
     * before every call precisely because a moving body was known to burn its patience.
     *
     * <h2>What is expected to break, and which criterion sees which</h2>
     *
     * <ul>
     *   <li><b>Drift.</b> {@code TowerProcess:155-157} computes the support cell from the body's
     *       CURRENT x/z, while {@code jumpFromY} was latched at the jump. A body still carrying the
     *       walk's momentum fills a column it has already left — criteria C and D.</li>
     *   <li><b>The settle tax.</b> READY returns early while {@code !onGround()}, and
     *       {@code stuckTicks} counts from the first tick regardless — criteria A and B, via a run
     *       that ends on the stuck guard with a full stack in hand.</li>
     * </ul>
     *
     * <p><b>Do not add a {@code HoldStill} to make this green.</b> The green would be a statement
     * about a scene that no caller resembles. If the answer turns out to be "callers must land
     * first", then that belongs in {@code TowerProcess} — as a landing wait that does not spend the
     * stuck budget — and this arm goes green without being touched.
     */
    private static void serverTowersAfterAWalk(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int runway = 12, courses = 8, stock = 64;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        // The only arm that plans. Real slices over real server ticks — a synchronous A* inside one
        // server tick is the shape that has wedged this suite before.
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);

        // The box is not centred on the origin, so this arm stages its own instead of the square.
        ctx.cleanup(() -> clearBox(level, cx, cz, floorY, -3, runway + 3, -3, HEADROOM + 4, -3, 3));
        clearBox(level, cx, cz, floorY, -3, runway + 3, -3, HEADROOM + 4, -3, 3);
        for (int dx = 0; dx <= runway; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        Blocks.STONE.defaultBlockState());

        ServerWorldDriver driver = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
        // NO settle(): starting un-landed is arm 2's variable, and the walk provides it.

        // Staged against the runway END, which is where the tower is expected to start. The tower's
        // own column is re-checked in the continuation against where the walk ACTUALLY left the body.
        requireStaged(ctx, "walk", fp, new BlockPos(cx, standY, cz),
                cx + runway, standY, courses, cz, stock);

        final BlockPos far = new BlockPos(cx + runway, standY, cz);
        ServerAvatarManager.register(driver.runProcess(new IntentProcess(new Intent(new Goal.Block(far)))));
        ctx.await(driver::finished).within(400).then(() -> {
            ServerAvatarManager.unregister(driver);
            BlockPos at = fp.blockPosition();
            ctx.record("walk.end", "arrived at " + at.toShortString() + " (runway end "
                    + far.toShortString() + ") | endReason=" + driver.botState().mc_goto.endReason
                    + " | onGround=" + fp.onGround()
                    + " | h=" + String.format(Locale.ROOT, "%.4f",
                            Math.hypot(fp.getDeltaMovement().x, fp.getDeltaMovement().z)));
            if (at.getY() != standY || at.getX() < cx + runway - 1)
                ctx.fail("serverTowersAfterAWalk: THE RIG, not the subject — the walk did not reach"
                        + " the runway end, so nothing this arm measures about the tower would be"
                        + " about a tower started from a walk: ended at " + at.toShortString());

            // The tower's own three staged readings, taken where the walk actually stopped.
            requireStaged(ctx, "tower", fp, null, at.getX(), at.getY(), courses, at.getZ(), stock);

            final int startX = at.getX(), startZ = at.getZ(), startFeetY = at.getY();
            Run r = runTower(ctx, "tower", driver, startFeetY + courses, courses, DRIVE_BUDGET, () -> {});

            ctx.check(r.endFeetY).as("A 涨 " + courses + " 格: end feet y (start " + startFeetY
                    + "; the walk's momentum is the only difference from wd.serverTowersTwelveCourses)")
                    .isEqualTo(startFeetY + courses);
            ctx.check(r.spent).as("B 恰好耗 " + courses + " 块: cobblestone spent").isEqualTo(courses);
            ctx.check(r.columnPlaced).as("C 那 " + courses + " 格全是放的方块: " + r.columnAfter
                    + " — a leaning tower spends its blocks and gains its height while leaving this"
                    + " column empty, and only this row can tell the two apart").isEqualTo(courses);
            ctx.check(r.endX == startX && r.endZ == startZ)
                    .as("D 终点 x/z 等于起塔时的 x/z: started at x=" + startX + " z=" + startZ
                            + ", ended at x=" + r.endX + " z=" + r.endZ + " — TowerProcess:155-157"
                            + " recomputes the support from the CURRENT x/z against a jumpFromY"
                            + " latched at the jump, so a moving body builds under where it is going"
                            + " rather than where it jumped").isTrue();
        });
    }

    /**
     * <b>A 1x1 pillar in open air: the drift sensor.</b>
     *
     * <p>Same verb, same block, same course-by-course machinery as arm 1, with the floor taken away.
     * Five clear cells of air on every side and twenty-five below, so <b>any</b> horizontal slip is
     * both visible and unrecoverable — where arm 1 has a 7x7 floor to drift onto and would report a
     * perfectly good climb one column over.
     *
     * <p>The subject is {@code TowerProcess:155-157}: the support cell is recomputed from the live
     * x/z on the PLACING tick, so the process is only correct while the body is exactly over the
     * column it jumped from. Sixteen blocks are stocked for ten courses — enough slack that "ran
     * out" cannot be confused with "placed somewhere else", which the {@code stock} row makes
     * explicit either way.
     *
     * <p>Expected GREEN: with no horizontal input the body should not drift at all, and a green
     * here is what makes arm 2's red attributable to the walk's momentum rather than to the process
     * being unable to hold a column under any circumstances.
     */
    private static void serverTowersOnAFreePillar(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int topY = ctx.origin().getY() + 30, standY = topY + 1;
        final int courses = 10, stock = 16, drop = 25;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);

        final int baseY = topY - 3;                        // a pillar, not a single block
        final int catchY = baseY - drop - 1;
        ctx.cleanup(() -> clearBox(level, cx, cz, topY, -6, 6, catchY - topY, HEADROOM + 4, -6, 6));
        clearBox(level, cx, cz, topY, -6, 6, catchY - topY, HEADROOM + 4, -6, 6);
        // A floor to catch a body that slips off, so the arm reports a drift instead of a fall out
        // of the world — the two want completely different fixes and must not print alike.
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, catchY, cz + dz),
                        Blocks.STONE.defaultBlockState());
        for (int y = baseY; y <= topY; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz), Blocks.STONE.defaultBlockState());

        ServerWorldDriver driver = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
        settle(driver.avatar());

        requireStaged(ctx, "tower", fp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);

        final int startX = fp.blockPosition().getX(), startZ = fp.blockPosition().getZ();
        final int startFeetY = fp.blockPosition().getY();
        ctx.record("rig", "pillar x=" + cx + " z=" + cz + " y=" + baseY + ".." + topY
                + " | stand y=" + standY + " | air within 5 cells on every side | "
                + drop + " clear cells under the pillar, catch floor y=" + catchY);
        Run r = runTower(ctx, "tower", driver, startFeetY + courses, courses, DRIVE_BUDGET, () -> {});

        ctx.check(r.endX == startX && r.endZ == startZ)
                .as("A 不许漂: started x=" + startX + " z=" + startZ + ", ended x=" + r.endX
                        + " z=" + r.endZ + " — there is nothing to stand on beside this column, so"
                        + " any drift is a fall to y=" + (catchY + 1)).isTrue();
        ctx.check(r.endFeetY).as("B 涨 " + courses + " 格: end feet y (start " + startFeetY + ")")
                .isEqualTo(startFeetY + courses);
        ctx.check(r.columnPlaced).as("C 柱身 " + courses + " 格全是放的方块: " + r.columnAfter
                + " (stocked " + stock + ", spent " + r.spent + " — the slack is why 'ran out' and"
                + " 'placed elsewhere' cannot be confused here)").isEqualTo(courses);
    }

    /**
     * <b>⚠️ RED BY DESIGN — the criterion is written first and the engine is fixed afterwards.</b>
     * Do not relax this arm to get a green; a criterion adjusted to fit today's answer records
     * nothing.
     *
     * <p>Two orders, on the same flat rig: tower to the cell the body already stands in, and tower
     * to one three blocks BELOW it. Both exit on the first tick at {@code TowerProcess:104}:
     *
     * <pre>{@code
     * if (feetY >= targetY && p.onGround()) {
     *     st.builder.lastError = "done (placed=" + placed + ", feetY=" + feetY + ")";
     * }
     * }</pre>
     *
     * <h2>判据 — two halves, and only one of them is red</h2>
     *
     * <ol>
     *   <li><b>Green today, and kept as the control reading:</b> nothing is placed, nothing is
     *       spent, the foot cell stays air, and the process reports on its FIRST tick. A no-op that
     *       silently built something, or that spun for the whole budget, would be a much worse
     *       defect than the one below and nothing else in the family would see it.</li>
     *   <li><b>RED today:</b> the verdict must not read {@code done (placed=…}. That prefix is the
     *       verdict of a tower that <i>built</i>, so a caller receiving it cannot distinguish
     *       「从未需要垒」from「垒完了」— and the distinction is exactly what it needs in order to
     *       decide whether to retry, to give up, or to carry on. The client entry point already
     *       refuses to create the ambiguity: {@code BotApiImpl} rejects {@code finalTargetY <=
     *       startY} with {@code "target Y (…) must be > current feet Y (…)"} before constructing
     *       anything. The constructor has no such guard, and every server-side caller in the tree —
     *       {@code JourneyEndRungs}, {@code JourneyShaft}, {@code wd.serverTowersOutOfADeepShaft},
     *       this file — goes through the constructor.</li>
     * </ol>
     *
     * <p>The fix cannot be a constructor guard alone: {@code startFeetY} is not known until the
     * first tick, so the process has to produce a DIFFERENT verdict on that tick rather than refuse
     * at construction. Which is why the criterion is phrased as "not that prefix" instead of naming
     * a replacement string this file would then be dictating.
     *
     * <p>Both legs keep their own {@code record} prefix. One key written twice is a silent
     * overwrite, and the second leg's answer would quietly replace the first's.
     *
     * <p><b>And each leg gets its own body two cells away</b>, rather than reusing one. Sharing
     * would make the second leg's staged readings depend on the first leg's outcome: a first leg
     * that (defectively) placed a block would leave the second one failing its rig check, and the
     * message would blame the staging for a defect the first leg had already caught.
     */
    private static void serverTowersToWhereItStands(SceneContext ctx) {
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int stock = 64;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        stageFlatArena(ctx, 3, floorY);

        noOpLeg(ctx, "atLevel", cx - 2, cz, standY, stock, 0, "targetY == 当前脚格");
        noOpLeg(ctx, "below", cx + 2, cz, standY, stock, -3, "targetY 比当前脚格低 3 格");
    }

    /** One order that asks for no climb at all: its own body, its own column, its own record keys. */
    private static void noOpLeg(SceneContext ctx, String arm, int x, int z, int standY,
                                int stock, int dTargetY, String order) {
        ServerWorldDriver driver = body(ctx, x + 0.5, standY, z + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
        settle(driver.avatar());

        requireStaged(ctx, arm, fp, new BlockPos(x, standY, z), x, standY, 1, z, stock);
        final int startFeetY = fp.blockPosition().getY();
        Run r = runTower(ctx, arm, driver, startFeetY + dTargetY, 1, DRIVE_BUDGET, () -> {});
        noOpCriteria(ctx, arm, r, order + " (" + (startFeetY + dTargetY) + ")");
    }

    /** The three criteria both legs of {@link #serverTowersToWhereItStands} share. */
    private static void noOpCriteria(SceneContext ctx, String arm, Run r, String order) {
        ctx.check(r.spent == 0 && r.columnSolid == 0)
                .as(arm + " A 什么都不该垒 [" + order + "]: spent " + r.spent + ", foot cell now »"
                        + r.columnAfter + "« — a no-op that quietly built something is a worse defect"
                        + " than the verdict wording and nothing else in this family would see it")
                .isTrue();
        ctx.check(r.ticks).as(arm + " B 第一 tick 就该结束 [" + order + "]: ran " + r.ticks
                + " ticks, finished=" + r.finished).isEqualTo(1);
        ctx.check(r.lastError != null && !r.lastError.startsWith("done (placed="))
                .as(arm + " C ⚠️ RED BY DESIGN —「从未需要垒」和「垒完了」不能长得一样 [" + order
                        + "]: got »" + r.lastError + "«, and 'done (placed=…' is what a tower that"
                        + " actually built reports. BotApiImpl already refuses this argument"
                        + " ('target Y must be > current feet Y'); the constructor every server-side"
                        + " caller uses does not. Fix the verdict, not this line").isTrue();
    }

    /**
     * <b>The anti-overfit arm: nine tools in the hotbar, the cobblestone in the bag.</b>
     *
     * <p>{@code TowerProcess:177-209} scans slots 0..8 for the preferred block and only walks the
     * rest of the inventory in creative mode. That is a real constraint with a real reason —
     * spending blocks the caller did not put in hand is a side effect no verb should have — and the
     * cheapest way to turn any of the five arms above green is to widen it. This arm is what makes
     * that cost something.
     *
     * <h2>判据 — two clauses, and the second is about the MESSAGE</h2>
     *
     * <ol>
     *   <li><b>Nothing is placed and nothing is spent.</b> The bag's 64 cobblestone are still there
     *       and the target column is still air.</li>
     *   <li><b>The verdict is {@code "no placeable block in hotbar"} and NOT
     *       {@code "…out of blocks?"}.</b> Both are exits from the same tick loop and both leave a
     *       caller with a tower that did not happen; only one of them sends the reader to the right
     *       place. A body holding 64 cobblestone told it ran out of blocks has been pointed at the
     *       inventory when the fault is in the hotbar scan — this repo has already spent rounds on
     *       exactly that class of misdirection, so the wording is a criterion here, not a nicety.
     *       The two clauses are checked separately although the first implies the second: a
     *       deliberate reword must update clause 2a and will still be caught by 2b if it starts
     *       naming the wrong cause.</li>
     * </ol>
     */
    private static void serverTowersWithAFullBackpack(SceneContext ctx) {
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int courses = 6, stock = 64, bagSlot = 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        stageFlatArena(ctx, 3, floorY);

        ServerWorldDriver driver = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        // Nine hotbar slots of things that are emphatically not blocks — tools, buckets, food — so
        // the hotbar is FULL rather than merely lacking cobblestone. A hotbar with an empty slot
        // would let a future "shuffle the bag into the gap" fix pass without being noticed.
        Item[] hotbar = {
                Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.WOODEN_PICKAXE,
                Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET,
                Items.BREAD, Items.COOKED_BEEF, Items.APPLE };
        for (int s = 0; s < hotbar.length; s++) fp.getInventory().items.set(s, new ItemStack(hotbar[s]));
        fp.getInventory().items.set(bagSlot, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
        settle(driver.avatar());

        requireStaged(ctx, "tower", fp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);
        ctx.record("tower.hotbar", "slots 0..8 = pickaxes/buckets/food (no BlockItem anywhere in the"
                + " hotbar); " + stock + " " + BLOCK_ID + " parked in inventory slot " + bagSlot);

        final int startFeetY = fp.blockPosition().getY();
        Run r = runTower(ctx, "tower", driver, startFeetY + courses, courses, DRIVE_BUDGET, () -> {});

        ctx.check(r.spent == 0 && r.columnSolid == 0)
                .as("A 一块也不许放: spent " + r.spent + " of the " + stock + " parked in the bag,"
                        + " target column now »" + r.columnAfter + "« — reaching into the main"
                        + " inventory would spend blocks the caller never put in hand").isTrue();
        ctx.check(r.lastError).as("B-a 判词要说对原因: the exit must name the HOTBAR"
                        + " (place tally: " + r.placeTally + ")")
                .isEqualTo("no placeable block in hotbar");
        ctx.check(r.lastError != null && !r.lastError.contains("out of blocks"))
                .as("B-b 判词不许说成缺方块: got »" + r.lastError + "« while the body carries "
                        + r.carriedAfter + " " + BLOCK_ID + ". Kept as its own clause although B-a"
                        + " implies it — a deliberate reword updates B-a, and B-b still catches a"
                        + " reword that starts naming the wrong cause").isTrue();
    }

    // ------------------------------------------------- the ceiling arms ----

    /** Ticks a tower may spend learning that it cannot rise. "Is the head cell solid?" is a block
     *  read; sixty ticks of face-down jumping is what it costs today, once per course of every climb
     *  a mine exit runs. Ten leaves room for the settle and one attempt without leaving room for a
     *  second sixty-tick patience. */
    private static final int CEILING_VERDICT_TICKS = 10;

    /** The body's own box as cells, so a straddle is a reading rather than an inference. The whole
     *  point of the neighbour arm is that {@code blockPosition()} answers the same in both of its
     *  runs while this row does not. */
    private static String footprint(ServerPlayer fp) {
        AABB b = fp.getBoundingBox();
        return String.format(Locale.ROOT, "x=%.2f z=%.2f box x[%.2f,%.2f] z[%.2f,%.2f] -> 列 x %d..%d"
                        + " z %d..%d（blockPosition 只说得出 %d,%d）", fp.getX(), fp.getZ(),
                b.minX, b.maxX, b.minZ, b.maxZ,
                Mth.floor(b.minX + 1.0E-7), Mth.floor(b.maxX - 1.0E-7),
                Mth.floor(b.minZ + 1.0E-7), Mth.floor(b.maxZ - 1.0E-7),
                fp.blockPosition().getX(), fp.blockPosition().getZ());
    }

    /**
     * A per-tick sampler that records the highest y the body ever reached during one order.
     *
     * <p>It is the reading that separates the two ways a course can produce no height, and the
     * ladder's own row could not: {@code apexFeetY} is a floored CELL, so「跳了但只升了 0.2」and
     * 「一次也没起跳」print the same number. A jump that fired peaks at {@code +1.25} in the open and
     * at {@code +0.2} under a lid; one that never fired never leaves {@code +0.00}. Paired with
     * {@code dbgLastJumpTick}, which says whether an impulse was emitted at all.
     */
    private static Runnable apexWatch(ServerPlayer fp, String arm, SceneContext ctx) {
        double[] apex = { fp.getY() };
        double[] base = { fp.getY() };
        return () -> {
            apex[0] = Math.max(apex[0], fp.getY());
            ctx.record(arm + ".apex", String.format(Locale.ROOT,
                    "起 y=%.2f 最高 y=%.2f（升 %.2f 格；满一跳 +1.25，撞盖子 +0.20，没起跳 +0.00）",
                    base[0], apex[0], apex[0] - base[0]));
        };
    }

    /**
     * <b>A ceiling one cell over the head: the wedge that cost the ladder rungs 10 through 12.</b>
     *
     * <p>Three independent legs of the 2026-08-19 journey run reported the same row, and it is a row
     * the process cannot produce on purpose:
     *
     * <pre>
     * vein2.exit#3.climb.1.stalled = stuck (no Y gain in 60t: placed=0, holding=64, phase=JUMPING, apexFeetY=44)
     * vein2.exit#3.climb.1.state   = onGround=true inWater=false y=44.00
     * crystal.0.climb              = stuck (no Y gain in 60t: placed=0, holding=21, phase=JUMPING, apexFeetY=63)
     * </pre>
     *
     * A body on the ground, holding a stack, not in water, sixty ticks, nothing placed. Read against
     * {@link TowerProcess}'s state machine those four facts pick out exactly one state: {@code
     * JUMPING} is left on {@code p.getY() >= jumpFromY + 1.0} and on nothing else, and the jump key
     * is released on its first tick — so <b>a jump that fails to lift the body one whole block ends
     * the order</b>. Not the course: the ORDER. There is no way back to {@code READY}, so the
     * process spends every remaining tick face-down over a cell it has already decided not to fill.
     * {@code apexFeetY} equal to the start height is the corroboration: a clean 0.42 jump peaks at
     * {@code +1.25} and would have moved it.
     *
     * <p>A block at {@code feet+2} is the cheapest way to produce that short rise — the body's box is
     * {@code [y, y+1.8]}, so a whole block of rise needs {@code y+2} free, and a jump into it clips
     * at {@code +0.2} and drops straight back. That is not an exotic arena: it is a two-cell mine
     * corridor, which is what {@code vein2.exit} climbs out of.
     *
     * <h2>控制臂先跑，而且它是有故障的那一臂</h2>
     *
     * The fault predicate is the caller's own bottom line — <b>the tower did not deliver the height
     * it was ordered</b>. Under the ceiling that must be 1 fault: a tower cannot dig, and this arm
     * does not want it to. A control that climbs anyway means the ceiling was never in the way and
     * every reading below is about a different arena, so it is a hard {@code fail} naming the rig.
     *
     * <p>The control is therefore where the defect lives, and its three criteria are about the
     * VERDICT rather than the height:
     *
     * <ol>
     *   <li><b>the order ends.</b> {@code finished == true} — a process that cannot climb must say
     *       so and stop, not spin out its caller's budget.</li>
     *   <li><b>it ends quickly.</b> Learning that the head is in rock is a block read, not a
     *       sixty-tick experiment, and a mine exit pays that tax once per course.</li>
     *   <li><b>the verdict names the obstruction</b> and does not name {@code phase=JUMPING}. A
     *       phase says where the code stopped; the caller needs the CELL, because the caller is the
     *       one that can mine it — {@code JourneyShaft.ascendByTowering} already tries to.</li>
     * </ol>
     *
     * <p>The subject is the same order over the same rig with that one ceiling row taken away, and
     * it must climb all four courses. Without it「垒不上去」would also be satisfied by a tower that
     * cannot climb anywhere.
     */
    private static void serverTowersUnderALowCeiling(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int courses = 4, stock = 64;
        final int ceilY = standY + 2;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);

        // ---- control: the ceiling is in, and the tower must NOT get out from under it ----
        stageFlatArena(ctx, 3, floorY);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, ceilY, cz + dz),
                        Blocks.STONE.defaultBlockState());

        ServerWorldDriver control = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer cfp = control.fakePlayer();
        cfp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        cfp.getInventory().selected = 0;
        settle(control.avatar());
        // Only the cells UNDER the lid: the staged-column check asserts「the tower's target column is
        // air right now」, and the lid deliberately sits inside the four cells the order asks for. A
        // rig check that failed on the arena's own subject would report the ceiling as a staging bug.
        requireStaged(ctx, "control", cfp, new BlockPos(cx, standY, cz), cx, standY, ceilY - standY, cz, stock);
        ctx.record("control.ceiling", "stone at y=" + ceilY + " over the whole 7x7 | feet y=" + standY
                + ", head cell y=" + (standY + 1) + ", so the cell a one-block rise needs is y=" + ceilY
                + " = " + level.getBlockState(new BlockPos(cx, ceilY, cz)).getBlock());

        Run c = runTower(ctx, "control", control, standY + courses, ceilY - standY, DRIVE_BUDGET,
                apexWatch(cfp, "control", ctx));
        ctx.record("control.jumpTick", "last emitted jump impulse t=" + control.avatar().dbgLastJumpTick()
                + "。配合 control.apex 读：非 -1 且 apex ≈ +0.20 = 跳了、撞盖子、又落回来"
                + "（修之前就是这一行，接着 60 tick 卡在 JUMPING）；-1 且 apex = +0.00 = 根本没起跳，"
                + "READY 先把盖子点名了");
        int controlFaults = c.climbed() == courses ? 0 : 1;
        ctx.record("control.after", controlFaults + " fault(s): 顶上封死，" + courses + " 格里涨了 "
                + c.climbed() + " 格；判词 »" + c.lastError + "«");
        WorldDriverCommon.LOG.info("[tower:ceiling] control climbed={} ticks={} finished={} err={}",
                c.climbed(), c.ticks, c.finished, c.lastError);
        if (controlFaults == 0)
            ctx.fail("THE RIG, not the subject: the body climbed " + c.climbed() + " courses with"
                    + " stone at y=" + ceilY + " over every column of the arena, so the ceiling was"
                    + " never in the way and nothing this arm measures is about one — " + c.columnAfter);
        control.fakePlayer().discard();

        ctx.check(c.finished).as("A 垒不上去也要给判词，不许把预算耗光: ran " + c.ticks + " of "
                + DRIVE_BUDGET + " ticks, finished=" + c.finished + ", verdict »" + c.lastError
                + "«。JUMPING 只有一条出路（p.getY() >= jumpFromY + 1.0），跳不满一格就再也回不到"
                + " READY —— 这正是 vein2.exit#3 / crystal.0 那三行 phase=JUMPING 的来处").isTrue();
        ctx.check(c.ticks <= CEILING_VERDICT_TICKS)
                .as("B 判词要来得快: " + c.ticks + " tick（上限 " + CEILING_VERDICT_TICKS
                        + "）。「头顶是不是实心」是一次方块读数，不是一场 60 tick 的实验，"
                        + "而爬井的每一级都要付这笔钱").isTrue();
        ctx.check(c.lastError != null && c.lastError.contains(String.valueOf(ceilY))
                        && !c.lastError.contains("phase=JUMPING"))
                .as("C 判词要说出挡路的那一格（y=" + ceilY + "），不许只报 phase: got »" + c.lastError
                        + "«。相位说的是代码停在哪，调用方需要的是格子 —— 能挖掉它的是调用方"
                        + "（JourneyShaft.ascendByTowering 就在挖）").isTrue();
        ctx.check(c.spent).as("D 一块也不该花: spent " + c.spent + "，起塔柱 »" + c.columnAfter
                + "« —— 一次跳不起来的课程不该留下花掉的方块").isEqualTo(0);

        // ---- subject: the same order, the same rig, that one row of stone gone ----
        stageFlatArena(ctx, 3, floorY);
        ServerWorldDriver subject = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer sfp = subject.fakePlayer();
        sfp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        sfp.getInventory().selected = 0;
        settle(subject.avatar());
        requireStaged(ctx, "subject", sfp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);

        Run s = runTower(ctx, "subject", subject, standY + courses, courses, DRIVE_BUDGET,
                apexWatch(sfp, "subject", ctx));
        int subjectFaults = s.climbed() == courses ? 0 : 1;
        ctx.record("subject.after", subjectFaults + " fault(s): 抽掉 y=" + ceilY + " 那一层，"
                + "同一条命令涨了 " + s.climbed() + "/" + courses + " 格");
        ctx.check(s.climbed()).as("E 两臂只差 y=" + ceilY + " 那一层石头: 涨了 " + s.climbed()
                + " 格（对照臂 " + c.climbed() + "）。没有这一读，「垒不上去」也可以是一个"
                + "到哪都垒不动的塔说的").isEqualTo(courses);
        ctx.check(s.spent).as("F 恰好花 " + courses + " 块: spent " + s.spent).isEqualTo(courses);
    }

    /**
     * <b>The ceiling is over the NEXT column, and the caller's own check cannot see it.</b>
     *
     * <p>{@code JourneyShaft.ascendByTowering} clears {@code at.above(2)} before every course, where
     * {@code at} is {@code player().blockPosition()} — one column, the body's own. A body is 0.6
     * wide and stands wherever a walk left it, so its box straddles two columns whenever its x or z
     * is within 0.3 of a cell boundary, and a rise is stopped by whichever of them is lowest. That is
     * the ladder's shape exactly: a two-cell mine corridor whose ceiling has been opened over one
     * column only is precisely what the caller's check produces.
     *
     * <p>It is also how {@code vein2.exit#3} could report {@code phase=JUMPING} on course
     * <b>1</b>: course 0 opened the body's own ceiling and gained its block, and the neighbour's
     * never moved.
     *
     * <h2>One variable, and it is a fifth of a block</h2>
     *
     * Both runs use the same arena and the same single stone cell at {@code (cx-1, standY+2, cz)}.
     * The control's body sits at {@code x = cx + 0.05} — box {@code [cx-0.25, cx+0.35]}, straddling
     * into that neighbour — and the subject's at {@code x = cx + 0.5}, box {@code [cx+0.2, cx+0.8]},
     * clear of it. Both report {@code blockPosition().getX() == cx}, so <b>the cell the caller checks
     * is air in both</b> and no column-shaped question can tell them apart. {@code footprint()} is
     * recorded on each so the difference is a reading and not an inference.
     */
    private static void serverTowersUnderTheNeighboursCeiling(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = floorY(ctx), standY = floorY + 1;
        final int courses = 4, stock = 64;
        final int ceilY = standY + 2;
        final BlockPos lid = new BlockPos(cx - 1, ceilY, cz);
        final BlockPos own = new BlockPos(cx, ceilY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        towerConfig();
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);

        // ---- control: the body straddles into the lidded column ----
        stageFlatArena(ctx, 3, floorY);
        level.setBlockAndUpdate(lid, Blocks.STONE.defaultBlockState());

        ServerWorldDriver control = body(ctx, cx + 0.05, standY, cz + 0.5);
        ServerPlayer cfp = control.fakePlayer();
        cfp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        cfp.getInventory().selected = 0;
        settle(control.avatar());
        requireStaged(ctx, "control", cfp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);
        ctx.record("control.footprint", footprint(cfp) + " | 盖子 " + lid.toShortString() + "="
                + level.getBlockState(lid).getBlock() + " | 调用方查的那一格 " + own.toShortString()
                + "=" + level.getBlockState(own).getBlock());

        Run c = runTower(ctx, "control", control, standY + courses, courses, DRIVE_BUDGET,
                apexWatch(cfp, "control", ctx));
        int controlFaults = c.climbed() == courses ? 0 : 1;
        ctx.record("control.after", controlFaults + " fault(s): 身体压着 " + lid.toShortString()
                + " 那一柱，" + courses + " 格里涨了 " + c.climbed() + " 格；判词 »" + c.lastError + "«");
        WorldDriverCommon.LOG.info("[tower:neighbour] control climbed={} ticks={} finished={} err={}",
                c.climbed(), c.ticks, c.finished, c.lastError);
        if (controlFaults == 0)
            ctx.fail("THE RIG, not the subject: a body whose box reaches into " + lid.toShortString()
                    + " climbed all " + courses + " courses, so either the stone is not there or the"
                    + " body is not straddling — " + footprint(cfp) + " | " + c.columnAfter);
        control.fakePlayer().discard();

        ctx.check(c.finished).as("A 邻柱封住时也要给判词: ran " + c.ticks + " of " + DRIVE_BUDGET
                + " ticks, verdict »" + c.lastError + "«").isTrue();
        ctx.check(c.lastError != null && c.lastError.contains(lid.toShortString()))
                .as("B 判词要点名邻柱那一格 " + lid.toShortString() + "（而不是身体自己的柱 "
                        + own.toShortString() + "，那一格是空的）: got »" + c.lastError
                        + "«。调用方只挖得动它说得出名字的格子").isTrue();

        // ---- subject: same arena, same lid, the body a fifth of a block further in ----
        stageFlatArena(ctx, 3, floorY);
        level.setBlockAndUpdate(lid, Blocks.STONE.defaultBlockState());
        ServerWorldDriver subject = body(ctx, cx + 0.5, standY, cz + 0.5);
        ServerPlayer sfp = subject.fakePlayer();
        sfp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        sfp.getInventory().selected = 0;
        settle(subject.avatar());
        requireStaged(ctx, "subject", sfp, new BlockPos(cx, standY, cz), cx, standY, courses, cz, stock);
        ctx.record("subject.footprint", footprint(sfp) + " | 盖子仍在 " + lid.toShortString() + "="
                + level.getBlockState(lid).getBlock());

        Run s = runTower(ctx, "subject", subject, standY + courses, courses, DRIVE_BUDGET,
                apexWatch(sfp, "subject", ctx));
        int subjectFaults = s.climbed() == courses ? 0 : 1;
        ctx.record("subject.after", subjectFaults + " fault(s): 同一块盖子还在，身体挪进半格，涨了 "
                + s.climbed() + "/" + courses + " 格");
        ctx.check(s.climbed()).as("C 两臂只差身体在格内的位置（对照 x=cx+0.05，本体 x=cx+0.5），"
                + "盖子一模一样: 涨了 " + s.climbed() + " 格（对照臂 " + c.climbed() + "）").isEqualTo(courses);
        ctx.check(s.spent).as("D 恰好花 " + courses + " 块: spent " + s.spent).isEqualTo(courses);
    }
}
