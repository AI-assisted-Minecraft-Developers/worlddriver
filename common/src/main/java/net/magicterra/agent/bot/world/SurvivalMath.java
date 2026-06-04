package net.magicterra.agent.bot.world;

/** Pure survival arithmetic shared by HazardField + reflexes. No Minecraft client types. */
public final class SurvivalMath {
    private SurvivalMath() {}

    /**
     * Max fall distance (blocks) the player survives from current health.
     * Vanilla: fall damage = max(0, floor(fallDistance) - 3). So survivable when
     * fallDistance - 3 < health, i.e. distance < health + 3. featherFalling/jump-boost
     * ignored for v1 (conservative). Returns an int block count (floor).
     */
    public static int survivableFall(float health) {
        if (health <= 0f) return 0;
        // distance d survivable iff d - 3 < health  ->  d < health + 3
        int d = (int) Math.floor(health) + 3 - 1;
        return Math.max(0, d);
    }
}
