package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

/**
 * The portal rung's staircase, and whether it is still a staircase.
 *
 * <p>Split out of {@link JourneyPortalRung} when that file reached its 3000-line budget. Nothing
 * changed in the move; the seam is that the rung CUTS and WALKS the flight while this asks whether
 * the flight can still be walked, and puts back what it has lost.
 *
 * <p>The whole reason this exists as a self-check rather than as a one-off dig: a flight of sixteen
 * cut cells is not a thing that stays cut. Ten casts walk it twenty times, and between legs the same
 * body pillars, backfills and floods the ground it is standing on.
 */
final class JourneyStairs {

    private JourneyStairs() {}

    /**
     * Every cell of the flight, top first — the steps as cut, not as planned.
     *
     * <p>Recorded by {@code digStairsDown} as each one is opened, so the audit reads the staircase
     * the body actually made rather than the one the arithmetic predicted.
     */
    static final List<BlockPos> cells = new ArrayList<>();

    /** How far the body may be from a cell it mends. Five, which is a block or so past a player's
     *  own reach and well inside "the body walked to it"; see the note in {@link #mend}. */
    static final double MEND_REACH = 5.0;

    /** How many faults one leg may mend before it gives up and walks anyway. Three: the body has
     *  never broken more than one step in a trip, and a flight with four faults is a different
     *  finding that should be read rather than patched over. */
    private static final int MEND_PER_LEG = 3;

    /** Running totals, so a leg that mends nothing still leaves a trace of having asked. */
    private static int checked, mended;

    private static boolean sabotaged;

    /** Start a fresh flight. Called once, where the staircase's top cell is chosen. */
    static void reset(BlockPos top) {
        cells.clear();
        cells.add(top);
        checked = 0;
        mended = 0;
        sabotaged = false;
    }

    /** Add a step, unless it is the one already at the bottom of the list. */
    static void cut(BlockPos foot) {
        if (cells.isEmpty() || !cells.get(cells.size() - 1).equals(foot)) cells.add(foot);
    }

    /**
     * Where the next course starts, which is not always the cell the body is standing in.
     *
     * <p>{@code digStairsDown} used to read {@code blockPosition()} and cut the next step diagonally
     * out of it. A step is opened by removing the floor of the cell the body is about to occupy, so
     * the reading it takes right after is often of a body one row ABOVE that step, falling into it.
     * A course measured from there is one row too high, and the course AFTER it — taken once the body
     * has landed — lands its step in the same column. The flight is then a contradiction no world
     * state satisfies: {@link #faults} wants the upper step's support solid and the lower step's own
     * cell open, and those are one cell.
     *
     * <pre>
     * stair.3 = -8, 66, 19 → -6, 64, 19（一次挖两级）   ← the step is cut at y=64
     * stair.4 = -6, 65, 19 → -5, 64, 19                 ← read from its HEAD ROOM, one row high
     * stair.5 = -6, 64, 19 → -5, 63, 19                 ← same column as the step above it
     * </pre>
     *
     * <p>Not theoretical, and not rare where it happens: over every archived rehearsal and ladder run
     * of this rung that cut a staircase, exactly two flights carry such a stacked pair, and they are
     * the two runs handed over on 2026-08-17. Both spend their whole mend budget flipping that one
     * cell — {@code cast*.stairsMend.0 = … → 垫上了} against {@code lava*.stairsMend.0 = … → 敲开了},
     * eighteen audits and sixteen mends on the south geometry — and on the east arm the fill is what
     * ended the run: {@code cast6.returnStopped = 停在 -5, 64, 19 … 脚下 cobblestone} is the body
     * standing on the block its own repair had just dropped into the staircase, facing a two-block
     * drop where a step used to be.
     *
     * <p>So the course is anchored to the flight rather than to the body — <b>unconditionally</b>.
     *
     * <h2>A CONDITIONAL ANCHOR FALLS BACK TO THE THING IT REPLACED</h2>
     *
     * <p>Worth the general statement, because it outlives this cell: <b>a rule whose condition fails
     * silently is bypassed by its own fallback</b>. This anchor first shipped guarded — it applied
     * only while the body stood directly over the flight's deepest step — and the {@code else} behind
     * that guard was {@code return body}, i.e. exactly the "measure from {@code blockPosition()}"
     * algorithm the anchor exists to abolish. So the defect was not removed, only made intermittent,
     * and it came back as a coin flip. It is the same shape as {@link #needsOpen} being honoured by
     * the ramp and ignored by the tower behind it; there the fallback was another class, here it hid
     * inside a boolean.
     *
     * <p>Measured on the east arm, four pinned rehearsals that split two-and-two on this one cell:
     *
     * <pre>
     * {e4,e5}  stair.1 = -7, 65, 19 → -6, 64, 19     the next course
     * {e3,e6}  stair.1 = -8, 66, 19 → -7, 65, 19     stair.0's course, cut a second time
     *          stair.1.waited = -8, 66, 19 还没迈下去
     * </pre>
     *
     * In {e3,e6} the body had not yet stepped off the top, so it was not over the deepest step, so the
     * guard failed and the course was re-measured from the body — re-cutting the course just made.
     * Every repeat walks the flight one cell further out, which moved {@code stairs.bottom} from
     * {@code 2,56,19} to {@code 3,56,19}, dragged the whole mould with it, and killed the scoop
     * ({@code recover6}) on a seat whose only sightline is blocked by this rung's own obsidian. The
     * race is real — whether the body has begun falling when the next course is measured is tick
     * timing — but <b>the fix is not to wait for it</b>: waiting on a body standing on a step that
     * never opened burned a whole 40000-tick budget and produced no {@code stairs.bottom} at all.
     * The fix is to stop asking the body.
     *
     * <p>The one course that must NOT be anchored is the last, and that exception is <b>named at the
     * call site</b> ({@code anchored.getY() <= targetY ? body : anchored}) rather than hidden here:
     * anchoring it aims below the target row. Keeping it there is deliberate — this method now has no
     * silent branch at all.
     */
    static BlockPos courseFrom(BlockPos body) {
        return cells.isEmpty() ? body : cells.get(cells.size() - 1);
    }

    /** How much asking has been done, for a message that would otherwise imply none. */
    static String tally() { return checked + " 次，修好 " + mended + " 级"; }

    /**
     * Which of the flight's cells this is, or null — the question everything that wants to put a
     * block down in the alcove has to ask.
     *
     * <p><b>Three cells per step, not one.</b> {@code digStairsDown} cuts the step, its head room and
     * the cell two above it, and {@link #faults} audits all three ({@code s > 0} for the third,
     * because nothing is ever climbed from the top cell). {@link #cells} holds only the first of the
     * three, so {@code cells.contains(...)} — which is what the alcove's own staircase builder used to
     * ask — is a third of the question.
     *
     * <p>The other two thirds cost a run. The alcove's floor row IS the flight's bottom step's row, so
     * a raise out of the alcove starts beside that step and rises straight through the clearance the
     * ascent jumps through. Measured on the south geometry, 2026-08-17: the raise laid
     * {@code wet.8.ramp.flight = -7,56,32 → -8,57,32 → -9,58,32 → -9,59,33}, whose third block is
     * {@code stairs.bottom(-9,56,32).above(2)}. The next audit read it as a broken stair, which it
     * genuinely was, and mended it the only way a blocked cell can be mended:
     * {@code lava8.stairsMend.1 = -9, 56, 32 起跳格 -9, 58, 32=cobblestone → 敲开了}. The raise the
     * body was standing on came out from under it and the run ended in the flooded mould,
     * {@code lava8.upStopped = 停在 -9, 58, 34 … 脚下/身处/头顶 都是 water}.
     *
     * <p>Named rather than lumped: a step, its head room and its jump clearance are refused for one
     * reason but a reader chasing a refusal needs to know which cell of which step it hit.
     */
    static String flightCell(BlockPos c) {
        for (int s = 0; s < cells.size(); s++) {
            BlockPos step = cells.get(s);
            if (step.equals(c)) return "是下井楼梯 " + step.toShortString() + " 那一级本身";
            if (step.above().equals(c))
                return "是下井楼梯 " + step.toShortString() + " 那一级的头顶格";
            if (s > 0 && step.above(2).equals(c))
                return "是下井楼梯 " + step.toShortString() + " 那一级的起跳格（爬上去要从这里穿过）";
        }
        return null;
    }

    /**
     * Does the flight need this cell OPEN? The boolean half of {@link #flightCell}.
     *
     * <p><b>A RULE IS ONLY AS STRONG AS ITS WEAKEST FALLBACK, and this one has a fallback that does
     * not ask.</b> Worth knowing in the general shape, because it outlives the cell it was found on:
     * {@code JourneyRamp.fillable} honours this rule and refuses to lay a step into the flight, and
     * the thing that runs BEHIND that refusal — the scripted tower, {@code JourneyShaft.climbOut*}
     * into {@code TowerProcess} — then does the very thing the ramp just refused, because a bot
     * process places under itself and has never heard of a staircase. Measured on the south geometry,
     * 2026-08-17: {@code cast7.ramp.noFlight = … -9, 57, 32 是下井楼梯 -9, 56, 32 那一级的头顶格，
     * 不能堵}, and the tower that took over filled exactly {@code -9,57,32}, which the next leg read
     * back as {@code cast8.stairsBroken = 1/12 级坏了}. One run of two survived it because the audit's
     * mend happened to land before the ascent — that is luck, not safety.
     *
     * <p>So when a refusal here matters, check what runs after it. A guard that only the polite path
     * consults reads as enforced right up until the impolite path is the one that runs.
     */
    static boolean needsOpen(BlockPos c) { return flightCell(c) != null; }

    /** One step that has stopped being a step, and which of the four ways it can stop being one. */
    record StairFault(BlockPos step, BlockPos cell, boolean missingSupport, String saw) {
        String describe() {
            String how = missingSupport ? " 脚下 "
                    : (cell.getY() - step.getY() == 2 ? " 起跳格 " : " 挡住 ");
            return step.toShortString() + how + cell.toShortString() + "=" + saw;
        }
    }

    /**
     * Is the staircase still a staircase?
     *
     * <p>Two runs have ended with the walker refusing to climb a flight whose geometry the rung had
     * never once re-read, and both times the answer was a single cell: the run of 2026-08-12 lost
     * {@code -9,64,22}, the support under the second step from the top, and every one of the other
     * twelve steps was perfect. "The stairs are dug but it will not climb them" is not a walker
     * finding until this has been asked, and asking it costs four block reads a step.
     *
     * <p>Four ways a step dies, and the order matters: no support is the one that cannot be seen
     * from above (the cell reads air either way), so it is checked first.
     *
     * <p>The fourth is the one this audit spent three runs without. {@code digStairsDown} cuts THREE
     * cells per step and says why in its own javadoc — the third is the clearance a jump needs two
     * above the feet it starts from — and this asked about two of them. On 2026-08-15 the ladder
     * stalled on the bottom step with {@code -9,58,36=dirt}, the body's own pillar backfilled into
     * that third cell, and the audit certified {@code 16 级都完好}: {@code StepUp.valid} refuses a +1
     * step unless {@code from.above(2)} is passable, and the flight walks under {@link NoBreak}, so
     * {@code StairUpBreak} — the variant that would have broken through it — is not on the table.
     * A flight can be perfect by every question this used to ask and still be unclimbable.
     */
    static List<StairFault> faults(ServerLevel level) {
        List<StairFault> out = new ArrayList<>();
        for (int s = 0; s < cells.size(); s++) {
            BlockPos step = cells.get(s);
            BlockPos under = step.below();
            if (!level.getBlockState(under).blocksMotion()) {
                out.add(new StairFault(step, under, true,
                        String.valueOf(level.getBlockState(under).getBlock())));
            } else if (level.getBlockState(step).blocksMotion()) {
                out.add(new StairFault(step, step, false,
                        String.valueOf(level.getBlockState(step).getBlock())));
            } else if (level.getBlockState(step.above()).blocksMotion()) {
                out.add(new StairFault(step, step.above(), false,
                        String.valueOf(level.getBlockState(step.above()).getBlock())));
            } else if (s > 0 && level.getBlockState(step.above(2)).blocksMotion()) {
                // s > 0: the clearance belongs to the step the body jumps FROM, and nothing is ever
                // climbed from the top cell. It is also the one cell of the flight `digStairsDown`
                // never cut — it is where the body was already standing — so asking about it would
                // report untouched surface rock as a broken stair and spend a mend digging it out.
                out.add(new StairFault(step, step.above(2), false,
                        String.valueOf(level.getBlockState(step.above(2)).getBlock())));
            }
        }
        return out;
    }

    /**
     * Which steps are standing in a fluid, as a clause to hang off the audit.
     *
     * <p>NOT faults: a pick does not mend water, and the alcove's drainage is its own open item. They
     * are on the line because water is invisible to every question {@link #faults} asks —
     * {@code blocksMotion()} is false for a water block, so a drowned staircase and a dry one both
     * report {@code N 级都完好}. That line is the one the last three rounds of this rung quoted to
     * rule the staircase out, and on 2026-08-15 the bottom step was under water while it said so.
     */
    private static String flooding(ServerLevel level) {
        List<String> wet = new ArrayList<>();
        for (BlockPos step : cells)
            for (BlockPos c : List.of(step, step.above()))
                if (!level.getFluidState(c).isEmpty())
                    wet.add(c.toShortString() + "=" + level.getBlockState(c).getBlock());
        if (wet.isEmpty()) return "";
        return "；" + wet.size() + " 格泡在流体里："
                + String.join("，", wet.subList(0, Math.min(wet.size(), 4)))
                + (wet.size() > 4 ? " …" : "");
    }

    /** The audit as one line, for a failure message that would otherwise have to guess. */
    static String report(ServerLevel level) {
        if (cells.isEmpty()) return "还没挖楼梯";
        List<StairFault> faults = faults(level);
        String wet = flooding(level);
        if (faults.isEmpty()) return cells.size() + " 级都完好" + wet;
        StringBuilder sb = new StringBuilder(faults.size() + "/" + cells.size() + " 级坏了：");
        for (int i = 0; i < Math.min(faults.size(), 5); i++)
            sb.append(i == 0 ? "" : "，").append(faults.get(i).describe());
        return sb + (faults.size() > 5 ? " …" : "") + wet;
    }

    /**
     * Put back what the flight has lost.
     *
     * <p>A missing support is answered with cobblestone the body is already carrying (rung 10 leaves
     * it about ninety), clicked onto a solid neighbour through {@code useItemOn} — the same path a
     * right click takes, and nothing here is a {@code setBlock}. Anything blocking a cell — the step,
     * its head room, or the clearance the ascent jumps through — is answered with the pick.
     */
    static void mend(JourneyRig rig, String tag, List<StairFault> faults, int i, Runnable then) {
        if (i >= faults.size() || i >= MEND_PER_LEG) { then.run(); return; }
        StairFault f = faults.get(i);
        ServerLevel level = rig.ctx().level();
        // Get within arm's reach FIRST. The place and the mine both go through the body's own
        // hands, so a repair aimed from the far end of the flight is a repair the ladder has not
        // earned — and the walk is short by construction, because the step below the break is
        // itself a step and the goal is satisfied from there.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(f.step(), 2), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 400, () -> {
            BlockPos body = rig.player().blockPosition();
            // ARM'S LENGTH OR NOTHING. `placeOn` goes straight to `gameMode.useItemOn`, which has
            // no reach gate on this avatar — so without this a mend the body could not walk to
            // would still succeed, from the far end of the flight, through ten blocks of rock.
            // That is a repair the ladder has not earned, and it would read as one that worked.
            double reach = Math.sqrt(body.distSqr(f.cell()));
            if (reach > MEND_REACH) {
                rig.evidence(tag + ".stairsMend." + i, f.describe() + " → 够不着（身体 "
                        + body.toShortString() + "，距 " + Math.round(reach) + " 格）");
                mend(rig, tag, faults, i + 1, then);
                return;
            }
            if (f.missingSupport()) {
                boolean held = rig.body().avatar().holdItem(Items.COBBLESTONE);
                boolean put = held && placeInto(level, rig, f.cell());
                mended += put ? 1 : 0;
                rig.evidence(tag + ".stairsMend." + i, f.describe() + " → "
                        + (put ? "垫上了" : (held ? "垫不上（贴不到实心面）" : "手上没有圆石"))
                        + "（身体 " + body.toShortString() + "，现在是 "
                        + level.getBlockState(f.cell()).getBlock() + "）");
                mend(rig, tag, faults, i + 1, then);
                return;
            }
            rig.mineCellOrGiveUp(f.cell(), 300, () -> {
                boolean open = !level.getBlockState(f.cell()).blocksMotion();
                mended += open ? 1 : 0;
                rig.evidence(tag + ".stairsMend." + i, f.describe() + " → "
                        + (open ? "敲开了" : "敲不开") + "（身体 "
                        + rig.player().blockPosition().toShortString() + "）");
                mend(rig, tag, faults, i + 1, then);
            });
        });
    }

    /**
     * Count this use of the flight, and — rehearsal only — ask for the mend to be needed.
     *
     * <p>{@code -Dworlddriver.journey.breakAStair=true}, with {@code -Dworlddriver.journey.rehearse}
     * already naming a rung, takes the support out from under the second step the first time the
     * flight is walked. The step it removes is exactly the one the run of 2026-08-12 lost, so a
     * rehearsal with this on measures the repair against the failure it was written for.
     *
     * <p>It exists because a healthy flight never trips the audit, and a guard nobody has watched
     * trip is a guard nobody has tested. The first leg is the control (audit silent, no mend); the
     * second is the case (one fault, one mend, and the eight legs after it silent again).
     *
     * <p>Two locks, because a sabotage that reached the real ladder would be the worst possible
     * bug here: it is off unless asked for, and it refuses outright when no rung is being rehearsed.
     * It is also a {@link JourneyLedger#staged} call, so a run that somehow did it anyway could
     * never report {@code staging.calls=0}.
     */
    static void aboutToWalk(JourneyRig rig, String tag) {
        checked++;
        if (sabotaged || checked < 2 || cells.size() < 3) return;
        if (!Boolean.getBoolean("worlddriver.journey.breakAStair")) return;
        if (JourneyRehearsal.target() == null) return;
        sabotaged = true;
        BlockPos gone = cells.get(1).below();
        JourneyLedger.staged("rehearsal: broke " + gone.toShortString()
                + ", the support under stair step 1, to make the flight's own audit trip");
        rig.ctx().level().destroyBlock(gone, false);
        rig.evidence("stairs.sabotage", gone.toShortString() + " 拆掉了（排练专用，只为让自检必须发现它）"
                + " —— 下一步 " + tag + " 应当报出这一级并垫回去");
    }

    /** Click the block into {@code cell} against whichever neighbour is solid. Mirrors the nether
     *  rung's own placer; the return value is read off the WORLD, because a placement can be
     *  refused for reasons the caller cannot see. */
    static boolean placeInto(ServerLevel level, JourneyRig rig, BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos against = cell.relative(d);
            if (!level.getBlockState(against).blocksMotion()) continue;
            rig.body().avatar().placeOn(against, d.getOpposite());
            if (level.getBlockState(cell).blocksMotion()) return true;
        }
        return false;
    }
}
