package net.magicterra.worlddriver.game.body;

import static net.magicterra.worlddriver.game.body.BotUtil.STATION_REACH;
import static net.magicterra.worlddriver.game.body.BotUtil.nearestBlockWithinReach;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** The crafting table and furnace a body could use from where it stands. */
public final class Stations {
    private Stations() {}

    /**
     * Nearest crafting table within interaction reach of the eye. Twin of {@link #furnace} — the scan lives in
     * {@link BotUtil#nearestBlockWithinReach} so the two cannot drift the way the PLACE half of this same pair did (see
     * {@code PlaceNearby}).
     */
    public static BlockPos table(Player p, Level lvl) {
        return nearestBlockWithinReach(p, lvl, Blocks.CRAFTING_TABLE, STATION_REACH, 4, 2);
    }

    /** Nearest furnace within interaction reach of the eye. Twin of {@link #table}. */
    public static BlockPos furnace(Player p, Level lvl) {
        return nearestBlockWithinReach(p, lvl, Blocks.FURNACE, STATION_REACH, 4, 2);
    }

    /**
     * Stations the bot can use RIGHT NOW without acquiring one — currently just a crafting_table already placed within
     * interaction reach ({@link #table}, the same check {@code CraftProcess.setupStation} uses). SINGLE SOURCE, shared
     * with the planner verbs (mc.recipe.resolve / mc.plan.acquire): the plan they report must be the plan the craft
     * process will actually run. If the planner used a different reach rule it could promise "no table needed" where
     * setupStation then fails — or, the other way, report an infeasible craft that would in fact succeed beside a
     * village table (gap #275). {@code p == null} → nothing available (world-less caller).
     */
    public static Set<String> available(Player p, Level lvl) {
        return (p != null && lvl != null && table(p, lvl) != null) ? Set.of("crafting_table") : Set.of();
    }
}
