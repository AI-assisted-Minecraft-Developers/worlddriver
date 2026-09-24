package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
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

/**
 * A support that passes every test the placer has and still cannot be built on.
 *
 * <p><b>Why this is a scene and not another ladder run.</b> Three fixes in a row were recoveries for
 * rare failures, and three consecutive ladder runs — stopping at rungs 11, 10 and 6, each on a
 * different cause — did not exercise a single one. A ladder answers "did it happen this time"; what
 * has to be answered is "when it happens, does the recovery work".
 *
 * <p><b>What the first version of this scene found, which is why it is now about lily pads.</b> It
 * staged the bot over deep water and asserted a recovery that had just been added to the craft.
 * The scene went red on that assertion and green on everything else, and the evidence said why:
 * {@code station.steppingOff.0 = …219 → …216} then {@code furnace.crafted = 1}. The job already
 * belonged to {@code JourneyStation.makeRoomForAStation}, which runs earlier and is strictly more
 * capable, so the new retry never got a turn. The retry was removed; a scene paid for itself inside
 * five minutes by deleting a fix instead of confirming it.
 *
 * <p><b>The occasion that is real.</b> Ladder j47's furnace rung failed in three ticks holding
 * sixteen cobblestone and a table, with the error "a crafting table is needed (there is one in the
 * inventory, but no free spot beside the feet to place it)", and {@code makeRoomForAStation} wrote
 * no row at all — it had answered "there is room". The one line that says why lives in the game
 * log rather than the results:
 *
 * <pre>
 * [craft] placeNearby: click failed cell=67,64,59 (air) below=67,63,59 (lily_pad)
 * </pre>
 *
 * A lily pad is not air and cannot be replaced, so it passes the support test both the placer and
 * the recovery use — and its top face holds nothing. The rung then reported "no spot" about a bot
 * that had a spot it could not use.
 *
 * <p><b>The staging is taken from the bot, not predicted.</b> A player dropped into water settles at
 * whatever row the surface puts it in, and the whole point of this arena is that the pad sits in the
 * bot's OWN row. So the pool is built first, the bot is dropped and stepped, and only then is the
 * pad placed beside where it actually came to rest. Predicting that row is how a scene ends up
 * asserting on geometry it does not have.
 */
public final class JourneyWorkableSpotScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.journeyCraftStepsAsideForRoom", 4_000,
                        JourneyWorkableSpotScenes::craftStepsAsideForRoom),
                // The same complaint one layer down: a dig that gives up and says nothing.
                Scene.of("wd.journeyNamesTheCellADigCouldNotOpen", 1_200,
                        JourneyWorkableSpotScenes::namesTheCellADigCouldNotOpen));
    }

    /** Ticks one give-up dig gets here. Small on purpose — the positive control is a cell that can
     *  never open, so every tick past the first is spent proving the same thing twice. */
    private static final int DIG_TICKS = 40;

    /** How far along z this arm's ground sits from the pool the other arm floods. */
    private static final int DIG_Z = 24;

    /** How far the positive control sits from the bot, in cells. Twenty-four: well outside
     *  {@code Body.canBreak}'s reach — which is the ONLY gate {@code breakItWhereItStands} has, as
     *  the bedrock version of this arm proved by removing bedrock — and about three times what
     *  {@link #DIG_TICKS} buys a walking bot, so the budget cannot expire "nearly" in reach and make
     *  this control depend on pathfinding luck. Not further: a cell several chunks out is one whose
     *  staging depends on what the arena's ticket keeps loaded, and that is a different bug to debug. */
    private static final int DIG_FAR = 24;

    /**
     * A dig that gives up names the cell it left behind — and a dig that succeeds stays quiet.
     *
     * <p><b>Calibrating the instrument, on a known positive AND a known negative in one arm.</b>
     * {@code mineCellOrGiveUp} records only the cells that did NOT open, which makes the ABSENCE of a
     * row meaningful — and an absence is only meaningful if something would have written one.
     * Without this arm, nothing would ever have seen that row: rung 12's rehearsal asked for three
     * doorway cells, got two, and the run contained no line at all between "three cells to clear"
     * and "one cell still blocked". A silence that has never been seen to speak is not evidence of
     * success; it is evidence of nothing.
     *
     * <p><b>The positive is DISTANCE, and the first version got that wrong in a way worth keeping.</b>
     * It staged bedrock, on the reasoning that unbreakable-by-construction beats unbreakable-by-
     * circumstance. The arm went red and {@code subject.after} said why: {@code …100024=air} — the
     * bedrock was gone. {@code breakItWhereItStands} goes through {@code destroyBlock}, which honours
     * neither hardness nor reach for this bot, so "can never be mined" is not a property this arena
     * can buy with a block id.
     *
     * <p>That failure is also a reading about the case this instrument was added for: a bot whose
     * dig removes BEDROCK did not leave rung 12's doorway cobblestone standing because it was too
     * hard. It never got within reach of it. So the positive here is a cell far enough away that the
     * give-up budget expires first — which is the real failure mode rather than a substitute for it.
     *
     * <p>Plain stone at the bot's elbow is the negative, and the two assertions take opposite
     * values, so a scene-global leaking between them could not satisfy both.
     */
    private static void namesTheCellADigCouldNotOpen(SceneContext ctx) {
        // Its own patch of ground, clear of the lily-pad pool this file's other arm digs out — the two
        // share an origin, and an arena that overlaps another arm's is a scene that owns a global by
        // accident rather than by declaration.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = DIG_Z - 3; dz <= DIG_Z + 3; dz++) {
                for (int dy = 0; dy <= 4; dy++) ctx.setBlock(dx, SURFACE + dy, dz, Blocks.AIR);
                ctx.setBlock(dx, SURFACE - 1, dz, Blocks.STONE);
            }
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = DIG_Z - 3; dz <= DIG_Z + 3; dz++)
                    for (int dy = -1; dy <= 4; dy++) ctx.setBlock(dx, SURFACE + dy, dz, Blocks.AIR);
        });
        BlockPos wontOpen = ctx.rel(DIG_FAR, SURFACE, DIG_Z);
        BlockPos willOpen = ctx.rel(-1, SURFACE, DIG_Z);
        ctx.setBlock(DIG_FAR, SURFACE, DIG_Z, Blocks.STONE);
        ctx.setBlock(DIG_FAR, SURFACE - 1, DIG_Z, Blocks.STONE);
        ctx.setBlock(-1, SURFACE, DIG_Z, Blocks.STONE);
        ctx.cleanup(() -> {
            ctx.setBlock(DIG_FAR, SURFACE, DIG_Z, Blocks.AIR);
            ctx.setBlock(DIG_FAR, SURFACE - 1, DIG_Z, Blocks.AIR);
        });

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, SURFACE, DIG_Z));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();
        ServerLevel level = ctx.level();
        ctx.record("staged.cells", "out of reach: " + wontOpen.toShortString() + "="
                + level.getBlockState(wontOpen).getBlock() + " (" + DIG_FAR + " blocks from the bot, budget "
                + DIG_TICKS + " ticks); within reach: " + willOpen.toShortString()
                + "=" + level.getBlockState(willOpen).getBlock() + "; bot at "
                + fp.blockPosition().toShortString());
        ctx.check(level.getBlockState(wontOpen).isAir()).as("Control: the positive cell must contain a"
                + " block before the run starts. An empty cell counts as opened no matter who digs it,"
                + " and this arm would then be testing 0==0").isFalse();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.PORTAL_LIT, driver);
        rig.mineCellOrGiveUp(wontOpen, DIG_TICKS, () -> rig.mineCellOrGiveUp(willOpen, DIG_TICKS, () -> {
            Object saidNo = rig.evidenceOf("mineCell." + wontOpen.toShortString());
            Object saidYes = rig.evidenceOf("mineCell." + willOpen.toShortString());
            ctx.record("subject.wontOpen", String.valueOf(saidNo));
            ctx.record("subject.willOpen", String.valueOf(saidYes));
            ctx.record("subject.after", wontOpen.toShortString() + "="
                    + level.getBlockState(wontOpen).getBlock() + ", " + willOpen.toShortString()
                    + "=" + level.getBlockState(willOpen).getBlock());

            ctx.check(saidNo).as("A The cell that could not be dug must leave a row. Without it, the"
                    + " absence of a mineCell row would mean both 'everything was dug' and 'the row is"
                    + " never written', and downstream code draws its conclusion from that silence")
                    .isNotNull();
            ctx.check(String.valueOf(saidNo)).as("A2 The row must name the block that is still there: "
                    + saidNo).contains("stone");
            // The literals below are matched against text written by JourneyRig.sayIfStillThere.
            ctx.check(String.valueOf(saidNo)).as("A3 The row must also state how far the bot was from"
                    + " the cell. 'Out of reach' and 'within reach but the swing missed' need opposite"
                    + " fixes, and both report the same block name: "
                    + saidNo).contains("cell-centre distance");
            ctx.check(String.valueOf(saidNo)).as("A4 The row must carry the gate's own answer, not a"
                    + " distance that resembles it. The authority measures eye to cell centre while a"
                    + " reader computes cell to cell, one eye height apart, so a reading close to the"
                    + " limit means nothing on its own: " + saidNo).contains("canBreak=false");
            ctx.check(String.valueOf(saidNo)).as("A5 The row must also say which half of the gate"
                    + " failed. This arm stages insufficient distance while the cell has an exposed"
                    + " face; reading false here means the refusal came from enclosure rather than"
                    + " distance, and the arm is not testing what it claims to test: " + saidNo)
                    .contains("exposedFace=true");
            ctx.check(level.getBlockState(willOpen).isAir()).as("B Control: the diggable cell must"
                    + " actually be open. Otherwise the silence in C means 'not dug either', not 'dug,"
                    + " so no row was written': "
                    + level.getBlockState(willOpen).getBlock()).isTrue();
            ctx.check(saidYes).as("C The cell that was dug must leave no row. Writing a row for every"
                    + " cell would bury the one that matters, and the absence is meaningful only"
                    + " because A proved the row is written when it applies: " + saidYes).isNull();
        }));
    }

    /** The water surface, as a dy offset inside the arena box. */
    private static final int SURFACE = 20;

    /** How deep the pool is. Four: the placer probes {@code dy} of 0, −1 and +1, so two would already
     *  leave every support wet — this is double that, so a bot that settles a row lower than
     *  expected is still over water on every side. */
    private static final int DEPTH = 4;

    /** Half-width of the pool. Four: the probe only ever looks one cell out. */
    private static final int POOL = 4;

    /** Where the dry platform starts and ends, in +x. */
    private static final int SHORE_NEAR = 6;
    private static final int SHORE_FAR = 10;

    private static void craftStepsAsideForRoom(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        stage(ctx);

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, SURFACE, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 16));
        fp.getInventory().items.set(1, new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        BlockPos pad = foot.east();
        level.setBlockAndUpdate(pad, Blocks.LILY_PAD.defaultBlockState());
        ctx.record("staged.foot", foot.toShortString() + ", feet cell="
                + level.getBlockState(foot).getBlock() + ", below feet="
                + level.getBlockState(foot.below()).getBlock());
        ctx.record("staged.pad", pad.toShortString() + " = " + level.getBlockState(pad).getBlock());

        // THE CONTROL, and it is the whole finding: the staged spot has EXACTLY ONE support the old
        // test accepts, that support is the pad, and NOTHING here is sturdy enough to build on. A
        // green run without these three numbers could not tell "the recovery worked" from "the
        // test setup never posed the question".
        int loose = supports(level, foot, false);
        int sturdy = supports(level, foot, true);
        ctx.record("staged.supports", "the old criterion (not air and not replaceable) accepts " + loose
                + ", actually load-bearing (isFaceSturdy UP): " + sturdy);
        ctx.check(loose > 0).as("Control A: the old criterion must be fooled, otherwise this spot does"
                + " not reproduce the j47 situation: " + loose).isTrue();
        ctx.check(sturdy).as("Control B: no support may actually be load-bearing, otherwise the craft"
                + " would succeed anyway and 'the recovery ran' below would be 0==0: " + sturdy)
                .isEqualTo(0);
        ctx.check(level.getBlockState(pad).is(Blocks.LILY_PAD))
                .as("Control C: the lily pad must still be there (the water did not wash it away): "
                        + level.getBlockState(pad).getBlock())
                .isTrue();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.FURNACE, driver);
        WorldDriverJourneyScenes.craftKeepingTheTable(rig, "minecraft:furnace", 3_000, () -> {
            BlockPos ended = fp.blockPosition();
            Object stepped = rig.evidenceOf("station.steppingOff.0");
            ctx.record("subject.endedAt", ended.toShortString() + ", below feet="
                    + level.getBlockState(ended.below()).getBlock());
            ctx.record("subject.steppingOff", String.valueOf(stepped));
            ctx.record("subject.noGround", String.valueOf(rig.evidenceOf("station.noGround")));

            ctx.check(stepped).as(
                    "A The recovery must be observed to start: `station.steppingOff.0` must be written."
                    + " If it is missing, placerWouldFindRoom was fooled by the lily pad again and the"
                    + " fix did not take effect: "
                    + stepped).isNotNull();
            ctx.check(supports(level, ended, true) > 0).as(
                    "B The cell where the bot finally stands can actually bear a block (this checks the"
                    + " end position, not how far the bot walked): " + ended
                    + " has " + supports(level, ended, true)).isTrue();
            ctx.check(rig.carrying("minecraft:furnace")).as(
                    "C The furnace must actually be crafted. If A and B pass and this fails, moving to"
                    + " a new spot did not fix this failure")
                    .isEqualTo(1);
        });
    }

    /**
     * How many of the cells {@code PlaceNearby.place} probes have a usable support.
     *
     * <p>{@code sturdy=false} is the test the placer and {@code JourneyStation} both used before
     * 2026-08-24: not air, not replaceable. {@code sturdy=true} adds the question the click will
     * actually ask. Both are spelled out here rather than called because the placer's copy is
     * private to a process and takes a {@code Body} — and because the SCENE's job is to state
     * what it staged, in numbers a reader can check, rather than to agree with the code under test
     * by construction.
     */
    private static int supports(ServerLevel level, BlockPos foot, boolean sturdy) {
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        int n = 0;
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                var cs = level.getBlockState(cell);
                var bs = level.getBlockState(below);
                if (!cs.canBeReplaced()) continue;
                if (bs.isAir() || bs.canBeReplaced()) continue;
                if (sturdy && !bs.isFaceSturdy(level, below, Direction.UP)) continue;
                n++;
            }
        }
        return n;
    }

    private static void stage(SceneContext ctx) {
        clearBox(ctx);
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                ctx.setBlock(dx, SURFACE - DEPTH - 1, dz, Blocks.STONE);
        for (int dx = -POOL; dx <= POOL; dx++)
            for (int dz = -POOL; dz <= POOL; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.WATER);
        // The shore, and the rim that lets the bot wade out rather than climb: this scene is about
        // the craft, and a bot that cannot leave the pool would fail it for the wrong reason.
        for (int dx = POOL + 1; dx <= SHORE_FAR; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                for (int dy = SURFACE - DEPTH - 2; dy <= SURFACE + 6; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }
}
