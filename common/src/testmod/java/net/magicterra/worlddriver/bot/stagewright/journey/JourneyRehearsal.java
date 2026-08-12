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
        if (target == JourneyStage.NETHER) {
            stageNether(ctx);
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
        BlockPos stand = dryStandNear(level, lake, 8, 20);
        if (stand == null) {
            ctx.fail("排练：岩浆湖 " + lake.toShortString() + " 周围 8..20 格内找不到一处干燥落脚点");
            return;
        }
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
    private static BlockPos dryStandNear(ServerLevel level, BlockPos lake, int min, int max) {
        for (int r = min; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = lake.getX() + dx;
                    int z = lake.getZ() + dz;
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
