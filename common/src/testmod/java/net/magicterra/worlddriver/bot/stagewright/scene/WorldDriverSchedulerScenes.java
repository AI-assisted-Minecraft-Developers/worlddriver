package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.auto.AntiSuffocateGate;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.process.RunAwayProcess;
import net.magicterra.worlddriver.bot.scheduler.BunkerChain;
import net.magicterra.worlddriver.bot.scheduler.CancelRouting;
import net.magicterra.worlddriver.bot.scheduler.Chain;
import net.magicterra.worlddriver.bot.scheduler.ChainProcessLifecycle;
import net.magicterra.worlddriver.bot.scheduler.CombatChain;
import net.magicterra.worlddriver.bot.scheduler.DuskSecureChain;
import net.magicterra.worlddriver.bot.scheduler.Priorities;
import net.magicterra.worlddriver.bot.scheduler.RetreatChain;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.bot.world.SurvivalFacts;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4c wave 7, the Scheduler-semantics matrix family</b>: the 11
 * legacy {@code AgentGameTestServer} scheduler / reflex-gate tests (the #54P1 regression guards —
 * hurt-entry / frail gate / DUSK_URGENT90 / death-clears-table / terminal honesty / AutoTool grace /
 * cancel routing / episode lifecycle) migrated verbatim to testkit {@code wd.*} scenes, with their
 * legacy twins deleted from the Server suite in the same commit (count chain legacy 44 → 33).
 *
 * <p><b>These are assertion-DENSE matrices.</b> Each scene iterates rows of
 * {@code (input-state → expected-verdict)} over a pure gate/classifier; the porting rule is that
 * <b>every matrix row survives translation one-for-one</b> — no row is dropped, reordered, or merged.
 * The legacy bodies used a {@code BiConsumer<Boolean,String> check} lambda supplied by the
 * {@code @GameTest} arena as {@code (ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); }};
 * each helper is copied byte-for-byte and the scene body supplies the SceneContext analogue
 * {@code (ok, msg) -> { if (!ok) ctx.fail(msg); }}. Row counts (legacy == scene, audited in
 * migration-log wave-7): retreatGateMatrix 43, walkerTerminalReport 5, antiSuffocateShouldTrigger 8,
 * chainEpisodeCancel 3, combatGrace 2, frailBlocked 3, urgentBid 10 (urgentBid 5 + wouldEscalate 5),
 * duskSecureHeldProcessLifecycle 33 (matrix 26 + world leg 7), cancelRouting 19, manualSlotGrace 8,
 * nearestFirstScan 5.
 *
 * <p><b>Nine of the eleven are PURE LOGIC — no world, no avatar, no walker</b> (static gate /
 * classifier / in-memory chain-state matrices): {@code wd.walkerTerminalReportMatrix},
 * {@code wd.antiSuffocateShouldTriggerMatrix}, {@code wd.chainEpisodeCancelMatrix},
 * {@code wd.combatGraceMatrix}, {@code wd.frailBlockedMatrix}, {@code wd.urgentBidMatrix},
 * {@code wd.cancelRoutingMatrix}, {@code wd.manualSlotGraceMatrix}, {@code wd.nearestFirstScanMatrix}.
 * Their scene body runs the matrix once on the first RUN tick and resolves immediately — no
 * {@code await}, no ticks elapse, so the persistent dogfood world is untouched and no cleanup is
 * needed (nothing is spawned or placed).
 *
 * <p><b>Two touch the world.</b> {@code wd.retreatGateMatrix} creates a real {@code Skeleton}
 * (RangedAttackMob) + {@code Zombie} to hold as {@code ThreatScanner.Threat} references — the mobs are
 * used only for their entity identity / {@code instanceof RangedAttackMob} discrimination and are NOT
 * scanned via {@code getEntitiesOfClass} (so, unlike the CombatSense wave, no entity-visibility await
 * is needed); it also plants a 1×1 stone pocket for the {@code BunkerProcess.enclosed} geometry leg.
 * {@code wd.duskSecureHeldProcessLifecycle} runs the pure lifecycle matrix, then a REAL
 * {@code BunkerProcess} world leg (the gap#75-b re-arm incident) over a {@code createIsolated}
 * FakePlayer driven synchronously via {@code driver.tick()}. Both register {@code ctx.cleanup} to
 * discard their avatar/mobs and scrub every block they place (the #40 persistent-world lesson). The
 * canonical substitutions are the wave-6 Station set: {@code helper.getLevel()} →
 * {@link SceneContext#level()}; absolute {@code cx/cz} → origin X/Z; absolute {@code floorY=220} →
 * {@code origin.y + 20}; {@code ServerWorldDriver.create} → {@link ServerWorldDriver#createIsolated};
 * legacy {@code FakePlayer} → common {@link ServerPlayer}; {@code try/finally} config save/restore →
 * {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)}; {@code throw} →
 * {@link SceneContext#fail}. ⛔ No scene calls {@code level.tick()} (this family has none — the
 * re-entrant {@code level.tick()} mines are all in the Process wave, P4c Task 4).
 */
public final class WorldDriverSchedulerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.retreatGateMatrix", 200, WorldDriverSchedulerScenes::retreatGateMatrixScene),
                Scene.of("wd.walkerTerminalReportMatrix", 200, WorldDriverSchedulerScenes::walkerTerminalReportMatrixScene),
                Scene.of("wd.antiSuffocateShouldTriggerMatrix", 200, WorldDriverSchedulerScenes::antiSuffocateShouldTriggerMatrixScene),
                Scene.of("wd.chainEpisodeCancelMatrix", 200, WorldDriverSchedulerScenes::chainEpisodeCancelMatrixScene),
                Scene.of("wd.combatGraceMatrix", 200, WorldDriverSchedulerScenes::combatGraceMatrixScene),
                Scene.of("wd.frailBlockedMatrix", 200, WorldDriverSchedulerScenes::frailBlockedMatrixScene),
                Scene.of("wd.urgentBidMatrix", 200, WorldDriverSchedulerScenes::urgentBidMatrixScene),
                Scene.of("wd.duskSecureHeldProcessLifecycle", 400, WorldDriverSchedulerScenes::duskSecureHeldProcessLifecycleScene),
                Scene.of("wd.cancelRouting", 200, WorldDriverSchedulerScenes::cancelRoutingScene),
                Scene.of("wd.manualSlotGraceMatrix", 200, WorldDriverSchedulerScenes::manualSlotGraceMatrixScene),
                Scene.of("wd.nearestFirstScanMatrix", 200, WorldDriverSchedulerScenes::nearestFirstScanMatrixScene));
    }

    // ==================================================================================
    // wd.retreatGateMatrix — gap#65/#68-①/#71/#72-③④ RetreatChain enter/release gate matrix.
    // Touches the world: real Skeleton (RangedAttackMob) + Zombie threat references + a 1×1
    // stone pocket for the BunkerProcess.enclosed geometry leg. 43 matrix rows.
    // ==================================================================================

    private static void retreatGateMatrixScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        var skeleton = net.minecraft.world.entity.EntityType.SKELETON.create(level);
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        ctx.cleanup(() -> { skeleton.discard(); zombie.discard(); });
        // Scrub the pocket the geometry leg plants (#40 persistent-world lesson — the mobs are
        // discarded above; only the stone pocket is left in the world otherwise).
        ctx.cleanup(() -> {
            for (int dx = 7; dx <= 9; dx++)
                for (int dy = 20; dy <= 24; dy++)
                    for (int dz = 7; dz <= 9; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, ctx.origin().getY() + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        skeleton.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0, 0);
        zombie.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0, 0);
        level.addFreshEntity(skeleton);
        level.addFreshEntity(zombie);

        java.util.function.BiFunction<Double, boolean[], net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> skel =
                (dist, flags) -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                skeleton, skeleton.getId(), "minecraft:skeleton", dist,
                                /*canSeeMe*/ flags[0], /*facingMe*/ flags[0], /*charging*/ flags[0],
                                0.8, 0f, /*attackedMe*/ flags[1])),
                        java.util.List.of());
        var empty = new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(java.util.List.of(), java.util.List.of());

        // (a) THE death-#6 case: low HP, sniped from 14 (attackedMe), LoS-blocked
        // (charging=false because arrows arc over the corner) → MUST enter.
        if (!RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[]{false, true})))
            ctx.fail("gap#65(a): hp8 + ranged attacker hit me from 14 (LoS-blocked) must enter retreat");
        // (b) idle distant skeleton, never hit me → must NOT enter (flee-from-nothing guard).
        if (RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[]{false, false})))
            ctx.fail("gap#65(b): hp8 + idle skeleton at 14 that never hit me must not enter");
        // (c) low HP with a hostile close by → enter (pre-existing reactive path preserved).
        if (!RetreatChain.shouldEnter(8f, 10f, skel.apply(11.0, new boolean[]{false, false})))
            ctx.fail("gap#65(c): hp8 + hostile at 11 must enter (reactive path)");
        // (d) low HP, empty field → must NOT enter.
        if (RetreatChain.shouldEnter(4f, 10f, empty))
            ctx.fail("gap#65(d): hp4 + no threats must not enter");
        // (e) full HP but a skeleton is aiming with LoS at 10 → enter (proactive preserved).
        if (!RetreatChain.shouldEnter(20f, 10f, skel.apply(10.0, new boolean[]{true, false})))
            ctx.fail("gap#65(e): charging skeleton at 10 must enter (proactive path)");
        // (f) ranged attacker hit me from 16 at FULL HP → enter: confirmed fire is a
        // strictly stronger signal than the aim (e) already reacts to. Waiting for
        // hp<=thr means 2-3 arrows already landed (the canopy/tunnel-snipe deaths).
        if (!RetreatChain.shouldEnter(20f, 10f, skel.apply(16.0, new boolean[]{false, true})))
            ctx.fail("gap#65(f): ranged attacker hit me (16, full HP) must enter");
        // (g) melee attacker at 14 who hit me once, full HP → MUST enter.
        // gap#68-①: hurt-entry supersedes the gap#65-era expectation — a connected
        // hit within 2×CLEAR_RADIUS latches at ANY hp
        var meleeScan = new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                        zombie, zombie.getId(), "minecraft:zombie", 14.0, true, true, false, 0.6, 0f, true)),
                java.util.List.of());
        if (!RetreatChain.shouldEnter(20f, 10f, meleeScan))
            ctx.fail("gap#68-①(g): melee attackedMe at 14 must enter (hurt-entry supersedes gap#65's melee carve-out)");
        // (h) latched + recovered, but the ranged attacker is STILL hitting me from 14
        // → must NOT release (releasing walks straight back into the fire).
        if (RetreatChain.shouldRelease(20f, 10f, skel.apply(14.0, new boolean[]{false, true})))
            ctx.fail("gap#65(h): recovered but still under ranged fire must not release");
        // (i) skeleton drifted to 20, no longer hit me → release (outran it).
        if (!RetreatChain.shouldRelease(8f, 10f, skel.apply(20.0, new boolean[]{false, false})))
            ctx.fail("gap#65(i): hostile at 20, not firing → must release");

        // gap#68-①(R3): hurt-entry latch for ANY connected attacker (melee included)
        // + dynamic low-HP threshold max(thr, 40% maxHp). meleeHit = zombie that
        // actually hit me (attackedMe=true) at the given distance; zombieNear = an
        // idle (never-hit-me) zombie at the given distance — used both to prove the
        // dynamic threshold's hostileWithin gate and as the negative control.
        java.util.function.Function<Double, net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> meleeHit =
                dist -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                zombie, zombie.getId(), "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ true)),
                        java.util.List.of());
        java.util.function.Function<Double, net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> zombieNear =
                dist -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                zombie, zombie.getId(), "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ false)),
                        java.util.List.of());
        // gap#68-①: 被近战打中(attackedMe,非 Ranged)必须进闩——旧门只认 Ranged 或 HP≤thr
        if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0)))
            ctx.fail("gap#68-①: melee attackedMe at full-ish HP must latch the flee");
        // 动态阈值:maxHp*0.4=8 > thr=6,HP 7 + 近战近身必须进
        if (!RetreatChain.shouldEnter(7f, 6f, 20f, zombieNear.apply(5.0)))
            ctx.fail("gap#68-①: effective threshold is max(thr, 40% maxHp)");
        // 阴性:无人打我、HP 高、无 ranged → 不进
        if (RetreatChain.shouldEnter(18f, 6f, 20f, zombieNear.apply(5.0)))
            ctx.fail("gap#68-①: nearby idle zombie at high HP must NOT latch");
        // release 对称性(gap#65 先例: underRangedFire 同时挡 enter 和 release):
        // melee attackedMe@14 在 hostileWithin(12) 外、hurt-entry(24) 内 —— 若 release
        // 不认 hurtByAnyone, safe 支当 tick 放闩、下一 tick hurt-entry 重进 = 每 tick 抖动。
        if (RetreatChain.shouldRelease(20f, 10f, meleeHit.apply(14.0)))
            ctx.fail("gap#68-①: melee attackedMe at 14 must BLOCK release (enter/release symmetry)");
        // 进入边界: 锁定 CLEAR_RADIUS*2=24 的精确截断。
        if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(23.0)))
            ctx.fail("gap#68-①: connected hit at 23 (inside 2xCLEAR_RADIUS) must enter");
        if (RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(25.0)))
            ctx.fail("gap#68-①: connected hit at 25 (outside 2xCLEAR_RADIUS) must NOT enter");

        // final-review finding #2 (T6×T7 composition): the unconditional hurt-entry
        // latch made a HEALTHY bot deliberately brawling (mc.bot.combat) flee on the
        // first connected counter-hit — retreat (>=100) outbids COMBAT (60), so an
        // explicit fight can livelock (approach -> hit -> flee -> repeat). gap#68's
        // evidence book (legs ⑨⑪⑫) is all hit-while-goto/digging, never
        // hit-while-brawling; Task 7's frail gate is the designed handoff once HP
        // actually drops. hp=18, thr=6, maxHp=20 -> effThr=max(6,8)=8.
        // engaged + healthy (18>8) + melee hit -> must NOT enter (the fix).
        if (RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true))
            ctx.fail("finding#2: engaged + healthy (hp18>effThr8) + melee hit must NOT enter (would livelock an explicit fight)");
        // engaged but FRAIL (hp7<=effThr8) + melee hit -> still enter: the safety net.
        if (!RetreatChain.shouldEnter(7f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true))
            ctx.fail("finding#2: engaged + frail (hp7<=effThr8) + melee hit must enter (frail handoff)");
        // NOT engaged + healthy + melee hit -> must enter (leg ① preserved; the
        // not-engaged scenario the 4-arg back-compat overload models, = case (g)).
        if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ false))
            ctx.fail("finding#2: not-engaged + healthy + melee hit must enter (leg ① / case (g) preserved)");

        // gap#71 (near-death #19): a pursuing skeleton (bow 15+, 2-3s shot cadence)
        // circled a naked bot 20->3.2 across 4 hits — release fired on EVERY gap
        // between shots because hostileWithin's engagedRanged only widens on
        // charging||attackedMe, and both go false between volleys (mid-strafe,
        // vanilla's attackedMe window lapses faster than the shot interval). The
        // fix is two guards ANDed into shouldRelease: (A) a currently-VISIBLE
        // RangedAttackMob within 18, regardless of charging/attackedMe; (B) a
        // release-side cooldown of 60t since the last connected hit, wider than
        // the shot interval. visibleSkel/visibleZombie isolate each guard from
        // the pre-existing charging/attackedMe-gated signals.
        java.util.function.BiFunction<Double, Boolean, net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> visibleSkel =
                (dist, canSee) -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                skeleton, skeleton.getId(), "minecraft:skeleton", dist,
                                /*canSeeMe*/ canSee, /*facingMe*/ false, /*charging*/ false,
                                0.8, 0f, /*attackedMe*/ false)),
                        java.util.List.of());
        java.util.function.Function<Double, net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> visibleZombie =
                dist -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                zombie, zombie.getId(), "minecraft:zombie", dist,
                                /*canSeeMe*/ true, /*facingMe*/ false, /*charging*/ false,
                                0.6, 0f, /*attackedMe*/ false)),
                        java.util.List.of());

        // (j) THE near-death-#19 case: recovered HP, a VISIBLE skeleton at 15 that
        // is neither charging nor freshly attackedMe — old gate reads this as
        // "safe" (engagedRanged never widens) and releases straight back under
        // the next volley. Must NOT release.
        if (RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, true)))
            ctx.fail("gap#71(j): recovered + VISIBLE ranged threat at 15 (not charging/hit) must NOT release");
        // (k) same skeleton/distance but LoS is broken (stepped behind cover) +
        // long since the last hit -> the visible-ranged guard clears -> release.
        if (!RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, false), 100L))
            ctx.fail("gap#71(k): same skeleton, canSeeMe=false (behind cover) + 100t since hurt must release");
        // (l) a MELEE hostile (not RangedAttackMob) at the same 15 -> unaffected by
        // the new visible-ranged guard; hostileWithin's original 12-radius still
        // governs and this releases exactly as before gap#71.
        if (!RetreatChain.shouldRelease(20f, 10f, visibleZombie.apply(15.0), 100L))
            ctx.fail("gap#71(l): melee (non-Ranged) hostile at 15 must NOT be gated by the new visible-ranged guard");
        // (m) Guard B: hurt 30t ago (<60t cooldown) with NO threats visible at all
        // -> must still NOT release. Isolates the hurt-cooldown latch from every
        // scan-based signal (this is the "circled in the gap between shots" case).
        if (RetreatChain.shouldRelease(20f, 10f, empty, 30L))
            ctx.fail("gap#71(m): 30t since last hurt (<60t cooldown) with empty scan must NOT release");
        // (n) boundary: exactly 60t since last hurt, no threats -> release.
        if (!RetreatChain.shouldRelease(20f, 10f, empty, 60L))
            ctx.fail("gap#71(n): exactly 60t since last hurt must release (cooldown boundary)");
        // (o) boundary: 59t -> one tick inside the cooldown, still blocked.
        if (RetreatChain.shouldRelease(20f, 10f, empty, 59L))
            ctx.fail("gap#71(o): 59t since last hurt must NOT release (one tick inside cooldown)");

        // gap#72-③ (the self-dug-bunker breach): duskSecure SEALED a 1×1 pocket,
        // a surface hostile drifted to 11.4 (3D distance, THROUGH the 7-block
        // roof) — hostileWithin's bare distance yardstick latched the flee, and
        // the only expandable flee direction inside a sealed pocket is straight
        // DOWN: the reflex dug the bot out of its own bunker (y76→68) at night.
        // A SEALED pocket is unreachable to the mob, i.e. SAFER than any flee —
        // so for a threat that cannot see me (canSeeMe=false) and has not hit me
        // (attackedMe=false), a sealed bot must neither ENTER nor MAINTAIN the
        // flee. A connected hit still latches (breached pocket = real danger:
        // hurt-entry semantics untouched), and a VISIBLE threat means the pocket
        // is effectively breached — only unseen+unconnected threats are exempt.
        // pocket = zombie at dist with the given canSeeMe/attackedMe flags.
        java.util.function.BiFunction<Boolean, Boolean, net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan> pocket =
                (canSee, hitMe) -> new net.magicterra.worlddriver.bot.combat.ThreatScanner.Scan(
                        java.util.List.of(new net.magicterra.worlddriver.bot.combat.ThreatScanner.Threat(
                                zombie, zombie.getId(), "minecraft:zombie", 11.0,
                                /*canSeeMe*/ canSee, /*facingMe*/ false, /*charging*/ false,
                                0.6, 0f, /*attackedMe*/ hitMe)),
                        java.util.List.of());
        // (p) THE incident case: sealed + low HP + unseen never-hit hostile at 11
        // -> must NOT enter (old gate: lowHp && hostileWithin latched the flee).
        if (RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ true))
            ctx.fail("gap#72-③(p): sealed pocket + unseen never-hit hostile at 11 must NOT enter (fleeing out of a sealed bunker is a downgrade)");
        // (q) sealed + the same hostile actually HIT me -> pocket is breached,
        // hurt-entry must latch exactly as before.
        if (!RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, true), false, /*sealed*/ true))
            ctx.fail("gap#72-③(q): sealed + attackedMe must still enter (hurt-entry untouched — a hit through the seal means it's breached)");
        // (r) NOT sealed, same unseen hostile at 11, low HP -> enters exactly as
        // before (case (c) semantics preserved through the new overload).
        if (!RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ false))
            ctx.fail("gap#72-③(r): not sealed + hostile at 11 + low HP must enter (existing reactive path preserved)");
        // (s) sealed but the hostile CAN SEE me (lateral opening / broken seal)
        // -> the exemption must not apply; low HP + visible hostile enters.
        if (!RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(true, false), false, /*sealed*/ true))
            ctx.fail("gap#72-③(s): sealed + VISIBLE hostile at 11 must enter (a mob that sees me means the pocket is not actually sealing)");
        // (t) MAINTAIN side: latched flee at LOW hp (recovered branch out of
        // play), now sealed, unseen never-hit hostile at 11, long since any
        // hurt -> must RELEASE (don't keep fleeing/digging away from a threat
        // that can't reach me).
        if (!RetreatChain.shouldRelease(8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, /*sealed*/ true))
            ctx.fail("gap#72-③(t): sealed + unseen never-hit hostile at 11 must RELEASE (don't maintain a flee out of your own bunker)");
        // (u) sealed but hurt 30t ago -> gap#71's 60t cooldown still blocks
        // release (hurt semantics untouched by the exemption).
        if (RetreatChain.shouldRelease(20f, 10f, pocket.apply(false, false), 30L, /*sealed*/ true))
            ctx.fail("gap#72-③(u): sealed + 30t since last hurt must NOT release (60t cooldown intact)");
        // (v) sealed + the hostile's hit is still connecting (attackedMe) ->
        // release stays blocked (enter/release hurt symmetry intact).
        if (RetreatChain.shouldRelease(20f, 10f, pocket.apply(false, true), Long.MAX_VALUE, /*sealed*/ true))
            ctx.fail("gap#72-③(v): sealed + attackedMe must NOT release (breached pocket = real danger)");
        // (w) NOT sealed through the new overload, same low hp -> hostileWithin
        // blocks release exactly as before (the (t)/(w) pair isolates the seal:
        // identical inputs, only sealedPocket flips).
        if (RetreatChain.shouldRelease(8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, /*sealed*/ false))
            ctx.fail("gap#72-③(w): not sealed + hostile at 11 at low hp must NOT release (existing semantics preserved)");

        // death#26 (07-20 live, open-terrain skeleton): the "safe" release branch
        // ignored HP entirely, so a CRITICALLY-HURT bot (hp<thr) released on every
        // tick the pursuing skeleton flickered out of scan (LoS break / >18 blocks /
        // the >60t lull between volleys — wider than gap#71's 60t hurt-cooldown):
        // stood still, got shot, 35s of release("safe")↔enter("lowHp") flapping then
        // dead. Fix = a low-HP threat-PRESENCE floor (THREAT_MEMORY_TICKS) on the
        // "safe" branch ONLY, isolated on the 5-arg releaseReason (ticksSinceThreat).
        // (x) THE incident: hp 5.7 (<thr 10), threat flickered out THIS tick (empty),
        // 100t since the last hit (past the 60t cooldown — old gate said "safe"), but
        // a threat was present 20t ago -> must NOT release (keep fleeing).
        if (RetreatChain.releaseReason(5.7f, 10f, empty, 100L, 20L) != null)
            ctx.fail("death#26(x): low HP + threat seen 20t ago must NOT release in a scan-flicker gap (was: 'safe')");
        // (y) genuinely broken contact: same, but no threat for 150t (>100 memory) ->
        // a low-HP no-food bot resumes its task instead of fleeing forever.
        if (!"safe".equals(RetreatChain.releaseReason(5.7f, 10f, empty, 100L, 150L)))
            ctx.fail("death#26(y): low HP + no threat for 150t (>memory) must release safe (resume, don't flee forever)");
        // (z) the floor is LOW-HP only: a recovered bot (hp>=thr) with a threat seen
        // 20t ago releases exactly as before — healthy proactive flees unaffected.
        if (!"safe".equals(RetreatChain.releaseReason(20f, 10f, empty, 100L, 20L)))
            ctx.fail("death#26(z): recovered HP must release regardless of recent-threat memory (floor is low-HP only)");
        // (aa) boundary: threat 99t ago (<100) at low HP still blocked; exactly 100t releases.
        if (RetreatChain.releaseReason(9.9f, 10f, empty, 100L, 99L) != null)
            ctx.fail("death#26(aa): 99t since threat (<100 memory) at low HP must NOT release");
        if (!"safe".equals(RetreatChain.releaseReason(9.9f, 10f, empty, 100L, 100L)))
            ctx.fail("death#26(aa'): exactly 100t since threat must release (memory boundary)");
        // (bb) sealed composition: an unreachable roof-mob is filtered out, so the
        // caller never stamps recent-threat (ticksSinceThreat large) -> a sealed
        // low-HP bot still releases; gap#72's don't-dig-out-of-your-bunker intact.
        if (!"safe".equals(RetreatChain.releaseReason(8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, Long.MAX_VALUE, /*sealed*/ true)))
            ctx.fail("death#26(bb): sealed + unreachable threat (large ticksSinceThreat) at low HP must still release (gap#72 intact under the new floor)");

        // death#26 sub-fix ② (BunkerChain ranged-pin escalation): ① stops the release
        // oscillation but a low-HP bot SHOT from range in the open still can't escape
        // by fleeing (controlled-live: skeleton holds bow range and whittles 6→4→2, or
        // closes to melee — rig-non-deterministic). Escalate to a bunker to break LoS.
        // Deterministic gate test (retreatThr=6; skeleton=RangedAttackMob, zombie not).
        // (cc) THE incident: hp 5 (<=thr 6) + a skeleton whose arrow CONNECTED
        // (attackedMe) at 13, not sealed -> must escalate to bunker.
        if (!BunkerChain.shouldRangedBunker(5f, 6f, skel.apply(13.0, new boolean[]{false, true}), /*sealed*/ false))
            ctx.fail("death#26②(cc): low HP + connected ranged hit in the open must escalate to bunker");
        // (dd) healthy bot (hp>thr) being shot -> no bunker; retreat/flee handles it.
        if (BunkerChain.shouldRangedBunker(8f, 6f, skel.apply(13.0, new boolean[]{false, true}), false))
            ctx.fail("death#26②(dd): hp above retreatThr must NOT escalate to bunker (only a low-HP pin does)");
        // (ee) skeleton aiming but no hit landed (charging, !attackedMe) -> a mere aim
        // is retreat's proactive-flee job; the bunker escalation needs a CONNECTED shot.
        if (BunkerChain.shouldRangedBunker(5f, 6f, skel.apply(13.0, new boolean[]{true, false}), false))
            ctx.fail("death#26②(ee): charging-but-not-hit must NOT bunker (needs a connected shot)");
        // (ff) already sealed -> must NOT re-trigger (gap#29 re-dig ratchet guard).
        if (BunkerChain.shouldRangedBunker(5f, 6f, skel.apply(13.0, new boolean[]{false, true}), /*sealed*/ true))
            ctx.fail("death#26②(ff): already sealed must NOT re-escalate (no deeper re-dig once capped)");
        // (gg) a MELEE hit (zombie attackedMe, not RangedAttackMob) -> not a ranged
        // pin; the swarm 'cornered' path (bunkerHpThreshold) governs melee, not this.
        if (BunkerChain.shouldRangedBunker(5f, 6f, meleeHit.apply(5.0), false))
            ctx.fail("death#26②(gg): a melee (non-ranged) hit must NOT trigger the ranged-pin bunker");

        // gap#72-④: the telemetry REASON CLASSIFIERS are the same single source
        // as the boolean gates (shouldEnter == enterReason!=null and shouldRelease
        // == releaseReason!=null by construction — the booleans delegate to the
        // classifiers), so pin the classification itself: first-match order is
        // lowHp → ranged-aiming → ranged-fire → hurt, release is safe/recovered.
        if (!"lowHp".equals(RetreatChain.enterReason(8f, 10f, 20f, skel.apply(11.0, new boolean[]{false, false}), false)))
            ctx.fail("gap#72-④: hp8 + hostile at 11 must classify enter as lowHp");
        if (!"ranged-aiming".equals(RetreatChain.enterReason(20f, 10f, 20f, skel.apply(10.0, new boolean[]{true, false}), false)))
            ctx.fail("gap#72-④: charging skeleton at 10 (full HP) must classify enter as ranged-aiming");
        if (!"ranged-fire".equals(RetreatChain.enterReason(20f, 10f, 20f, skel.apply(16.0, new boolean[]{false, true}), false)))
            ctx.fail("gap#72-④: connected ranged hit at 16 (full HP) must classify enter as ranged-fire");
        if (!"hurt".equals(RetreatChain.enterReason(20f, 10f, 20f, meleeHit.apply(14.0), false)))
            ctx.fail("gap#72-④: melee attackedMe at 14 (full HP) must classify enter as hurt");
        if (RetreatChain.enterReason(18f, 6f, 20f, zombieNear.apply(5.0), false) != null)
            ctx.fail("gap#72-④: no-enter must classify as null (idle zombie, healthy)");
        if (RetreatChain.enterReason(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ true) != null)
            ctx.fail("gap#72-④: sealed exemption must classify as null (no phantom reason)");
        if (!"safe".equals(RetreatChain.releaseReason(8f, 10f, empty, Long.MAX_VALUE)))
            ctx.fail("gap#72-④: empty field must classify release as safe");
        if (!"recovered".equals(RetreatChain.releaseReason(20f, 10f, zombieNear.apply(5.0), Long.MAX_VALUE)))
            ctx.fail("gap#72-④: hp20 + idle zombie at 5 must classify release as recovered (hysteresis branch)");
        if (RetreatChain.releaseReason(20f, 10f, meleeHit.apply(14.0), Long.MAX_VALUE) != null)
            ctx.fail("gap#72-④: hurt-blocked release must classify as null");

        // gap#72-③ geometry leg: the "am I sealed" signal is BunkerProcess's
        // block-level enclosure ground truth (foot's 4 horizontal neighbors +
        // head's 4 + the cell above the head all solid), now a public static
        // gate shared with RetreatChain — single source, and it self-verifies
        // "龛未破" (a stale SEALED slot over a since-breached pocket reads false).
        BlockPos pFoot = new BlockPos(cx + 8, floorY + 1, cz + 8);
        BlockPos pHead = pFoot.above();
        for (BlockPos b : new BlockPos[]{pFoot.north(), pFoot.south(), pFoot.east(), pFoot.west(),
                pHead.north(), pHead.south(), pHead.east(), pHead.west(), pHead.above()})
            level.setBlockAndUpdate(b, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pFoot, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pHead, Blocks.AIR.defaultBlockState());
        LevelWorldView pocketView = new LevelWorldView(level, null);
        if (!BunkerProcess.enclosed(pocketView, pFoot))
            ctx.fail("gap#72-③(x): fully enclosed 1×1 pocket must read enclosed=true");
        level.setBlockAndUpdate(pHead.above(), Blocks.AIR.defaultBlockState());   // breach the roof
        if (BunkerProcess.enclosed(pocketView, pFoot))
            ctx.fail("gap#72-③(y): pocket with a broken roof must read enclosed=false (龛未破 self-verifies)");
    }

    // ==================================================================================
    // wd.walkerTerminalReportMatrix — gap#68-R2a Walker.classifyArrival honesty (5 rows).
    // ==================================================================================

    // gap#68-R2: Walker.classifyArrival 纯函数矩阵 —— ARRIVED 出口必须可区分
    // (goal-snapped / frontier-giveup 等由调用点直接传标签,本函数只管三态通用出口)
    static void walkerTerminalReportMatrix(BiConsumer<Boolean, String> check) {
        check.accept("arrived".equals(Walker.classifyArrival(false, true,  false)), "full path + reached = arrived");
        check.accept("arrived".equals(Walker.classifyArrival(true,  true,  false)), "best-effort + reached = arrived");
        check.accept("path-consumed".equals(Walker.classifyArrival(false, false, false)), "full path + NOT reached = path-consumed");
        check.accept("best-effort-consumed".equals(Walker.classifyArrival(true, false, false)), "best-effort + NOT reached = best-effort-consumed");
        check.accept("goal-snapped".equals(Walker.classifyArrival(false, true,  true)),  "snapped goal reached = goal-snapped");
    }

    private static void walkerTerminalReportMatrixScene(SceneContext ctx) {
        walkerTerminalReportMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.antiSuffocateShouldTriggerMatrix — gap#69/M1 AntiSuffocateGate.shouldTrigger (8 rows).
    // ==================================================================================

    static void antiSuffocateShouldTriggerMatrix(BiConsumer<Boolean, String> check) {
        // (a) THE death-#16 case: client geometry says NOT in a wall, but the server
        // damage attribution says we ARE taking inWall damage — must trigger.
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true),
                "gap#69(a): isInWall=false but lastDamage=inWall must trigger (death #16 desync)");
        // (b) existing behavior preserved: client geometry alone still triggers.
        check.accept(AntiSuffocateGate.shouldTrigger(true, null, true, true),
                "gap#69(b): isInWall=true (no damage signal yet) must still trigger");
        // (c) negative control: neither signal present → must NOT trigger.
        check.accept(!AntiSuffocateGate.shouldTrigger(false, null, true, true),
                "gap#69(c): no isInWall and no inWall damage must NOT trigger");
        // (d) allowBreak gate preserved: even with the damage fallback firing, a
        // disabled allowBreak must still suppress the reflex (it mines the block).
        check.accept(!AntiSuffocateGate.shouldTrigger(false, "inWall", true, false),
                "gap#69(d): allowBreak=false must suppress even the damage fallback");
        // (e) antiSuffocate config gate preserved: cfg off must suppress everything.
        check.accept(!AntiSuffocateGate.shouldTrigger(true, "inWall", false, true),
                "gap#69(e): antiSuffocate=false must suppress even isInWall+damage both true");
        // (f) an unrelated damage source (e.g. a mob hit) must NOT trigger the reflex.
        check.accept(!AntiSuffocateGate.shouldTrigger(false, "mob", true, true),
                "gap#69(f): a non-inWall lastDamage msgId must NOT trigger");

        // final-review M1: AntiSuffocate#resolveHead's foot/horizontal fallback legs
        // must additionally require hurtTime>0 — shouldTrigger above stays a coarse
        // ~40t damage-window gate (fine for the eye/above legs), but the proximity
        // fallback is a last-resort guess that must go quiet as soon as the bot is
        // actually freed, well before the 40t window itself lapses.
        // (g) a real, ongoing desync burial: shouldTrigger fires (damage signal) AND
        // hurtTime is hot (re-damaged this cycle) → fallback stays armed.
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && AntiSuffocateGate.allowProximityFallback(10),
                "M1(g): damage-signal fresh (death-#16 desync) AND hurtTime=10 (still being hurt) must arm the fallback legs");
        // (h) THE M1 case: damage-signal still fresh (shouldTrigger true — we're inside
        // the stale 40t tail) but hurtTime has already decayed to 0 (freed) → the
        // fallback must NOT arm, even though shouldTrigger itself is still true (the
        // eye/above legs are unaffected and keep reading real air, so nothing breaks).
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && !AntiSuffocateGate.allowProximityFallback(0),
                "M1(h): damage-signal fresh but hurtTime==0 (freed) must NOT arm foot/horizontal fallback (eye/above-only)");
    }

    private static void antiSuffocateShouldTriggerMatrixScene(SceneContext ctx) {
        antiSuffocateShouldTriggerMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.chainEpisodeCancelMatrix — gap#68-R1a BunkerChain episode state reset (3 rows).
    // ==================================================================================

    // gap#68-R1a/⑦: the structural fix for "cancel can't reach a process-less reflex
    // chain" — BunkerChain's anchor re-bids priority 300 forever once sealed, and
    // mc.bot.cancel (all or named) had no seam to reach it. Chain now exposes
    // episodePhase()/cancelEpisode(); the pure state reset (resetEpisodeState) is
    // exercised directly — the client key-release half touches Minecraft.getInstance()
    // and is NOT exercised here (dedicated server has no client classes; live in Task 10 leg ⑦).
    static void chainEpisodeCancelMatrix(BiConsumer<Boolean, String> check) {
        BunkerChain bc = new BunkerChain();
        // Build a sealed episode (the ⑦ deadlock shape: sealed anchor re-bids forever).
        bc.anchorForTest().beginIfIdle(0, 64, 0);
        bc.anchorForTest().sealed = true;
        check.accept("SEALED".equals(bc.episodePhase()), "sealed anchor reads as SEALED episode");
        bc.resetEpisodeState();
        check.accept(bc.episodePhase() == null, "resetEpisodeState clears the anchor");
        check.accept(!bc.anchorForTest().active() && !bc.anchorForTest().sealed, "anchor fully reset");
    }

    private static void chainEpisodeCancelMatrixScene(SceneContext ctx) {
        chainEpisodeCancelMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.combatGraceMatrix — gap#68-③/⑧ CombatChain death-grace suppression (2 rows).
    // ==================================================================================

    // gap#68-③/⑧: death must gate autoFight re-engagement for a grace window, or a
    // freshly-respawned naked bot resumes hunting whatever killed it (death #9/#11/#12
    // family). Pure counter logic — no Minecraft instance needed.
    static void combatGraceMatrix(BiConsumer<Boolean, String> check, BotState st) {
        CombatChain cc = new CombatChain(st);
        cc.suppressAutoFor(3);
        check.accept(cc.autoSuppressed(), "suppressed right after death");
        cc.decayAutoSuppression(); cc.decayAutoSuppression(); cc.decayAutoSuppression();
        check.accept(!cc.autoSuppressed(), "suppression decays to zero");
    }

    private static void combatGraceMatrixScene(SceneContext ctx) {
        combatGraceMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); }, new BotState());
    }

    // ==================================================================================
    // wd.frailBlockedMatrix — gap#68-② CombatChain.frailBlocked gate (3 rows).
    // ==================================================================================

    // gap#68-②: a bot at frail HP must not be walked into a fight — neither by
    // autoFight bidding in, nor (absent force:true) by an explicit mc.bot.combat
    // order. Pure static gate — no Minecraft instance needed.
    static void frailBlockedMatrix(BiConsumer<Boolean, String> check) {
        check.accept(CombatChain.frailBlocked(5f, 6f, false), "hp5<=thr6 without force must block");
        check.accept(!CombatChain.frailBlocked(5f, 6f, true), "force overrides the frail gate");
        check.accept(!CombatChain.frailBlocked(7f, 6f, false), "hp above threshold must not block");
    }

    private static void frailBlockedMatrixScene(SceneContext ctx) {
        frailBlockedMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.urgentBidMatrix — gap#68-④⑨ + finding#3 DuskSecureChain urgent-bid + dry-run
    // canary escalation predicate (urgentBid 5 rows + wouldEscalate 5 rows = 10 rows).
    // ==================================================================================

    // gap#68-④⑨: a user task (USER=50) must not permanently suppress dusk shelter all
    // night — when exposed at night and not already cornered/sheltered, the reflex must
    // escalate to DUSK_URGENT=90 (above USER, below SURVIVAL/BUNKER) unless the escalation
    // flag is off or a dry-run canary holds it at the legacy 40. Pure static gate.
    static void urgentBidMatrix(BiConsumer<Boolean, String> check) {
        check.accept(DuskSecureChain.urgentBid(true, false, true, false) == Priorities.DUSK_URGENT,
                "exposed night urgent=90");
        check.accept(DuskSecureChain.urgentBid(true, false, true, true) == Priorities.IDLE_SECURE,
                "dry-run stays 40");
        check.accept(DuskSecureChain.urgentBid(true, false, false, false) == Priorities.IDLE_SECURE,
                "flag off stays 40");
        check.accept(DuskSecureChain.urgentBid(false, false, true, false) == Priorities.IDLE_SECURE,
                "daytime stays 40");
        check.accept(DuskSecureChain.urgentBid(true, true, true, false) == 0f,
                "already cornered/sheltered = no bid");
    }

    // final-review finding #3: the dry-run canary (maybeEmitDryRun) used to be gated
    // behind priority()'s legacy THREAT_RADIUS veto, which returns 0 (and resets
    // idleTicks) BEFORE the canary is ever reached — so a threat sitting near the bot
    // at night (precisely the case the escalation exists to catch) silently starved
    // the canary of every emit. wouldEscalate is the extracted pure predicate that now
    // gates the emit INSTEAD, deliberately taking no threat-distance/idle-debounce
    // input at all — its whole point is to be independent of both.
    static void wouldEscalateMatrix(BiConsumer<Boolean, String> check) {
        check.accept(DuskSecureChain.wouldEscalate(true, false, true, true),
                "exposed+not cornered+urgent-on+dry-run -> canary would fire (the fixed case)");
        check.accept(!DuskSecureChain.wouldEscalate(true, false, true, false),
                "not dry-run -> nothing to observe, the real escalation happens instead");
        check.accept(!DuskSecureChain.wouldEscalate(true, false, false, true),
                "escalation flag off -> nothing would have escalated");
        check.accept(!DuskSecureChain.wouldEscalate(true, true, true, true),
                "cornered -> never (bunker/real dig-in owns the channel, not the canary)");
        check.accept(!DuskSecureChain.wouldEscalate(false, false, true, true),
                "daytime -> never");
    }

    private static void urgentBidMatrixScene(SceneContext ctx) {
        urgentBidMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
        wouldEscalateMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.duskSecureHeldProcessLifecycle — gap#72-① held-process interrupt/cancel lifecycle
    // matrix (26 rows) + gap#75-b re-arm WORLD leg over a real BunkerProcess (7 rows) = 33.
    // ==================================================================================

    // gap#72-①: duskSecure holds a BunkerProcess (SEALED-hold, active=true endReason=SEALED
    // = "驻守中" by design) and a higher chain (RetreatChain 100) preempts it. onInterrupt
    // used to only `process = null` — the slot's ONLY reset point (BunkerProcess.finish)
    // became forever unreachable, so st.bunker stayed active=true/SEALED as a permanent
    // orphan and mc.bot.status lied all night (live 2026-07-14 incident). Interrupt/cancel
    // of a chain-held process must go through the same finish/slot-reset lifecycle as a
    // natural completion, with a distinguishable endReason (INTERRUPTED vs CANCELLED vs
    // SEALED). Pure state matrix — client key release is NOT exercised here (dedicated
    // server has no client classes; same split as chainEpisodeCancelMatrix).
    static void duskSecureHeldProcessLifecycleMatrix(BiConsumer<Boolean, String> check) {
        // ① preemption (onInterrupt) of a SEALED-hold bunker
        BotState st = new BotState();
        DuskSecureChain chain = new DuskSecureChain(st, new WorldModel());
        BunkerProcess held = new BunkerProcess(3);
        held.attach(st);                       // active=true, exactly what tick() does
        st.bunker.endReason = "SEALED";        // what the SEALED-hold branch stamps in place
        st.bunker.goalReached = true;
        chain.adoptProcessForTest(held);
        chain.interruptEpisodeState("retreat");
        check.accept(chain.heldProcessForTest() == null, "interrupt drops the held process");
        check.accept(!st.bunker.active,
                "interrupt: bunker slot must not stay active (orphan) — active=" + st.bunker.active);
        check.accept("INTERRUPTED".equals(st.bunker.endReason),
                "interrupt: endReason must be INTERRUPTED, not stale SEALED — got " + st.bunker.endReason);
        check.accept(Boolean.TRUE.equals(st.bunker.goalReached),
                "interrupt: the sealed verdict (goalReached=true) is history, keep it readable");

        // ② cancelEpisode of a mid-dig bunker (no SEALED verdict yet)
        BotState st2 = new BotState();
        DuskSecureChain chain2 = new DuskSecureChain(st2, new WorldModel());
        BunkerProcess held2 = new BunkerProcess(3);
        held2.attach(st2);
        chain2.adoptProcessForTest(held2);
        chain2.cancelEpisodeState("agent cancel");
        check.accept(chain2.heldProcessForTest() == null, "cancel drops the held process");
        check.accept(!st2.bunker.active, "cancel: bunker slot must not stay active");
        check.accept("CANCELLED".equals(st2.bunker.endReason),
                "cancel: endReason must be CANCELLED — got " + st2.bunker.endReason);
        check.accept("agent cancel".equals(st2.bunker.lastError),
                "cancel: reason recorded on lastError — got " + st2.bunker.lastError);

        // ③ guard: st.bunker is SHARED with the user-verb mc.bot.bunker (UserTaskChain
        // slot). When duskSecure holds NO process, its interrupt/cancel must not stomp
        // a user bunker's live slot.
        BotState st3 = new BotState();
        DuskSecureChain chain3 = new DuskSecureChain(st3, new WorldModel());
        new BunkerProcess(3).attach(st3);      // user-verb bunker owns the slot
        st3.bunker.endReason = "SEALED";
        chain3.cancelEpisodeState("stray cancel");
        check.accept(st3.bunker.active,
                "no held process: cancel must NOT reset the user-verb bunker's slot");
        check.accept("SEALED".equals(st3.bunker.endReason),
                "no held process: user slot endReason untouched — got " + st3.bunker.endReason);
        chain3.interruptEpisodeState("retreat");
        check.accept(st3.bunker.active,
                "no held process: interrupt must NOT reset the user-verb bunker's slot");

        // ④ the shared helper itself: onCancelled must fire with the detail (the same
        // finalize hook UserTaskChain.cancel runs — path archives etc.), null process
        // is a no-op, and an already-inactive slot is left untouched.
        BotState st4 = new BotState();
        java.util.concurrent.atomic.AtomicReference<String> cancelledWith = new java.util.concurrent.atomic.AtomicReference<>();
        BotProcess probe = new BotProcess() {
            @Override public String kind() { return "probe"; }
            @Override public String failure() { return null; }
            @Override public void attach(BotState s) {}
            @Override public boolean tick(net.magicterra.worlddriver.bot.body.Body a,
                                          net.magicterra.worlddriver.bot.pathfinder.WorldView w, BotState s) { return false; }
            @Override public void onCancelled(String reason) { cancelledWith.set(reason); }
        };
        st4.bunker.active = true;
        BotProcess dropped = ChainProcessLifecycle.drop(probe, st4.bunker,
                ChainProcessLifecycle.INTERRUPTED, "preempted by retreat");
        check.accept(dropped == null, "drop returns null for assignment");
        check.accept("preempted by retreat".equals(cancelledWith.get()),
                "drop must run the process's onCancelled finalize hook — got " + cancelledWith.get());
        check.accept(!st4.bunker.active && "INTERRUPTED".equals(st4.bunker.endReason),
                "drop stamps+resets a live slot");
        check.accept(ChainProcessLifecycle.drop(null, st4.bunker, ChainProcessLifecycle.CANCELLED, "x") == null,
                "null process is a no-op");
        check.accept(!"x".equals(st4.bunker.lastError),
                "null process must not stamp the slot");
        BotState st5 = new BotState();          // inactive slot: verdict of a FINISHED run
        st5.bunker.endReason = "DONE";
        ChainProcessLifecycle.drop(probe, st5.bunker, ChainProcessLifecycle.CANCELLED, "late cancel");
        check.accept("DONE".equals(st5.bunker.endReason),
                "inactive slot keeps its finished verdict — got " + st5.bunker.endReason);

        // ⑤ gap#75-b: a preemption must ARM a re-try. The dropped dig leaves the bot
        // standing in its own half-dug 1×1 shaft, which reads cornered=true in the
        // HazardField — without a pending re-arm the legacy cornered start-gate
        // self-vetoes the reflex for the REST of the night (live 2026-07-14: a user
        // goto preempted the dusk dig, was itself cancelled, and the bot idled
        // exposed in an unsealed 2-deep pit all night). Only the process's own
        // verdict / dawn / an explicit cancel consume the pending re-arm.
        BotState st6 = new BotState();
        DuskSecureChain chain6 = new DuskSecureChain(st6, new WorldModel());
        BunkerProcess held6 = new BunkerProcess(2);
        held6.attach(st6);
        chain6.adoptProcessForTest(held6);
        check.accept(!chain6.rearmPendingForTest(), "no re-arm pending before any preemption");
        chain6.interruptEpisodeState("user");
        check.accept(chain6.rearmPendingForTest(),
                "gap#75-b: INTERRUPTED must not consume the dusk episode — re-arm must be pending");
        // The armed gate waives ONLY the cornered self-veto, and only inside the window.
        check.accept(!DuskSecureChain.startGateBlocks(true, true, true, true),
                "armed: the own-shaft cornered reading must not block the re-dig");
        check.accept(DuskSecureChain.startGateBlocks(true, true, true, false),
                "unarmed: genuine cornered still sits the reflex out (BunkerChain's case)");
        check.accept(DuskSecureChain.startGateBlocks(true, false, true, true),
                "armed but day/dawn: window closed, gate blocks");
        check.accept(DuskSecureChain.startGateBlocks(false, true, true, true),
                "armed but absent: gate blocks");
        check.accept(!DuskSecureChain.startGateBlocks(true, true, false, false),
                "plain exposed night, not cornered: gate open (legacy path unchanged)");
        // An explicit cancel stands down — it consumes the pending re-arm.
        BunkerProcess held6b = new BunkerProcess(2);
        held6b.attach(st6);
        chain6.adoptProcessForTest(held6b);
        chain6.cancelEpisodeState("agent cancel");
        check.accept(!chain6.rearmPendingForTest(), "cancel consumes the pending re-arm");
        // A stray interrupt with NOTHING held must not arm (no dig was preempted).
        BotState st7 = new BotState();
        DuskSecureChain chain7 = new DuskSecureChain(st7, new WorldModel());
        chain7.interruptEpisodeState("retreat");
        check.accept(!chain7.rearmPendingForTest(),
                "no held process: interrupt must not arm a re-dig");
    }

    private static void duskSecureHeldProcessLifecycleScene(SceneContext ctx) {
        duskSecureHeldProcessLifecycleMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
        duskSecureRearmWorldLeg(ctx);
    }

    /**
     * gap#75-b world leg (merged into the lifecycle scene — no new scene): the full live
     * incident shape over a REAL {@link BunkerProcess} on a FakePlayer. duskSecure starts a
     * dusk dig; a user goto (USER 50 > 40) preempts it mid-shaft; the goto is then cancelled
     * and the body is idle IN the half-dug, unsealed pit. Ground truth asserted from the real
     * world: that pit reads {@code cornered=true} in the HazardField and is still sky-exposed —
     * so pre-fix, priority()'s cornered start-gate self-vetoed and duskSecure never re-armed,
     * idling the bot exposed all night (live 2026-07-14, dayTime 13000). Asserts the pure
     * start-gate re-arms after the preemption, then drives the re-armed dig to a genuinely
     * SEALED pocket. (The dusk clock itself has no server seam — priority() is client-tick code
     * — so window state feeds the pure gate, same split as urgentBidMatrix.)
     */
    private static void duskSecureRearmWorldLeg(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        // Scrub the whole dirt slab + dig shaft on any exit (#40 persistent-world lesson).
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -8; dy <= 8; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Dirt slab with a 1×2 standing slot at the centre (serverBunkerArena shape),
        // deep enough for the re-armed dig to deepen + carve an embedded niche.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -6; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int dy = 2; dy <= 6; dy++)   // defensive air box above the slab
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));  // server breaks drop nothing → pre-stock plug blocks
        BotState st = driver.botState();
        DuskSecureChain chain = new DuskSecureChain(st, new WorldModel());

        // ① dusk: duskSecure starts the dig (live seq1945) — run until mid-shaft.
        BunkerProcess dig = new BunkerProcess(2);
        driver.runProcess(dig);                 // attaches to st, like chain.tick() does
        chain.adoptProcessForTest(dig);
        int t = 0;
        for (; t < 400 && fp.blockPosition().getY() > floorY; t++) driver.tick();
        if (fp.blockPosition().getY() > floorY)
            ctx.fail("rig: dig never went a block down in " + t + " ticks");

        // ② USER preempts mid-dig (live: endReason=INTERRUPTED "preempted by user").
        chain.interruptEpisodeState("user");
        if (chain.heldProcessForTest() != null || st.bunker.active)
            ctx.fail("rig: interrupt must drop the held process + slot");

        // ③ user goto cancelled; body idle IN the half-dug unsealed shaft. Evidence
        // for the root cause, from the real world:
        BlockPos foot = fp.blockPosition();
        HazardField hf = HazardField.compute(driver.world(), foot, 1,
                SurvivalMath.survivableFall(fp.getHealth()), BotConfig.deepWaterMax);
        boolean cornered = SurvivalFacts.cornered(hf);
        // "Still exposed" ground truth as a direct block scan — canSeeSky would read
        // the light engine, which hasn't recomputed the freshly-carved column within
        // this synchronous tick (live it reads true: duskExposed kept firing).
        boolean openAbove = true;
        for (int y = foot.getY() + 1; y <= floorY + 6; y++)
            if (driver.world().isSolid(new BlockPos(foot.getX(), y, foot.getZ()))) { openAbove = false; break; }
        WorldDriverCommon.LOG.info("[wd.duskSecureHeldProcessLifecycle/rearmWorldLeg] preempted@t{} foot={} cornered={} openAbove={} rearmPending={}",
                t, foot, cornered, openAbove, chain.rearmPendingForTest());
        if (!cornered)
            ctx.fail("rig: the half-dug 1×1 shaft must read cornered=true "
                    + "(that reading IS the live self-veto) — foot=" + foot);
        if (!openAbove)
            ctx.fail("rig: the unsealed shaft must still be open to the sky — foot=" + foot);

        // ④ THE gap: the dusk window is still open and the body is idle — the
        // start-gate must be willing to restart. Pre-fix it blocked all night.
        if (DuskSecureChain.startGateBlocks(true, true, cornered, chain.rearmPendingForTest()))
            ctx.fail("gap#75-b: duskSecure preempted mid-dig never re-arms — its own "
                    + "unsealed shaft reads cornered and the start-gate self-vetoes for the rest of the night "
                    + "(rearmPending=" + chain.rearmPendingForTest() + ")");

        // ⑤ re-arm exactly as tick() would: a fresh BunkerProcess from the pit floor
        // must finish the job — dig on, carve, step in, plug: genuinely SEALED.
        BunkerProcess redo = new BunkerProcess(2);
        driver.runProcess(redo);
        chain.adoptProcessForTest(redo);
        for (int i = 0; i < 800 && !"SEALED".equals(st.bunker.endReason); i++) driver.tick();
        WorldDriverCommon.LOG.info("[wd.duskSecureHeldProcessLifecycle/rearmWorldLeg] redo endReason={} lastErr={} foot={} enclosed={}",
                st.bunker.endReason, st.bunker.lastError, fp.blockPosition(),
                BunkerProcess.enclosed(driver.world(), fp.blockPosition()));
        if (!"SEALED".equals(st.bunker.endReason))
            ctx.fail("gap#75-b: re-armed bunker failed to seal — endReason="
                    + st.bunker.endReason + " lastError=" + st.bunker.lastError);
        if (!BunkerProcess.enclosed(driver.world(), fp.blockPosition()))
            ctx.fail("gap#75-b: SEALED verdict but the pocket is not enclosed at "
                    + fp.blockPosition());
    }

    // ==================================================================================
    // wd.cancelRouting — gap#72-② CancelRouting named-cancel resolution matrix (19 rows).
    // ==================================================================================

    // gap#72-②: mc.bot.cancel{process:"bunker"} returned ok:true while duskSecure's held
    // BunkerProcess sat untouched (live 2026-07-14). "bunker" is BOTH BunkerChain's chain
    // NAME and BunkerProcess's KIND: the old routing tried the user slot (dusk's process
    // isn't there), then scheduler.byName("bunker") — the IDLE BunkerChain's anchor — and
    // unconditionally said ok:true. A named cancel must resolve, in order: user slot by
    // kind → chain episode by NAME (only a LIVE one counts as a hit) → any chain-HELD
    // process of that kind (Chain.heldProcessKind, cancelled through the unified
    // ChainProcessLifecycle drop) — and the result must be honest: distinguishable
    // labels for what was actually cancelled, ok:false/no-active-target on zero hits.
    static void cancelRoutingMatrix(BiConsumer<Boolean, String> check) {
        // ① the incident: duskSecure holds a SEALED-hold BunkerProcess, BunkerChain idle.
        BotState st = new BotState();
        DuskSecureChain dusk = new DuskSecureChain(st, new WorldModel());
        BunkerProcess held = new BunkerProcess(3);
        held.attach(st);
        st.bunker.endReason = "SEALED";
        st.bunker.goalReached = true;
        dusk.adoptProcessForTest(held);
        BunkerChain bunkerChain = new BunkerChain();
        RetreatChain retreat = new RetreatChain(st);
        List<Chain> chains = List.of(bunkerChain, retreat, dusk);
        check.accept("bunker".equals(dusk.heldProcessKind()),
                "duskSecure holding a BunkerProcess must expose heldProcessKind=bunker — got "
                        + dusk.heldProcessKind());
        CancelRouting.Plan plan = CancelRouting.resolve("bunker", null, chains);
        check.accept(!plan.cancelUserProcess(), "incident: nothing in the user slot to cancel");
        check.accept(plan.episodeTargets().size() == 1 && plan.episodeTargets().get(0) == dusk,
                "incident: the ONE target must be duskSecure (held process by kind), not the idle "
                        + "BunkerChain — got " + plan.episodeTargets());
        check.accept(List.of("duskSecure/bunker-process").equals(plan.labels()),
                "incident: label must say what is actually cancelled — got " + plan.labels());
        // Execute the plan the way BotApiImpl does (server-safe state half of
        // cancelEpisode) and assert the process REALLY cancels + the slot resets.
        dusk.cancelEpisodeState("user-cancel");
        check.accept(dusk.heldProcessForTest() == null, "incident: held process really dropped");
        check.accept(!st.bunker.active && "CANCELLED".equals(st.bunker.endReason),
                "incident: slot reset with honest endReason — active=" + st.bunker.active
                        + " endReason=" + st.bunker.endReason);
        Map<String, Object> r = CancelRouting.honestResult(plan.labels(), "bunker");
        check.accept(Boolean.TRUE.equals(r.get("ok"))
                        && "duskSecure/bunker-process".equals(r.get("cancelled")),
                "incident: result must name the real target — got " + r);

        // ② nothing active anywhere: cancel{process:"bunker"} must be an honest miss
        // (the old unconditional ok:true is the live lie).
        CancelRouting.Plan miss = CancelRouting.resolve("bunker", null, chains);
        check.accept(!miss.cancelUserProcess() && miss.episodeTargets().isEmpty()
                        && miss.labels().isEmpty(),
                "empty world: no targets — got " + miss.labels());
        Map<String, Object> rm = CancelRouting.honestResult(miss.labels(), "bunker");
        check.accept(Boolean.FALSE.equals(rm.get("ok")),
                "empty world: ok must be false, not the unconditional true — got " + rm);
        check.accept("no-active-target".equals(rm.get("reason")),
                "empty world: reason=no-active-target — got " + rm);

        // ③ P1-⑦ regression: USER-verb bunker (kind in the user slot) still routes to
        // the user slot; an idle duskSecure must not be dragged in.
        CancelRouting.Plan user = CancelRouting.resolve("bunker", "bunker", chains);
        check.accept(user.cancelUserProcess(),
                "user-verb bunker: the user slot leg must hit");
        check.accept(user.episodeTargets().isEmpty(),
                "user-verb bunker: no chain episode to cancel — got " + user.episodeTargets());
        check.accept(List.of("user/bunker-process").equals(user.labels()),
                "user-verb bunker: label — got " + user.labels());

        // ④ chain NAME with a LIVE episode + a held process of the same kind elsewhere:
        // both are hit, labels distinguish them (episode vs held process).
        bunkerChain.anchorForTest().beginIfIdle(0, 64, 0);   // BunkerChain mid-dig
        BunkerProcess held2 = new BunkerProcess(3);
        held2.attach(st);
        dusk.adoptProcessForTest(held2);
        CancelRouting.Plan both = CancelRouting.resolve("bunker", null, chains);
        check.accept(both.episodeTargets().equals(List.of(bunkerChain, dusk)),
                "name+kind: BunkerChain episode first, then duskSecure's held process — got "
                        + both.episodeTargets());
        check.accept(List.of("bunker-episode", "duskSecure/bunker-process").equals(both.labels()),
                "name+kind: distinguishable labels — got " + both.labels());
        check.accept("bunker-episode,duskSecure/bunker-process".equals(
                        CancelRouting.honestResult(both.labels(), "bunker").get("cancelled")),
                "name+kind: cancelled joins all hits");
        bunkerChain.resetEpisodeState();
        dusk.cancelEpisodeState("cleanup");

        // ⑤ an IDLE chain matched by name is NOT a hit (BunkerChain anchor idle →
        // episodePhase null → cancel{process:"bunker"} with nothing held = miss ②
        // above already proves it); retreat's held flee is reachable by KIND.
        retreat.adoptProcessForTest(new RunAwayProcess(new BlockPos(0, 64, 0), 16, st.retreat));
        check.accept("runAway".equals(retreat.heldProcessKind()),
                "retreat holding a flee must expose heldProcessKind=runAway — got "
                        + retreat.heldProcessKind());
        CancelRouting.Plan flee = CancelRouting.resolve("runAway", null, chains);
        check.accept(flee.episodeTargets().equals(List.of(retreat))
                        && List.of("retreat/runAway-process").equals(flee.labels()),
                "kind=runAway: retreat's held flee is the target — got " + flee.labels());
        // user runAway AND reflex flee at once: both legs hit, both labelled.
        CancelRouting.Plan fleeBoth = CancelRouting.resolve("runAway", "runAway", chains);
        check.accept(fleeBoth.cancelUserProcess()
                        && List.of("user/runAway-process", "retreat/runAway-process").equals(fleeBoth.labels()),
                "kind=runAway with user flee: both hits labelled — got " + fleeBoth.labels());
        // (no cancelEpisode cleanup for retreat here — its client half touches
        // Minecraft.getInstance(); these are throwaway test-local objects.)
    }

    private static void cancelRoutingScene(SceneContext ctx) {
        cancelRoutingMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.manualSlotGraceMatrix — gap#68-⑪ AutoTool external-selection grace (8 rows).
    // ==================================================================================

    // gap#68-⑪: an external setHotbarSlot RPC (or human scroll) must not be clobbered
    // by AutoTool on the very next tick — if the live selection differs from the slot
    // AutoTool itself last wrote, honor the external change for a grace period instead
    // of overwriting it. Pure static gate — no Minecraft instance needed.
    static void manualSlotGraceMatrix(BiConsumer<Boolean, String> check) {
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.shouldYield(2, 7, 0),
                "selected(2) != lastAuto(7) = external change -> yield");
        check.accept(!net.magicterra.worlddriver.bot.auto.AutoTool.shouldYield(7, 7, 0),
                "no external change, no grace -> proceed");
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.shouldYield(7, 7, 10),
                "grace still counting -> yield");
        check.accept(!net.magicterra.worlddriver.bot.auto.AutoTool.shouldYield(2, -1, 0),
                "first tick (no lastAuto yet) -> proceed");
        // Arm/decrement/expire sequence (stepGrace): an armed grace of N yields N ticks
        // total, and — regression lock — a zero/negative config must clamp to a
        // single-tick grace that EXPIRES (returns 0), not load 0 and decrement to -1
        // (which skips the ==0 expiry forever and wedges AutoTool in a permanent yield).
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.stepGrace(0, 100) == 99,
                "fresh arm with config 100 -> 99 left after this tick");
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.stepGrace(1, 100) == 0,
                "last armed tick -> 0 = expired, re-arms cleanly");
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.stepGrace(0, 0) == 0,
                "config 0 clamps to single-tick grace that expires (no -1 wedge)");
        check.accept(net.magicterra.worlddriver.bot.auto.AutoTool.stepGrace(0, -7) == 0,
                "negative config clamps to single-tick grace that expires (no wedge)");
    }

    private static void manualSlotGraceMatrixScene(SceneContext ctx) {
        manualSlotGraceMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.nearestFirstScanMatrix — gap#67-⑤ NearestFirstScan.offsetsNearestFirst (5 rows).
    // ==================================================================================

    // gap#67-⑤ — MineProcess.scanForTarget and GoalResolver.findNearestStandForBlock
    // both walked a dy-outer / dx,dz-inner triple loop with a flat "cells scanned"
    // budget: at a large horizontal radius, ONE dy layer alone blows the whole
    // budget (r=32 -> 65x65=4225 cells/layer, 50_000 cap -> dy in [+4,+8] never
    // scanned at all), so a jungle-canopy log 6 blocks above the bot went
    // invisible even though it sits well inside both limits. The shared fix is
    // net.magicterra.worlddriver.bot.util.NearestFirstScan.offsetsNearestFirst: every
    // offset in the box, sorted ascending by squared distance from the origin,
    // so any budget cutoff drops the FARTHEST cells instead of a whole height band.
    static void nearestFirstScanMatrix(BiConsumer<Boolean, String> check) {
        // (a) full coverage: every offset in the box appears exactly once.
        net.minecraft.core.BlockPos[] atR32 =
                net.magicterra.worlddriver.bot.util.NearestFirstScan.offsetsNearestFirst(32, 8);
        check.accept(atR32.length == 65 * 65 * 17,
                "gap#67(a): offset count wrong: " + atR32.length);

        // (b) the exact reproduction from the live bug report: at r=32 the old
        // dy-outer loop never reached dy=+6 (budget dies mid-way through the
        // low dy layers) even though (8,6,6) is a NEAR cell (distSq=136, rank
        // near the very front once sorted) — it must land inside a 50_000 cap.
        int idx = indexOfOffset(atR32, 8, 6, 6);
        check.accept(idx >= 0 && idx < 50_000,
                "gap#67(b): near-but-high cell (8,6,6) must rank inside a 50k budget, got index " + idx);

        // (c) nearest-first really means sorted ascending by squared distance —
        // the property any budget cutoff relies on to drop the farthest cells.
        long prev = -1;
        boolean sorted = true;
        for (net.minecraft.core.BlockPos p : atR32) {
            long d2 = (long) p.getX() * p.getX() + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ();
            if (d2 < prev) { sorted = false; break; }
            prev = d2;
        }
        check.accept(sorted, "gap#67(c): offsets must be sorted ascending by squared distance");

        // (d) the origin itself (zero distance) is always first.
        check.accept(atR32[0].equals(net.minecraft.core.BlockPos.ZERO),
                "gap#67(d): nearest offset must be the origin itself");

        // (e) goto's call site (GoalResolver.findNearestStandForBlock, radius 64
        // per the brief) gets the same guarantee at the larger radius.
        net.minecraft.core.BlockPos[] atR64 =
                net.magicterra.worlddriver.bot.util.NearestFirstScan.offsetsNearestFirst(64, 8);
        int idx64 = indexOfOffset(atR64, 10, 6, 0);
        check.accept(idx64 >= 0 && idx64 < 50_000,
                "gap#67(e): radius-64 near-but-high cell (10,6,0) must still rank inside a 50k budget, got index " + idx64);
    }

    private static int indexOfOffset(net.minecraft.core.BlockPos[] arr, int x, int y, int z) {
        for (int i = 0; i < arr.length; i++)
            if (arr[i].getX() == x && arr[i].getY() == y && arr[i].getZ() == z) return i;
        return -1;
    }

    private static void nearestFirstScanMatrixScene(SceneContext ctx) {
        nearestFirstScanMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }
}
