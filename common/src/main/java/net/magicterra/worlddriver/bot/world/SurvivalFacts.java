package net.magicterra.worlddriver.bot.world;

/** Pure derivations over a HazardField. */
public final class SurvivalFacts {
    private SurvivalFacts() {}

    /** Number of lethal cells in the field, not counting the center. */
    public static int lethalCount(HazardField f) {
        int n = 0;
        int r = f.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (f.at(dx, dz).lethal()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Whether none of the eight neighbouring cells is both standable and safe. */
    public static boolean cornered(HazardField f) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                HazardCell c = f.at(dx, dz);
                if (c.standable() && !c.lethal()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The standable, non-lethal neighbouring step {@code {dx, dz}} that points most directly away from the threat
     * direction {@code (tx, tz)}, or null when every neighbour is unsafe.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null is "no step"; callers branch on it
    public static int[] safeFleeStep(HazardField f, int tx, int tz) {
        int[] best = null;
        double bestScore = -1e9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                HazardCell c = f.at(dx, dz);
                if (!c.standable() || c.lethal()) {
                    continue;
                }
                double score = dx * (-tx) + dz * (-tz);
                if (score > bestScore) {
                    bestScore = score;
                    best = new int[] {dx, dz};
                }
            }
        }
        return best;
    }
}
