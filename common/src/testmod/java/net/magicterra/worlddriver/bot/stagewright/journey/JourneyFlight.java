package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * How the body actually travelled one leg — recorded tick by tick, not read off the wreckage.
 *
 * <h2>Why a recorder and not another snapshot</h2>
 *
 * Every reading the nether crossing has produced so far is taken AFTER the leg gave up:
 * {@code fortress.around.1 = 脚下=cave_air 身处=cave_air 头顶=air}, then two attempts of
 * {@code 六面全是 lava}. Those lines say where the body ENDED. They cannot say how it got there,
 * and the three ways it could have are the three different bugs:
 *
 * <ol>
 *   <li>the floor it was standing on stopped being a floor — a thin roof, gravel, or the walker
 *       breaking through its own support;</li>
 *   <li>it walked off the edge of a floor that is still there — the planned route crossed a cave
 *       mouth or a lava shore;</li>
 *   <li>it never left the ground under its own steam and the leg was judged finished while it was
 *       in the air, in which case the fall is downstream of the verdict, not upstream of it.</li>
 * </ol>
 *
 * <p>All three end with a body hanging in {@code cave_air}, and the snapshot that reports that is
 * the same line in all three cases.
 *
 * <h2>Which cells it asks about, and why that had to be fixed once already</h2>
 *
 * The first version read the single cell at {@code blockPosition().below()} and printed
 * {@code 原地离地（脚下 air）} — a line with two completely different causes and no way to tell
 * them apart, because <b>a player is 0.6 blocks wide and its support is whatever its bounding box
 * rests on</b>, which is up to four cells and frequently not the one under its centre. A body
 * standing on the last block of a lava shore has air directly beneath its feet position for a
 * whole tick before it falls. So the recorder reads the <i>footprint</i>: every cell the bounding
 * box spans, at the y just below it. Keeping the previous tick's footprint POSITIONS (not just
 * their names) is what makes "the floor was removed" and "the body walked off it" separable —
 * the same cells are re-read after the fall starts.
 *
 * <h2>What it reads, and from where</h2>
 *
 * The level and the body's own physics, never the bot's world view — the open question includes
 * "is the plan going somewhere the world does not agree with", and a view is not a witness to that.
 * Everything it touches is in the body's own chunk or the one it just left, both loaded by
 * definition, so a leg costs a handful of block reads per tick and no chunk loads.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It never fails, never steers and never touches the world. A recorder that could end a leg would
 * change the thing it is measuring, and the current diagnosis needs an unmodified crossing far more
 * than it needs another guard. {@link JourneyNetherRungs}'s {@code hazardBlockingARetry} is the
 * guard; this is the instrument.
 */
public final class JourneyFlight implements JourneyRig.TickWatcher {

    /** A drop worth writing down. Three blocks: one and two are step-downs an ordinary walk makes
     *  constantly, and a list that recorded those would bury the twenty-block fall in noise. */
    private static final int NOTABLE_DROP = 3;

    /** How many falls get their own line before the rest are only counted. Six is more than any
     *  healthy leg has and few enough to stay readable in one evidence value. */
    private static final int MAX_FALLS = 6;

    /** How many ticks of run-up are kept for the first notable fall. Eight is about half a second —
     *  long enough to show the body walking to the edge, short enough to read in one line. */
    private static final int RUN_UP = 8;

    /** How many track samples to keep. When full the track halves itself and doubles its stride, so
     *  a leg of any length ends with the same number of evenly spaced samples — the alternative is
     *  choosing a stride up front, which is choosing wrong for either a 300-tick leg or a 48000-tick
     *  one. */
    private static final int TRACK_SAMPLES = 16;

    private final JourneyRig rig;
    private final BlockPos from;
    private final int goalX;
    private final int goalZ;
    private final int legLength;

    private int t;
    private BlockPos prev;
    private boolean prevOnGround;
    private double prevY;
    private List<BlockPos> prevSupport = List.of();
    private String prevSupportNames = "无";
    private int prevSupportSolid;

    private boolean airborne;
    private BlockPos launchAt;
    private int launchTick;
    private int launchY;
    private String launchWhy = "";
    private float deepestFallField;
    private double fastestDrop;

    private final List<String> falls = new ArrayList<>();
    private int fallCount;
    private int deepestDrop;
    private boolean startedAirborne;
    private String lava;
    private String finishedAt;
    private String runUpOfFirstFall;
    private String pendingRunUp;

    private int lowestY = Integer.MAX_VALUE;
    private int highestY = Integer.MIN_VALUE;

    private final List<String> runUp = new ArrayList<>();
    private final List<String> track = new ArrayList<>();
    private int trackStride = 100;
    private int trackNext = 0;

    private JourneyFlight(JourneyRig rig, BlockPos from, int goalX, int goalZ) {
        this.rig = rig;
        this.from = from;
        this.goalX = goalX;
        this.goalZ = goalZ;
        this.legLength = (int) Math.round(Math.hypot(from.getX() - goalX, from.getZ() - goalZ));
    }

    /** Start watching a leg that is about to be walked towards the column {@code (x, z)}. */
    public static JourneyFlight watching(JourneyRig rig, BlockPos from, int x, int z) {
        return new JourneyFlight(rig, from, x, z);
    }

    @Override
    public void tick() {
        ServerPlayer fp = rig.player();
        ServerLevel level = fp.serverLevel();
        BlockPos at = fp.blockPosition();
        boolean onGround = fp.onGround();
        boolean inLava = fp.isInLava();
        boolean inWater = fp.isInWater();
        t++;
        lowestY = Math.min(lowestY, at.getY());
        highestY = Math.max(highestY, at.getY());
        sample(at, onGround, inLava);

        List<BlockPos> support = footprint(fp);
        int solid = 0;
        for (BlockPos p : support) if (level.getBlockState(p).blocksMotion()) solid++;

        // The tick the leg was decided on, whatever the verdict.
        //
        // NOT ServerWorldDriver.lastStep(). On the runProcess path that field is set to ARRIVED for
        // ANY process that reports done — a walk that ended `no path (expanded=1)` prints ARRIVED
        // there, and the first version of this line did exactly that: `step=ARRIVED … 泡在岩浆里`,
        // which reads as "the walker believed it had arrived, in lava" and is simply false. The
        // process's own honest verdict is the goto slot's, so that is what this asks.
        if (finishedAt == null && rig.body().finished()) {
            var slot = rig.body().botState().mc_goto;
            finishedAt = "goalReached=" + slot.goalReached + " end=" + slot.endReason
                    + " 在 " + at.toShortString() + " onGround=" + onGround
                    + " 支撑" + names(level, support)
                    + (inLava ? " 泡在岩浆里" : inWater ? " 泡在水里" : "");
        }

        if (lava == null && inLava) {
            lava = "t=" + t + " " + at.toShortString() + " —— "
                    + (airborne ? "从 y=" + launchY + " 掉进去的" : "走进去的");
        }

        if (prev == null) {                        // first tick of the leg
            startedAirborne = !onGround;
            remember(fp, at, onGround, support, names(level, support), solid);
            return;
        }

        if (airborne) {
            deepestFallField = Math.max(deepestFallField, fp.fallDistance);
            fastestDrop = Math.max(fastestDrop, prevY - fp.getY());
            if (onGround || inLava || inWater) land(level, at, onGround, inLava, support);
        } else if (prevOnGround && !onGround && !inLava && !inWater) {
            launch(level, at, support);
        }
        rememberRunUp(at, onGround, solid);
        remember(fp, at, onGround, support, names(level, support), solid);
    }

    /**
     * Note the cell the body just left the ground from, and WHY it stopped being ground.
     *
     * <p>Three readings, and the order they are tested in is the diagnosis. The previous tick's
     * footprint POSITIONS are re-read now: solid then and gone now is a floor that disappeared under
     * a standing body. Still solid, and the body's footprint has moved off it, is a body that walked
     * over the edge — and the cells it walked ONTO name what it walked into. Still solid and the
     * footprint unchanged is neither, and says so rather than picking one.
     */
    private void launch(ServerLevel level, BlockPos at, List<BlockPos> support) {
        int stillSolid = 0;
        for (BlockPos p : prevSupport) if (level.getBlockState(p).blocksMotion()) stillSolid++;
        boolean movedOff = !prevSupport.equals(support);
        airborne = true;
        launchAt = prev;
        launchTick = t;
        launchY = prev.getY();
        deepestFallField = 0;
        fastestDrop = 0;
        // Snapshot the run-up HERE and not when the fall lands. The first version kept it until
        // landing, by which time an eleven-block drop had rolled eight airborne ticks through the
        // ring and the evidence line read `t=72 空 撑0 | t=73 空 撑0 | …` — a perfect record of the
        // fall and none at all of the walk into it, which is the half being asked about. Held
        // pending rather than committed, because a launch is not yet a notable fall: an ordinary
        // one-block step down also leaves the ground, and the run-up wanted is the one belonging to
        // the drop that gets written down.
        pendingRunUp = String.join(" | ", runUp);
        if (prevSupportSolid > 0 && stillSolid == 0) {
            launchWhy = "上一 tick 撑着它的 " + prevSupportSolid + " 格没了（" + prevSupportNames
                    + " → " + names(level, prevSupport) + "）";
        } else if (prevSupportSolid > 0 && movedOff) {
            launchWhy = "走出了支撑格（上一 tick 踩着 " + prevSupportNames
                    + "，这一 tick 脚下是 " + names(level, support) + "）";
        } else if (prevSupportSolid == 0) {
            launchWhy = "上一 tick 就已经没有支撑格了（" + prevSupportNames
                    + "），onGround 却还报 true —— 支撑在别处，或者 onGround 迟了一拍";
        } else {
            launchWhy = "支撑格还在也没走开（" + prevSupportNames + "）";
        }
    }

    /** Close a fall episode and, if it was worth recording, write its line. */
    private void land(ServerLevel level, BlockPos at, boolean onGround, boolean inLava,
                      List<BlockPos> support) {
        int drop = launchY - at.getY();
        deepestDrop = Math.max(deepestDrop, drop);
        boolean worth = drop >= NOTABLE_DROP || inLava;
        airborne = false;
        if (!worth) return;
        fallCount++;
        if (runUpOfFirstFall == null) runUpOfFirstFall = pendingRunUp;
        if (falls.size() >= MAX_FALLS) return;
        String ended = inLava ? "落进岩浆 " + at.toShortString()
                : onGround ? "落到 " + at.toShortString() + "（脚下 " + names(level, support) + "）"
                : "落进水里 " + at.toShortString();
        // The two speeds are printed together on purpose. fastestDrop is measured off the body's own
        // y; fallDistance is the field vanilla keeps — and on a FakePlayer it stays 0 forever,
        // because ServerPlayer.checkFallDamage (the one Entity.move calls) is an EMPTY override and
        // the accumulating version, doCheckFallDamage, runs only off a movement packet this body
        // never sends. Printing both is what makes that provable from an evidence row instead of
        // arguable: 1.14 blocks in one tick against a fallDistance of 0.0.
        falls.add("#" + fallCount + " t=" + launchTick + " 从 " + launchAt.toShortString()
                + " " + launchWhy + " → " + ended
                + "，坠 " + drop + " 格（最快一 tick 掉 "
                + String.format(Locale.ROOT, "%.2f", fastestDrop)
                + " 格；同期 fallDistance 最大 "
                + String.format(Locale.ROOT, "%.1f", deepestFallField)
                + " —— 这个字段对 FakePlayer 恒为 0，不是「没掉」）"
                + "，已走 " + walkedAt(launchAt) + "/" + legLength + " 格"
                + "，" + planAt());
    }

    private void remember(ServerPlayer fp, BlockPos at, boolean onGround,
                          List<BlockPos> support, String supportNames, int solid) {
        prev = at;
        prevY = fp.getY();
        prevOnGround = onGround;
        prevSupport = support;
        prevSupportNames = supportNames;
        prevSupportSolid = solid;
    }

    /** The last few ticks, kept so the FIRST notable fall can show its run-up. */
    private void rememberRunUp(BlockPos at, boolean onGround, int solid) {
        runUp.add("t=" + t + " " + at.toShortString() + (onGround ? " 地" : " 空") + " 撑" + solid);
        if (runUp.size() > RUN_UP) runUp.remove(0);
    }

    /**
     * Keep an evenly spaced track of the whole leg, at whatever stride fits.
     *
     * <p>The falls list says where the body dropped; this says what the leg looked like between
     * them. A crossing that descended steadily for two hundred blocks and one that walked level and
     * then fell off a cliff produce the same fall entry and completely different tracks.
     */
    private void sample(BlockPos at, boolean onGround, boolean inLava) {
        if (t < trackNext) return;
        track.add(at.getX() + "," + at.getY() + "," + at.getZ()
                + (inLava ? "(岩浆)" : onGround ? "" : "(空中)"));
        trackNext = t + trackStride;
        if (track.size() < TRACK_SAMPLES) return;
        for (int i = track.size() - 1; i > 0; i -= 2) track.remove(i);
        trackStride *= 2;
    }

    // -----------------------------------------------------------------------------------------
    // Reading it back.
    // -----------------------------------------------------------------------------------------

    /** One line for the whole leg: how far it got, how it moved, and how it ended. */
    public String report() {
        ServerPlayer fp = rig.player();
        BlockPos at = fp.blockPosition();
        StringBuilder sb = new StringBuilder();
        sb.append("走了 ").append(walkedAt(at)).append('/').append(legLength).append(" 格（还差 ")
          .append(awayFromGoal(at)).append("）；y ").append(from.getY()).append('→').append(at.getY());
        if (highestY != Integer.MIN_VALUE) {
            sb.append("（途中最高 ").append(highestY).append("，最低 ").append(lowestY).append("）");
        }
        sb.append("；共 ").append(t).append(" tick；离地 ").append(fallCount).append(" 次");
        if (fallCount > 0) sb.append("，最深 ").append(deepestDrop).append(" 格");
        if (startedAirborne) sb.append("；起步时就不在地上");
        if (airborne) {
            sb.append("；结束时还在空中（从 ").append(launchAt.toShortString())
              .append(" y=").append(launchY).append(" 起，").append(launchWhy).append("）");
        }
        sb.append("；收工那一刻：").append(finishedAt == null
                ? "没有 —— 这一段是跑满 tick 被叫停的，不是进程自己结束的" : finishedAt);
        if (lava != null) sb.append("；首次入岩浆 ").append(lava);
        return sb.toString();
    }

    /** The fall lines, in order. Empty when the leg never left the ground by more than a step. */
    public List<String> falls() {
        if (fallCount <= falls.size()) return List.copyOf(falls);
        List<String> out = new ArrayList<>(falls);
        out.add("…另有 " + (fallCount - falls.size()) + " 次没有逐条记");
        return List.copyOf(out);
    }

    /** The leg's shape, sampled evenly. */
    public String trackLine() {
        return "每 " + trackStride + " tick 一采：" + String.join(" → ", track);
    }

    /**
     * Attach everything this recorder has to the rung's evidence, under {@code <what>.<tag>}.
     *
     * <p>Recorded on arrival as well as on failure, deliberately. A leg that reached its column
     * after a thirty-block fall into a cave arrived by the goal's definition and is still the
     * finding — and a PASS prints no evidence, so the only place that row can be read is the
     * results file, which is exactly where it belongs.
     */
    public void recordInto(String what, String tag) {
        rig.evidence(what + ".flight." + tag, report());
        List<String> lines = falls();
        for (int i = 0; i < lines.size(); i++) rig.evidence(what + ".fell." + tag + "." + i, lines.get(i));
        if (runUpOfFirstFall != null) rig.evidence(what + ".runUp." + tag, runUpOfFirstFall);
        rig.evidence(what + ".track." + tag, trackLine());
    }

    // -----------------------------------------------------------------------------------------

    /**
     * The cells the body's bounding box would rest on — its real support, not the one under its
     * centre.
     *
     * <p>A player box is 0.6 wide, so it spans one or two cells per axis and up to four in total.
     * Reading only {@code blockPosition().below()} is what made the first version of this recorder
     * unable to tell "the block was removed" from "the body was standing on the very edge of it".
     */
    private static List<BlockPos> footprint(ServerPlayer fp) {
        AABB box = fp.getBoundingBox();
        int y = Mth.floor(box.minY - 0.02);
        int x0 = Mth.floor(box.minX + 1.0E-4);
        int x1 = Mth.floor(box.maxX - 1.0E-4);
        int z0 = Mth.floor(box.minZ + 1.0E-4);
        int z1 = Mth.floor(box.maxZ - 1.0E-4);
        List<BlockPos> out = new ArrayList<>(4);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, y, z));
        return List.copyOf(out);
    }

    private static String names(ServerLevel level, List<BlockPos> cells) {
        if (cells.isEmpty()) return "[无]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(cells.get(i).toShortString()).append('=').append(blockName(level, cells.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Where in the plan the walker was, at the moment asked. A body that left the ground on step
     *  3 of 40 was following a route; one that left it with the plan exhausted was not. */
    private String planAt() {
        var slot = rig.body().botState().mc_goto;
        return "计划第 " + slot.pathStep + "/" + slot.pathLen + " 步";
    }

    private int walkedAt(BlockPos at) {
        return (int) Math.round(Math.hypot(at.getX() - from.getX(), at.getZ() - from.getZ()));
    }

    private int awayFromGoal(BlockPos at) {
        return (int) Math.round(Math.hypot(at.getX() - goalX, at.getZ() - goalZ));
    }

    private static String blockName(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
    }
}
