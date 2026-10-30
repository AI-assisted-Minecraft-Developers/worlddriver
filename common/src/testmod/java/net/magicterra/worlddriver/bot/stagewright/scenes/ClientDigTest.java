package net.magicterra.worlddriver.bot.stagewright.scenes;

import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.at;
import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.Slot;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Walks the client's REAL player has to dig its way through. What these measure that a surface walk cannot: every node
 * the search expands inside rock is priced through {@code breakCost}, so the planner's per-node cost, the slice cadence
 * and the segment length all change shape the moment the player is enclosed.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientDigTest {

    private static final int GROUND = 0;
    /** Levels from the tunnel's foot row ({@code GROUND + 1}) to standing on the slab ({@code GROUND + 9}). */
    private static final int SLAB_OVER_FOOT = 8;

    private static final int LEG_TICKS = 2_400;
    private static final int SETTLE_TICKS = 10;
    private static final int IDLE_TICKS = 600;

    /**
     * The player stands in a two-high pocket inside solid stone with a survival bag and an iron pickaxe in hand; the
     * goal is ten cells away in the same rock, and every cell between is stone. A traverse is two iron-pickaxe stone
     * digs (about eight ticks a block plus vanilla's five-tick destroy delay) and a step, near thirty ticks: ten cells
     * are some 300 ticks of physics, and the budget is that plus one search, not a planner that freezes the player for
     * seconds at every segment end.
     */
    @Test
    void clientTunnelsThroughStone(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        tunnel(game, client, player, plot, 10, 12, 450);
    }

    /**
     * The same rock forty cells long, the slab eight blocks thick over the goal: past the search horizon, so the walk
     * is several best-effort segments, and the planner climbs out, walks the top and digs back down. Measured at 680
     * ticks once the stale-plan cut, the stacked-node pointer hold and the full-cube fast path were in; before them it
     * did not arrive in 1500. That route digs 27 blocks when walked cleanly (430 ticks), so the dig floor is the
     * cheaper route's: eight levels out and eight back down.
     */
    @Test
    void clientTunnelsFarThroughStone(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 64, y = 24, z = 64) Plot plot) {
        tunnel(game, client, player, plot, 40, 26, 900);
    }

    private static void tunnel(Game game, Client client, Player player, Plot plot, int cells, int half, int arriveBy) {
        plot.fill(-half, GROUND - 6, -half, half, GROUND + 8, half, Blocks.STONE);
        int sx = -cells / 2 + 1;
        plot.fill(sx, GROUND + 1, 0, sx, GROUND + 2, 0, Blocks.AIR);
        BlockPos start = plot.at(sx, GROUND + 1, 0);
        BlockPos goal = plot.at(sx + cells, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        // A survival bag, not a lone pickaxe: every tool in it is a tag scan per breakCost ask, and a bag with one
        // stack measures nothing about the pricing a real journey pays.
        rig.hold(
                new ItemStack(Items.IRON_PICKAXE),
                new ItemStack(Items.STONE_PICKAXE),
                new ItemStack(Items.IRON_AXE),
                new ItemStack(Items.STONE_AXE),
                new ItemStack(Items.IRON_SHOVEL),
                new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.WOODEN_HOE),
                new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.BREAD, 12));
        player.runOnServer(p -> {
            for (int slot = 9; slot < 33; slot++) {
                p.getInventory().items.set(slot, new ItemStack(slot % 2 == 0 ? Items.OAK_LOG : Items.DIRT, 64));
            }
            p.containerMenu.broadcastChanges();
        });
        rig.settings()
                .set(Map.of(
                        "allowBreak",
                        true,
                        "allowPlace",
                        false,
                        "navigatorDebug",
                        true,
                        "navigatorFinalNodeDirectAim",
                        true,
                        "navigatorHoldLastNodeUntilStanding",
                        true,
                        "navigatorOrbitBreaksAimLag",
                        true,
                        "navigatorSurfaceSprintSwim",
                        true));
        rig.sync(30);

        ClientWalk walk = new ClientWalk(rig);
        int[] arrived = {-1};
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            if (arrived[0] < 0 && s.onGround() && s.flat(goal) <= 1.0) {
                arrived[0] = walk.tick(s);
            }
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        int cobble = ClientWalk.count(rig, Items.COBBLESTONE);
        int minDug = Math.min(cells, 2 * SLAB_OVER_FOOT);
        String progress =
                "reached the goal at tick " + at(arrived[0]) + ", cobblestone mined " + cobble + "; " + rig.where();
        game.check(
                end.flat(goal) <= 1.5,
                "A: the player stops within 1.5 blocks of the goal cell: horizontal distance " + f(end.flat(goal))
                        + "; " + progress);
        game.check(
                arrived[0] >= 0 && arrived[0] <= arriveBy,
                "B: the player arrives within " + arriveBy + " ticks: " + progress);
        game.check(cobble >= minDug, "C: the player dug at least " + minDug + " blocks: " + progress);
    }

    /**
     * {@code autoBackfill} puts back what the player broke and nothing else. The player digs a five-cell tunnel out of
     * a sealed stone pocket into a natural 3×3×2 room and walks across the room to its far wall, then idles. The
     * backfill must plug the tunnel's mouth — the two dug cells it can stand beside without digging — and leave every
     * room cell air, although the player walked through three of them: a tracker fed the foot cell every tick filled
     * those first, being nearest. The cells deeper in the tunnel are reachable only by digging through the fill, so
     * they are given up, and the process must have ended by the close rather than trading the same cells back and
     * forth.
     */
    @Test
    void clientBackfillRefillsOwnDigs(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        plot.fill(-10, GROUND - 6, -10, 10, GROUND + 8, 10, Blocks.STONE);
        plot.fill(-6, GROUND + 1, 0, -6, GROUND + 2, 0, Blocks.AIR);
        plot.fill(0, GROUND + 1, -1, 2, GROUND + 2, 1, Blocks.AIR);
        BlockPos start = plot.at(-6, GROUND + 1, 0);
        BlockPos goal = plot.at(2, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        // Cobblestone from the start: this test is about which cells get filled, not about pickup.
        rig.hold(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COBBLESTONE, 64));
        rig.settings()
                .set(Map.of(
                        "allowBreak",
                        true,
                        "allowPlace",
                        false,
                        "autoBackfill",
                        true,
                        "autoBackfillBlock",
                        "minecraft:cobblestone"));
        rig.sync(30);

        // The closest approach over the whole walk, not the position when the end is noticed: the walk's end reaches
        // the test a few ticks late from the joined client, and by then autoBackfill has already walked the player
        // back toward its first hole.
        double[] closest = {Double.MAX_VALUE};
        new ClientWalk(rig).go(Targets.block(goal), LEG_TICKS, 0, s -> closest[0] = Math.min(closest[0], s.flat(goal)));
        rig.sync(IDLE_TICKS);
        Slot builder = rig.status().slots().builder();

        // Which tunnel the player dug is the planner's choice among equal-cost rows, so the mouth is found, not
        // assumed:
        // the cells of the stone wall west of the room (x = -1) that are cobblestone were dug and filled back.
        record Room(List<String> mouthFilled, List<String> mouthOpen, List<String> tunnelFilled, List<String> filled) {}
        Room room = game.onServer(s -> {
            var level = s.overworld();
            List<String> filled = new ArrayList<>(),
                    mouthFilled = new ArrayList<>(),
                    mouthOpen = new ArrayList<>(),
                    tunnelFilled = new ArrayList<>();
            for (int dy = 1; dy <= 2; dy++) {
                for (int dx = 0; dx <= 2; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos cell = plot.at(dx, GROUND + dy, dz);
                        if (!level.getBlockState(cell).isAir()) {
                            filled.add(cell.toShortString());
                        }
                    }
                }
            }
            for (int dy = 0; dy <= 3; dy++) {
                for (int dx = -5; dx <= -1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos cell = plot.at(dx, GROUND + dy, dz);
                        boolean cobble = level.getBlockState(cell).is(Blocks.COBBLESTONE);
                        if (cobble) {
                            tunnelFilled.add("(" + dx + "," + dy + "," + dz + ")");
                        }
                        if (dx != -1 || dy < 1) {
                            continue;
                        }
                        if (cobble) {
                            mouthFilled.add("(" + dy + "," + dz + ")");
                        } else if (level.getBlockState(cell).isAir()) {
                            mouthOpen.add("(" + dy + "," + dz + ")");
                        }
                    }
                }
            }
            return new Room(mouthFilled, mouthOpen, tunnelFilled, filled);
        });
        String after = rig.where() + "; builder=" + builder;
        game.check(
                closest[0] <= 1.5,
                "A: during the goto the player reached the far side of the room: closest " + "horizontal distance "
                        + f(closest[0]) + "; " + after);
        game.check(
                room.mouthOpen().isEmpty() && room.mouthFilled().size() >= 2,
                "B: both cells of the tunnel mouth the "
                        + "player dug are filled back in and the room's west wall has no opening: wall cells (dy,dz) filled "
                        + room.mouthFilled() + ", open " + room.mouthOpen() + "; tunnel cells (dx,dy,dz) filled "
                        + room.tunnelFilled() + "; " + after);
        game.check(
                room.filled().isEmpty(),
                "C: no cell of the natural air room is filled, including the ones the "
                        + "player walked through: filled " + room.filled() + "; " + after);
        game.check(
                !builder.active(),
                "D: the backfill process has ended by the end of the idle window instead of "
                        + "alternating between filling and digging: " + after);
    }
}
