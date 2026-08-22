package net.magicterra.worlddriver.bot.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place a world's {@code dayTime} is turned into an API row.
 *
 * <p>Same reason {@link AttackSnap} exists, and the same three consumers were already
 * disagreeing before it did. The day-phase bucketing was written three times:
 *
 * <ul>
 *   <li>{@code ObserveApi.playerSnapshot} — {@code ((dt % 24000) + 24000) % 24000}</li>
 *   <li>{@code ClientEventDetector}'s {@code time.phase} event —
 *       {@code dt % 24000; if (tod &lt; 0) tod += 24000}</li>
 *   <li>{@code ClientObserve.observePlayer} — {@code dt % 24000}, and nothing else</li>
 * </ul>
 *
 * <p>Java's {@code %} keeps the sign of the dividend, so the third one returns
 * {@code (-24000, 0]} for a negative {@code dayTime}, where {@code tod < 12000} is
 * unconditionally true — it reports {@code "day"} for every negative time. Two of the three
 * authors guarded against that independently and the third did not, which is the whole
 * argument for this class: <b>one of the three is wrong and reading the code cannot say
 * which</b>, because either negative dayTime is reachable and the third is broken, or it is
 * not and the other two carry dead defensive code. A single implementation makes the question
 * answerable — and moot.
 *
 * <p>{@code ClientEventDetector} promised this in a comment ("Buckets match
 * observe.player.time.phase so the Agent reads the same vocabulary") and kept the promise by
 * copying. A comment that says "I agree with that other code" is a constraint that needs
 * enforcing, not a statement that needs reading.
 *
 * <p><b>Deliberately preserved, not fixed:</b> {@code dayOfWorld} stays {@code dt / 24000},
 * plain truncating division, so a negative {@code dayTime} rounds toward zero. Both original
 * copies did that; changing it here would be a behaviour change smuggled into a de-duplication.
 */
public final class TimeSnap {
    private TimeSnap() {}

    /** Ticks in a Minecraft day. */
    public static final long DAY_TICKS = 24000L;

    /** {@code dayTime} folded into {@code [0, DAY_TICKS)} — negative input included. */
    public static long timeOfDay(long dayTime) {
        return ((dayTime % DAY_TICKS) + DAY_TICKS) % DAY_TICKS;
    }

    /**
     * The agent-facing bucket: {@code day} / {@code sunset} / {@code night} / {@code sunrise}.
     * Boundaries are vanilla's: mobs start spawning at 13000 and burn from 23000.
     */
    public static String phase(long dayTime) {
        long tod = timeOfDay(dayTime);
        if (tod < 12000) return "day";
        if (tod < 13000) return "sunset";
        if (tod < 23000) return "night";
        return "sunrise";
    }

    /**
     * The {@code time} row of {@code mc.observe.player}: {@code dayTime} (raw, absolute),
     * {@code dayOfWorld}, {@code timeOfDay} (folded), {@code phase}. Key order is the order
     * both hand-written copies used, so the JSON is byte-identical to what shipped.
     */
    public static Map<String, Object> snapshot(long dayTime) {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dayTime", dayTime);
        time.put("dayOfWorld", dayTime / DAY_TICKS);
        time.put("timeOfDay", timeOfDay(dayTime));
        time.put("phase", phase(dayTime));
        return time;
    }
}
