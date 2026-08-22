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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
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

    /**
     * Whether the rung being rehearsed needs {@link JourneyRoute#lavaLake} to have been surveyed.
     *
     * <p><b>The question this answers is not「is this rung about lava」but「does this rung's own
     * staging read {@code JourneyRoute.lavaLake}」</b>, and that is deliberate, because the second
     * question can be checked with one grep of this file while the first is a judgement about the
     * game. Today exactly one does: {@link #stagePortalLit} at the {@code UNSURVEYED} check, which
     * is why the list has one entry. {@code stageObsidian} reads {@code firstLava} — a different,
     * baked landmark — and needs nothing from here.
     *
     * <p><b>Why the survey was unconditional and why that had to stop.</b> It costs a chunk load
     * and a fluid sweep, and it {@code ctx.fail}s the rung when it comes up short. RECON is the
     * FIRST scene of every rehearsal, so that failure blocks SPAWN and every rung above it — which
     * means a rehearsal of rung 18, which has never heard of lava, died on a lake.
     *
     * <p><b>Wrong in either direction is safe, which is the point of putting it here.</b> An extra
     * entry costs one survey. A MISSING entry leaves {@code JourneyRoute.lavaLake} at
     * {@code UNSURVEYED} — its declared initial value — rather than at a wrong-but-plausible
     * coordinate, so the consumer reports「没有岩浆湖坐标 —— wd.rehearse01Recon 没有跑成功」and
     * names the fix instead of casting into the wrong pool.
     */
    private static boolean needsTheLavaLake(JourneyStage target) {
        return target == JourneyStage.PORTAL_LIT;
    }

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
            int declared = original.budgetTicks();
            int budget = Math.min(declared, capFor(rung, declared));
            return bare(name, budget, ctx -> {
                // SAY IT WHEN THE FIXTURE SHORTENED THE RUNG, and say it in words no rung ever uses
                // about itself. A run clipped here dies as a timeout, and a timeout on rung 20 reads
                // as「龙没打死」— a failure message naming the wrong mechanism, which is the family
                // this rung's investigation has lost the most rounds to. The row appears only when
                // the cap actually bit, so its ABSENCE is a reading too.
                if (budget < declared) {
                    ctx.record("rehearse.budgetCapped", budget + "（scene 自己声明 " + declared
                            + "，被排练的预算帽砍掉 " + (declared - budget) + "）—— "
                            + "⚠️ 这一趟如果超时，先看这一行：是夹具把预算砍短了，不是这一级判负。"
                            + "要跑满就 -PrehearseBudget=" + declared);
                }
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

    /**
     * The cap this rung actually gets — the default one, unless the rung's own scripted waits are
     * longer than it.
     *
     * <h2>A cap below a rung's own waits is not a shorter rehearsal, it is one that cannot finish</h2>
     *
     * <p>{@link #DEFAULT_BUDGET} is 40 000 and it is the right number for a rehearsal whose whole
     * point is dying in minutes rather than in the ladder's hours. Rung 20 does not fit in it by an
     * order of magnitude: its duel alone is scripted at 200 000 ticks and it waits a little past
     * that. Capped at 40 000 the duel could never end — the rehearsal would report a timeout for
     * every dragon, forever, and <b>the timeout would say nothing about the dragon</b>. See
     * {@link #budgetFloor} for which rungs get a floor and why the list is explicit, and
     * {@code rehearse.budgetCapped} for the row that fires whenever a cap did shorten a rung.
     *
     * <p><b>An explicit {@code -PrehearseBudget=N} still wins outright</b>, floor or no floor: that
     * property is how a wedged run gets killed early, and a floor that overrode it would take the
     * brake away exactly when it is wanted.
     *
     * <p>PROVISIONAL, like every other number about rungs 17–20: no climb has reached them, so the
     * duel's 200 000 is itself an estimate. The key to replace it with is {@code duel.ticks} on
     * {@code wd.journey20Dragon} once a ladder run gets there.
     */
    private static int capFor(JourneyStage rung, int declared) {
        int cap = budgetCap();
        String raw = System.getProperty(BUDGET_PROPERTY);
        boolean explicit = raw != null && !raw.isBlank();
        return explicit ? cap : Math.max(cap, budgetFloor(rung, declared));
    }

    /**
     * The tick floor below which capping this rung stops being「a shorter rehearsal」.
     *
     * <p><b>Zero for every rung but one, and that is what keeps this invisible to the rungs already
     * being measured.</b> {@code PORTAL_LIT} declares 250 000 of its own and has been rehearsed at
     * 40 000 all along, so a blanket「never cap below the scene's own budget」would raise it and
     * change a path that is currently a regression gate. The list is explicit for that reason and
     * must stay explicit: the next rung added to it has to be argued for, not inherited.
     *
     * <p><b>{@code DRAGON} gets its own DECLARED budget, and the middle number it used to get was a
     * mistake I made against my own rule.</b> The first version returned {@code DUEL_TICKS + 2 000}
     * = 202 000, on the reasoning that the duel is what does not fit in 40 000. But the duel is
     * 200 000 of that, which leaves two thousand ticks for reaching the End, building the platform,
     * bridging to the island and finding the dragon — and「just barely enough」is precisely the shape
     * this suite forbids in staging (see {@code stagedEyes}: an amount fitted to the requirement makes
     * the reading a tautology, and here it would make every over-run report「龙没打死」when the truth
     * is「预算到顶」). Worse, 202 000 was MY number, invented with no measurement behind it, standing
     * in front of a number the rung's own author chose. No climb has ever reached rung 20, so I have
     * nothing to justify a middle value with; the scene's declared 500 000 is at least a considered
     * ceiling and it is still a ceiling, not「无限跑」.
     *
     * <p>So the floor here is the declaration itself, which makes the default cap a no-op for this
     * one rung. An explicit {@code -PrehearseBudget=N} still wins outright — that is the brake, and a
     * floor that overrode it would take the brake away exactly when it is wanted.
     *
     * <p><b>{@code STRONGHOLD} joined the list on 2026-08-22, and here is its argument.</b> The rule
     * above is that a rung added here must be argued for rather than inherit the exemption, and the
     * argument has two halves. First, it does not fit, by the same order of magnitude the dragon does
     * not: the rung is「the longest walk in the game」— a march home across the Nether and then 1745
     * blocks of overworld terrain, at {@code MARCH_LEG_TICKS} = 4 000 a leg and up to
     * {@code MAX_MARCH_LEGS} = 48 legs, so the walk ALONE can want 192 000. Second — and this is what
     * separates it from {@code PORTAL_LIT}, which declares 250 000 and is deliberately left capped —
     * <b>no rehearsal of this rung has ever run</b>, because it had no staging recipe until the same
     * day. There is therefore no existing measurement for the floor to move, which is the exact
     * objection that keeps PORTAL_LIT capped.
     *
     * <p>Its first run is why this is not theoretical: capped at 40 000 it recorded
     * {@code rehearse.budgetCapped} and then failed on the return, and the two readings would have
     * been indistinguishable from each other — a rung that ran out of fixture budget and a rung that
     * cannot walk home look identical from the outside.
     */
    private static int budgetFloor(JourneyStage rung, int declared) {
        return rung == JourneyStage.DRAGON || rung == JourneyStage.STRONGHOLD ? declared : 0;
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

        // The lake is a landmark ONE rung's staging reads. Surveying it for the others buys nothing
        // and can cost everything: this is the first scene of the run, so its failure blocks SPAWN
        // and with it every rung above. See needsTheLavaLake.
        //
        // `target == null` keeps the old path on purpose — that is the un-targeted start-up, and a
        // change that only bites when a target was named is a change with a smaller blast radius.
        if (target != null && !needsTheLavaLake(target)) {
            ctx.record("rehearsal.lake", "未勘测 —— " + target.name() + "(" + target.label()
                    + ") 的布景不读 JourneyRoute.lavaLake（见 needsTheLavaLake）");
            JourneyLedger.reached(JourneyStage.RECON, "REHEARSAL：只做了排练需要的最小勘测（未勘岩浆湖）",
                    Map.of("rehearsal.lake", "skipped"), level.getGameTime());
            ctx.passNote("REHEARSAL 勘测 — 种子 " + level.getSeed() + "，未勘岩浆湖（"
                    + target.name() + " 用不到）。这不是攀爬。");
            WorldDriverCommon.LOG.info("[rehearsal] lake=skipped target={}", target);
            return;
        }

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
        if (target == JourneyStage.STRONGHOLD) {
            stageStronghold(ctx);
            return;
        }
        if (target == JourneyStage.END_PORTAL) {
            stageEndPortal(ctx);
            return;
        }
        if (target == JourneyStage.END) {
            stageEnd(ctx);
            return;
        }
        if (target == JourneyStage.DRAGON) {
            stageDragon(ctx);
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
        // (Aside on tools: the climb of 2026-08-16 arrived with `stone_pickaxe 131/131` AND
        //  `wooden_pickaxe 59/59`, so its stone head was full too — durability was not what stopped
        //  it, and both bodies carve holding `minecraft:stone_pickaxe`. Left as two stone heads on
        //  purpose; changing it would be a second variable with no measurement behind it.)
        // cells; one tool makes durability a hidden variable in exactly the leg under investigation,
        // and a mine that silently stops because the head snapped looks identical to a mine that
        // could not reach.
        kit.put("minecraft:stone_pickaxe", 2);
        // EMPTY, like a climb's. This used to hand the water over already in the bucket, on the
        // reasoning that rung 12's walk to water re-tests a rung-10 capability at rung 12's expense.
        // That reasoning is sound about COST and wrong about FIDELITY, and the difference cost a round:
        // the climb of 2026-08-16 fills its own bucket here (`waterFill.hand = minecraft:bucket`,
        // `waterFill.result = CONSUME`), and where that trip leaves the body is what decides where it
        // is standing when the carve begins — which is exactly the quantity under investigation, since
        // the rehearsal's carve fails with the body up on the surface (`cell.0.standMissed 停在
        // 3,64,20，脚下 grass_block`) while the climb's on the same geometry carved 67/67.
        //
        // A staging shortcut may skip a walk; it may not skip a walk that DECIDES the thing being
        // measured. The cost is real — the trip to `firstWater 64,62,60` is its own long-standing
        // failure (a ladder run died on it) — so a rehearsal that stops there has been blocked
        // UPSTREAM and has measured nothing about the carve. Read `waterFill.*` before reading the
        // carve rows.
        int buckets = stagedBuckets(ctx);
        kit.put("minecraft:bucket", buckets);
        kit.put("minecraft:flint_and_steel", 1);
        kit.put("minecraft:cobblestone", PORTAL_LIT_COBBLESTONE);
        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        // THE NUMBER, not「对齐了」. A give is a claim about what a climb arrives holding, and the only
        // way to judge it later is against the climb's own measured row — so both go on the record.
        ctx.record("rehearsal.gave", gave.toString()
                + "（圆石 " + PORTAL_LIT_COBBLESTONE + " 与水桶为空，都照真 ladder 2026-08-16 那趟实测的 "
                + "cobblestone.before=111 / bucket.before=1 对齐；仍然故意不同的只剩「镐给两把」，"
                + "理由见 stagePortalLit 里那处注释）");

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
        // NOT「摆在这一侧就朝这一侧」—— that is what this row used to promise and it was false. Rung 12
        // opens by walking to the lava, which discards the staged stand entirely; the orientation is
        // decided later by pickDigColumn. Measured 2026-08-16: east / south / west staged three
        // different stands and all three came out `shaft.standingOn = -9,21`, `forge.face … 朝 south`.
        // So the side is PUBLISHED for that choice to honour, and this row now says which of the two
        // things it is claiming.
        stagedForgeSide = side;
        stagedShaftColumn = forcedShaftColumn(ctx);
        ctx.record("rehearsal.forgeAway", side == null
                ? "自然朝向 " + JourneyPortalRung.awayFrom(lake, stand) + "（没有指定 -PforgeAway）"
                : "指定 " + side + "：落脚点摆在湖的这一侧，并且下挖柱也只在这一侧挑"
                        + "（这一侧挑不出合格柱就退回四周找，看 forge.away 与否决计数）");
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
        // 128 → 384, AND THIS MAKES THE REHEARSAL EASIER THAN A CLIMB. Saying so first because that
        // is the cost: a rehearsal PASS bought with stone the ladder would have had to mine is not
        // evidence the ladder can do this, and this number must not be read as one.
        //
        // What it buys is a MEASUREMENT nothing else can produce. On 2026-08-21 the corridor reached
        // waypoint 9 of 17 and then reported `expanded=100000` — which reads as a pathfinding
        // failure, and was read as one for four rounds. The causeway note said what was actually
        // true: 「直段已经放了 73 格，身上还剩 0 个可放置方块」. A bridge edge needs something to
        // place; with an empty bag there are no bridge edges at all, so the search over open sky has
        // nothing to expand and burns its node cap. 缺料 wearing a search failure's uniform.
        //
        // The corridor's second half is not ground — six of its seventeen waypoints have no floor
        // under them at all, and the three that do have one twenty blocks down under LAVA — so the
        // bridging is intrinsic, not a symptom. The open question is therefore「这条走廊要多少石
        // 头」, and it cannot be answered by a run that runs out: a body that stops at zero measures
        // the allowance, not the requirement. Give it more than it can need, and the number it
        // actually spends is the requirement — which then becomes a claim on rungs 9-13, that the
        // climb must ARRIVE holding that many.
        kit.put("minecraft:cobblestone", 384);
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

    /**
     * Rung 17's starting conditions: eyes in the bag, a body deep in the Nether, and a doorway home
     * that it has to WALK to rather than see.
     *
     * <p>This recipe exists because rung 17's return path had no way to run. The rung's first act is
     * {@code backToTheOverworld}, and on the real ladder it has only ever been reached once, before
     * the return was rewritten — so the march home, {@code stepBackThrough}, and the ledger's banked
     * doorway are all code that has never executed. Without a recipe here, {@code -Prehearse=STRONGHOLD}
     * started the rung on an empty body at world spawn: already in the overworld, so
     * {@code backToTheOverworld} returns on its FIRST branch and the whole return stays untested
     * while the scene reports a pass.
     *
     * <p><b>The doorway is put {@link #STAGED_DOOR_AWAY} blocks away on purpose.</b> Rung 17 scans 24
     * blocks for a portal first and only falls back to the banked coordinate when that misses. A
     * doorway placed next to the body would take the fast path — the one branch that already worked —
     * and the rehearsal would prove nothing about the branch it was built for.
     *
     * <p>Blocks are handed over for the same reason the fortress corridor needs them: the march runs
     * under {@code generousPathfinding}, which leaves {@code allowPlace} on, and a body with nothing
     * to bridge with reports「架不起桥」as「走不到」.
     */
    private static void stageStronghold(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:cooked_beef", 16);
        kit.put("minecraft:ender_eye", EYES_A_CLIMB_ARRIVES_WITH);
        kit.put("minecraft:cobblestone", 128);
        crossToTheNether(ctx, "STRONGHOLD", kit,
                "要塞、回程的路、以及那道门在哪儿，都不告诉这一级 —— 门只记进账本");
        buildTheDoorwayAndBankIt(ctx);
    }

    /** How far the staged doorway sits from the body. Past rung 17's 24-block local scan, so the
     *  return is forced down the BANKED route — see {@link #stageStronghold}. */
    private static final int STAGED_DOOR_AWAY = 96;

    /** A nether portal's interior: two wide, three tall. */
    private static final int DOOR_WIDTH = 2, DOOR_HEIGHT = 3;

    /**
     * Build the doorway rung 13 would have left behind, and bank it the way rung 13 banks it.
     *
     * <p>The portal blocks are set directly rather than lit with flint and steel. That is the same
     * licence {@code openTheDoorLikeVanilla} takes and for the same reason — lighting is rung 12's
     * subject, not this one's — but it carries a risk that one does not: {@code NetherPortalBlock}
     * pops itself off when its frame does not hold, so a frame built wrong yields a portal that is
     * gone by the time the rung walks back to it, and rung 17 would then report
     * {@code 门被毁了}, a true sentence about a world nobody staged correctly.
     *
     * <p><b>So it asserts on the cells after the fact</b>, which is the only reading that can tell
     * "the staging built a portal" from "the staging built something portal-shaped".
     */
    private static void buildTheDoorwayAndBankIt(SceneContext ctx) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— 摆不了回程的门");
            return;
        }
        ServerPlayer fp = body.fakePlayer();
        ServerLevel nether = (ServerLevel) fp.level();
        BlockPos from = fp.blockPosition();
        BlockPos want = new BlockPos(from.getX() + STAGED_DOOR_AWAY, from.getY(), from.getZ());
        loadAround(nether, want, 2);
        BlockPos foot = netherStandNear(nether, want);
        if (foot == null) {
            ctx.fail("排练：身体东边 " + STAGED_DOOR_AWAY + " 格附近找不到能立门的落脚点"
                    + "（想放在 " + want.toShortString() + "）");
            return;
        }
        // Clear the pocket the frame stands in, so worldgen rock does not decide whether the
        // doorway is enterable. One cell of margin all round the 4x5 frame.
        for (int dx = -2; dx <= DOOR_WIDTH + 1; dx++)
            for (int dy = -1; dy <= DOOR_HEIGHT + 2; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    nether.setBlockAndUpdate(foot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
        // The frame: obsidian everywhere on the ring, corners included (vanilla ignores the corners,
        // and filling them keeps this from depending on that).
        for (int dx = -1; dx <= DOOR_WIDTH; dx++)
            for (int dy = -1; dy <= DOOR_HEIGHT; dy++) {
                boolean ring = dx == -1 || dx == DOOR_WIDTH || dy == -1 || dy == DOOR_HEIGHT;
                if (ring) nether.setBlockAndUpdate(foot.offset(dx, dy, 0),
                        Blocks.OBSIDIAN.defaultBlockState());
            }
        // Standing room in front of it, or the body arrives at a doorway it cannot reach.
        for (int dx = -1; dx <= DOOR_WIDTH; dx++)
            nether.setBlockAndUpdate(foot.offset(dx, -1, -1), Blocks.OBSIDIAN.defaultBlockState());
        BlockState door = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int dx = 0; dx < DOOR_WIDTH; dx++)
            for (int dy = 0; dy < DOOR_HEIGHT; dy++)
                nether.setBlock(foot.offset(dx, dy, 0), door, 2);
        // Poke the frame so a neighbour update actually reaches the cells before they are counted.
        // Without this the count is taken in the window BEFORE NetherPortalBlock.updateShape has had
        // a chance to reject the frame — six cells would be reported for a doorway that is gone by
        // the time the rung walks back to it, which is the one failure this assertion exists to catch.
        nether.setBlockAndUpdate(foot.offset(-1, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
        int cells = 0;
        for (int dx = 0; dx < DOOR_WIDTH; dx++)
            for (int dy = 0; dy < DOOR_HEIGHT; dy++)
                if (nether.getBlockState(foot.offset(dx, dy, 0)).is(Blocks.NETHER_PORTAL)) cells++;

        JourneyLedger.staged("rehearsal: built the return doorway at " + foot.toShortString()
                + " and banked it, instead of walking back through one rung 12 lit");
        JourneyLedger.noteNetherPortal(foot);
        long away = Math.round(Math.sqrt(foot.distSqr(from)));
        ctx.record("rehearsal.doorway", foot.toShortString() + " 起 " + DOOR_WIDTH + "×" + DOOR_HEIGHT
                + "，成了 " + cells + " 格 nether_portal；离身体 " + away + " 格"
                + "（17 级先扫 24 格，扫不到才走账本记下的那条路 —— 这里要的就是后者）");
        if (cells < DOOR_WIDTH * DOOR_HEIGHT) {
            ctx.fail("排练立不起门：" + DOOR_WIDTH + "×" + DOOR_HEIGHT + " 只成了 " + cells
                    + " 格 nether_portal —— 这是布景的问题，不是 STRONGHOLD 这一级的问题"
                    + "（多半是框架不闭合，NetherPortalBlock 自己弹掉了）");
            return;
        }
        if (away <= 24) {
            ctx.fail("排练把门放得太近了（" + away + " 格）：17 级的 24 格局部扫描会直接扫到它，"
                    + "走的是快路径，而这套布景存在的意义正是逼它走账本那条路");
        }
    }

    /** How many blaze rods a fortress trip is worth. Seven — see {@link #stageEnderPearl}. */
    private static final int RODS_A_FORTRESS_PAYS = 7;

    /** A portal frame's worth of eyes; the copy of {@code JourneyEndRungs.EYES_A_PORTAL_COSTS} this
     *  file is allowed to have, for the same reason {@link #PORTAL_FRAME_CELLS} is duplicated: it is
     *  the number the STAGING has to pay, not the number the rung asserts. */
    private static final int EYES_A_PORTAL_COSTS = 12;

    /** How many frames one stronghold portal room has — the ring
     *  {@code EndPortalFrameBlock.getOrCreatePortalShape()} matches. The staging asserts on this
     *  before it claims to have put the body「in the room」: eleven frames is not a room, it is a
     *  scan whose box clipped one. */
    private static final int FRAMES_A_ROOM_HAS = 12;

    /** {@code JourneyEndRungs.ROOM_SCAN_CHUNKS} as it stands. Duplicated on the same licence as
     *  {@link #PORTAL_FRAME_CELLS}, and paying EXACTLY it is the point — see {@link #roomScanChunks}. */
    private static final int STRONGHOLD_ROOM_SCAN_CHUNKS = 6;

    /** What rung 16 hands over, measured on ITS rehearsal (not on a climb). See {@link #stagedEyes}. */
    private static final int EYES_A_CLIMB_ARRIVES_WITH = 12;

    /**
     * Rung 18's starting conditions: a body standing in the stronghold's portal room, holding eyes.
     *
     * <p>Rung 18 is twelve {@code useOn}-only interactions and nothing else. Everything ELSE about
     * the rung is somebody else's: finding the stronghold is rung 17's, making the eyes is rung
     * 16's. So this recipe stages exactly those two and stages <b>nothing about the frames</b>.
     *
     * <p><b>Not one frame is pre-filled and the portal is not opened.</b> Whatever eyes the room
     * already has are the world's own — vanilla pre-fills each of the twelve with probability 0.1 —
     * and {@code rehearsal.framesWithEye} records the count so that {@code ender_eye.left} at the
     * end is a cross-check rather than a number nobody can read.
     *
     * <p><b>No cobblestone, deliberately.</b> {@code generousPathfinding} leaves
     * {@code allowPlace = true} and rung 18 never turns it off, so a bag of blocks lets the walker
     * pillar to a frame — which replaces「walk to within EYE_REACH」, the thing under test, with
     * something else. What a climb actually arrives here carrying is unknown: rung 17 records no
     * inventory at all, so there is no key to calibrate against yet. Adding a {@code stock.*}
     * evidence row to rung 17 is the fix, and it belongs to whoever writes rung 17's recipe.
     *
     * <p><b>Every number here is PROVISIONAL.</b> No climb has ever reached rung 16, so none of them
     * has a ladder measurement behind it; {@code rehearsal.gave} says so in the results file rather
     * than only here.
     */
    private static void stageEndPortal(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:ender_eye", stagedEyes(ctx));
        // Two, and the reason is the one stagePortalLit already paid for: rung 17 sinks a shaft
        // into the stronghold and a climb arrives here with a WORN head. A rehearsal that handed
        // over one fresh stone pickaxe would be reproducing an inventory no climb has.
        kit.put("minecraft:stone_pickaxe", 2);
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:cooked_beef", 16);
        ctx.record("rehearsal.noBlocks", "没给圆石 —— 这一级不放置任何方块，而 allowPlace 是开着的；"
                + "给了石料就等于允许 walker 垒到框架跟前，把「走进 EYE_REACH」换成别的事。"
                + "真梯到这一级带多少石料今天查不到（17 级没记库存）—— 校准要先给 17 级加 stock.* 行");
        standInThePortalRoom(ctx, "END_PORTAL", kit,
                "框架一格没补、门也没开 —— 那两件正是这一级要证明的事");
    }

    /**
     * Put the body in the stronghold's portal room, with a bag, and arrange nothing else.
     *
     * <p>Shared the way {@code crossToTheNether} is shared by rungs 14/15/16: what differs between
     * rung 18 and rung 19 is the bag and what the room already contains, not how a body gets there.
     *
     * <p><b>The frames are read through {@link JourneyEndRungs#framesAround} rather than through a
     * second scan written here.</b> Two scans that disagree put「the room the staging found」and
     *「the room the rung found」in different places, and there is no reading that tells them apart
     * afterwards. This file is licensed to duplicate CONSTANTS with a stated reason (see
     * {@link #PORTAL_FRAME_CELLS}); an algorithm is not the same licence.
     *
     * @return the frame ring's centre, which rung 19's recipe will need — unused by rung 18
     */
    private static BlockPos standInThePortalRoom(SceneContext ctx, String what,
                                                 Map<String, Integer> kit, String notStaged) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return null;
        }
        ServerLevel level = ctx.level();
        ServerPlayer fp = body.fakePlayer();
        BlockPos baked = JourneyRoute.stronghold;

        // Generate before scanning, and TIME it. framesAround loads what it reads on its own, so
        // this is not needed for correctness — it is needed so the price of a kilometre-out block
        // of fresh chunks lands in one recorded number instead of hiding inside a call that looks
        // like it is only reading.
        int scan = roomScanChunks(ctx);
        long startedNs = System.nanoTime();
        loadAround(level, baked, scan);
        List<BlockPos> frames = JourneyEndRungs.framesAround(level, baked, scan);
        long ms = (System.nanoTime() - startedNs) / 1_000_000L;
        ctx.record("rehearsal.roomScanMs", ms + " ms（" + (2 * scan + 1) + "×" + (2 * scan + 1)
                + " 区块，全新世界）");
        ctx.record("rehearsal.frames", frames.size() + " 格 end_portal_frame（以烘入的 stronghold "
                + baked.toShortString() + " 为心，±" + (scan * 16) + " 格）");
        if (frames.size() < FRAMES_A_ROOM_HAS) {
            ctx.fail("排练摆不出传送门房间：以 " + baked.toShortString() + " 为心 ±" + (scan * 16)
                    + " 格内只有 " + frames.size() + " 格 end_portal_frame，一间房要 "
                    + FRAMES_A_ROOM_HAS + " 格 —— 这是布景的问题，不是 " + what + " 这一级的问题。"
                    + "0 格 = 烘入的坐标过期，或半径远不够；1..11 格 = 半径把房间切了。"
                    + "用 -ProomScanChunks=12 再跑一次分辨这两者");
            return null;
        }
        BlockPos centre = JourneyEndRungs.centreOf(frames);
        int away = Math.max(Math.abs(centre.getX() - baked.getX()),
                            Math.abs(centre.getZ() - baked.getZ()));
        ctx.record("rehearsal.frameCentre", centre.toShortString() + "，距烘入的 stronghold "
                + away + " 格（切比雪夫）—— rung 17 的 ROOM_SCAN_CHUNKS 至少要 " + (away / 16 + 1)
                + " 才扫得到，它现在是 " + STRONGHOLD_ROOM_SCAN_CHUNKS);
        BlockPos stand = JourneyEndRungs.standingCellInTheRoom(level, centre);
        if (stand == null) {
            ctx.fail("排练：扫到了 " + frames.size() + " 格框架（中心 " + centre.toShortString()
                    + "）却没有能落脚的格子 —— 这是布景的问题，不是 " + what + " 这一级的问题");
            return null;
        }
        int withEye = 0;
        for (BlockPos f : frames) if (JourneyEndRungs.hasEye(level, f)) withEye++;
        ctx.record("rehearsal.framesWithEye", withEye + "/" + frames.size()
                + "（世界自带的，一格都没补 —— 收尾时 ender_eye.left 应该等于这个数）");

        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        // THE NUMBERS AND WHERE THEY CAME FROM. Every one is PROVISIONAL: no climb has ever reached
        // rung 16, so none has a ladder measurement behind it and the record must not read as if it
        // did — see the ladder-calibration table in TODO.md for which key replaces which number.
        ctx.record("rehearsal.gave", gave + "（全部 PROVISIONAL：真梯从未爬到 12 级以上，"
                + "这些数没有 ladder 实测。ender_eye 照 16 级排练实测的 " + EYES_A_CLIMB_ARRIVES_WITH
                + "；镐 2 把是故意与真梯不同，理由见 stageEndPortal 里那处注释）");

        loadAround(level, stand, 2);
        JourneyLedger.staged("rehearsal: put the body in the stronghold portal room at "
                + stand.toShortString() + " instead of marching there and sinking a shaft");
        fp.setDeltaMovement(Vec3.ZERO);
        fp.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, fp.getYRot(), fp.getXRot());
        fp.setOnGround(true);
        ctx.record("rehearsal.stand", stand.toShortString() + "，距框架中心 "
                + Math.round(Math.sqrt(stand.distSqr(centre))) + " 格（" + notStaged + "）");
        WorldDriverCommon.LOG.info("[rehearsal] staged {}: gave {} and stood the body at {} ({} frames)",
                what, gave, stand, frames.size());
        return centre;
    }

    /** How many {@code end_portal} cells one open door is. Vanilla lays a 3×3 and nothing else does,
     *  so this is what the STAGING must produce before it may claim the door is open — the same
     *  duplication licence as {@link #PORTAL_FRAME_CELLS}, not a number any rung asserts. */
    private static final int PORTAL_CELLS_A_DOOR_HAS = 9;

    /**
     * Rung 19's starting conditions: the same room as rung 18, with the door already open.
     *
     * <p>Rung 19 is「step into an open end portal and come out on the End's arrival platform」. So
     * what this stages is exactly the one thing rung 18 produces — an open door — and it stages it
     * <b>through vanilla's own code path, not through the verb rung 18 is tested on</b>. See
     * {@link #openTheDoorLikeVanilla} for why that distinction is the whole recipe.
     *
     * <p><b>No cobblestone, for the same reason rung 18 gets none, and it costs more here.</b> The
     * stronghold's nine portal cells sit over the room's lava pool, and rung 19's own failure message
     *（{@code 要塞的门开在熔岩池上方，走进去和站到旁边是两码事}）is a finding it must be able to
     * report. {@code generousPathfinding} leaves {@code allowPlace = true}, so a bag of blocks lets
     * the walker bridge over that pool — which would silently convert「the walker can reach a portal
     * cell」into「the walker can build a path to one」and make the rung permanently unable to report
     * the thing it exists to catch. What a climb actually arrives with is unknown: rung 19 records no
     * inventory, so there is no key to calibrate against and the fix is a {@code stock.*} row on rung
     * 19, exactly as for rung 17.
     *
     * <p><b>Every number here is PROVISIONAL</b> — no climb has reached rung 16, let alone 19.
     */
    private static void stageEnd(SceneContext ctx) {
        Map<String, Integer> kit = new LinkedHashMap<>();
        // The same bag rung 18 gets, because rung 18 is what would have handed it over and rung 18
        // spends nothing but eyes. The eyes themselves are NOT handed over: they are spent by then,
        // and giving them back would let a reader mistake this for a rehearsal of rung 18.
        kit.put("minecraft:stone_pickaxe", 2);
        kit.put("minecraft:iron_sword", 1);
        kit.put("minecraft:cooked_beef", 16);
        ctx.record("rehearsal.noBlocks", "没给圆石 —— 门开在熔岩池上方，而 allowPlace 是开着的；"
                + "给了石料就等于允许 walker 在池子上架桥，把「走得到门格」换成「造得出通往门格的路」，"
                + "这一级最该报的那个发现就永远报不出来了。真梯到这一级带多少石料今天查不到"
                + "（19 级没记库存）—— 校准要先给 19 级加 stock.* 行");
        BlockPos centre = standInThePortalRoom(ctx, "END", kit,
                "门是布景开的，走进去、活着到达降落台是这一级自己的事");
        if (centre == null) return;
        openTheDoorLikeVanilla(ctx, centre);
    }

    /**
     * Open the end portal the way <b>vanilla</b> opens it, which is emphatically not the way rung 18
     * opens it.
     *
     * <h2>Why the path matters more than the result</h2>
     *
     * Rung 18's entire subject is {@code Avatar.useBlock} → {@code EnderEyeItem.useOn}: that a DRIVEN
     * BODY can spend an eye into a frame. If this staging opened the door by driving the avatar, then
     * a rehearsal of rung 19 would be running rung 18's tested verb as scenery — and a staging that
     * performs the thing another rung is judged on has stopped being staging. Worse, it would be
     * silently load-bearing in the wrong direction: a regression in {@code useBlock} would fail rung
     * 19's SETUP, which reads as「rung 19 is broken」.
     *
     * <p>So the eyes go in as block state and the door is opened by the <b>tail of
     * {@code EnderEyeItem.useOn} itself</b> — {@code EndPortalFrameBlock.getOrCreatePortalShape()
     * .find(...)}, then vanilla's own {@code getFrontTopLeft().offset(-3, 0, -3)} 3×3 of
     * {@code END_PORTAL}. Copying the tail rather than re-deriving where the door goes is deliberate:
     * a hand-rolled「the door is the 3×3 inside the ring」puts it in the right place for a ring the
     * staging laid itself and in the WRONG place for a ring worldgen laid, and no reading afterwards
     * tells the two apart.
     *
     * <p>The two {@code levelEvent} broadcasts vanilla also makes (1503 and 1038) are left out on
     * purpose: they carry no world state, and a staging must not be judgeable by an effect packet.
     *
     * <p><b>It asserts, because a door that did not open is a staging failure and must not reach the
     * rung as one of its own.</b> If the pattern does not match, or fewer than
     * {@link #PORTAL_CELLS_A_DOOR_HAS} cells came out, this fails naming the staging — otherwise rung
     * 19 reports {@code 身边 12 格内没有 end_portal 方块}, which is a true sentence about a world
     * nobody staged correctly and reads as a bug in rung 18.
     */
    private static void openTheDoorLikeVanilla(SceneContext ctx, BlockPos centre) {
        ServerLevel level = ctx.level();
        // One chunk each way round the ring's own chunk. The ring spans five blocks, so this cannot
        // miss it and cannot reach a second room — a stronghold has one.
        List<BlockPos> frames = JourneyEndRungs.framesAround(level, centre, 1);
        int lit = 0;
        BlockPos last = null;
        for (BlockPos f : frames) {
            BlockState was = level.getBlockState(f);
            if (!was.hasProperty(EndPortalFrameBlock.HAS_EYE)) continue;
            last = f;
            if (was.getValue(EndPortalFrameBlock.HAS_EYE)) continue;
            // The four lines EnderEyeItem.useOn runs per eye, minus the item shrink and the effect.
            BlockState now = was.setValue(EndPortalFrameBlock.HAS_EYE, true);
            Block.pushEntitiesUp(was, now, level, f);
            level.setBlock(f, now, 2);
            level.updateNeighbourForOutputSignal(f, Blocks.END_PORTAL_FRAME);
            lit++;
        }
        JourneyLedger.staged("rehearsal: set " + lit + " eyes as block state and ran the tail of "
                + "EnderEyeItem.useOn, instead of driving the avatar's useBlock (that is rung 18)");
        ctx.record("rehearsal.eyesSet", lit + " 只（直接写 HAS_EYE，没走 Avatar.useBlock —— "
                + "那是 18 级的被测动作，布景不许替它做）");
        BlockPattern.BlockPatternMatch match = last == null ? null
                : EndPortalFrameBlock.getOrCreatePortalShape().find(level, last);
        if (match == null) {
            ctx.fail("排练开不了门：" + frames.size() + " 格框架全填了眼，"
                    + "EndPortalFrameBlock.getOrCreatePortalShape().find(" + xyzOf(last) + ") 仍不匹配"
                    + " —— 这是布景的问题，不是 END 这一级的问题（多半是扫到的框架不属于同一个环）");
            return;
        }
        BlockPos topLeft = match.getFrontTopLeft().offset(-3, 0, -3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                level.setBlock(topLeft.offset(i, 0, j), Blocks.END_PORTAL.defaultBlockState(), 2);
        int cells = 0;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (level.getBlockState(topLeft.offset(i, 0, j)).is(Blocks.END_PORTAL)) cells++;
        JourneyLedger.staged("rehearsal: opened the end portal at " + topLeft.toShortString()
                + " instead of walking the stronghold and spending twelve eyes");
        ctx.record("rehearsal.doorway", topLeft.toShortString() + " 起 3×3，开出 " + cells + " 格 "
                + "end_portal（vanilla 的 getFrontTopLeft().offset(-3,0,-3)，不是自己算的中心）");
        if (cells < PORTAL_CELLS_A_DOOR_HAS) {
            ctx.fail("排练开不全门：3×3 只成了 " + cells + "/" + PORTAL_CELLS_A_DOOR_HAS
                    + " 格 end_portal —— 这是布景的问题，不是 END 这一级的问题");
        }
    }

    /** {@code toShortString} that survives a null, for a failure message whose whole job is to be
     *  readable when something upstream returned nothing. */
    private static String xyzOf(BlockPos pos) { return pos == null ? "无框架" : pos.toShortString(); }

    /** How many blocks rung 20 starts with. See {@link #stageDragon} for the arithmetic; the point of
     *  the number is that it is roughly TWICE the worst case, so that「跑到一半没方块了」can never be
     *  the thing that decides a rehearsal of the bridge and the towers. */
    private static final int BLOCKS_A_DRAGON_TRIP_NEEDS = 3_072;

    /**
     * Rung 20's starting conditions: a body on the End's arrival platform, with something to bridge
     * with.
     *
     * <h2>The platform is built by vanilla's own feature, not by a teleport</h2>
     *
     * {@code EndPortalBlock.getPortalDestination} does two things when it sends a body to the End,
     * and a staging that copies only the second one drops the body into the void:
     *
     * <pre>
     * EndPlatformFeature.createEndPlatform(end, BlockPos.containing(END_SPAWN_POINT.getBottomCenter()).below(), true);
     * vec3 = END_SPAWN_POINT.getBottomCenter().subtract(0, 1, 0);   // for a ServerPlayer
     * yRot = Direction.WEST.toYRot();
     * </pre>
     *
     * Both are reproduced here, from that call verbatim, because the platform is a 5×5 of obsidian
     * that <b>does not exist in a freshly generated End</b> — vanilla builds it at arrival time.
     *
     * <p><b>⚠️ THE PLATFORM THIS BUILDS IS RUNG 20'S PRECONDITION, NEVER RUNG 19'S OUTPUT.</b> The two
     * read identically in a results file — a body standing on obsidian at (100, 49, 0) — and they
     * mean opposite things. Rung 19 is judged on whether the CROSSING put it there; this staging puts
     * it there so that rung 20 can start. On 2026-08-17 rung 19 passed with the body 4426 blocks down
     * the void, and a rehearsal of rung 20 would have been green over that same defect on the same
     * afternoon, because it never asks the question — it lays the floor itself. So a green
     * {@code wd.rehearse20Dragon} is evidence about the dragon and about nothing upstream of it, and
     * anyone reading「20 级过了，所以进末地是好的」has read this row backwards.
     *
     * <h2>The landing cell is pinned, and that is a correctness requirement rather than tidiness</h2>
     *
     * {@code EndDragonFight.validPlayer} is
     * {@code EntitySelector.withinDistance(0, 128, 0, 192.0)}, and {@code tick()} does <b>nothing at
     * all</b> — no arena ticket, no {@code scanState}, no {@code createNewDragon} — while no valid
     * player is in range. {@code END_SPAWN_POINT} (100, 50, 0) is 126.8 blocks from (0, 128, 0), so a
     * body that lands there counts. Move it a few dozen blocks out「for a shorter bridge」and the
     * dragon is never created — at which point rung 20 prints its {@code FakePlayer 不在玩家表里}
     * diagnostic, <b>which would then be a false statement</b>: the body would be in the list and
     * merely out of range. A staging that can make an existing diagnostic lie is worse than no
     * staging, so the cell is fixed and the distance is recorded next to its own threshold.
     *
     * <h2>The player list is read, not fixed</h2>
     *
     * The other half of the same mechanism is that the body must be in {@code level.players()} at
     * all, which only {@code JoinedPlayerBodies} ({@code -Dworlddriver.realPlayerBodies=true}) does;
     * {@code runRehearsalServer} already sets it. This records the answer rather than asserting it,
     * on purpose: when the flag is off, rung 20's own {@code noDragonHere} is <b>correct</b> and
     * costs only {@link JourneyEndRungs} {@code DRAGON_WAIT_TICKS} to reach, and exercising a true
     * diagnostic is worth more than short-circuiting it here.
     *
     * <h2>⚠️ The bridge may not be the mechanism under test at all</h2>
     *
     * This recipe's own note used to say「架桥和爬塔是这一级自己的事」, and the first half of that was
     * an assumption nobody had measured. Sampled 2026-08-17 along the plan the walker actually made
     * from the arrival platform: <b>every node had solid ground under it and every chunk was
     * loaded</b> — {@code [0]100,49,0 下方实心 … [16]51,57,0 下方实心}, with eight {@code stepUp}
     * edges climbing y 49→57. The planner was not routing over the void; it was routing up a real
     * slope toward the island.
     *
     * <p>So「从降落台到主岛之间是虚空,过去要架桥」is not established for this seed and this
     * platform. If the ground is in fact continuous, the CORRECT outcome of this leg is that the body
     * <b>walks</b> there, and a run that bridges would be doing unnecessary work rather than passing.
     * The blocks below are therefore stock against a gap that may or may not exist — not a
     * declaration that one does.
     *
     * <h2>Why 1024 blocks, and why not fewer</h2>
     *
     * Worst case is about 60 blocks of bridge from x=100 to the island's edge plus ten spikes at
     * roughly 40 blocks of tower each — call it 460. The rule against staging「just barely enough」
     * bites hardest here, because a shortfall does not report itself as a shortfall: it reports as
     * {@code 走不到主岛中央} or a tower that stops early, i.e. as a bug in the two mechanisms this
     * rung exists to exercise. Only cobblestone is handed over so that {@code pillarBlock} is
     * deterministic — it picks whichever of {@code PILLAR_BLOCKS} the body carries most of.
     *
     * <p><b>Every number here is PROVISIONAL and the shape of the bag is a guess</b>: no climb has
     * reached rung 16. The calibration key is a {@code stock.*} row on rung 19, which does not exist
     * yet either.
     */
    private static void stageDragon(SceneContext ctx) {
        ServerWorldDriver body = JourneyRig.bodyOrNull();
        if (body == null) {
            ctx.fail("排练：没有身体 —— wd.rehearse02Spawn 没有创建 avatar");
            return;
        }
        ServerPlayer fp = body.fakePlayer();
        ServerLevel end = ctx.level().getServer().getLevel(net.minecraft.world.level.Level.END);
        if (end == null) {
            ctx.fail("排练：这个运行时没有末地维度（数据包移除了 minecraft:the_end）—— "
                    + "布景摆不出 DRAGON 的起点");
            return;
        }

        BlockPos platform = BlockPos.containing(ServerLevel.END_SPAWN_POINT.getBottomCenter()).below();
        loadAround(end, platform, 2);
        // Vanilla's own call, arguments included. A fresh End has no arrival platform: the 5x5 of
        // obsidian is built at arrival time by EndPortalBlock, so a body teleported to the same
        // coordinates without this falls through the void and the rung reports a walk that failed.
        EndPlatformFeature.createEndPlatform(end, platform, true);
        JourneyLedger.staged("rehearsal: built the End arrival platform at " + platform.toShortString()
                + " with EndPlatformFeature.createEndPlatform, the call EndPortalBlock makes");

        // ORDER MATTERS, and it is not a style choice. A player inventory is 36 slots; the block
        // budget alone is BLOCKS_A_DRAGON_TRIP_NEEDS/64 stacks, so anything handed over AFTER it has
        // nowhere to go and is silently dropped on the floor. Raising the budget to 3072 (48 stacks)
        // did exactly that: the bow and 256 arrows never entered the bag, and the fight reported
        // 「进入远程分支 0 tick，箭存量 0，手上=minecraft:iron_sword」 — a ranged half that had been
        // fixed three times and could not have worked whatever the code said. The tools go in first
        // and the bulk last, so an overflow can only ever cost blocks, which the run counts.
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("minecraft:iron_sword", 1);
        // Carried for parity with the recipes below, not for a reading: this body is invulnerable and
        // never hungers, so nothing in rung 20 consumes it.
        kit.put("minecraft:cooked_beef", 16);
        // A bow, because the dragon spends almost all of this fight out of melee reach and vanilla
        // quarters every hit that is not on the head. Measured without one: 4000 consecutive ticks
        // with the head never inside 4.5, and the single body hit that did land moved 200.0 -> 198.8.
        // PROVISIONAL like everything else here — the real ladder would have to earn string from
        // spiders — and listed in rehearsal.gave so it can never be mistaken for something climbed.
        kit.put("minecraft:bow", 1);
        kit.put("minecraft:arrow", 256);
        // ...and the bulk last, capped at what actually fits beside them. 36 slots minus the four
        // the tools occupy is 32 stacks = 2048; asking for more does not carry more, it only makes
        // the shortfall invisible.
        kit.put("minecraft:cobblestone",
                Math.min(BLOCKS_A_DRAGON_TRIP_NEEDS, (36 - kit.size() - 1) * 64));
        StringBuilder gave = new StringBuilder();
        for (var e : kit.entrySet()) {
            give(fp, e.getKey(), e.getValue());
            if (gave.length() > 0) gave.append(' ');
            gave.append(e.getKey().substring(e.getKey().indexOf(':') + 1)).append('×').append(e.getValue());
        }
        JourneyLedger.staged("rehearsal: gave " + gave);
        ctx.record("rehearsal.gave", gave + "（全部 PROVISIONAL：真梯从未爬到 12 级以上。"
                + "圆石 " + BLOCKS_A_DRAGON_TRIP_NEEDS + " —— 原本是 1024，按「约 60 格架桥 + 10 座塔"
                + " × 约 40」的两倍估的，2026-08-18 起不再成立：路径规划现在拒绝在可架桥时跨越虚空"
                + "（Move.overTheVoid）与两角皆空的对角线，于是每一道过去靠跳过去的缺口都改成了架桥。"
                + "实测一趟打完水晶后 holding=11，塔因此报 stuck 而不是报缺料 —— 缺料不会说自己缺料，"
                + "它会伪装成走不动或塔停住，那正是这一级要考的两个机制"
                + "的两倍上下 —— 缺料不会报成缺料，会报成「走不到主岛」或塔提前停，"
                + "那正是这一级要考的两个机制；只给圆石是为了让 pillarBlock 的选择确定）");

        Vec3 land = ServerLevel.END_SPAWN_POINT.getBottomCenter().subtract(0, 1, 0);
        fp.setDeltaMovement(Vec3.ZERO);
        // Where the body was standing WHEN IT LEFT, in the terms the swim branches read. A cross-
        // dimension teleport does not recompute the fluid flags — those are written by baseTick, and
        // the walker ticks BEFORE the avatar's step — so whatever is true here is what WalkerTickDrive
        // sees on its first tick in the End. Measured 2026-08-17: `支=swimColumn 水=true 没顶=true`
        // fired a jump on the dry obsidian platform, and the flags read false again seven ticks later.
        // Recorded on BOTH sides of the teleport, because a flag that was already false before it
        // moves the question somewhere else entirely.
        String wetBefore = fp.level().dimension().location() + " 水=" + fp.isInWater()
                + " 没顶=" + fp.isUnderWater() + " 脚格="
                + fp.level().getBlockState(fp.blockPosition()).getBlock();
        fp.teleportTo(end, land.x, land.y, land.z, java.util.Set.of(),
                Direction.WEST.toYRot(), fp.getXRot());
        fp.setOnGround(true);
        String wetOnArrival = "水=" + fp.isInWater() + " 没顶=" + fp.isUnderWater();
        loadAround(end, fp.blockPosition(), 2);
        // MAKE THE FIXTURE DO WHAT THE PORTAL DOES. On the real ladder the crossing happens inside
        // Entity.baseTick() — handlePortal() first, then updateInWaterStateAndDoFluidPushing() and
        // updateFluidOnEyes() a few lines later — so a body that walks through the End portal has its
        // fluid flags recomputed AT THE DESTINATION, in the same tick. Those two calls are the ONLY
        // writers of wasTouchingWater/wasEyeInWater, and baseTick reaches this body only through
        // ServerPlayerAvatar.step(), which does not run while the driver is unregistered — which is
        // exactly when staging runs. So a fixture teleport left the flags frozen at whatever the body
        // last saw: measured 2026-08-17, rung 20 arrived on the dry obsidian platform still reading
        // 水=true 没顶=true from an overworld pool, and WalkerTickDrive's `swimColumn` (both of whose
        // terms are body flags, so it never reads the world when isUnderWater is set) fired a 0.42 on
        // the platform. The body was still airborne seven ticks later when the parkour edge came up,
        // the ground gate correctly refused it, and it walked into the void.
        //
        // This is a FIXTURE bug, not a product one — the portal path recomputes and rung 20 is not
        // supposed to be testing a teleport. Refreshing here removes the artefact without hiding it:
        // rehearsal.wetOnDeparture still records what the body carried out of the world it left.
        // AFTER loadAround, deliberately: updateFluidHeightAndDoFluidPushing returns early on an
        // unloaded chunk, which would report "in no fluid at all" and re-freeze a wrong answer.
        //
        // baseTick() and not the two update* methods: both of those are protected/private on Entity,
        // and reaching them would mean widening product visibility to fix a fixture. baseTick is also
        // the FAITHFUL call — it is the one the portal crossing itself runs, and the one
        // ServerPlayerAvatar.step() runs every tick, so this stages no behaviour the driver does not
        // already perform on the body once a tick.
        fp.baseTick();
        ctx.record("rehearsal.wetOnDeparture", wetBefore + " → 落地时 " + wetOnArrival
                + " → 重算后 水=" + fp.isInWater() + " 没顶=" + fp.isUnderWater() + " 脚格="
                + end.getBlockState(fp.blockPosition()).getBlock());
        // The record above is not an assertion, so make the fixture refuse to hand rung 20 a body
        // whose flags disagree with the world it is standing in. A rehearsal that stages a defect
        // reports it as the rung's, and this one cost a full round of investigation before the two
        // readings sat side by side.
        if (fp.isInWater() || fp.isUnderWater())
            ctx.fail("rehearsal staging: the body reached the End platform still reporting 水="
                    + fp.isInWater() + " 没顶=" + fp.isUnderWater() + " while standing in "
                    + end.getBlockState(fp.blockPosition()).getBlock()
                    + ". The fixture's teleport left the fluid flags frozen — rung 20 would be"
                    + " judging a staging artefact, not the rung.");
        JourneyLedger.staged("rehearsal: put the body on the End arrival platform at "
                + fp.blockPosition().toShortString() + " instead of stepping through a portal");
        ctx.record("rehearsal.stand", fp.blockPosition().toShortString() + " @ "
                + fp.level().dimension().location() + "（脚下 "
                + end.getBlockState(fp.blockPosition().below()).getBlock() + "，"
                + "水晶、龙、主岛一律没有布景 —— 走过去（或架桥过去）和爬塔都是这一级自己的事。"
                + "⚠️「中间是虚空、必须架桥」未经证实：2026-08-17 沿计划采样，每个节点下方都是实心地面，"
                + "地面若真是连的，这一段的正确结局是走过去，不是架桥）");

        // THE TWO READINGS THAT DECIDE WHETHER THIS RUNG HAS AN OPPONENT AT ALL. Neither is asserted:
        // both failure modes have a correct diagnostic inside rung 20 already, and both are cheap to
        // reach. What they must not do is stay unrecorded, because "no dragon" has two causes that
        // look identical in the results file.
        double away = Math.sqrt(fp.distanceToSqr(0.0, 128.0, 0.0));
        // ⚠️ A STAGING-TIME MEASUREMENT, and it says so in its own text. Nothing keeps it true: the
        // body moves, and on 2026-08-17 it moved 23 000 blocks below the island, at which point this
        // row still read「在范围内，龙会被创建」while the range was the actual cause of
        // dragonUUID=null. The failure-time re-read is JourneyEndRungs.fightRangeNow, published as
        // dragon.rangeNow — this row must never be the one a reader uses to rule the distance out.
        ctx.record("rehearsal.fightRange", String.format(java.util.Locale.ROOT,
                "【布景时刻测的，之后不再成立 —— 失败时看 dragon.rangeNow】"
                        + "距 (0,128,0) %.1f 格，EndDragonFight.validPlayer 的门限是 192 —— %s", away,
                away <= 192.0 ? "在范围内，龙会被创建" : "超了：龙永远不会出现，而 rung 20 会打出"
                        + "「FakePlayer 不在玩家表里」那句话，在这里那句话是错的"));
        ctx.record("rehearsal.inPlayerList", end.players().contains(fp) + "（level.players() 有 "
                + end.players().size() + " 人）—— false 时 EndDragonFight.tick 每 20 tick 扫一次"
                + "空表、什么都不做，要 -Dworlddriver.realPlayerBodies=true（JoinedPlayerBodies）");
        WorldDriverCommon.LOG.info("[rehearsal] staged DRAGON: gave {}, platform {}, body {} inList={}",
                gave, platform, fp.blockPosition(), end.players().contains(fp));
    }

    /**
     * How many eyes rung 18 starts with — twelve, and twelve is a MEASUREMENT, not a fit.
     *
     * <p>Rung 16's rehearsal ended {@code 末影之眼 ×12（够一套门）}, so twelve is what the rung below
     * actually hands over. It is emphatically <b>not</b>「as many as there are empty frames」and must
     * never become that: vanilla pre-fills each of the twelve with probability 0.1, so a staging that
     * matched the empty count would make {@code ender_eye.left} unreadable. Left at twelve,
     * {@code ender_eye.left} measures what the WORLD pre-filled and can be checked against
     * {@code rehearsal.framesWithEye} — a cross-check instead of a tautology, <b>but ONLY WHILE THE
     * BAG IS ENOUGH</b>. Measured 2026-08-17, both halves came back identical:
     * {@code -Peyes=12} ended {@code ender_eye.left = 0} because twelve were spent on twelve empty
     * frames, and {@code -Peyes=8} ended {@code ender_eye.left = 0} because eight were spent on the
     * first eight. <b>One reading, two mechanisms</b> — a bag that fitted and a bag that ran dry are
     * indistinguishable in it — so {@code left} may never be read on its own. What does tell them
     * apart is {@code eyes.ranOutAt} (written only when the bag ran dry) and {@code frames.filled};
     * the cross-check against {@code rehearsal.framesWithEye} is valid only when neither says short.
     * (Rung 16's own recipe
     * has the tautology this avoids: it hands over exactly {@link #EYES_A_PORTAL_COSTS} pearls, so
     * {@code ender_eye.shortfall = 0} is arithmetic rather than a finding.)
     *
     * <p>What twelve DOES make unreachable is the short-stock branch. That is code nobody has run,
     * and this lever is the only thing that runs it, on exactly the discipline of {@code stagedBuckets}:
     * <b>judge it by {@code eyes.ranOutAt} and {@code frames.filled}, never by the colour.</b> A short
     * run SHOULD end red at {@code portal.cells = 0}; what is being tested is that it says so in the
     * right words.
     *
     * <pre>./gradlew :fabric:runRehearsalServer -Prehearse=END_PORTAL -Peyes=8</pre>
     */
    private static int stagedEyes(SceneContext ctx) {
        String raw = System.getProperty("worlddriver.journey.eyes", "").trim();
        if (raw.isEmpty()) return EYES_A_CLIMB_ARRIVES_WITH;
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            ctx.record("rehearsal.eyes", raw + " 不是数字 —— 按 " + EYES_A_CLIMB_ARRIVES_WITH
                    + " 只摆（16 级排练实测的数）");
            return EYES_A_CLIMB_ARRIVES_WITH;
        }
        if (n == EYES_A_CLIMB_ARRIVES_WITH) return n;
        JourneyLedger.staged("rehearsal: gave " + n + " eyes instead of the " + EYES_A_CLIMB_ARRIVES_WITH
                + " rung 16 measured, to reach the short-stock branch");
        ctx.record("rehearsal.eyes", n + " 只（16 级排练实测是 " + EYES_A_CLIMB_ARRIVES_WITH
                + "）—— 这是为了跑到「眼不够」那条分支；判据是 eyes.ranOutAt 出现且 frames.filled < 12，"
                + "不是颜色");
        return Math.max(0, n);
    }

    /**
     * How wide the STAGING's own frame scan is.
     *
     * <p>Defaults to {@link #STRONGHOLD_ROOM_SCAN_CHUNKS}, i.e. to rung 17's own
     * {@code ROOM_SCAN_CHUNKS}, and paying exactly that number is the whole point: this staging
     * makes the same call rung 17 makes, on the same centre, at the same radius, so what it finds is
     * what rung 17 will find. A rehearsal of rung 18 therefore answers rung 17's open question —
     * <b>is ±96 blocks from the {@code /locate} START piece enough to reach the portal room?</b> —
     * in minutes instead of in an hour of marching.
     *
     * <p><b>The lever widens the STAGING scan only; rung 17 keeps its constant untouched.</b> If it
     * raised the rung's radius too, that question could never be answered「no」— the widening would
     * hide the very shortfall it was raised to measure.
     *
     * <pre>./gradlew :fabric:runRehearsalServer -Prehearse=END_PORTAL -ProomScanChunks=12</pre>
     */
    private static int roomScanChunks(SceneContext ctx) {
        String raw = System.getProperty("worlddriver.journey.roomScanChunks", "").trim();
        if (raw.isEmpty()) return STRONGHOLD_ROOM_SCAN_CHUNKS;
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            ctx.record("rehearsal.roomScanChunks", raw + " 不是数字 —— 按 "
                    + STRONGHOLD_ROOM_SCAN_CHUNKS + " 区块扫（和 rung 17 一样）");
            return STRONGHOLD_ROOM_SCAN_CHUNKS;
        }
        if (n == STRONGHOLD_ROOM_SCAN_CHUNKS) return n;
        JourneyLedger.staged("rehearsal: widened the STAGING's frame scan to " + n
                + " chunks (rung 17 still uses " + STRONGHOLD_ROOM_SCAN_CHUNKS + ")");
        ctx.record("rehearsal.roomScanChunks", n + " 区块（±" + (n * 16) + " 格）—— "
                + "只放宽布景这一侧的扫描，rung 17 的 ROOM_SCAN_CHUNKS 仍是 "
                + STRONGHOLD_ROOM_SCAN_CHUNKS + "，否则「6 够不够」这个问题永远不可能答「不够」");
        return Math.max(1, n);
    }

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
    /**
     * The side a rehearsal staged, for {@code JourneyTerrain.pickDigColumn} to honour — {@code null}
     * on every real climb, which is what makes this inert there.
     *
     * <p>Rehearsal-only three times over: it is written only by the staging step (which only runs when
     * {@link #target()} is set), the choice is already in the staging ledger, and a climb that somehow
     * set it could not report {@code staging.calls=0}. It is a static because the rung reads it four
     * calls deep and threading a rehearsal concern through the ladder's own signatures would put it
     * where a real climb could reach it.
     */
    static Direction stagedForgeSide;

    /**
     * The exact shaft column a rehearsal staged — the only lever that reproduces a ladder's mould.
     *
     * <h2>Why a side is not enough, measured</h2>
     *
     * The real ladder of 2026-08-16 cut an {@code east} mould at {@code forge.face = 4,56,19}. It was
     * natural to assume that came from a different lava pool — rung 11 spends one, so rung 12 often
     * gets another — and that assumption was <b>wrong</b>, which the archived evidence map said in a
     * line sitting right beside the one that prompted it:
     *
     * <pre>
     * ladder    lava.landmark = lavaLake -9, 63, 19（勘测到 72 格源块）  shaft.standingOn = -8,19 (就近合格柱)
     * rehearsal lava.landmark = lavaLake -9, 63, 19（勘测到 72 格源块）  shaft.standingOn = -9,21 (选定柱)
     * </pre>
     *
     * <b>The same pool.</b> The whole difference is the shaft column — and {@code -8,19} is
     * {@code dx=+1, dz=0}, i.e. <b>r=1</b>, while {@link JourneyTerrain#pickDigColumn} rings outward
     * from <b>r=2</b>. So the column the ladder actually used is one that method can never propose;
     * the climb reached it only through {@code stepOntoDiggableColumn}'s adopt-where-you-stand
     * short-circuit, after {@code walkToColumn(lava)} stopped one block out
     * ({@code lava.arrivedDistance = 1}).
     *
     * <p>That is why {@link #stagedForgeSide} can turn a mould but cannot REPRODUCE one: asking for
     * the east side got {@code -7,17} — the nearest east column on the r≥2 ring — and a geometry the
     * ladder never visits. Naming the column is the faithful lever.
     *
     * <p>Rehearsal-only on the same three counts as the side: written only by the staging step,
     * counted into the ledger, and cleared by the ladder's own {@code recon}.
     */
    static BlockPos stagedShaftColumn;

    /**
     * How much cobblestone a {@code PORTAL_LIT} rehearsal hands over — the climb's MEASURED figure,
     * not a convenient round number.
     *
     * <p>It was 64, which is one stack because a stack is easy to type. The real ladder of 2026-08-16
     * arrived at this rung holding <b>111</b> ({@code cobblestone.before = 111}, and 116 by the time
     * the carve started). That gap matters because <b>a rehearsal stages the preconditions while a
     * climb arrives carrying eleven rungs of residue</b>, and inventory is the commonest thing left
     * out of that sentence — this repo has already been fooled by it once, when a rehearsal carried
     * cobblestone and a climb carried dirt, {@code tidyTheAlcove} matched only {@code Blocks.COBBLESTONE},
     * and the dig sealed its own foothold. Any conclusion drawn from a rehearsal whose bag differs
     * from the climb's is a conclusion about the rehearsal.
     *
     * <p>Two differences REMAIN deliberate and are named in the evidence row rather than quietly
     * lived with: the bucket is handed over full (rung 12's water walk is a rung-10 capability, see
     * {@code fillWaterAtTheSurface}) and there are two pickaxes (durability must not be a hidden
     * variable in the leg under investigation).
     */
    private static final int PORTAL_LIT_COBBLESTONE = 111;

    /** Forget them between suites, so a rehearsal cannot colour a later run in the same JVM. */
    static void resetStagedForgeSide() {
        stagedForgeSide = null;
        stagedShaftColumn = null;
    }

    /** {@code -PshaftColumn=x,z}, or null. Rehearsal-only, read from the staging step. */
    private static BlockPos forcedShaftColumn(SceneContext ctx) {
        String raw = System.getProperty("worlddriver.journey.shaftColumn", "").trim();
        if (raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            ctx.record("rehearsal.shaftColumn", raw + " 不是 x,z 形式 —— 忽略，按自然选柱");
            return null;
        }
        try {
            BlockPos col = new BlockPos(Integer.parseInt(parts[0].trim()), 0,
                    Integer.parseInt(parts[1].trim()));
            JourneyLedger.staged("rehearsal: pinned the shaft column to "
                    + col.getX() + "," + col.getZ());
            ctx.record("rehearsal.shaftColumn", col.getX() + "," + col.getZ()
                    + "：井柱被钉死（pickDigColumn 不参与，就地采纳也只认这一柱）"
                    + " —— 这是复现某一趟 ladder 模腔的办法，比指定方位精确");
            return col;
        } catch (NumberFormatException e) {
            ctx.record("rehearsal.shaftColumn", raw + " 解析不了 —— 忽略，按自然选柱");
            return null;
        }
    }

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
