package net.magicterra.agent.bot.movement;

/**
 * What an {@link Avatar}'s underlying entity can do. Lets the orchestrator skip
 * Player-only chains (crafting/containers) on a plain mob and lets the planner
 * read the entity's movement caps. Phase 0/1 only ever uses {@link #PLAYER}
 * (both ClientPlayerAvatar and ServerPlayerAvatar wrap a Player), but the type
 * exists so later phases (MobAvatar) can advertise a reduced set.
 */
public record BodyCapabilities(
        boolean canPlace, boolean canBreak, boolean canCraft, boolean canSprint,
        int stepUpBlocks, int jumpUpBlocks, double aabbWidth) {

    /** A full-capability on-foot player (step 0, jump 1, width 0.6). */
    public static final BodyCapabilities PLAYER =
            new BodyCapabilities(true, true, true, true, 0, 1, 0.6);
}
