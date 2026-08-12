package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * The two nether rungs: a blaze rod, and an ender pearl.
 *
 * <p>Registered by {@code WorldDriverJourneyScenes} from {@link #rungs()} rather than written into
 * it — the ladder file is already at its source budget, and these two rungs are the first that need
 * a second dimension, a mob that flies, and a mob that teleports.
 *
 * <h2>What is scripted here, and why it is shaped like this</h2>
 *
 * Both rungs come from measurements taken in sealed arenas, which is the order this suite is
 * supposed to work in: {@code wd.serverEarnsABlazeRod} answered the loot table, {@code
 * wd.serverFightsAFlyingBlaze} answered the geometry, {@code wd.serverEarnsAnEnderPearl} answered
 * the teleport. Everything below is those answers applied to a world nobody staged.
 *
 * <ul>
 *   <li><b>Walls first, then the ceiling.</b> A driven body cannot finish a blaze under open sky —
 *       3000 ticks took one from 20 health to 8 and never killed it, because the mob hovers six to
 *       eight blocks up and melee reaches about three. The same fight in a closed room takes 40
 *       ticks. <b>A bare lid is not a room:</b> the first arena attempt roofed an open floor, got
 *       the blaze to 2 health, and lost it — it drifted out past the lid's edge and climbed over.
 *       So {@link #roomShell} lays every wall course before it lays a single ceiling cell, and the
 *       ordering is the finding, not a preference.</li>
 *   <li><b>A majority, not a clean sweep.</b> The enderman rung asserts four kills out of six and
 *       not six of six, for the same reason the arena does: teleport-on-hurt makes each fight a
 *       random process, a real fortress is not a sealed box, and an assertion pinned to the tail of
 *       a random process reddens the gate for the one outcome that is good news.</li>
 *   <li><b>Nothing is staged.</b> No {@code setblock}, no {@code summon}, no {@code give}. The room
 *       is built out of blocks the body is carrying or quarries on the spot, through the same
 *       {@code useItemOn} path a player's right click takes, so every wall cell costs a real item.
 *       The blazes and the endermen have to be ones the world produced.</li>
 * </ul>
 *
 * <h2>The wall these rungs currently hit, stated up front</h2>
 *
 * Two engine facts stand between this script and a green row, and neither is fixable from a test:
 *
 * <ol>
 *   <li><b>The driver's {@code WorldView} is pinned to the level the body was created in.</b>
 *       {@code ServerWorldDriver} builds one {@code LevelWorldView} in its constructor, from
 *       {@code fakePlayer().level()}, and hands that same view to every {@code BotProcess} for the
 *       rest of the run. The journey's body is created in the overworld at SPAWN and never
 *       replaced, so from the moment it steps through the portal the pathfinder is planning routes
 *       across <b>overworld terrain at nether coordinates</b>. {@link #worldViewDisagreements} is
 *       the cheap decisive probe for it — the same cells read two ways, which must agree if the
 *       view belongs to the level the body is standing in — and both rungs run it before they spend
 *       a budget on a walk that cannot work.</li>
 *   <li><b>A {@code FakePlayer} is not in {@code level.players()}.</b> {@code BaseSpawner
 *       .isNearPlayer} reads that list, and so does natural spawning, so a spawner near this body
 *       never turns and no mob ever appears for it to fight. That is not something the ladder may
 *       stage its way around, so {@link #whyNothingSpawns} says it in the failure instead of
 *       leaving a reader with "no blaze appeared".</li>
 * </ol>
 *
 * <p>Both are written down here rather than worked around, on the rule this suite is built on: when
 * a stage cannot be scripted, that IS the finding. The rest of the script below is complete and
 * runs the moment either of those is fixed.
 */
public final class JourneyNetherRungs {

    private JourneyNetherRungs() {}

    // =====================================================================================
    // Registration.
    // =====================================================================================

    /**
     * The two rungs, in ladder order, for {@code WorldDriverJourneyScenes} to register.
     *
     * <p>Shaped exactly like the ladder's own {@code stage(...)}: required-ness read from
     * {@link JourneyStage#gating()} so promotion stays one edit in one place, and
     * {@code withArena(false)} because the journey never sets foot in its allocated plot — waiting
     * on an arena it does not use is what took two rungs out of one earlier run.
     */
    public static List<Scene> rungs() {
        List<Scene> out = new ArrayList<>();
        // 360 000, and the number is the arithmetic of the plan rather than caution: the crossing
        // may re-plan three times at FORTRESS_WALK_TICKS with a midpoint leg between each (≈192k),
        // then the approach (6k), up to sixty quarry legs (18k), the wait for the spawner to turn
        // (2.4k) and eight fights (9.6k). A budget sized for one clean walk would turn "the
        // fortress is far" into a timeout, which is the wrong sentence about the right world.
        out.add(rung("wd.journey14BlazeRod", JourneyStage.BLAZE_ROD, 360_000,
                JourneyNetherRungs::blazeRod));
        // 120 000: no build, and the walk is to whatever enderman is already loaded rather than to
        // a landmark — six fights plus the waits between them.
        out.add(rung("wd.journey15EnderPearl", JourneyStage.ENDER_PEARL, 120_000,
                JourneyNetherRungs::enderPearl));
        return List.copyOf(out);
    }

    private static Scene rung(String name, JourneyStage stage, int budget,
                              java.util.function.Consumer<SceneContext> body) {
        return Scene.of(name, budget, body).withRequired(stage.gating()).withArena(false);
    }

    // =====================================================================================
    // 14 — BLAZE_ROD. Walk to the fortress, wall in the spawner, fight inside.
    // =====================================================================================

    /**
     * Earn a blaze rod the way a player does: find the spawner, build the room, then fight.
     *
     * <p>The room is the whole trick and it is a step in the plan, not a capability gap — see the
     * class note for the 75× measurement that put it there. Nothing here asks the engine for a
     * ranged attack, a hover-aware melee loop, or any other verb: the fix for "melee cannot reach a
     * hovering mob" is a ceiling.
     */
    private static void blazeRod(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.BLAZE_ROD);
        rig.attempting("走到下界要塞，把烈焰人刷怪笼围成一间封顶小屋，在屋里打出烈焰棒");
        rig.generousPathfinding();
        rig.liveWorld(true);
        JourneyRig.seeAtLeast(SEE_CHUNKS);
        ctx.cleanup(JourneyRig::seeNormally);

        if (!standingInTheNether(ctx, rig)) return;
        if (!theViewMatchesTheWorld(ctx, rig)) return;

        BlockPos here = rig.player().blockPosition();
        rig.evidence("arrival.at", here.toShortString());
        rig.evidence("arrival.biome", biomeAt(rig, here));
        rig.evidence("blocks.carried", placeableCount(rig) + " 个可放置方块");
        rig.evidence("blaze_rod.before", rig.carrying(BLAZE_ROD));

        BlockPos fortress = fortressLandmark(ctx, rig, here);
        if (fortress == null) return;

        int away = (int) Math.round(Math.sqrt(here.distSqr(fortress)));
        rig.evidence("fortress.at", fortress.toShortString() + "（距身体 " + away + " 格）");
        rig.attempting("走到要塞 " + fortress.toShortString() + "（" + away + " 格）");
        walkToColumn(rig, "fortress", fortress.getX(), fortress.getZ(), FORTRESS_ARRIVE_WITHIN,
                FORTRESS_WALK_TICKS,
                () -> findTheSpawner(ctx, rig),
                () -> ctx.fail("走不到要塞 " + fortress.toShortString() + "：停在 "
                        + rig.player().blockPosition().toShortString()
                        + "。下界的路是熔岩海和峡谷，这一段是这一级最贵的一步"));
    }

    /**
     * The fortress landmark, surveyed on the spot when nobody has baked one.
     *
     * <p>Surveyed rather than required, because this is the one landmark the recon scene cannot
     * check the way it checks the others: it lives in a dimension the survey never visits, so there
     * is no earlier value for a later run to disagree with. The price is recorded either way — see
     * {@link JourneyRoute.Located} for why a survey's cost is part of its answer.
     */
    private static BlockPos fortressLandmark(SceneContext ctx, JourneyRig rig, BlockPos here) {
        if (!JourneyRoute.netherFortress.equals(JourneyRoute.UNSURVEYED)) {
            rig.evidence("fortress.landmark", "烘好的 " + JourneyRoute.netherFortress.toShortString()
                    + "（上次勘测耗时 " + JourneyRoute.netherFortressMs + " ms）");
            return JourneyRoute.netherFortress;
        }
        JourneyRoute.Located survey = JourneyRoute.surveyNetherFortressFrom(ctx, here);
        rig.evidence("fortress.survey", survey.asRecord());
        if (survey.found().where() == null) {
            ctx.fail("生成器说这附近没有下界要塞（以 " + here.toShortString() + " 为心）—— "
                    + survey.asRecord() + "。注意搜索半径的单位是放置区不是区块，"
                    + "放宽之前先读 JourneyRoute 里那条常量的说明");
            return null;
        }
        return survey.found().where();
    }

    /** The nearest spawner in the loaded fortress chunks, and what it is set to spawn. */
    private static void findTheSpawner(SceneContext ctx, JourneyRig rig) {
        ServerLevel nether = rig.player().serverLevel();
        BlockPos at = rig.player().blockPosition();
        rig.evidence("fortress.arrivedAt", at.toShortString());
        rig.evidence("fortress.biome", biomeAt(rig, at));

        BlockPos spawner = nearestSpawner(nether, at, SPAWNER_SEARCH_CHUNKS);
        if (spawner == null) {
            ctx.fail("要塞落点 " + at.toShortString() + " 周围 " + SPAWNER_SEARCH_CHUNKS
                    + " 个区块里没有刷怪笼。要塞的刷笼在桥面平台上，落点偏了一片就会整片扫空 —— "
                    + "这是落点的问题，不是驱动的问题");
            return;
        }
        rig.evidence("spawner.at", spawner.toShortString() + "（距身体 "
                + Math.round(Math.sqrt(at.distSqr(spawner))) + " 格）");
        rig.evidence("spawner.spawns", spawnerMob(nether, spawner));

        rig.attempting("走到刷怪笼 " + spawner.toShortString() + " 旁边");
        rig.settle(new IntentProcess(new Intent(new Goal.Near(spawner, 2))), SPAWNER_APPROACH_TICKS, () -> {
            BlockPos stood = rig.player().blockPosition();
            double gap = Math.sqrt(stood.distSqr(spawner));
            rig.evidence("spawner.stoodAt", stood.toShortString()
                    + "（距刷怪笼 " + String.format(Locale.ROOT, "%.1f", gap) + " 格）");
            if (gap > SPAWNER_MUST_BE_WITHIN) {
                ctx.fail("走不到刷怪笼旁边：停在 " + stood.toShortString() + "，距 "
                        + spawner.toShortString() + " 还有 "
                        + String.format(Locale.ROOT, "%.1f", gap) + " 格 —— "
                        + "屋子要围住刷怪范围，人不在里面就围不成");
                return;
            }
            sealTheRoom(ctx, rig, spawner);
        });
    }

    // ---- the room ----

    /**
     * The cells that turn an open spawner platform into a room, <b>walls first, then the ceiling</b>.
     *
     * <p>The order is the measurement. A bare lid over an open floor took an arena blaze to 2 health
     * and still lost it: the mob went SIDEWAYS, out past the lid's edge, and climbed above it. So
     * every wall course is emitted before any ceiling cell, and a run that goes short of material
     * goes short of ceiling rather than short of walls.
     *
     * <p>Sized on the spawner rather than on the body, because what has to be enclosed is the
     * SPAWN volume — vanilla scatters spawns up to four cells either side of the block — and a room
     * built around wherever the walk happened to stop would leave most of that volume outside it.
     */
    private static List<BlockPos> roomShell(BlockPos spawner) {
        List<BlockPos> out = new ArrayList<>();
        for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
                    if (Math.abs(dx) == ROOM_RADIUS || Math.abs(dz) == ROOM_RADIUS) {
                        out.add(spawner.offset(dx, dy, dz));
                    }
                }
            }
        }
        for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
            for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
                out.add(spawner.offset(dx, ROOM_HEIGHT, dz));
            }
        }
        return out;
    }

    private static void sealTheRoom(SceneContext ctx, JourneyRig rig, BlockPos spawner) {
        ServerLevel nether = rig.player().serverLevel();
        List<BlockPos> shell = roomShell(spawner);
        int open = 0;
        for (BlockPos cell : shell) if (!nether.getBlockState(cell).blocksMotion()) open++;
        rig.evidence("room.shell", shell.size() + " 格外壳，其中 " + open + " 格是空的（"
                + (2 * ROOM_RADIUS - 1) + "×" + (2 * ROOM_RADIUS - 1) + "×" + ROOM_HEIGHT + " 的屋子）");
        rig.evidence("room.stockBefore", placeableCount(rig) + " 个可放置方块");
        rig.attempting("先补齐石料，再按 先墙后顶 的顺序把刷怪笼围起来");
        Set<BlockPos> keepOut = new HashSet<>(shell);
        quarryUntilStocked(rig, keepOut, open, MAX_QUARRY_LEGS, MAX_QUARRY_LEGS,
                () -> layTheShell(ctx, rig, spawner, shell));
    }

    /**
     * Mine nearby rock until there is enough of it to build with, or the legs run out.
     *
     * <p>Bounded on purpose, and the bound is a budget statement rather than a safety one: each leg
     * is a walk plus a break plus a settle, so a quarry sized to fill the whole shell would cost
     * more ticks than the fight it exists to enable. A run that comes up short still builds — walls
     * first — and records exactly how short, which is the reading that says whether the ladder
     * should be arriving in the Nether with more in the bag.
     */
    private static void quarryUntilStocked(JourneyRig rig, Set<BlockPos> keepOut,
                                           int need, int legsLeft, int legsTotal, Runnable then) {
        if (placeableCount(rig) >= need || legsLeft <= 0) {
            rig.evidence("quarry.legs", (legsTotal - legsLeft) + "/" + legsTotal);
            rig.evidence("quarry.stock", placeableCount(rig) + "/" + need + " 格所需石料");
            then.run();
            return;
        }
        BlockPos rock = quarryCell(rig, keepOut);
        if (rock == null) {
            rig.evidence("quarry.legs", (legsTotal - legsLeft) + "/" + legsTotal + "（身边挖不到更多石料）");
            rig.evidence("quarry.stock", placeableCount(rig) + "/" + need + " 格所需石料");
            then.run();
            return;
        }
        rig.mineCellOrGiveUp(rock, QUARRY_LEG_TICKS, () -> rig.settle(new HoldStill(4), 12,
                () -> quarryUntilStocked(rig, keepOut, need, legsLeft - 1, legsTotal, then)));
    }

    /**
     * Lay the shell in one pass, and report what actually stood up.
     *
     * <p>One pass rather than one cell per await step, because a placement is instantaneous and
     * server-authoritative — nothing has to tick between two of them — and 177 await steps would
     * cost the rung its budget to model a delay that does not exist. Breaking and falling need the
     * world to advance; placing does not.
     *
     * <p>The count that matters is read back off the WORLD, not off the number of calls made. A
     * placement can be refused for reasons the caller cannot see (the cell is not empty after all,
     * the body is standing in it, vanilla found no face to place against), and a rung that counted
     * its own attempts would report a room it does not have.
     */
    private static void layTheShell(SceneContext ctx, JourneyRig rig, BlockPos spawner,
                                    List<BlockPos> shell) {
        ServerLevel nether = rig.player().serverLevel();
        BlockPos body = rig.player().blockPosition();
        int placed = 0, refused = 0, ranOut = 0, occupied = 0, walls = 0, roof = 0;
        for (BlockPos cell : shell) {
            if (nether.getBlockState(cell).blocksMotion()) continue;
            if (cell.equals(body) || cell.equals(body.above())) { occupied++; continue; }
            String id = placeableBlock(rig);
            if (id == null || !rig.body().avatar().holdItem(item(id))) { ranOut++; continue; }
            if (placeAt(rig, nether, cell)) {
                placed++;
                if (cell.getY() - spawner.getY() >= ROOM_HEIGHT) roof++; else walls++;
            } else {
                refused++;
            }
        }
        int stillOpen = 0;
        for (BlockPos cell : shell) if (!nether.getBlockState(cell).blocksMotion()) stillOpen++;

        rig.evidence("room.placed", placed + " 格（墙 " + walls + "，顶 " + roof + "）");
        rig.evidence("room.refused", refused + " 格放不上（没有可贴的面，或者格子并不是空的）");
        rig.evidence("room.ranOut", ranOut + " 格没石料了");
        rig.evidence("room.bodyInTheWay", occupied + " 格是身体自己占着的");
        rig.evidence("room.stillOpen", stillOpen + " 格仍然是通的");
        rig.evidence("room.stockAfter", placeableCount(rig) + " 个可放置方块");
        // Recorded, never asserted. "The room is not finished" is a reason the FIGHT may go badly,
        // and the fight is what this rung claims; failing here would replace a measurement of the
        // driver with a measurement of how much cobblestone the rung below happened to leave.
        waitForABlaze(ctx, rig, spawner);
    }

    // ---- the fight ----

    private static void waitForABlaze(SceneContext ctx, JourneyRig rig, BlockPos spawner) {
        ServerLevel nether = rig.player().serverLevel();
        rig.evidence("level.players", nether.players().size());
        rig.evidence("gamerule.doMobSpawning",
                nether.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING));
        rig.evidence("gamerule.doMobLoot", nether.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT));
        rig.attempting("等刷怪笼转出第一只烈焰人");

        int[] waited = {0};
        rig.await(() -> !blazesNear(nether, spawner).isEmpty() || ++waited[0] >= SPAWN_WAIT_TICKS,
                SPAWN_WAIT_TICKS + 200, () -> {
            List<Blaze> seen = blazesNear(nether, spawner);
            rig.evidence("blaze.appeared", seen.size() + " 只（等了 " + waited[0] + " tick）");
            if (seen.isEmpty()) {
                ctx.fail("刷怪笼 " + spawner.toShortString() + " 等了 " + waited[0]
                        + " tick 一只烈焰人都没出来 —— " + whyNothingSpawns(nether));
                return;
            }
            rig.evidence("weapon", holdBestWeapon(rig));
            rig.attempting("在屋里把烈焰人打死并捡起烈焰棒");
            fightOneBlaze(ctx, rig, spawner, BLAZE_FIGHTS, new StringBuilder(), new int[]{0});
        });
    }

    /**
     * One blaze, then the next, until there is a rod in the bag or the rounds run out.
     *
     * <p>Driven by hand rather than through {@link JourneyRig#settle} for one reason: the altitude.
     * The arena measured a blaze hovering six to eight blocks up under open sky and about one inside
     * a room, and that number is what says whether a failed fight was a bad room or a bad fight. It
     * can only be sampled per tick, and only the await predicate runs every tick.
     */
    private static void fightOneBlaze(SceneContext ctx, JourneyRig rig, BlockPos spawner,
                                      int roundsLeft, StringBuilder tally, int[] killed) {
        ServerLevel nether = rig.player().serverLevel();
        int rods = rig.carrying(BLAZE_ROD);
        if (roundsLeft <= 0 || rods >= BLAZE_RODS_WANTED) {
            blazeVerdict(ctx, rig, tally, killed[0]);
            return;
        }
        List<Blaze> here = blazesNear(nether, spawner);
        if (here.isEmpty()) {
            int[] again = {0};
            rig.await(() -> !blazesNear(nether, spawner).isEmpty() || ++again[0] >= RESPAWN_WAIT_TICKS,
                    RESPAWN_WAIT_TICKS + 100, () -> {
                if (blazesNear(nether, spawner).isEmpty()) {
                    tally.append(tally.length() == 0 ? "" : ",").append("×没再刷");
                    blazeVerdict(ctx, rig, tally, killed[0]);
                } else {
                    fightOneBlaze(ctx, rig, spawner, roundsLeft, tally, killed);
                }
            });
            return;
        }

        final Blaze target = here.get(0);
        final int before = rods;
        final int round = BLAZE_FIGHTS - roundsLeft + 1;
        ServerWorldDriver driver = rig.body();
        ServerAvatarManager.register(driver.runProcess(
                new CombatProcess(CombatProcess.Mode.KILL, target.getId(), null)));
        double[] highest = {target.getY()};
        int[] waited = {0};
        rig.await(() -> {
            if (target.isAlive()) highest[0] = Math.max(highest[0], target.getY());
            return !target.isAlive() || driver.finished() || ++waited[0] >= BLAZE_FIGHT_TICKS;
        }, BLAZE_FIGHT_TICKS + 100, () -> {
            ServerAvatarManager.unregister(driver);
            boolean dead = !target.isAlive();
            if (dead) killed[0]++;
            int now = rig.carrying(BLAZE_ROD);
            tally.append(tally.length() == 0 ? "" : ",").append(dead ? String.valueOf(now - before) : "×没打死");
            rig.evidence("fight." + round, (dead ? "打死" : "没打死") + "，用了 " + waited[0]
                    + " tick，最高离地 " + String.format(Locale.ROOT, "%.1f",
                            highest[0] - rig.player().getY()) + " 格");
            fightOneBlaze(ctx, rig, spawner, roundsLeft - 1, tally, killed);
        });
    }

    /**
     * The rung's claim, asserted on the ITEM and on nothing else.
     *
     * <p>Not on the kill: {@code wd.serverEarnsABlazeRod} exists because vanilla gates this drop on
     * {@code killed_by_player}, so a body that hits hard enough to kill and does not register as a
     * player clears a fortress and comes home with nothing — and the kill count says the fight went
     * fine the whole time. Not on the room either; a finished room with an empty bag is not this
     * rung.
     */
    private static void blazeVerdict(SceneContext ctx, JourneyRig rig, StringBuilder tally, int killed) {
        int rods = rig.carrying(BLAZE_ROD);
        rig.evidence("blaze.killed", killed + " 只");
        rig.evidence("rods.perKill", tally.length() == 0 ? "一场没打" : tally.toString());
        rig.evidence("blaze_rod", rods);
        rig.evidence("dropsNearby", rig.dropsNearby(BLAZE_ROD, 8) + " 根掉在地上没捡");
        rig.noteAdvancement("minecraft:nether/obtain_blaze_rod");
        ctx.expect(rods).as("烈焰棒真的进了包（不是打死了就算）").isAtLeast(1);
        rig.reach("在自己围出来的屋子里打死 " + killed + " 只烈焰人，收 " + rods + " 根烈焰棒");
    }

    // =====================================================================================
    // 15 — ENDER_PEARL. Hunt what teleports away from you.
    // =====================================================================================

    /**
     * Hunt endermen for pearls.
     *
     * <p>Hunted in the Nether because that is where the rung below leaves the body, and because the
     * warped forest is the densest enderman ground on the road to the dragon — the alternative is
     * walking back through the portal and waiting for an overworld night, which is a longer plan
     * for the same mob.
     *
     * <p>The bar is a MAJORITY of the hunts, with a per-kill tally beside it. An enderman's defence
     * is to stop being there, so every fight is a random process; the claim worth gating is
     * "teleport-on-hurt does not make it unkillable", which a majority establishes and which a
     * systematic failure (the loop can never land a second hit) still shows up as 0 or 1.
     */
    private static void enderPearl(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.ENDER_PEARL);
        rig.attempting("在下界猎末影人，把末影珍珠攒进包里");
        rig.generousPathfinding();
        rig.liveWorld(true);
        JourneyRig.seeAtLeast(SEE_CHUNKS);
        ctx.cleanup(JourneyRig::seeNormally);

        if (!standingInTheNether(ctx, rig)) return;
        if (!theViewMatchesTheWorld(ctx, rig)) return;

        ServerLevel nether = rig.player().serverLevel();
        BlockPos here = rig.player().blockPosition();
        rig.evidence("hunt.from", here.toShortString());
        rig.evidence("hunt.biome", biomeAt(rig, here));
        rig.evidence("level.players", nether.players().size());
        rig.evidence("gamerule.doMobSpawning",
                nether.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING));
        rig.evidence("ender_pearl.before", rig.carrying(ENDER_PEARL));
        rig.evidence("weapon", holdBestWeapon(rig));

        // found/killed, carried in an array because the hunt is a chain of continuations and a
        // local cannot survive one.
        huntOne(ctx, rig, ENDERMAN_HUNTS, new int[]{0, 0}, new StringBuilder());
    }

    private static void huntOne(SceneContext ctx, JourneyRig rig, int roundsLeft,
                                int[] foundAndKilled, StringBuilder tally) {
        ServerLevel nether = rig.player().serverLevel();
        if (roundsLeft <= 0) { pearlVerdict(ctx, rig, foundAndKilled, tally); return; }

        EnderMan target = nearestEnderman(nether, rig.player().blockPosition());
        if (target == null) {
            int[] waited = {0};
            rig.await(() -> nearestEnderman(nether, rig.player().blockPosition()) != null
                            || ++waited[0] >= ENDERMAN_WAIT_TICKS,
                    ENDERMAN_WAIT_TICKS + 100, () -> {
                if (nearestEnderman(nether, rig.player().blockPosition()) == null) {
                    tally.append(tally.length() == 0 ? "" : ",").append("×没找到");
                    pearlVerdict(ctx, rig, foundAndKilled, tally);
                } else {
                    huntOne(ctx, rig, roundsLeft, foundAndKilled, tally);
                }
            });
            return;
        }

        foundAndKilled[0]++;
        final int round = ENDERMAN_HUNTS - roundsLeft + 1;
        final int before = rig.carrying(ENDER_PEARL);
        final EnderMan man = target;
        // Walk to it FIRST, then engage. CombatProcess scans 32 blocks and gives up in two ticks
        // when nothing matches, so handing it a target that is further away than that reads exactly
        // like a broken combat verb — the same trap the food rung's hunt had to learn.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(man.blockPosition(), 3))),
                ENDERMAN_APPROACH_TICKS, () -> {
            if (!man.isAlive()) {                      // it died to something else, or despawned
                tally.append(tally.length() == 0 ? "" : ",").append("×走到时已经没了");
                huntOne(ctx, rig, roundsLeft - 1, foundAndKilled, tally);
                return;
            }
            ServerWorldDriver driver = rig.body();
            ServerAvatarManager.register(driver.runProcess(
                    new CombatProcess(CombatProcess.Mode.KILL, man.getId(), null)));
            int[] waited = {0};
            rig.await(() -> !man.isAlive() || driver.finished() || ++waited[0] >= ENDERMAN_FIGHT_TICKS,
                    ENDERMAN_FIGHT_TICKS + 100, () -> {
                ServerAvatarManager.unregister(driver);
                boolean dead = !man.isAlive();
                if (dead) foundAndKilled[1]++;
                int now = rig.carrying(ENDER_PEARL);
                tally.append(tally.length() == 0 ? "" : ",")
                        .append(dead ? String.valueOf(now - before) : "×没打死");
                rig.evidence("hunt." + round, (dead ? "打死" : "没打死") + "，用了 " + waited[0] + " tick");
                huntOne(ctx, rig, roundsLeft - 1, foundAndKilled, tally);
            });
        });
    }

    private static void pearlVerdict(SceneContext ctx, JourneyRig rig, int[] foundAndKilled,
                                     StringBuilder tally) {
        ServerLevel nether = rig.player().serverLevel();
        int found = foundAndKilled[0], killed = foundAndKilled[1];
        int pearls = rig.carrying(ENDER_PEARL);
        rig.evidence("enderman.found", found + "/" + ENDERMAN_HUNTS + " 场找到了目标");
        rig.evidence("enderman.killed", killed + "/" + ENDERMAN_HUNTS);
        rig.evidence("pearls.perFight", tally.length() == 0 ? "一场没打" : tally.toString());
        rig.evidence("ender_pearl", pearls);
        rig.evidence("dropsNearby", rig.dropsNearby(ENDER_PEARL, 8) + " 颗掉在地上没捡");
        rig.evidence("arena", "真的下界，不是盒子 —— 瞬移可以真的把它带走");
        if (found == 0) {
            ctx.fail("身边 " + ENDERMAN_SEARCH + " 格内一只末影人都没有，等了也没等到 —— "
                    + whyNothingSpawns(nether));
            return;
        }
        // A MAJORITY, and the bar is where it is because the alternative sits on the wrong side of
        // the dice: an enderman teleports when hurt and a fortress is not a sealed box, so demanding
        // every hunt land would redden this row for a slow fight rather than a broken one.
        ctx.expect(killed).as("会瞬移的末影人不是打不死的（" + ENDERMAN_HUNTS + " 场里的多数）")
                .isAtLeast(ENDERMEN_TO_KILL);
        // No advancement recorded here on purpose: vanilla has none for obtaining a pearl, and
        // noting a nearby-sounding one (`story/follow_ender_eye` is entering a STRONGHOLD) would
        // put a permanent not-earned on this row for a thing this rung was never about.
        ctx.expect(pearls).as("珍珠真的进了包（打死了不等于捡到了）").isAtLeast(1);
        rig.reach(ENDERMAN_HUNTS + " 场里打死 " + killed + " 只末影人，收 " + pearls + " 颗末影珍珠");
    }

    // =====================================================================================
    // Guards — the two engine facts these rungs run into, measured rather than assumed.
    // =====================================================================================

    /** Refuse to run a nether rung on a body that is not in the Nether. */
    private static boolean standingInTheNether(SceneContext ctx, JourneyRig rig) {
        rig.evidence("dimension", rig.dimension());
        if ("minecraft:the_nether".equals(rig.dimension())) return true;
        ctx.fail("身体不在下界（现在是 " + rig.dimension() + "，站在 "
                + rig.player().blockPosition().toShortString() + "）—— "
                + "NETHER 那一级说走过去了，这一级却站在别处，两者必有一个是假的");
        return false;
    }

    /**
     * Check that the driver's own world view describes the level the body is standing in.
     *
     * <p>The cheapest decisive probe there is: read the same cells twice, once through the view the
     * pathfinder plans on and once straight off the level, and count the disagreements. Two reads of
     * one world agree in all 27; a single disagreement proves they are two worlds, and 27
     * disagreements is not needed to prove it.
     *
     * <p>Why this rung spends a step on it. {@code ServerWorldDriver} builds its
     * {@code LevelWorldView} once, in its constructor, from the level the body was created in — and
     * the journey's body is created in the overworld at SPAWN and never replaced. So from the moment
     * it steps through the portal, every {@code BotProcess} it runs is handed a view of the
     * OVERWORLD indexed by nether coordinates. Nothing about that is visible from the outside: the
     * walker plans a route, drives it, and reports a perfectly ordinary failure to arrive.
     *
     * <p>Failing here rather than walking is deliberate. A nether crossing is thousands of blocks
     * and this rung's whole budget; spending it to rediscover something one comparison already
     * established would cost a run and produce a worse sentence.
     */
    private static boolean theViewMatchesTheWorld(SceneContext ctx, JourneyRig rig) {
        int off = worldViewDisagreements(rig);
        rig.evidence("worldview.disagreements", off + "/27 格（身体周围 3×3×3，两种读法）");
        if (off == 0) return true;
        ctx.fail("驱动的寻路视图不是身体脚下这个世界：身体周围 27 格里有 " + off
                + " 格，LevelWorldView 和 ServerLevel 读出来不一样。"
                + "ServerWorldDriver 在构造时用 fakePlayer().level() 建了一个 LevelWorldView 就再不换，"
                + "而这具身体是在主世界 SPAWN 那一级造出来的 —— 过了传送门之后，"
                + "每一次寻路都是拿下界的坐标去查主世界的方块。"
                + "这一级往下的脚本（找刷怪笼、围屋子、打架）都写好了，等这一条修好就能跑");
        return false;
    }

    /** How many of the 27 cells around the body the two readings disagree about. */
    private static int worldViewDisagreements(JourneyRig rig) {
        ServerLevel level = rig.player().serverLevel();
        var view = rig.body().world();
        BlockPos at = rig.player().blockPosition();
        int off = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos c = at.offset(dx, dy, dz);
                    if (view.isSolid(c) != level.getBlockState(c).blocksMotion()) off++;
                }
            }
        }
        return off;
    }

    /**
     * Why the world produced no mob, said in the failure rather than left for a reader.
     *
     * <p>"No blaze appeared" has two completely different causes with opposite fixes, and only one
     * of them is anybody's bug. A spawner turns only when {@code BaseSpawner.isNearPlayer} finds
     * somebody in {@code level.players()}, and a {@code FakePlayer} is a {@code ServerPlayer} that
     * was never PLACED — it is not in that list, and natural spawning reads the same list. So on the
     * default headless body no mob can ever appear, however long the rung waits, and reporting that
     * as "the fight failed" would send the next round at the combat loop.
     */
    private static String whyNothingSpawns(ServerLevel level) {
        if (!level.players().isEmpty()) {
            return "本层 level.players() 里有 " + level.players().size()
                    + " 个玩家，所以这不是 isNearPlayer 的问题 —— 要往刷怪条件查（光照、脚下方块、"
                    + "同类上限、屋子把刷怪点全堵死了）";
        }
        return "本层 level.players() 是空的。BaseSpawner.isNearPlayer 读的正是这份名单，"
                + "自然刷怪也读它；而这具身体是 FakePlayer —— 一个从来没有被 PlayerList.placeNewPlayer "
                + "放进服务器的 ServerPlayer，所以它不在名单里，刷怪笼一次也不会转。"
                + "这不是战斗逻辑的问题，加多少 tick 都等不来。要给这一级机会，"
                + "journeyServer 这条 run 配置得带上 -Dworlddriver.realPlayerBodies=true（JoinedPlayerBodies）";
    }

    // =====================================================================================
    // Reading the world.
    // =====================================================================================

    /**
     * The nearest spawner in the chunks around the body, found through the block ENTITIES.
     *
     * <p>Not a block scan. The volume worth searching for a fortress landmark is a few chunks in
     * every direction and thirty blocks of height, which is a quarter of a million {@code
     * getBlockState} calls on the tick thread; the chunk already keeps an index of exactly the
     * blocks that have an entity attached, and a spawner is one of them. Same answer, three orders
     * of magnitude cheaper.
     */
    private static BlockPos nearestSpawner(ServerLevel level, BlockPos from, int chunkRadius) {
        ChunkPos centre = new ChunkPos(from);
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int cx = centre.x - chunkRadius; cx <= centre.x + chunkRadius; cx++) {
            for (int cz = centre.z - chunkRadius; cz <= centre.z + chunkRadius; cz++) {
                for (BlockPos at : level.getChunk(cx, cz).getBlockEntitiesPos()) {
                    if (!level.getBlockState(at).is(Blocks.SPAWNER)) continue;
                    double d2 = from.distSqr(at);
                    if (d2 < bestD2) { bestD2 = d2; best = at.immutable(); }
                }
            }
        }
        return best;
    }

    /** What a spawner is set to spawn, or why that could not be read. Evidence, never a gate: a
     *  fortress has exactly one kind of spawner and the ladder is allowed to know that. */
    private static String spawnerMob(ServerLevel level, BlockPos at) {
        try {
            if (!(level.getBlockEntity(at) instanceof SpawnerBlockEntity spawner)) return "不是刷怪笼";
            var display = spawner.getSpawner().getOrCreateDisplayEntity(level, at);
            if (display == null) return "读不出刷什么";
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(display.getType());
            return key == null ? "?" : key.toString();
        } catch (RuntimeException | LinkageError e) {
            return "UNREADABLE(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static List<Blaze> blazesNear(ServerLevel level, BlockPos centre) {
        return level.getEntitiesOfClass(Blaze.class, box(centre, BLAZE_SEARCH));
    }

    private static EnderMan nearestEnderman(ServerLevel level, BlockPos from) {
        EnderMan best = null;
        double bestD2 = Double.MAX_VALUE;
        for (EnderMan man : level.getEntitiesOfClass(EnderMan.class, box(from, ENDERMAN_SEARCH))) {
            if (!man.isAlive()) continue;
            double d2 = man.distanceToSqr(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
            if (d2 < bestD2) { bestD2 = d2; best = man; }
        }
        return best;
    }

    private static AABB box(BlockPos centre, double r) {
        return new AABB(centre.getX() - r, centre.getY() - r, centre.getZ() - r,
                centre.getX() + r + 1, centre.getY() + r + 1, centre.getZ() + r + 1);
    }

    private static String biomeAt(JourneyRig rig, BlockPos at) {
        return rig.player().serverLevel().getBiome(at).unwrapKey()
                .map(k -> k.location().toString()).orElse("?");
    }

    // =====================================================================================
    // Handling the body.
    // =====================================================================================

    /**
     * Put a block in the cell, clicking against whichever neighbour is solid.
     *
     * <p>Deliberately NOT {@code avatar().place(worldView, cell)}, which does the same search
     * through the driver's {@code WorldView} — the very view {@link #theViewMatchesTheWorld} shows
     * belongs to another dimension. This asks the level the body is standing in, so the room does
     * not inherit the pathfinder's problem.
     *
     * <p>Goes through {@code placeOn} → {@code gameMode.useItemOn}, which is the path a right click
     * takes: the item is consumed out of the real inventory and the block lands with its real
     * neighbour updates. Nothing here is a {@code setBlock}.
     */
    private static boolean placeAt(JourneyRig rig, ServerLevel level, BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos against = cell.relative(d);
            if (!level.getBlockState(against).blocksMotion()) continue;
            rig.body().avatar().placeOn(against, d.getOpposite());
            if (level.getBlockState(cell).blocksMotion()) return true;
        }
        return false;
    }

    /**
     * Whichever wall material the body has most of, or null when it has none.
     *
     * <p>Most-of rather than first-in-a-list, and re-read per cell rather than once: the room is
     * built out of whatever the shaft, the portal and the quarry left behind, and a run that
     * committed to one id would stop building with two other stacks still in the bag.
     */
    private static String placeableBlock(JourneyRig rig) {
        String best = null;
        int most = 0;
        for (String id : WALL_BLOCKS) {
            int n = rig.carrying(id);
            if (n > most) { most = n; best = id; }
        }
        return best;
    }

    private static int placeableCount(JourneyRig rig) {
        int total = 0;
        for (String id : WALL_BLOCKS) total += rig.carrying(id);
        return total;
    }

    /**
     * The nearest thing worth mining for wall material, avoiding the room's own shell.
     *
     * <p>Whitelisted rather than "anything solid", because the neighbourhood of a fortress spawner
     * includes lava, magma, the bridge the body is standing on and the spawner itself, and a quarry
     * that took the nearest solid block would eventually take one of those.
     */
    private static BlockPos quarryCell(JourneyRig rig, Set<BlockPos> keepOut) {
        ServerLevel level = rig.player().serverLevel();
        BlockPos body = rig.player().blockPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dx = -QUARRY_RADIUS; dx <= QUARRY_RADIUS; dx++) {
            for (int dy = -QUARRY_DEPTH; dy <= QUARRY_DEPTH; dy++) {
                for (int dz = -QUARRY_RADIUS; dz <= QUARRY_RADIUS; dz++) {
                    BlockPos at = body.offset(dx, dy, dz);
                    if (keepOut.contains(at)) continue;
                    if (at.equals(body.below())) continue;          // the cell holding the body up
                    var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock());
                    if (id == null || !QUARRYABLE.contains(id.toString())) continue;
                    double d2 = body.distSqr(at);
                    if (d2 < bestD2) { bestD2 = d2; best = at.immutable(); }
                }
            }
        }
        return best;
    }

    /**
     * Put the best melee weapon this body owns in its hand, and say what that turned out to be.
     *
     * <p>{@code CombatProcess} swings whatever is SELECTED — it has no weapon picker of its own —
     * and the ladder arrives in the Nether holding whatever the last dig left in the slot. A fight
     * lost bare-handed and a fight lost to a broken combat loop read identically afterwards unless
     * the hand is on the record.
     */
    private static String holdBestWeapon(JourneyRig rig) {
        for (String id : WEAPONS) {
            if (rig.carrying(id) < 1) continue;
            if (rig.body().avatar().holdItem(item(id))) return id;
        }
        return "空手（包里一件武器都没有）";
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    // =====================================================================================
    // Walking — copied from the ladder's own leg, deliberately.
    // =====================================================================================

    /**
     * Walk to an XZ column, re-planning when a leg falls short.
     *
     * <p>A copy of the ladder's own {@code walkToColumn}, and a copy on purpose rather than a shared
     * helper: that one lives in a file this class must not edit, and a nether crossing needs the
     * same two behaviours it learned the hard way.
     *
     * <p><b>A retry that changes nothing is not a retry.</b> A body can be WEDGED — an earlier rung
     * spent two attempts and four minutes issuing ninety searches from one cell, every one of them
     * burning its whole node budget. Three identical questions get three identical answers, so an
     * attempt that ends where it began aims at the MIDPOINT first, which is a shorter question the
     * pathfinder may be able to answer, and then resumes.
     */
    private static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, Runnable onArrived, Runnable onStuck) {
        walkToColumn(rig, what, x, z, tolerance, budget, MAX_WALK_ATTEMPTS, onArrived, onStuck);
    }

    private static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, int left, Runnable onArrived, Runnable onStuck) {
        BlockPos before = rig.player().blockPosition();
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(x, z, tolerance))), budget, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - x, at.getZ() - z);
            int attempt = MAX_WALK_ATTEMPTS - left + 1;
            rig.evidence(what + ".arrivedDistance", Math.round(away));
            rig.evidence(what + ".walkAttempts", attempt);
            // The tolerance is the GOAL's own radius, so a leg that finished inside it arrived by
            // the only definition the walker was given. The ladder's copy of this compares against
            // a bare 5, which is right for its callers (they all pass tolerance 0) and would
            // declare a 24-block fortress goal unreached the moment it was reached.
            if (away <= tolerance + ARRIVED_WITHIN) { onArrived.run(); return; }
            rig.evidence(what + ".goto." + attempt,
                    "end=" + rig.body().botState().mc_goto.endReason
                            + " err=" + rig.body().botState().mc_goto.lastError);
            if (left <= 1) { onStuck.run(); return; }
            double moved = Math.hypot(at.getX() - before.getX(), at.getZ() - before.getZ());
            if (moved >= WEDGED_UNDER) {
                walkToColumn(rig, what, x, z, tolerance, budget, left - 1, onArrived, onStuck);
                return;
            }
            int mx = (at.getX() + x) / 2;
            int mz = (at.getZ() + z) / 2;
            rig.evidence(what + ".viaMidpoint", mx + "," + mz + "（卡在 " + at.toShortString() + "）");
            rig.settle(new IntentProcess(new Intent(new Goal.XZ(mx, mz, 3))), Math.max(600, budget / 2),
                    () -> walkToColumn(rig, what, x, z, tolerance, budget, left - 1, onArrived, onStuck));
        });
    }

    // =====================================================================================
    // Numbers.
    // =====================================================================================

    /** Item ids these rungs are about. Named rather than inlined because they are what is asserted. */
    private static final String BLAZE_ROD = "minecraft:blaze_rod";
    private static final String ENDER_PEARL = "minecraft:ender_pearl";

    /** How far the travelling chunk pin must see. Two chunks keeps a WALKING body's own chunk
     *  entity-ticking and is the wrong number for a rung that SEARCHES: entities exist only in
     *  loaded chunks, so a mob scan wider than the pin reports "there are none here" about ground
     *  nothing is holding. Four covers both mob searches below. */
    private static final int SEE_CHUNKS = 4;

    /** How close to the fortress column counts as arrived. Generous, because the landmark is the
     *  structure's locate position and a fortress is a hundred blocks of bridges around it. */
    private static final int FORTRESS_ARRIVE_WITHIN = 24;

    /** Ticks for ONE attempt at the crossing. The nether is 8:1, so this leg is short in nether
     *  blocks and long in terrain: lava seas, ravines, and ground the pathfinder has to break
     *  through. Multiplied by {@link #MAX_WALK_ATTEMPTS} plus the midpoint legs, this is most of
     *  the rung's registered budget — see {@link #rungs()} for that sum. */
    private static final int FORTRESS_WALK_TICKS = 48_000;

    /** How far a leg must move for the next attempt to be a different question — see the walk. */
    private static final int WEDGED_UNDER = 4;
    private static final int MAX_WALK_ATTEMPTS = 3;
    private static final int ARRIVED_WITHIN = 5;

    /** Chunks either side of the arrival to sweep for a spawner. Three is a 112-block square, which
     *  covers a fortress wing without pulling in a neighbour's chunks. */
    private static final int SPAWNER_SEARCH_CHUNKS = 3;
    private static final int SPAWNER_APPROACH_TICKS = 6_000;

    /** How close to the spawner the body has to get before the room is worth building. Five, because
     *  the room is sized on the spawner and a body that stopped further out would be walled OUT of
     *  its own trap — which reads as "the fight never started" and is really "the walk fell short". */
    private static final double SPAWNER_MUST_BE_WITHIN = 5.0;

    /**
     * The room: interior 7×7, three high, lid on top.
     *
     * <p>Three high is the number that matters. The arena measured a blaze under open sky hovering
     * six to eight blocks up against a melee reach of about three, so a ceiling at three caps its
     * altitude below the reach — the fight becomes winnable by geometry rather than by damage. Wider
     * would enclose more of vanilla's ±4 spawn scatter and costs quadratically more wall; seven
     * interior cells is the compromise the material budget allows.
     */
    private static final int ROOM_RADIUS = 4;
    private static final int ROOM_HEIGHT = 3;

    /** How many break-and-collect legs the quarry may spend. Each is a walk, a break and a settle,
     *  so this is a budget statement: a quarry sized to fill the whole shell would cost more ticks
     *  than the fight it exists to enable. */
    private static final int MAX_QUARRY_LEGS = 60;
    private static final int QUARRY_LEG_TICKS = 300;
    private static final int QUARRY_RADIUS = 5;
    private static final int QUARRY_DEPTH = 2;

    /** How long to wait for the spawner to turn. 2400 ticks is two minutes of game time against a
     *  spawner's 10–40 second cycle, so a run that spends it all has found a spawner that is not
     *  cycling at all — a different finding from one that is merely slow. */
    private static final int SPAWN_WAIT_TICKS = 2_400;
    private static final int RESPAWN_WAIT_TICKS = 1_200;

    /** How many blazes to fight, and how long one fight gets. The arena's closed-room fight took 40
     *  ticks; 1200 leaves room for the approach and for a room that turned out to be leaky. */
    private static final int BLAZE_FIGHTS = 8;
    private static final int BLAZE_FIGHT_TICKS = 1_200;
    private static final double BLAZE_SEARCH = 16.0;

    /** How many rods to stop at. One is what this rung claims; the eyes of ender above it will want
     *  more, and asking for them here would make this rung fail for the rung above's bill. */
    private static final int BLAZE_RODS_WANTED = 1;

    /** Six hunts and a bar of four — a MAJORITY. See {@link #enderPearl} for why not six of six. */
    private static final int ENDERMAN_HUNTS = 6;
    private static final int ENDERMEN_TO_KILL = 4;
    private static final double ENDERMAN_SEARCH = 48.0;
    private static final int ENDERMAN_WAIT_TICKS = 1_200;
    private static final int ENDERMAN_APPROACH_TICKS = 4_000;
    private static final int ENDERMAN_FIGHT_TICKS = 4_000;

    /** Everything the ladder might be carrying that a wall can be made of, plus everything the
     *  quarry below produces. Order does not matter — {@link #placeableBlock} takes the biggest
     *  stack, not the first entry. */
    private static final List<String> WALL_BLOCKS = List.of(
            "minecraft:netherrack", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:nether_bricks", "minecraft:blackstone", "minecraft:basalt",
            "minecraft:smooth_basalt", "minecraft:stone", "minecraft:dirt", "minecraft:tuff",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite");

    /** What the quarry is allowed to take. A whitelist, because the neighbourhood of a fortress
     *  spawner also contains lava, magma and the bridge the body is standing on. */
    private static final Set<String> QUARRYABLE = Set.of(
            "minecraft:netherrack", "minecraft:nether_bricks", "minecraft:blackstone",
            "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:cobblestone",
            "minecraft:stone", "minecraft:cobbled_deepslate", "minecraft:deepslate",
            "minecraft:tuff");

    /** Melee weapons, best first. A pickaxe is on the list on purpose: it is what this ladder
     *  actually owns by the time it reaches the Nether, and it still beats a bare hand. */
    private static final List<String> WEAPONS = List.of(
            "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword",
            "minecraft:golden_sword", "minecraft:stone_sword", "minecraft:wooden_sword",
            "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe",
            "minecraft:stone_axe", "minecraft:wooden_axe",
            "minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe");
}
