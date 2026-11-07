package net.magicterra.worlddriver.bot.rules.moves;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;

/**
 * Break the block directly ABOVE and rise into it — the vertical escape from a capped underwater pocket (a dug shaft
 * that flooded and sealed, or any water column roofed by solid blocks where autoSwim can't reach air). From a water
 * cell, mine the ceiling cell {@code from+(0,1,0)} (and the one above it for head clearance) so the bot can swim up
 * toward the surface.
 *
 * <p>The horizontal twins {@link SwimAshoreBreak}/{@link SwimTraverseBreak} need a solid bank to climb onto; a
 * fully-capped pocket has none, so without this a roofed bot drowns. Same gating: {@link WorldView#escapeBreakCost}
 * (default-ON {@code allowSwimEscapeBreak}, confined to the local escape bubble) and only from a
 * {@link Move#waterEscapeContext}. Must break ≥1 cell or {@link SwimUp} handles it.
 */
public final class SwimUpBreak extends Move {
    /** Swims up one cell and breaks the block in the way. */
    public SwimUpBreak() {
        super(0, 1, 0, 24);
    }

    @Override
    public boolean valid(WorldView w, BlockPos from) {
        return eval(w, from) != null;
    }

    @Override
    public Edge eval(WorldView w, BlockPos from) {
        if (!waterEscapeContext(w, from)) {
            return null;
        }
        if (!w.isWater(from)) {
            return null; // must launch from water
        }
        BlockPos to = apply(from); // ceiling cell we rise into
        if (RulesConfig.pathfinderSwimUpBreakStaysInWater && w.surfaceWaterNodes() && !staysWater(w, to)) {
            // Rising out of the top water cell into air is the swimUp the surface-node model forbids: the body bobs
            // under that cell and has no floor there to jump from, so a stairUpBreak or stepUp planned from it is a
            // climb the body cannot make. On the dig-ashore benchmark course, swimUpBreak into 1048,150,1200 and a
            // stairUpBreak onto 1049,151 peaked at y150.65 against the bank, a third of a block short.
            return null;
        }
        BlockPos head = to.offset(0, 1, 0); // clearance above the new foot
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        if (w.isSolid(to)) {
            double c = w.escapeBreakCost(to);
            if (Double.isInfinite(c)) {
                return null;
            }
            bc += c;
            br.add(to);
        } else if (w.isHazard(to)) {
            return null;
        }
        if (w.isSolid(head)) {
            double c = w.escapeBreakCost(head);
            if (Double.isInfinite(c)) {
                return null;
            }
            bc += c;
            br.add(head);
        } else if (w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) {
            return null; // ceiling already clear → SwimUp is cheaper
        }
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    /** True when {@code cell} is water, or water above or beside it will fill it once it is open. */
    private static boolean staysWater(WorldView w, BlockPos cell) {
        return w.isWater(cell)
                || w.isWater(cell.above())
                || w.isWater(cell.east())
                || w.isWater(cell.west())
                || w.isWater(cell.north())
                || w.isWater(cell.south());
    }

    @Override
    public String name() {
        return "swimUpBreak";
    }

    @Override
    public Locomotion.Mode mode() {
        return Locomotion.Mode.SWIM;
    }
}
