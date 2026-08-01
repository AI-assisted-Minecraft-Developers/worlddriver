package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

/**
 * task#97c: learned stuck-risk edge tax — the engine end of the ML cost model.
 *
 * <p>Offline, 3.6k pathArchive replays were distilled into a lookup table
 * (ml/costmodel/export_table.py): for a handful of historically diseased move
 * families (swim*, bridgePlace, parkour*), the probability that the walker
 * stalls within 60 ticks of executing that move in a given local terrain
 * pattern (reduced-feature LightGBM, AUC 0.85; the full-feature model reads
 * 0.93 and independently reproduces the hand-debugged disease archive:
 * swimUp .93 / swimAshore .85 / bridgePlace .65). This class prices risky
 * edges so A* prefers a modest detour over a probable wedge.
 *
 * <p>Contract (the "learned components get no veto" rule): PURE ADDITIVE
 * g-side tax on a niche move subset — never a prune, never a capability
 * change, inert unless {@link BotConfig#riskBias} is ON. A non-negative
 * g-tax keeps the untaxed heuristic an underestimate, so A* admissibility
 * is unchanged (same argument as the legacy water taxes).
 *
 * <p>Storage: dense binary jar resource ({@value #RESOURCE}, format SRT1 —
 * move-name index + one byte[1120] risk plane per move, ~7 KB total), with
 * an optional config-dir override file ({@code config/worlddriver/}
 * {@value #FILE_NAME}) so a regenerated table can be A/B'd live without a
 * dev-jar rebuild (resource baking requires a relaunch).
 *
 * <p>The feature computation MUST mirror ml/costmodel/export_table.py
 * (training/inference consistency): medium(foot), facing riser height,
 * headroom, facing fall depth, in/over-water flag — ≤15 cached WorldView
 * reads, paid only on table-listed moves.
 */
public final class RiskCostTable {
    private RiskCostTable() {}

    public static final String RESOURCE = "/worlddriver/stuckrisk-table.bin";
    public static final String FILE_NAME = "stuckrisk-table.bin";

    private static final int MED_AIR = 0, MED_SOLID = 1, MED_WATER = 2, MED_LAVA = 3;
    /** slots per move: med_foot(5) * riser(4) * headroom(4) * fall(7) * water(2). */
    private static final int SLOTS = 5 * 4 * 4 * 7 * 2;

    /** move name -> dense plane index (only table-listed moves: fast negative). */
    private static volatile Map<String, Integer> moveIds = Map.of();
    /** concatenated per-move risk planes, byte 0..100 each. */
    private static volatile byte[] planes = new byte[0];
    private static volatile boolean loaded;
    /** Optional filesystem override (relative to the game working dir, same
     *  convention as BotConfig.persistPath() / the pathArchive replays dir). */
    private static Path override = Path.of("config", "worlddriver", FILE_NAME);

    /** Test/ops hook: repoint the override + force reload. */
    public static void reload(Path p) {
        if (p != null) override = p;
        loaded = false;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (RiskCostTable.class) {
            if (loaded) return;
            Map<String, Integer> ids = new HashMap<>();
            byte[] data = new byte[0];
            String from = null;
            try {
                if (override != null && Files.isRegularFile(override)) {
                    try (InputStream in = Files.newInputStream(override)) {
                        data = parse(in, ids);
                        from = override.toString();
                    }
                } else {
                    InputStream in = RiskCostTable.class.getResourceAsStream(RESOURCE);
                    if (in != null) {
                        try (in) {
                            data = parse(in, ids);
                            from = "resource " + RESOURCE;
                        }
                    }
                }
                if (from != null) {
                    LOG.info("[riskTable] loaded {} move planes from {}", ids.size(), from);
                } else {
                    LOG.info("[riskTable] no table (resource {} absent) — riskBias inert", RESOURCE);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("[riskTable] load failed ({}) — riskBias inert", e.toString());
                ids.clear();
                data = new byte[0];
            }
            moveIds = ids;
            planes = data;
            loaded = true;
        }
    }

    /** SRT1: magic | u16 moveCount | moveCount×(u16 len + UTF-8 name) | planes. */
    private static byte[] parse(InputStream raw, Map<String, Integer> ids) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("SRT1")) {
            throw new IOException("bad magic");
        }
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            byte[] name = new byte[in.readUnsignedShort()];
            in.readFully(name);
            ids.put(new String(name, StandardCharsets.UTF_8), i);
        }
        byte[] data = new byte[n * SLOTS];
        in.readFully(data);
        return data;
    }

    /** The edge tax. Zero-cost early exits: flag off (one volatile read) or a
     *  move family the table does not price (one map probe). */
    public static double tax(BlockPos from, Move.Edge edge, WorldView w) {
        if (!BotConfig.riskBias) return 0;
        ensureLoaded();
        Integer mid = moveIds.get(edge.move);
        if (mid == null) return 0;

        int medFoot = medium(w, from);
        int water = (medFoot == MED_WATER || w.isWater(from.below())) ? 1 : 0;

        // facing riser: destination footprint column read at the FROM height
        // (mirrors extract.py's facing-cell column scan)
        int tx = edge.to.getX(), tz = edge.to.getZ(), fy = from.getY();
        int riser = 0;
        for (int dy = 0; dy < 3; dy++) {
            if (w.isSolid(new BlockPos(tx, fy + dy, tz))) riser = dy + 1;
            else break;
        }
        int headroom = 3;
        for (int dy = 2; dy <= 4; dy++) {
            if (w.isSolid(from.above(dy))) { headroom = dy - 1; break; }
        }
        int fall = 0;
        for (int dy = 2; dy <= 6; dy++) {
            if (!w.isSolid(new BlockPos(tx, fy - dy + 1, tz))) fall = dy - 1;
            else break;
        }

        int slot = ((((medFoot * 4 + Math.min(riser, 3)) * 4 + headroom) * 7
                + Math.min(fall, 6)) * 2 + water);
        int risk = planes[mid * SLOTS + slot] & 0xFF;
        return risk * BotConfig.riskBiasScale / 100.0;
    }

    private static int medium(WorldView w, BlockPos p) {
        if (w.isWater(p)) return MED_WATER;
        if (w.isHazard(p)) return MED_LAVA;
        if (w.isSolid(p)) return MED_SOLID;
        return MED_AIR;   // air/passable collapsed (export keys rarely use 4)
    }
}
