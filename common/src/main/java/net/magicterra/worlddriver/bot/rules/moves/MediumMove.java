package net.magicterra.worlddriver.bot.rules.moves;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;

/**
 * One stroke to a neighbouring cell for a body that moves freely through water or air and never
 * leaves it: a fish, a dolphin, a guardian, a squid; a parrot, a bee, a ghast, a phantom. Every cell
 * the body's box covers on the way must be of the medium, so a diagonal never cuts a corner and the
 * route never touches the other medium or anything with a collision shape.
 *
 * <p>A node is the cell the body's feet are in, with the body centred across it and its feet
 * {@link #feetLift lifted} a little off the cell's floor; the box then covers {@link #cellsBeside} cells
 * to each side and {@link #cellsAbove} above. A body that fits one cell checks only the cells it moves
 * into. The cells the body is in when it starts are not checked: a start grazing a wall, or a flyer
 * that fell into water, still moves out.
 *
 * <p>Not part of {@link Move#ALL}: the walking body's swim moves are built around a player who floats
 * at the surface and climbs out, and this family is for a body that does neither.
 */
public final class MediumMove extends Move {

    /** What a {@link MediumMove} moves through. */
    public enum Medium {
        /** Water that blocks no motion: not air, and not a waterlogged block with a shape. */
        WATER("waterSwim", Locomotion.Mode.SWIM),
        /** Loaded air, or a block with no collision, that is not water and does not hurt. */
        AIR("airFly", Locomotion.Mode.FLY);

        private final String move;
        private final Locomotion.Mode mode;

        Medium(String move, Locomotion.Mode mode) {
            this.move = move;
            this.mode = mode;
        }

        /** Whether {@code p} is this medium. */
        public boolean holds(WorldView w, BlockPos p) {
            return switch (this) {
                case WATER -> w.isWater(p) && !w.isSolid(p);
                case AIR -> w.isKnown(p) && w.isPassable(p) && !w.isWater(p) && !w.isHazard(p);
            };
        }
    }

    /** Every stroke through water, to each of the 26 neighbours. */
    public static final List<Move> SWIM = build(Medium.WATER);
    /** Every stroke through air, to each of the 26 neighbours. */
    public static final List<Move> FLY = build(Medium.AIR);

    /** The lowest a body's feet are kept above its node's floor. */
    private static final double MIN_FEET_LIFT = 0.1;
    private static final double EPS = 1e-6;

    private final Medium medium;

    private MediumMove(Medium medium, int dx, int dy, int dz) {
        super(dx, dy, dz, cost(dx, dy, dz));
        this.medium = medium;
    }

    /** Ten per block of straight-line length, as a walk is priced. */
    private static int cost(int dx, int dy, int dz) {
        return (int) Math.round(10 * Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /**
     * How far above its node's floor a body {@code height} tall keeps its feet: centred in the cell when
     * it is shorter than one, else a little off the floor, so its feet stay in the node's cell while it
     * bobs.
     */
    public static double feetLift(double height) {
        return Math.max(MIN_FEET_LIFT, (1 - height) / 2);
    }

    /** Cells a body {@code width} wide covers on each side of its node's, centred on it. */
    public static int cellsBeside(double width) {
        return (int) Math.floor(0.5 + width / 2 - EPS);
    }

    /** Cells a body {@code height} tall covers above its node's, its feet at {@link #feetLift}. */
    public static int cellsAbove(double height) {
        return (int) Math.floor(feetLift(height) + height - EPS);
    }

    @Override public boolean valid(WorldView w, BlockPos from) {
        int side = cellsBeside(w.bodyWidth()), up = cellsAbove(w.bodyHeight());
        // The box at its new node and every corner on the way, less where it already is.
        for (int x = -side + Math.min(0, dx); x <= side + Math.max(0, dx); x++)
            for (int y = Math.min(0, dy); y <= up + Math.max(0, dy); y++)
                for (int z = -side + Math.min(0, dz); z <= side + Math.max(0, dz); z++) {
                    if (Math.abs(x) <= side && y >= 0 && y <= up && Math.abs(z) <= side) continue;
                    if (!medium.holds(w, from.offset(x, y, z))) return false;
                }
        return true;
    }

    @Override public String name() { return medium.move; }

    @Override public Locomotion.Mode mode() { return medium.mode; }

    private static List<Move> build(Medium medium) {
        List<Move> out = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if ((dx | dy | dz) != 0) out.add(new MediumMove(medium, dx, dy, dz));
        return List.copyOf(out);
    }
}
