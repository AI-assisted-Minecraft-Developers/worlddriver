package net.magicterra.worlddriver.bot.body;

/**
 * What a {@link Body}'s underlying entity can do. Lets the orchestrator skip
 * Player-only chains (crafting/containers) on a plain mob and lets the planner
 * read the entity's movement caps. Both player implementations answer {@link #PLAYER}
 * ({@link ClientPlayerBody} and {@code ServerPlayerBody} wrap a Player); the type
 * exists so a non-player {@code Body} can advertise a reduced set.
 */
public record BodyCapabilities(
        boolean canPlace, boolean canBreak, boolean canCraft, boolean canSprint,
        int stepUpBlocks, int jumpUpBlocks, double aabbWidth) {

    /** A full-capability on-foot player (step 0, jump 1, width 0.6). */
    public static final BodyCapabilities PLAYER =
            new BodyCapabilities(true, true, true, true, 0, 1, 0.6);
}
