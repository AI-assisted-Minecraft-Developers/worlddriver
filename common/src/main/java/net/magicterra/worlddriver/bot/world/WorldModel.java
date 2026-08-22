package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.util.TimeSnap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick derived-facts blackboard. Mutated ONLY on the client tick thread; each
 * {@link #update} publishes an immutable {@link Snapshot} to a volatile field for
 * off-thread readers (mc.client.scene). Makes NO decisions — pure derived facts.
 * See docs/design/04-perception-and-decision-boundary.md.
 */
public final class WorldModel {
    private volatile Snapshot snapshot = Snapshot.absent();
    private HazardField hazard;          // raw grid kept in-process for reflexes
    private int tick;

    public Snapshot snapshot() { return snapshot; }
    public HazardField hazard() { return hazard; }

    public void update(Minecraft mc, WorldView w, Object state) {
        var p = mc.player;
        if (p == null || mc.level == null) { snapshot = Snapshot.absent(); hazard = null; return; }
        tick++;
        BlockPos foot = p.blockPosition();
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        int radius = BotConfig.hazardGridRadius;
        int decimate = Math.max(1, BotConfig.hazardGridDecimateTicks);
        if (hazard == null || tick % decimate == 0 || !foot.equals(hazard.center)) {
            hazard = HazardField.compute(w, foot, radius, survivable, BotConfig.deepWaterMax);
        }
        boolean cornered = SurvivalFacts.cornered(hazard);
        int lethal = SurvivalFacts.lethalCount(hazard);
        long timeOfDay = TimeSnap.timeOfDay(mc.level.getDayTime());
        String phase = dayPhase(timeOfDay);
        boolean skyExposed = mc.level.canSeeSky(foot.above());
        boolean exposedAtNight = ("NIGHT".equals(phase) || "DUSK".equals(phase)) && skyExposed;
        this.snapshot = new Snapshot(true, foot, p.getHealth(), p.getFoodData().getFoodLevel(),
                phase, skyExposed, exposedAtNight, cornered, lethal, AsciiMapRenderer.rows(hazard));
    }

    /**
     * The BOT's day phase — <b>not</b> {@link TimeSnap#phase}, and deliberately left that way
     * until someone decides which is right.
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
     * {@code mc.client.scene.dayPhase}, the other as {@code mc.observe.player.time.phase}. An
     * agent polling both is told two different things in two windows: {@code [13000, 13800)}
     * (night vs DUSK) and {@code [22200, 23000)} (night vs DAWN).
     *
     * <p><b>Only one of those boundaries is load-bearing in code.</b> The single consumer here
     * is {@code exposedAtNight}, which ORs DUSK with NIGHT — so 13800 gates nothing, 12000
     * matches the other table exactly, and DAWN and DAY are equivalent to it. What is left is
     * the dawn edge: {@code DuskSecureChain} is armed over {@code [12000, 22200)} while the
     * agent is told it is night until 23000, so for 800 ticks at the end of the night the agent
     * reads "night" and the shelter reflex has already stood down.
     *
     * <p>Left as-is because unifying moves when the bunker reflex fires — a survival-path
     * behaviour change plus an API vocabulary change, not a de-duplication. Only the
     * {@code % 24000} folding is shared (via {@link TimeSnap#timeOfDay}).
     */
    static String dayPhase(long t) {
        if (t < 12000) return "DAY";
        if (t < 13800) return "DUSK";
        if (t < 22200) return "NIGHT";
        return "DAWN";
    }

    /** Immutable, off-thread-safe view. */
    public record Snapshot(boolean present, BlockPos pos, float health, int food,
                           String dayPhase, boolean skyExposed, boolean exposedAtNight,
                           boolean cornered, int lethalCount, List<String> rows) {
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
