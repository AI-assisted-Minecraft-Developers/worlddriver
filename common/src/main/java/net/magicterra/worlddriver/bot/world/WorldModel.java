package net.magicterra.worlddriver.bot.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.observe.TimeSnap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Per-tick derived-facts blackboard over one bot's body. Mutated ONLY on that bot's tick thread; each {@link #update}
 * publishes an immutable {@link Snapshot} to a volatile field for off-thread readers (mc.client.scene). Makes NO
 * decisions — pure derived facts. See docs/design/perception-and-decision-layers.md.
 */
public final class WorldModel {
    private volatile Snapshot snapshot = Snapshot.absent();
    private HazardField hazard; // raw grid kept in-process for reflexes
    private int tick;

    public Snapshot snapshot() {
        return snapshot;
    }

    public HazardField hazard() {
        return hazard;
    }

    public void update(Body body, WorldView w) {
        LivingEntity p = body == null ? null : body.entity();
        if (p == null) {
            snapshot = Snapshot.absent();
            hazard = null;
            return;
        }
        Level level = p.level();
        tick++;
        BlockPos foot = p.blockPosition();
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        int radius = WorldConfig.hazardGridRadius;
        int decimate = Math.max(1, WorldConfig.hazardGridDecimateTicks);
        if (hazard == null || tick % decimate == 0 || !foot.equals(hazard.center)) {
            hazard = HazardField.compute(w, foot, radius, survivable, WorldConfig.deepWaterMax);
        }
        boolean cornered = SurvivalFacts.cornered(hazard);
        int lethal = SurvivalFacts.lethalCount(hazard);
        long timeOfDay = TimeSnap.timeOfDay(level.getDayTime());
        String phase = dayPhase(timeOfDay);
        boolean skyExposed = level.canSeeSky(foot.above());
        boolean exposedAtNight = ("NIGHT".equals(phase) || "DUSK".equals(phase)) && skyExposed;
        // Only a player eats; any other body reads as the absent snapshot's 0.
        int food = p instanceof Player pl ? pl.getFoodData().getFoodLevel() : 0;
        this.snapshot = new Snapshot(
                true,
                foot,
                p.getHealth(),
                food,
                phase,
                skyExposed,
                exposedAtNight,
                cornered,
                lethal,
                AsciiMapRenderer.rows(hazard));
    }

    /**
     * The BOT's day phase — <b>not</b> {@link TimeSnap#phase}, and deliberately left that way until someone decides
     * which is right.
     *
     * <pre>
     *   this               TimeSnap.phase (what the agent is told)
     *   &lt;12000  DAY        &lt;12000  day
     *   &lt;13800  DUSK       &lt;13000  sunset
     *   &lt;22200  NIGHT      &lt;23000  night
     *   else    DAWN       else    sunrise
     * </pre>
     *
     * <p>Different thresholds AND different vocabulary — and both are exported: this one as
     * {@code mc.client.scene.dayPhase}, the other as {@code mc.observe.player.time.phase}. An agent polling both is
     * told two different things in two windows: {@code [13000, 13800)} (night vs DUSK) and {@code [22200, 23000)}
     * (night vs DAWN).
     *
     * <p><b>Only one of those boundaries is load-bearing in code.</b> The single consumer here is
     * {@code exposedAtNight}, which ORs DUSK with NIGHT — so 13800 gates nothing, 12000 matches the other table
     * exactly, and DAWN and DAY are equivalent to it. What is left is the dawn edge: {@code DuskSecureChain} is armed
     * over {@code [12000, 22200)} while the agent is told it is night until 23000, so for 800 ticks at the end of the
     * night the agent reads "night" and the shelter reflex has already stood down.
     *
     * <p>Left as-is because unifying moves when the bunker reflex fires — a survival-path behaviour change plus an API
     * vocabulary change, not a de-duplication. Only the {@code % 24000} folding is shared (via
     * {@link TimeSnap#timeOfDay}).
     */
    static String dayPhase(long t) {
        if (t < 12000) return "DAY";
        if (t < 13800) return "DUSK";
        if (t < 22200) return "NIGHT";
        return "DAWN";
    }

    /** Immutable, off-thread-safe view. */
    public record Snapshot(
            boolean present,
            BlockPos pos,
            float health,
            int food,
            String dayPhase,
            boolean skyExposed,
            boolean exposedAtNight,
            boolean cornered,
            int lethalCount,
            List<String> rows) {
        public static Snapshot absent() {
            return new Snapshot(false, BlockPos.ZERO, 0, 0, "DAY", false, false, false, 0, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("present", present);
            if (!present) return m;
            m.put("pos", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
            m.put("health", health);
            m.put("food", food);
            m.put("dayPhase", dayPhase);
            m.put("skyExposed", skyExposed);
            m.put("exposedAtNight", exposedAtNight);
            m.put("cornered", cornered);
            m.put("lethalCount", lethalCount);
            m.put("rows", rows);
            return m;
        }
    }
}
