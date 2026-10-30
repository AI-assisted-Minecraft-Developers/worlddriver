package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The N7–N10 hostile-mob family: blaze rod, dragon damage, the flying blaze, the ender pearl, and the two stagings of
 * the flying blaze's side effects (the futile-search gate under a creeping goal, and the fall guard). The subject of
 * each is a mob that has to be fought rather than a block that has to be placed.
 *
 * <p>{@code wd.serverBreaksAnEndCrystal} was dropped: it called vanilla's attack directly, and
 * {@code CrystalBlastTest.crystalBlastOnThePillar} breaks a crystal through the product verb.
 *
 * <p>Each fight is ticked by the test, the bot and the mob in lockstep, inside one server task per kill, so no world
 * tick falls between them.
 */
@Scene("wd.scenes")
class MobFightTest {

    /** A bot on the arena's centre with nothing in its bag but {@code weapon}. */
    private static SceneBot fighter(Game game, Plot plot, int cx, int floorY, int cz, Item weapon) {
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + 1, cz + 0.5);
        bot.give(weapon, 1);
        return bot;
    }

    /** {@code mc.bot.combat} kill on the nearest {@code type}: the fight a caller orders. Server thread. */
    private static void kill(SceneBot bot, String type) {
        Calls.started(
                "combat kill " + type,
                bot.tasks()
                        .combat(new CombatContracts.Params(
                                bot.ref(),
                                CombatContracts.Mode.KILL,
                                Optional.of(new CombatContracts.FoeType(type)),
                                false,
                                0)));
    }

    /**
     * Kill a blaze with a driven bot and pick up the rod — the drop, not the kill, is the question.
     *
     * <p>A blaze rod is one of the very few things on the road to the dragon that vanilla will not give to just
     * anything that lands the killing blow: the loot table carries a {@code killed_by_player} condition, satisfied from
     * {@code lastHurtByPlayer}. A bot that hits hard enough to kill and does not register as a player kills the blaze
     * and gets <b>nothing</b>, and the failure is silent — the mob dies, the fight looks won, and the eye of ender is
     * never craftable. Measured at 11 rods from 24 kills.
     *
     * <p><b>What this test deliberately does NOT cover: flight.</b> The blaze here is pinned — no AI,
     * knockback-resistant, re-pinned each tick — so that a red result means "the drop does not reach a driven bot" and
     * cannot also mean "it flew away". {@link #serverFightsAFlyingBlaze} is the other half.
     */
    @Test
    void serverEarnsABlazeRod(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();

        Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, 0, 0);

        // The gamerule first, because it is the one explanation for "nothing dropped" that has
        // nothing to do with the bot.
        reporter.publishEntry(
                "gamerule.doMobLoot",
                String.valueOf(game.<Boolean>onServer(s -> s.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT))));

        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.IRON_SWORD);

        // TWENTY-FOUR, and the count is the measurement rather than padding. A blaze rod is a uniform 0..1
        // roll, so ONE kill cannot tell "the player-kill condition failed" from "the die came up zero";
        // eight kills measured 0,0,1,0,0,0,1,0, still far too close to a coin flip. Twenty-four puts an
        // all-zero run out of reach even if the true rate is half that, and the per-kill tally is recorded
        // so a future red says which of the two explanations it is.
        final int kills = 24;
        int rods = 0, killed = 0;
        StringBuilder tally = new StringBuilder();
        for (int i = 0; i < kills; i++) {
            // One server task per kill: the bot, the blaze's own tick and its re-pin in lockstep.
            int[] kill = game.onServer(s -> {
                ServerLevel level = s.getLevel(plot.dimension());
                Blaze blaze = new Blaze(EntityType.BLAZE, level);
                blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
                blaze.setNoAi(true);
                blaze.setPersistenceRequired();
                var kbr = blaze.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
                if (kbr != null) kbr.setBaseValue(1.0);
                level.addFreshEntity(blaze);

                kill(bot, "minecraft:blaze");
                for (int t = 0; t < 1_200 && blaze.isAlive(); t++) {
                    bot.tick();
                    if (blaze.isAlive()) {
                        blaze.tick(); // its hurt-cooldown
                        blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
                        blaze.setDeltaMovement(Vec3.ZERO);
                    }
                }
                int dead = blaze.isAlive() ? 0 : 1;
                bot.tick(10);

                int here = 0;
                for (ItemEntity d :
                        level.getEntitiesOfClass(ItemEntity.class, Staging.entityBox(new BlockPos(cx, floorY, cz)))) {
                    if (d.getItem().is(Items.BLAZE_ROD)) here += d.getItem().getCount();
                    d.discard(); // clear the floor so the next kill is read alone
                }
                blaze.discard();
                bot.park();
                return new int[] {dead, here};
            });
            killed += kill[0];
            rods += kill[1];
            tally.append(tally.length() == 0 ? "" : ",").append(kill[1]);
        }

        reporter.publishEntry("blaze.killed", killed + "/" + kills);
        reporter.publishEntry("rods.perKill", tally.toString());
        reporter.publishEntry("rods.total", rods + "");
        assertEquals(kills, killed, "a driven bot can kill a blaze at all");
        // The whole point: vanilla gates the rod on killed_by_player, read from lastHurtByPlayer.
        assertTrue(
                rods >= 1, "the kills count as PLAYER kills, so rods actually drop: " + rods + " rods from " + killed);
    }

    /**
     * Can a driven bot hurt the ender dragon at all?
     *
     * <p><b>A dragon does not take damage as itself.</b> {@code EnderDragon.hurt} refuses every direct hit; damage only
     * lands through an {@code EnderDragonPart}, and only the HEAD part takes it undivided — every other part divides it
     * by four and forwards it. So a combat loop that swings at "the nearest entity of type
     * {@code minecraft:ender_dragon}" is aiming at something with no hittable hitbox there, and would report a fight it
     * is winning while the boss bar never moves.
     *
     * <p>This test measures the SEAM rather than the strategy: the bot beside a dragon pinned with no AI and no phase,
     * and whether the driver's own attack path can take health off it. Measured identically on both loaders — 200.0 ->
     * 197.3 from the combat loop, then 2.75 per hit aimed at the head.
     */
    @Test
    void serverDamagesTheDragon(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();

        Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, 0, 0);

        EnderDragon dragon = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            EnderDragon d = new EnderDragon(EntityType.ENDER_DRAGON, level);
            d.setNoAi(true);
            d.setPos(cx + 3.5, floorY + 1, cz + 0.5);
            level.addFreshEntity(d);
            return d;
        });
        game.afterTest(s -> dragon.discard());

        game.await()
                .within(200)
                .as("the dragon is in the level's index")
                .until(() -> !dragon.level()
                        .getEntitiesOfClass(EnderDragon.class, Staging.entityBox(new BlockPos(cx, floorY, cz)))
                        .isEmpty());
        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.DIAMOND_SWORD);

        float[] hp = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            float before = dragon.getHealth();
            // 1. What the existing combat loop does, unchanged — the reading that says whether the
            //    ladder can reuse it or has to learn the parts.
            kill(bot, "minecraft:ender_dragon");
            for (int t = 0; t < 600; t++) {
                bot.tick();
                dragon.setPos(cx + 3.5, floorY + 1, cz + 0.5);
                dragon.setDeltaMovement(Vec3.ZERO);
                dragon.hurtTime = 0; // its own cooldown, as the other fights do
            }
            float afterCombat = dragon.getHealth();

            // 2. The head, by hand, as a control on WHERE the damage lands. `invulnerableTime` is the timer
            //    that refuses damage for 20 ticks (`hurtTime` is only the red flash), and the attack-strength
            //    ticker is reset BEFORE the swing: a control which measures the test rig reads exactly like a
            //    capability that is missing.
            var head = dragon.getSubEntities()[0];
            for (var part : dragon.getSubEntities()) if ("head".equals(part.name)) head = part;
            float beforeHead = dragon.getHealth();
            for (int i = 0; i < 10; i++) {
                dragon.invulnerableTime = 0;
                dragon.hurtTime = 0;
                fp.resetAttackStrengthTicker();
                bot.tick(15); // let the swing recharge
                fp.attack(head);
            }
            return new float[] {before, afterCombat, beforeHead, dragon.getHealth(), dragon.getSubEntities().length};
        });
        reporter.publishEntry("dragon.hp0", String.format(Locale.ROOT, "%.1f", hp[0]));
        reporter.publishEntry("dragon.hpAfterCombatProcess", String.format(Locale.ROOT, "%.1f", hp[1]));
        reporter.publishEntry("dragon.parts", (int) hp[4] + " parts");
        reporter.publishEntry("dragon.hpAfterHeadHits", String.format(Locale.ROOT, "%.1f", hp[3]));
        reporter.publishEntry("dragon.perHeadHit", String.format(Locale.ROOT, "%.2f", (hp[2] - hp[3]) / 10));
        assertTrue(hp[1] < hp[0], "the existing combat loop takes health off the dragon: " + hp[0] + " → " + hp[1]);
        assertTrue(hp[3] < hp[2], "a hit aimed at the head lands on a multipart boss: " + hp[2] + " → " + hp[3]);
    }

    /**
     * The blaze that is allowed to fly — the half {@link #serverEarnsABlazeRod} pinned away.
     *
     * <p>A blaze hovers, drifts, and shoots from above; a melee loop that can only hit what is standing next to it wins
     * the pinned fight and loses every real one. <b>The bot is invulnerable</b>, so this cannot say whether a real run
     * survives the fireballs — only whether the fight can be WON.
     *
     * <p><b>Measured, and the open-sky answer is NO.</b> Given six thousand ticks the loop took a blaze from 20 health
     * to 8 and never finished it: the mob hovers six to eight blocks above the floor and melee reaches roughly three.
     * So the fight runs TWICE — under open sky, and in a closed room, which is what a player builds at a spawner. Only
     * the room is asserted: an assertion that a fight is NOT won sits on the wrong side of the dice (a NeoForge run
     * finished the open blaze at 2.0 health left).
     *
     * <p><b>A ceiling alone was not enough either.</b> A bare lid over an open floor got the blaze to 2 health and
     * still lost it — it drifted out past the lid's edge and climbed above it. Walls plus a ceiling contain a blaze:
     * sideways first, then up.
     *
     * <p>The futile-search census rows: on 2026-08-24 the bot chased the blaze off the floor, fell to the world bottom
     * and spent most of the fight in searches toward a goal hundreds of blocks up. The door was answered by
     * {@link #serverFutileGateUnderACreepingGoal} (bucket 9, "seeded after a reset"); the rows stay here as the in-situ
     * reading.
     */
    @Test
    void serverFightsAFlyingBlaze(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 32, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.at(0, -8, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();

        Settings settings = Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, -8, 0);

        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.IRON_SWORD);
        final Map<String, Long> futileAtStart = futileSnapshot(bot);

        // Round 1: open sky. Measured, not assumed — and it does NOT work.
        Settings openBudgets = Settings.save(game);
        BlazeFightRun openRun = game.onServer(s -> new BlazeFightRun(
                game, settings, openBudgets, s.getLevel(plot.dimension()), bot, cx, cz, floorY, 3_000, "open"));
        game.await()
                .within(openRun.tickAllowance())
                .as("the open-sky fight ends")
                .until(openRun::pump);
        BlazeFight open = game.onServer(s -> openRun.finish());
        openRun.rows.forEach(reporter::publishEntry);
        final Map<String, Long> futileAfterOpen = futileSnapshot(bot);
        reporter.publishEntry("futileGate.open", futileGateLine(futileAtStart, futileAfterOpen));

        // Round 2: the same fight in a closed room — what a player does at a spawner: not a new engine verb,
        // a different room.
        final int rr = 5, hh = 4;
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++)
                    for (int dy = 1; dy <= hh; dy++)
                        if (Math.abs(dx) == rr || Math.abs(dz) == rr || dy == hh)
                            level.setBlockAndUpdate(
                                    new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        });
        Settings roofedBudgets = Settings.save(game);
        BlazeFightRun roofedRun = game.onServer(s -> new BlazeFightRun(
                game, settings, roofedBudgets, s.getLevel(plot.dimension()), bot, cx, cz, floorY, 3_000, "roofed"));
        game.await()
                .within(roofedRun.tickAllowance())
                .as("the roofed fight ends")
                .until(roofedRun::pump);
        BlazeFight roofed = game.onServer(s -> roofedRun.finish());
        roofedRun.rows.forEach(reporter::publishEntry);
        reporter.publishEntry("futileGate.roofed", futileGateLine(futileAfterOpen, futileSnapshot(bot)));
        reporter.publishEntry(
                "body.invulnerable",
                "true — so this result shows only that the fight can be" + " won, not that the bot would survive it");
        reporter.publishEntry(
                "summary",
                "under open sky the blaze was not killed in " + open.ticks + " ticks ("
                        + String.format(Locale.ROOT, "%.1f", open.hp) + " health left, highest "
                        + String.format(Locale.ROOT, "%.1f", open.rise)
                        + " blocks above the floor); with a roof four blocks"
                        + " high it took " + roofed.ticks + " ticks");
        assertTrue(
                roofed.dead,
                "in a closed room a driven bot kills a blaze that is free to fly: " + roofed.hp + " health left after "
                        + roofed.ticks + " ticks");
    }

    /** One unpinned blaze fight, reported rather than asserted — the caller decides what it means. */
    private record BlazeFight(boolean dead, int ticks, float hp, double rise) {}

    /**
     * The futile-search gate, read on a goal that creeps — the loop {@link #serverFightsAFlyingBlaze} only produces by
     * accident.
     *
     * <p><b>What this reproduces.</b> On the 2026-08-24 run that fight chased its blaze off an 11x11 floor; the bot
     * fell 282 blocks to the world bottom and then spent 29 consecutive searches, each ~64k nodes, on a goal 267 blocks
     * straight up. The gate that exists to stop exactly that ({@code navigatorFutileSearchCap}) never fired.
     *
     * <p><b>Why a test rather than another run of the fight.</b> Whether the blaze lures the bot off the floor is a
     * coin toss. The situation itself is three facts — a bot that cannot move, a goal it cannot reach, and a goal that
     * creeps a block every other tick — and all three can simply be staged.
     *
     * <p><b>What it measured, before the fix.</b> {@code still} took 9 searches in 240 ticks; {@code creep} took 240 —
     * one full search every tick — with bucket 6 ("got closer") reading exactly 120, the re-goal count. The counter was
     * wiped by {@code start}, whose {@code reset()} leaves the baseline at {@code +INFINITY}, so the next judged search
     * was trivially "closer" than infinity.
     *
     * <p><b>The quarry is a marker the bot follows.</b> {@code mc.bot.follow} re-aims its navigator whenever the quarry
     * changes block, with the same rule {@code CombatProcess.approach} uses: the same entity in a new cell is
     * {@code retarget}, a different entity is {@code start}. The marker hangs 90 blocks up because follow only sees
     * quarries within 96 blocks, and sways sideways one block every other tick.
     *
     * <p><b>Four arms.</b> {@code still} holds the goal fixed. {@code creepSetGoal} is the recorded baseline, a new
     * quarry on each change ({@code start}). {@code creepRetarget} moves the one quarry ({@code retarget}) at the
     * blaze's rate. {@code chase} is the reverse: a quarry the bot CAN reach, re-goaled at the same rate, where the
     * guard must stay silent. Every arm runs its full 240 ticks and reads the navigator's verdict on every one: an arm
     * that stopped at the first terminal would miss a latched cap that re-reports itself by running another full search
     * every tick.
     */
    @Test
    void serverFutileGateUnderACreepingGoal(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 100, z = 32) Plot plot, TestReporter reporter) {
        BlockPos o = plot.at(0, plot.min().getY() + 4, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();

        Settings settings = Settings.pin(game)
                .set("navigatorDebug", false)
                // The goal has to be UNREACHABLE, not merely far. With either of these left on, the bot
                // pillars up to it or digs its way somewhere, and the gate is never asked its question.
                .set("allowBreak", false)
                .set("allowPlace", false)
                // Small on purpose. Whether the gate counts a search does not depend on how many nodes that
                // search burned; reproducing the COST would spend a minute of every run.
                .set("pathfinder.maxNodes", 2_000);
        SceneArena.buildFloor(plot, 0, plot.min().getY() + 4, 0);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + 1, cz + 0.5);
        List<ArmReading> arms = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            return List.of(
                    creepArm(bot, level, "still", cx, floorY, cz, 0, false),
                    creepArm(bot, level, "creepSetGoal", cx, floorY, cz, 2, false),
                    creepArm(bot, level, "creepRetarget", cx, floorY, cz, 2, true),
                    chaseArm(bot, level, cx, floorY, cz));
        });
        ArmReading still = arms.get(0), setGoalArm = arms.get(1), retargetArm = arms.get(2), chase = arms.get(3);
        reporter.publishEntry("gate.still", still.line());
        reporter.publishEntry("gate.creepSetGoal", setGoalArm.line());
        reporter.publishEntry("gate.creepRetarget", retargetArm.line());
        reporter.publishEntry("gate.chase", chase.line());

        List<String> fails = new ArrayList<>();
        // A census that silently counts nothing reads exactly like a gate that judged everything and
        // let it pass, so prove the channel spoke before reading anything else out of it.
        for (ArmReading arm : arms)
            if (arm.searches() == 0)
                fails.add("the gate census channel is silent: one arm ran no search at all, so this reading"
                        + " cannot be used to judge anything: " + arm.line());

        // The claim the fix makes, stated as a number the arm can miss. A cap of 5 admits one unjudged
        // seeding search plus the five it counts; anything past that means the bot is still paying for a
        // goal it cannot reach.
        int cap = settings.integer("navigatorFutileSearchCap");
        if (retargetArm.searches() > cap + 2)
            fails.add("chasing an unreachable goal ran " + retargetArm.searches() + " searches in 240 ticks" + " (cap "
                    + cap + ", allowed " + (cap + 2) + "): " + retargetArm.line());
        if (!retargetArm.noRoute())
            fails.add("the gate never fired: chasing a goal " + HIGH + " blocks overhead that can be neither dug to"
                    + " nor built to produced no 'no route progress' within 240 ticks: " + retargetArm.line());
        // The reverse. A guard that fires on a healthy pursuit is worse than one that never fires.
        if (chase.noRoute())
            fails.add("the gate fired on a healthy chase: the goal is 8 blocks away on the same floor and"
                    + " the bot kept walking, yet 'no route progress' was reported: " + chase.line());

        String recover = game.onServer(s -> recoverArm(bot, s.getLevel(plot.dimension()), cx, floorY, cz, fails));
        reporter.publishEntry("gate.recover", recover);
        if (!fails.isEmpty()) fail(String.join("\n", fails));
    }

    /**
     * The latch must not outlive the pursuit that earned it.
     *
     * <p>The latch's only key is the bot's displacement, which is right while the pursuit is the same one. It is WRONG
     * across pursuits: a bot latched on an unreachable flying blaze would carry the latch into the walk toward a zombie
     * it could plainly reach, and never start a search again. {@code start} means "new journey" and clears everything;
     * {@code retarget} means "same journey, the cell moved". This arm asserts the engine half of that contract: latch,
     * DON'T move the bot, then replace the quarry with a new one within easy reach, which the follow takes up with
     * {@code start} — searches must resume and the bot must go. Server thread.
     */
    private static String recoverArm(SceneBot bot, ServerLevel level, int cx, int floorY, int cz, List<String> fails) {
        int step = 0;
        ArmorStand quarry = marker(level, cx + 0.5, floorY + HIGH, cz + 0.5);
        follow(bot, cx + 0.5, floorY + 1, cz + 0.5, 2);
        boolean latched = false;
        for (int t = 0; t < ARM_TICKS && !latched; t++) {
            if (t > 0 && t % 2 == 0) quarry.setPos(cx + sway(++step) + 0.5, floorY + HIGH, cz + 0.5);
            bot.tick();
            if (noRoute(verdict(bot))) latched = true;
        }
        BlockPos stuckAt = bot.player().blockPosition();
        Map<String, Long> before = futileSnapshot(bot);
        quarry.discard();
        quarry = marker(level, cx + 4.5, floorY + 1, cz + 0.5);
        String verdict = null;
        for (int t = 0; t < ARM_TICKS; t++) {
            bot.tick();
            verdict = verdict(bot);
        }
        Map<String, Long> after = futileSnapshot(bot);
        BlockPos end = bot.player().blockPosition();
        bot.cancel();
        quarry.discard();
        long searches = searchSum(before, after);
        String line = "recover: phase 1 " + (latched ? "latched" : "WARNING: did not latch") + " (bot stopped at "
                + stuckAt.toShortString() + "); without moving the bot, a new quarry 4 blocks away was set;"
                + " after " + ARM_TICKS + " ticks the navigator's verdict was "
                + (verdict == null ? "still walking" : verdict)
                + " with the bot at " + end.toShortString() + "; " + futileGateLine(before, after);

        // Phase 1 not latching would make phase 2 vacuous — it would prove a latch can be cleared
        // that was never set. Say so rather than reporting a pass.
        if (!latched)
            fails.add("this arm's precondition did not hold: phase 1 failed to latch, so phase 2 proves" + " nothing: "
                    + line);
        else if (searches == 0)
            fails.add("the latch outlived the chase that set it: the bot did not move, but after a new"
                    + " reachable quarry 4 blocks away was set, no search started in " + ARM_TICKS
                    + " ticks — a silent freeze with no terminal state and no log: " + line);
        else if (end.equals(stuckAt))
            fails.add("searching resumed but the bot did not move: the new quarry is 4 blocks away on the"
                    + " same floor, and after " + ARM_TICKS + " ticks the bot is still at "
                    + end.toShortString() + ": " + line);
        return line;
    }

    /**
     * The reverse arm: a quarry the bot CAN reach, re-goaled at the same rate as the creeping one. Same floor, same
     * re-goal call, same tick budget; the only thing that changed is that the goal is eight blocks away instead of
     * ninety straight up. Server thread.
     */
    private static ArmReading chaseArm(SceneBot bot, ServerLevel level, int cx, int floorY, int cz) {
        Map<String, Long> before = futileSnapshot(bot);
        ArmorStand quarry = marker(level, cx + 4.5, floorY + 1, cz + 0.5);
        follow(bot, cx - 4.5, floorY + 1, cz + 0.5, 1);
        int regoals = 1;
        boolean noRoute = false;
        String verdict = null;
        for (int t = 0; t < ARM_TICKS; t++) {
            if (t > 0 && t % 20 == 0) { // the quarry strolls back along the floor
                quarry.setPos(cx + 4 - (t / 20) % 9 + 0.5, floorY + 1, cz + 0.5);
                regoals++;
            }
            bot.tick();
            verdict = verdict(bot);
            if (noRoute(verdict)) noRoute = true;
        }
        BlockPos end = bot.player().blockPosition();
        Map<String, Long> after = futileSnapshot(bot);
        bot.cancel();
        quarry.discard();
        return new ArmReading(
                "chase: ran the full " + ARM_TICKS + " ticks, moved the quarry " + regoals
                        + " times, last verdict " + (verdict == null ? "still walking" : verdict)
                        + ", bot at " + end.getX() + "," + end.getY() + "," + end.getZ()
                        + "; " + futileGateLine(before, after),
                searchSum(before, after),
                noRoute);
    }

    /**
     * One arm's reading. The line is for a human; the two numbers are what the test judges on: {@code searches} is the
     * arm's whole share of the gate, terminals included, and {@code noRoute} says whether the guard ever spoke.
     */
    private record ArmReading(String line, long searches, boolean noRoute) {}

    private static long searchSum(Map<String, Long> before, Map<String, Long> after) {
        long sum = 0;
        for (var e : after.entrySet()) sum += e.getValue() - before.getOrDefault(e.getKey(), 0L);
        return sum;
    }

    private static final int ARM_TICKS = 240;

    /** How high the unreachable quarry hangs: out of reach, and inside the 96 blocks follow looks for a quarry in. */
    private static final int HIGH = 90;

    private static final String MARKER = "minecraft:armor_stand";

    /** A quarry that hangs where it is put. */
    private static ArmorStand marker(ServerLevel level, double x, double y, double z) {
        ArmorStand m = new ArmorStand(level, x, y, z);
        m.setNoGravity(true);
        level.addFreshEntity(m);
        return m;
    }

    /** Sideways offset of the {@code step}th move: back and forth within 8 of the centre, a new cell every step. */
    private static int sway(int step) {
        int k = Math.floorMod(step, 32);
        return k <= 8 ? k : k <= 24 ? 16 - k : k - 32;
    }

    /**
     * Re-seat the bot on {@code (x, y, z)} and follow the nearest marker, standing off {@code radius}. No idle or
     * progress limit: like the combat approach, the follow keeps pursuing whatever its navigator says.
     */
    private static void follow(SceneBot bot, double x, double y, double z, int radius) {
        bot.player().setPos(x, y, z);
        bot.player().setDeltaMovement(Vec3.ZERO);
        Calls.started(
                "follow the marker",
                bot.tasks()
                        .follow(new MoveContracts.FollowParams(
                                bot.ref(), new MoveContracts.QuarryType(MARKER), radius, 0, 0, Optional.empty(), 0)));
    }

    /** How the navigator's last walk ended, or null while it is still walking; the next re-goal clears it. */
    private static String verdict(SceneBot bot) {
        return bot.navigator().traces().get("walkResult");
    }

    private static boolean noRoute(String verdict) {
        return verdict != null && verdict.contains("no route progress");
    }

    /**
     * One arm: re-seat the bot, have it follow an unreachable quarry, and tick. {@code regoalEvery} zero means the
     * quarry never moves; two means it moves a block every second tick and the follow re-goals each time — the combat
     * approach's idiom at the rate the blaze sank. {@code retarget} picks WHICH re-goal call the arm makes: the same
     * quarry moved ({@code retarget}), or a new quarry in the next cell ({@code start}); the {@code start} arm is a
     * control, its contract still live for {@code CombatProcess.collectSweep}. Server thread.
     */
    private static ArmReading creepArm(
            SceneBot bot,
            ServerLevel level,
            String tag,
            int cx,
            int floorY,
            int cz,
            int regoalEvery,
            boolean retarget) {
        Map<String, Long> before = futileSnapshot(bot);
        int step = 0;
        ArmorStand quarry = marker(level, cx + 0.5, floorY + HIGH, cz + 0.5);
        follow(bot, cx + 0.5, floorY + 1, cz + 0.5, 2); // every arm starts from the same cell
        int regoals = 1;
        int firstTerminal = -1;
        String firstError = null, verdict = null;
        boolean noRoute = false;
        for (int t = 0; t < ARM_TICKS; t++) {
            if (regoalEvery > 0 && t > 0 && t % regoalEvery == 0) {
                double x = cx + sway(++step) + 0.5;
                if (retarget) {
                    quarry.setPos(x, floorY + HIGH, cz + 0.5);
                } else {
                    quarry.discard();
                    quarry = marker(level, x, floorY + HIGH, cz + 0.5);
                }
                regoals++;
            }
            bot.tick();
            verdict = verdict(bot);
            if (noRoute(verdict)) noRoute = true;
            if (verdict != null && firstTerminal < 0) {
                firstTerminal = t;
                firstError = verdict;
            }
        }
        Map<String, Long> after = futileSnapshot(bot);
        bot.cancel();
        quarry.discard();
        return new ArmReading(
                tag + ": ran the full " + ARM_TICKS + " ticks, set the goal " + regoals + " times, "
                        + (firstTerminal < 0
                                ? "no terminal state during the run"
                                : "first terminal state at t=" + firstTerminal + " (" + firstError + ")")
                        + ", last verdict " + (verdict == null ? "still walking" : verdict) + "; "
                        + futileGateLine(before, after),
                searchSum(before, after),
                noRoute);
    }

    private static final String FUTILE = "futileGate.";

    /** The body's futile-gate counters by bucket, from {@code mc.debug.navigator}. */
    private static Map<String, Long> futileSnapshot(SceneBot bot) {
        Map<String, Long> out = new LinkedHashMap<>();
        bot.navigator().counters().forEach((k, v) -> {
            if (k.startsWith(FUTILE)) out.put(k.substring(FUTILE.length()), v);
        });
        return out;
    }

    /**
     * One round's share of the futile-search gate, bucket by bucket. The counters are the body's and outlive one round,
     * so only a DELTA between two snapshots means anything. A zero sum gets its own sentence: all zeros read like "the
     * gate let everything through" when they say the navigator never searched at all.
     */
    private static String futileGateLine(Map<String, Long> before, Map<String, Long> after) {
        long sum = 0;
        StringBuilder sb = new StringBuilder();
        for (var e : after.entrySet()) {
            long v = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            sum += v;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(v);
        }
        return sum == 0
                ? "no search at all this round — the navigator never searched for a path, so do not read this"
                        + " line as \"the gate let it through\""
                : sum + " searches this round, gate outcomes: " + sb;
    }

    /**
     * A blaze fight spread ACROSS server ticks instead of crammed into one: {@link #pump()} is the await's condition,
     * run once per server tick, and stops starting new iterations once {@link #SLICE_MS} is gone.
     *
     * <p><b>Why.</b> This fight used to be one loop inside a single server tick; on three dedicated-NeoForge runs out
     * of eight the server crossed {@code max-tick-time=60000} and the watchdog killed it. Not a loader difference and
     * not one runaway search: {@code pathfinder.idleSliceMs} is 30 ms and the search spends that idle slice on every
     * tick a bot chases a blaze hovering out of reach; 3000 iterations x ~20 ms is the 60 s.
     *
     * <p><b>⚠️ The budget bounds the ITERATION COUNT per tick, never the tick.</b> The deadline can only be read
     * between iterations, and the untimed pin lifts the planner's budgets so a search is never truncated
     * mid-measurement: a single slow search blows the slice on its own (measured worstIterMs=80.6 against 40). Any
     * overrun is logged the instant it happens, because a tick that kills the server never reaches {@link #finish()}.
     *
     * <p>What bounded the rest is {@link #noteTheFall()}: every overrun happened after the bot walked off the floor and
     * was re-planning from the world bottom toward a goal 250 blocks up, which is not the fight. So the real budgets
     * come back armed by the fall, not by the clock: the healthy arm never executes a byte of it.
     *
     * <p>The {@code blaze.tick()} / {@code bot.tick()} interleaving is a real requirement: the mob and the bot must
     * advance in lockstep or the fight being measured is not the fight the field sees.
     */
    private static final class BlazeFightRun {
        /** Wall clock one server tick may give this fight; under a vanilla 50 ms tick. Not a production number. */
        private static final long SLICE_MS = 40;

        /**
         * How far under the floor counts as "no longer in this arena": past any step-down or knockback, far short of
         * the drop the bot actually takes.
         */
        private static final int FALL_MARGIN = 6;

        /**
         * Iterations to keep feeding the futile-gate census AFTER the bot has left the floor, under a real pathfinder
         * budget: the evidence of the pathological phase, without its cost.
         */
        private static final int FALL_PROBE_ITERS = 60;

        private final Game game;
        private final Settings settings;
        /** The budgets as the round found them (lifted past what a write accepts), put back after the fall. */
        private final Settings budgetsWere;

        private final SceneBot bot;
        private final ServerPlayer fp;
        private final int floorY, budget;
        private final String tag;
        private final Blaze blaze;
        private final ServerLevel level;
        private final int cx, cz;
        /** The evidence, published by the test once the round is over. */
        final Map<String, String> rows = new LinkedHashMap<>();

        private double highest;
        private int t;
        private long worstIter, workNanos, worstPump;
        private int worstAt = -1, serverTicks;

        private Map<String, Long> futileAtFall;
        private int fellAt = -1;
        private double fellY;
        private boolean fallReported;
        private String postFallCensus;
        private Runnable staging = () -> {};

        /** Server thread. {@code budgetsWere} is a {@link Settings#save} taken just before, on the test thread. */
        BlazeFightRun(
                Game game,
                Settings settings,
                Settings budgetsWere,
                ServerLevel level,
                SceneBot bot,
                int cx,
                int cz,
                int floorY,
                int budget,
                String tag) {
            this.game = game;
            this.settings = settings;
            this.budgetsWere = budgetsWere;
            this.level = level;
            this.bot = bot;
            this.fp = bot.player();
            this.cx = cx;
            this.cz = cz;
            this.floorY = floorY;
            this.budget = budget;
            this.tag = tag;

            blaze = new Blaze(EntityType.BLAZE, level);
            blaze.setPos(cx + 3.5, floorY + 1, cz + 0.5);
            blaze.setPersistenceRequired(); // AI ON: the whole point
            level.addFreshEntity(blaze);
            blaze.setTarget(fp);
            fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);

            kill(bot, "minecraft:blaze");
            highest = blaze.getY();
        }

        /** Ticks the await may wait: worst case one iteration per server tick. */
        int tickAllowance() {
            return budget + 200;
        }

        private boolean done() {
            return t >= budget || !blaze.isAlive() || (fellAt >= 0 && t - fellAt >= FALL_PROBE_ITERS);
        }

        /**
         * The bot walked off the floor — end this round, but take evidence on the way out, under the real pathfinder
         * budgets. LOGGED, not merely recorded: the tick that kills the server never reaches finish().
         */
        private void noteTheFall() {
            fellAt = t;
            fellY = fp.getY();
            futileAtFall = futileSnapshot(bot);
            Object sliceWas = Settings.read(game, "pathfinder.sliceMs");
            Object maxWas = Settings.read(game, "pathfinder.maxMs");
            // Production values, not the untimed pin's. The probe wants a bounded search far more than an
            // untruncated one.
            settings.set("pathfinder.sliceMs", 6).set("pathfinder.maxMs", 500);
            TestMod.LOG.warn(
                    "[blazefight] {} BOT LEFT THE ARENA at iter={} — y={} is {} below floor {};"
                            + " blaze at y={}. Ending the round after {} probe iterations under a"
                            + " REAL pathfinder budget (slice {}->6 ms, max {}->500 ms).",
                    tag,
                    fellAt,
                    String.format(Locale.ROOT, "%.1f", fellY),
                    String.format(Locale.ROOT, "%.1f", floorY - fellY),
                    floorY,
                    String.format(Locale.ROOT, "%.1f", blaze.getY()),
                    FALL_PROBE_ITERS,
                    sliceWas,
                    maxWas);
        }

        /** Log the probe's census share and hand the budgets back. Idempotent. */
        private void reportTheFall() {
            if (fallReported || fellAt < 0) return;
            fallReported = true;
            budgetsWere.restore(); // the lifted budgets are past what a write accepts
            postFallCensus = futileGateLine(futileAtFall, futileSnapshot(bot));
            TestMod.LOG.warn(
                    "[blazefight] {} POST-FALL CENSUS over {} iterations — {}", tag, t - fellAt, postFallCensus);
        }

        int iterations() {
            return t;
        }

        int fellAt() {
            return fellAt;
        }

        float blazeHealthFraction() {
            return blaze.getHealth() / blaze.getMaxHealth();
        }

        /** A staging step run before every iteration (per iteration, not per server tick). */
        void stageEachIteration(Runnable step) {
            staging = step;
        }

        /** Worst single {@link #pump()}, in ms. The quantity the hang watchdog actually measures. */
        double worstPumpMs() {
            return worstPump / 1_000_000.0;
        }

        /** Advance the fight for at most {@link #SLICE_MS}; true when the fight is over. Server thread. */
        boolean pump() {
            serverTicks++;
            long pumpBegan = System.nanoTime();
            long deadline = pumpBegan + SLICE_MS * 1_000_000L;
            do {
                if (done()) break;
                staging.run();
                long iter = System.nanoTime();
                // No per-iteration breadcrumb: at 50-120 iterations per server tick its logging inflated the
                // very timings this fight reports.
                bot.tick();
                if (blaze.isAlive()) {
                    blaze.tick();
                    highest = Math.max(highest, blaze.getY());
                }
                // AFTER the tick, so the y read is the one the iteration just produced.
                if (fellAt < 0 && fp.getY() < floorY - FALL_MARGIN) noteTheFall();
                long spent = System.nanoTime() - iter;
                if (spent > worstIter) {
                    worstIter = spent;
                    worstAt = t;
                }
                // THE MOMENT IT HAPPENS, not in finish(): if the overrun is the tick the watchdog kills, finish()
                // never runs.
                if (spent > SLICE_MS * 1_000_000L)
                    TestMod.LOG.warn(
                            "[blazefight] {} SINGLE ITERATION OVERRAN THE SLICE: iter={} took {} ms"
                                    + " > sliceMs={} — bounded iterations, unbounded cost per iteration",
                            tag,
                            t,
                            spent / 1_000_000L,
                            SLICE_MS);
                t++;
                if (t % 200 == 0)
                    TestMod.LOG.info(
                            "[blazefight] {} iter={} serverTicks={} workMs={} worstIterMs={}",
                            tag,
                            t,
                            serverTicks,
                            workNanos / 1_000_000L,
                            worstIter / 1_000_000L);
            } while (System.nanoTime() < deadline);
            if (fellAt >= 0 && done()) reportTheFall();
            long pump = System.nanoTime() - pumpBegan;
            workNanos += pump;
            if (pump > worstPump) worstPump = pump;
            return done();
        }

        /** Record the evidence, clear the arena, and hand back the outcome. Server thread. */
        BlazeFight finish() {
            reportTheFall();
            long workMs = workNanos / 1_000_000L;
            // Recorded on EVERY run: a row that only appears on the bad runs cannot be differenced against a good one.
            rows.put(
                    tag + ".leftTheArena",
                    fellAt < 0
                            ? "no — the bot stayed on the platform for the whole round (the healthy case; the guard"
                                    + " executed no code in this run)"
                            : String.format(
                                    Locale.ROOT,
                                    "yes — at iteration %d the bot fell to y=%.1f (platform top %d, %.1f blocks lower);"
                                            + " the round then ended after %d bounded iterations of evidence collection,"
                                            + " so the round's ticks are not the full %d",
                                    fellAt,
                                    fellY,
                                    floorY,
                                    floorY - fellY,
                                    t - fellAt,
                                    budget));
            if (postFallCensus != null) rows.put(tag + ".postFallGate", postFallCensus);
            rows.put(
                    tag + ".workMs",
                    workMs + " ms (" + t + " iterations, spread over " + serverTicks + " server ticks)");
            rows.put(
                    tag + ".worstServerTickMs",
                    String.format(Locale.ROOT, "%.1f ms (budget %d ms)", worstPump / 1_000_000.0, SLICE_MS));
            rows.put(
                    tag + ".worstIterMs",
                    String.format(Locale.ROOT, "%.1f ms (iteration %d)", worstIter / 1_000_000.0, worstAt));
            rows.put(tag + ".msPerIter", String.format(Locale.ROOT, "%.2f ms", t == 0 ? 0.0 : (double) workMs / t));
            BlazeFight out = new BlazeFight(!blaze.isAlive(), t, blaze.getHealth(), highest - floorY);
            rows.put(tag + ".dead", String.valueOf(out.dead()));
            rows.put(tag + ".ticks", t + (t >= budget ? " (budget exhausted)" : ""));
            rows.put(tag + ".hpLeft", String.format(Locale.ROOT, "%.1f", out.hp()));
            rows.put(tag + ".highestAboveFloor", String.format(Locale.ROOT, "%.1f", out.rise()));
            blaze.discard();
            for (ItemEntity d :
                    level.getEntitiesOfClass(ItemEntity.class, Staging.entityBox(new BlockPos(cx, floorY, cz))))
                d.discard();
            return out;
        }
    }

    /**
     * The guard from {@code BlazeFightRun.noteTheFall()}, on the one occasion that fires it.
     *
     * <p>The guard's whole design property is that a healthy run never executes a byte of it — so a green suite says
     * nothing about whether it works, and the occasion happens roughly once in three flying-blaze runs.
     *
     * <p><b>The fall is staged, not simulated.</b> The bot is dropped onto a real pad 200 blocks under the arena AFTER
     * the fight has been running for a while, so the combat process carries the same live re-planning state it carries
     * in the field. What is asserted is the BOUND, not the outcome of the fight: that the guard fired, that the round
     * then ended within the probe's own iteration budget instead of running to 3000, and that no single pump got
     * anywhere near the 60 s the watchdog kills at.
     */
    @Test
    void serverBlazeFightStopsWhenTheBodyFallsOut(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 232, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.at(0, plot.max().getY() - 20, 0);
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();
        final int padY = floorY - 200;

        // The same untimed budgets the real fight uses. Staging the fall under a budget the real fight does not
        // have would test a guard nobody ships.
        Settings settings = Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, plot.max().getY() - 20, 0);
        plot.fill(-1, plot.max().getY() - 220, -1, 1, plot.max().getY() - 220, 1, Blocks.STONE);

        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.IRON_SWORD);

        // Push after a while of fighting, or once the blaze is at half health, whichever comes first:
        // a bot that wins before the push leaves nothing to stage.
        final int pushAfter = 200, pushNoEarlierThan = 20;
        Settings budgets = Settings.save(game);
        BlazeFightRun run = game.onServer(s -> new BlazeFightRun(
                game, settings, budgets, s.getLevel(plot.dimension()), bot, cx, cz, floorY, 3_000, "fell"));
        final int[] pushedAt = {-1};
        run.stageEachIteration(() -> {
            if (pushedAt[0] < 0
                    && run.iterations() >= pushNoEarlierThan
                    && (run.iterations() >= pushAfter || run.blazeHealthFraction() <= 0.5f)) {
                pushedAt[0] = run.iterations();
                bot.player().moveTo(cx + 0.5, padY + 1, cz + 0.5);
            }
        });
        game.await().within(run.tickAllowance()).as("the fight ends").until(run::pump);
        BlazeFight out = game.onServer(s -> run.finish());
        run.rows.forEach(reporter::publishEntry);
        reporter.publishEntry(
                "staged.pushedAt",
                "after " + pushedAt[0] + " iterations the bot was moved to " + cx + ", " + (padY + 1) + ", " + cz
                        + " (platform top " + floorY + ", 200 blocks lower)");
        reporter.publishEntry(
                "subject.worstPumpMs",
                String.format(Locale.ROOT, "%.1f ms (the watchdog kills at 60000 ms)", run.worstPumpMs()));

        game.check(
                run.fellAt() >= 0,
                "A: the guard fires: the bot was moved 200 blocks below the platform, and"
                        + " noteTheFall should record fellAt. If it did not fire, the criterion reads the wrong quantity"
                        + " (fp.getY() is not necessarily the entity that combat reads); log y first and then change the"
                        + " threshold, do not adjust FALL_MARGIN directly. fellAt=" + run.fellAt());
        // Deliberately NOT `== FALL_PROBE_ITERS`. The pump completes whole iterations, so the round can overshoot
        // by one.
        game.check(
                run.fellAt() >= 0 && out.ticks() - run.fellAt() <= 80,
                "B: the round ends within a bound: after the fall only 60 more evidence-collection iterations should"
                        + " run, not the full 3000. Actual: "
                        + (run.fellAt() < 0 ? "not applicable" : String.valueOf(out.ticks() - run.fellAt())));
        game.check(
                run.worstPumpMs() < 2_000,
                "C: no single pump comes near the watchdog: once switched to the real"
                        + " budget no pump should approach the 2.6-3 s an untimed search from the bottom takes. Actual: "
                        + String.format(Locale.ROOT, "%.1f ms", run.worstPumpMs()));
    }

    /**
     * The ender pearl — a drop from a mob whose defence is to stop being there.
     *
     * <p>Measured on 1.21.1: an enderman teleports when its target player looks at it from within 4 blocks, and when
     * hurt by a source that is not a living entity; the bot's melee is the player's, so it never takes the second path.
     * The first is what lost fights: aiming at it from 3.5 blocks counts as a stare, and the teleport picks a spot
     * within ±32 and walks down to the first solid block, which from a plot 145 blocks above the ground is the ground,
     * where the enderman cannot reach the bot again. A fight after the first started with the bot still facing where
     * the last enderman died, which is where the next one spawns. So the bot wears a carved pumpkin, vanilla's own
     * counter: the stare reaction is gone, and the test measures whether driven melee kills an enderman and the kill
     * reaches its loot table.
     *
     * <p>The box only keeps the fight in one place; it cannot keep a teleport inside, since every destination is
     * outside it. Like the blaze, the bot is invulnerable: the fight can be won, not survived.
     *
     * <p>The bar is "more than one kill out of six", set when stare teleports made the count a spread (6,6,6,4,4,4,3,1
     * over eight archived runs). 0-or-1, the systematic break, still reddens; the speed question is
     * {@code fights.slow}, recorded and not gated, with a {@code fights.stall} row per fight that used the whole
     * budget.
     *
     * <p>The pearl is not a roll: each enderman carries a fixed loot seed, so every kill must yield exactly
     * {@link #PEARLS_PER_KILL}, and a short count means a kill did not reach its loot table.
     */
    @Test
    void serverEarnsAnEnderPearl(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();

        Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, 0, 0);
        // A lid and four walls, five high — tall enough for an enderman; they keep the fight in one place.
        final int r = 5, h = 5;
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    for (int dy = 1; dy <= h; dy++) {
                        boolean wall = Math.abs(dx) == r || Math.abs(dz) == r || dy == h;
                        if (wall)
                            level.setBlockAndUpdate(
                                    new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
                    }
        });

        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.DIAMOND_SWORD);
        // Vanilla's counter to the stare reaction; see the javadoc.
        game.runOnServer(s -> bot.player().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)));

        final int fights = 6;
        final int fightTicks = 4_000;
        int killed = 0, pearls = 0, slow = 0;
        StringBuilder tally = new StringBuilder();
        StringBuilder ticks = new StringBuilder();
        String[] stalls = {null};
        for (int i = 0; i < fights; i++) {
            final int fightNo = i;
            // One server task per fight: the bot and the enderman's own tick in lockstep.
            int[] fight = game.onServer(s -> {
                ServerLevel level = s.getLevel(plot.dimension());
                ServerPlayer fp = bot.player();
                EnderMan man = new EnderMan(EntityType.ENDERMAN, level);
                // A fixed loot seed makes the drop a constant, so the test asks "do kills reach the loot table" and
                // not "did this roll come up". Mob reads the seed only beside a DeathLootTable, so both are set;
                // load() goes first so the round trip cannot undo the position or persistence set after it.
                CompoundTag tag = man.saveWithoutId(new CompoundTag());
                tag.putString("DeathLootTable", "minecraft:entities/enderman");
                tag.putLong("DeathLootTableSeed", PEARL_SEED);
                man.load(tag);
                man.setPos(cx + 2.5, floorY + 1, cz + 0.5);
                man.setPersistenceRequired();
                level.addFreshEntity(man);
                man.setTarget(fp);

                int bagBefore = bot.count(Items.ENDER_PEARL);
                kill(bot, "minecraft:enderman");
                long searchesBefore = searches(bot);
                Vec3 prev = man.position();
                double maxJump = 0;
                int t = 0;
                for (; t < fightTicks && man.isAlive(); t++) {
                    bot.tick();
                    if (man.isAlive()) man.tick(); // AI ON: it fights back and can still teleport
                    maxJump = Math.max(maxJump, man.position().distanceTo(prev));
                    prev = man.position();
                }
                int dead = man.isAlive() ? 0 : 1;
                if (t >= fightTicks) {
                    // Target lost (a large jump, a long distance, few searches) or a search storm (searches in the
                    // hundreds): the four readings that tell the two apart. Recorded, not gated.
                    stalls[0] = (stalls[0] == null ? "" : stalls[0] + "; ") + "fight " + fightNo + ": alive="
                            + man.isAlive() + " removed=" + man.isRemoved()
                            + String.format(Locale.ROOT, " dist=%.1f maxJump=%.1f", man.distanceTo(fp), maxJump)
                            + " searches=" + (searches(bot) - searchesBefore)
                            + " process=" + bot.navigator().process().orElse("none");
                }
                bot.tick(10);

                int here = bot.count(Items.ENDER_PEARL) - bagBefore; // a pearl already picked up still counts
                for (ItemEntity d :
                        level.getEntitiesOfClass(ItemEntity.class, Staging.entityBox(new BlockPos(cx, floorY, cz)))) {
                    if (d.getItem().is(Items.ENDER_PEARL)) here += d.getItem().getCount();
                    d.discard();
                }
                man.discard();
                bot.park();
                return new int[] {dead, here, t};
            });
            if (fight[0] == 1) killed++;
            else slow++;
            pearls += fight[1];
            ticks.append(ticks.length() == 0 ? "" : ",").append(fight[2]);
            tally.append(tally.length() == 0 ? "" : ",").append(fight[0] == 0 ? "×" : fight[1] + "");
        }

        reporter.publishEntry("enderman.killed", killed + "/" + fights);
        reporter.publishEntry(
                "fights.slow",
                slow + "/" + fights + " fights used the full " + fightTicks
                        + "-tick budget without a kill; ticks per fight: " + ticks);
        reporter.publishEntry("pearls.perFight", tally + " (× = not killed)");
        reporter.publishEntry("pearls.total", pearls + "");
        if (stalls[0] != null) reporter.publishEntry("fights.stall", stalls[0]);
        assertTrue(
                killed >= 2,
                "a driven bot wearing a carved pumpkin kills an enderman in more than one of " + fights + " fights: "
                        + killed);
        assertEquals(
                killed * PEARLS_PER_KILL,
                pearls,
                "every seeded kill yields exactly what its seed rolls: " + tally + " (× = not killed)");
    }

    /**
     * The enderman's loot seed. Its table is one ender_pearl entry with {@code set_count} uniform 0..1 and no looting
     * on the sword, so the count is the seeded random's first {@code nextInt(2)}, which for this seed is 1.
     */
    private static final long PEARL_SEED = 20_261_029L;

    private static final int PEARLS_PER_KILL = 1;

    /** The searches the bot's running navigator has made, or -1 when it has none. Server thread. */
    private static long searches(SceneBot bot) {
        return bot.navigator().counters().getOrDefault("searches", -1L);
    }

    /**
     * A zombie two blocks from an idle bot with a sword, and no order given: with {@code autoFight} on the bot attacks
     * it. {@code CombatChain.bid}: the top threat scoring at least {@code autoFightThreatThreshold} (0.05; a zombie in
     * sight two blocks away scores about 0.6) bids {@code Priorities.COMBAT} and runs an ENGAGE fight. The same staging
     * is ticked with the setting off first, which must leave the zombie untouched.
     */
    @Test
    void autoFightAttacksAHostileInReach(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ(), floorY = o.getY();
        Settings settings = Settings.pinUntimed(game).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = fighter(game, plot, cx, floorY, cz, Items.IRON_SWORD);
        Zombie zombie = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Zombie z = new Zombie(EntityType.ZOMBIE, level);
            z.setNoAi(true);
            z.setPersistenceRequired();
            z.setPos(cx + 2.5, floorY + 1, cz + 0.5);
            level.addFreshEntity(z);
            return z;
        });
        game.afterTest(s -> zombie.discard());

        float off = game.onServer(s -> {
            fightTicks(bot, zombie, cx, cz, floorY, 60);
            return zombie.getHealth();
        });
        settings.set("autoFight", true);
        float[] on = game.onServer(s -> {
            int t = fightTicks(bot, zombie, cx, cz, floorY, 400);
            bot.park();
            return new float[] {zombie.getHealth(), t};
        });
        reporter.publishEntry("zombie.health.off", String.valueOf(off));
        reporter.publishEntry("zombie.health.on/ticks", on[0] + "/" + (int) on[1]);
        assertEquals(zombie.getMaxHealth(), off, "with autoFight off the idle bot hurt the zombie");
        assertTrue(on[0] < zombie.getMaxHealth(), "with autoFight on the bot did not hurt the zombie in 400 ticks");
    }

    /**
     * Tick the bot and the zombie in lockstep, the zombie held in place, until it is hurt or {@code max} ticks pass;
     * the ticks it took. Server thread.
     */
    private static int fightTicks(SceneBot bot, Zombie zombie, int cx, int cz, int floorY, int max) {
        int t = 0;
        for (; t < max && zombie.getHealth() >= zombie.getMaxHealth(); t++) {
            bot.tick();
            zombie.tick(); // its hurt cooldown
            zombie.setPos(cx + 2.5, floorY + 1, cz + 0.5);
            zombie.setDeltaMovement(Vec3.ZERO);
        }
        return t;
    }
}
