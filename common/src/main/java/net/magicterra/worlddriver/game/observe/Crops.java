package net.magicterra.worlddriver.game.observe;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The crops a bot can farm: each crop block, the item that replants it, and the item harvesting it yields.
 * {@code mc.bot.farm}'s {@code Crop} enum names the same blocks; {@code CropsTest} keeps the two equal.
 */
public final class Crops {
    private Crops() {}

    public record Crop(String block, String seed, String harvest) {}

    public static final List<Crop> ALL = List.of(
            new Crop("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat"),
            new Crop("minecraft:carrots", "minecraft:carrot", "minecraft:carrot"),
            new Crop("minecraft:potatoes", "minecraft:potato", "minecraft:potato"),
            new Crop("minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot"));

    /** The item that replants {@code block}, or null when it is not a crop. */
    public static String seedFor(String block) {
        for (Crop c : ALL) {
            if (c.block.equals(block)) {
                return c.seed;
            }
        }
        return null;
    }

    /** The items farming can produce. */
    public static Set<String> harvests() {
        return ALL.stream().map(Crop::harvest).collect(Collectors.toUnmodifiableSet());
    }
}
