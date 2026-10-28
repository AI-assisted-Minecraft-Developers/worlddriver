package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The N4–N6/N9 portal family, split out of {@link WorldDriverProcessScenes} verbatim so that class
 * fits the 3000-line source budget (the same mechanical seam-split the {@code *Phase} phase
 * classes did for {@code Navigator}). Nothing here is new, renamed or reordered: these are the
 * obsidian-cast, portal-frame, portal-lighting, nether-transit and end-portal scenes, and
 * {@link WorldDriverProcessScenes#scenes()} calls {@link #register} at exactly the position they
 * occupied in the single list.
 *
 * <p>The bucket helpers ({@code scoopSource} / {@code pourInto} / {@code standTo} / {@code placeAt})
 * came with them because nothing outside this family calls them; {@code countItem} stayed behind
 * because three families do.
 */
public final class WorldDriverPortalScenes {

    private WorldDriverPortalScenes() {}

    /** Registers this family in the order {@link WorldDriverProcessScenes#scenes()} used before the split. */
    static void register(List<Scene> out) {
        out.addAll(List.of(
                // N6's first question, and the one the ladder cannot ask cheaply: a lit portal is
                // worth nothing if the bot that lit it cannot walk through. Required as of the
                // teleport fix — it was written as a frontier sensor, went red on BOTH loaders for
                // the same reason, and is kept required so that reason cannot come back quietly.
                Scene.of("wd.serverEntersTheNether", 4_000,
                        WorldDriverPortalScenes::serverEntersTheNether),
                // The whole of N5 in one scene, from a flat floor: build the mould, cast the ten,
                // light it. Written as a frontier sensor and green on both loaders first try, so it
                // is required — it is the ladder's own plan, and the ladder is expensive to ask.
                Scene.of("wd.serverBuildsAndLightsAPortal", 4_000,
                        WorldDriverPortalScenes::serverBuildsAndLightsAPortal),
                // N9/N10: the last two verbs between a stronghold and the dragon. Green on both
                // loaders first try, so required — the eye insert is a useOn-only item and would
                // regress the same silent way the flint-and-steel did.
                Scene.of("wd.serverOpensTheEndPortal", 4_000,
                        WorldDriverPortalScenes::serverOpensTheEndPortal)));
    }

    /** {@code dx/dy} of a frame cell relative to the ring's bottom-left, for evidence keys that stay
     *  readable when the arena moves. */
    private static String label(BlockPos at, int x0, int y0) {
        return (at.getX() - x0) + "_" + (at.getY() - y0);
    }

    /**
     * Put the bot where the wall cell it is about to work on is at EYE LEVEL, on a block of its own.
     *
     * <p>Aiming at the backing behind a cell only reaches that cell if the ray is close to
     * horizontal. From one fixed ledge the ray to a cell five blocks up is steep enough to enter the
     * wall a block low, and the run that found this had just cast obsidian into exactly that block:
     * the scoop hit the fresh obsidian, left the water behind, and the NEXT cell reported an empty
     * bucket. So the standpoint is a function of the target, not a constant.
     */
    private static void standTo(ServerLevel level, ServerPlayer fp, BlockPos target, int floorY) {
        int feet = Math.max(floorY + 2, target.getY() - 1);
        level.setBlockAndUpdate(new BlockPos(target.getX(), feet - 1, target.getZ() - 2),
                Blocks.STONE.defaultBlockState());
        fp.setPos(target.getX() + 0.5, feet, target.getZ() - 1.5);
    }

    /** Stand beside a one-cell pool and pick it up. The empty bucket clips {@code SOURCE_ONLY}, so
     *  the ray stops on the fluid itself rather than passing through to the floor. */
    private static boolean scoopSource(SceneBot bot, ServerPlayer fp, BlockPos at, int floorY,
                                       net.minecraft.world.item.Item expected) {
        ServerLevel level = (ServerLevel) fp.level();
        boolean inWall = at.getY() > floorY;
        if (inWall) standTo(level, fp, at, floorY);
        else fp.setPos(at.getX() + 0.5, floorY + 1, at.getZ() + 1.5);
        Harness.tick();
        if (!bot.hold(Items.BUCKET)) return false;
        bot.lookAt(inWall ? at.relative(Direction.SOUTH) : at);
        Harness.tick();
        bot.use();
        Harness.tick();
        return WorldDriverProcessScenes.countItem(fp, expected) >= 1;
    }

    /** Put the held fluid into a cell carved in the wall, by standing on the ledge in front of it and
     *  aiming at the SOLID BACKING behind it: a bucket fills the neighbour of the face its ray lands
     *  on, so aiming into the air cell itself hits nothing and the fluid goes wherever the ray
     *  eventually stops — which is how an earlier version poured its water onto the floor. */
    private static boolean pourInto(SceneBot bot, ServerPlayer fp, BlockPos target, int floorY,
                                    net.minecraft.world.item.Item held, Block want, ServerLevel level) {
        standTo(level, fp, target, floorY);
        Harness.tick();
        if (!bot.hold(held)) return false;
        bot.lookAt(target.relative(Direction.SOUTH));
        Harness.tick();
        bot.use();
        Harness.tick();
        return level.getBlockState(target).getBlock() == want;
    }

    /**
     * Walk a server-side player through a lit portal and out the other side, into the Nether.
     *
     * <p>The first question of ROADMAP N6, and it is asked here rather than on the ladder because
     * the rung that asks it in the field has just spent an hour of wall-clock casting ten obsidian
     * at the bottom of a shaft. A portal that lights and does not transit would invalidate that
     * whole rung, and it would do so silently: the bot would stand in purple fog forever and the
     * budget would run out, which reads as a slow walk.
     *
     * <p><b>Why it was genuinely in doubt.</b> {@code JoinedPlayers.JoinedBody} keeps
     * {@code tick()} empty for the level's entity loop, so the player is ticked only when its driver
     * steps it. Vanilla's portal handling lives in {@code Entity.baseTick()}, which each step reaches
     * through {@code JoinedBody.pump}'s {@code doTick()}, and {@code checkInsideBlocks()} rides
     * {@code move()}. So the machinery is present — but "present"
     * and "works for a player with a connection that discards every packet it is given" are different
     * claims, and only one of them can be tested.
     *
     * <p><b>Frontier, not required.</b> If this is red the finding is engine-shaped and belongs in
     * TODO.md, not in a gate that blocks unrelated work. Promote it the day it is green.
     *
     * <p>Staged: the frame is set as obsidian rather than cast, because the cast has two scenes of
     * its own and this one is about the seam AFTER a portal exists. The lighting is NOT staged —
     * it goes through the same flint-and-steel path {@code wd.serverLightsPortal} proves, so that a
     * portal built by the driver is what the driver then tries to walk into.
     */
    private static void serverEntersTheNether(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            ctx.skip("this runtime has no Nether dimension (a data pack removed minecraft:the_nether), so there is nowhere to go");
            return;
        }

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 5; dx++)
                for (int dy = -1; dy <= 8; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        for (int dx = -4; dx <= 5; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        final int x0 = cx + 1, y0 = floorY + 1;
        List<BlockPos> frame = new ArrayList<>();
        frame.add(new BlockPos(x0, y0, cz));           frame.add(new BlockPos(x0 + 1, y0, cz));
        frame.add(new BlockPos(x0, y0 + 4, cz));       frame.add(new BlockPos(x0 + 1, y0 + 4, cz));
        for (int dy = 1; dy <= 3; dy++) {
            frame.add(new BlockPos(x0 - 1, y0 + dy, cz));
            frame.add(new BlockPos(x0 + 2, y0 + dy, cz));
        }
        for (BlockPos at : frame) level.setBlockAndUpdate(at, Blocks.OBSIDIAN.defaultBlockState());
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 1; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y0 + dy, cz), Blocks.AIR.defaultBlockState());

        SceneBot bot = SceneBot.spawn(ctx, level, x0 + 0.5, floorY + 1, cz + 2.5);
        var fp = bot.player();
        fp.getInventory().items.set(0, new ItemStack(Items.FLINT_AND_STEEL, 1));
        fp.getInventory().selected = 0;

        BlockPos hearth = new BlockPos(x0, y0, cz);
        bot.hold(Items.FLINT_AND_STEEL);
        bot.lookAt(hearth);
        Harness.tick();
        bot.useOn(hearth, Direction.UP);
        Harness.tick();
        BlockPos doorway = hearth.above();
        ctx.record("portal.lit", String.valueOf(level.getBlockState(doorway).getBlock()));
        ctx.expect(level.getBlockState(doorway).getBlock() == Blocks.NETHER_PORTAL)
                .as("the frame lights before anything is asked about walking through it").isTrue();

        // Standing in it is what starts vanilla's portal timer; a player's is ~80 ticks, so the
        // budget below is generous by design — a run that spends it all has found a player the timer
        // never starts for, which is a different finding from a player it never fires for. The bot
        // stands idle on the tick list with every key let go, so its body is stepped and nothing steers.
        bot.release();
        bot.unpark();
        fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);

        final int budget = 600;
        int ticked = 0;
        while (ticked < budget && fp.level() == level) { Harness.tick(); ticked++; }

        ctx.record("transit.ticks", ticked + (ticked >= budget ? " (budget exhausted)" : ""));
        ctx.record("transit.dimension", fp.level().dimension().location().toString());
        ctx.record("transit.pos", fp.blockPosition().toShortString());
        ctx.record("transit.standingIn", String.valueOf(fp.level().getBlockState(fp.blockPosition()).getBlock()));
        // Two readings, because they want opposite fixes: a bot that never entered the portal's
        // own block is a POSITIONING fault, and a bot that stood in it for six hundred ticks
        // without moving is a TICK fault.
        ctx.record("transit.everInPortal",
                level.getBlockState(doorway).getBlock() == Blocks.NETHER_PORTAL ? "portal still present" : "portal gone");

        ctx.expect(fp.level().dimension()).as("the driven bot arrives in the Nether through its own portal")
                .isEqualTo(Level.NETHER);

        // WHERE it landed, and this is not a detail. Vanilla scales the destination by the ratio of
        // the two dimensions' coordinate_scale — 8:1 — so an overworld portal at x=100001 belongs at
        // nether x≈12500. A bot that arrives at the UNSCALED coordinate is in the Nether and is also
        // 87 000 blocks from the fortress the blaze rod rung will look for, and every rung above this
        // one would search the wrong world while this scene reported green.
        double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(
                level.dimensionType(), nether.dimensionType());
        BlockPos want = new BlockPos((int) Math.floor(cx * scale), fp.blockPosition().getY(),
                (int) Math.floor(cz * scale));
        int drift = Math.max(Math.abs(fp.blockPosition().getX() - want.getX()),
                Math.abs(fp.blockPosition().getZ() - want.getZ()));
        ctx.record("transit.scale", String.valueOf(scale));
        ctx.record("transit.expectedXZ", want.getX() + "," + want.getZ() + " (drift " + drift + " blocks)");
        ctx.record("transit.arrivalPortal", String.valueOf(
                fp.level().getBlockState(fp.blockPosition()).getBlock()));
        ctx.record("transit.underfoot", String.valueOf(
                fp.level().getBlockState(fp.blockPosition().below()).getBlock()));
        // The dimension's own ceiling: a nether arrival above logical height is standing where the
        // roof is, which no portal search should ever return.
        ctx.record("transit.logicalHeight", nether.dimensionType().logicalHeight()
                + " (arrival y=" + fp.blockPosition().getY() + ")");
        ctx.expect(drift).as("the arrival is at the 8:1-scaled coordinate, not the raw one")
                .isAtMost(128);
        ctx.expect(fp.blockPosition().getY()).as("the arrival is under the Nether's own roof")
                .isAtMost(nether.dimensionType().logicalHeight());
        ctx.passNote("reached the Nether through the portal the bot lit itself in " + ticked + " ticks, landing at "
                + fp.blockPosition().toShortString() + " (expected near " + want.getX() + "," + want.getZ() + ")");
    }

    /**
     * The whole of N5 end to end: a flat floor, a bucket, a flint-and-steel and a pile of
     * cobblestone go in; a <b>lit nether portal</b> comes out.
     *
     * <p>Each step is already proven on its own — {@code wd.serverCastsObsidian} the cast,
     * {@code wd.serverCastsAPortalFrame} the ten-from-one-bucket shuttle,
     * {@code wd.serverLightsPortal} the ignition. What none of them covers is the step the rung
     * actually spends its blocks on: those three all work a wall that was <b>staged</b>, and in the
     * field there is no two-thick wall waiting beside the lava. The bot has to build the mould.
     *
     * <p><b>Placement is exact, which is why this is affordable.</b> {@code mc.bot.useItem} with a
     * {@code pos} and {@code face} builds its own {@code BlockHitResult} from them rather than
     * ray-tracing for one, so the mould is bookkeeping rather than a navigation problem. It does
     * check reach, as the server's packet handler would, so each block is placed from a spot just in
     * front of it; never inside it, because a block cannot be placed into a cell the placer is
     * standing in ({@code isUnobstructed}).
     *
     * <p><b>Order is what makes every block placeable.</b> A free-standing wall has nothing to
     * place against, so the backing slab at {@code z=cz+1} goes up first, bottom-up, each block
     * supported by the one below and the lowest by the floor. Every solid cell of the front layer
     * is then placed against the backing behind it — {@code useBlock(backing, NORTH)} — which needs
     * no support of its own. Building the front layer first would strand every cell whose lower
     * neighbour is one of the sixteen that must stay air.
     *
     * <p>Staged: the floor, the lava lake, the reservoir, and the block the bot stands on to pour
     * from. That is terrain and a pillar — the terrain the seed provides, and a climb
     * {@code ascendByTowering} owns on the ladder. Everything the rung must MAKE is made here.
     */
    private static void serverBuildsAndLightsAPortal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -10; dx <= 20; dx++)
                for (int dy = -1; dy <= 12; dy++)
                    for (int dz = -12; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });
        for (int dx = -10; dx <= 20; dx++)
            for (int dz = -12; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        final int x0 = cx, y0 = floorY + 1;

        List<BlockPos> lake = new ArrayList<>();
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++) {
                BlockPos at = new BlockPos(cx + 14 + dx, floorY, cz - 9 + dz);
                level.setBlockAndUpdate(at, Blocks.LAVA.defaultBlockState());
                lake.add(at);
            }
        BlockPos well = new BlockPos(cx - 8, floorY, cz - 8);
        level.setBlockAndUpdate(well, Blocks.WATER.defaultBlockState());

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 7.5, floorY + 1, cz - 5.5);
        var fp = bot.player();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().items.set(1, new ItemStack(Items.BUCKET, 1));
        fp.getInventory().items.set(2, new ItemStack(Items.FLINT_AND_STEEL, 1));
        for (int slot = 3; slot <= 5; slot++)
            fp.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;

        // ---- 1. the backing slab, bottom-up, each block resting on the one below ----
        int placed = 0, wanted = 0;
        for (int dy = 0; dy <= 6; dy++)
            for (int dx = -2; dx <= 3; dx++) {
                BlockPos at = new BlockPos(x0 + dx, y0 + dy, cz + 1);
                wanted++;
                if (placeAt(bot, level, at, at.below(), Direction.UP, floorY)) placed++;
                else ctx.record("backing.missed." + dx + "_" + dy,
                        String.valueOf(level.getBlockState(at).getBlock()));
            }
        ctx.record("mould.backing", placed + "/" + wanted);

        // ---- 2. the front layer's solid cells, each against the backing behind it ----
        // Everything in the 6x8 face EXCEPT the ten ring cells, the six interior cells and the two
        // cap notches the top row is cast against.
        java.util.Set<BlockPos> hollow = new java.util.HashSet<>();
        List<BlockPos[]> plan = new ArrayList<>();
        plan.add(new BlockPos[]{ new BlockPos(x0, y0, cz),         new BlockPos(x0, y0 + 1, cz) });
        plan.add(new BlockPos[]{ new BlockPos(x0 + 1, y0, cz),     new BlockPos(x0 + 1, y0 + 1, cz) });
        for (int dy = 1; dy <= 3; dy++) {
            plan.add(new BlockPos[]{ new BlockPos(x0 - 1, y0 + dy, cz), new BlockPos(x0, y0 + dy, cz) });
            plan.add(new BlockPos[]{ new BlockPos(x0 + 2, y0 + dy, cz), new BlockPos(x0 + 1, y0 + dy, cz) });
        }
        List<BlockPos> caps = List.of(new BlockPos(x0, y0 + 5, cz), new BlockPos(x0 + 1, y0 + 5, cz));
        plan.add(new BlockPos[]{ new BlockPos(x0, y0 + 4, cz),     caps.get(0) });
        plan.add(new BlockPos[]{ new BlockPos(x0 + 1, y0 + 4, cz), caps.get(1) });
        List<BlockPos> interior = new ArrayList<>();
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 1; dy <= 3; dy++) interior.add(new BlockPos(x0 + dx, y0 + dy, cz));
        for (BlockPos[] step : plan) hollow.add(step[0]);
        hollow.addAll(interior);
        hollow.addAll(caps);

        int wall = 0, wallWanted = 0;
        for (int dy = 0; dy <= 6; dy++)
            for (int dx = -2; dx <= 3; dx++) {
                BlockPos at = new BlockPos(x0 + dx, y0 + dy, cz);
                if (hollow.contains(at)) continue;
                wallWanted++;
                if (placeAt(bot, level, at, at.relative(Direction.SOUTH), Direction.NORTH, floorY)) wall++;
                else ctx.record("wall.missed." + dx + "_" + dy,
                        String.valueOf(level.getBlockState(at).getBlock()));
            }
        ctx.record("mould.wall", wall + "/" + wallWanted);
        ctx.record("cobblestone.left", WorldDriverProcessScenes.countItem(fp, Items.COBBLESTONE) + "");
        ctx.expect(placed + wall).as("every block of the mould goes where it was named")
                .isEqualTo(wanted + wallWanted);

        // ---- 3. the ten casts, one bucket, exactly as wd.serverCastsAPortalFrame proves ----
        ctx.expect(scoopSource(bot, fp, well, floorY, Items.WATER_BUCKET))
                .as("the reservoir fills the bucket with water").isTrue();
        int cast = 0;
        for (int i = 0; i < plan.size(); i++) {
            BlockPos cell = plan.get(i)[0], wet = plan.get(i)[1];
            if (!pourInto(bot, fp, wet, floorY, Items.WATER_BUCKET, Blocks.WATER, level)) {
                ctx.record("water.stuckAt", label(cell, x0, y0)); break;
            }
            if (!scoopSource(bot, fp, lake.get(i), floorY, Items.LAVA_BUCKET)) {
                ctx.record("lava.stuckAt", label(cell, x0, y0)); break;
            }
            pourInto(bot, fp, cell, floorY, Items.LAVA_BUCKET, Blocks.OBSIDIAN, level);
            if (level.getBlockState(cell).getBlock() == Blocks.OBSIDIAN) cast++;
            else ctx.record("cast.missed." + label(cell, x0, y0),
                    String.valueOf(level.getBlockState(cell).getBlock()));
            scoopSource(bot, fp, wet, floorY, Items.WATER_BUCKET);
        }
        ctx.record("frame.cast", cast + "/" + plan.size());
        ctx.expect(cast).as("ten obsidian cast into a mould the bot built itself")
                .isEqualTo(plan.size());

        // ---- 4. light it ----
        BlockPos hearth = new BlockPos(x0, y0, cz);
        standTo(level, fp, hearth, floorY);
        Harness.tick();
        ctx.expect(bot.hold(Items.FLINT_AND_STEEL)).as("flint-and-steel in hand").isTrue();
        bot.lookAt(hearth);
        Harness.tick();
        bot.useOn(hearth, Direction.UP);
        Harness.tick();

        int lit = 0;
        for (BlockPos c : interior)
            if (level.getBlockState(c).getBlock() == Blocks.NETHER_PORTAL) lit++;
        ctx.record("portal.cells", lit + "/" + interior.size());
        ctx.record("interior.after", String.valueOf(level.getBlockState(interior.get(0)).getBlock()));
        ctx.expect(lit).as("the portal the bot built and cast is lit end to end")
                .isEqualTo(interior.size());
        ctx.passNote("portal built on flat ground: laid " + (wanted + wallWanted) + " mould blocks, cast " + cast
                + " obsidian blocks with one bucket, lit " + lit + " portal cells");
    }

    /** Place a held cobblestone in {@code target} by clicking {@code face} of {@code support}. No aim
     *  and no walk: the click names its own cell and face. {@code mc.bot.useItem} refuses a block out
     *  of the player's reach, so the bot is first put a cell and a half in front of the target at its
     *  height — never in the target cell — where a parked bot stays put, since nothing steps it. */
    private static boolean placeAt(SceneBot bot, ServerLevel level, BlockPos target,
                                   BlockPos support, Direction face, int floorY) {
        bot.player().setPos(target.getX() + 0.5, Math.max(floorY + 1, target.getY() - 1),
                Math.min(target.getZ(), support.getZ()) - 1.5);
        if (!bot.hold(Items.COBBLESTONE)) return false;
        bot.useOn(support, face);
        Harness.tick();
        return !level.getBlockState(target).isAir();
    }

    /**
     * Set twelve eyes into a stronghold's frame and step through into the End.
     *
     * <p>Two verbs, both on the critical path and neither exercised anywhere else. Inserting an eye
     * is {@code EnderEyeItem.useOn} — the same {@code useOn}-only shape as the flint-and-steel, so a
     * bot that reaches for {@code useItemInHand} gets {@code PASS} and a frame that never fills.
     * Stepping through is {@code EndPortalBlock}, which reaches {@code changeDimension} by a
     * different road than the Nether's and lands on a fixed point rather than a searched one.
     *
     * <p><b>Why this is worth its own scene given the Nether already passes.</b> The teleport that
     * was swallowed is delivered the same way here, but the destination is
     * {@code ServerLevel.END_SPAWN_POINT} rather than a scaled coordinate, so a bot that arrived
     * "somewhere in the End" would look correct on a dimension check while standing in the void
     * beside the island. The assertion is on the platform.
     *
     * <p>Staged: the frame and the twelve eyes. Where eyes come from is
     * {@code ENDER_PEARL}/{@code EYE_OF_ENDER}'s question and finding the stronghold is
     * {@code STRONGHOLD}'s; what this owns is that a driven bot can spend them and survive the
     * crossing.
     */
    private static void serverOpensTheEndPortal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        ServerLevel end = level.getServer().getLevel(Level.END);
        if (end == null) {
            ctx.skip("this runtime has no End dimension (a data pack removed minecraft:the_end), so there is nowhere to go");
            return;
        }

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -1; dy <= 4; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        // Twelve frames round a 3x3, each FACING the middle — the shape
        // EndPortalFrameBlock.getOrCreatePortalShape() looks for. A frame laid without facings is a
        // frame that fills with eyes and never becomes a portal, which reads as a broken insert.
        final int y = floorY + 1;
        List<BlockPos> frames = new ArrayList<>();
        for (int d = -1; d <= 1; d++) {
            frames.add(place(level, new BlockPos(cx + d, y, cz - 2), Direction.SOUTH));
            frames.add(place(level, new BlockPos(cx + d, y, cz + 2), Direction.NORTH));
            frames.add(place(level, new BlockPos(cx - 2, y, cz + d), Direction.EAST));
            frames.add(place(level, new BlockPos(cx + 2, y, cz + d), Direction.WEST));
        }
        ctx.record("frame.blocks", frames.size() + "");

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 3.5);
        var fp = bot.player();
        fp.getInventory().items.set(0, new ItemStack(Items.ENDER_EYE, 12));
        fp.getInventory().selected = 0;

        int set = 0;
        for (BlockPos at : frames) {
            if (!bot.hold(Items.ENDER_EYE)) { ctx.record("eyes.ranOutAt", at.toShortString()); break; }
            bot.useOn(at, Direction.UP);
            Harness.tick();
            if (level.getBlockState(at).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)) set++;
            else ctx.record("eye.missed." + at.toShortString(), String.valueOf(level.getBlockState(at)));
        }
        ctx.record("eyes.set", set + "/" + frames.size());
        ctx.record("eyes.left", WorldDriverProcessScenes.countItem(fp, Items.ENDER_EYE) + "");
        ctx.expect(set).as("all twelve eyes go into the frame from a driven bot's hand")
                .isEqualTo(frames.size());

        BlockPos doorway = new BlockPos(cx, y, cz);
        ctx.record("portal.formed", String.valueOf(level.getBlockState(doorway).getBlock()));
        ctx.expect(level.getBlockState(doorway).getBlock() == Blocks.END_PORTAL)
                .as("the twelfth eye opens the portal").isTrue();

        // Idle on the tick list with every key let go, as for the Nether: stepped, never steered.
        bot.release();
        bot.unpark();
        fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);

        final int budget = 400;
        int ticked = 0;
        while (ticked < budget && fp.level() == level) { Harness.tick(); ticked++; }
        ctx.record("transit.ticks", ticked + (ticked >= budget ? " (budget exhausted)" : ""));
        ctx.record("transit.dimension", fp.level().dimension().location().toString());
        ctx.record("transit.pos", fp.blockPosition().toShortString());
        ctx.expect(fp.level().dimension()).as("the driven bot crosses into the End").isEqualTo(Level.END);

        // The platform, not merely the dimension. END_SPAWN_POINT is fixed, so "somewhere in the
        // End" and "on the obsidian island vanilla builds for arrivals" are different claims and
        // only the second one can fight a dragon.
        BlockPos want = net.minecraft.server.level.ServerLevel.END_SPAWN_POINT;
        int drift = Math.max(Math.abs(fp.blockPosition().getX() - want.getX()),
                Math.abs(fp.blockPosition().getZ() - want.getZ()));
        ctx.record("transit.spawnPoint", want.toShortString() + " (drift " + drift + " blocks)");
        ctx.record("transit.underfoot", String.valueOf(
                fp.level().getBlockState(fp.blockPosition().below()).getBlock()));
        ctx.expect(drift).as("the arrival is on the End's own spawn platform").isAtMost(16);
        ctx.passNote("twelve eyes opened the portal, crossed into the End in " + ticked + " ticks, landing at "
                + fp.blockPosition().toShortString());
    }

    /** An end-portal frame at {@code at} facing {@code towards}, returned for the caller's list. */
    private static BlockPos place(ServerLevel level, BlockPos at, Direction towards) {
        level.setBlockAndUpdate(at, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING, towards));
        return at;
    }

}
