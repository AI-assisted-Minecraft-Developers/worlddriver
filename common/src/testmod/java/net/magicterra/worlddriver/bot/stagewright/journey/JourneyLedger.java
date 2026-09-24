package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * What this run of the journey achieved, carried across the scenes that make it up.
 *
 * <p>Every other suite in this repo is built so that scenes cannot see each other. This one is
 * built the opposite way on purpose, and the reason is the whole point of the exercise: a
 * playthrough is <b>one continuous run</b>, so its stages are not independent tests that happen to
 * be adjacent — they are chapters of a single narrative, and each one only means anything given
 * everything before it. An {@code IRON} stage that ran in a freshly staged arena would be a test of
 * {@code SmeltProcess}; an {@code IRON} stage that ran on ore the same body mined with a pickaxe it
 * crafted from wood it cut is a test of the playthrough. The suite already has 222 scenes doing the
 * former.
 *
 * <p>So state is static and deliberate. The costs that buys are real and are paid for explicitly:
 *
 * <ul>
 *   <li><b>Order matters.</b> The provider returns its scenes in ladder order and their names sort
 *       in that order too, so both the explicit list and {@code Stages}' name sort agree.</li>
 *   <li><b>A broken rung stops the climb.</b> Downstream stages do not fail — they record BLOCKED
 *       and skip, naming the rung that stopped them. N red rows for one root cause is noise; one
 *       red row plus a ledger that says how far the run got is a diagnosis.</li>
 *   <li><b>Nothing may leak into the rest of the suite.</b> The journey family only registers when
 *       armed, and the verdict scene tears the session down on every exit path.</li>
 * </ul>
 *
 * <h2>The ratchet</h2>
 *
 * {@link #FLOOR} is the highest rung the engine has actually climbed. The verdict scene fails when
 * a run does not reach it. That is the mechanism that turns a suite full of optional frontier
 * stages into a gate with teeth: everything above the floor may fail freely while it is being
 * built, and the moment a stage goes green it is promoted — {@code FLOOR} is raised and
 * {@link JourneyStage#gating()} is flipped — in the same commit. Progress therefore cannot silently
 * regress, and the frontier cannot silently stall either, because the floor is a written claim
 * about where it is.
 */
public final class JourneyLedger {

    /**
     * The highest rung this engine is held to.
     *
     * <p><b>Raise this only together with the matching {@link JourneyStage#gating()} flip, and only
     * on evidence of a green run.</b> Lowering it is a deliberate admission of regression and should
     * not happen without the reason going into {@code CHANGELOG.md}.
     *
     * <p>Currently {@link JourneyStage#PORTAL_KIT} — ROADMAP N0 through N3. Raised six times
     * (WOOD_TOOLS → STONE_TOOLS → FOOD → FURNACE → IRON → PORTAL_KIT), each time in the change that
     * made that rung reliable. Everything above is frontier and ships optional.
     *
     * <p><b>OBSIDIAN has been promoted and demoted twice</b> — once on three greens, once on five —
     * and the second round trip taught the thing the first did not. See {@link JourneyStage#OBSIDIAN}
     * for what a promotion of THAT rung has to show before it is worth making again.
     *
     * <p><b>IRON waited a long time for this, and what it was waiting for was not one green run.</b>
     * It had passed before and failed on the same code the next run, so it was left below the floor
     * on the rule this number exists to enforce: the floor claims a rung WORKS, not that it has once
     * worked. Ratcheting onto a coin flip makes every later regression unreadable, because half the
     * red rows are the coin. What changed is that the causes were found and fixed — none of them in
     * the engine, all of them in what the script asked for: a haul counted without regard to
     * species, a crafting table re-bought every rung instead of carried, a craft attempted where
     * there was no room to place a table, a search wider than the loaded world, and a walk that
     * reported arrival 88 blocks short. Promoted on three consecutive green runs of the same code,
     * ending with iron ingots ×4 / ×6 / ×6 and {@code stagingCalls=0}.
     *
     * <p><b>Raised to PORTAL_KIT on 2026-08-10.</b> It had been green six times before that and was
     * still held down here, because the greens were not on one code base and one of the reds was
     * real: the kit costs four ingots, the iron rung's vein loop was written to work three veins,
     * and only two were ever baked into {@link JourneyRoute}. A rung that passes because the terrain
     * was generous is not a rung that works. What made it promotable was finding that, not running
     * more runs. Four consecutive greens, ending with iron ingots ×6 / ×6 / ×11 / ×6.
     *
     * <p><b>Counting greens is the wrong promotion criterion, and OBSIDIAN cost two round trips to
     * establish it.</b> Promoted on three, regressed next run. Bar raised to five on the reasoning
     * that a one-in-four hazard is invisible to a three-run window; promoted on five, regressed next
     * run. Both times the regression was the SAME hazard — the tunnel holing a cave roof — and both
     * times the qualifying runs had simply never hit it: {@code tunnel.fell} appears in none of them.
     *
     * <p>So a bigger sample was never the answer. **A run that does not exercise a known recovery is
     * not evidence about that recovery**, however many of them there are, and this was written down
     * before the second promotion and then promoted past anyway. What a promotion here has to show
     * is that each known hazard's recovery has been <b>observed to work</b> — not that N runs in a
     * row happened to avoid it.
     *
     * <p>One bound rides along with this floor and is not hidden: every green row on this track
     * carries {@code body.invulnerable=true}. The ladder proves what the driver can DO, never that
     * a body survives doing it.
     */
    public static final JourneyStage FLOOR = JourneyStage.PORTAL_KIT;

    /** How one rung ended. */
    public enum Outcome {
        /** Climbed, with evidence. */
        REACHED,
        /** Attempted and did not make it — the interesting one. */
        FAILED,
        /** Never attempted, because a rung below it had not been climbed. */
        BLOCKED
    }

    /** One rung's result, with whatever the stage wrote down about it. */
    public record Entry(JourneyStage stage, Outcome outcome, String detail,
                        Map<String, Object> evidence, long serverTick) {}

    private static final EnumMap<JourneyStage, Entry> ENTRIES = new EnumMap<>(JourneyStage.class);

    /** Every deliberate departure from "play the game" this run made — expected to stay empty. */
    private static final List<String> STAGING = new ArrayList<>();

    private static long startedAtTick = -1;

    /**
     * Where the run's own portal came out on the nether side, or null before it stepped through.
     *
     * <p>Run-scoped rather than baked into {@code JourneyRoute}, because unlike the surveyed
     * landmarks this one is <b>made by the run</b> — it is wherever this playthrough happened to
     * cast and light it, and a fresh world puts it somewhere else.
     *
     * <p>It exists because the 2026-08-22 run needed it and only had it as prose: rung 13 printed
     * "landed at 6, 41, 3" into an evidence string, and rung 17 — which must walk back through that
     * doorway — could not read a sentence. It searched 24 blocks around a body that rungs 14 and 15
     * had carried 470 blocks away, found nothing, and failed. A landmark that is only printed is a
     * landmark nobody can use.
     */
    private static BlockPos netherPortal;

    private JourneyLedger() {}

    /** Wipe the ledger. Called by the first scene of a run so a second run in the same JVM (both
     *  dogfood passes share a process) does not read the first one's achievements as its own. */
    public static synchronized void reset(long serverTick) {
        ENTRIES.clear();
        STAGING.clear();
        startedAtTick = serverTick;
        netherPortal = null;
    }

    /** Remember where the run came out in the Nether, so the way home is data and not prose. */
    public static synchronized void noteNetherPortal(BlockPos where) {
        netherPortal = where == null ? null : where.immutable();
    }

    /** Where the run came out in the Nether, or null if it has not been through yet. */
    public static synchronized BlockPos netherPortal() { return netherPortal; }

    /** The server tick the run began on, or -1 before {@link #reset}. */
    public static synchronized long startedAtTick() { return startedAtTick; }

    /** Record a rung as climbed, with the evidence that says so. */
    public static synchronized void reached(JourneyStage stage, String detail,
                                            Map<String, Object> evidence, long serverTick) {
        ENTRIES.put(stage, new Entry(stage, Outcome.REACHED, detail,
                Map.copyOf(evidence == null ? Map.of() : evidence), serverTick));
    }

    /** Record a rung as attempted and missed. {@code detail} is what the run could not do. */
    public static synchronized void failed(JourneyStage stage, String detail,
                                           Map<String, Object> evidence, long serverTick) {
        ENTRIES.put(stage, new Entry(stage, Outcome.FAILED, detail,
                Map.copyOf(evidence == null ? Map.of() : evidence), serverTick));
    }

    /** Record a rung as never attempted because {@code blockedBy} was not climbed. */
    public static synchronized void blocked(JourneyStage stage, JourneyStage blockedBy, long serverTick) {
        ENTRIES.put(stage, new Entry(stage, Outcome.BLOCKED,
                "prerequisite stage " + blockedBy.name() + "(" + blockedBy.label() + ") was not reached",
                Map.of(), serverTick));
    }

    /** Whether a rung was climbed this run. */
    public static synchronized boolean has(JourneyStage stage) {
        Entry e = ENTRIES.get(stage);
        return e != null && e.outcome() == Outcome.REACHED;
    }

    /** This rung's entry, or null when the run never got to it at all. */
    public static synchronized Entry entry(JourneyStage stage) {
        return ENTRIES.get(stage);
    }

    /**
     * The highest rung reached with every rung below it also reached.
     *
     * <p>Contiguous rather than maximal, deliberately. A run that somehow recorded {@code DRAGON}
     * without {@code IRON} has not finished the game; it has a bug in this ledger or a stage that
     * staged its own prerequisites. Reporting the contiguous height makes that impossible to
     * mistake for progress.
     */
    public static synchronized JourneyStage height() {
        JourneyStage best = null;
        for (JourneyStage stage : JourneyStage.values()) {
            // Side rungs neither count nor block: a bed is not progress toward the dragon, and a run
            // that could not find a sheep has not stopped climbing. See JourneyStage#criticalPath.
            if (!stage.criticalPath()) continue;
            if (!has(stage)) break;
            best = stage;
        }
        return best;
    }

    /** Note a deliberate departure from playing the game. Expected to be called never. */
    public static synchronized void staged(String what) {
        STAGING.add(what);
    }

    /** Every staging call this run made — the measured version of "no test setup at any point". */
    public static synchronized List<String> stagingCalls() {
        return List.copyOf(STAGING);
    }

    /** One line per rung, in ladder order — the run's story, for the verdict's record. */
    public static synchronized Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (JourneyStage stage : JourneyStage.values()) {
            Entry e = ENTRIES.get(stage);
            out.put(stage.name(), e == null ? "NOT_RUN"
                    : e.outcome() + (e.detail() == null || e.detail().isBlank() ? "" : " — " + e.detail()));
        }
        return out;
    }
}
