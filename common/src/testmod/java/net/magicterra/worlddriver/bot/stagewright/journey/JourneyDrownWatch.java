package net.magicterra.worlddriver.bot.stagewright.journey;

import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * How long the head has been under, how low the air got, and <b>who was holding the channel while
 * it happened</b>.
 *
 * <p>This exists because the question「did a drowning reflex ever drive?」was asked of
 * {@code BotApiImpl.armedReflexes()}, which enumerates six COMBAT flags and says nothing about
 * drowning at all. Its silence was read as an answer. Both drown paths default on and
 * {@code DrownEscapeChain} is registered in the very scheduler {@link JourneyRig} drives through, so
 *「never ticked」is excluded by code — but that argument is about REGISTRATION, and the reading that
 * separates「a reflex held the channel and still could not surface」from「a reflex never got the
 * channel」is OCCUPANCY. The two want opposite fixes.
 *
 * <p><b>Accumulated per tick, not sampled per heartbeat.</b> {@code JourneyRig}'s heartbeat is 200
 * ticks and the window this has to see is the 100 between {@code drownEscapeAirThreshold} and an
 * empty bar; a 200-tick sampler over a 100-tick window misses it more often than it catches it, and
 * the miss reads exactly like「the reflex never held」. So air is latched at its minimum every tick
 * and the channel is polled at 5 Hz — the rate {@code mc.bot.status} is already answered at over
 * RPC, on the read {@code JourneyRig#botStatus} documents as safe from this thread.
 *
 * <p>Split out of {@code JourneyRig} rather than living in it because that file sits at its 3000-line
 * budget and the gate only permits shrinking; shaving comments to make room would have paid for this
 * instrument with the readability of unrelated code.
 */
final class JourneyDrownWatch {

    /** Ticks between channel polls while the head is under. Air itself is read every tick; it is
     *  one field, and it is the one with a threshold under it. */
    private static final int CHANNEL_SAMPLE_TICKS = 4;

    /** {@code DrownEscapeChain}'s arming threshold, printed beside the reading because a bare
     *  「最低空气 118」cannot be judged without it. */
    private static final int CHAIN_THRESHOLD = 100;

    /** The idle-only sentinel's, which is a different number for a different reflex — the pair is
     *  the point, since a run can sit between them. */
    private static final int SENTINEL_THRESHOLD = 240;

    private int underwaterTicks;
    private int airLowest = Integer.MAX_VALUE;
    private int sinceChannelSample;
    private final Map<String, Integer> channelsWhileUnder = new LinkedHashMap<>();

    /**
     * One tick of the latch.
     *
     * <p>The gate is {@code isUnderWater()} (head submerged) rather than {@code isInWater()},
     * because air only falls under the former and this instrument's whole subject is the air bar. A
     * body wading through a flooded shaft with its head out is not drowning, and counting it would
     * dilute the channel histogram with the walking process's name — which is the shape of every
     * reading in this repo that answered a question nobody asked.
     *
     * @param activeChain read lazily so the poll cost is paid only on the 5 Hz ticks, not on every
     *                    underwater tick.
     */
    void tick(ServerPlayer fp, Supplier<Object> activeChain) {
        if (!fp.isUnderWater()) { sinceChannelSample = 0; return; }
        underwaterTicks++;
        airLowest = Math.min(airLowest, fp.getAirSupply());
        if (++sinceChannelSample < CHANNEL_SAMPLE_TICKS) return;
        sinceChannelSample = 0;
        // THE VALUE, NOT A PREDICATE. 「is it the drown chain」answers the question I already think
        // I am asking; the name is what separates「a reflex held」from「the walk held」from
        //「nothing held」. A null chain is a real third state and gets its own bucket rather than
        // being folded into「not the drown chain」.
        Object chain = activeChain.get();
        channelsWhileUnder.merge(chain == null ? "（无链，用户任务在跑）" : String.valueOf(chain),
                1, Integer::sum);
    }

    /**
     * The latch as one row.
     *
     * <p>Written even when the body never went under: 「从没没过顶」is the positive control that
     * separates「the instrument ran and saw nothing」from「the instrument was not in this build」.
     * That confusion is not hypothetical — it cost rung 12's post-mortem its only discriminating
     * field on 2026-08-26, when a whole key family absent from three archives was read as a
     * behaviour difference and was a generation difference.
     */
    String line() {
        if (underwaterTicks == 0) return "从没没过顶（本级零 tick isUnderWater）";
        StringBuilder who = new StringBuilder();
        for (var e : channelsWhileUnder.entrySet())
            who.append(who.isEmpty() ? "" : "，").append(e.getKey()).append('=').append(e.getValue());
        return "没顶 " + underwaterTicks + " tick，最低空气 " + airLowest
                + "/300（反射链 DrownEscapeChain 的闸是 <" + CHAIN_THRESHOLD
                + "，只在闲时武装的哨兵是 <" + SENTINEL_THRESHOLD + "）"
                + "；没顶期间握着通道的（每 " + CHANNEL_SAMPLE_TICKS + " tick 一采）：" + who;
    }
}
