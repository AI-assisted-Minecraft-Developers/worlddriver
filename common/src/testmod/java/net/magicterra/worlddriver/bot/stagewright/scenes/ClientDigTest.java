package net.magicterra.worlddriver.bot.stagewright.scenes;

import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.twoDecimals;

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
 * Walks the client's REAL player has to dig its way through, and what happens to the cells it dug. How fast it tunnels
 * is the benchmark's to measure (the dig-and-build and ore-block courses); what stays here is the backfill contract no
 * course exercises.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientDigTest {

    private static final String COMMA = ",";

    private static final String SEMICOLON_SPACE = "; ";

    private static final int GROUND = 0;

    private static final int LEG_TICKS = 2_400;

    private static final int IDLE_TICKS = 600;

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
        final BlockPos goal = plot.at(2, GROUND + 1, 0);
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
            List<String> filled = new ArrayList<>();
            List<String> mouthFilled = new ArrayList<>();
            List<String> mouthOpen = new ArrayList<>();
            List<String> tunnelFilled = new ArrayList<>();
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
                            tunnelFilled.add("(" + dx + COMMA + dy + COMMA + dz + ")");
                        }
                        if (dx != -1 || dy < 1) {
                            continue;
                        }
                        if (cobble) {
                            mouthFilled.add("(" + dy + COMMA + dz + ")");
                        } else if (level.getBlockState(cell).isAir()) {
                            mouthOpen.add("(" + dy + COMMA + dz + ")");
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
                        + twoDecimals(closest[0]) + SEMICOLON_SPACE + after);
        game.check(
                room.mouthOpen().isEmpty() && room.mouthFilled().size() >= 2,
                "B: both cells of the tunnel mouth the "
                        + "player dug are filled back in and the room's west wall has no opening: wall cells (dy,dz) filled "
                        + room.mouthFilled() + ", open " + room.mouthOpen() + "; tunnel cells (dx,dy,dz) filled "
                        + room.tunnelFilled() + SEMICOLON_SPACE + after);
        game.check(
                room.filled().isEmpty(),
                "C: no cell of the natural air room is filled, including the ones the "
                        + "player walked through: filled " + room.filled() + SEMICOLON_SPACE + after);
        game.check(
                !builder.active(),
                "D: the backfill process has ended by the end of the idle window instead of "
                        + "alternating between filling and digging: " + after);
    }
}
