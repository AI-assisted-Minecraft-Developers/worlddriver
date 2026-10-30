package net.magicterra.worlddriver.bot.rules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.worlddriver.bot.rules.moves.MediumMove;
import net.magicterra.worlddriver.game.body.Locomotion;

/**
 * The moves of each way of moving, and the catalog a body gets by merging the ones it has.
 *
 * <p>Every {@link Move} names its {@link Move#mode()}, so the catalogs are {@link Move#ALL} partitioned by that answer.
 * A player walks and swims, so its catalog is all of {@link Move#ALL}, in its order — the order is kept because a
 * search breaks ties by insertion. A body that lives in one medium, water or air, gets that medium's {@link MediumMove}
 * strokes instead; riding has no moves yet.
 */
public final class MoveCatalog {

    private static final Map<Locomotion.Mode, List<Move>> BY_MODE = partition();

    private MoveCatalog() {}

    /** The moves of {@link Move#ALL} performed in {@code mode}. */
    public static List<Move> of(Locomotion.Mode mode) {
        return BY_MODE.get(mode);
    }

    /**
     * The moves of every mode in {@code modes}, in {@link Move#ALL}'s order — or, for a body that lives in one
     * {@link #medium}, that medium's strokes: the swim moves in {@link Move#ALL} are a walking body's, which floats at
     * the surface and climbs out, and a flyer that could also land only flies.
     */
    public static List<Move> forModes(Set<Locomotion.Mode> modes) {
        MediumMove.Medium medium = medium(modes);
        if (medium != null) return medium == MediumMove.Medium.AIR ? MediumMove.FLY : MediumMove.SWIM;
        List<Move> out = new ArrayList<>(Move.ALL.size());
        for (Move m : Move.ALL) if (modes.contains(m.mode())) out.add(m);
        return out;
    }

    /**
     * The one medium a body with {@code modes} moves freely through, or null for a body that walks: air for one that
     * flies, water for one that swims and does not walk.
     */
    public static MediumMove.Medium medium(Set<Locomotion.Mode> modes) {
        if (modes.contains(Locomotion.Mode.FLY)) return MediumMove.Medium.AIR;
        if (modes.contains(Locomotion.Mode.SWIM) && !modes.contains(Locomotion.Mode.WALK))
            return MediumMove.Medium.WATER;
        return null;
    }

    private static Map<Locomotion.Mode, List<Move>> partition() {
        Map<Locomotion.Mode, List<Move>> by = new EnumMap<>(Locomotion.Mode.class);
        for (Locomotion.Mode mode : Locomotion.Mode.values()) {
            List<Move> ms = new ArrayList<>();
            for (Move m : Move.ALL) if (m.mode() == mode) ms.add(m);
            by.put(mode, List.copyOf(ms));
        }
        return by;
    }
}
