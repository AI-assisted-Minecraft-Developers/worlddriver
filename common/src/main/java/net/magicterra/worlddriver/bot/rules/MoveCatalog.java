package net.magicterra.worlddriver.bot.rules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.game.body.Locomotion;

/**
 * The moves of each way of moving, and the catalog a body gets by merging the ones it has.
 *
 * <p>Every {@link Move} names its {@link Move#mode()}, so the catalogs are {@link Move#ALL} partitioned
 * by that answer. A player walks and swims, so its catalog is all of {@link Move#ALL}, in its order —
 * the order is kept because a search breaks ties by insertion. Flying and riding have no moves yet;
 * a body that only flies gets an empty catalog and finds no route rather than a walking one.
 */
public final class MoveCatalog {

    private static final Map<Locomotion.Mode, List<Move>> BY_MODE = partition();

    private MoveCatalog() {}

    /** The moves performed in {@code mode}. */
    public static List<Move> of(Locomotion.Mode mode) { return BY_MODE.get(mode); }

    /** The moves of every mode in {@code modes}, in {@link Move#ALL}'s order. */
    public static List<Move> forModes(Set<Locomotion.Mode> modes) {
        List<Move> out = new ArrayList<>(Move.ALL.size());
        for (Move m : Move.ALL) if (modes.contains(m.mode())) out.add(m);
        return out;
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
