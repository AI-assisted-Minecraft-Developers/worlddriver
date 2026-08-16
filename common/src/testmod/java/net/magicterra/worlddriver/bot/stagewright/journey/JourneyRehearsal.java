package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * One upper rung of the journey ladder, run on its own with its preconditions put there by hand.
 *
 * <h2>The problem this exists for</h2>
 *
 * The ladder is one body climbing twenty rungs in sequence, and that is the whole point of it — but
 * it makes the upper rungs almost untestable. To exercise rung 12 you must replay rungs 1–11 and be
 * lucky: measured, about half of runs reach it at all, and a run costs twenty-five minutes. The
 * consequence is not theoretical. <b>Two committed fixes to rung 12 had never once executed.</b> A
 * change you cannot run is not a fix, it is a guess with a commit hash.
 *
 * <p>So this mode exists to make one rung reproducible: put the body where that rung starts, give it
 * what the rungs below would have given it, and run the rung's own code — unmodified — against it.
 *
 * <h2>What a green rehearsal is worth, and what it is not</h2>
 *
 * <b>It is not a climb.</b> The ladder's entire claim is {@code staging.calls=0}: the body really
 * played, and nothing was arranged for it. A rehearsal arranges everything, so it can never support
 * that claim and must never be mistaken for it. Four things keep the two apart, deliberately more
 * than one because the failure mode here is a human misreading a log six months from now:
 *
 * <ul>
 *   <li><b>Different scene names.</b> A rehearsal registers {@code wd.rehearse12PortalLit}, never
 *       {@code wd.journey12PortalLit}. No results file can carry a rehearsal under a ladder row's
 *       name, so grepping the ladder's own names never turns one up.</li>
 *   <li><b>Opt-in behind its own property.</b> {@link #PROPERTY} is set by one Gradle task and by
 *       nothing else. A normal ladder run, and all six gates, are byte-unchanged.</li>
 *   <li><b>Every arrangement is counted.</b> Each placeholder rung and each item handed over calls
 *       {@link JourneyLedger#staged}, so the rehearsed rung's own PASS row carries a non-zero
 *       {@code staging.calls} — the same number the real ladder asserts is zero.</li>
 *   <li><b>The verdict says so in words.</b> {@link #verdict} passes with
 *       {@code REHEARSAL — not a climb, staging.calls=N}.</li>
 * </ul>
 *
 * <p>What it IS worth: everything about the rung's own logic. The carve, the cast order, the bucket
 * ferrying, the reach of every verb the rung calls — all of that is the rung's real code running
 * against real terrain, and a failure in it is the same failure the ladder would hit. What it cannot
 * see is anything the rungs below would have handed over in a shape this staging got wrong (a worn
 * tool, an inventory laid out differently, a body standing somewhere else), which is why a rehearsal
 * green is a reason to run the ladder, not a substitute for having run it.
 */
public final class JourneyRehearsal {

    /** Names the ONE rung to rehearse, e.g. {@code -Dworlddriver.journey.rehearse=PORTAL_LIT}. */
    public static final String PROPERTY = "worlddriver.journey.rehearse";

    /** Caps the rehearsed rung's tick budget, so a wedged rehearsal dies in minutes rather than
     *  inheriting the ladder's hours-long ceiling. Override with {@code -D…rehearse.budget=N}. */
    public static final String BUDGET_PROPERTY = "worlddriver.journey.rehearse.budget";

    private static final int DEFAULT_BUDGET = 40_000;

    /** The ladder's own scene prefix, and the one this mode substitutes for it. */
    private static final String LADDER_PREFIX = "wd.journey";
    private static final String REHEARSAL_PREFIX = "wd.rehearse";

    /** How many obsidian cells a portal frame costs — {@code WorldDriverJourneyScenes.RING.length}.
     *  Duplicated rather than exposed: this is the number the LAKE has to be able to pay, which is a
     *  question about the landmark and not about the ring's shape. */
    private static final int PORTAL_FRAME_CELLS = 10;

    /**
     * The lava lake rung 12 casts from, baked so a rehearsal need not run the full survey.
     *
     * <p>{@code JourneyRoute.lavaLake} ships {@code UNSURVEYED} and is filled in at RECON by a scan
     * that sweeps eighty blocks of the seed and generates a hundred chunks doing it. That is right
     * for a ladder run, which pays it once and then climbs for twenty-five minutes; it is most of the
     * wall clock of a rehearsal that only wants to reach one rung.
     *
     * <p>So the coordinate is baked here — with the run measuring it rather than trusting it. The
     * source count around it is taken every run and recorded, and a short count re-surveys the
     * immediate neighbourhood before giving up, so a lake that drifted reports as a lake that
     * drifted instead of as a rung that cannot cast.
     */
    private static final BlockPos BAKED_LAVA_LAKE = new BlockPos(-9, 63, 19);

    private JourneyRehearsal() {}

    // =====================================================================================
    // Arming.
    // =====================================================================================

    /** The rung this run was asked to rehearse, or null on an ordinary run. */
    public static JourneyStage target() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return null;
        String wanted = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (JourneyStage stage : JourneyStage.values()) {
            if (stage.name().equals(wanted)) return stage;
        }
        throw new IllegalArgumentException("-D" + PROPERTY + "=" + raw
                + " names no rung; the ladder's rungs are " + java.util.Arrays.toString(JourneyStage.values()));
    }

    // =====================================================================================
    // Rewriting the ladder into a rehearsal.
    // =====================================================================================

    /**
     * Turn the registered ladder into a rehearsal of one rung.
     *
     * <p>Takes the ladder as it is already built rather than duplicating the registration, so a rung
     * added or re-budgeted upstream is rehearsable the same day without a second list to keep in
     * step. The mapping from scene to rung is the number in the name: {@code wd.journey09Iron} is
     * {@code JourneyStage.values()[8]}, which the ladder's own registration guarantees by ordering
     * its scenes and its enum together.
     */
    public static List<Scene> rewrite(List<Scene> ladder, JourneyStage target) {
        List<Scene> out = new ArrayList<>(ladder.size());
        for (Scene scene : ladder) {
            String name = scene.name();
            if (!name.startsWith(LADDER_PREFIX)) { out.add(scene); continue; }
            String tail = name.substring(LADDER_PREFIX.length());
            if (tail.length() < 2 || !Character.isDigit(tail.charAt(0)) || !Character.isDigit(tail.charAt(1))) {
                out.add(scene);                       // wd.journeyArmed — always registers, untouched
                continue;
            }
            String rehearsalName = REHEARSAL_PREFIX + tail;
            int nn = Integer.parseInt(tail.substring(0, 2));
            if (nn == 99) {
                out.add(bare(rehearsalName, 400, JourneyRehearsal::verdict).withRequired(true));
                continue;
            }
            if (nn < 1 || nn > JourneyStage.values().length) { out.add(scene); continue; }
            JourneyStage rung = JourneyStage.values()[nn - 1];
            out.add(rehearsalFor(scene, rehearsalName, rung, target));
        }
        return List.copyOf(out);
    }

    private static Scene rehearsalFor(Scene original, String name, JourneyStage rung, JourneyStage target) {
        if (rung == target) {
            Consumer<SceneContext> real = original.body();
            int budget = Math.min(original.budgetTicks(), budgetCap());
            return bare(name, budget, ctx -> {
                stageFor(ctx, target);
                real.accept(ctx);
            });
        }
        if (rung.ordinal() > target.ordinal()) {
            return bare(name, 100, ctx -> ctx.skip("REHEARSAL：本次只排练 " + target.name()
                    + "(" + target.label() + ")，它上面的 " + rung.name() + " 不跑"));
        }
        if (rung == JourneyStage.RECON) return bare(name, 1_200, JourneyRehearsal::recon);
        // SPAWN is the one rung below the target that is played for real: it is the only place a body
        // may be created, and its "empty-handed at spawn" assertion is what makes everything this
        // class then hands over visible AS staging rather than as inventory that was always there.
        if (rung == JourneyStage.SPAWN) return bare(name, original.budgetTicks(), original.body());
        return bare(name, 100, ctx -> placeholder(ctx, rung, target));
    }

    /** A rehearsal scene: never required (the verdict is the single authority) and never waits for an
     *  arena, exactly as the ladder's own rungs do not. */
    private static Scene bare(String name, int budget, Consumer<SceneContext> body) {
        return Scene.of(name, budget, body).withRequired(false).withArena(false);
    }

    private static int budgetCap() {
        String raw = System.getProperty(BUDGET_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_BUDGET;
        return Integer.parseInt(raw.trim());
    }

    // =====================================================================================
    // The rungs below: marked, not climbed.
    // =====================================================================================

    /**
     * Mark a rung reached without climbing it, so the rung above will start.
     *
     * <p>This is the single most dangerous thing in this class and it is written to announce itself.
     * {@link JourneyRig#enter} turns a rung away when the one below it was not climbed — correct, and
     * exactly what has to be defeated to run rung 12 alone. Defeating it by writing REACHED into the
     * ledger is a lie the ledger cannot detect, so every call is counted as staging and the skip text
     * says the word 未攀爬 rather than anything that reads like a pass.
     *
     * <p>The harness renders a skip as {@code PASS (0 ticks) — skipped: …}, so the reason string is
     * the only signal there is. It gets to say the whole thing.
     */
    private static void placeholder(SceneContext ctx, JourneyStage rung, JourneyStage target) {
        JourneyLedger.staged("rehearsal: marked " + rung.name() + " REACHED without climbing it");
        JourneyLedger.reached(rung, "REHEARSAL 占位 —— 本次并未攀爬这一级",
                Map.of("rehearsal.placeholder", true), ctx.level().getGameTime());
        ctx.record("rehearsal.placeholder", rung.name());
        ctx.skip("REHEARSAL 占位：" + rung.name() + "(" + rung.label() + ") 本次【未攀爬】，"
                + "只是被写进账本好让 " + target.name() + " 能起跑。这不是通过 —— "
                + "真正的攀爬是 ./gradlew :fabric:runJourneyServer");
    }

    // =====================================================================================
    // 01 — the minimum survey a rehearsal needs.
    // =====================================================================================

    /**
     * Reset the ledger, assert the seed, and adopt the lake without paying for the full survey.
     *
     * <p>The seed assertion is not ceremony: every coordinate this class bakes is a statement about
     * seed 5471, and a rehearsal booted on another world would move the body into terrain that has
     * never been looked at and report the rung failing there.
     */
    private static void recon(SceneContext ctx) {
        ServerLevel level = ctx.level();
        JourneyLedger.reset(level.getGameTime());
        JourneyStage target = target();

        ctx.record("REHEARSAL", "这是排练，不是攀爬 —— 见 wd.rehearse99Verdict");
        ctx.record("rehearsal.target", target == null ? "?" : target.name());
        ctx.record("seed", level.getSeed());
        ctx.expect(level.getSeed()).as("world seed (the rehearsal's baked coordinates are this seed's)")
                .isEqualTo(JourneyRoute.SEED);

        // Generate the lake's neighbourhood before counting it: every fluid read below would force
        // the same generation one chunk at a time, and doing it deliberately keeps the cost in one
        // place where it can be seen.
        loadAround(level, BAKED_LAVA_LAKE, 2);
        BlockPos lake = BAKED_LAVA_LAKE;
        int sources = lavaSourcesAround(level, lake, 8, 4);
        if (sources < PORTAL_FRAME_CELLS) {
            // The baked answer no longer describes the world. Look in its immediate neighbourhood
            // before giving up — a lake that shifted a few blocks is a different finding from a lake
            // that is not there, and only the second is worth a human's time.
            var found = JourneyRoute.surveyLavaLake(level, BAKED_LAVA_LAKE, 24);
            ctx.record("rehearsal.lake.rebaked", found.getKey().toShortString()
                    + " 有 " + found.getValue() + " 格源块（烘入的 " + BAKED_LAVA_LAKE.toShortString()
                    + " 只剩 " + sources + " 格）");
            if (found.getValue() >= PORTAL_FRAME_CELLS) {
                lake = found.getKey();
                sources = found.getValue();
            }
        }
        ctx.record("rehearsal.lake", lake.toShortString() + " 有 " + sources + " 格岩浆源块（浇十块要十格）");
        if (sources < PORTAL_FRAME_CELLS) {
            ctx.fail("排练用的岩浆湖不成立：" + lake.toShortString() + " 附近只有 " + sources
                    + " 格源块，浇十块要 " + PORTAL_FRAME_CELLS + " 格 —— "
                    + "跑一次完整的 wd.journey01Recon，把 lake.chosen 的坐标烘回 BAKED_LAVA_LAKE");
            return;
        }
        JourneyLedger.staged("rehearsal: adopted lavaLake " + lake.toShortString()
                + " (" + sources + " sources) instead of running the ladder's own survey");
        JourneyRoute.lavaLake = lake;
        JourneyRoute.lavaLakeSources = sources;

        JourneyLedger.reached(JourneyStage.RECON, "REHEARSAL：只做了排练需要的最小勘测",
                Map.of("rehearsal.lake", lake.toShortString(), "rehearsal.lakeSources", sources),
                level.getGameTime());
        ctx.passNote("REHEARSAL 勘测 — 种子 " + level.getSeed() + "，岩浆湖 " + lake.toShortString()
                + "（" + sources + " 格源块）。这不是攀爬。");
        WorldDriverCommon.LOG.info("[rehearsal] lake={} sources={} target={}", lake, sources, target);
    }

    // =====================================================================================
    // The staging itself.
    // =====================================================================================

    /** Put the rehearsed rung's preconditions into the world, loudly. */
    private static void stageFor(SceneContext ctx, JourneyStage target) {
        ctx.record("REHEARSAL", "这是排练，不是攀爬：下面各级并未真正爬过，本级的前置条件是布景摆出来的");
        if (target == JourneyStage.PORTAL_LIT) {
            stagePortalLit(ctx);
            return;
        }
        if (target == JourneyStage.OBSIDIAN) {
            stageObsidian(ctx);
            return;
        }
        if (target == JourneyStage.NETHER) {
            stageNether(ctx);
            return;
        }
        if (target == JourneyStage.BLAZE_ROD) {
            stageBlazeRod(ctx);
            return;
        }
        if (target == JourneyStage.ENDER_PEARL) {
            stageEnderPearl(ctx);
            return;
        }
        if (target == JourneyStage.EYE_OF_ENDER) {
            stageEyeOfEnder(ctx);
            return;
        }
        // No recipe. Say so rather than starting the rung on whatever the placeholder rungs left
        // behind — which is an empty body at world spawn, and a rung that fails on that reports a
        // missing recipe as a driver bug.
        JourneyLedger.staged("rehearsal: no staging recipe for " + target.name());
        ctx.record("rehearsal.recipe", "无 —— " + target.name()
                + " 还没有写布景配方，身体将以出生态起跑（多半会失败，而那不是引擎的错）");
    }

    /**
     * Rung 12's starting conditions: the portal toolkit, and a body standing beside the lake.
     *
     * <p>What is handed over is what rungs 1–11 would have handed over, at the tier they reach it
     * at — a STONE pickaxe, because the ladder's iron pays for the bucket and the flint-and-steel and
     * has none left for a tool. Handing over an iron one would make every mining budget in the rung
     * pass for a reason the ladder will never have.
     */
    private static void stagePortalLit(SceneContext ctx) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return;
        }
        ServerLevel level = ctx.level();
        ServerPlayer fp = body.fakePlayer();
        BlockPos lake = JourneyRoute.lavaLake;
        if (lake.equals(JourneyRoute.UNSURVEYED)) {
            ctx.fail("排练：没有岩浆湖坐标 —— wd.rehearse01Recon 没有跑成功");
            return;
        }

        Map<String, Integer> kit = new LinkedHashMap<>();
        // Two pickaxes, not one. A stone pickaxe has 131 uses and this rung breaks about a hundred
        // cells; one tool makes durability a hidden variable in exactly the leg under investigation,
        // and a mine that silently stops because the head snapped looks identical to a mine that
        // could not reach.
        kit.put("minecraft:stone_pickaxe", 2);
        // The water is handed over already in the bucket. Rung 12 fills it at the surface when it can
        // and walks to firstWater when it cannot, and that walk is a rung-10 capability being
        // re-tested at rung 12's expense — see fillWaterAtTheSurface's own short-circuit.
        kit.put("minecraft:water_bucket", 1);
        // The EXTRA buckets, empty, and only when asked for. See stagedBuckets.
        int buckets = stagedBuckets(ctx);
        if (buckets > 1) kit.put("minecraft:bucket", buckets - 1);
        kit.put("minecraft:flint_and_steel", 1);
        kit.put("minecraft:cobblestone", 64);
        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        ctx.record("rehearsal.gave", gave.toString());

        // Beside the lake, not on it. The rung walks the last few blocks itself, which keeps its own
        // approach under test; what is skipped is the eighty-block crossing from world spawn that
        // rung 11 would have paid for.
        loadAround(level, lake, 2);
        Direction side = forcedSide(ctx);
        BlockPos stand = dryStandNear(level, lake, 8, 20, side);
        if (stand == null) {
            ctx.fail("排练：岩浆湖 " + lake.toShortString() + " 周围 8..20 格内"
                    + (side == null ? "" : "的 " + side + " 侧") + "找不到一处干燥落脚点"
                    + (side == null ? "" : " —— 这颗种子在这一侧摆不出这个朝向"));
            return;
        }
        ctx.record("rehearsal.forgeAway", side == null
                ? "自然朝向 " + JourneyPortalRung.awayFrom(lake, stand) + "（没有指定 -PforgeAway）"
                : "指定 " + side + "，落脚点选在湖的这一侧，楼梯与模腔都会朝这边");
        loadAround(level, stand, 2);
        JourneyLedger.staged("rehearsal: moved the body to " + stand.toShortString()
                + " beside the lake instead of walking there");
        fp.setDeltaMovement(Vec3.ZERO);
        fp.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, fp.getYRot(), fp.getXRot());
        fp.setOnGround(true);
        ctx.record("rehearsal.stand", stand.toShortString() + "，距湖 "
                + Math.round(Math.sqrt(stand.distSqr(lake))) + " 格");
        WorldDriverCommon.LOG.info("[rehearsal] staged PORTAL_LIT: gave {} and stood the body at {}",
                gave, stand);
    }

    /**
     * Rung 11's starting conditions: an empty bucket, a pickaxe, and a body beside the lava column.
     *
     * <p>Written for one subject and it is worth naming, because a rehearsal without a subject drifts
     * into being a second ladder: <b>the shaft</b>. Rung 11 sinks the deepest hole the ladder digs —
     * 36 blocks on this seed — and it is the only rung that CHOOSES its column at runtime, which
     * makes it the only place a wet column can be answered by moving to a dry one. That answer is
     * unreachable on a real climb without luck (the flood is random: the same column read
     * {@code below=dirt} on one ladder run and {@code below=water} on the next), so
     * {@code -Dworlddriver.journey.wetShaft=true} makes it certain — see
     * {@link JourneyShaft#floodTheColumnOnce}.
     *
     * <p>What is handed over is what rungs 1–10 would leave: the empty bucket PORTAL_KIT buys, and
     * stone pickaxes, because the ladder's iron pays for the bucket and the flint-and-steel and has
     * none left for a tool. Two of them for the same reason rung 12's recipe gives two — a 36-block
     * shaft plus a tunnel is around a hundred breaks against a stone head's 131, and a mine that
     * stops because the tool snapped looks exactly like a mine that could not reach.
     *
     * <p>The body is stood a few blocks from the lava's own column at the SURFACE. Not on it: the
     * step onto a checked column is part of what the shaft does, and staging the body onto the
     * chosen column would stage the very check the descent depends on.
     */
    private static void stageObsidian(SceneContext ctx) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return;
        }
        ServerLevel level = ctx.level();
        ServerPlayer fp = body.fakePlayer();
        BlockPos lava = JourneyRoute.firstLava;
        if (lava.equals(JourneyRoute.UNSURVEYED)) {
            ctx.fail("排练：JourneyRoute.firstLava 还是 UNSURVEYED —— 这一级没有目标可去");
            return;
        }

        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:stone_pickaxe", 2);
        kit.put("minecraft:bucket", 1);
        kit.put("minecraft:cobblestone", 64);
        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        ctx.record("rehearsal.gave", gave.toString());

        loadAround(level, lava, 2);
        BlockPos stand = dryStandNear(level, lava, 4, 16);
        if (stand == null) {
            ctx.fail("排练：岩浆柱 " + lava.toShortString() + " 上方 4..16 格内找不到一处干燥落脚点");
            return;
        }
        loadAround(level, stand, 2);
        JourneyLedger.staged("rehearsal: moved the body to " + stand.toShortString()
                + " beside the lava column instead of walking there");
        fp.setDeltaMovement(Vec3.ZERO);
        fp.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, fp.getYRot(), fp.getXRot());
        fp.setOnGround(true);
        ctx.record("rehearsal.stand", stand.toShortString() + "，岩浆柱 "
                + lava.getX() + "," + lava.getZ() + " 在 " + Math.round(Math.hypot(
                        stand.getX() - lava.getX(), stand.getZ() - lava.getZ())) + " 格外，下挖 "
                + (stand.getY() - lava.getY()) + " 格");
        WorldDriverCommon.LOG.info("[rehearsal] staged OBSIDIAN: gave {} and stood the body at {}",
                gave, stand);
    }

    /**
     * Rung 13's starting conditions: a LIT portal, and a body standing in front of it.
     *
     * <p>Rung 13 is the first rung that had no recipe, which is why it had never executed a single
     * tick: without one the rehearsal starts an empty body at world spawn, {@code nether} looks for a
     * portal block within 24 and finds none, and the rung reports rung 12's absence rather than
     * anything about itself.
     *
     * <p>Built and lit the way the world builds one, not by writing {@code nether_portal} blocks
     * directly. Placing fire in the corner and letting {@code BaseFireBlock.onPlace} run
     * {@code PortalShape} is the same code path a flint-and-steel takes, so a frame this staging
     * accepts is a frame vanilla accepts — and if the shape were wrong the staging would say so here
     * instead of handing the rung an inert box of obsidian to walk into.
     *
     * <p>The body is put three blocks in FRONT of the doorway, not in it. What rung 13 is for is the
     * walk in and the dimension change; standing it in the portal would stage the very thing under
     * test.
     */
    private static void stageNether(SceneContext ctx) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return;
        }
        ServerLevel level = ctx.level();
        ServerPlayer fp = body.fakePlayer();
        BlockPos at = fp.blockPosition();
        loadAround(level, at, 2);
        BlockPos stand = dryStandNear(level, at, 6, 24);
        if (stand == null) {
            ctx.fail("排练：身体周围 6..24 格内找不到一处干燥落脚点来搭传送门");
            return;
        }
        loadAround(level, stand, 2);
        // The doorway's bottom-left interior cell. The frame is in the X-Y plane, so the body walks
        // into it along Z — the same orientation rung 12 casts.
        BlockPos door = stand.above();
        // Clear the box the frame and its doorway occupy, and floor it, so nothing of the terrain
        // is left standing inside a portal that is supposed to be six cells of air.
        for (int dx = -2; dx <= 3; dx++)
            for (int dy = -1; dy <= 5; dy++)
                for (int dz = -3; dz <= 1; dz++) {
                    BlockPos c = door.offset(dx, dy, dz);
                    level.setBlock(c, dy == -1 ? Blocks.STONE.defaultBlockState()
                                               : Blocks.AIR.defaultBlockState(), 2);
                }
        List<BlockPos> frame = new ArrayList<>();
        for (int dx = 0; dx <= 1; dx++) {                       // sill and lintel
            frame.add(door.offset(dx, -1, 0));
            frame.add(door.offset(dx, 3, 0));
        }
        for (int dy = 0; dy <= 2; dy++) {                       // the two jambs
            frame.add(door.offset(-1, dy, 0));
            frame.add(door.offset(2, dy, 0));
        }
        for (BlockPos c : frame) level.setBlock(c, Blocks.OBSIDIAN.defaultBlockState(), 3);
        level.setBlock(door, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 3);
        int lit = 0;
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 0; dy <= 2; dy++)
                if (level.getBlockState(door.offset(dx, dy, 0)).is(Blocks.NETHER_PORTAL)) lit++;
        ctx.record("rehearsal.portal", door.toShortString() + " 门洞左下角，六格中 " + lit + " 格已点亮");
        if (lit < 6) {
            ctx.fail("排练：布景摆的传送门没点着（" + lit + "/6）—— 这是布景的问题，不是 NETHER 这一级的问题");
            return;
        }
        JourneyLedger.staged("rehearsal: built and lit a portal at " + door.toShortString()
                + " instead of casting one");
        BlockPos front = door.offset(0, 0, 3);
        loadAround(level, front, 1);
        JourneyLedger.staged("rehearsal: moved the body to " + front.toShortString()
                + " in front of the portal instead of walking there");
        fp.setDeltaMovement(Vec3.ZERO);
        fp.moveTo(front.getX() + 0.5, front.getY(), front.getZ() + 0.5, fp.getYRot(), fp.getXRot());
        fp.setOnGround(true);
        ctx.record("rehearsal.stand", front.toShortString() + "，距门 3 格");
        WorldDriverCommon.LOG.info("[rehearsal] staged NETHER: lit a portal at {} and stood the body at {}",
                door, front);
    }

    /**
     * Rung 14's starting conditions: a body standing in the Nether, with the kit, and NOTHING else.
     *
     * <p>The line this recipe is careful about is the one that makes a rehearsal worthless. Rung 14
     * is "walk to the fortress, wall the spawner in, fight inside", and the walk is most of it — so
     * the fortress is <b>not</b> staged, not searched for here, and not hinted at. What is handed
     * over is what rungs 1–13 would have handed over: a body on the other side of a portal, a sword,
     * food, and blocks to build the room with. Finding the fortress stays the rung's own problem.
     *
     * <p>Crossed with {@code teleportTo}, which is a real cross-level move for a {@code ServerPlayer}
     * rather than a coordinate write — the driver's own view has to follow the body across, and if it
     * does not, that is a finding this rung should surface rather than one the staging should hide.
     */
    private static void stageBlazeRod(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        // What thirteen rungs would have left in the bag, at the tier they reach it at. An IRON sword
        // because rung 9 mines iron and a blaze is what the ladder buys it for; cobblestone because
        // the room is the rung's own plan; food because the fight is long.
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:stone_pickaxe", 1);
        kit.put("minecraft:cobblestone", 128);
        kit.put("minecraft:cooked_beef", 16);
        crossToTheNether(ctx, "BLAZE_ROD", kit, "要塞没有布景，得这一级自己找");
    }

    /**
     * Rung 15's starting conditions: the same body in the same Nether, one fortress richer.
     *
     * <p>Rung 15 hunts endermen, so the line to be careful about is a different one from rung 14's:
     * what must NOT be staged here is a mob or the ground that spawns them. No warped forest is
     * searched for, no enderman is summoned, and the body is put where a portal would have put it —
     * exactly where rung 14 would have left it, give or take the walk to the fortress. Whether there
     * are endermen within reach of that spot is the rung's problem and one of the things it is for.
     *
     * <p>The bag gains what rung 14 pays out — blaze rods — because rung 16 is the one that spends
     * them and a rehearsal of 15 that came home rodless would make 16 unrehearsable for a reason
     * that has nothing to do with 16.
     */
    private static void stageEnderPearl(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:stone_pickaxe", 1);
        kit.put("minecraft:cobblestone", 64);
        kit.put("minecraft:cooked_beef", 16);
        // What rung 14 hands over. Seven rather than twelve: a blaze drops 0–1 rods and the ladder's
        // own rung asserts a handful, so a rehearsal that started with a dozen would be rehearsing a
        // run the ladder has never had.
        kit.put("minecraft:blaze_rod", RODS_A_FORTRESS_PAYS);
        crossToTheNether(ctx, "ENDER_PEARL", kit, "末影人和它们的林地都没有布景，得这一级自己找");
    }

    /**
     * Rung 16's starting conditions: rods in one hand, pearls in the other, and no eyes.
     *
     * <p>The thinnest recipe in the file, because rung 16 is the thinnest rung: it grinds rods into
     * powder and marries powder to pearls, both 2×2 recipes that need no table and no room. What it
     * needs is stock, and stock is exactly what rungs 14 and 15 produce.
     *
     * <p>Not one {@code ender_eye} is handed over. The rung's whole subject is that the craft works
     * and that twelve of them can be got, so an eye in the bag would be the tautology this mode
     * exists to avoid — {@code eyeOfEnder} short-circuits on {@code already >= 1} and would report a
     * pass over a craft it never ran.
     */
    private static void stageEyeOfEnder(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:cooked_beef", 16);
        // Enough to make a set and no more. Twelve eyes want twelve powder and twelve pearls; six
        // rods grind to exactly twelve powder, so a run that ends short is short because the CRAFT
        // fell over and not because the staging was mean.
        kit.put("minecraft:blaze_rod", EYES_A_PORTAL_COSTS / 2);
        kit.put("minecraft:ender_pearl", EYES_A_PORTAL_COSTS);
        crossToTheNether(ctx, "EYE_OF_ENDER", kit, "一只末影之眼都没给 —— 合成本身就是这一级要证明的事");
    }

    /** How many blaze rods a fortress trip is worth. Seven — see {@link #stageEnderPearl}. */
    private static final int RODS_A_FORTRESS_PAYS = 7;

    /** A portal frame's worth of eyes; the copy of {@code JourneyEndRungs.EYES_A_PORTAL_COSTS} this
     *  file is allowed to have, for the same reason {@link #PORTAL_FRAME_CELLS} is duplicated: it is
     *  the number the STAGING has to pay, not the number the rung asserts. */
    private static final int EYES_A_PORTAL_COSTS = 12;

    /**
     * Hand over a bag and put the body where a portal would have put it.
     *
     * <p>Shared by all three Nether rungs, because their starting condition differs only in what is
     * in the bag. The coordinate is the overworld body's divided by eight, which is the same
     * arithmetic rung 13 asserts — staging it anywhere else would quietly change which part of the
     * Nether the rung has to search.
     *
     * <p>Crossed with {@code teleportTo}, which is a real cross-level move for a {@code ServerPlayer}
     * rather than a coordinate write — the driver's own view has to follow the body across, and if it
     * does not, that is a finding these rungs should surface rather than one the staging should hide.
     */
    private static void crossToTheNether(SceneContext ctx, String what, Map<String, Integer> kit,
                                         String notStaged) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return;
        }
        ServerPlayer fp = body.fakePlayer();
        ServerLevel nether = ctx.level().getServer()
                .getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether == null) {
            ctx.fail("排练：这台服务器没有下界（allow-nether?）—— 布景摆不出 " + what + " 的起点");
            return;
        }
        BlockPos want = new BlockPos(Math.floorDiv(fp.blockPosition().getX(), 8), 64,
                Math.floorDiv(fp.blockPosition().getZ(), 8));
        loadAround(nether, want, 2);
        BlockPos stand = netherStandNear(nether, want);
        if (stand == null) {
            ctx.fail("排练：下界 " + want.toShortString() + " 附近找不到一处站得住又不挨岩浆的落脚点");
            return;
        }
        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        ctx.record("rehearsal.gave", gave.toString());
        JourneyLedger.staged("rehearsal: crossed the body to the Nether at " + stand.toShortString()
                + " instead of walking through a portal it lit");
        fp.setDeltaMovement(Vec3.ZERO);
        fp.teleportTo(nether, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5,
                java.util.Set.of(), fp.getYRot(), fp.getXRot());
        fp.setOnGround(true);
        loadAround(nether, stand, 2);
        ctx.record("rehearsal.stand", stand.toShortString() + " @ " + fp.level().dimension().location()
                + "（" + notStaged + "）");
        WorldDriverCommon.LOG.info("[rehearsal] staged {}: gave {} and crossed the body to {}",
                what, gave, stand);
    }

    /** A cell in the Nether with something solid under it, two clear above, and no lava touching.
     *  Searched downward from the roof-clearance line, because a spot chosen at a fixed y is as
     *  likely to be inside the netherrack as on it. */
    private static BlockPos netherStandNear(ServerLevel nether, BlockPos want) {
        for (int r = 0; r <= 16; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int y = 100; y >= 32; y--) {
                        BlockPos foot = new BlockPos(want.getX() + dx, y, want.getZ() + dz);
                        if (!nether.getBlockState(foot.below()).blocksMotion()) continue;
                        if (!nether.getBlockState(foot).isAir()
                                || !nether.getBlockState(foot.above()).isAir()) continue;
                        boolean wet = false;
                        for (int ax = -1; ax <= 1 && !wet; ax++)
                            for (int ay = -1; ay <= 1 && !wet; ay++)
                                for (int az = -1; az <= 1 && !wet; az++)
                                    if (!nether.getFluidState(foot.offset(ax, ay, az)).isEmpty()) wet = true;
                        if (!wet) return foot;
                    }
                }
        return null;
    }

    // =====================================================================================
    // 99 — the verdict, which exists to make a green rehearsal unmistakable.
    // =====================================================================================

    /**
     * Report the one rung, and refuse to let the row read as a climb.
     *
     * <p>Deliberately NOT {@link JourneyLedger#height()}: the placeholder rungs make that number say
     * PORTAL_LIT, which is precisely the sentence this mode must never produce. What is reported is
     * the rehearsed rung's own outcome, its evidence, and the count of everything that was arranged
     * for it.
     */
    private static void verdict(SceneContext ctx) {
        try {
            JourneyStage target = target();
            List<String> staging = JourneyLedger.stagingCalls();
            JourneyLedger.Entry entry = target == null ? null : JourneyLedger.entry(target);

            ctx.record("REHEARSAL", "not a climb — 本次不是攀爬，下面各级是占位，不能当作 journey 结果");
            ctx.record("rehearsal.target", target == null ? "?" : target.name());
            ctx.record("rehearsal.stagingCalls", staging.size());
            for (int i = 0; i < staging.size(); i++) ctx.record("staged." + i, staging.get(i));
            ctx.record("rehearsal.outcome", entry == null ? "NOT_RUN"
                    : entry.outcome() + " — " + entry.detail());
            if (entry != null) entry.evidence().forEach((k, v) -> ctx.record("ev." + k, v));

            if (entry == null || entry.outcome() != JourneyLedger.Outcome.REACHED) {
                ctx.fail("REHEARSAL 失败：" + (target == null ? "?" : target.name())
                        + " 没有达成 —— " + (entry == null ? "这一级根本没跑" : entry.detail())
                        + "（staging.calls=" + staging.size() + "，这一行不是 journey 的成绩）");
                return;
            }
            ctx.passNote("REHEARSAL — not a climb, staging.calls=" + staging.size()
                    + "；只排练了 " + target.name() + "(" + target.label() + ")，"
                    + "它下面的每一级都是占位，未攀爬。真正的成绩来自 :fabric:runJourneyServer。");
            WorldDriverCommon.LOG.info("[rehearsal] {} REACHED with staging.calls={} — NOT a climb",
                    target, staging.size());
        } finally {
            JourneyRig.teardown();
        }
    }

    // =====================================================================================
    // Small world helpers. Reading terrain is not staging; moving the body is, and is counted.
    // =====================================================================================

    private static void give(ServerPlayer fp, String itemId, int count) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        fp.getInventory().add(new ItemStack(item, count));
    }

    /** Bring a square of chunks to FULL, so a scan of them measures terrain instead of generating it
     *  one lookup at a time. */
    private static void loadAround(ServerLevel level, BlockPos centre, int chunkRadius) {
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++)
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) level.getChunk(x, z);
    }

    private static int lavaSourcesAround(ServerLevel level, BlockPos centre, int r, int h) {
        int n = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -h; dy <= h; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA)) n++;
                }
        return n;
    }

    /**
     * Somewhere beside the lake a body can be set down: solid floor, two cells of air, no fluid.
     *
     * <p>Rings outward from {@code min} so the body lands close enough for the rung's own walk to be
     * short and far enough that it is not standing in the pool. The fluid checks are the whole point:
     * dropping a body into a surface lava lake is invisible on an invulnerable avatar and turns every
     * later reading into nonsense.
     */
    /**
     * Which side of the lake this rehearsal must stand on, or null for whichever comes first.
     *
     * <p><b>A rehearsal that always stands in one place tests one geometry.</b> The real ladder picks
     * its forge orientation from wherever eleven rungs left the body relative to the pool, and it is
     * a different one nearly every run: two consecutive ladder runs carved {@code forge.away=east}
     * and {@code forge.away=south}, and the second failed in a way the first could not reach — the
     * body could not walk back out of the alcove to the staircase. Thirty rehearsals had never once
     * been in that geometry, so the rehearsal was structurally blind to it, which is the same
     * blindness the inventory difference had (cobblestone here, dirt on the climb).
     *
     * <p><b>The side is staged, not the direction.</b> Forcing {@code stairDir} outright would let
     * the mould be carved TOWARD the lake — the one mistake that ends a run rather than costing it a
     * retry, and a state the real ladder can never be in, so anything found that way would not be a
     * finding. Standing the body on the requested side makes {@link JourneyPortalRung#awayFrom} return
     * that direction on its own, and every geometric invariant the rung relies on still holds. A seed
     * with no dry ground on one side simply cannot rehearse that orientation, and says so.
     *
     * <p>Rehearsal-only twice over, like {@code breakAStair}: this is read only from the staging step,
     * which only runs when {@link #target()} is set, and the choice goes into the staging ledger.
     */
    private static Direction forcedSide(SceneContext ctx) {
        String want = System.getProperty("worlddriver.journey.forgeAway", "").trim();
        if (want.isEmpty()) return null;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (d.getName().equalsIgnoreCase(want)) {
                JourneyLedger.staged("rehearsal: stood the body on the " + d
                        + " side of the lake so the forge faces " + d);
                return d;
            }
        ctx.record("rehearsal.forgeAway", want
                + " 不是 north/south/east/west 之一 —— 按自然朝向摆，没有强制");
        return null;
    }

    /**
     * How many buckets rung 12 gets, which is what decides how many trips to the pool it makes.
     *
     * <p>{@code castOpenedCell} climbs the staircase only when the bag has no lava left, and
     * {@code loadBuckets} fills every empty bucket in one visit, so the trips a run makes are
     * {@code ceil(10 / buckets-that-can-hold-lava)} — 10 trips on one bucket, 4 on four (one of them
     * stays empty for the water). <b>That branch is unreachable on the real ladder today</b>: the
     * kit's iron budget buys a bucket and a flint-and-steel and nothing more, so a climb arrives here
     * with exactly one bucket and the loop stops before its first iteration. Code that cannot run is
     * code nobody has tested, and this is what runs it.
     *
     * <p>Rehearsal-only twice over, like {@code breakAStair} and {@code forgeAway}: read only from
     * the staging step, which only runs when {@link #target()} is set, and counted into the ledger so
     * a run that somehow carried four buckets could never report {@code staging.calls=0}. The default
     * is 1 — a plain rehearsal hands over exactly what it handed over before this existed.
     *
     * <p>The claim to judge it by is <b>the trip count</b>, not the colour: count
     * {@code lava*.up} / {@code lava*.loaded} rows in the results file. Green proves the rung still
     * casts; only a fall from ten trips to four proves this did anything.
     */
    private static int stagedBuckets(SceneContext ctx) {
        String raw = System.getProperty("worlddriver.journey.buckets", "").trim();
        if (raw.isEmpty()) return 1;
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            ctx.record("rehearsal.buckets", raw + " 不是数字 —— 按 1 个桶摆（和真实爬升一样）");
            return 1;
        }
        if (n <= 1) return 1;
        JourneyLedger.staged("rehearsal: gave the body " + n + " buckets (" + (n - 1)
                + " empty + 1 of water) so the pool trips become ceil(10/" + (n - 1) + ")"
                + " — the real ladder can only afford one");
        ctx.record("rehearsal.buckets", n + " 个桶（" + (n - 1) + " 个空桶 + 1 桶水）—— "
                + "真实爬升只买得起 1 个，这是为了跑到多桶那条分支；判据是上楼趟数下降，不是绿");
        return n;
    }

    private static BlockPos dryStandNear(ServerLevel level, BlockPos lake, int min, int max) {
        return dryStandNear(level, lake, min, max, null);
    }

    private static BlockPos dryStandNear(ServerLevel level, BlockPos lake, int min, int max,
                                         Direction side) {
        for (int r = min; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = lake.getX() + dx;
                    int z = lake.getZ() + dz;
                    // THE RUNG'S OWN RULE, not a second copy of it. The staircase direction is
                    // `awayFrom(lake, stand)`, so filtering candidates through the very same call is
                    // what makes "stood on the north side" and "carved facing north" the same claim.
                    if (side != null
                            && JourneyPortalRung.awayFrom(lake, new BlockPos(x, lake.getY(), z)) != side)
                        continue;
                    int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            new BlockPos(x, 0, z)).getY();
                    BlockPos foot = new BlockPos(x, y, z);
                    if (!level.getBlockState(foot.below()).blocksMotion()) continue;
                    if (!level.getFluidState(foot.below()).isEmpty()) continue;
                    if (!level.getFluidState(foot).isEmpty()) continue;
                    if (!level.getFluidState(foot.above()).isEmpty()) continue;
                    if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) continue;
                    if (!level.getBlockState(foot.above()).getCollisionShape(level, foot.above()).isEmpty()) continue;
                    return foot;
                }
            }
        }
        return null;
    }
}
