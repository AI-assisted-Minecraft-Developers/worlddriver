package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.stagewright.journey.HoldStill;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
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
 * fits the 3000-line source budget (the same mechanical seam-split the {@code WalkerTick*} phase
 * classes did for {@code Walker}). Nothing here is new, renamed or reordered: these are the
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
                // Shipped optional as the capability probe for a rung nobody had written, and
                // promoted in the run that first saw it green — the whole cast works on a
                // server-side body with no engine change, so from here a red row means N4's
                // foundation moved rather than that it was never there.
                Scene.of("wd.serverCastsObsidian", 400,
                        WorldDriverPortalScenes::serverCastsObsidian),
                // N5's capability probe, written before the rung for the same reason the cast's was:
                // the rung that needs this stands at the bottom of a 36-block shaft with ten blocks
                // of obsidian it spent an hour casting, and "can the body work a flint-and-steel" is
                // a question worth answering in 200ms instead.
                Scene.of("wd.serverLightsPortal", 400,
                        WorldDriverPortalScenes::serverLightsPortal),
                // The other half of N5, and the expensive half: ten casts, one bucket, one water
                // placement. Proving the technique here costs a second; proving it on the ladder
                // costs a descent, and finding out there that it needs a second bucket costs the
                // rung below it too.
                Scene.of("wd.serverCastsAPortalFrame", 1_200,
                        WorldDriverPortalScenes::serverCastsAPortalFrame),
                // N6's first question, and the one the ladder cannot ask cheaply: a lit portal is
                // worth nothing if the body that lit it cannot walk through. Required as of the
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

    /**
     * Cast one obsidian block the way a portal is actually built.
     *
     * <p>The capability probe for ROADMAP N4, written before the rung rather than after it, because
     * the rung is a ~77-block descent to this seed's nearest lava and that would be an expensive
     * place to discover that the body cannot work a bucket. Everything the cast needs fits in eight
     * blocks of arena: fill an empty bucket from a lava source, empty it into a chosen cell, and let
     * water convert that cell to obsidian.
     *
     * <p><b>Why a cast and not a mine.</b> Obsidian that already exists — the crust of a lava lake —
     * needs a diamond pickaxe to take, and diamonds are several rungs above anything this route
     * holds. A portal is therefore not found but MADE: a mould, then lava placed into it one bucket
     * at a time, then water. That is why this asserts the block at a cell the body CHOSE, rather
     * than anywhere obsidian happens to appear.
     *
     * <p><b>One bucket, three uses.</b> The cast is scripted the way the ladder can actually afford
     * it: the water is placed ONCE at the build site and stays there, and the same bucket then
     * shuttles lava for every frame block. So this probe empties a water bucket, fills it from lava,
     * empties it into the mould — and then asserts the water source is STILL THERE, because that
     * last reading is the whole of the one-bucket claim. If the cast consumed its water, the portal
     * would cost ten trips back to open water and {@code JourneyStage.PORTAL_KIT}'s bill — one
     * bucket, three ingots — would be wrong for the second time.
     */
    private static void serverCastsObsidian(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 4; dx++)
                for (int dy = -1; dy <= 3; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        // A stone floor, one lava source to draw from, and a hole to cast into. The lava is placed
        // by the scene because this probe is about the BUCKET, not about finding lava — the journey
        // has a surveyed coordinate for that, and getting there is the rung's problem not this one's.
        for (int dx = -3; dx <= 4; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos source = new BlockPos(cx + 2, floorY, cz);
        level.setBlockAndUpdate(source, Blocks.LAVA.defaultBlockState());
        BlockPos mould = new BlockPos(cx - 1, floorY, cz);
        level.setBlockAndUpdate(mould, Blocks.AIR.defaultBlockState());
        // The mould needs a BOTTOM. Without one the arena's single floor layer leaves air under the
        // hole, the aim at that cell hits nothing, and the pour comes back PASS with the bucket
        // still full — a miss, which reads nothing like the CONSUME-but-empty-target of a pour that
        // landed somewhere else. A mould is a container, and a container with no floor is a hole.
        level.setBlockAndUpdate(mould.below(), Blocks.STONE.defaultBlockState());
        // The mould's far wall, and it is load-bearing rather than scenery. A fluid lands in the cell
        // in FRONT of the face the ray hit, so putting water in the cell ABOVE the mould needs a face
        // that points at that cell — and a hole has no such face: its rim points up, at the cell the
        // water is supposed to end up in. The wall supplies one. A real cast has it anyway, because
        // a mould is a trench cut into rock rather than a dent in a plain.
        BlockPos wall = new BlockPos(cx - 2, floorY + 1, cz);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());

        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        var fp = driver.fakePlayer();
        // A WATER bucket, not an empty one, and that is the order the plan runs in: the water is what
        // gets carried to the site, and the bucket is empty from then on except while it is holding
        // the lava it is about to pour.
        // And the bucket is deliberately NOT the selected slot. `useItemInHand` uses whatever the
        // hotbar has selected, so a body that just mined its way down holds a PICKAXE when it
        // reaches the lava — and a pickaxe's `use` returns PASS and changes nothing, which is
        // byte-identical to a bucket whose ray missed. The journey lost a whole run to that shape
        // (fill.result=PASS, lava_bucket=0, source untouched, aim dead on at 2.5 m). So this arena
        // starts the way the rung actually arrives, and every use below goes through holdItem.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().items.set(1, new ItemStack(Items.WATER_BUCKET, 1));
        fp.getInventory().selected = 0;

        // 1. Set the water down, against the wall, so it stands one cell above the mould. This is the
        //    verb the first version of this probe never asked about: it staged the water with
        //    setBlockAndUpdate, which proved the CONVERSION and left "can the body put water where it
        //    wants it" unanswered — and that question is the one a 77-block descent would have been
        //    an expensive place to fail.
        ctx.expect(driver.avatar().holdItem(Items.WATER_BUCKET))
                .as("the water bucket can be brought to the main hand from the bag").isTrue();
        driver.avatar().aimAtBlock(wall);
        ServerAvatarManager.tickAll();
        ctx.record("water.hand", String.valueOf(fp.getMainHandItem().getItem()));
        ctx.record("water.aim", String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                fp.getYRot(), fp.getXRot()));
        ctx.record("water.result", String.valueOf(driver.avatar().useItemInHand()));
        ctx.record("water.landedAt", whereIs(level, cx, floorY, cz, Blocks.WATER));
        ctx.record("bucket.afterWater", WorldDriverProcessScenes.countItem(fp, Items.BUCKET) + " empty");
        ctx.expect(level.getBlockState(mould.above()).getBlock() == Blocks.WATER)
                .as("water placed in the cell above the mould (see water.landedAt)").isTrue();

        // 2. Fill — by AIMING at the lava and using the item in hand, not by right-clicking the
        //    block. The first version of this used useBlock and came back bucket.filled=0 with the
        //    source untouched, which is correct behaviour and the wrong verb: useItemOn is the
        //    block-targeted path, and a bucket has no useOn. BucketItem does its work in `use`,
        //    which ray-traces from the eyes for a fluid — so where the body is LOOKING is the whole
        //    input, and aiming is not decoration here the way it is for a place.
        ctx.expect(driver.avatar().holdItem(Items.BUCKET))
                .as("the now-empty bucket is back in the main hand before the fill").isTrue();
        driver.avatar().aimAtBlock(source);
        // A tick between aiming and using, because the aim is state the body carries and the ray
        // trace reads it — and because a use that fails for want of a tick and a use that fails for
        // want of reach are the same FAIL from outside.
        ServerAvatarManager.tickAll();
        ctx.record("aim.yawPitch", String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                fp.getYRot(), fp.getXRot()));
        ctx.record("aim.eyeToSource", String.format(java.util.Locale.ROOT, "%.2f",
                fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(source))));
        // What vanilla's own pick would hit from where the body is looking. If this is not the
        // source, the aim is the problem; if it IS and the use still fails, the problem is the use.
        // Clipped the way BucketItem clips, not with Entity.pick, and the difference is not
        // cosmetic. `pick` calls getViewYRot, which LivingEntity overrides to return yHeadRot —
        // and Avatar.aimAtBlock sets yRot/xRot only, so a pick rays down a direction nobody aimed.
        // In this arena that produced a quietly nonsensical reading (`-5,-59,-2` for a floor at
        // y=220) and nothing depended on it; in the journey the same call drove a tunnel, and the
        // tunnel mined eight blocks AWAY from the lava. Item.getPlayerPOVHitResult reads
        // getXRot()/getYRot() directly, so this is what the use will actually see.
        var picked = aimedAt(fp, 6.0, true);
        ctx.record("aim.picks", picked.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? picked.getBlockPos().toShortString() + " " + level.getBlockState(picked.getBlockPos()).getBlock()
                : String.valueOf(picked.getType()));
        ctx.record("use.result", String.valueOf(driver.avatar().useItemInHand()));
        int filled = WorldDriverProcessScenes.countItem(fp, Items.LAVA_BUCKET);
        ctx.record("bucket.filled", filled);
        ctx.record("source.after", String.valueOf(level.getBlockState(source).getBlock()));
        ctx.expect(filled).as("lava bucket held after right-clicking a lava source").isAtLeast(1);

        // 3. Pour into the cell the body chose. Same verb and the same reason: emptying is also
        //    BucketItem.use, ray-traced. Aimed at the floor BENEATH the mould, because the fluid
        //    lands in the cell in FRONT of the face that was hit, not in the block that was hit —
        //    and the mould is ADJACENT to the body for that aim to be possible at all. Two cells
        //    away it was not: a ray toward a cell below floor level clips the floor's lip first, the
        //    bucket emptied onto whatever it did hit, and the mould stayed air while the use
        //    reported CONSUME.
        ctx.expect(driver.avatar().holdItem(Items.LAVA_BUCKET))
                .as("the filled bucket is in the main hand before the pour").isTrue();
        driver.avatar().aimAtBlock(mould.below());
        ServerAvatarManager.tickAll();          // as above: the aim has to land before the use reads it
        ctx.record("pour.hand", String.valueOf(fp.getMainHandItem().getItem()));
        ctx.record("pour.aim", String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                fp.getYRot(), fp.getXRot()));
        ctx.record("pour.result", String.valueOf(driver.avatar().useItemInHand()));
        // Where the lava actually went, when it did not go where it was aimed. A CONSUME with an
        // empty target cell means vanilla accepted the use and put the fluid somewhere else, which
        // is a different bug from a use vanilla refused.
        ctx.record("pour.holdingAfter", WorldDriverProcessScenes.countItem(fp, Items.LAVA_BUCKET) + " lava bucket(s)");
        ctx.record("pour.lavaLandedAt", whereIs(level, cx, floorY, cz, Blocks.LAVA));
        ctx.record("mould.afterPour", String.valueOf(level.getBlockState(mould).getBlock()));

        // 4. And it is already obsidian, with no step in between. A lava SOURCE placed next to water
        //    converts on the neighbour update, not on a fluid tick — so with the water set down first
        //    there is nothing to wait for, and the loop below is only here so that a build where the
        //    conversion IS deferred reports the conversion rather than a missing one.
        for (int t = 0; t < 40 && level.getBlockState(mould).getBlock() != Blocks.OBSIDIAN; t++) {
            ServerAvatarManager.tickAll();
        }
        ctx.record("mould.cast", String.valueOf(level.getBlockState(mould).getBlock()));
        ctx.expect(level.getBlockState(mould).getBlock() == Blocks.OBSIDIAN)
                .as("lava poured beneath standing water casts obsidian in the chosen cell").isTrue();

        // 5. The reading the one-bucket plan stands on: the water is STILL a source. A cast that ate
        //    its water would need a fresh trip to open water for every one of the portal's ten
        //    blocks, which is a different route with a different bill — and nothing about the
        //    obsidian above would have said so.
        ctx.record("water.afterCast", String.valueOf(level.getBlockState(mould.above()).getBlock()));
        ctx.expect(level.getBlockState(mould.above()).getBlock() == Blocks.WATER)
                .as("the water source survives the cast (one bucket shuttles all ten blocks)").isTrue();
    }

    /**
     * Cast a whole portal frame — ten obsidian — with one bucket, one water source, and no staging
     * of anything the ladder could not carry.
     *
     * <p>{@code wd.serverCastsObsidian} proved one cast and proved the reading the plan stands on:
     * <b>the water survives</b>. What it could not show is how ten casts share one source, and three
     * wrong answers to that were tried here before the right one. Each is recorded because each
     * failed as a <i>broken bucket</i> rather than as a wrong plan, which is the expensive kind.
     *
     * <ol>
     *   <li><b>Water down the outside of the face.</b> Reached {@code 0/10}: falling water spreads
     *       where it LANDS, and a pocket cut into a vertical face has no floor to spread along.</li>
     *   <li><b>Lava into every cell first, douse at the end.</b> The first pour missed and left the
     *       bucket full, so cell two reported "no empty bucket" and the real fault was two steps
     *       upstream — a cascade that hides its own cause.</li>
     *   <li><b>One source in the interior, let it flow to all ten.</b> It cannot, for two independent
     *       reasons. A scene ticks BODIES, not the level, so no fluid tick ever runs; and even under
     *       a live tick a source cannot wet the two cells <i>above</i> it, because water does not
     *       flow up.</li>
     * </ol>
     *
     * <p><b>What works is to move the water.</b> Placing a source in the interior cell adjacent to
     * the cell being cast reproduces {@code serverCastsObsidian}'s geometry exactly, for every cell,
     * and needs no flow at all — the conversion is a neighbour update, not a fluid tick. The one
     * bucket then falls out of the ordering for free: it is empty after placing the water (so it can
     * fetch lava), and empty again after pouring the lava (so it can take the water back). The well
     * is visited once, at the start; every later cell reuses the same water.
     *
     * <p><b>The top row does not cast against the interior.</b> Water below lava converts nothing —
     * vanilla looks ABOVE the lava and to its four sides, never under it — so the two top cells are
     * cast against a notch cut one block higher. A frame carved into a wall therefore costs twelve
     * cells of digging, not ten, and getting it wrong shows up only as two cells of standing lava.
     *
     * <p><b>The lava lake is not one cell, and that is a bill not a detail.</b> A scoop takes the
     * SOURCE and leaves air, so ten casts need ten distinct source cells and ten walks. The first
     * version of this scene staged a single lava block and read the second scoop's empty pool as a
     * broken fill.
     *
     * <p>Staged deliberately: the wall, the ledge, the lake and the reservoir are scenery. Under test
     * are the ten fills, the ten pours, the ten water moves, and that the interior ends up EMPTY —
     * because a portal with a flooded interior does not light.
     */
    private static void serverCastsAPortalFrame(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -8; dx <= 18; dx++)
                for (int dy = -1; dy <= 12; dy++)
                    for (int dz = -12; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        for (int dx = -8; dx <= 18; dx++)
            for (int dz = -12; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        // Two layers: z=cz is the layer the ring is carved out of, z=cz+1 backs it so a fluid put
        // into a carved cell has something to sit against — and so the aim has something to STOP on,
        // which is the whole reason the backing exists. A bucket fills the neighbour of the face its
        // ray lands on, and an air cell stops no ray.
        for (int dx = -3; dx <= 4; dx++)
            for (int dy = 1; dy <= 8; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + 1), Blocks.STONE.defaultBlockState());
            }

        final int x0 = cx, y0 = floorY + 1;
        // Ring cell -> the interior cell the water goes into for that cast. Above for the bottom
        // pair, below for the top pair, sideways for the two columns: every ring cell of a portal
        // touches the interior, which is what makes one source enough.
        List<BlockPos[]> plan = new ArrayList<>();
        plan.add(new BlockPos[]{ new BlockPos(x0, y0, cz),         new BlockPos(x0, y0 + 1, cz) });
        plan.add(new BlockPos[]{ new BlockPos(x0 + 1, y0, cz),     new BlockPos(x0 + 1, y0 + 1, cz) });
        for (int dy = 1; dy <= 3; dy++) {
            plan.add(new BlockPos[]{ new BlockPos(x0 - 1, y0 + dy, cz), new BlockPos(x0, y0 + dy, cz) });
            plan.add(new BlockPos[]{ new BlockPos(x0 + 2, y0 + dy, cz), new BlockPos(x0 + 1, y0 + dy, cz) });
        }
        // The top row is the exception, and it cost this scene a run to find. Water BELOW lava
        // converts nothing: vanilla checks {DOWN,NORTH,SOUTH,WEST,EAST}.getOpposite() around the
        // lava, which is ABOVE plus the four sides and never below. So the top pair is cast against
        // a notch cut one block higher, not against the interior underneath it — and a route that
        // carves a frame into a wall has to cut those two extra cells or come up two obsidian short
        // with no other symptom than "lava sat there".
        List<BlockPos> caps = List.of(new BlockPos(x0, y0 + 5, cz), new BlockPos(x0 + 1, y0 + 5, cz));
        plan.add(new BlockPos[]{ new BlockPos(x0, y0 + 4, cz),     caps.get(0) });
        plan.add(new BlockPos[]{ new BlockPos(x0 + 1, y0 + 4, cz), caps.get(1) });

        List<BlockPos> interior = new ArrayList<>();
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 1; dy <= 3; dy++) interior.add(new BlockPos(x0 + dx, y0 + dy, cz));
        for (BlockPos[] step : plan) level.setBlockAndUpdate(step[0], Blocks.AIR.defaultBlockState());
        for (BlockPos c : interior) level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        for (BlockPos c : caps) level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        ctx.record("frame.cells", plan.size() + " ring + " + interior.size() + " interior + "
                + caps.size() + " cap notches");

        // The ledge every pour is made from. One block up, and hard against the wall: from feet at
        // floorY+2 and z=cz-1.5 the eyes reach both the top row and the bottom row of the ring, and
        // a body one block further back reaches neither — 4.53 against a ~4.5-block ray.
        for (int dx = -4; dx <= 5; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz - 2), Blocks.STONE.defaultBlockState());

        // A lake, not a puddle: one source per cast. Flush in the floor so it cannot spread and reach
        // the reservoir, which is the same reason the route's real lake and its water must stay apart.
        List<BlockPos> lake = new ArrayList<>();
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++) {
                BlockPos at = new BlockPos(cx + 12 + dx, floorY, cz - 9 + dz);
                level.setBlockAndUpdate(at, Blocks.LAVA.defaultBlockState());
                lake.add(at);
            }
        BlockPos well = new BlockPos(cx - 6, floorY, cz - 8);
        level.setBlockAndUpdate(well, Blocks.WATER.defaultBlockState());

        ServerWorldDriver driver = SceneBody.mint(ctx, level, x0 + 0.5, floorY + 2, cz - 1.5);
        var fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().items.set(1, new ItemStack(Items.BUCKET, 1));
        fp.getInventory().selected = 0;

        ctx.expect(scoopSource(driver, fp, well, floorY, Items.WATER_BUCKET))
                .as("the reservoir fills the bucket with water").isTrue();

        int cast = 0, moves = 0;
        for (int i = 0; i < plan.size(); i++) {
            BlockPos cell = plan.get(i)[0], wet = plan.get(i)[1];

            if (!pourInto(driver, fp, wet, floorY, Items.WATER_BUCKET, Blocks.WATER, level)) {
                ctx.record("water.stuckAt", label(cell, x0, y0) + " 想放水到 " + label(wet, x0, y0)
                        + "，那格现在是 " + level.getBlockState(wet).getBlock());
                break;
            }
            moves++;

            if (!scoopSource(driver, fp, lake.get(i), floorY, Items.LAVA_BUCKET)) {
                ctx.record("lava.stuckAt", label(cell, x0, y0)
                        + "（湖格 " + lake.get(i).toShortString() + " = " + level.getBlockState(lake.get(i)).getBlock() + "）");
                break;
            }
            pourInto(driver, fp, cell, floorY, Items.LAVA_BUCKET, Blocks.OBSIDIAN, level);

            if (level.getBlockState(cell).getBlock() == Blocks.OBSIDIAN) cast++;
            else ctx.record("cast.missed." + label(cell, x0, y0), level.getBlockState(cell).getBlock()
                    + "（旁边 " + label(wet, x0, y0) + " 是 " + level.getBlockState(wet).getBlock() + "）");

            // The bucket is empty again, which is exactly what taking the water back needs. This is
            // the step that makes ONE bucket enough, and it is also the step that leaves the interior
            // clear at the end without a separate clean-up trip.
            if (!scoopSource(driver, fp, wet, floorY, Items.WATER_BUCKET) && i < plan.size() - 1)
                ctx.record("water.notRecovered." + label(cell, x0, y0),
                        String.valueOf(level.getBlockState(wet).getBlock()));
        }
        ctx.record("frame.cast", cast + "/" + plan.size());
        ctx.record("water.moves", moves + "");
        ctx.record("bucket.after", WorldDriverProcessScenes.countItem(fp, Items.BUCKET) + " 空 / "
                + WorldDriverProcessScenes.countItem(fp, Items.LAVA_BUCKET) + " 岩浆 / " + WorldDriverProcessScenes.countItem(fp, Items.WATER_BUCKET) + " 水");
        ctx.expect(cast).as("obsidian cast into every frame cell from one bucket")
                .isEqualTo(plan.size());

        // A flooded interior does not light, and the water that cast the frame was in it ten times.
        // The last scoop is the one that has to have taken it back out.
        int flooded = 0;
        for (BlockPos c : interior) if (!level.getFluidState(c).isEmpty()) flooded++;
        for (BlockPos c : caps) if (!level.getFluidState(c).isEmpty()) flooded++;
        ctx.record("interior.wetCells", flooded + "/" + (interior.size() + caps.size()));
        ctx.expect(flooded).as("the interior is dry once the last cast's water is scooped back")
                .isEqualTo(0);
        ctx.expect(WorldDriverProcessScenes.countItem(fp, Items.WATER_BUCKET)).as("the water comes home in the bucket").isEqualTo(1);
        ctx.passNote("10 obsidian from 1 bucket: " + moves + " water moves, "
                + plan.size() + " lake cells spent");
    }

    /** {@code dx/dy} of a frame cell relative to the ring's bottom-left, for evidence keys that stay
     *  readable when the arena moves. */
    private static String label(BlockPos at, int x0, int y0) {
        return (at.getX() - x0) + "_" + (at.getY() - y0);
    }

    /**
     * Put the body where the wall cell it is about to work on is at EYE LEVEL, on a block of its own.
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
    private static boolean scoopSource(ServerWorldDriver driver, ServerPlayer fp, BlockPos at, int floorY,
                                       net.minecraft.world.item.Item expected) {
        ServerLevel level = (ServerLevel) fp.level();
        boolean inWall = at.getY() > floorY;
        if (inWall) standTo(level, fp, at, floorY);
        else fp.setPos(at.getX() + 0.5, floorY + 1, at.getZ() + 1.5);
        ServerAvatarManager.tickAll();
        if (!driver.avatar().holdItem(Items.BUCKET)) return false;
        driver.avatar().aimAtBlock(inWall ? at.relative(Direction.SOUTH) : at);
        ServerAvatarManager.tickAll();
        driver.avatar().useItemInHand();
        ServerAvatarManager.tickAll();
        return WorldDriverProcessScenes.countItem(fp, expected) >= 1;
    }

    /** Put the held fluid into a cell carved in the wall, by standing on the ledge in front of it and
     *  aiming at the SOLID BACKING behind it: a bucket fills the neighbour of the face its ray lands
     *  on, so aiming into the air cell itself hits nothing and the fluid goes wherever the ray
     *  eventually stops — which is how an earlier version poured its water onto the floor. */
    private static boolean pourInto(ServerWorldDriver driver, ServerPlayer fp, BlockPos target, int floorY,
                                    net.minecraft.world.item.Item held, Block want, ServerLevel level) {
        standTo(level, fp, target, floorY);
        ServerAvatarManager.tickAll();
        if (!driver.avatar().holdItem(held)) return false;
        driver.avatar().aimAtBlock(target.relative(Direction.SOUTH));
        ServerAvatarManager.tickAll();
        driver.avatar().useItemInHand();
        ServerAvatarManager.tickAll();
        return level.getBlockState(target).getBlock() == want;
    }

    /**
     * Walk a server-driven body through a lit portal and out the other side, into the Nether.
     *
     * <p>The first question of ROADMAP N6, and it is asked here rather than on the ladder because
     * the rung that asks it in the field has just spent an hour of wall-clock casting ten obsidian
     * at the bottom of a shaft. A portal that lights and does not transit would invalidate that
     * whole rung, and it would do so silently: the body would stand in purple fog forever and the
     * budget would run out, which reads as a slow walk.
     *
     * <p><b>Why it was genuinely in doubt.</b> {@code JoinedPlayerBodies.JoinedBody} overrides
     * {@code tick()} to do <i>nothing</i> — deliberately, so vanilla does not integrate locomotion
     * a second time on top of {@code ServerPlayerAvatar.step()}. Vanilla's portal handling lives in
     * {@code Entity.baseTick()}, and whether that is reached depends entirely on the avatar's own
     * mirror of the tick. It is: {@code step()} calls {@code fp.baseTick()} first, and
     * {@code checkInsideBlocks()} rides {@code move()}. So the machinery is present — but "present"
     * and "works for a body with a connection that discards every packet it is given" are different
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
            ctx.skip("这个运行时没有下界维度（数据包移除了 minecraft:the_nether），没有可去的地方");
            return;
        }

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, x0 + 0.5, floorY + 1, cz + 2.5);
        var fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.FLINT_AND_STEEL, 1));
        fp.getInventory().selected = 0;

        BlockPos hearth = new BlockPos(x0, y0, cz);
        driver.avatar().holdItem(Items.FLINT_AND_STEEL);
        driver.avatar().aimAtBlock(hearth);
        ServerAvatarManager.tickAll();
        driver.avatar().useBlock(hearth, Direction.UP);
        ServerAvatarManager.tickAll();
        BlockPos doorway = hearth.above();
        ctx.record("portal.lit", String.valueOf(level.getBlockState(doorway).getBlock()));
        ctx.expect(level.getBlockState(doorway).getBlock() == Blocks.NETHER_PORTAL)
                .as("the frame lights before anything is asked about walking through it").isTrue();

        // Standing in it is what starts vanilla's portal timer; a player's is ~80 ticks, so the
        // budget below is generous by design — a run that spends it all has found a body the timer
        // never starts for, which is a different finding from a body it never fires for.
        driver.runProcess(new HoldStill(4_000));
        ServerAvatarManager.register(driver);
        fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);

        final int budget = 600;
        int ticked = 0;
        while (ticked < budget && fp.level() == level) { ServerAvatarManager.tickAll(); ticked++; }

        ctx.record("transit.ticks", ticked + (ticked >= budget ? "（用尽）" : ""));
        ctx.record("transit.dimension", fp.level().dimension().location().toString());
        ctx.record("transit.pos", fp.blockPosition().toShortString());
        ctx.record("transit.standingIn", String.valueOf(fp.level().getBlockState(fp.blockPosition()).getBlock()));
        // Two readings, because they want opposite fixes: a body that never entered the portal's
        // own block is a POSITIONING fault, and a body that stood in it for six hundred ticks
        // without moving is a TICK fault.
        ctx.record("transit.everInPortal",
                level.getBlockState(doorway).getBlock() == Blocks.NETHER_PORTAL ? "门还在" : "门没了");

        ctx.expect(fp.level().dimension()).as("the driven body arrives in the Nether through its own portal")
                .isEqualTo(Level.NETHER);

        // WHERE it landed, and this is not a detail. Vanilla scales the destination by the ratio of
        // the two dimensions' coordinate_scale — 8:1 — so an overworld portal at x=100001 belongs at
        // nether x≈12500. A body that arrives at the UNSCALED coordinate is in the Nether and is also
        // 87 000 blocks from the fortress the blaze rod rung will look for, and every rung above this
        // one would search the wrong world while this scene reported green.
        double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(
                level.dimensionType(), nether.dimensionType());
        BlockPos want = new BlockPos((int) Math.floor(cx * scale), fp.blockPosition().getY(),
                (int) Math.floor(cz * scale));
        int drift = Math.max(Math.abs(fp.blockPosition().getX() - want.getX()),
                Math.abs(fp.blockPosition().getZ() - want.getZ()));
        ctx.record("transit.scale", String.valueOf(scale));
        ctx.record("transit.expectedXZ", want.getX() + "," + want.getZ() + "（漂移 " + drift + " 格）");
        ctx.record("transit.arrivalPortal", String.valueOf(
                fp.level().getBlockState(fp.blockPosition()).getBlock()));
        ctx.record("transit.underfoot", String.valueOf(
                fp.level().getBlockState(fp.blockPosition().below()).getBlock()));
        // The dimension's own ceiling: a nether arrival above logical height is standing where the
        // roof is, which no portal search should ever return.
        ctx.record("transit.logicalHeight", nether.dimensionType().logicalHeight()
                + "（落点 y=" + fp.blockPosition().getY() + "）");
        ctx.expect(drift).as("the arrival is at the 8:1-scaled coordinate, not the raw one")
                .isAtMost(128);
        ctx.expect(fp.blockPosition().getY()).as("the arrival is under the Nether's own roof")
                .isAtMost(nether.dimensionType().logicalHeight());
        ctx.passNote("穿过自己点燃的传送门到达下界，用了 " + ticked + " tick，落在 "
                + fp.blockPosition().toShortString() + "（期望附近 " + want.getX() + "," + want.getZ() + "）");
    }

    /**
     * The whole of N5 end to end: a flat floor, a bucket, a flint-and-steel and a pile of
     * cobblestone go in; a <b>lit nether portal</b> comes out.
     *
     * <p>Each step is already proven on its own — {@code wd.serverCastsObsidian} the cast,
     * {@code wd.serverCastsAPortalFrame} the ten-from-one-bucket shuttle,
     * {@code wd.serverLightsPortal} the ignition. What none of them covers is the step the rung
     * actually spends its blocks on: those three all work a wall that was <b>staged</b>, and in the
     * field there is no two-thick wall waiting beside the lava. The body has to build the mould.
     *
     * <p><b>Placement is exact and reach-free, which is why this is affordable.</b>
     * {@code ServerPlayerAvatar.useBlock} constructs its own {@code BlockHitResult} from the cell
     * and face it is given rather than ray-tracing for one, and vanilla's distance check lives in
     * {@code ServerGamePacketListenerImpl.handleUseItemOn} — a packet this body never sends. So a
     * driven body can place a block in a named cell from wherever it is standing, and the mould is
     * bookkeeping rather than a navigation problem. The body is parked clear of the mould for the
     * whole build for the one reason that does still bite: a block cannot be placed into a cell the
     * placer is standing in ({@code isUnobstructed}).
     *
     * <p><b>Order is what makes every block placeable.</b> A free-standing wall has nothing to
     * place against, so the backing slab at {@code z=cz+1} goes up first, bottom-up, each block
     * supported by the one below and the lowest by the floor. Every solid cell of the front layer
     * is then placed against the backing behind it — {@code useBlock(backing, NORTH)} — which needs
     * no support of its own. Building the front layer first would strand every cell whose lower
     * neighbour is one of the sixteen that must stay air.
     *
     * <p>Staged: the floor, the lava lake, the reservoir, and the block the body stands on to pour
     * from. That is terrain and a pillar — the terrain the seed provides, and a climb
     * {@code ascendByTowering} owns on the ladder. Everything the rung must MAKE is made here.
     */
    private static void serverBuildsAndLightsAPortal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 7.5, floorY + 1, cz - 5.5);
        var fp = driver.fakePlayer();
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
                if (placeAt(driver, level, at, at.below(), Direction.UP)) placed++;
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
                if (placeAt(driver, level, at, at.relative(Direction.SOUTH), Direction.NORTH)) wall++;
                else ctx.record("wall.missed." + dx + "_" + dy,
                        String.valueOf(level.getBlockState(at).getBlock()));
            }
        ctx.record("mould.wall", wall + "/" + wallWanted);
        ctx.record("cobblestone.left", WorldDriverProcessScenes.countItem(fp, Items.COBBLESTONE) + "");
        ctx.expect(placed + wall).as("every block of the mould goes where it was named")
                .isEqualTo(wanted + wallWanted);

        // ---- 3. the ten casts, one bucket, exactly as wd.serverCastsAPortalFrame proves ----
        ctx.expect(scoopSource(driver, fp, well, floorY, Items.WATER_BUCKET))
                .as("the reservoir fills the bucket with water").isTrue();
        int cast = 0;
        for (int i = 0; i < plan.size(); i++) {
            BlockPos cell = plan.get(i)[0], wet = plan.get(i)[1];
            if (!pourInto(driver, fp, wet, floorY, Items.WATER_BUCKET, Blocks.WATER, level)) {
                ctx.record("water.stuckAt", label(cell, x0, y0)); break;
            }
            if (!scoopSource(driver, fp, lake.get(i), floorY, Items.LAVA_BUCKET)) {
                ctx.record("lava.stuckAt", label(cell, x0, y0)); break;
            }
            pourInto(driver, fp, cell, floorY, Items.LAVA_BUCKET, Blocks.OBSIDIAN, level);
            if (level.getBlockState(cell).getBlock() == Blocks.OBSIDIAN) cast++;
            else ctx.record("cast.missed." + label(cell, x0, y0),
                    String.valueOf(level.getBlockState(cell).getBlock()));
            scoopSource(driver, fp, wet, floorY, Items.WATER_BUCKET);
        }
        ctx.record("frame.cast", cast + "/" + plan.size());
        ctx.expect(cast).as("ten obsidian cast into a mould the body built itself")
                .isEqualTo(plan.size());

        // ---- 4. light it ----
        BlockPos hearth = new BlockPos(x0, y0, cz);
        standTo(level, fp, hearth, floorY);
        ServerAvatarManager.tickAll();
        ctx.expect(driver.avatar().holdItem(Items.FLINT_AND_STEEL)).as("flint-and-steel in hand").isTrue();
        driver.avatar().aimAtBlock(hearth);
        ServerAvatarManager.tickAll();
        driver.avatar().useBlock(hearth, Direction.UP);
        ServerAvatarManager.tickAll();

        int lit = 0;
        for (BlockPos c : interior)
            if (level.getBlockState(c).getBlock() == Blocks.NETHER_PORTAL) lit++;
        ctx.record("portal.cells", lit + "/" + interior.size());
        ctx.record("interior.after", String.valueOf(level.getBlockState(interior.get(0)).getBlock()));
        ctx.expect(lit).as("the portal the body built and cast is lit end to end")
                .isEqualTo(interior.size());
        ctx.passNote("平地起门: 铺 " + (wanted + wallWanted) + " 块模具, 一只桶浇 " + cast
                + " 块黑曜石, 点亮 " + lit + " 格");
    }

    /** Place a held cobblestone in {@code target} by clicking {@code face} of {@code support}.
     *  No aim and no walk: {@code useBlock} builds its own hit result, and the reach check the
     *  server applies lives on a packet path this body never uses. */
    private static boolean placeAt(ServerWorldDriver driver, ServerLevel level, BlockPos target,
                                   BlockPos support, Direction face) {
        if (!driver.avatar().holdItem(Items.COBBLESTONE)) return false;
        driver.avatar().useBlock(support, face);
        ServerAvatarManager.tickAll();
        return !level.getBlockState(target).isAir();
    }

    /**
     * Set twelve eyes into a stronghold's frame and step through into the End.
     *
     * <p>Two verbs, both on the critical path and neither exercised anywhere else. Inserting an eye
     * is {@code EnderEyeItem.useOn} — the same {@code useOn}-only shape as the flint-and-steel, so a
     * body that reaches for {@code useItemInHand} gets {@code PASS} and a frame that never fills.
     * Stepping through is {@code EndPortalBlock}, which reaches {@code changeDimension} by a
     * different road than the Nether's and lands on a fixed point rather than a searched one.
     *
     * <p><b>Why this is worth its own scene given the Nether already passes.</b> The teleport that
     * was swallowed is delivered the same way here, but the destination is
     * {@code ServerLevel.END_SPAWN_POINT} rather than a scaled coordinate, so a body that arrived
     * "somewhere in the End" would look correct on a dimension check while standing in the void
     * beside the island. The assertion is on the platform.
     *
     * <p>Staged: the frame and the twelve eyes. Where eyes come from is
     * {@code ENDER_PEARL}/{@code EYE_OF_ENDER}'s question and finding the stronghold is
     * {@code STRONGHOLD}'s; what this owns is that a driven body can spend them and survive the
     * crossing.
     */
    private static void serverOpensTheEndPortal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        ServerLevel end = level.getServer().getLevel(Level.END);
        if (end == null) {
            ctx.skip("这个运行时没有末地维度（数据包移除了 minecraft:the_end），没有可去的地方");
            return;
        }

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 3.5);
        var fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.ENDER_EYE, 12));
        fp.getInventory().selected = 0;

        int set = 0;
        for (BlockPos at : frames) {
            if (!driver.avatar().holdItem(Items.ENDER_EYE)) { ctx.record("eyes.ranOutAt", at.toShortString()); break; }
            driver.avatar().useBlock(at, Direction.UP);
            ServerAvatarManager.tickAll();
            if (level.getBlockState(at).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)) set++;
            else ctx.record("eye.missed." + at.toShortString(), String.valueOf(level.getBlockState(at)));
        }
        ctx.record("eyes.set", set + "/" + frames.size());
        ctx.record("eyes.left", WorldDriverProcessScenes.countItem(fp, Items.ENDER_EYE) + "");
        ctx.expect(set).as("all twelve eyes go into the frame from a driven body's hand")
                .isEqualTo(frames.size());

        BlockPos doorway = new BlockPos(cx, y, cz);
        ctx.record("portal.formed", String.valueOf(level.getBlockState(doorway).getBlock()));
        ctx.expect(level.getBlockState(doorway).getBlock() == Blocks.END_PORTAL)
                .as("the twelfth eye opens the portal").isTrue();

        driver.runProcess(new HoldStill(4_000));
        ServerAvatarManager.register(driver);
        fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);

        final int budget = 400;
        int ticked = 0;
        while (ticked < budget && fp.level() == level) { ServerAvatarManager.tickAll(); ticked++; }
        ctx.record("transit.ticks", ticked + (ticked >= budget ? "（用尽）" : ""));
        ctx.record("transit.dimension", fp.level().dimension().location().toString());
        ctx.record("transit.pos", fp.blockPosition().toShortString());
        ctx.expect(fp.level().dimension()).as("the driven body crosses into the End").isEqualTo(Level.END);

        // The platform, not merely the dimension. END_SPAWN_POINT is fixed, so "somewhere in the
        // End" and "on the obsidian island vanilla builds for arrivals" are different claims and
        // only the second one can fight a dragon.
        BlockPos want = net.minecraft.server.level.ServerLevel.END_SPAWN_POINT;
        int drift = Math.max(Math.abs(fp.blockPosition().getX() - want.getX()),
                Math.abs(fp.blockPosition().getZ() - want.getZ()));
        ctx.record("transit.spawnPoint", want.toShortString() + "（漂移 " + drift + " 格）");
        ctx.record("transit.underfoot", String.valueOf(
                fp.level().getBlockState(fp.blockPosition().below()).getBlock()));
        ctx.expect(drift).as("the arrival is on the End's own spawn platform").isAtMost(16);
        ctx.passNote("十二只眼开门, " + ticked + " tick 过到末地, 落在 "
                + fp.blockPosition().toShortString());
    }

    /** An end-portal frame at {@code at} facing {@code towards}, returned for the caller's list. */
    private static BlockPos place(ServerLevel level, BlockPos at, Direction towards) {
        level.setBlockAndUpdate(at, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING, towards));
        return at;
    }

    /**
     * Light a nether portal with a flint-and-steel, on a server-side body.
     *
     * <p>The capability probe for ROADMAP N5, and the frame here is <b>staged on purpose</b>. This
     * arena is not asking whether the ladder can cast ten obsidian — {@code wd.serverCastsObsidian}
     * owns one cast and the journey rung owns the other nine. It is asking the one question that
     * sits between a finished frame and a lit portal, and that question is worth its own 200ms
     * because the rung that asks it in the field does so at the bottom of a 36-block shaft.
     *
     * <p><b>The verb is the opposite of the bucket's, and getting it wrong looks identical.</b>
     * {@code BucketItem} has no {@code useOn}, so a bucket must go through {@code Item.use} —
     * driver-side {@code useItemInHand}. {@code FlintAndSteelItem} is the mirror image: it overrides
     * <b>{@code useOn(UseOnContext)}</b> and has no {@code use} at all, so it must go through
     * {@code useBlock(cell, face)}. Called the other way it returns {@code Item.use}'s default
     * {@code PASS} and the world does not move — the same silent nothing a pickaxe gives, which
     * already cost this ladder a run.
     *
     * <p><b>Where the fire lands is a parameter, not a detail.</b> Reading the item: when the clicked
     * block is not itself ignitable — obsidian is not — vanilla puts the fire at
     * {@code clickedPos.relative(clickedFace)}. So the click has to be on a FRAME block with the face
     * pointing INTO the interior, and clicking the interior's floor with face UP is the natural way
     * to say that. {@code ServerPlayerAvatar.useBlock} builds its {@code BlockHitResult} from that
     * face, so the parameter really does reach vanilla.
     */
    private static void serverLightsPortal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 5; dx++)
                for (int dy = -1; dy <= 7; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        for (int dx = -4; dx <= 5; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        // A minimal frame: interior 2 wide × 3 tall, so ten obsidian with the corners left out —
        // which is what the ladder can afford and therefore what this must prove lights.
        final int x0 = cx + 1, y0 = floorY + 1;
        List<BlockPos> frame = new ArrayList<>();
        frame.add(new BlockPos(x0, y0, cz));           frame.add(new BlockPos(x0 + 1, y0, cz));
        frame.add(new BlockPos(x0, y0 + 4, cz));       frame.add(new BlockPos(x0 + 1, y0 + 4, cz));
        for (int dy = 1; dy <= 3; dy++) {
            frame.add(new BlockPos(x0 - 1, y0 + dy, cz));
            frame.add(new BlockPos(x0 + 2, y0 + dy, cz));
        }
        for (BlockPos p : frame) level.setBlockAndUpdate(p, Blocks.OBSIDIAN.defaultBlockState());
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 1; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y0 + dy, cz), Blocks.AIR.defaultBlockState());
        ctx.record("frame.blocks", frame.size() + " obsidian");

        ServerWorldDriver driver = SceneBody.mint(ctx, level, x0 + 0.5, floorY + 1, cz + 2.5);
        var fp = driver.fakePlayer();
        // Pickaxe selected, flint-and-steel behind it — the arena starts the way the rung arrives.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
        fp.getInventory().items.set(1, new ItemStack(Items.FLINT_AND_STEEL, 1));
        fp.getInventory().selected = 0;

        BlockPos hearth = new BlockPos(x0, y0, cz);      // a frame block; the fire goes above it
        BlockPos firstInterior = hearth.above();

        ctx.expect(driver.avatar().holdItem(Items.FLINT_AND_STEEL))
                .as("the flint-and-steel can be brought to the main hand from the bag").isTrue();
        driver.avatar().aimAtBlock(hearth);
        ServerAvatarManager.tickAll();
        ctx.record("light.hand", String.valueOf(fp.getMainHandItem().getItem()));
        ctx.record("light.clicked", hearth.toShortString() + " face=UP");

        driver.avatar().useBlock(hearth, Direction.UP);
        for (int t = 0; t < 20 && level.getBlockState(firstInterior).getBlock() != Blocks.NETHER_PORTAL; t++) {
            ServerAvatarManager.tickAll();
        }
        ctx.record("light.cellAfter", String.valueOf(level.getBlockState(firstInterior).getBlock()));
        ctx.record("flintAndSteel.after", WorldDriverProcessScenes.countItem(fp, Items.FLINT_AND_STEEL) + " (durability spent, not the item)");

        // Every interior cell, not just the one that was lit: a portal is the whole 2×3, and a fire
        // that burned in one cell without becoming a portal is a different outcome from a portal —
        // both leave "something happened" at the click site.
        int litCells = 0;
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 1; dy <= 3; dy++)
                if (level.getBlockState(new BlockPos(x0 + dx, y0 + dy, cz)).getBlock() == Blocks.NETHER_PORTAL)
                    litCells++;
        ctx.record("portal.cells", litCells + "/6");
        ctx.expect(litCells).as("nether portal blocks filling the frame's interior").isEqualTo(6);
    }

    /** The block a use would hit, clipped the way {@code Item.getPlayerPOVHitResult} clips it —
     *  from {@code getXRot()}/{@code getYRot()}, not from the head rotation {@code Entity.pick}
     *  reads and {@code Avatar.aimAtBlock} never sets. */
    private static net.minecraft.world.phys.BlockHitResult aimedAt(
            net.minecraft.server.level.ServerPlayer fp, double range, boolean hitFluids) {
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        net.minecraft.world.phys.Vec3 look =
                net.minecraft.world.phys.Vec3.directionFromRotation(fp.getXRot(), fp.getYRot());
        return fp.level().clip(new net.minecraft.world.level.ClipContext(eye,
                eye.add(look.scale(range)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                hitFluids ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY
                          : net.minecraft.world.level.ClipContext.Fluid.NONE, fp));
    }
    /** Every cell of the arena holding a block — for saying where a fluid went when it did not go
     *  where it was aimed. "CONSUME and the target is empty" and "the use was refused" are different
     *  bugs, and only this tells them apart. */
    private static String whereIs(ServerLevel level, int cx, int floorY, int cz,
                                  net.minecraft.world.level.block.Block want) {
        StringBuilder sb = new StringBuilder();
        for (int dx = -4; dx <= 5; dx++)
            for (int dy = -1; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos at = new BlockPos(cx + dx, floorY + dy, cz + dz);
                    if (level.getBlockState(at).getBlock() != want) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(at.toShortString());
                }
        return sb.length() == 0 ? "nowhere in the arena" : sb.toString();
    }
}
