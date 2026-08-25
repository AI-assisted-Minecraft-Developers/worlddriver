package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The N7–N10 hostile-mob family, split out of {@link WorldDriverProcessScenes} verbatim so that
 * class fits the 3000-line source budget — see {@link WorldDriverPortalScenes} for the shape of the
 * split. Blaze rod, dragon damage, the flying blaze, the ender pearl and the end crystal: the
 * scenes whose subject is a mob that has to be fought rather than a block that has to be placed.
 *
 * <p>{@code buildFloor} and {@code entityBox} are still {@link WorldDriverProcessScenes}' — they
 * are shared with the core process scenes, so they were widened to package-private rather than
 * copied.
 */
public final class WorldDriverMobFightScenes {

    private WorldDriverMobFightScenes() {}

    /** Registers this family in the order {@link WorldDriverProcessScenes#scenes()} used before the split. */
    static void register(List<Scene> out) {
        out.addAll(List.of(
                // N7: the blaze rod is the one drop on the critical path that vanilla gates on the
                // killer being a PLAYER. Measured at 11 rods from 24 kills — the uniform 0..1 roll,
                // so the condition is satisfied and the assertion is far from the coin flip a
                // single kill would have been.
                Scene.of("wd.serverEarnsABlazeRod", 4_000,
                        WorldDriverMobFightScenes::serverEarnsABlazeRod),
                // The summit's own question, and the last unmeasured verb on the road: a dragon is
                // not hit like a mob. Measured identically on both loaders — 200.0 -> 197.3 from the
                // existing combat loop, then 2.75 per hit aimed at the head — so it is required.
                Scene.of("wd.serverDamagesTheDragon", 4_000,
                        WorldDriverMobFightScenes::serverDamagesTheDragon),
                // The three the pinned probes deliberately could not answer. Each was named in a
                // javadoc as "no scene yet"; these are those scenes, and all three are green on both
                // loaders — the flying blaze only once it is given a room to be fought in.
                Scene.of("wd.serverFightsAFlyingBlaze", 8_000,
                        WorldDriverMobFightScenes::serverFightsAFlyingBlaze),
                Scene.of("wd.serverEarnsAnEnderPearl", 8_000,
                        WorldDriverMobFightScenes::serverEarnsAnEnderPearl),
                Scene.of("wd.serverBreaksAnEndCrystal", 4_000,
                        WorldDriverMobFightScenes::serverBreaksAnEndCrystal),
                // wd.serverFightsAFlyingBlaze produces this situation only when the blaze happens to
                // fly the body off its floor — once in three runs, and each run is forty minutes.
                // This stages the same reset loop on purpose, without a blaze, so the futile-search
                // gate's census can be read EVERY run instead of whenever the dice agree.
                Scene.of("wd.serverFutileGateUnderACreepingGoal", 4_000,
                        WorldDriverMobFightScenes::serverFutileGateUnderACreepingGoal)));
    }

    /**
     * Kill a blaze with a driven body and pick up the rod — the drop, not the kill, is the question.
     *
     * <p>A blaze rod is one of the very few things on the road to the dragon that vanilla will not
     * give to just anything that lands the killing blow: the loot table carries a
     * {@code killed_by_player} condition, satisfied from {@code lastHurtByPlayer}. A body that hits
     * hard enough to kill and does not register as a player kills the blaze and gets <b>nothing</b>,
     * and the failure is silent in the same way the advancement one was — the mob dies, the fight
     * looks won, and the eye of ender is never craftable.
     *
     * <p><b>What this scene deliberately does NOT cover: flight.</b> The blaze here is pinned the
     * way {@code wd.serverCombat}'s zombie is — no AI, knockback-resistant, re-pinned each tick — so
     * that a red result means "the drop does not reach a driven body" and cannot also mean "it flew
     * away". Whether the melee loop can reach a blaze that is actually hovering is a separate
     * question and needs its own scene; saying so here is the point, because a green row that
     * quietly meant "we never fought a flying mob" is the shape of coverage this suite exists to
     * refuse.
     */
    private static void serverEarnsABlazeRod(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);

        // The gamerule first, because it is the one explanation for "nothing dropped" that has
        // nothing to do with the body — and it is cheaper to read than to infer from eight kills.
        ctx.record("gamerule.doMobLoot", String.valueOf(
                level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT)));

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        var fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_SWORD));

        // TWENTY-FOUR, and the count is the measurement rather than padding. A blaze rod is a
        // uniform 0..1 roll, so ONE kill cannot tell "the player-kill condition failed" from "the
        // die came up zero" — the first run of this scene killed one blaze, saw an empty floor, and
        // the evidence was equally consistent with a broken drop and with a coin flip. Eight kills
        // then measured 0,0,1,0,0,0,1,0, which answers the question and is still far too close to a
        // coin flip to put in a gate that runs on every commit. Twenty-four puts an all-zero run
        // out of reach even if the true rate is half what the eight-kill sample suggested, and the
        // per-kill tally is recorded so a future red says which of the two explanations it is.
        final int kills = 24;
        int rods = 0, killed = 0;
        StringBuilder tally = new StringBuilder();
        for (int i = 0; i < kills; i++) {
            var blaze = new net.minecraft.world.entity.monster.Blaze(
                    net.minecraft.world.entity.EntityType.BLAZE, level);
            blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
            blaze.setNoAi(true);
            blaze.setPersistenceRequired();
            var kbr = blaze.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kbr != null) kbr.setBaseValue(1.0);
            level.addFreshEntity(blaze);

            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:blaze"));
            ServerAvatarManager.register(driver);
            for (int t = 0; t < 1_200 && blaze.isAlive(); t++) {
                ServerAvatarManager.tickAll();
                if (blaze.isAlive()) {
                    blaze.tick();                       // its hurt-cooldown, as wd.serverCombat does
                    blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
                    blaze.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
            }
            if (!blaze.isAlive()) killed++;
            for (int t = 0; t < 10; t++) ServerAvatarManager.tickAll();

            int here = 0;
            for (var d : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    WorldDriverProcessScenes.entityBox(cx, floorY, cz))) {
                if (d.getItem().is(Items.BLAZE_ROD)) here += d.getItem().getCount();
                d.discard();                            // clear the floor so the next kill is read alone
            }
            rods += here;
            tally.append(tally.length() == 0 ? "" : ",").append(here);
            blaze.discard();
        }

        ctx.record("blaze.killed", killed + "/" + kills);
        ctx.record("rods.perKill", tally.toString());
        ctx.record("rods.total", rods + "");
        ctx.expect(killed).as("a driven body can kill a blaze at all").isEqualTo(kills);
        // The whole point: vanilla gates the rod on killed_by_player, read from lastHurtByPlayer.
        // A body that kills without registering as a player clears fortresses and crafts no eyes.
        ctx.expect(rods).as("the kills count as PLAYER kills, so rods actually drop").isAtLeast(1);
        ctx.passNote("铁剑打死 " + killed + " 只烈焰人, 掉出 " + rods + " 根棒（钉住的, 没测飞行）");
    }

    /**
     * Can a driven body hurt the ender dragon at all?
     *
     * <p>The summit's own question, and the one verb on the road to it that is not shaped like any
     * other fight. <b>A dragon does not take damage as itself.</b> {@code EnderDragon.hurt} refuses
     * every direct hit; damage only lands through an {@code EnderDragonPart}, and only the HEAD part
     * takes it undivided — every other part divides it by four and forwards it. So a combat loop
     * that finds "the nearest entity of type {@code minecraft:ender_dragon}" and swings at its
     * position is aiming at something with no hittable hitbox there, and would report a fight it is
     * winning while the boss bar never moves.
     *
     * <p>This scene therefore measures the SEAM rather than the strategy: it puts the body beside a
     * pinned dragon and asks whether the driver's own attack path can take health off it. What it
     * deliberately does not cover is the fight — crystals, perching, the flight pattern — none of
     * which is worth designing before knowing whether the hit lands.
     *
     * <p>Staged: the arena, and the dragon is pinned with no AI and no phase, because a dragon that
     * flies is measuring navigation. A red here means the attack path cannot reach a multipart
     * entity; it cannot also mean the body could not catch up.
     */
    private static void serverDamagesTheDragon(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);

        var dragon = new net.minecraft.world.entity.boss.enderdragon.EnderDragon(
                net.minecraft.world.entity.EntityType.ENDER_DRAGON, level);
        dragon.setNoAi(true);
        dragon.setPos(cx + 3.5, floorY + 1, cz + 0.5);
        level.addFreshEntity(dragon);
        ctx.cleanup(() -> dragon.discard());

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ctx.await(() -> !level.getEntitiesOfClass(
                        net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                        WorldDriverProcessScenes.entityBox(cx, floorY, cz)).isEmpty())
                .within(200).then(() -> {
            ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            var fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));

            float before = dragon.getHealth();
            ctx.record("dragon.hp0", String.format(java.util.Locale.ROOT, "%.1f", before));

            // 1. What the existing combat loop does, unchanged — the reading that says whether the
            //    ladder can reuse it or has to learn the parts.
            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:ender_dragon"));
            ServerAvatarManager.register(driver);
            for (int t = 0; t < 600; t++) {
                ServerAvatarManager.tickAll();
                dragon.setPos(cx + 3.5, floorY + 1, cz + 0.5);
                dragon.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                dragon.hurtTime = 0;                     // its own cooldown, as the other fights do
            }
            float afterCombat = dragon.getHealth();
            ctx.record("dragon.hpAfterCombatProcess",
                    String.format(java.util.Locale.ROOT, "%.1f", afterCombat));

            // 2. The head, by hand, as a control on WHERE the damage lands — the head takes a hit
            //    undivided and every other part takes a quarter of it, so a loop that is only ever
            //    clipping a wing is winning four times slower than its evidence suggests.
            //
            //    The first version of this step read a flat ZERO and the fault was in the harness,
            //    not the dragon: it reset `hurtTime`, which is the red-flash timer, and left
            //    `invulnerableTime`, which is the one that actually refuses damage for 20 ticks.
            //    It also reset the attack-strength ticker AFTER swinging, so every swing landed at
            //    the bottom of the cooldown curve. Both are fixed here; the lesson is that a
            //    control which measures the test rig reads exactly like a capability that is missing.
            var head = dragon.getSubEntities()[0];
            for (var part : dragon.getSubEntities())
                if ("head".equals(part.name)) head = part;
            ctx.record("dragon.parts", dragon.getSubEntities().length + " 个（瞄 " + head.name + "）");
            float beforeHead = dragon.getHealth();
            int swings = 10;
            for (int i = 0; i < swings; i++) {
                dragon.invulnerableTime = 0;
                dragon.hurtTime = 0;
                fp.resetAttackStrengthTicker();
                for (int t = 0; t < 15; t++) ServerAvatarManager.tickAll();   // let the swing recharge
                fp.attack(head);
            }
            float afterPart = dragon.getHealth();
            ctx.record("dragon.hpAfterHeadHits",
                    String.format(java.util.Locale.ROOT, "%.1f", afterPart));
            ctx.record("dragon.perHeadHit", String.format(java.util.Locale.ROOT, "%.2f",
                    (beforeHead - afterPart) / swings));

            ctx.expect(afterCombat < before)
                    .as("the existing combat loop takes health off the dragon").isTrue();
            ctx.expect(afterPart < beforeHead)
                    .as("a hit aimed at the head lands on a multipart boss").isTrue();
            ctx.passNote("龙血 " + before + " → CombatProcess 后 " + afterCombat
                    + " → 再打头部 " + swings + " 下后 " + afterPart + "（钉住的, 没测飞行/水晶）");
        });
    }

    /**
     * The blaze that is allowed to fly — the half {@code wd.serverEarnsABlazeRod} pinned away.
     *
     * <p>That scene answered the drop and said in its own javadoc that it could not answer this,
     * because a target held at ground level measures the loot table and nothing about reach. A blaze
     * hovers, drifts, and shoots from above; a melee loop that can only hit what is standing next to
     * it wins the pinned fight and loses every real one.
     *
     * <p><b>The body is invulnerable</b> ({@code AvatarFakePlayer.isInvulnerableTo} → true), so this
     * cannot say whether a real run survives the fireballs — only whether the fight can be WON. That
     * limit is recorded on the green row rather than left for a reader to discover.
     *
     * <p><b>Measured, and the open-sky answer is NO.</b> Given six thousand ticks the loop took a
     * blaze from 20 health to 8 and never finished it: the mob hovers six to eight blocks above the
     * floor and melee reaches roughly three. So this scene runs the fight TWICE — once under open
     * sky, which fails, and once in a closed room, which is what a player builds at a spawner. The
     * fix is a different room, not a new verb. Only the room is asserted: the open round is recorded
     * because an assertion that a fight is NOT won sits on the wrong side of the dice — a NeoForge
     * run finished the open blaze at 2.0 health left, which would have reddened the gate for the one
     * reason that is good news.
     *
     * <p><b>A ceiling alone was not enough either.</b> A bare lid over an open floor got the blaze
     * to 2 health and still lost it — it drifted out past the lid's edge and climbed above it. What
     * contains a blaze is walls plus a ceiling, and that ordering (sideways first, then up) is the
     * useful part of the finding for whoever builds the room in the field.
     */
    private static void serverFightsAFlyingBlaze(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);
        ctx.cleanup(() -> {
            for (int dx = -6; dx <= 6; dx++)
                for (int dy = 1; dy <= 8; dy++)
                    for (int dz = -6; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        var fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_SWORD));

        // WHY THIS CENSUS IS HERE, AND WHY IT HAD TO COME FIRST.
        //
        // The futile-search gate (WalkerTickSearch, walkerFutileSearchCap) exists precisely to stop
        // a body re-asking a question it cannot answer. On the 2026-08-24 J60-C run it never fired
        // in this scene: the body chased the blaze off this 11x11 floor, fell 282 blocks to the
        // world bottom, and then spent >=29 s of the scene's 48 s in 29 consecutive searches that
        // each burned ~64k nodes toward a goal 267 blocks straight up — with zero "no route
        // progress" rows to show for it.
        //
        // Walker.futileGateBuckets already counts which of the gate's nine doors every search left
        // by, but it is read by the journey rig and by NOTHING else, so the one scene where the
        // gate demonstrably fails has never been able to say which door. Reading the code turns up
        // two candidate doors — bucket 5 (no path while stuck-penalties are live, so the gate never
        // judged the search at all) and bucket 7 (CombatProcess re-goals on every blaze block-move,
        // Walker.setGoal wipes searchGov, and a null futileFoot reads as "the body moved") — and
        // code alone cannot choose between them. This census chooses.
        //
        // It goes in BEFORE the rim that will stop the body leaving the floor, because that rim
        // removes the only occasion this defect has anywhere in the suite.
        final long[] futileAtStart = futileSnapshot();

        // Round 1: open sky. Measured, not assumed — and it does NOT work.
        var openRun = new BlazeFightRun(ctx, level, driver, fp, cx, cz, floorY, 3_000, "open");
        ctx.await(openRun::pump).within(openRun.tickAllowance()).then(() -> {
            var open = openRun.finish();
            // Split per round, and recorded here rather than in a cleanup. The open round is the
            // one that churns; folding both rounds into one row would let the roofed round's 40
            // tidy iterations dilute it. A cleanup could not carry either row: the harness calls
            // record(...) — which serialises ctx.records() into the results file — BEFORE
            // teardown(...) on every outcome, so a row written in a cleanup lands in the log and
            // in the ledger and is absent from the file the verdict is read from.
            final long[] futileAfterOpen = futileSnapshot();
            ctx.record("futileGate.open", futileGateLine(futileAtStart, futileAfterOpen));
            // Round 2: the same fight in a closed room. This is the hardcoded step, and it is what a
            // player does at a spawner: not a new engine verb, a different room.
            //
            // A CEILING ALONE IS NOT ENOUGH, and that was measured too: a bare 9x9 lid over an 11x11
            // floor took the blaze from 20 health to 2 and still lost it, because the mob drifted out
            // past the lid's edge and climbed to 6.2 above a ceiling that was 4 up. Walls are not
            // decoration here — the thing being contained moves sideways first.
            final int rr = 5, hh = 4;
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++)
                    for (int dy = 1; dy <= hh; dy++)
                        if (Math.abs(dx) == rr || Math.abs(dz) == rr || dy == hh)
                            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                    Blocks.STONE.defaultBlockState());
            var roofedRun = new BlazeFightRun(ctx, level, driver, fp, cx, cz, floorY, 3_000, "roofed");
            ctx.await(roofedRun::pump).within(roofedRun.tickAllowance()).then(() -> {
                var roofed = roofedRun.finish();
                ctx.record("futileGate.roofed", futileGateLine(futileAfterOpen, futileSnapshot()));

                ctx.record("body.invulnerable", "true —— 所以这一条只说打得赢, 不说活得下来");
                // The open-sky round is RECORDED, not asserted, and that is a deliberate correction.
                // It was written as `expect(open.dead).isFalse()` — "notice if the open fight ever
                // becomes winnable" — until a NeoForge run finished it at 2.0 health left. An
                // assertion that a fight is NOT won sits on the wrong side of the RNG: one lucky run
                // reddens the gate for the one reason that is good news. The claim this scene makes
                // is about the ROOM; the open number is the reason the room is in the plan, and it
                // lives in the evidence where a human reads it.
                ctx.expect(roofed.dead)
                        .as("in a closed room a driven body kills a blaze that is free to fly")
                        .isTrue();
                ctx.passNote("露天 " + open.ticks + " tick 打不死（剩 "
                        + String.format(java.util.Locale.ROOT, "%.1f", open.hp)
                        + " 血, 最高离地 " + String.format(java.util.Locale.ROOT, "%.1f", open.rise)
                        + " 格）；加个四格高的顶后 " + roofed.ticks + " tick 打死");
            });
        });
    }

    /** One unpinned blaze fight, reported rather than asserted — the caller decides what it means. */
    private record BlazeFight(boolean dead, int ticks, float hp, double rise) {}

    /**
     * The futile-search gate, read on a goal that creeps — the loop {@code wd.serverFightsAFlyingBlaze}
     * only produces by accident.
     *
     * <p><b>What this reproduces.</b> On the 2026-08-24 run that fight chased its blaze off an 11x11
     * floor; the body fell 282 blocks to the world bottom and then spent 29 consecutive searches,
     * each ~64k nodes and over a second, on a goal 267 blocks straight up. The gate that exists to
     * stop exactly that ({@code BotConfig.walkerFutileSearchCap}) never fired, and the log carried no
     * "no route progress" row to explain why. Two candidate doors survive a code read and cannot be
     * told apart by one: the gate may never have judged those searches at all (bucket 5), or the
     * counter may have been wiped from OUTSIDE — {@code CombatProcess.approach} re-goals whenever the
     * target changes block, {@code Walker.setGoal} is the only caller of {@code searchGov.reset()},
     * and a reset leaves {@code futileFoot} null, which the next judged search reads as "the body
     * moved" (bucket 7).
     *
     * <p><b>Why a scene rather than another run of the fight.</b> Whether the blaze flies the body
     * off the floor is a coin toss taken once per forty-minute gate. The situation itself is three
     * facts — a body that cannot move, a goal it cannot reach, and a goal that creeps a block closer
     * every other tick — and all three can simply be staged. That is the hardcoded-steps rule
     * applied to a defect instead of to a rung: do not add an engine capability to observe something
     * a room can be built for.
     *
     * <p><b>What it measured, before the fix.</b> {@code still} took 9 searches in 240 ticks;
     * {@code creep} took 240 — one full search every tick — with bucket 6 ("got closer") reading
     * exactly 120, the re-goal count, 1:1. Neither door in the paragraph above was the one: the
     * counter was wiped by {@code setGoal}, whose {@code reset()} leaves the baseline at
     * {@code +INFINITY}, so the next judged search was trivially "closer" than infinity. Under the
     * measurement the counter never passed 1 against a cap of 5.
     *
     * <p><b>Four arms.</b> {@code still} holds the goal fixed — the gate left alone. {@code creepSetGoal}
     * is the recorded baseline, still calling {@code setGoal} on each change, because that contract is
     * still live for callers that need a terminal cleared. {@code creepRetarget} calls
     * {@code retargetGoal} — {@code CombatProcess.approach}'s idiom after the fix, at the blaze's exact
     * descent rate. {@code chase} is the reverse: a quarry the body CAN reach, re-goaled at the same
     * rate, where the guard must stay silent.
     *
     * <p><b>Every arm runs its full 240 ticks and ignores the walker's verdict</b>, exactly as
     * {@code approach} does. An arm that stopped at the first terminal would report a tidy
     * "6 searches, FAILED" and miss the expensive half: {@code terminal()} stores nothing, so a
     * latched cap re-reports itself by running another full search every tick until something moves
     * the body. That is why the assertion counts searches over the whole arm rather than looking for
     * the failure row.
     */
    private static void serverFutileGateUnderACreepingGoal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);
        ctx.cleanup(() -> {
            for (int dx = -6; dx <= 6; dx++)
                for (int dy = 1; dy <= 8; dy++)
                    for (int dz = -6; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        BotConfig.walkerDebug = false;
        // The goal has to be UNREACHABLE, not merely far. With either of these left on, the body
        // pillars up to it or digs its way somewhere, the search succeeds, and the gate is never
        // asked the question this scene exists to ask.
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        // Small on purpose. Whether the gate counts a search does not depend on how many nodes that
        // search burned, and the live case burned ~64k of them per tick — reproducing the COST here
        // would buy nothing and spend a minute of every gate run.
        BotConfig.pathfinderMaxNodes = 2_000;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        fp.getInventory().clearContent();          // nothing to pillar with even if allowPlace flips
        LevelWorldView w = new LevelWorldView(level, fp);

        // 200 up, dead centre: no staircase, no wall, nothing to climb. Near(…, 2) rather than
        // Block so the goal has the same tolerance the combat approach gives it.
        final BlockPos high = new BlockPos(cx, floorY + 200, cz);

        var still = creepArm(av, fp, w, "still", high, cx, floorY, cz, 0, false);
        var setGoalArm = creepArm(av, fp, w, "creepSetGoal", high, cx, floorY, cz, 2, false);
        var retargetArm = creepArm(av, fp, w, "creepRetarget", high, cx, floorY, cz, 2, true);
        var chase = chaseArm(av, fp, w, cx, floorY, cz);

        ctx.record("gate.still", still.line());
        ctx.record("gate.creepSetGoal", setGoalArm.line());
        ctx.record("gate.creepRetarget", retargetArm.line());
        ctx.record("gate.chase", chase.line());
        ctx.passNote("够不着的目标：不动 / 每 2 tick 降一格（setGoal 基线 vs retargetGoal）；"
                + "外加一条够得着的追击反证。闸的去向见 gate.*");

        // A census that silently counts nothing reads exactly like a gate that judged everything and
        // let it pass, so prove the channel spoke before reading anything else out of it.
        for (var arm : java.util.List.of(still, setGoalArm, retargetArm, chase))
            if (arm.searches() == 0)
                ctx.fail("闸的普查通道是哑的，有一臂一次搜索都没发生 —— 这行读数不能用来判任何事：" + arm.line());

        // The claim the fix makes, stated as a number the arm can miss. A cap of 5 admits one
        // unjudged seeding search plus the five it counts; anything past that means the body is
        // still paying for a goal it cannot reach — either the counter is being wiped from outside
        // again, or the latched terminal is re-searching every tick.
        int cap = BotConfig.walkerFutileSearchCap;
        if (retargetArm.searches() > cap + 2)
            ctx.fail("追一个够不着的目标，240 tick 里搜了 " + retargetArm.searches()
                    + " 次（上限 " + cap + "，允许 " + (cap + 2) + "）：" + retargetArm.line());
        if (!retargetArm.noRoute())
            ctx.fail("闸压根没开火：追一个 200 格头顶、既不能挖也不能垒的目标，"
                    + "240 tick 里没有出现 no route progress：" + retargetArm.line());

        // The reverse. A guard that fires on a healthy pursuit is worse than one that never fires,
        // and "the body stayed put" is exactly what a body walking toward a moving quarry does NOT do.
        if (chase.noRoute())
            ctx.fail("闸误伤了一次正常追击：目标就在同一层地板上 8 格外，身体一路在走，"
                    + "却报了 no route progress：" + chase.line());

        recoverArm(ctx, av, fp, w, high, cx, floorY, cz);
    }

    /**
     * The latch must not outlive the pursuit that earned it.
     *
     * <p>The latch's only key is body displacement, which is right while the pursuit is the same
     * one — "unreachable from here" stops being true when "here" changes. It is WRONG across
     * pursuits, and {@code CombatProcess} now depends on that: a body latched on an unreachable
     * flying blaze would carry the latch into the walk toward a zombie it could plainly reach, and
     * never start a search again. The tick budget cannot save it either — every re-goal clears
     * {@code totalTicks}, so it never fills. Silent freeze, no terminal, no log.
     *
     * <p>What holds the two apart is that {@code setGoal} means "new journey" and clears everything,
     * while {@code retargetGoal} means "same journey, the cell moved". The caller decides which,
     * because only the caller knows whether the quarry is the same entity. This arm asserts the
     * engine half of that contract: latch, DON'T move the body, then set a genuinely new goal within
     * easy reach — searches must resume and the body must go.
     */
    private static void recoverArm(SceneContext ctx, ServerPlayerAvatar av, ServerPlayer fp,
                                   LevelWorldView w, BlockPos high, int cx, int floorY, int cz) {
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);
        Walker walker = new Walker();
        BlockPos target = high;
        walker.setGoal(new Goal.Near(target, 2));
        boolean latched = false;
        for (int t = 0; t < ARM_TICKS && !latched; t++) {
            if (t > 0 && t % 2 == 0) {
                target = target.below();
                walker.retargetGoal(new Goal.Near(target, 2));
            }
            walker.tick(av, w);
            if (walker.lastError != null && walker.lastError.startsWith("no route progress")) latched = true;
            av.step();
        }
        BlockPos stuckAt = fp.blockPosition();
        long[] before = futileSnapshot();
        walker.setGoal(new Goal.Near(new BlockPos(cx + 4, floorY + 1, cz), 1));
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < ARM_TICKS; t++) {
            s = walker.tick(av, w);
            av.step();
        }
        long[] after = futileSnapshot();
        BlockPos end = fp.blockPosition();
        long searches = searchSum(before, after);
        String line = "recover：第一段" + (latched ? "闩上了" : "⚠️ 没闩上") + "（身体停在 "
                + stuckAt.toShortString() + "），身体不动改下一个 8 格外的新目标，"
                + ARM_TICKS + " tick 后收在 " + s + "，身体停在 " + end.toShortString()
                + "；" + futileGateLine(before, after);
        ctx.record("gate.recover", line);

        // Phase 1 not latching would make phase 2 vacuous — it would prove a latch can be cleared
        // that was never set. Say so rather than reporting a pass.
        if (!latched)
            ctx.fail("这一臂的前提没成立：第一段没能闩住，于是第二段证明不了任何事：" + line);
        else if (searches == 0)
            ctx.fail("闩活过了它自己那趟追击：身体没挪，但换了一个 8 格外够得着的新目标之后，"
                    + ARM_TICKS + " tick 里一次搜索都没起 —— 这是无声冻结，没有终局也没有日志：" + line);
        else if (end.equals(stuckAt))
            ctx.fail("搜索恢复了但身体没动：新目标就在同一层地板上 8 格外，"
                    + ARM_TICKS + " tick 后仍停在 " + end.toShortString() + "：" + line);
    }

    /**
     * The reverse arm: a quarry the body CAN reach, re-goaled at the same rate as the creeping one.
     *
     * <p>The futile guard's whole job is to distinguish "unreachable from here" from "still walking",
     * and the fix hands it two new ways to be wrong — a counter that survives a re-goal, and a latch
     * that suppresses searches until the body moves. Both would show up here as a {@code no route
     * progress} on a body that is plainly making progress. Same floor, same re-goal call, same tick
     * budget; the only thing that changed is that the goal is eight blocks away instead of two
     * hundred straight up.
     */
    private static ArmReading chaseArm(ServerPlayerAvatar av, ServerPlayer fp, LevelWorldView w,
                                       int cx, int floorY, int cz) {
        fp.setPos(cx - 4.5, floorY + 1, cz + 0.5);
        long[] before = futileSnapshot();
        Walker walker = new Walker();
        walker.setGoal(new Goal.Near(new BlockPos(cx + 4, floorY + 1, cz), 1));
        int regoals = 1;
        boolean noRoute = false;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < ARM_TICKS; t++) {
            if (t > 0 && t % 20 == 0) {                 // the quarry strolls back along the floor
                walker.retargetGoal(new Goal.Near(new BlockPos(cx + 4 - (t / 20) % 9, floorY + 1, cz), 1));
                regoals++;
            }
            s = walker.tick(av, w);
            if (walker.lastError != null && walker.lastError.startsWith("no route progress")) noRoute = true;
            av.step();
        }
        BlockPos end = fp.blockPosition();
        long[] after = futileSnapshot();
        return new ArmReading("chase：" + ARM_TICKS + " tick 跑满，下了 " + regoals + " 次目标，收在 " + s
                + "，身体停在 " + end.getX() + "," + end.getY() + "," + end.getZ()
                + "；" + futileGateLine(before, after), searchSum(before, after), noRoute);
    }

    /**
     * One arm's reading. The line is for a human; the two numbers are what the scene judges on.
     *
     * <p>{@code searches} is the arm's whole share of the gate, terminals included — the count is the
     * cost, and a terminal that keeps searching costs exactly as much as one that never fired.
     * {@code noRoute} says whether the guard ever spoke, which is a different question from whether
     * it should have: one arm asserts it fired, another asserts it did not.
     */
    private record ArmReading(String line, long searches, boolean noRoute) {}

    private static long searchSum(long[] before, long[] after) {
        long sum = 0;
        for (int i = 0; i < after.length; i++) sum += after[i] - before[i];
        return sum;
    }

    private static final int ARM_TICKS = 240;

    /**
     * One arm: re-seat the body, hand the walker an unreachable goal, and tick.
     *
     * <p>{@code regoalEvery} is the whole experiment. Zero means the goal is set once and never
     * touched, so {@code searchGov} is reset exactly once, at the start. Two means the goal drops a
     * block every second tick and is re-issued each time it changes — {@code CombatProcess.approach}
     * verbatim, at the rate the blaze actually sank. Everything else is identical between the arms,
     * which is what makes the difference between their census rows readable.
     *
     * <p>{@code retarget} picks WHICH re-goal call the arm makes. Both shapes stay measured: the
     * {@code setGoal} arm is the recorded baseline (240 searches out of 240 ticks) and its contract
     * is still live elsewhere — {@code CombatProcess.collectSweep} relies on {@code setGoal} clearing
     * a terminal — so it is a control arm, not a defect left in place.
     *
     * <p><b>The loop deliberately ignores the walker's return value</b>, exactly as {@code approach}
     * does. An arm that stopped at the first terminal could not see the expensive half of this bug:
     * {@code terminal()} stores nothing, so a latched cap re-reports itself by running another full
     * search every single tick. Stopping at the terminal turns that into a tidy "6 searches, FAILED"
     * while the real fight keeps burning one A* per tick behind it.
     */
    private static ArmReading creepArm(ServerPlayerAvatar av, ServerPlayer fp, LevelWorldView w, String tag,
                                       BlockPos high, int cx, int floorY, int cz,
                                       int regoalEvery, boolean retarget) {
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);     // every arm starts from the same cell
        long[] before = futileSnapshot();
        Walker walker = new Walker();
        BlockPos target = high;
        walker.setGoal(new Goal.Near(target, 2));      // the first order is a new journey in every arm
        int regoals = 1;
        int firstTerminal = -1;
        String firstError = null;
        boolean noRoute = false;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < ARM_TICKS; t++) {
            if (regoalEvery > 0 && t > 0 && t % regoalEvery == 0) {
                target = target.below();
                Goal.Near g = new Goal.Near(target, 2);
                if (retarget) walker.retargetGoal(g); else walker.setGoal(g);
                regoals++;
            }
            s = walker.tick(av, w);
            if (walker.lastError != null && walker.lastError.startsWith("no route progress")) noRoute = true;
            if (s != Walker.Step.WALKING && firstTerminal < 0) {
                firstTerminal = t;
                firstError = walker.lastError;
            }
            av.step();
        }
        long[] after = futileSnapshot();
        return new ArmReading(tag + "：" + ARM_TICKS + " tick 跑满，下了 " + regoals + " 次目标，"
                + (firstTerminal < 0 ? "全程没有终局" : "首个终局在 t=" + firstTerminal + "（" + firstError + "）")
                + "，收在 " + s + "；" + futileGateLine(before, after),
                searchSum(before, after), noRoute);
    }

    private static long[] futileSnapshot() {
        long[] v = new long[Walker.FUTILE_GATE_BUCKETS.length];
        for (int i = 0; i < v.length; i++) v[i] = Walker.futileGateBuckets.get(i);
        return v;
    }

    /**
     * One round's share of the futile-search gate, bucket by bucket.
     *
     * <p>Buckets 0-5 are searches the gate never judged; 6-8 are what it did with the ones it did.
     * The counters are static and shared by every scene in the process, so only a DELTA between two
     * snapshots means anything here.
     *
     * <p>A zero sum gets its own sentence rather than nine {@code =0}s. Nine zeros read exactly like
     * "the gate judged nothing and let everything through", when what they actually say is that the
     * walker never searched at all this round — the difference between an answer and a dead channel.
     */
    private static String futileGateLine(long[] before, long[] after) {
        long sum = 0;
        StringBuilder sb = new StringBuilder();
        String[] names = Walker.FUTILE_GATE_BUCKETS;
        for (int i = 0; i < names.length; i++) {
            long v = after[i] - before[i];
            sum += v;
            if (i > 0) sb.append('，');
            sb.append(names[i]).append('=').append(v);
        }
        return sum == 0
                ? "这一轮一次搜索都没有 —— 走行器没搜过路，别把这行读成「闸放行了」"
                : "这一轮 " + sum + " 次搜索，闸的去向：" + sb;
    }

    /**
     * A blaze fight that is spread ACROSS server ticks instead of crammed into one.
     *
     * <p><b>What the old shape cost, and why a yield alone would not have fixed it.</b> This fight
     * used to be a plain {@code for} loop calling {@link ServerAvatarManager#tickAll()} up to
     * {@code budget} times, so the entire fight — every one of those simulated ticks, each of which
     * may run a pathfinder search — happened inside a single {@code MinecraftServer.tickServer()}.
     * On a healthy run that is 785–1355 ms and nobody notices. On three dedicated-NeoForge runs out
     * of eight the server crossed {@code max-tick-time=60000} and the hang watchdog killed it
     * mid-suite.
     *
     * <p>The cause is arithmetic, not a bug in anything it calls, and two hypotheses were measured
     * dead before it was found. It is <i>not</i> a loader difference: both loaders run the identical
     * iteration count (3000 open / 40 roofed) and NeoForge is the faster one per iteration (0.26 ms
     * vs 0.45 ms). It is <i>not</i> one runaway search either: a guard that reports any single
     * pathfinder expansion over 100 ms printed nothing at all during a tick that lasted 60 seconds.
     * What is left is the sum. {@code BotConfig.pathfinderIdleSliceMs} is 30 ms and
     * {@code WalkerTickSearch} deliberately spends that idle slice on a tick where the body has no
     * walkable path — which is every tick of a body chasing a blaze hovering out of reach. 3000
     * iterations x ~20 ms lands exactly on the 60 s the watchdog measured. The slice cap was working
     * the whole time; the loop calling it had no clock budget at all.
     *
     * <p>So the fix is a <b>per-tick wall-clock budget</b>, not merely a yield: a loop that yields
     * once but still runs all 3000 iterations across two ticks has only halved the problem. Each
     * {@link #pump()} stops starting new iterations once {@link #SLICE_MS} is gone and returns false
     * to be resumed on the next server tick, and it always completes at least one iteration so the
     * fight cannot stall.
     *
     * <p><b>⚠️ That budget bounds the ITERATION COUNT per tick, never the tick. It cannot.</b> The
     * deadline can only be read between iterations, so a pump costs its slice <i>plus one whole
     * iteration's overrun</i> — and the overrun is unbounded here, because every one of these scenes
     * sets {@code BotConfig.pathfinderSliceMs} and {@code pathfinderMaxMs} to {@code Long.MAX_VALUE
     * / 2} (see the four setup blocks) so that a search is never truncated mid-measurement. Bounded
     * iterations, unbounded cost per iteration: a single slow search inside {@code tickAll()} blows
     * the slice on its own, with nothing underneath to catch it. Measured on the NeoForge jumpfix
     * A1 run: {@code worstIterMs=80.6} against a 40 ms budget, {@code worstServerTickMs=116.8}.
     * So the old 60 s hang is gone — the sum is bounded now — but the failure mode that replaced it
     * is a rare single iteration, and <b>it is intermittent: one green run does not retire it.</b>
     * Any overrun is logged the instant it happens rather than only in {@link #finish()}, because a
     * tick that kills the server never reaches {@code finish()} and takes every {@code ctx.record}
     * with it. Actually capping it means giving the search back a real budget, which changes the
     * fight being measured — an A/B, not a tidy-up, and not yet done.
     *
     * <p>The {@code blaze.tick()} / {@code tickAll()} interleaving is preserved exactly, because it
     * is a real requirement rather than an artifact: the mob and the body must advance in lockstep
     * or the fight being measured is not the fight the field sees.
     */
    private static final class BlazeFightRun {
        /**
         * How much wall clock one server tick may give this fight. Three orders of magnitude under
         * {@code max-tick-time=60000}, and under a vanilla 50 ms tick so the server keeps pace.
         *
         * <p><b>Do not copy this number into a production process.</b> 40 ms inside a 50 ms tick
         * means the server runs at roughly half speed for the ~30 ticks the fight spans, which is
         * fine for a scene that owns the world and is measured on total wall clock, and not fine at
         * all for anything sharing a tick with players. The pattern is worth copying — a pump with a
         * per-tick clock budget that always makes at least one iteration of progress — but a live
         * process wants a slice small enough to be invisible, single-digit milliseconds, not one
         * that eats most of the tick.
         */
        private static final long SLICE_MS = 40;

        private final SceneContext ctx;
        private final ServerLevel level;
        private final int cx, cz, floorY, budget;
        private final String tag;
        private final net.minecraft.world.entity.monster.Blaze blaze;

        private double highest;
        private int t;
        private long worstIter, workNanos, worstPump;
        private int worstAt = -1, serverTicks;

        BlazeFightRun(SceneContext ctx, ServerLevel level, ServerWorldDriver driver, ServerPlayer fp,
                      int cx, int cz, int floorY, int budget, String tag) {
            this.ctx = ctx; this.level = level;
            this.cx = cx; this.cz = cz; this.floorY = floorY; this.budget = budget; this.tag = tag;

            blaze = new net.minecraft.world.entity.monster.Blaze(
                    net.minecraft.world.entity.EntityType.BLAZE, level);
            blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
            blaze.setPersistenceRequired();                       // AI ON: the whole point
            level.addFreshEntity(blaze);
            blaze.setTarget(fp);
            fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);

            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:blaze"));
            ServerAvatarManager.register(driver);
            highest = blaze.getY();
        }

        /** Ticks the await step may wait. Worst case is one iteration per server tick, so the
         *  allowance has to cover the whole iteration budget or a slow run fails as a timeout. */
        int tickAllowance() { return budget + 200; }

        private boolean done() { return t >= budget || !blaze.isAlive(); }

        /** Advance the fight for at most {@link #SLICE_MS}; true when the fight is over. */
        boolean pump() {
            serverTicks++;
            long pumpBegan = System.nanoTime();
            long deadline = pumpBegan + SLICE_MS * 1_000_000L;
            do {
                if (done()) break;
                long iter = System.nanoTime();
                // NO per-iteration breadcrumb here, deliberately. One was written and removed: at
                // 50-120 iterations per server tick its logging inflated open.worstServerTickMs /
                // workMs / msPerIter — the three numbers this scene exists to report, and the only
                // quantitative link between a healthy run and a fatal one (the worstIterMs series
                // 5.6 → 6.8 → 8.2 → 21.4 → 80.6 against a 60 000 ms death). An instrument that
                // breaks that series costs more than the iteration index it buys, and PathFinder's
                // own heartbeat already names the state a killed tick was in, with the driver count
                // in the same line. Same trap as the wp8 probe whose evidence row has to warn that
                // its scan generates chunks: a run carrying the probe is not comparable with one
                // that does not.
                ServerAvatarManager.tickAll();
                if (blaze.isAlive()) {
                    blaze.tick();
                    highest = Math.max(highest, blaze.getY());
                }
                long spent = System.nanoTime() - iter;
                if (spent > worstIter) { worstIter = spent; worstAt = t; }
                // THE MOMENT IT HAPPENS, not in finish(). One iteration costing more than the whole
                // per-tick slice is the failure mode this pump cannot prevent (see the class note),
                // and it is exactly the run that may not survive to record anything: if the overrun
                // is the tick the watchdog kills, finish() never runs and worstIterMs dies with it.
                // WARN rather than info because the periodic every-200 line above is a progress
                // trace, and this is the rare event that a future intermittent red needs to find by
                // grepping several runs' logs — the one thing a single green run cannot tell you.
                if (spent > SLICE_MS * 1_000_000L)
                    net.magicterra.worlddriver.WorldDriverCommon.LOG.warn(
                            "[blazefight] {} SINGLE ITERATION OVERRAN THE SLICE: iter={} took {} ms"
                                    + " > sliceMs={} — bounded iterations, unbounded cost per"
                                    + " iteration (pathfinder budgets are MAX_VALUE/2 in this scene)",
                            tag, t, spent / 1_000_000L, SLICE_MS);
                t++;
                // LOGGED, not merely recorded. If this ever blows a tick budget again the server is
                // killed part way through, and every `ctx.record` in `finish()` never runs — the
                // measurement has to already be in the log by then.
                if (t % 200 == 0)
                    net.magicterra.worlddriver.WorldDriverCommon.LOG.info(
                            "[blazefight] {} iter={} serverTicks={} workMs={} worstIterMs={}", tag, t,
                            serverTicks, workNanos / 1_000_000L, worstIter / 1_000_000L);
            } while (System.nanoTime() < deadline);
            long pump = System.nanoTime() - pumpBegan;
            workNanos += pump;
            if (pump > worstPump) worstPump = pump;
            return done();
        }

        /** Record the evidence, clear the arena, and hand back the outcome. */
        BlazeFight finish() {
            long workMs = workNanos / 1_000_000L;
            ctx.record(tag + ".workMs", workMs + " ms（" + t + " 次迭代，摊在 " + serverTicks
                    + " 个服务器 tick 上）");
            // The number the hang watchdog actually measures. It is the one that must stay small;
            // the total may legitimately be large, because the work is real.
            ctx.record(tag + ".worstServerTickMs",
                    String.format(java.util.Locale.ROOT, "%.1f ms（预算 %d ms）",
                            worstPump / 1_000_000.0, SLICE_MS));
            ctx.record(tag + ".worstIterMs", String.format(java.util.Locale.ROOT, "%.1f ms（第 %d 次）",
                    worstIter / 1_000_000.0, worstAt));
            ctx.record(tag + ".msPerIter", String.format(java.util.Locale.ROOT, "%.2f ms",
                    t == 0 ? 0.0 : (double) workMs / t));
            var out = new BlazeFight(!blaze.isAlive(), t, blaze.getHealth(), highest - floorY);
            ctx.record(tag + ".dead", String.valueOf(out.dead()));
            ctx.record(tag + ".ticks", t + (t >= budget ? "（用尽）" : ""));
            ctx.record(tag + ".hpLeft", String.format(java.util.Locale.ROOT, "%.1f", out.hp()));
            ctx.record(tag + ".highestAboveFloor",
                    String.format(java.util.Locale.ROOT, "%.1f", out.rise()));
            blaze.discard();
            for (var d : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    WorldDriverProcessScenes.entityBox(cx, floorY, cz))) d.discard();
            return out;
        }
    }

    /**
     * The ender pearl — a drop from a mob whose defence is to stop being there.
     *
     * <p>An enderman teleports when hurt, which makes it the one fight on this road where the
     * failure mode is not "cannot do enough damage" but "cannot land a second hit". So the reading
     * that matters is not a single kill but a RATE: how many of a fixed number die, and how many
     * pearls come back.
     *
     * <p><b>Enclosed on purpose.</b> Vanilla's teleport picks a destination within ±32 and fails if
     * it cannot fit, so an open arena lets the mob leave the measurement rather than survive it. A
     * roofed box keeps every teleport inside the thing being measured — which is the fight, not the
     * getaway. A real stronghold is not a box, and that difference is stated here rather than
     * discovered later.
     *
     * <p>Like the blaze scene, the body is invulnerable, so this says the fight can be won and not
     * that it can be survived.
     */
    private static void serverEarnsAnEnderPearl(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);

        // A lid and four walls, five high — tall enough for an enderman, closed enough that a
        // teleport lands back inside.
        final int r = 5, h = 5;
        ctx.cleanup(() -> {
            for (int dx = -r - 1; dx <= r + 1; dx++)
                for (int dy = 0; dy <= h + 1; dy++)
                    for (int dz = -r - 1; dz <= r + 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = 1; dy <= h; dy++) {
                    boolean wall = Math.abs(dx) == r || Math.abs(dz) == r || dy == h;
                    if (wall) level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());
                }

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        var fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));

        final int fights = 6;
        final int fightTicks = 4_000;
        int killed = 0, pearls = 0, slow = 0;
        StringBuilder tally = new StringBuilder();
        StringBuilder ticks = new StringBuilder();
        for (int i = 0; i < fights; i++) {
            var man = new net.minecraft.world.entity.monster.EnderMan(
                    net.minecraft.world.entity.EntityType.ENDERMAN, level);
            man.setPos(cx + 2.5, floorY + 1, cz + 0.5);
            man.setPersistenceRequired();
            level.addFreshEntity(man);
            man.setTarget(fp);

            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:enderman"));
            ServerAvatarManager.register(driver);
            int t = 0;
            for (; t < fightTicks && man.isAlive(); t++) {
                ServerAvatarManager.tickAll();
                if (man.isAlive()) man.tick();                 // AI ON: it teleports when hurt
            }
            if (!man.isAlive()) killed++; else slow++;
            ticks.append(ticks.length() == 0 ? "" : ",").append(t);
            for (int k = 0; k < 10; k++) ServerAvatarManager.tickAll();

            int here = 0;
            for (var d : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    WorldDriverProcessScenes.entityBox(cx, floorY, cz))) {
                if (d.getItem().is(Items.ENDER_PEARL)) here += d.getItem().getCount();
                d.discard();
            }
            pearls += here;
            tally.append(tally.length() == 0 ? "" : ",").append(man.isAlive() ? "×" : here + "");
            man.discard();
        }

        ctx.record("enderman.killed", killed + "/" + fights);
        // Recorded, never gated. A fight that runs its whole budget is the shape the old 4-of-6 bar
        // was really reacting to, and it belongs in a row where it can be READ across runs instead
        // of in a threshold that reddens the gate at random. 4000 ticks is an arena constant.
        ctx.record("fights.slow", slow + "/" + fights + " 场打满了 " + fightTicks
                + " tick 预算还没打死；每场用了 " + ticks + " tick");
        ctx.record("pearls.perFight", tally + "（× = 没打死）");
        ctx.record("pearls.total", pearls + "");
        ctx.record("arena", "封顶 " + (2 * r - 1) + "×" + (2 * r - 1) + "×" + (h - 1)
                + " 的盒子 —— 瞬移落回盒内, 真要塞不是盒子");
        ctx.record("body.invulnerable", "true —— 只说打得赢, 不说活得下来");
        // The bar was 4 of 6 — a MAJORITY — and the comment justifying it already named the failure
        // mode it wanted: "a systematic break shows up as 0 or 1, which this still catches". A
        // majority is a far stricter test than that claim needs, and it turned out to sit INSIDE
        // this scene's own spread. Eight archived fabric/neoforge runs of unchanged code:
        //
        //     6/6  6/6  6/6  4/6  4/6  4/6  3/6 FAIL  1/6 FAIL
        //
        // Two REDs out of eight, neither traceable to any change — the same run that failed at 3/6
        // passed every other scene, and the 1/6 run's only new commits could not reach this arena
        // (the body carries a sword and nothing else, so the planner change touching placeable
        // counts is inert here). A threshold drawn through the middle of the distribution it
        // measures is a coin flip wearing a gate's clothes, and every flip costs a gate run to
        // re-read. The bar is now what the claim actually is: kill it more than once, so a single
        // lucky resolve cannot carry the scene, and 0-or-1 — the systematic break — still reddens.
        //
        // The speed question the old bar was half-measuring does not disappear; it moves to
        // fights.slow, which is recorded and NOT gated, because 4000 ticks in a sealed 9x9 box is
        // an arena constant and the real fortress is not a box.
        ctx.expect(killed).as("teleport-on-hurt does not make an enderman unkillable — more than a"
                        + " lucky single resolve out of " + fights + " fights").isAtLeast(2);
        ctx.expect(pearls).as("the kills yield ender pearls").isAtLeast(1);
        ctx.passNote("盒中打死 " + killed + "/" + fights + " 只末影人, 掉 " + pearls + " 颗珍珠");
    }

    /**
     * Break an end crystal — the verb the dragon fight opens with.
     *
     * <p>The dragon heals from every crystal still standing, so the fight does not begin until they
     * are gone. Breaking one is a single hit on an entity with 5 health and no armour; what makes it
     * interesting is that it <b>explodes</b>, and the body doing the hitting is standing next to it.
     *
     * <p>Two readings, because they are different questions: the crystal dies, and the body is still
     * there afterwards. The second is weakened by this avatar being invulnerable — recorded on the
     * row so nobody reads it as "the explosion is survivable".
     *
     * <p>Scoped: the crystal is placed at the body's own level. On a real pillar it sits 20–40 blocks
     * up, and getting there is {@code ascendByTowering}'s problem, which has its own coverage and its
     * own known trouble. This is the verb, not the climb.
     */
    private static void serverBreaksAnEndCrystal(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        WorldDriverProcessScenes.buildFloor(level, cx, cz, floorY);
        level.setBlockAndUpdate(new BlockPos(cx + 3, floorY, cz), Blocks.OBSIDIAN.defaultBlockState());

        var crystal = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(
                level, cx + 3.5, floorY + 1, cz + 0.5);
        crystal.setShowBottom(true);
        level.addFreshEntity(crystal);
        ctx.cleanup(() -> { if (crystal.isAlive()) crystal.discard(); });

        ctx.await(() -> !level.getEntitiesOfClass(
                        net.minecraft.world.entity.boss.enderdragon.EndCrystal.class,
                        WorldDriverProcessScenes.entityBox(cx, floorY, cz)).isEmpty())
                .within(200).then(() -> {
            ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            var fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.IRON_SWORD));

            ctx.record("crystal.alive0", String.valueOf(crystal.isAlive()));
            for (int i = 0; i < 5 && crystal.isAlive(); i++) {
                fp.resetAttackStrengthTicker();
                for (int t = 0; t < 15; t++) ServerAvatarManager.tickAll();
                fp.attack(crystal);
            }
            for (int t = 0; t < 20; t++) ServerAvatarManager.tickAll();

            ctx.record("crystal.alive", String.valueOf(crystal.isAlive()));
            ctx.record("body.alive", String.valueOf(fp.isAlive()));
            ctx.record("body.invulnerable", "true —— 所以\"炸完还站着\"这条读数是弱的");
            ctx.expect(!crystal.isAlive()).as("a driven body can break an end crystal").isTrue();
            ctx.expect(fp.isAlive()).as("the body is still there after the explosion").isTrue();
            ctx.passNote("近身砸掉末影水晶, 身体还在（水晶放在同层, 没测爬柱子）");
        });
    }
}
