package net.magicterra.worlddriver.bot.stagewright.journey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * What the ground under a corridor leg actually looks like, printed as a map.
 *
 * <h2>Why the corridor needed a map before it needed another fix</h2>
 *
 * Rung 14 walks the cells of {@code JourneyNetherRungs.FORTRESS_WAYPOINTS}, baked from a run that
 * reached the fortress. (No count is written here on purpose: the table has been edited since — one
 * waypoint was deleted on 2026-08-22 — and a spelled-out number in prose does not follow it. Ask the
 * array, or read the legs, which print 「第 N/{@code length}」.) Legs 8, 9 and 10 came back
 * {@code {bridgePlace=15}}, {@code {bridgePlace=13}}, {@code {bridgePlace=14}} — and {@code walk=0}.
 * Not one step on existing ground. ⚠️ Those are readings from THAT run; the two ladder runs of
 * 2026-08-28 carry no bridging reading at all, so they neither confirm nor refute them.
 *
 * <p>That is not a bug in the walker. The waypoints are body positions recorded AFTER that run
 * bridged, so <b>they describe a causeway, not terrain</b>, and a fresh world has none of it. Every
 * later leg has to rebuild the span it is standing on, and the corridor's pass rate is therefore the
 * per-cell placement success rate raised to the number of laid cells. Legs 11 onward then fail with
 * {@code expanded=100000}, which is what an A* over open sky looks like: with a bridge edge available
 * off every face and no wall to bound the frontier, the search fans out in three dimensions and hits
 * its node cap.
 *
 * <p>Three fixes were argued for on the strength of guesses about what is out there — reroute, split
 * the leg, raise the node cap — and <b>nobody had looked</b>. This looks. It is a measurement and
 * nothing else: it never fails a scene, never changes what a rung does next, and writes only rows.
 *
 * <h2>What each map answers</h2>
 *
 * <ul>
 *   <li><b>stand</b> — is there anything to stand on in this column at all, and at what height. A
 *       field of {@code .} says the surveyed line genuinely has no floor and rerouting is the only
 *       honest answer; a band of digits says dry ground exists and the waypoints are simply aimed
 *       beside it.</li>
 *   <li><b>headroom</b> — how many passable cells sit above that floor. <b>This is the one that can
 *       name the killer outright.</b> A bridge edge needs a body-height gap to move into; a ceiling
 *       two or three above the stand level erases every bridge edge across the span, and a search
 *       with no edges to expand reports exactly {@code expanded=100000}. A corridor that looks open
 *       in the stand map and reads {@code 0} or {@code 1} in the headroom map is a roofed tunnel,
 *       and no amount of node budget will get a body through it.</li>
 * </ul>
 *
 * <h2>It generates chunks, and that has to be said out loud</h2>
 *
 * Reading a block in an ungenerated column runs worldgen on the server thread. The probe therefore
 * bounds itself to {@link #SPAN} blocks around the legs it is asked about, and records the wall time
 * it spent plus the fact that it pre-generated terrain. <b>A run carrying this probe is not tick-
 * comparable with one that does not</b> — the chunks are warm afterwards — so a tick count that
 * shifts between a probed and an unprobed run is the probe, not a regression. Saying so here is
 * cheaper than the round it would otherwise cost.
 *
 * <p>Solidity is {@code getCollisionShape().isEmpty()} and lava is {@link FluidTags#LAVA}, which is
 * what the rest of this package already uses — see {@code JourneyFill}'s note on why that and not
 * {@code blocksMotion()}. A probe that answered「实心吗」by a different predicate than the scenes
 * around it would produce a map nobody could line up against a failure.
 */
final class JourneyCorridorProbe {
    private JourneyCorridorProbe() {}

    /** Half-width, in blocks, of the box probed around the legs of interest. */
    private static final int SPAN = 10;
    /** Widest map printed. Beyond this the rows wrap in a terminal and stop being readable. */
    private static final int MAX_COLS = 56;
    private static final int Y_LO = 20;
    private static final int Y_HI = 80;
    /** How far above the leg's band a floor still counts as a floor this leg could climb onto. */
    private static final int ABOVE_BAND = 4;
    /** ...and how far below. Wider than {@link #ABOVE_BAND}: dropping to a ledge is cheap, and the
     *  corridor's real routes have been running a few blocks under the surveyed line all along. */
    private static final int BELOW_BAND = 12;
    /** A body is two cells tall; this many passable cells above the floor is what a move needs. */
    private static final int BODY_HEIGHT = 2;

    /**
     * Ask every baked waypoint whether it exists in THIS world, before the rung walks any of them.
     *
     * <h2>Why the whole table, and why up front</h2>
     *
     * The corridor has been diagnosed one leg per run: a leg fails, its verdict says「这一段没走到」,
     * and the next round guesses at that leg. But {@code FORTRESS_WAYPOINTS} are body positions
     * recorded from a run that BRIDGED its way across, so an unknown number of them are cells that
     * run <b>created</b> — and in a fresh world those columns are open air. A leg aimed at one of
     * them cannot succeed no matter what the walker does, and it will keep reporting a walking
     * failure because「走不到」is the only thing a leg knows how to say.
     *
     * <p>Measured on wp4 ({@code 63,41,87}): the walker declared {@code arrived} at {@code 63,42,86}
     * with {@code 支撑[63,42,86=air 63,42,87=air]} — inside the two-block goal sphere, in mid-air,
     * over a shaft — and the body then fell fourteen blocks. Four runs read that as a pathfinding
     * problem. <b>It is a table problem</b>, and one column scan per waypoint answers it for all
     * eighteen at once, before a single step, for the price of eighteen block reads.
     *
     * <p>Reports three things per waypoint, because the table deliberately mixes FEET cells with
     * FLOOR cells and no single test fits both: whether the cell itself is solid, whether a body
     * could stand in it (air here, air above, something solid below), and where the nearest floor in
     * that column actually is. A reader classifies from those; this does not guess.
     *
     * <p>Pure measurement, like the rest of this file: no verdict, no behaviour change, only rows.
     */
    static void auditWaypoints(JourneyRig rig, int[][] waypoints) {
        ServerLevel level = rig.player().serverLevel();
        StringBuilder sb = new StringBuilder();
        int hollow = 0, unstandable = 0;
        for (int i = 0; i < waypoints.length; i++) {
            BlockPos cell = new BlockPos(waypoints[i][0], waypoints[i][1], waypoints[i][2]);
            boolean solidHere = solid(level, cell.getX(), cell.getY(), cell.getZ());
            boolean canStand = !solidHere
                    && !solid(level, cell.getX(), cell.getY() + 1, cell.getZ())
                    && solid(level, cell.getX(), cell.getY() - 1, cell.getZ());
            int floor = Integer.MIN_VALUE;
            for (int d = 0; d <= COLUMN_LOOK; d++) {
                int y = cell.getY() - d;
                if (y < Y_LO) break;
                if (solid(level, cell.getX(), y, cell.getZ())) { floor = y; break; }
            }
            if (!solidHere) hollow++;
            if (!solidHere && !canStand) unstandable++;
            sb.append("\n wp").append(i + 1).append(' ').append(cell.toShortString())
              .append(solidHere ? " 本格实心（是地板格，身体站它上面）"
                      : canStand ? " 本格空、脚下有实心（是落脚格，可站）"
                      : " **本格空且脚下也空** —— 这一格在新世界里不存在")
              .append("，这一柱往下最近的实心面 ")
              .append(floor == Integer.MIN_VALUE ? COLUMN_LOOK + " 格内没有（是竖井）" : "y=" + floor)
              .append(floor == Integer.MIN_VALUE ? "" : "（差 " + (cell.getY() - floor) + " 格，是 "
                      // NAMED, and asked about lava, because「下面 20 格有个面」does not say whether a
                      // route could run along it. The whole wp6..wp11 span sits over a drop with a
                      // floor about twenty blocks down, and re-baking that span onto it is only an
                      // option if it is rock.
                      //
                      // Lava is why the NAME is needed rather than just the depth: it has an EMPTY
                      // collision shape, so this scan falls straight through a lava lake and reports
                      // the lake's BASIN as the floor. That number is true and useless on its own —
                      // the basin is under twenty blocks of lava. So the floor is named, and asked
                      // separately whether lava sits on it.
                      + name(level, cell.getX(), floor, cell.getZ())
                      + (lavaAt(level, cell.getX(), floor, cell.getZ()) ? "，**而且是岩浆面**" : "")
                      + "）");
        }
        // COUNTED, NOT SPELLED. It said 「十八个」 while the table held seventeen — `wp4` was deleted
        // on 2026-08-22 and this sentence was not, so for six days the row's own headline disagreed
        // with the rows under it and with every leg's 「第 N/17 個」. A hard-coded count in an
        // evidence row is a snapshot of what its author believed; the array is the measurement.
        rig.evidence("fortress.waypointAudit", waypoints.length + " 个烘入路点在**全新世界**里的样子：本格空的 "
                + hollow + " 个，其中 " + unstandable + " 个**脚下也是空的**。"
                + "路点表是一趟【架过桥的】跑动记录，所以本格空且脚下空的那些是那一趟自己摆出来的"
                + "石头，这个世界里没有——瞄准它们的段无论寻路怎么改都走不到，而它们只会报"
                + "「走不到」。**先看这张表再去改机制。**（表里同时混着落脚格和地板格，所以三个"
                + "读数都给出来，不替读者归类）" + sb);
    }

    /** How far down a waypoint's own column is searched for a floor. Past this it is a shaft, and
     *  the exact depth stops mattering to the question being asked. */
    private static final int COLUMN_LOOK = 24;

    /**
     * Probe the box spanning {@code from} and the next {@code legs} waypoints, and write the maps.
     *
     * <p>Keyed {@code <what>.probe.*}. Call it once, at the leg that is failing — probing every leg
     * would pre-generate the whole corridor and cost more wall time than the rung has.
     */
    static void record(JourneyRig rig, String what, BlockPos from, int[][] waypoints, int i, int legs) {
        int x0 = from.getX(), x1 = from.getX(), z0 = from.getZ(), z1 = from.getZ();
        // The reference height the maps are cut at — the surveyed line this leg was walking, NOT the
        // body's current Y. The body that triggers this probe has usually fallen; cutting the map at
        // where it ended would map the hole it is lying in instead of the route it failed to walk.
        int ref = waypoints[Math.min(waypoints.length - 1, i)][1];
        for (int k = i; k < Math.min(waypoints.length, i + legs); k++) {
            x0 = Math.min(x0, waypoints[k][0]); x1 = Math.max(x1, waypoints[k][0]);
            z0 = Math.min(z0, waypoints[k][2]); z1 = Math.max(z1, waypoints[k][2]);
        }
        x0 -= SPAN; x1 += SPAN; z0 -= SPAN; z1 += SPAN;
        // Clipped at the far edge rather than sampled with a stride. A map with a stride can step
        // over a one-block gap or a one-block bridge, and a one-block gap is exactly the feature
        // this is here to find — a truncated map that is right beats a complete map that lies.
        boolean clipped = (x1 - x0 + 1) > MAX_COLS || (z1 - z0 + 1) > MAX_COLS;
        x1 = Math.min(x1, x0 + MAX_COLS - 1);
        z1 = Math.min(z1, z0 + MAX_COLS - 1);

        ServerLevel level = rig.player().serverLevel();
        long t0 = System.nanoTime();
        StringBuilder stand = new StringBuilder();
        StringBuilder head = new StringBuilder();
        for (int z = z0; z <= z1; z++) {
            StringBuilder rs = new StringBuilder();
            StringBuilder rh = new StringBuilder();
            for (int x = x0; x <= x1; x++) {
                int y = standY(level, x, z, ref);
                if (y == Integer.MIN_VALUE) { rs.append('.'); rh.append('.'); continue; }
                rs.append(lavaAt(level, x, y, z) ? '~' : heightChar(y));
                rh.append(headChar(headroom(level, x, y, z)));
            }
            stand.append('\n').append(pad(z)).append(' ').append(rs);
            head.append('\n').append(pad(z)).append(' ').append(rh);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        rig.evidence(what + ".probe.box", "x " + x0 + ".." + x1 + "，z " + z0 + ".." + z1
                + "，两张图都是在 y=" + ref + " 这条勘测线上下切的（上 " + ABOVE_BAND
                + " 格、下 " + BELOW_BAND + " 格，硬边界 " + Y_LO + ".." + Y_HI
                + "）。**不是整柱最高面**：第 5 段的 72,42,85 那一柱最高面在 y=67，"
                + "而路线是从那座山体底下的洞里过去的，按最高面画出来的图会说这条路不存在。"
                + "耗时 " + ms + " ms"
                + (clipped ? "；⚠️ 这个盒子比 " + MAX_COLS + " 宽，远端被裁掉了（没有隔行取样："
                        + "有跨度的图会跨过一格宽的缺口，而那正是要找的东西）" : "")
                + "。⚠️ 这次扫描会生成区块：带探针的一趟和不带的一趟 tick 数不可比，"
                + "之后的 tick 变化是探针不是回归");
        rig.evidence(what + ".probe.stand", "每列最高的可站立面（. = 这一柱在 y 带里没有落脚点，"
                + "~ = 落脚面是岩浆，" + legend() + "）：" + stand);
        rig.evidence(what + ".probe.head", "落脚面之上的通行高度（. = 没有落脚点，0/1 = 不够身体过去，"
                + "2..9 = 够，+ = 9 以上）。**架桥的边需要 " + BODY_HEIGHT
                + " 格净空**，所以一片 0/1 就是有顶的隧道；那种地形上 expanded=100000 不是预算不够，"
                + "是压根没有边可以扩：" + head);
    }

    /**
     * The highest standable floor <b>at or just above the leg's own band</b> — not the highest in
     * the world column.
     *
     * <h2>The first cut of this scanned from the sky down, and produced a true map of the wrong
     * thing</h2>
     *
     * Leg 5 aims at {@code 72,42,85}. Scanning from {@code Y_HI} down, that column reports
     * {@code y=67}, and its neighbours 66–69: a massif. Read literally, the map says the waypoint is
     * buried twenty-five blocks under a mountain and the route is impossible. <b>It is not</b> — the
     * previous run stood at {@code 71,43,85}. The corridor runs through a CAVE beneath that massif,
     * and a topmost-surface scan cannot see a cave by construction.
     *
     * <p>So the reference is the leg's band ceiling, and the scan starts a little above it. What
     * comes back is the floor a leg could actually be routed along, which is the only question this
     * probe was ever asked. Columns whose only floor is far below the band still read
     * {@code Integer.MIN_VALUE} and print {@code .}, because a floor thirty blocks down is not this
     * leg's floor either.
     *
     * <p>A surveyed cell is not its column — this suite has paid for that sentence before, and the
     * probe written to end the guessing reproduced it on its first run.
     */
    private static int standY(ServerLevel level, int x, int z, int ref) {
        int top = Math.min(Y_HI, ref + ABOVE_BAND);
        int bottom = Math.max(Y_LO, ref - BELOW_BAND);
        for (int y = top; y >= bottom; y--) {
            if (!solid(level, x, y, z)) continue;
            if (solid(level, x, y + 1, z)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int headroom(ServerLevel level, int x, int y, int z) {
        int n = 0;
        for (int h = y + 1; h <= Y_HI && n < 10; h++) {
            if (solid(level, x, h, z)) break;
            n++;
        }
        return n;
    }

    private static boolean solid(ServerLevel level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** A block's short id, so an audit row stays readable. */
    private static String name(ServerLevel level, int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
    }

    private static boolean lavaAt(ServerLevel level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return level.getFluidState(p).is(FluidTags.LAVA)
                || level.getFluidState(p.above()).is(FluidTags.LAVA);
    }

    /** {@code 0..9} for y=40..49, then {@code a..z} upward, {@code <} below 40 and {@code >} above. */
    private static char heightChar(int y) {
        if (y < 40) return '<';
        if (y <= 49) return (char) ('0' + (y - 40));
        if (y <= 75) return (char) ('a' + (y - 50));
        return '>';
    }

    private static char headChar(int n) {
        return n >= 10 ? '+' : (char) ('0' + n);
    }

    private static String legend() {
        return "0-9 = y40-49，a-z = y50-75，< = 低于 40，> = 高于 75";
    }

    private static String pad(int z) {
        String s = String.valueOf(z);
        return s.length() >= 4 ? s : "    ".substring(s.length()) + s;
    }
}
