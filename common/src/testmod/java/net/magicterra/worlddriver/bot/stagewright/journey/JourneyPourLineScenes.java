package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A cast whose own scoop built a staircase through the line it then has to shoot down.
 *
 * <p>Rung 12's third independent failure family, and the third instance in one day of one shape: <b>a
 * recovery's placements become the next step's obstacle</b>. The first two were answered with a no-go
 * list of cells that must stay WALKABLE ({@link JourneyStairs#needsOpen}). This one needs the other
 * kind — cells that must stay clear of a LINE — because a block that cannot stop a body can still
 * stop a ray.
 *
 * <h2>The run, in five rows</h2>
 *
 * <p>Ladder run of 2026-08-20, an {@code east} mould based at {@code 4,56,19}, cast 8 of 10 — the
 * top-left ring cell {@code 4,60,19}. Read them in the order the rung wrote them:
 *
 * <pre>
 * cell.8.step       = 3, 58, 19 垫一格给 4, 60, 19 用 → 站得住了（Block{minecraft:cobblestone}）
 * wet.8.ramp.flight = 4 级：2, 56, 17 → 3, 57, 17 → 3, 58, 18 → 3, 59, 19
 * cast8.stand.1     = 2, 58, 19 瞄 5, 60, 19（背板近面） 否决计数 {脚下不实心=32,
 *                     射线停在 4, 60, 18 Block{minecraft:dirt}=2, 落脚格被占=92, …}
 * cast8.picks.1     = 3, 59, 19 Block{minecraft:cobblestone} face=west → 落进 2, 59, 19
 * cast8.clear3      = 浇线上没有可清的方块（3, 59, 19=Block{minecraft:cobblestone}(壁龛内) …）
 * </pre>
 *
 * <ol>
 *   <li>A step was placed at {@code 3,58,19} <b>for this very cell</b>, so the body could stand in
 *       {@code 3,59,19} and shoot the backing along the axis.</li>
 *   <li>The WET half of the same cell needs to be one row higher ({@code 4,61,19} is the notch above
 *       the frame), so its flight filled {@code 3,59,19} to reach {@code 3,60,19}.</li>
 *   <li>The LAVA half came back to find its own stand solid.</li>
 *   <li>The fallback stand's fired ray stopped on that same block, one cell short.</li>
 *   <li>{@code clearPourLine} declined to remove it, because {@link JourneyRamp#isStep} said the rung
 *       had put it there on purpose.</li>
 * </ol>
 *
 * <p>The rung built the blocker and then excused it. Nothing in the chain is a walker bug, a ray
 * quantisation artefact or a flooded alcove: it is one cell, contested by two legs of the same cast,
 * with no rule saying who owns it.
 *
 * <h2>What the {@code 4, 60, 18} in row three is, and what it is not</h2>
 *
 * <p>It is a vote in a HISTOGRAM. {@code standToPour} weighs 140 candidate feet
 * ({@link JourneyPour#standCandidates}) and merges each refusal into one map, so a row prints one
 * stand beside the reasons EVERY candidate was refused for — including candidates in other z ranks.
 * {@code 4,60,18} is the mould's own uncarved frame corner, and the feet that name it are the ones
 * one rank north of the target ({@code away.getClockWise()} is {@code SOUTH} for an {@code east}
 * mould, so {@code side=-1} is {@code z=18}); their line leaves the {@code z=19} rank before it
 * reaches the backing. The stand printed beside them, {@code 2,58,19}, is at {@code z=19} and its own
 * shot is stopped by the borrowed step. {@code wd.pourLineBlockedByTheStepTheScoopLeft} attributes
 * every such vote to the cell that cast it rather than leaving that to be reasoned about — this repo
 * has already lost four rounds to a ray it reasoned about instead of measuring.
 *
 * <h2>Why the fix is a take-back and not a refusal</h2>
 *
 * <p>The obvious remedy is to forbid the fill. It is not available here, and
 * {@code wd.pourLineHasNoOtherWayUp} is that arithmetic rather than an assertion: the wet cell sits
 * one row above the frame cell, so the stand it needs is one row above the frame cell's stand, so its
 * flight's landing rests on <b>exactly</b> the cell the frame cell wants to stand in. Refuse the fill
 * and the water pour has no way up at all — which is the same defect moved one leg earlier.
 *
 * <p>So the reservation is REDEEMED. {@link JourneyRamp#plan} prefers a route that keeps a pending
 * cast's line clear and says so when it has to borrow one anyway; {@link JourneySight#blockersOnTheLine}
 * hands the cell back when that cast asks for it, unless the body is resting on it. The two halves
 * are {@code wd.pourLineTakesBackTheStepItBorrowed}'s subject and control.
 *
 * <h2>Staged as「the previous leg just finished」, deliberately</h2>
 *
 * <p>Two of this family's three instances were caused by the PREVIOUS step's work, so a fixture that
 * stages one leg is structurally blind to it. Everything here is staged at the instant the lava half
 * of cast 8 walks back into the alcove: eight ring cells already obsidian, the ninth open, the wet
 * notch opened and drained, the frame cell's own step down, and the scoop's four-course flight
 * standing — registered through {@link JourneyRamp#laid}, the same call {@link JourneyRamp#lay} makes,
 * so the fixture and the ladder agree about what a step is.
 *
 * <h2>Dry, and what that costs</h2>
 *
 * <p>The ladder's alcove is flooded with the cast's own water by cell eight, and these arms are dry.
 * That is one variable removed, not a shortcut: water in a hollow alcove flows and drains, so a staged
 * puddle is a different world every tick. What the flood adds on the real ladder is one more refusal —
 * a body standing ON the borrowed step floats, so {@link JourneySight#pourGrade} predicts a line one
 * row high and vetoes it ({@code 射线停在 4, 62, 19 Block{minecraft:dirt}=1} in the run). Dry, that
 * stand survives, so these arms do NOT claim「there was nowhere left to pour from」. They claim the
 * two things that hold either way: the borrowed step is standing in the row the raise verifies, and
 * from the stand the body was actually on it stops the shot.
 *
 * <h2>Arena footprint</h2>
 *
 * <p>{@code dx ∈ [-2, 5]}, {@code dz ∈ [-4, 4]}, {@code dy ∈ [BASE-2, BASE+9]} around the origin —
 * inside the default one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
 * Stated here rather than left to be derived because {@code scripts/check_scene_arena.py} scans the
 * {@code scene} package only: these scenes live beside the code they test.
 */
public final class JourneyPourLineScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.pourLineBlockedByTheStepTheScoopLeft", 200,
                        JourneyPourLineScenes::blockedByTheStepTheScoopLeft).withRequired(false),
                Scene.of("wd.pourLineTakesBackTheStepItBorrowed", 200,
                        JourneyPourLineScenes::takesBackTheStepItBorrowed).withRequired(false),
                Scene.of("wd.pourLineHasNoOtherWayUp", 200,
                        JourneyPourLineScenes::hasNoOtherWayUp).withRequired(false));
    }

    // ---------------------------------------------------------------------- rig ----

    /** The alcove's floor row, as a dy offset — the row the mould's base sits in. Twenty above the
     *  grid's y=200, like the sibling journey arenas. */
    private static final int BASE = 20;

    /** How far the mould is pushed out from the shaft the body arrives down. Two, which is what
     *  {@code carveTheForge} settles on when the first offset is dry — and what the run this file is
     *  about used, so the corridor is the same two ranks wide. */
    private static final int PUSH = 2;

    /** The mould faces east, so {@code away.getClockWise()} is SOUTH and a ring cell's {@code dx}
     *  runs along +z. Same handedness as the run; the {@code z=18} attribution below depends on it. */
    private static final Direction AWAY = Direction.EAST;

    /** The shaft-bottom cell the corridor is measured from — {@code 2,56,19} in the run. */
    private static BlockPos at(SceneContext ctx) { return ctx.rel(0, BASE, 0); }

    /** The frame's bottom-left cell — {@code 4,56,19} in the run. */
    private static BlockPos base(SceneContext ctx) { return at(ctx).relative(AWAY, PUSH); }

    /** Which ring cell this file is about: index 8, {@code (0,4)}, the top-left one —
     *  {@code 4,60,19}. It is the first cast whose wet cell is the notch ABOVE the frame, which is
     *  what puts the scoop one row higher than the pour. */
    private static final int RING = 8;

    private static BlockPos target(SceneContext ctx) {
        return JourneyForge.frameCell(base(ctx), AWAY, JourneyForge.RING[RING][0],
                JourneyForge.RING[RING][1]);
    }

    /** The block the bucket is aimed at: the frame cell's backing — {@code 5,60,19}. */
    private static BlockPos backing(SceneContext ctx) { return target(ctx).relative(AWAY); }

    /** The cell the scoop's flight borrowed: one back and one down from the target, which is both
     *  the pour's own axis-aligned stand and {@code pourLine}'s {@code k=1, dy=-1} —
     *  {@code 3,59,19}. */
    private static BlockPos borrowed(SceneContext ctx) {
        return target(ctx).relative(AWAY.getOpposite()).below();
    }

    /** The step {@code standBehind} laid so the body could stand in {@link #borrowed} and mine the
     *  frame cell — {@code 3,58,19}. It is what makes the borrowed cell a stand at all. */
    private static BlockPos frameCellStep(SceneContext ctx) { return borrowed(ctx).below(); }

    /** Where the scoop had to get to: on TOP of the borrowed step, level with the notch it fills —
     *  {@code 3,60,19}. */
    private static BlockPos scoopLanding(SceneContext ctx) { return borrowed(ctx).above(); }

    /** Where the lava half was actually standing when it fired — {@code 2,58,19}, two back and two
     *  down, on the dirt its own raise tower left. */
    private static BlockPos stand(SceneContext ctx) {
        return target(ctx).relative(AWAY.getOpposite(), 2).below(2);
    }

    /** The mould's own uncarved frame corner — {@code 4,60,18}. The ring leaves corners out, so this
     *  is host rock, and it is what the {@code z=18} candidates' lines stop on. */
    private static BlockPos corner(SceneContext ctx) {
        return target(ctx).relative(AWAY.getClockWise().getOpposite());
    }

    /** The corridor as the rung computes it, so the flight planner is asked about the same volume. */
    private static Set<BlockPos> corridor(SceneContext ctx) {
        return Set.copyOf(JourneyForge.corridor(at(ctx), AWAY, PUSH));
    }

    /** How much of the ring is already obsidian when the fixture starts. Eight: cast 8 is the one
     *  under test, so everything below it is done and everything above it is not. */
    private static final int CAST_SO_FAR = RING;

    /**
     * Build the alcove at the instant the lava half of cast {@value #RING} walks back in.
     *
     * <p>Order matters and is the mould's own: the corridor is hollow, the frame plane is solid
     * except for what has been opened, and the ring cells below this one are obsidian. Nothing here
     * is a shortcut for the rung's own carve — it is the state that carve leaves, written directly,
     * because what is under test is one ray and not an excavation.
     *
     * @param flight whether the scoop's four-course staircase is standing. The ONE variable between
     *               this arm's control and its subject.
     */
    private static void stage(SceneContext ctx, boolean flight, int castSoFar) {
        clearBox(ctx);
        // Host rock. Everything is solid to begin with and the alcove is cut out of it, which is what
        // gives the frame its backing and the corners their block.
        for (int dx = -2; dx <= 5; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 2; dy <= BASE + 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);

        // The corridor: two ranks deep, five wide, seven tall. Hollow — every cell above the floor
        // row has air underneath, which is the whole reason this rung needs a flight and not a brick.
        for (BlockPos c : JourneyForge.corridor(at(ctx), AWAY, PUSH))
            ctx.level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());

        // The ring, as far as it has been cast. Obsidian is what makes a line SPENT — see
        // JourneySight#lineOwner, which reads the world rather than an index for exactly this.
        for (int i = 0; i < castSoFar; i++)
            ctx.level().setBlockAndUpdate(
                    JourneyForge.frameCell(base(ctx), AWAY, JourneyForge.RING[i][0],
                            JourneyForge.RING[i][1]),
                    Blocks.OBSIDIAN.defaultBlockState());
        // Their wet cells, opened and drained. The six interior cells and this cast's notch are air
        // by now; `target.below()` being one of them is why the pour has only ONE aim left (the
        // backing) — the floor-top aim needs a solid floor and there is none.
        for (int i = 0; i < castSoFar; i++)
            ctx.level().setBlockAndUpdate(
                    JourneyForge.wetCellFor(base(ctx), AWAY, JourneyForge.RING[i][0],
                            JourneyForge.RING[i][1]),
                    Blocks.AIR.defaultBlockState());
        // This cast's own two cells: the frame cell opened for the lava, the notch opened, filled and
        // scooped back. Only when it is this cast's turn — the control arm of hasNoOtherWayUp stages
        // the ring cell as already cast, and then it must stay obsidian.
        if (castSoFar <= RING) {
            ctx.level().setBlockAndUpdate(target(ctx), Blocks.AIR.defaultBlockState());
            ctx.level().setBlockAndUpdate(
                    JourneyForge.wetCellFor(base(ctx), AWAY, JourneyForge.RING[RING][0],
                            JourneyForge.RING[RING][1]),
                    Blocks.AIR.defaultBlockState());
        }

        // What the legs before this one left standing.
        JourneyRamp.reset();
        JourneySight.mould(base(ctx), AWAY);
        // The step standBehind laid so the body could stand in `borrowed` and mine the frame cell.
        put(ctx, frameCellStep(ctx));
        // The dirt the raise tower left under the fallback stand. Without it `stand` has no floor and
        // every candidate there is refused as 脚下不实心 before any ray is fired — which would make
        // this arm about a missing floor rather than about a blocked line.
        put(ctx, stand(ctx).below());
        if (flight) {
            // The scoop's flight, bottom course first, exactly as `wet.8.ramp.flight` printed it.
            // The top course is `borrowed`; the three below it are off the z=19 rank, which is why
            // only the top one is ever in this cast's way.
            put(ctx, ctx.rel(0, BASE, -2));
            put(ctx, ctx.rel(1, BASE + 1, -2));
            put(ctx, ctx.rel(1, BASE + 2, -1));
            put(ctx, borrowed(ctx));
        }
    }

    /** Lay one cobblestone and register it as a step, through the same call {@link JourneyRamp#lay}
     *  makes. A fixture that registered its own steps some other way would be testing its copy of the
     *  bookkeeping rather than the rung's. */
    private static void put(SceneContext ctx, BlockPos c) {
        ctx.level().setBlockAndUpdate(c, Blocks.COBBLESTONE.defaultBlockState());
        JourneyRamp.laid(c);
    }

    /** Air out the working box, one cell past the arena on every side, so a re-stage removes whatever
     *  the previous arm left wherever it left it. */
    private static void clearBox(SceneContext ctx) {
        for (int dx = -2; dx <= 5; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 2; dy <= BASE + 9; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The switches every arm shares, plus the statics these scenes have to hand back. A step set or
     *  a mould left behind would give a ladder run in the same process a flight it never built. */
    private static void config(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(JourneyRamp::reset);
        ctx.cleanup(JourneySight::forgetMould);
        // No staircase at all, and said so rather than assumed: JourneyRamp.fillable consults
        // JourneyStairs.needsOpen, so a flight left over from another scene would refuse cells of
        // this alcove for a reason that has nothing to do with what is under test.
        JourneyStairs.forget();
        ctx.cleanup(JourneyStairs::forget);
        // Registered BEFORE anything is built, so an arm that fails mid-way still hands the shared
        // dogfood world back empty.
        ctx.cleanup(() -> clearBox(ctx));
    }

    /** A body standing in {@code foot}, settled, holding a pickaxe and a bucket's worth of nothing. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot) {
        ServerWorldDriver driver = ServerWorldDriver.createIsolated(ctx.level(),
                foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        // A pickaxe because the take-back arm mines with it and `destroyBlock` hands the held item to
        // `dropResources` — a fist opens the cell and drops nothing.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerAvatar av = driver.avatar();
        // Three physics steps with no input, so the body is flush before any eye is read.
        for (int i = 0; i < 3; i++) av.step();
        return driver;
    }

    /** The shot, fired the way the pour fires it, described the way {@code cast*.picks} describes it. */
    private record Shot(BlockPos stopped, BlockPos landing, String where) {}

    private static Shot fire(SceneContext ctx, ServerWorldDriver driver, BlockPos aim) {
        ServerPlayer fp = driver.fakePlayer();
        BlockHitResult hit = JourneyPour.fire(driver.avatar(), fp, aim);
        BlockPos landing = JourneyPour.landedIn(hit);
        String where = String.format(Locale.ROOT, "身体 %s，眼睛 %.2f/%.2f/%.2f，瞄 %s → %s",
                fp.blockPosition().toShortString(), fp.getEyePosition().x, fp.getEyePosition().y,
                fp.getEyePosition().z, aim.toShortString(),
                landing == null ? String.valueOf(hit.getType())
                        : hit.getBlockPos().toShortString() + " "
                          + ctx.level().getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → 落进 " + landing.toShortString());
        return new Shot(landing == null ? null : hit.getBlockPos(), landing, where);
    }

    /** Open one named cell with an aimed swing — the three calls
     *  {@code JourneyRig.breakItWhereItStands} makes, judged on the WORLD because {@code breakHold}
     *  returns nothing and refuses silently. */
    private static boolean swing(ServerWorldDriver driver, BlockPos cell) {
        ServerPlayerAvatar av = driver.avatar();
        if (!av.canBreak(cell)) return false;
        av.selectTool(cell);
        av.aimAtBlock(cell);
        av.breakHold(true);
        av.breakHold(false);
        return !driver.fakePlayer().level().getBlockState(cell).blocksMotion();
    }

    /** The nearest foot cell that grades {@link JourneySight#ANYWHERE} — a stand this pour is
     *  entitled to plan on, as opposed to one it may only walk to. Production grading, production
     *  candidate list; the ranking is {@code standToAimAt}'s own (nearest to the body wins). */
    private static BlockPos nearestVerified(SceneContext ctx, ServerPlayer fp) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos foot : JourneyPour.standCandidates(target(ctx), AWAY)) {
            Map<String, Integer> ignored = new LinkedHashMap<>();
            if (JourneyPour.gradeFoot(ctx.level(), fp, foot, backing(ctx), target(ctx), ignored)
                    != JourneySight.ANYWHERE) continue;
            double d = foot.distSqr(fp.blockPosition());
            if (d < bestD) { bestD = d; best = foot.immutable(); }
        }
        return best;
    }

    /** How this one foot grades, by the production predicate — {@link JourneySight#REFUSED},
     *  {@code CENTRE_ONLY} or {@code ANYWHERE}. */
    private static int grade(SceneContext ctx, ServerPlayer fp, BlockPos foot) {
        return JourneyPour.gradeFoot(ctx.level(), fp, foot, backing(ctx), target(ctx),
                new LinkedHashMap<>());
    }

    // --------------------------------------------------------------------- arms ----

    /**
     * <b>The scoop's own top step stops the cast's shot one cell short — and the coordinate the
     * refusal histogram prints belongs to a different candidate than the stand beside it.</b>
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>the control:</b> with the borrowed cell AIR the same body, from the same stand, aiming
     *       at the same backing, puts the fluid in the target. Without this every row below is about
     *       an alcove that could not be poured into anyway;</li>
     *   <li>with the step standing, the shot stops ON it and the fluid would land in the cell behind
     *       it — the run's {@code 落进 2, 59, 19}, reproduced;</li>
     *   <li>the production veto histogram really does carry a row naming the frame corner, so the
     *       measurement below is not vacuous;</li>
     *   <li><b>every candidate that casts that vote is outside the target's own z rank</b>, so the
     *       coordinate in row three of the run belongs to feet the stand printed beside it is not
     *       one of;</li>
     *   <li>the stand's own veto names the borrowed step, at the target's own z. That is the pair:
     *       one coordinate from another rank, one from this one, in the same row.</li>
     * </ol>
     */
    private static void blockedByTheStepTheScoopLeft(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);

        BlockPos target = target(ctx);
        BlockPos backing = backing(ctx);
        BlockPos borrowed = borrowed(ctx);
        BlockPos stand = stand(ctx);

        // ---- control: the same shot with the line clear ----
        stage(ctx, false, CAST_SO_FAR);
        ctx.record("staged.mould", "base " + base(ctx).toShortString() + " 朝 " + AWAY
                + "，浇 " + target.toShortString() + "（第 " + RING + " 格），瞄背板 "
                + backing.toShortString() + "；壁龛 " + corridor(ctx).size() + " 格，地板 y="
                + JourneyRamp.floorOf(corridor(ctx)));
        ctx.check(level.getBlockState(backing).isSolidRender(level, backing))
                .as("THE RIG: 背板 " + backing.toShortString() + " 必须是实心的，否则射线穿过去，"
                        + "这一臂量的就不是「被挡住」而是「没有东西可瞄」——"
                        + level.getBlockState(backing).getBlock()).isTrue();

        ServerWorldDriver control = body(ctx, stand);
        Shot clear = fire(ctx, control, backing);
        ctx.record("control.shot", clear.where());
        ctx.record("control.after", (target.equals(clear.landing()) ? 0 : 1) + " fault(s): 落进 "
                + (clear.landing() == null ? "无" : clear.landing().toShortString()));
        WorldDriverCommon.LOG.info("[pourLine] control landing={} target={}", clear.landing(), target);
        if (!target.equals(clear.landing()))
            ctx.fail("THE RIG, not the subject: 线上什么都没有的时候这一枪就该落进 "
                    + target.toShortString() + "，实际 " + clear.where()
                    + " —— 这一臂之后的每一行都会变成在量一个本来就浇不进去的壁龛");
        control.fakePlayer().discard();

        // ---- subject: the flight the scoop left standing ----
        stage(ctx, true, CAST_SO_FAR);
        ctx.check(JourneyRamp.isStep(borrowed)).as("THE RIG: " + borrowed.toShortString()
                + " 要以「本鸢自己垒的台阶」的身份登记，否则 clearPourLine 的旧豁免根本不会碰它")
                .isTrue();

        ServerWorldDriver subject = body(ctx, stand);
        ServerPlayer fp = subject.fakePlayer();
        Shot shot = fire(ctx, subject, backing);
        ctx.record("subject.shot", shot.where());
        ctx.check(shot.stopped()).as("A 射线要停在收水那一趟垒的顶级台阶 " + borrowed.toShortString()
                + " 上：" + shot.where()).isEqualTo(borrowed);
        ctx.check(shot.landing()).as("B 流体会落进台阶后面那一格，而不是门框格 " + target.toShortString()
                + "：" + shot.where()).isEqualTo(borrowed.relative(AWAY.getOpposite()));

        // ---- the histogram, and who actually cast each vote ----
        Map<String, Integer> why = new LinkedHashMap<>();
        JourneyPour.PourSpot spot = JourneyPour.standToPour(level, fp, target, AWAY, why);
        ctx.record("subject.stand", (spot == null ? "null" : spot.stand().toShortString() + " 瞄 "
                + spot.aim().toShortString()) + " 否决计数 " + why);

        String cornerVote = "射线停在 " + corner(ctx).toShortString() + " "
                + level.getBlockState(corner(ctx)).getBlock();
        ctx.check(why.containsKey(cornerVote)).as("C 否决计数里要真的有这一条，否则下面的归属是空话："
                + "想找 »" + cornerVote + "«，实际 " + why.keySet()).isTrue();

        List<BlockPos> cast = new ArrayList<>();
        List<BlockPos> offRank = new ArrayList<>();
        for (BlockPos foot : JourneyPour.standCandidates(target, AWAY)) {
            Map<String, Integer> one = new LinkedHashMap<>();
            JourneyPour.gradeFoot(level, fp, foot, backing, target, one);
            if (!one.containsKey(cornerVote)) continue;
            cast.add(foot.immutable());
            if (foot.getZ() != target.getZ()) offRank.add(foot.immutable());
        }
        ctx.record("subject.whoVoted", cornerVote + " ← " + cast + "（目标那一列 z=" + target.getZ()
                + "；这些落脚点的 z=" + cast.stream().map(BlockPos::getZ).distinct().toList() + "）");
        ctx.check(cast.isEmpty()).as("D 这一票必须找得到投票人（走的是同一张候选表 standCandidates）："
                + cast).isFalse();
        ctx.check(offRank).as("E 投这一票的每一个落脚点都不在目标那一列上 —— 行里印的那个坐标"
                + " " + corner(ctx).toShortString() + " 属于别的候选，不属于同一行印出来的落脚点 "
                + stand.toShortString() + "：投票人 " + cast).isEqualTo(cast);

        Map<String, Integer> mine = new LinkedHashMap<>();
        JourneyPour.gradeFoot(level, fp, stand, backing, target, mine);
        ctx.record("subject.standVote", stand.toShortString() + "（z=" + stand.getZ() + "）→ " + mine);
        ctx.check(String.valueOf(mine.keySet()).contains(borrowed.toShortString()))
                .as("F 身体自己站的那一格，否决理由要指向台阶 " + borrowed.toShortString()
                        + "（跟目标同一列 z=" + target.getZ() + "）：" + mine).isTrue();
    }

    /**
     * <b>The pour takes the borrowed step back — unless it is the floor holding the body up.</b>
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>the control:</b> the same production rule, asked with the body standing ON the step,
     *       must refuse to offer it. One variable — where the body is — and it is the safety half of
     *       the change: a remedy that mines its own floor is the mistake this exemption was written
     *       for in the first place;</li>
     *   <li>from the stand the run was actually on, the rule offers exactly the borrowed cell and
     *       nothing else;</li>
     *   <li>the body's own swing really opens it, else the shot below measures a wall that was never
     *       there;</li>
     *   <li>the same shot, from the same cell, now lands in the target;</li>
     *   <li><b>what the take-back buys, stated as a before/after pair over the same body:</b> with
     *       the step standing, the cell the body is on does not verify at all and the nearest one
     *       that does is a row ABOVE the row {@code standLevelWith} asks for — so the rung has to
     *       raise, and the raise is what spent the run. With the step gone the body's own cell
     *       verifies (no walk at all), and so does the cell the step was occupying, at exactly that
     *       row. That last one is the point: <b>the flight was standing in the pour's stand.</b></li>
     * </ol>
     */
    private static void takesBackTheStepItBorrowed(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);

        BlockPos target = target(ctx);
        BlockPos backing = backing(ctx);
        BlockPos borrowed = borrowed(ctx);
        BlockPos stand = stand(ctx);
        BlockPos onTop = scoopLanding(ctx);

        // ---- control: a body standing on the step may not be handed its own floor ----
        stage(ctx, true, CAST_SO_FAR);
        ServerWorldDriver control = body(ctx, onTop);
        List<BlockPos> refused = JourneySight.blockersOnTheLine(level, corridor(ctx), target, AWAY,
                JourneyPortalRung.POUR_LINE, control.fakePlayer());
        ctx.record("control.body", control.fakePlayer().blockPosition().toShortString()
                + "（站在台阶 " + borrowed.toShortString() + " 上，restsOn="
                + JourneySight.restsOn(control.fakePlayer(), borrowed) + "）");
        ctx.record("control.after", (refused.contains(borrowed) ? 1 : 0) + " fault(s): 可收回 "
                + refused);
        if (refused.contains(borrowed))
            ctx.fail("THE RIG, not the subject: 身体正站在 " + borrowed.toShortString()
                    + " 上，规则却把它算成可以收回的 —— 那是把自己的地板挖掉，"
                    + "这一条豁免当初就是为它写的：" + refused);
        control.fakePlayer().discard();

        // ---- subject ----
        stage(ctx, true, CAST_SO_FAR);
        ServerWorldDriver subject = body(ctx, stand);
        ServerPlayer fp = subject.fakePlayer();

        BlockPos before = nearestVerified(ctx, fp);
        int standBefore = grade(ctx, fp, stand);
        Shot blocked = fire(ctx, subject, backing);
        ctx.record("subject.before", blocked.where() + "；最近的验得过的落脚点 "
                + (before == null ? "无" : before.toShortString()) + "；身体自己这一格 "
                + stand.toShortString() + " 的评级 " + standBefore + "（2=处处成立）");

        List<BlockPos> take = JourneySight.blockersOnTheLine(level, corridor(ctx), target, AWAY,
                JourneyPortalRung.POUR_LINE, fp);
        ctx.record("subject.take", take + "（身体 " + fp.blockPosition().toShortString()
                + "，restsOn(" + borrowed.toShortString() + ")="
                + JourneySight.restsOn(fp, borrowed) + "）");
        ctx.check(take).as("A 从身体真正站的那一格看，浇线上要收回的恰好是那一级台阶 "
                + borrowed.toShortString() + "：" + take).isEqualTo(List.of(borrowed));

        boolean opened = swing(subject, borrowed);
        JourneyRamp.forget(borrowed);
        ctx.record("subject.dig", borrowed.toShortString() + " → "
                + level.getBlockState(borrowed).getBlock() + "（canBreak="
                + subject.avatar().canBreak(borrowed) + "）");
        ctx.check(opened).as("B 那一格真的敲开了，否则下面的射线是在量一堵不存在的墙："
                + borrowed.toShortString() + "=" + level.getBlockState(borrowed).getBlock()).isTrue();
        if (!opened) return;

        Shot now = fire(ctx, subject, backing);
        BlockPos after = nearestVerified(ctx, fp);
        ctx.record("subject.shot", now.where());
        ctx.record("subject.after", (target.equals(now.landing()) ? 0 : 1) + " fault(s): 落进 "
                + (now.landing() == null ? "无" : now.landing().toShortString())
                + "；最近的验得过的落脚点 " + (after == null ? "无" : after.toShortString()));
        ctx.check(now.landing()).as("C 同一具身体、同一格、同一个瞄准，收回台阶之后这一枪要落进门框格 "
                + target.toShortString() + "：" + now.where()).isEqualTo(target);

        ctx.check(before).as("D 台阶还在的时候，最近的验得过的落脚点是台阶顶上那一格 "
                + onTop.toShortString() + " —— 比 standLevelWith 验的那一排（y="
                + (target.getY() - 1) + "）高一排，所以这一鸢非抬高不可：" + before).isEqualTo(onTop);
        ctx.check(standBefore).as("E 而且台阶还在的时候，身体自己站的这一格 " + stand.toShortString()
                + " 一点都验不过（评级要是 " + JourneySight.REFUSED + "）：" + standBefore)
                .isEqualTo(JourneySight.REFUSED);
        ctx.check(after).as("F 收回之后，最近的验得过的落脚点就是身体自己这一格 "
                + stand.toShortString() + " —— 一步都不用走：" + after).isEqualTo(stand);
        int borrowedAfter = grade(ctx, fp, borrowed);
        ctx.check(borrowedAfter).as("G 台阶原来占着的那一格 " + borrowed.toShortString()
                + " 本身也验得过，而且正是 standLevelWith 要站的那一排 y=" + (target.getY() - 1)
                + " —— 收水那一趟的楼梯当初就是砌在浇筑的落脚格里：评级 " + borrowedAfter
                + "（2=处处成立）").isEqualTo(JourneySight.ANYWHERE);
        ctx.check(borrowed.getY()).as("H 那一格确实在要站的那一排上（不是巧合，是 target.y-1）")
                .isEqualTo(target.getY() - 1);
    }

    /**
     * <b>Refusing the fill is not available: the scoop's landing rests on exactly the cell the cast
     * has to stand in.</b>
     *
     * <p>This is the arm that stops「reserve it and refuse」being the obvious fix. The reservation
     * exists and {@link JourneyRamp#plan} asks it first; what this measures is that in this alcove it
     * has no second answer, so a refusal would move the failure one leg earlier rather than remove it.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>the control:</b> the same geometry with this ring cell already cast — nothing pending
     *       on that line — and pass one finds the flight. Without it「pass one refuses」is satisfied
     *       by a planner that always refuses;</li>
     *   <li>the reservation names the cell, and names the cast that owns it;</li>
     *   <li>with that cast pending, the strict pass finds NO flight to the scoop's landing;</li>
     *   <li>the permissive pass finds one, and its top course rests on exactly the reserved cell.</li>
     * </ol>
     */
    private static void hasNoOtherWayUp(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);

        BlockPos target = target(ctx);
        BlockPos borrowed = borrowed(ctx);
        BlockPos landing = scoopLanding(ctx);
        Set<BlockPos> corridor = corridor(ctx);
        int floorY = JourneyRamp.floorOf(corridor);

        // ---- control: the same alcove with this cast already done ----
        stage(ctx, false, RING + 1);
        List<BlockPos> controlFlight =
                JourneyRamp.planKeeping(level, corridor, floorY, landing, true);
        ctx.record("control.owner", "第 " + RING + " 格 " + target.toShortString() + "="
                + level.getBlockState(target).getBlock() + "，"
                + borrowed.toShortString() + " 的线主 "
                + JourneySight.lineOwner(level, borrowed));
        ctx.record("control.after", (controlFlight == null ? 1 : 0) + " fault(s): 严格那一趟 "
                + (controlFlight == null ? "修不出楼梯" : controlFlight.size() + " 级 "
                        + supports(controlFlight)));
        if (controlFlight == null)
            ctx.fail("THE RIG, not the subject: 这一格已经浇成黑曜石了，线上没有待办，"
                    + "严格那一趟本该修得出楼梯却修不出 —— 那么下面的「严格那一趟拒绝」量的不是保留区，"
                    + "而是一个永远拒绝的规划器");

        // ---- subject: the cast is still pending, so its line is reserved ----
        stage(ctx, false, CAST_SO_FAR);
        BlockPos owner = JourneySight.lineOwner(level, borrowed);
        ctx.record("subject.owner", borrowed.toShortString() + " 的线主 "
                + (owner == null ? "无" : owner.toShortString() + "="
                        + level.getBlockState(owner).getBlock()));
        ctx.check(owner).as("A 保留区要认得这一格：它在还没浇的第 " + RING + " 格 "
                + target.toShortString() + " 的浇线上（k=1, dy=-1）").isEqualTo(target);

        List<BlockPos> strict = JourneyRamp.planKeeping(level, corridor, floorY, landing, true);
        List<BlockPos> loose = JourneyRamp.planKeeping(level, corridor, floorY, landing, false);
        ctx.record("subject.strict", strict == null ? "修不出楼梯" : strict.size() + " 级 " + supports(strict));
        ctx.record("subject.loose", loose == null ? "修不出楼梯" : loose.size() + " 级 " + supports(loose));
        ctx.record("subject.after", (strict == null && loose != null ? 0 : 1) + " fault(s): 严格 "
                + (strict == null ? "无" : "有") + "，放宽 " + (loose == null ? "无" : "有"));

        ctx.check(strict).as("B 守着保留区，通往收水落脚点 " + landing.toShortString()
                + " 的楼梯根本修不出来 —— 所以「保留区 = 禁止填」会把这一格的失败搬到上一腿去："
                + (strict == null ? "null" : supports(strict))).isNull();
        ctx.check(loose == null ? null : loose.get(loose.size() - 1).below())
                .as("C 放宽之后唯一的那条路，顶级台阶正好就是保留的那一格 " + borrowed.toShortString()
                        + " —— 收水的落脚点就压在浇筑要站的那一格上："
                        + (loose == null ? "null" : supports(loose))).isEqualTo(borrowed);
        // Named separately: a null `loose` would make C pass on a null==null that says nothing.
        ctx.check(loose).as("D 放宽那一趟必须真的修得出楼梯，否则 C 是 null==null").isNotNull();
        ctx.check(floorY).as("THE RIG: 壁龛地板行要跟井底同一排 —— 规划器是照它数级数的")
                .isEqualTo(at(ctx).getY());
    }

    /** A flight as the blocks it lays, which is how {@code ramp.flight} prints it: each entry is a
     *  cell the body STANDS in, and the cobblestone goes under it. */
    private static String supports(List<BlockPos> flight) {
        StringBuilder out = new StringBuilder();
        for (BlockPos s : flight)
            out.append(out.isEmpty() ? "" : " → ").append(s.below().toShortString());
        return out.toString();
    }
}
