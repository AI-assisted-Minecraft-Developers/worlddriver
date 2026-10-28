package net.magicterra.worlddriver.bot.control.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * <b>A sustained pin discards the plan, and until the forced-repath line existed it did so in total
 * silence.</b>
 *
 * <p>A ladder crossing over a lava sea once ended in a shuttle: four hops of 900 ticks each, 61–76 walk
 * edges apiece, net −8 to −28 blocks, all inside one 27×31 box, with the only ground beyond one shelf a
 * causeway the bot had built itself. The stride guard fired 491 times in that crossing across 184
 * distinct cells. With {@link Safety}'s 8-tick hold tail a fire every eight ticks keeps the pin streak
 * alive, and at 30 pinned ticks the plan is thrown away. So two opposite diagnoses fit every row that
 * run produced — the bot was given plans that route backwards, or good plans that kept being discarded
 * under it — and nothing in the log or the evidence map could choose, because the discard logged
 * nothing and was counted nowhere.
 *
 * <p>This does not ask whether the discard is right. It asks only whether the forced-repath line
 * <b>separates the two situations the counter was built for</b>:
 * <ul>
 *   <li>a LIVELOCK — the bot held against one lip, pinning on the same cell, the 567-pins-at-one-cell
 *       run the forced repath exists for;</li>
 *   <li>a RIM WALK — the bot travelling along a lava shore, pinning on a new cell every few ticks, which
 *       is what a Nether crossing is made of.</li>
 * </ul>
 * Both must force a repath — the CONTROL: an arm where either does not has not reproduced the situation.
 * What must differ is the distinct-cell count in the line.
 *
 * <p>The streak is fed here one pinned tick at a time, with the stride cell the guard would have
 * plugged and a plan that does not advance, which is what both arms of the old in-game scene produced
 * against a lava trench. What that scene measured on top — that a real body pressed along a lava rim
 * really does pin on a changing cell without consuming its plan — needs a level and a body, and is
 * not asked here.
 */
class SafetyGuardRepathTest {

    /** The navigator's CURRENT wording of the pinned-cell count clause, deliberately not a union of past
     *  phrasings: accepting the dead ones would hide the next rewording. */
    private static final Pattern CELLS_IN_LINE = Pattern.compile("pinned in (\\d+) cells");

    /** Pinned ticks one drive gets; past the 30 that force a repath, with room to spare. */
    private static final int PIN_TICKS = 220;

    /** A body over no entity: the streak reads it only to say where the bot was. */
    private static final Body BODY = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[]{Body.class},
            (p, m, a) -> null);

    /** What one drive produced: forced repaths and the last forced-repath line. */
    private record Pin(int repaths, String line) {}

    /**
     * Pin {@code nav} tick after tick on the stride cell {@code cellAt(tick)}, its plan never advancing,
     * until two forced repaths (enough to read the line) or {@link #PIN_TICKS}.
     */
    private static Pin drive(java.util.function.IntFunction<BlockPos> cellAt) {
        Navigator nav = new Navigator(NavigatorSettings.named("goto"));
        BlockPos goal = new BlockPos(-5, 21, 24);
        nav.start(new Goal.Block(goal));
        List<BlockPos> plan = new ArrayList<>();
        for (int x = 1; x >= -5; x--) plan.add(new BlockPos(x, 21, 1));
        nav.route.install(plan, Arrays.asList(new Move.Edge[plan.size()]), 0, false);
        int before = nav.tel.guardForcedRepaths;
        for (int t = 0; t < PIN_TICKS; t++) {
            if (nav.route.path() == null) {
                // safetyRepath re-plans from where the body is; the same plan again, from its first node.
                nav.route.install(new ArrayList<>(plan), Arrays.asList(new Move.Edge[plan.size()]), 0, false);
            }
            nav.safety.guardPlugCell = cellAt.apply(t);
            nav.safety.forcedRepathIfPinnedTooLong(BODY);
            if (nav.tel.guardForcedRepaths - before >= 2) break;
        }
        return new Pin(nav.tel.guardForcedRepaths - before, nav.tel.lastGuardRepath);
    }

    /**
     * The cell count out of the forced-repath line, or −1 when the line cannot be read.
     *
     * <p><b>Parsed rather than recomputed, on purpose:</b> the claim is that the LINE separates a rim walk
     * from a livelock, so reading the streak's counter directly would assert something no reader ever sees.
     * That couples this to the WORDING of a log line, and the coupling rotted twice unnoticed in the old
     * scene: the navigator reworded its clause and the matcher went on returning −1, while the navigator's
     * own numbers were right. So a miss fails loudly as the parser's, never as a cell count.
     */
    private static int cellsIn(String line) {
        Matcher m = CELLS_IN_LINE.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Test
    void guardRepathSeparatesARimWalkFromALivelock() {
        BlockPos lip = new BlockPos(0, 21, 1);
        Pin walk = drive(t -> lip.south(t / 4));   // travelling along +z: a new stride cell every four ticks
        Pin lock = drive(t -> lip);                // held against one lip

        // THE CONTROL: both situations must reach the discard, or there is no reading to compare.
        assertTrue(walk.repaths() >= 1, "the rim-walk arm never triggered a forced repath (" + walk.line() + ")");
        assertTrue(lock.repaths() >= 1, "the livelock arm never triggered a forced repath (" + lock.line() + ")");

        // THE PARSER'S OWN CONTRACT, checked before the two readings are compared.
        assertTrue(walk.line() != null && lock.line() != null && cellsIn(walk.line()) >= 0 && cellsIn(lock.line()) >= 0,
                "cannot parse the navigator's forced-repath line: CELLS_IN_LINE (" + CELLS_IN_LINE.pattern()
                        + ") does not match its current wording. Rim walk: " + walk.line() + " | livelock: " + lock.line());

        int walkCells = cellsIn(walk.line()), lockCells = cellsIn(lock.line());
        assertEquals(1, lockCells, "the livelock must report exactly one cell, which is the situation this counter was"
                + " built for: " + lock.line());
        assertTrue(walkCells > lockCells, "the rim walk must report more cells than that; otherwise the line still prints"
                + " \"walked ninety blocks\" and \"stuck on one cell\" as the same sentence: rim walk " + walkCells
                + " cells, livelock " + lockCells + " cells");
    }
}
