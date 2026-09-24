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
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
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
 * top-left ring cell {@code 4,60,19}. Read them in the order the rung wrote them (values paraphrased
 * in English):
 *
 * <pre>
 * cell.8.step       = 3, 58, 19 placed as support for 4, 60, 19 → standable (Block{minecraft:cobblestone})
 * wet.8.ramp.flight = 4 steps: 2, 56, 17 → 3, 57, 17 → 3, 58, 18 → 3, 59, 19
 * cast8.stand.1     = 2, 58, 19 aiming at 5, 60, 19 (backing, near face), veto counts {no solid floor=32,
 *                     ray stopped at 4, 60, 18 Block{minecraft:dirt}=2, stand cell occupied=92, …}
 * cast8.picks.1     = 3, 59, 19 Block{minecraft:cobblestone} face=west → lands in 2, 59, 19
 * cast8.clear3      = nothing to clear on the pour line
 *                     (3, 59, 19=Block{minecraft:cobblestone}(in alcove) …)
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
 * <h2>The next run's blocker was a FINISHED ring cell, and it is a different question</h2>
 *
 * <p>2026-08-20, the run after the take-back landed: cast 8 poured
 * ({@code cast8.clear3} reported one block to clear, then {@code cast8.result = CONSUME}) and cast 9 —
 * {@code 4,60,20}, the last cell — died with the fired ray stopping on the obsidian of cast 8
 * (values paraphrased in English):
 *
 * <pre>
 * cast9.picks.1 = 4, 60, 19 Block{minecraft:obsidian} face=up → lands in 4, 61, 19
 *                 (pouring 4, 60, 20, aiming at 5, 60, 20, bot at 3, 60, 19)
 * cast9.stand.3 = 3, 60, 19 aiming at 5, 60, 20, veto counts {no solid floor=27,
 *                 stand cell occupied=100, …}
 * </pre>
 *
 * <p>Two things that look like new families, and neither is:
 *
 * <ul>
 *   <li><b>"A finished cell shadows an uncast one" is not an ordering problem and not the ring's
 *       shape.</b> {@code wd.pourLineRingOrderCannotShadowAPour} stages every ring cell with all
 *       nine others already obsidian — the worst shadow ANY order can produce, so a subset argument
 *       settles all 3 628 800 orderings at once — and every one of the ten keeps columns in its own
 *       rank, at the row the raise verifies. The shadow exists only for a body one rank over, and
 *       only at that row: the arm's own row-by-row reading is {@code y221:1 y223:0 y225:1}. The
 *       body was at {@code 3,60,19}; the target is at {@code z=20}.</li>
 *   <li><b>The 100 "stand cell occupied" vetoes are the alcove's own rock.</b>
 *       {@code wd.pourLineOccupiedStandsAreOutsideTheAlcove} splits the vote: 100 of the 140
 *       candidates fall outside the carved corridor (the scan reaches four back and two either side
 *       of a mould pushed two out of a five-wide alcove), and the only one inside is a registered
 *       flight step. Scaffolding contributes single digits, so this is NOT another instance of "a
 *       recovery's placements become the next step's obstacle" and the no-go list is the wrong
 *       place to look.</li>
 * </ul>
 *
 * <p>What is left is the DELIVERY, and the run says so in its own rows: {@code cast9.ramp.laid}
 * reported 0 of 3 steps placed with the bot at {@code 2, 56, 20} — a flight that laid nothing
 * because the bot was standing on its own bottom support — and then {@code cast9.raisedY = 59/59}
 * with the bot stopped at column {@code 3,17} instead of the assigned column {@code 2,20}. The
 * column the ray chose was right; nothing got the bot into it. No fix is shipped here for that:
 * it lives inside {@link JourneyRamp#lay}'s walk-and-place loop, which needs a {@link JourneyRig}
 * to drive, and this file's arms deliberately stop where an arena stops being honest.
 *
 * <h2>Staged as "the previous step of the cast has just completed", deliberately</h2>
 *
 * <p>Two of this family's three instances were caused by the PREVIOUS step's work, so a fixture that
 * stages a single step in isolation is structurally blind to it. Everything here is staged at the
 * instant the lava half of cast 8 walks back into the alcove: eight ring cells already obsidian, the ninth open, the wet
 * notch opened and drained, the frame cell's own step down, and the scoop's four-course flight
 * standing — registered through {@link JourneyRamp#laid}, the same call {@link JourneyRamp#lay} makes,
 * so the fixture and the ladder agree about what a step is.
 *
 * <h2>Dry, and what that costs</h2>
 *
 * <p>The ladder's alcove is flooded with the cast's own water by cell eight, and these arms are dry.
 * That is one variable removed, not a shortcut: water in a hollow alcove flows and drains, so a staged
 * puddle is a different world every tick. What the flood adds on the real ladder is one more refusal —
 * a bot standing ON the borrowed step floats, so {@link JourneySight#pourGrade} predicts a line one
 * row high and vetoes it (the run's histogram has one "ray stopped at 4, 62, 19
 * Block{minecraft:dirt}" vote). Dry, that stand survives, so these arms do NOT claim "there was
 * nowhere left to pour from". They claim the two things that hold either way: the borrowed step is
 * standing in the row the raise verifies, and from the stand the bot was actually on it stops the
 * shot.
 *
 * <h2>Arena footprint</h2>
 *
 * <p>{@code dx ∈ [-2, 5]}, {@code dz ∈ [-4, 4]}, {@code dy ∈ [BASE-3, BASE+9]} around the origin —
 * inside the default one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
 * Stated here rather than left to be derived because {@code scripts/check_scene_arena.py} scans the
 * {@code scene} package only: these scenes live beside the code they test.
 */
public final class JourneyPourLineScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.pourLineTopPairAimsAtItsSideNeighbour", 200,
                        JourneyPourLineScenes::topPairAimsAtItsSideNeighbour),
                Scene.of("wd.pourLineBlockedByTheStepTheScoopLeft", 200,
                        JourneyPourLineScenes::blockedByTheStepTheScoopLeft),
                Scene.of("wd.pourLineTakesBackTheStepItBorrowed", 200,
                        JourneyPourLineScenes::takesBackTheStepItBorrowed),
                Scene.of("wd.pourLineHasNoOtherWayUp", 200,
                        JourneyPourLineScenes::hasNoOtherWayUp),
                Scene.of("wd.pourLineRingOrderCannotShadowAPour", 200,
                        JourneyPourLineScenes::ringOrderCannotShadowAPour),
                Scene.of("wd.pourLineOccupiedStandsAreOutsideTheAlcove", 200,
                        JourneyPourLineScenes::occupiedStandsAreOutsideTheAlcove));
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
    /** How many verified stands the top-pair arm actually fires from. Each one spawns a bot, and the
     *  question is "does at least one stand hit" rather than a census — but the number tried is
     *  printed beside the number that qualified, so a capped run never reads as an exhaustive one. */
    private static final int SHOTS_TRIED = 12;

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
        //
        // THREE ROWS BELOW THE ALCOVE FLOOR, not two. The pour's own scan reaches seven rows down
        // from the target, so its bottom row needs a floor UNDER it — without one those twenty
        // candidates are refused for having no solid floor instead of for an occupied stand cell,
        // and the vote arithmetic in wd.pourLineOccupiedStandsAreOutsideTheAlcove came out 81
        // against 101. The arm's own
        // exact-sum criterion is what found it; a "mostly rock" criterion would have passed over it.
        for (int dx = -2; dx <= 5; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 3; dy <= BASE + 8; dy++)
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

        // What the earlier steps of this cast left standing.
        JourneyRamp.reset();
        JourneySight.mould(base(ctx), AWAY);
        // The step standBehind laid so the body could stand in `borrowed` and mine the frame cell.
        put(ctx, frameCellStep(ctx));
        // The dirt the raise tower left under the fallback stand. Without it `stand` has no floor and
        // every candidate there is refused for having no solid floor before any ray is fired, which
        // would make this arm about a missing floor rather than about a blocked line.
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
                for (int dy = BASE - 3; dy <= BASE + 9; dy++)
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
        ServerWorldDriver driver = SceneBody.managed(ctx, foot);
        ServerPlayer fp = driver.fakePlayer();
        // A pickaxe because the take-back arm mines with it and `destroyBlock` hands the held item to
        // `dropResources` — a fist opens the cell and drops nothing.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
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
        String where = String.format(Locale.ROOT, "bot at %s, eyes %.2f/%.2f/%.2f, aiming at %s → %s",
                fp.blockPosition().toShortString(), fp.getEyePosition().x, fp.getEyePosition().y,
                fp.getEyePosition().z, aim.toShortString(),
                landing == null ? String.valueOf(hit.getType())
                        : hit.getBlockPos().toShortString() + " "
                          + ctx.level().getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → lands in " + landing.toShortString());
        return new Shot(landing == null ? null : hit.getBlockPos(), landing, where);
    }

    /** {@link JourneyHands#swing} — the arena's own break oracle, shared with the portal-entry
     *  arenas so there is one of it rather than one per file. */
    private static boolean swing(ServerWorldDriver driver, BlockPos cell) {
        return JourneyHands.swing(driver, cell);
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
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>the control:</b> with the borrowed cell AIR the same body, from the same stand, aiming
     *       at the same backing, puts the fluid in the target. Without this every row below is about
     *       an alcove that could not be poured into anyway;</li>
     *   <li>with the step standing, the shot stops ON it and the fluid would land in the cell behind
     *       it — the run's landing cell {@code 2, 59, 19}, reproduced;</li>
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
        ctx.record("staged.mould", "base " + base(ctx).toShortString() + " facing " + AWAY
                + ", pouring " + target.toShortString() + " (ring cell " + RING + "), aiming at backing "
                + backing.toShortString() + "; alcove " + corridor(ctx).size() + " cells, floor y="
                + JourneyRamp.floorOf(corridor(ctx)));
        ctx.check(level.getBlockState(backing).isSolidRender(level, backing))
                .as("THE RIG: the backing " + backing.toShortString() + " must be solid; otherwise the"
                        + " ray passes through it and this arm measures 'nothing to aim at' instead of"
                        + " 'blocked' — " + level.getBlockState(backing).getBlock()).isTrue();

        ServerWorldDriver control = body(ctx, stand);
        Shot clear = fire(ctx, control, backing);
        ctx.record("control.shot", clear.where());
        ctx.record("control.after", (target.equals(clear.landing()) ? 0 : 1) + " fault(s): lands in "
                + (clear.landing() == null ? "none" : clear.landing().toShortString()));
        WorldDriverCommon.LOG.info("[pourLine] control landing={} target={}", clear.landing(), target);
        if (!target.equals(clear.landing()))
            ctx.fail("THE RIG, not the subject: with nothing on the line this shot should land in "
                    + target.toShortString() + ", but got " + clear.where()
                    + " — every row after this would be measuring an alcove that cannot be poured into");
        control.fakePlayer().discard();

        // ---- subject: the flight the scoop left standing ----
        stage(ctx, true, CAST_SO_FAR);
        ctx.check(JourneyRamp.isStep(borrowed)).as("THE RIG: " + borrowed.toShortString()
                + " must be registered as a step this cast built itself; otherwise the old"
                + " clearPourLine exemption would never apply to it")
                .isTrue();

        ServerWorldDriver subject = body(ctx, stand);
        ServerPlayer fp = subject.fakePlayer();
        Shot shot = fire(ctx, subject, backing);
        ctx.record("subject.shot", shot.where());
        ctx.check(shot.stopped()).as("A the ray stops on the top step the scoop built, "
                + borrowed.toShortString() + ": " + shot.where()).isEqualTo(borrowed);
        ctx.check(shot.landing()).as("B the fluid lands in the cell behind the step, not in the frame"
                + " cell " + target.toShortString() + ": " + shot.where())
                .isEqualTo(borrowed.relative(AWAY.getOpposite()));

        // ---- the histogram, and who actually cast each vote ----
        Map<String, Integer> why = new LinkedHashMap<>();
        JourneyPour.PourSpot spot = JourneyPour.standToPour(level, fp, target, AWAY, why);
        ctx.record("subject.stand", (spot == null ? "null" : spot.stand().toShortString() + " aiming at "
                + spot.aim().toShortString()) + ", veto counts " + why);

        // Must match the veto key JourneySight.pourLine produces byte for byte.
        String cornerVote = "the ray stopped at " + corner(ctx).toShortString() + " "
                + level.getBlockState(corner(ctx)).getBlock();
        ctx.check(why.containsKey(cornerVote)).as("C the veto counts must actually contain this entry,"
                + " or the attribution below proves nothing: looking for »" + cornerVote + "«, got "
                + why.keySet()).isTrue();

        List<BlockPos> cast = new ArrayList<>();
        List<BlockPos> offRank = new ArrayList<>();
        for (BlockPos foot : JourneyPour.standCandidates(target, AWAY)) {
            Map<String, Integer> one = new LinkedHashMap<>();
            JourneyPour.gradeFoot(level, fp, foot, backing, target, one);
            if (!one.containsKey(cornerVote)) continue;
            cast.add(foot.immutable());
            if (foot.getZ() != target.getZ()) offRank.add(foot.immutable());
        }
        ctx.record("subject.whoVoted", cornerVote + " ← " + cast + " (target rank z=" + target.getZ()
                + "; these stands are at z=" + cast.stream().map(BlockPos::getZ).distinct().toList() + ")");
        ctx.check(cast.isEmpty()).as("D this vote must have at least one voter (the scan uses the same"
                + " standCandidates list): " + cast).isFalse();
        ctx.check(offRank).as("E every stand that casts this vote is outside the target's rank — the"
                + " coordinate " + corner(ctx).toShortString() + " printed in the row belongs to other"
                + " candidates, not to the stand " + stand.toShortString() + " printed beside it: voters "
                + cast).isEqualTo(cast);

        Map<String, Integer> mine = new LinkedHashMap<>();
        JourneyPour.gradeFoot(level, fp, stand, backing, target, mine);
        ctx.record("subject.standVote", stand.toShortString() + " (z=" + stand.getZ() + ") → " + mine);
        ctx.check(String.valueOf(mine.keySet()).contains(borrowed.toShortString()))
                .as("F the veto for the cell the bot is standing in must name the step "
                        + borrowed.toShortString() + " (in the target's rank z=" + target.getZ() + "): "
                        + mine).isTrue();
    }

    /**
     * <b>The pour takes the borrowed step back — unless it is the floor holding the body up.</b>
     *
     * <h2>Criteria</h2>
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
                + " (standing on the step " + borrowed.toShortString() + ", restsOn="
                + JourneySight.restsOn(control.fakePlayer(), borrowed) + ")");
        ctx.record("control.after", (refused.contains(borrowed) ? 1 : 0) + " fault(s): can take back "
                + refused);
        if (refused.contains(borrowed))
            ctx.fail("THE RIG, not the subject: the bot is standing on " + borrowed.toShortString()
                    + ", yet the rule offers it for take-back — that would mine the bot's own floor,"
                    + " which is exactly what the exemption exists to prevent: " + refused);
        control.fakePlayer().discard();

        // ---- subject ----
        stage(ctx, true, CAST_SO_FAR);
        ServerWorldDriver subject = body(ctx, stand);
        ServerPlayer fp = subject.fakePlayer();

        BlockPos before = nearestVerified(ctx, fp);
        int standBefore = grade(ctx, fp, stand);
        Shot blocked = fire(ctx, subject, backing);
        ctx.record("subject.before", blocked.where() + "; nearest verified stand "
                + (before == null ? "none" : before.toShortString()) + "; grade of the bot's own cell "
                + stand.toShortString() + " is " + standBefore + " (2=valid anywhere)");

        List<BlockPos> take = JourneySight.blockersOnTheLine(level, corridor(ctx), target, AWAY,
                JourneyPortalRung.POUR_LINE, fp);
        ctx.record("subject.take", take + " (bot at " + fp.blockPosition().toShortString()
                + ", restsOn(" + borrowed.toShortString() + ")="
                + JourneySight.restsOn(fp, borrowed) + ")");
        ctx.check(take).as("A from the cell the bot is actually standing in, the only block to take"
                + " back from the pour line is that step, " + borrowed.toShortString() + ": " + take)
                .isEqualTo(List.of(borrowed));

        boolean opened = swing(subject, borrowed);
        JourneyRamp.forget(borrowed);
        ctx.record("subject.dig", borrowed.toShortString() + " → "
                + level.getBlockState(borrowed).getBlock() + " (canBreak="
                + subject.avatar().canBreak(borrowed) + ")");
        ctx.check(opened).as("B the cell was actually broken open; otherwise the ray below measures a"
                + " wall that is not there: "
                + borrowed.toShortString() + "=" + level.getBlockState(borrowed).getBlock()).isTrue();
        if (!opened) return;

        Shot now = fire(ctx, subject, backing);
        BlockPos after = nearestVerified(ctx, fp);
        ctx.record("subject.shot", now.where());
        ctx.record("subject.after", (target.equals(now.landing()) ? 0 : 1) + " fault(s): lands in "
                + (now.landing() == null ? "none" : now.landing().toShortString())
                + "; nearest verified stand " + (after == null ? "none" : after.toShortString()));
        ctx.check(now.landing()).as("C same bot, same cell, same aim: once the step is taken back"
                + " the shot lands in the frame cell " + target.toShortString() + ": " + now.where())
                .isEqualTo(target);

        ctx.check(before).as("D while the step stands, the nearest verified stand is the cell on top of"
                + " it, " + onTop.toShortString() + " — one row above the row standLevelWith verifies"
                + " (y=" + (target.getY() - 1) + "), so this cast has to raise: " + before)
                .isEqualTo(onTop);
        ctx.check(standBefore).as("E and while the step stands, the bot's own cell "
                + stand.toShortString() + " does not verify at all (grade must be "
                + JourneySight.REFUSED + "): " + standBefore)
                .isEqualTo(JourneySight.REFUSED);
        ctx.check(after).as("F after the take-back, the nearest verified stand is the bot's own cell "
                + stand.toShortString() + " — no walking needed: " + after).isEqualTo(stand);
        int borrowedAfter = grade(ctx, fp, borrowed);
        ctx.check(borrowedAfter).as("G the cell the step occupied, " + borrowed.toShortString()
                + ", verifies too, and it is exactly the row standLevelWith stands in, y="
                + (target.getY() - 1) + " — the scoop's staircase was built in the pour's stand"
                + " cell: grade " + borrowedAfter + " (2=valid anywhere)")
                .isEqualTo(JourneySight.ANYWHERE);
        ctx.check(borrowed.getY()).as("H that cell really is in the stand row (by construction,"
                + " target.y-1, not by coincidence)")
                .isEqualTo(target.getY() - 1);
    }

    /**
     * <b>Refusing the fill is not available: the scoop's landing rests on exactly the cell the cast
     * has to stand in.</b>
     *
     * <p>This is the arm that stops "reserve it and refuse" being the obvious fix. The reservation
     * exists and {@link JourneyRamp#plan} asks it first; what this measures is that in this alcove it
     * has no second answer, so a refusal would move the failure one step of the cast earlier rather
     * than remove it.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>the control:</b> the same geometry with this ring cell already cast — nothing pending
     *       on that line — and pass one finds the flight. Without it "pass one refuses" is satisfied
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
        ctx.record("control.owner", "ring cell " + RING + " " + target.toShortString() + "="
                + level.getBlockState(target).getBlock() + ", line owner of "
                + borrowed.toShortString() + " is "
                + JourneySight.lineOwner(level, borrowed));
        ctx.record("control.after", (controlFlight == null ? 1 : 0) + " fault(s): strict pass "
                + (controlFlight == null ? "cannot build a staircase" : controlFlight.size() + " steps "
                        + supports(controlFlight)));
        if (controlFlight == null)
            ctx.fail("THE RIG, not the subject: this cell is already obsidian and nothing is pending"
                    + " on the line, so the strict pass should build a staircase and did not — the"
                    + " 'strict pass refuses' check below would then measure a planner that always"
                    + " refuses, not the reservation");

        // ---- subject: the cast is still pending, so its line is reserved ----
        stage(ctx, false, CAST_SO_FAR);
        BlockPos owner = JourneySight.lineOwner(level, borrowed);
        ctx.record("subject.owner", "line owner of " + borrowed.toShortString() + " is "
                + (owner == null ? "none" : owner.toShortString() + "="
                        + level.getBlockState(owner).getBlock()));
        ctx.check(owner).as("A the reservation recognises this cell: it is on the pour line of the"
                + " uncast ring cell " + RING + ", " + target.toShortString() + " (k=1, dy=-1)")
                .isEqualTo(target);

        List<BlockPos> strict = JourneyRamp.planKeeping(level, corridor, floorY, landing, true);
        List<BlockPos> loose = JourneyRamp.planKeeping(level, corridor, floorY, landing, false);
        ctx.record("subject.strict", strict == null ? "cannot build a staircase"
                : strict.size() + " steps " + supports(strict));
        ctx.record("subject.loose", loose == null ? "cannot build a staircase"
                : loose.size() + " steps " + supports(loose));
        ctx.record("subject.after", (strict == null && loose != null ? 0 : 1) + " fault(s): strict "
                + (strict == null ? "none" : "found") + ", relaxed " + (loose == null ? "none" : "found"));

        ctx.check(strict).as("B while the reservation is honoured, no staircase to the scoop's landing "
                + landing.toShortString() + " can be built — so 'reservation = forbid the fill' would"
                + " move this cell's failure to the step of the cast before it: "
                + (strict == null ? "null" : supports(strict))).isNull();
        ctx.check(loose == null ? null : loose.get(loose.size() - 1).below())
                .as("C the only route once relaxed has its top step on exactly the reserved cell "
                        + borrowed.toShortString() + " — the scoop's landing rests on the cell the"
                        + " pour has to stand in: "
                        + (loose == null ? "null" : supports(loose))).isEqualTo(borrowed);
        // Named separately: a null `loose` would make C pass on a null==null that says nothing.
        ctx.check(loose).as("D the relaxed pass must actually build a staircase, otherwise C is"
                + " null==null").isNotNull();
        ctx.check(floorY).as("THE RIG: the alcove floor row must be level with the shaft bottom — the"
                + " planner counts steps from it")
                .isEqualTo(at(ctx).getY());
    }

    /** A flight as the blocks it lays — {@link JourneyRamp#supports}, so these rows and the rung's
     *  own {@code ramp.flight} row are printed by one formatter rather than by two that agree. */
    private static String supports(List<BlockPos> flight) {
        return JourneyRamp.supports(flight);
    }

    // ------------------------------------------ the ring's own shadow, enumerated ----

    /** How many feet a pour weighs. Four back, five wide, seven rows down — the run's own veto
     *  histogram sums to exactly this, which is what says it is a histogram over the SCAN and not a
     *  reading about the one stand printed beside it. */
    private static final int CANDIDATES = 4 * 5 * 7;

    private static BlockPos ring(SceneContext ctx, int i) {
        return JourneyForge.frameCell(base(ctx), AWAY, JourneyForge.RING[i][0],
                JourneyForge.RING[i][1]);
    }

    /**
     * The mould with EVERY ring cell cast but one — the worst shadow any casting order can produce.
     *
     * <p>Worst rather than actual, and that is the whole argument about ordering: any order casts a
     * SUBSET of the other nine before this one, so a mould that leaves a clean column with all nine
     * standing leaves one under every order there is. One staging per cell settles all 3 628 800 of
     * them, and no enumeration of orders is needed.
     *
     * <p>No scaffolding at all. The question here is what the RING does to a line, and a cobblestone
     * in the alcove would answer a different one —
     * {@code wd.pourLineBlockedByTheStepTheScoopLeft} already owns that.
     */
    private static void stageRingOnly(SceneContext ctx, int uncast) {
        clearBox(ctx);
        for (int dx = -2; dx <= 5; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 3; dy <= BASE + 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (BlockPos c : JourneyForge.corridor(at(ctx), AWAY, PUSH))
            ctx.level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        // The fourteen cells a bucket is ever aimed into are open by the end of the casting: the six
        // interior cells and the two notches are air. That is the state the LAST cast sees, and the
        // last cast is the one with nine finished cells around it.
        for (BlockPos c : JourneyForge.aimedCells(base(ctx), AWAY))
            ctx.level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        for (int i = 0; i < JourneyForge.RING.length; i++)
            if (i != uncast)
                ctx.level().setBlockAndUpdate(ring(ctx, i), Blocks.OBSIDIAN.defaultBlockState());
        JourneyRamp.reset();
        JourneySight.mould(base(ctx), AWAY);
    }

    /**
     * Which corridor columns {@code side} ranks over from {@code cell} have a clear line to its
     * backing — {@link JourneyPour}'s {@code raiseColumn} question, without the floor it does not
     * require.
     *
     * <p>A raise BUILDS the floor, so "is there a column whose eye can see the backing" has to be
     * asked of the line alone. Asking {@code gradeFoot} here would answer "is there a finished stand
     * down there", which in a hollow alcove is almost always no and says nothing about shadowing.
     */
    private static List<BlockPos> columnsThatSee(SceneContext ctx, ServerPlayer fp, BlockPos cell,
                                                 int side, Map<String, Integer> why) {
        return columnsThatSee(ctx, fp, cell, side, why, Integer.MIN_VALUE);
    }

    /** As above, restricted to one row when {@code onlyY} is given. The row matters and the run is
     *  why: from a rank one over, whether a finished cell is in the way DEPENDS ON THE HEIGHT — the
     *  diagonal from two rows below or one row above the target misses it entirely, and the diagonal
     *  from the row {@code standLevelWith} verifies ({@code target.y - 1}) does not. A measurement
     *  that lumps the rows together therefore answers neither question. */
    private static List<BlockPos> columnsThatSee(SceneContext ctx, ServerPlayer fp, BlockPos cell,
                                                 int side, Map<String, Integer> why, int onlyY) {
        ServerLevel level = ctx.level();
        Set<BlockPos> corridor = corridor(ctx);
        BlockPos backing = cell.relative(AWAY);
        List<BlockPos> out = new ArrayList<>();
        for (int back = 1; back <= JourneyPortalRung.POUR_LINE; back++)
            for (int dy = 0; dy < JourneyForge.ALCOVE_HEIGHT; dy++) {
                int y = at(ctx).getY() + dy;
                if (onlyY != Integer.MIN_VALUE && y != onlyY) continue;
                BlockPos foot = new BlockPos(cell.getX(), y, cell.getZ())
                        .relative(AWAY.getOpposite(), back).relative(AWAY.getClockWise(), side);
                if (!corridor.contains(foot) || !corridor.contains(foot.above())) continue;
                if (JourneySight.pourGrade(level, fp, foot, false, backing, cell, why)
                        != JourneySight.ANYWHERE) continue;
                out.add(foot.immutable());
            }
        return out;
    }

    /**
     * <b>No casting order can put a finished ring cell in an uncast one's way — the RANK the body
     * stands in is what decides, and a body in the cell's own rank is never shadowed.</b>
     *
     * <p>Ladder run of 2026-08-20, cast 9 of 10, the last ring cell {@code 4,60,20}:
     *
     * <pre>
     * cast9.picks.1 = 4, 60, 19 Block{minecraft:obsidian} face=up → lands in 4, 61, 19
     *                 (pouring 4, 60, 20, aiming at 5, 60, 20, bot at 3, 60, 19, eyes 3.36/61.62/19.15)
     * </pre>
     *
     * <p>That is the FIRED ray, not a histogram vote, so the blocker really is obsidian this rung
     * cast itself — ring cell 8, one column north. But read the body: {@code 3,60,19} is at
     * {@code z=19} and the target is at {@code z=20}. The shot is a DIAGONAL from the next rank, and
     * a diagonal is the only shape that can reach a neighbour's cell at all. From the target's own
     * rank the line is axis-aligned in z and crosses nothing but corridor air and the target.
     *
     * <p>So the fix is not in the ring's order and not in the ring's shape: it is in whatever left
     * the bot a rank over. On that run it is on the record: {@code cast9.ramp.laid} reported 0 of 3
     * steps placed with the bot at {@code 2, 56, 20}, a flight that laid nothing because the bot was
     * standing on its own bottom support, and then {@code cast9.raisedY = 59/59} with the bot
     * stopped at column {@code 3,17} instead of the assigned column {@code 2,20}.
     *
     * <h2>The shadow is a property of the ROW as well as the rank, which is why this arm names both</h2>
     *
     * <p>Measured here, not assumed: from the neighbouring rank the finished cell is in the way at
     * the row {@code standLevelWith} verifies ({@code target.y - 1}) and OUT of the way two rows
     * below it and one row above. So "the next rank is shadowed" is false as a blanket statement and
     * true where it costs the cast, and an arm that swept the rows together would have reported
     * either one of those as the whole answer. The first draft of this arm did exactly that and its
     * own rig check caught it.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>the control, and the reason criterion B is not vacuous:</b> at the row the raise
     *       verifies, the NEXT rank over has no column that sees the backing, and the veto names the
     *       obsidian the run named. One variable — {@code side} — at one row; if the neighbouring
     *       rank saw the backing there too, there would be no difference to report and this arm would
     *       be describing an alcove where nothing shadows anything;</li>
     *   <li><b>every one of the ten ring cells keeps a column in its OWN rank under the worst shadow
     *       any order can make</b> — all nine others already obsidian. Any order casts a subset of
     *       those nine, so this settles every ordering at once: casting in a different order would
     *       change nothing;</li>
     *   <li>and it keeps one at the row the raise actually asks for, so criterion B is not satisfied
     *       by a column six rows down that no raise would ever choose.</li>
     * </ol>
     */
    private static void ringOrderCannotShadowAPour(SceneContext ctx) {
        config(ctx);
        int last = JourneyForge.RING.length - 1;
        stageRingOnly(ctx, last);
        // Parked outside the mould: pourGrade reads the eye HEIGHT and uses the body only as the
        // clip's shape context, so where it stands cannot move a line — but it must not be inside a
        // cell this arm is about to describe.
        ServerWorldDriver driver = body(ctx, ctx.rel(0, BASE, -2));
        ServerPlayer fp = driver.fakePlayer();

        BlockPos died = ring(ctx, last);
        BlockPos neighbour = ring(ctx, last - 1);
        int wantY = died.getY() - 1;

        // ROW BY ROW FIRST, because "the neighbouring rank is blocked" is not true of the whole rank
        // and printing it as though it were is how a reading ends a search in the wrong place.
        StringBuilder byRow = new StringBuilder();
        for (int dy = 0; dy < JourneyForge.ALCOVE_HEIGHT; dy++) {
            int y = at(ctx).getY() + dy;
            List<BlockPos> sees = columnsThatSee(ctx, fp, died, -1, new LinkedHashMap<>(), y);
            byRow.append(dy == 0 ? "" : " ").append('y').append(y).append(':').append(sees.size());
        }
        ctx.record("control.byRow", "neighbouring rank (side=-1, z=" + (died.getZ() - 1)
                + "), columns that see the backing, row by row: " + byRow + " (stand row y=" + wantY + ")");

        Map<String, Integer> nextRank = new LinkedHashMap<>();
        List<BlockPos> fromNextRank = columnsThatSee(ctx, fp, died, -1, nextRank, wantY);
        ctx.record("control.rank", "ring cell " + last + " " + died.toShortString()
                + ", columns in the neighbouring rank that see the backing at row y=" + wantY + ": "
                + fromNextRank + "; vetoes " + nextRank);
        ctx.record("control.after", (fromNextRank.isEmpty() ? 0 : 1) + " fault(s): "
                + fromNextRank.size() + " columns");
        if (!fromNextRank.isEmpty())
            ctx.fail("THE RIG, not the subject: at the stand row y=" + wantY
                    + " the neighbouring rank also sees the backing, so 'the own rank sees it', as"
                    + " this arm reports it, is not a difference — nothing in this mould shadows that"
                    + " row at all: " + fromNextRank);
        String shadow = String.valueOf(nextRank.keySet());
        ctx.check(shadow.contains(neighbour.toShortString()))
                .as("A the reason the neighbouring rank is blocked at this row must name ring cell "
                        + (last - 1) + ", " + neighbour.toShortString() + ", which this cast poured"
                        + " itself (the run printed exactly that cell): " + nextRank).isTrue();

        List<Integer> blind = new ArrayList<>();
        List<Integer> blindAtWantY = new ArrayList<>();
        StringBuilder each = new StringBuilder();
        for (int i = 0; i < JourneyForge.RING.length; i++) {
            stageRingOnly(ctx, i);
            BlockPos cell = ring(ctx, i);
            List<BlockPos> own = columnsThatSee(ctx, fp, cell, 0, new LinkedHashMap<>());
            List<BlockPos> atRow = columnsThatSee(ctx, fp, cell, 0, new LinkedHashMap<>(),
                    cell.getY() - 1);
            if (own.isEmpty()) blind.add(i);
            // The bottom pair sits on the alcove floor, so ITS verified row is under the floor and no
            // corridor cell is there at all — those two are poured from the row above, which is what
            // `cast0.fromHere` records. Excluded by the geometry, not by taste.
            if (atRow.isEmpty() && cell.getY() - 1 >= at(ctx).getY()) blindAtWantY.add(i);
            each.append(i == 0 ? "" : "; ").append(i).append(':').append(cell.toShortString())
                    .append("→").append(own.size()).append(" columns")
                    .append(own.isEmpty() ? "" : " (lowest " + own.get(0).toShortString() + ")")
                    .append(", of which ").append(atRow.size()).append(" in the stand row");
        }
        ctx.record("subject.perCell", each.toString());
        ctx.record("subject.after", (blind.size() + blindAtWantY.size())
                + " fault(s): cells with no usable column in their own rank " + blind
                + "; cells with none in the stand row " + blindAtWantY);
        ctx.check(blind).as("B every one of the ten cells keeps at least one column in its own rank"
                + " that sees the backing (the other nine are already obsidian — any casting order is"
                + " a subset of those nine, so this settles every order at once): " + each).isEmpty();
        ctx.check(blindAtWantY).as("C and that column is in the row a raise actually goes to"
                + " (target.y-1); otherwise B could be satisfied by a column six rows down that no"
                + " raise would ever choose: "
                + each).isEmpty();
    }

    /**
     * <b>The hundred "stand cell occupied" vetoes are the alcove's own rock, not anything this rung
     * put down.</b>
     *
     * <p>{@code cast9.stand.N} prints a count of 100 "stand cell occupied" vetoes beside one stand,
     * and a hundred refusals for an occupied cell reads like scaffolding — which would make this the
     * third instance of "a recovery's placements become the next step's obstacle" and point the fix
     * at the no-go list. It is not. The counter is a MERGED HISTOGRAM over all {@value #CANDIDATES}
     * candidate feet ({@link JourneyPour#standCandidates}), and that scan reaches four cells back and two either
     * side of the target — which, for a mould pushed two out of a five-wide alcove, is mostly the
     * rock the alcove was cut into. The run's own votes sum to exactly 140.
     *
     * <h2>Criteria — a partition, not an emptiness claim</h2>
     *
     * <p>The first draft asserted that NO occupied stand is inside the alcove, and its own control
     * caught that as false: the borrowed step this file's other arms stage is itself a candidate for
     * the last ring cell. The scaffolding contributes — it contributes ONE. What answers the question
     * is the split, so the arm splits it:
     *
     * <ol>
     *   <li>the scan is the size the histogram is a histogram OF, so no vote is being read as
     *       belonging to a stand that is not in it;</li>
     *   <li><b>the count is exactly the candidates that fall outside the carved alcove, plus the
     *       handful inside it</b> — two numbers computed independently, one from the production
     *       predicate and one from corridor membership;</li>
     *   <li><b>every occupied stand INSIDE the alcove is a block this rung registered as a step</b>,
     *       so it is attributable rather than mysterious — and there are single digits of them
     *       against dozens of rock;</li>
     *   <li><b>the control:</b> one cobblestone dropped into a carved corridor cell that IS a
     *       candidate and HAS a floor moves the count by exactly one. Without it, criterion 3 is
     *       satisfied by a counter that can never see scaffolding at all.</li>
     * </ol>
     */
    private static void occupiedStandsAreOutsideTheAlcove(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, true, CAST_SO_FAR);
        // The LAST ring cell — the one the run died on, and the one with the most finished
        // neighbours. Its own two cells are opened so the mould is in the state that cast sees.
        int last = JourneyForge.RING.length - 1;
        BlockPos cell = ring(ctx, last);
        level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(JourneyForge.wetCellFor(base(ctx), AWAY,
                JourneyForge.RING[last][0], JourneyForge.RING[last][1]),
                Blocks.AIR.defaultBlockState());
        ServerWorldDriver driver = body(ctx, stand(ctx));
        ServerPlayer fp = driver.fakePlayer();

        Set<BlockPos> corridor = corridor(ctx);
        List<BlockPos> candidates = JourneyPour.standCandidates(cell, AWAY);
        ctx.check(candidates.size()).as("THE RIG: the candidate list must be the same size as the"
                + " run's (the veto counts are its histogram)").isEqualTo(CANDIDATES);

        List<BlockPos> occupied = occupiedVoters(ctx, fp, cell, candidates);
        List<BlockPos> inside = new ArrayList<>();
        List<BlockPos> outsideTheAlcove = new ArrayList<>();
        for (BlockPos f : candidates) if (!corridor.contains(f)) outsideTheAlcove.add(f);
        for (BlockPos f : occupied) if (corridor.contains(f)) inside.add(f);
        List<BlockPos> notAStep = new ArrayList<>();
        for (BlockPos f : inside) if (!JourneyRamp.isStep(f)) notAStep.add(f);
        ctx.record("subject.counts", "candidates " + candidates.size() + ", stand cell occupied "
                + occupied.size() + " = " + outsideTheAlcove.size() + " cells outside the alcove"
                + " + " + inside.size() + " cells inside it " + inside
                + "; alcove " + corridor.size() + " cells, steps built by this cast "
                + JourneyRamp.stepsNow().size());
        ctx.record("subject.after", notAStep.size() + " fault(s): occupied cells in the alcove that"
                + " are not this cast's steps " + notAStep);
        ctx.check(occupied.size()).as("A the vote total is exactly the number of cells where the scan"
                + " reaches into uncarved rock plus the few occupied cells inside the alcove — two"
                + " numbers computed independently, one from the production predicate and one from"
                + " alcove membership: "
                + outsideTheAlcove.size() + " + " + inside.size())
                .isEqualTo(outsideTheAlcove.size() + inside.size());
        ctx.check(notAStep).as("B every occupied cell in the alcove is a step this cast registered"
                + " (attributable, not an unowned block): "
                + inside).isEmpty();
        ctx.check(inside.size()).as("C and scaffolding accounts for single digits, which cannot"
                + " explain a hundred votes — this family is not 'the previous step's placements"
                + " block the next step': " + inside.size() + " cells inside the alcove, "
                + outsideTheAlcove.size() + " cells of rock").isLessThan(10);

        // A CANDIDATE WITH A FLOOR, because gradeFoot checks for a solid floor first and a cell over
        // air never reaches the occupancy test at all. A control planted over air shows no change,
        // which would wrongly read as "the counter cannot see scaffolding".
        BlockPos plant = null;
        for (BlockPos f : candidates)
            if (corridor.contains(f) && !occupied.contains(f) && level.getBlockState(f).isAir()
                    && level.getBlockState(f.below()).blocksMotion()) {
                plant = f;
                break;
            }
        if (plant == null)
            ctx.fail("THE RIG, not the subject: no candidate cell in the alcove is both empty and"
                    + " over a solid floor, so there is nowhere to place the control cobblestone");
        level.setBlockAndUpdate(plant, Blocks.COBBLESTONE.defaultBlockState());
        List<BlockPos> after = occupiedVoters(ctx, fp, cell, candidates);
        ctx.record("control.after", (after.size() - occupied.size() == 1 ? 0 : 1)
                + " fault(s): after placing one cobblestone at "
                + plant.toShortString() + " (floor " + plant.below().toShortString()
                + "=" + level.getBlockState(plant.below()).getBlock()
                + "), total " + occupied.size() + " → " + after.size());
        if (after.size() - occupied.size() != 1)
            ctx.fail("THE RIG, not the subject: a cobblestone was placed in the alcove candidate cell "
                    + plant.toShortString() + ", yet the count did not rise by exactly one vote — the"
                    + " split above then comes from a counter that cannot see scaffolding and proves"
                    + " nothing: " + occupied.size() + " → " + after.size());
        ctx.check(after.contains(plant)).as("D the control cell must actually appear among the voters"
                + " (same candidate list, same predicate): " + plant.toShortString()).isTrue();
    }

    /**
     * <b>The top pair of the frame has no floor to aim at, so the aim list has to reach sideways —
     * and the cell it reaches for is solid by the casting order, not by luck.</b>
     *
     * <p>{@link #stage}'s own comment names the defect this arm guards: with the interior opened,
     * {@code target.below()} is air, "which is why the pour has only ONE aim left (the backing)".
     * The 2026-08-27 ladder is what one aim costs. Its cell eight vetoed every candidate — eight of
     * them because {@code 4,59,20} is not solid and so cannot hold the placed fluid — fell back to a
     * stand outside the alcove, measured {@code 5.21 > 4.50} to the backing, and put the lava in
     * {@code 0,60,20}. Eight of ten cells cast, and the ninth is where twenty rungs stopped.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li><b>THE RIG</b> — the staged cell really is a top-pair cell: its floor is not solid. A
     *       solid floor means the mould was staged at some other rank and every row below measures
     *       something else.</li>
     *   <li><b>THE RIG</b> — at least one in-plane side neighbour IS solid. That is
     *       {@code JourneyPortalRung}'s casting invariant (two cells opened per cast, the rest of the
     *       ring left standing), and if the staged world does not have it, the arm is asking for
     *       something production is not entitled to.</li>
     *   <li>With the backing taken away, {@code standToPour} still finds a spot, and the block it
     *       aims at is the side neighbour.</li>
     *   <li>The shot fired from that spot lands in the target. Choosing the candidate is not the
     *       same as it working, and only the second one is worth anything.</li>
     * </ol>
     *
     * <h2>Why the backing is removed rather than moved out of reach</h2>
     *
     * The ladder exhausted the two-aim list by DISTANCE — the backing was solid the whole time and
     * simply too far from anywhere the body could stand. Reproducing that needs a floor plan that
     * denies every standable cell within {@code BUCKET_REACH}, which makes the arm a test of this
     * arena's shape. Removing the backing exhausts the same list by SOLIDITY, in one line, and what
     * is under test is the list — not which of the two ways it runs out. The removal is recorded, so
     * a reader is never left to infer that the mould came that way.
     */
    private static void topPairAimsAtItsSideNeighbour(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        // WITH THE FLIGHT, because a side neighbour can only be hit on the face that points at the
        // target, and that face is reachable only from the target's OWN row one cell back — the
        // corridor is hollow, so without the scoop's top course that cell has nothing under it and
        // the only candidate left is four rows down, whose line to the neighbour runs through the
        // ring cell already cast below it. Measured here with `false`, twice: `verifiedForTheSide`
        // reported a single cell, and its shot stopped on obsidian.
        //
        // This is not the arm giving itself the answer. The step is what {@code standLevelWith}
        // builds in production and what {@code wd.pourLineBlockedByTheStepTheScoopLeft} stages from
        // the same call; what the widening supplies is the AIM, and with the backing gone the two
        // old candidates are dead however many places there are to stand.
        stage(ctx, true, CAST_SO_FAR);

        BlockPos target = target(ctx);
        BlockPos floor = target.below();
        BlockPos backing = backing(ctx);
        BlockPos cw = target.relative(AWAY.getClockWise());
        BlockPos ccw = target.relative(AWAY.getCounterClockWise());

        ctx.record("staged.topPair", "pouring " + target.toShortString() + " (ring cell " + RING + ")"
                + "; floor " + floor.toShortString() + "=" + level.getBlockState(floor).getBlock()
                + ", backing " + backing.toShortString() + "=" + level.getBlockState(backing).getBlock()
                + ", in-row side neighbours " + cw.toShortString() + "=" + level.getBlockState(cw).getBlock()
                + " / " + ccw.toShortString() + "=" + level.getBlockState(ccw).getBlock());

        ctx.check(level.getBlockState(floor).isSolidRender(level, floor))
                .as("A THE RIG: a top-pair cell is defined by a floor that is not solid — the floor is"
                        + " the portal interior, opened before the third cast. A solid cell here means"
                        + " the test setup did not stage the top pair, and every row below measures"
                        + " something else: "
                        + floor.toShortString() + "=" + level.getBlockState(floor).getBlock())
                .isFalse();

        boolean cwSolid = level.getBlockState(cw).isSolidRender(level, cw);
        boolean ccwSolid = level.getBlockState(ccw).isSolidRender(level, ccw);
        ctx.check(cwSolid || ccwSolid)
                .as("B THE RIG: at least one in-row side neighbour must be solid — each cast opens"
                        + " only two cells (the frame cell and the water cell) and leaves the rest of"
                        + " the frame solid; that is the casting order's invariant. Without it in the"
                        + " test setup this arm asks for something production code is not entitled"
                        + " to expect: " + cw.toShortString() + "="
                        + level.getBlockState(cw).getBlock() + ", " + ccw.toShortString() + "="
                        + level.getBlockState(ccw).getBlock())
                .isTrue();
        BlockPos side = cwSolid ? cw : ccw;

        // The list runs out. Recorded, because a mould does not come this way and a reader who
        // assumed it did would read every row below as being about a different alcove.
        level.setBlockAndUpdate(backing, Blocks.AIR.defaultBlockState());
        ctx.record("staged.backingRemoved", "set the backing " + backing.toShortString()
                + " to air — both old candidates (backing and floor) are now invalid, while the side"
                + " neighbour " + side.toShortString() + " is still solid. The real ladder run reached"
                + " the same state by distance (5.21>4.50); this setup reaches it by solidity, so it"
                + " measures the candidate list itself rather than the way it runs out");

        ServerWorldDriver driver = body(ctx, stand(ctx));
        ServerPlayer fp = driver.fakePlayer();

        Map<String, Integer> why = new LinkedHashMap<>();
        JourneyPour.PourSpot spot = JourneyPour.standToPour(level, fp, target, AWAY, why);
        ctx.record("spot", spot == null ? "null, veto counts " + why
                : spot.stand().toShortString() + " aiming at " + spot.aim().toShortString() + "="
                  + level.getBlockState(spot.aim()).getBlock() + ", veto counts " + why);
        if (spot == null)
            ctx.fail("C no stand is left once the candidate list is exhausted — the side neighbour "
                    + side.toShortString() + "=" + level.getBlockState(side).getBlock()
                    + " is solid and aiming at its opposite face lands in " + target.toShortString()
                    + ", but it is not in the candidate list. Veto counts: " + why);

        ctx.check(spot.aim()).as("C the aim must be the in-row side neighbour " + side.toShortString()
                + ": the backing is air and the floor is the portal interior, so neither old candidate"
                + " verifies, and any other answer is the fallback — which hands the backing back"
                + " unchanged, and the backing is now "
                + level.getBlockState(backing).getBlock() + ". Actual aim: "
                + spot.aim().toShortString() + "=" + level.getBlockState(spot.aim()).getBlock())
                .isEqualTo(side);

        // D ASKS THE QUESTION THE POUR ASKS, which is not "does the nearest verified stand work".
        //
        // Asking that would fail this arm for something it is not about. `standToPour` returns
        // the NEAREST verified stand, and in this arena the nearest one's line to the side neighbour
        // rides the edge between the target's floor and the ring cell below-and-beside it: the
        // segment clip that chose it says clear, the float-derived ray the bucket fires stops on
        // `254626,223,99999` obsidian. That fork is a KNOWN defect with its own note on
        // {@link JourneyPour#aimThatLandsIn}, and production already survives it — the pour fires,
        // compares, and moves to the next candidate. An arm about the aim LIST must not fail on it.
        //
        // So: of the stands the production predicate accepts for the side neighbour, does at least
        // one actually put the fluid in the target when the shot is fired? That is what the rung
        // needs and the widening is what makes any of them exist.
        driver.fakePlayer().discard();
        List<BlockPos> verified = new ArrayList<>();
        for (BlockPos foot : JourneyPour.standCandidates(target, AWAY))
            if (JourneyPour.gradeFoot(level, fp, foot, side, target, new LinkedHashMap<>())
                    != JourneySight.REFUSED) verified.add(foot.immutable());
        ctx.record("verifiedForTheSide", verified.size() + " cells pass the production stand check"
                + " (aiming at " + side.toShortString() + "): " + verified.stream().limit(8).toList()
                + (verified.size() > 8 ? " … (first 8 shown)" : ""));

        List<BlockPos> lands = new ArrayList<>();
        String firstShot = "no cell to try";
        // Capped, and the cap is printed rather than left to look like exhaustion. Each try spawns a
        // bot, and the answer this arm needs is "at least one cell", not a census.
        int tried = 0;
        for (BlockPos foot : verified) {
            if (tried++ >= SHOTS_TRIED) break;
            ServerWorldDriver shooter = body(ctx, foot);
            Shot s = fire(ctx, shooter, side);
            if (tried == 1) firstShot = s.where();
            if (target.equals(s.landing())) lands.add(foot.immutable());
            shooter.fakePlayer().discard();
        }
        ctx.record("shots", "tried " + Math.min(verified.size(), SHOTS_TRIED) + "/" + verified.size()
                + " cells (cap " + SHOTS_TRIED + "), " + lands.size() + " landed in "
                + target.toShortString() + ": " + lands.stream().limit(8).toList()
                + "; first shot " + firstShot);
        ctx.check(lands.isEmpty()).as("D being chosen is not the same as hitting — of the stands aiming"
                + " at the side neighbour " + side.toShortString() + ", at least one must actually put"
                + " the fluid in " + target.toShortString() + " when fired. " + verified.size()
                + " cells passed the stand check, " + Math.min(verified.size(), SHOTS_TRIED)
                + " were tried, and none hit. First shot: " + firstShot)
                .isFalse();
    }

    /** The candidates the production guard refuses for an occupied stand cell, by walking the same
     *  scan one cell at a time — the histogram counts REASONS and cannot say which cell cast which
     *  vote. The key below must match the veto key {@link JourneyPour} produces, word for word. */
    private static List<BlockPos> occupiedVoters(SceneContext ctx, ServerPlayer fp, BlockPos target,
                                                 List<BlockPos> candidates) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos foot : candidates) {
            Map<String, Integer> one = new LinkedHashMap<>();
            JourneyPour.gradeFoot(ctx.level(), fp, foot, target.relative(AWAY), target, one);
            if (one.containsKey("foot cell occupied")) out.add(foot.immutable());
        }
        return out;
    }
}
