package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.Routes;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The navigator's and the server body's whole-walk measurements on a headless bot: the self-shaft dig-up, the gear
 * scope and the entity leash.
 *
 * <p>Each arena is built at absolute coordinates taken from its plot ({@code cx}, {@code cz}, {@code floorY}), so the
 * old geometry carries over line for line. A walk runs inside one {@code game.runOnServer}: the bot is ticked by the
 * test alone, with no world tick between its ticks, as the old scene body ran. The old fixed origin slot of
 * {@code selfShaftDigUp} only served a golden nothing asserts; it is gone, and the golden below is where the old runs
 * measured it.
 */
@Scene("wd.scenes")
class WorldDriverTest {

    private static final String COMMA = ",";

    private static final String NAVIGATOR_DEBUG = "navigatorDebug";

    /**
     * The gap #53 self-shaft dig-up: a bare-hand climb to a level 22 up from a sealed chamber must not fall back down
     * the hollow column it digs and pillars behind itself. Near the top A* used to re-plan a {@code parkourAscend2}
     * leap from the 1-wide pillar top onto the slab; a stationary pillar top has no run-up, so the executor launched
     * into the void and fell ~20 blocks down its own column. {@code pathfinderParkourAscendNeedRunway} (default ON)
     * makes A* reject the runway-less leap. Post-fix golden: {@code worstBackslide=1.2522034151012633} (the normal
     * pillar-jump arc); the bound is {@code pathfinderMaxDryFall + 1}, and a shaft fall blows it by fifteen.
     */
    @Test
    void selfShaftDigUp(Game game, @Plot.Size(x = 16, y = 32, z = 16) Plot plot) {
        BlockPos o = plot.at(8, 1, 8);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int baseY = o.getY();
        final int top = baseY + 20;
        final int targetY = top + 2;
        // Solid 7x7 stone slab base..top, air above, sealed 2-high chamber at the centre.
        plot.fill(5, 1, 5, 11, 21, 11, Blocks.STONE);
        plot.fill(8, 2, 8, 8, 3, 8, Blocks.AIR);
        Settings settings = Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set(NAVIGATOR_DEBUG, true);
        int maxDryFall = settings.integer("pathfinderMaxDryFall");
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, baseY + 1, cz + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64)); // pillar/plug stock; NO pickaxe (live parity)
            fp.getInventory().selected = 0;
            bot.goTo(Targets.level(targetY));
            double maxY = fp.getY();
            double worstBackslide = 0;
            boolean walking = true;
            for (int t = 0; t < 4000 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
                worstBackslide = Math.max(worstBackslide, maxY - fp.getY());
            }
            String reading = "step=" + NavWalk.step(bot) + " pos=(" + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ()
                    + ") maxY=" + maxY + " worstBackslide=" + worstBackslide;
            TestMod.LOG.info("[wd.selfShaftDigUp] {}", reading);
            if (worstBackslide > maxDryFall + 1) {
                fail("selfShaftDigUp: dig-up FELL back down its own shaft (> maxDryFall+1=" + (maxDryFall + 1)
                        + ") — the gap #53 death, reproduced: " + reading);
            }
            if (fp.getY() < targetY - 1.5) {
                fail("selfShaftDigUp: did not reach the level " + targetY + ": " + reading);
            }
        });
    }

    /**
     * The gap #46 gear scope: how much of a server body's held and worn gear is inert. A bare fist against an iron
     * sword on a fresh NoAI zombie ({@link SimProbes#probeSwing}), a fixed 10-point hit bare against full diamond
     * ({@link SimProbes#probeHurt}). Asserts sword damage ≥ 3× fist — an outcome, not a mirrored attribute: a zombie
     * losing more health to a sword than to a fist is what an agent pays for. Golden: {@code bareHand=0.94000053
     * ironSword=5.9040003}, full-diamond {@code ARMOR=20.0}.
     */
    @Test
    void gearScope(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        plot.fill(-2, 0, -2, 4, 0, 2, Blocks.STONE);
        Settings.pin(game).set(NAVIGATOR_DEBUG, false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);

        float bare = SimProbes.probeSwing(game, bot, ItemStack.EMPTY, cx, floorY, cz);
        float sword = SimProbes.probeSwing(game, bot, new ItemStack(Items.IRON_SWORD), cx, floorY, cz);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            final float tookBare = SimProbes.probeHurt(fp, false);
            final float tookArmored = SimProbes.probeHurt(fp, true);

            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            fp.getInventory().selected = 0;
            bot.step(); // the gear must land through the NORMAL tick, not a special API
            final double atk = fp.getAttributeValue(Attributes.ATTACK_DAMAGE);
            final double spd = fp.getAttributeValue(Attributes.ATTACK_SPEED);

            // Is the body hurtable at all? Measured rather than assumed: an invulnerable body makes "armor does
            // nothing" moot and the blast radius offense-only.
            fp.getInventory().clearContent();
            fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
            bot.step();
            String reading = "dealt bare=" + bare + " sword=" + sword + "; taken (10pt) bare=" + tookBare
                    + " fullDiamond=" + tookArmored + "; holding the sword ATTACK_DAMAGE=" + atk + " ATTACK_SPEED="
                    + spd + "; wearing diamond ARMOR=" + fp.getAttributeValue(Attributes.ARMOR) + " invulnerable="
                    + fp.isInvulnerableTo(fp.damageSources().generic());
            TestMod.LOG.warn("[wd.gearScope] {}", reading);
            if (bare <= 0f) {
                fail("gearScope: rig broken: a bare-handed swing dealt no damage at all: " + reading);
            }
            // Vanilla: fist = 1 damage, iron sword = 6; measured 0.94 vs 0.94 before the fix.
            if (sword < bare * 3.0f) {
                fail("gearScope: an iron sword deals no more than a bare fist: the body's held item never reaches "
                        + "its attributes, so server-mode melee swings a weapon it does not benefit from: " + reading);
            }
        });
    }

    /** Lets the leashed goto run its 200-tick window and fails when it escaped the leash before the anchor moved. */
    private static void runLeashPhaseOne(SceneBot bot, BlockPos goal, int cx, int cz, double leashRadius) {
        ServerPlayer fp = bot.player();
        bot.unpark();
        for (int t = 0; t < 200 && bot.busy(); t++) {
            bot.tick();
        }
        boolean finished1 = !bot.busy();
        double sdx = fp.getX() - (cx + 0.5);
        double sdz = fp.getZ() - (cz + 0.5);
        double standDist1 = Math.sqrt(sdx * sdx + sdz * sdz);
        boolean arrivedTrueGoal1 =
                Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        TestMod.LOG.info(
                "[wd.entityLeash] phase1 pos=({},{},{}) finished={} standDist={} arrivedTrueGoal={}",
                fp.getX(),
                fp.getY(),
                fp.getZ(),
                finished1,
                standDist1,
                arrivedTrueGoal1);
        if (finished1 || arrivedTrueGoal1) {
            fail("entityLeash: phase1: the goto reached the true goal before the anchor moved — the hard leash did "
                    + "not hold the bot back: pos=(" + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + ") finished="
                    + finished1);
        }
        if (standDist1 > leashRadius + 3.0) {
            fail("entityLeash: phase1: bot strayed beyond the leash radius+slack: standDist=" + standDist1 + " radius="
                    + leashRadius);
        }
    }

    /**
     * The dynamic entity-anchor leash: a real {@code mc.bot.goto} whose route leash centres on an armor stand, proving
     * the whole re-solve chain (find → dirty → rebuild profile → replan), not just the static leash math. Phase 1: the
     * stand is AT the bot's start; the goal is 24 blocks away but the HARD leash (radius 8) prunes every route node
     * farther than 8 from it, so the best-effort fallback only reaches the radius edge and the navigator holds there —
     * the goto stays busy for the whole 200-tick window. Phase 2: the stand moves 4 blocks PAST the goal; within the
     * leash's 20-tick re-solve limit the goto notices, rebuilds the search around the new anchor and arrives.
     *
     * <p>A fresh entity is not visible to the leash's scan until the level has indexed it, which takes real server
     * ticks; the bot is parked across each of those waits, so the test's own loops are its only driver. Golden: phase 1
     * {@code standDist=4.187857529833143}, phase 2 reached.
     */
    @Test
    void entityLeash(Game game, @Plot.Size(x = 16, y = 16, z = 32) Plot plot) {
        BlockPos o = plot.at(8, 1, 1);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int goalDz = 24;
        final double leashRadius = 8.0;
        // Side rails keep the navigator ON the lane, so the arena tests the leash, not edge-clipping churn.
        plot.fill(6, 1, 0, 10, 1, 1 + goalDz + 2, Blocks.STONE);
        plot.fill(6, 2, 0, 6, 2, 1 + goalDz + 2, Blocks.STONE);
        plot.fill(10, 2, 0, 10, 2, 1 + goalDz + 2, Blocks.STONE);
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + goalDz);
        // The phase-2 anchor: 4 blocks PAST the goal (NoGravity, floats past the lane end).
        final BlockPos p2anchor = new BlockPos(goal.getX(), floorY + 1, goal.getZ() + 4);
        Settings.pinUntimed(game).set(NAVIGATOR_DEBUG, false);
        ArmorStand stand = game.onServer(s -> {
            ArmorStand a = new ArmorStand(s.overworld(), cx + 0.5, floorY + 1, cz + 0.5); // AT the bot's start
            a.setNoGravity(true);
            s.overworld().addFreshEntity(a);
            return a;
        });
        game.afterTest(s -> stand.discard());
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, floorY + 1, cz + 0.5);
        game.runOnServer(s -> {
            // Near(1), not Block: "reach the destination AREA" — exact-cell parking is not this arena's gate.
            bot.goTo(
                    Targets.near(goal, 1),
                    Routes.plain()
                            .leashEntity("minecraft:armor_stand", leashRadius, true, 0)
                            .build());
            // Parked at once: starting the goto put the bot on the tick list, and the server must not drive it
            // during the wait for the stand to be indexed.
            bot.park();
        });

        // The leash's scan: every entity within 96 of the player, the nearest of the type winning.
        game.await()
                .within(180)
                .as("the leash's scan sees the fresh armor stand")
                .until(() -> game.onServer(s -> {
                    ServerPlayer fp = bot.player();
                    return !fp.level()
                            .getEntities(fp, fp.getBoundingBox().inflate(96.0), e -> e instanceof ArmorStand)
                            .isEmpty();
                }));
        game.runOnServer(s -> {
            runLeashPhaseOne(bot, goal, cx, cz, leashRadius);
            // 4 PAST the goal, beyond the navigator's overshoot band: a +2 stand was rammed by the carrot-drive
            // overshoot, and its collision shoved the bot onto the rails.
            stand.teleportTo(p2anchor.getX() + 0.5, floorY + 1, p2anchor.getZ() + 0.5);
            bot.park();
        });

        game.await()
                .within(180)
                .as("the armor stand is indexed at its new anchor")
                .until(() -> game.onServer(s -> !s.overworld()
                        .getEntitiesOfClass(ArmorStand.class, new AABB(p2anchor).inflate(2.0))
                        .isEmpty()));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.unpark();
            for (int t = 0; t < 600 && bot.busy(); t++) {
                bot.tick();
            }
            boolean finished2 = !bot.busy();
            boolean reached =
                    Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            TestMod.LOG.info(
                    "[wd.entityLeash] phase2 pos=({},{},{}) finished={} reached={}",
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    finished2,
                    reached);
            if (!finished2) {
                fail("entityLeash: phase2: the leash re-solve goto did not end after the anchor moved");
            }
            if (!reached) {
                fail("entityLeash: phase2: bot did not ARRIVE at the goal after the anchor moved: pos=(" + fp.getX()
                        + COMMA + fp.getY() + COMMA + fp.getZ() + ")");
            }
        });
    }
}
