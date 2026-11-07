package net.magicterra.worlddriver.bot.rules.moves;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;

/**
 * Climb +1 cardinally out of water onto a bank, mining whatever obstructs the step — the "get ashore over a high bank"
 * move. From a water cell, the bot jumps onto the block at {@code from+(dx,0,dz)} (the bank face it climbs onto) after
 * breaking the destination foot {@code from+(dx,1,dz)} and head {@code from+(dx,2,dz)} cells if they're solid. Chained,
 * it digs a staircase up and out of a flooded pit or a 2-3-high lake/ocean bank.
 *
 * <p>Distinct from {@link StepUp} (no breaking) and {@link TraverseBreak} (same-Y, gated on {@code allowBreak}): this
 * one is gated on the water-escape break ({@link WorldView#escapeBreakCost}, default ON) and fires ONLY from a
 * {@link Move#waterEscapeContext}, so it never tunnels on dry land. It must break at least one cell, otherwise a plain
 * {@link StepUp} is cheaper and A* uses that instead.
 */
public final class SwimAshoreBreak extends Move {
    /**
     * Base a touch above StepUp's 15: swimming/climbing out of water is slower than a dry hop, so A* prefers a real
     * walkable exit when one exists.
     */
    public SwimAshoreBreak(int dx, int dz) {
        super(dx, 1, dz, 22);
    }

    @Override
    public boolean valid(WorldView w, BlockPos from) {
        return eval(w, from) != null;
    }

    @Override
    public Edge eval(WorldView w, BlockPos from) {
        if (!waterEscapeContext(w, from)) {
            return null; // only as a water escape
        }
        if (w.isSubmergedFoot(from)) {
            return null; // must start within 1 of the surface to jump-mount (WorldView#isSubmergedFoot)
        }
        BlockPos to = apply(from); // from + (dx, 1, dz)
        BlockPos floor = to.offset(0, -1, 0); // = from + (dx, 0, dz): the bank we land on
        if (!w.isSolid(floor) || w.isHazard(floor)) {
            return null; // need a solid bank to stand on (we don't place)
        }
        List<BlockPos> br = new ArrayList<>(3);
        double bc = headRoom(w, from, br);
        if (Double.isInfinite(bc)) {
            return null;
        }
        // Destination foot cell — break it if it's a solid bank block.
        if (w.isSolid(to)) {
            double c = w.escapeBreakCost(to);
            if (Double.isInfinite(c)) {
                return null;
            }
            bc += c;
            br.add(to);
        } else if (!w.isPassable(to) || w.isHazard(to)) {
            return null; // lava/hazard we won't dig into
        }
        // Destination head cell — clearance for the 2-tall hitbox at the new level.
        BlockPos head = to.offset(0, 1, 0);
        if (w.isSolid(head)) {
            double c = w.escapeBreakCost(head);
            if (Double.isInfinite(c)) {
                return null;
            }
            bc += c;
            br.add(head);
        } else if (!w.isPassable(head) || w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) {
            return null; // nothing blocked → StepUp is cheaper
        }
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    /**
     * The price of opening the cell two above {@code from}, added to {@code br} when it is solid: the body rises in
     * its own column until its feet clear the bank, so its head reaches that cell before it moves over. Under the
     * ceiling of the dig-ashore benchmark course (1048,151,1200) it pressed against the bank at y149.2 for two seconds
     * with both bank cells already dug. +INF when the cell cannot be opened.
     */
    static double headRoom(WorldView w, BlockPos from, List<BlockPos> br) {
        BlockPos srcUp2 = from.offset(0, 2, 0);
        if (!RulesConfig.pathfinderSwimAshoreHeadRoom || !w.isSolid(srcUp2)) {
            return 0;
        }
        double c = w.escapeBreakCost(srcUp2);
        if (Double.isFinite(c)) {
            br.add(srcUp2);
        }
        return c;
    }

    @Override
    public String name() {
        return "swimAshore";
    }

    @Override
    public Locomotion.Mode mode() {
        return Locomotion.Mode.SWIM;
    }
}
