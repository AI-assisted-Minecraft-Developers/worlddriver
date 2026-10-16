package net.magicterra.worlddriver.bot.rules.moves;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;

/**
 * One swim stroke to a neighbouring cell for a body that lives in water and never leaves it: a fish,
 * a dolphin, a guardian, a squid. Every cell of the box the stroke crosses must be water that blocks
 * no motion, so a diagonal never cuts a corner and the route never touches air or a waterlogged step.
 *
 * <p>Not part of {@link Move#ALL}: the walking body's swim moves are built around a player who floats
 * at the surface and climbs out, and this family is for a body that does neither. Sized for a body
 * that fits one cell; a wider one (an elder guardian) may graze the walls of a one-wide channel.
 */
public final class WaterSwim extends Move {

    /** Every stroke, to each of the 26 neighbours. */
    public static final List<Move> ALL = build();

    private WaterSwim(int dx, int dy, int dz) {
        super(dx, dy, dz, cost(dx, dy, dz));
    }

    /** Ten per block of straight-line length, as a walk is priced. */
    private static int cost(int dx, int dy, int dz) {
        return (int) Math.round(10 * Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    @Override public boolean valid(WorldView w, BlockPos from) {
        if (!swimmable(w, from)) return false;
        for (int x = Math.min(0, dx); x <= Math.max(0, dx); x++)
            for (int y = Math.min(0, dy); y <= Math.max(0, dy); y++)
                for (int z = Math.min(0, dz); z <= Math.max(0, dz); z++)
                    if ((x | y | z) != 0 && !swimmable(w, from.offset(x, y, z))) return false;
        return true;
    }

    /** Water that a body can move through: not air, and not a waterlogged block with a shape. */
    public static boolean swimmable(WorldView w, BlockPos p) {
        return w.isWater(p) && !w.isSolid(p);
    }

    @Override public String name() { return "waterSwim"; }

    @Override public Locomotion.Mode mode() { return Locomotion.Mode.SWIM; }

    private static List<Move> build() {
        List<Move> out = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if ((dx | dy | dz) != 0) out.add(new WaterSwim(dx, dy, dz));
        return List.copyOf(out);
    }
}
