package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.moves.DiagonalAscend;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biomes;
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
        // 360 000, and the number is the arithmetic of the plan rather than caution: the crossing is
        // up to MAX_HOPS legs of HOP_TICKS (≈22k), then the approach (6k), up to sixty quarry legs
        // (18k), the wait for the spawner to turn (2.4k) and eight fights (9.6k). A budget sized for
        // one clean walk would turn "the fortress is far" into a timeout, which is the wrong
        // sentence about the right world. The crossing's share fell by an order of magnitude when it
        // stopped being three 48 000-tick attempts at one distant goal — see crossToColumn; what the
        // headroom now buys is the fight, which is what this rung actually claims.
        out.add(rung("wd.journey14BlazeRod", JourneyStage.BLAZE_ROD, 360_000,
                JourneyNetherRungs::blazeRod));
        // 120 000 still, and now it is the arithmetic rather than the absence of one. The old note
        // said "no build, and the walk is to whatever enderman is already loaded rather than to a
        // landmark", which is exactly what was wrong with the rung: the walk is now to a landmark,
        // up to MAX_HOPS legs of HOP_TICKS (≈22k), then
        // six rounds of approach-and-fight (≈48k), then up to six dry waits (≈7k), which is 77k.
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
        dontCutCornersOverLava(rig);
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

        // HORIZONTAL distance, and the "2D" is the whole point. The landmark comes from a structure
        // locate, which reports y=0 — it is an XZ answer wearing a BlockPos. Measuring to it in 3D
        // folds that fake y into the number and reports a crossing longer than the one the body will
        // walk. The goal below is a `Goal.XZ`, whose `ignoresY()` is true and whose `reached()` and
        // `estimate()` both use dx/dz only, so the walk never had a y to reach in the first place;
        // only this evidence line was ever wrong. Resolving a real standable y here would not change
        // the walk at all, and would cost a block read 390 blocks away in an unloaded chunk.
        int away = (int) Math.round(Math.hypot(here.getX() - fortress.getX(), here.getZ() - fortress.getZ()));
        rig.evidence("fortress.at", fortress.toShortString()
                + "（距身体 " + away + " 格水平；地标的 y=" + fortress.getY() + " 是 locate 的占位，不是可站立高度）");
        rig.attempting("走到要塞 " + fortress.toShortString() + "（" + away + " 格）");
        crossToColumn(rig, "fortress", fortress.getX(), fortress.getZ(), FORTRESS_ARRIVE_WITHIN,
                HOP_TICKS,
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
        // Re-held every round, not once. Anything that walks or digs calls `selectTool`, which
        // swaps the best TOOL for the block into the selected slot — so the hand a fight starts
        // with is whatever the last approach or quarry leg left there, and a fight lost bare-handed
        // reads exactly like a fight lost to a broken combat loop.
        final String weapon = holdBestWeapon(rig);
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
                    + " tick，手里 " + weapon + "，最高离地 " + String.format(Locale.ROOT, "%.1f",
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
     * <p><b>And it now walks to one.</b> That paragraph was the plan from the first draft and the
     * rung never carried it out: it hunted from wherever the rung below stopped, which is a fortress,
     * which is {@code nether_wastes}. The first run this rung ever executed reported
     * {@code enderman.found=0/6} with {@code hunt.biome=minecraft:nether_wastes} — and an enderman is
     * one weight unit out of that biome's ~170, against a warped forest whose monster list is
     * essentially endermen alone, four to a pack. Standing in the wrong biome is not a slow hunt, it
     * is a different experiment.
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
        dontCutCornersOverLava(rig);
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
        rig.evidence("hunt.census", census(nether, rig.player()));

        // found/killed, carried in an array because the hunt is a chain of continuations and a
        // local cannot survive one.
        walkToEndermanGround(rig, here, () ->
                huntOne(ctx, rig, ENDERMAN_HUNTS, new int[]{0, 0}, new StringBuilder()));
    }

    /**
     * Walk INTO a warped forest before hunting, when the body is not already standing deep in one.
     *
     * <p>Asked of the generator rather than of a baked landmark, for the same reason the fortress is
     * ({@link #fortressLandmark}): the recon scene never visits this dimension, so there is no
     * earlier value for a later run to disagree with. The sampler answers from climate noise without
     * generating a chunk, so the cost is a few thousand samples on the tick thread and it is
     * measured into the evidence either way.
     *
     * <h2>Why the aim is a SHARE and not the nearest sample</h2>
     *
     * The first version of this walk asked {@code findClosestBiome3d} and walked at the answer. That
     * call returns the CLOSEST matching cell, which is by construction a point on the biome's
     * boundary — and the run that carried it out arrived and reported
     * {@code warped.arrivedBiome = minecraft:nether_wastes（停在 130, 41, -229）} against a sample
     * point at {@code 136, 41, -233}. Seven blocks off a boundary sample is outside the biome about
     * half the time, and {@link #WARPED_ARRIVE_WITHIN} is wider than that. <b>"Walked to it" and
     * "standing in it" were never the same claim</b>, and only the second one is what this rung's
     * design rests on.
     *
     * <p>What replaces it is the quantity vanilla actually consults. A spawn attempt picks a random
     * cell in a chunk — {@code NaturalSpawner.getRandomPosWithin} draws y uniformly from the floor to
     * the surface — and then reads {@code level.getBiome(pos)} at that cell to choose the mob list.
     * Nether biomes are three-dimensional, so the deciding quantity is not "which biome is the block
     * under the boots" but <b>what fraction of the spawnable VOLUME around the body is warped
     * forest</b>. Warped forest's monster list is endermen alone; {@code nether_wastes} is
     * zombified piglins at weight 100 against an enderman's 1. So {@link WarpedGrid} samples that
     * fraction on a coarse grid and the walk aims at the cell where it is highest — the interior,
     * not the rim — and the same number is printed again from wherever the body ends up.
     *
     * <p>The radius that matters for the aim is {@link #ENDERMAN_SEARCH}, because a pearl needs an
     * enderman the hunt can SEE, and the hunt looks 48 blocks. The wider {@link #ENDERMAN_CENSUS}
     * share is reported beside it because that is the one that speaks to the CAP: the monster cap is
     * shared by the whole level, so a body whose 128-block spawn window is mostly wastes has its 70
     * slots filled by piglins however warped the ground under its own feet is.
     *
     * <p><b>Not finding one is not a failure, and neither is not reaching one.</b> Endermen do spawn
     * in {@code nether_wastes}; the biome only changes the rate. A rung that failed here would be
     * reporting "the walk fell short" under the name of "the pearls could not be got", which is the
     * wrong sentence about the right world — so both give up by name and hunt where the body stands.
     * The bounded radius is part of that: a forest further away than the walk budget can carry is a
     * landmark this rung cannot use, and searching further would only buy a longer timeout.
     */
    private static void walkToEndermanGround(JourneyRig rig, BlockPos here, Runnable then) {
        ServerLevel nether = rig.player().serverLevel();
        long startedNs = System.nanoTime();
        WarpedGrid survey = new WarpedGrid(nether, here, WARPED_SEARCH_RADIUS,
                (int) ENDERMAN_SEARCH, SURVEY_STEP);
        BlockPos aim = survey.densest();
        long ms = (System.nanoTime() - startedNs) / 1_000_000L;
        if (aim == null) {
            // NAME WHAT WAS SEEN, not what was wanted. The first version of this row said "not one
            // warped column was sampled within 256" whenever no column QUALIFIED, and the run it was
            // written for printed exactly that over a survey that had sampled the seed's nearest
            // warped forest — 272 blocks out, outside the candidate margin. A row that reports an
            // empty world when the world was merely out of reach sends the next round at the biome
            // source. So the miss says how far the nearest sampled warped column actually was.
            rig.evidence("warped.survey", "以 " + here.toShortString() + " 为心 " + WARPED_SEARCH_RADIUS
                    + " 格内没有一处可以走的 " + WARPED + "：" + survey.nearestWarped()
                    + "（" + survey.howSampled() + "，找了 " + ms
                    + " ms）—— 就地猎，nether_wastes 也刷末影人，只是稀");
            rig.evidence("warped.standing", standingIn(nether, here));
            then.run();
            return;
        }
        double aimShare = survey.shareAround(aim, (int) ENDERMAN_SEARCH);
        double hereShare = survey.shareAround(here, (int) ENDERMAN_SEARCH);
        // The y is deliberately not resolved — same reading as the fortress landmark one rung below.
        // The grid says nothing about which y is standable and the goal below is a `Goal.XZ`, so
        // there was never a y to walk to and only this line could have been wrong about it.
        int away = (int) Math.round(Math.hypot(here.getX() - aim.getX(), here.getZ() - aim.getZ()));
        rig.evidence("warped.survey", "最密的一处在 " + aim.getX() + ", ?, " + aim.getZ() + "（距身体 "
                + away + " 格水平）：那里 " + (int) ENDERMAN_SEARCH + " 格内可刷体积疣林占 " + pct(aimShare)
                + "，身体现在这处只占 " + pct(hereShare) + "；" + survey.nearestWarped()
                + "（" + survey.howSampled() + "，找了 " + ms + " ms）");
        if (hereShare >= aimShare - SHARE_TIE) {
            rig.evidence("warped.already", "身体脚下这一带已经和最密的那处一样疣（" + pct(hereShare)
                    + " 对 " + pct(aimShare) + "），不用走");
            rig.evidence("warped.standing", standingIn(nether, here));
            then.run();
            return;
        }
        rig.attempting("走进疣林深处 " + aim.getX() + ", ?, " + aim.getZ() + "（" + away + " 格，那里 "
                + (int) ENDERMAN_SEARCH + " 格内疣林占 " + pct(aimShare) + "）再猎");
        crossToColumn(rig, "warped", aim.getX(), aim.getZ(), WARPED_ARRIVE_WITHIN, HOP_TICKS,
                () -> {
                    BlockPos stood = rig.player().blockPosition();
                    rig.evidence("warped.arrivedBiome", biomeAt(rig, stood)
                            + "（停在 " + stood.toShortString() + "）");
                    rig.evidence("warped.standing", standingIn(nether, stood));
                    rig.evidence("warped.census", census(nether, rig.player()));
                    then.run();
                },
                () -> {
                    BlockPos stood = rig.player().blockPosition();
                    rig.evidence("warped.notReached", "走不到 " + aim.getX() + ", ?, " + aim.getZ()
                            + "：停在 " + stood.toShortString() + "，就地猎 —— "
                            + "这一行说的是走位，不是这一级的成败");
                    rig.evidence("warped.standing", standingIn(nether, stood));
                    then.run();
                });
    }

    /**
     * Where the body is standing, in the two numbers that decide whether it can hunt there.
     *
     * <p>Three rows, and each answers a different question a bare {@code enderman.found=0/6} cannot:
     * the biome under the boots says whether the walk ended inside the forest at all; the
     * {@link #ENDERMAN_SEARCH} share says whether the volume the hunt can SEE spawns endermen; the
     * {@link #ENDERMAN_CENSUS} share says whether the level's shared monster cap is going to be
     * filled by this neighbourhood's piglins before an enderman gets a slot.
     *
     * <p>They can disagree, and the disagreement is the finding. A body one block inside the rim
     * reads {@code warped_forest} underfoot with a 10% share at 48 — which is a hunt that will come
     * home empty for a reason that has nothing to do with combat.
     */
    private static String standingIn(ServerLevel level, BlockPos at) {
        WarpedGrid around = new WarpedGrid(level, at, 0, (int) ENDERMAN_CENSUS, SURVEY_STEP);
        return "脚下 " + biomeName(level, at) + "；" + (int) ENDERMAN_SEARCH + " 格内可刷体积疣林占 "
                + pct(around.shareAround(at, (int) ENDERMAN_SEARCH)) + "（猎的半径）、"
                + (int) ENDERMAN_CENSUS + " 格内占 " + pct(around.shareAround(at, (int) ENDERMAN_CENSUS))
                + "（刷怪窗口，决定上限被谁占）；" + around.howSampled();
    }

    private static String pct(double share) {
        return Math.round(share * 100) + "%";
    }

    /**
     * How much of the volume a mob could spawn in is warped forest, on a coarse grid.
     *
     * <p>Sampled through {@code ServerLevel.getUncachedNoiseBiome}, which asks the biome source
     * directly and <b>loads no chunk</b> — the same call {@code findClosestBiome3d} makes underneath.
     * A whole 256-radius survey is a few thousand climate samples and lands in single-digit
     * milliseconds on the tick thread, which is what makes it affordable to ask about the interior of
     * a biome rather than about its nearest edge.
     *
     * <p><b>Why a column of y slices and not one reading.</b> Nether biomes are three-dimensional and
     * {@code NaturalSpawner.getRandomPosWithin} draws its y uniformly from the build floor to the
     * surface, so the mob list a chunk rolls is decided at a height nobody chose. Sampling the column
     * at {@link #SPAWN_SLICES} approximates that draw; reading a single y would answer a question
     * vanilla never asks.
     *
     * <p><b>The grid is bigger than the ground it ranks, and that is the point.</b> A share is an
     * average over a neighbourhood, so a column scored with half its neighbourhood missing scores as
     * though the missing half were warped — the rim would outrank the interior. Sampling
     * {@code candidateRadius + neighbourhoodRadius} and ranking only the inner
     * {@code candidateRadius} is what makes every candidate's number mean the same thing. The first
     * version instead RANKED the inner region and sampled nothing beyond it, which silently made the
     * outer 48 blocks ineligible — and at this seed that is exactly where the only warped forest is.
     */
    private static final class WarpedGrid {
        private final int cx, cz, step, half, span, candidateCells;
        private final int[] warpedSlices;     // per column, 0..SPAWN_SLICES.length

        WarpedGrid(ServerLevel level, BlockPos centre, int candidateRadius, int neighbourhoodRadius,
                   int step) {
            this.cx = centre.getX();
            this.cz = centre.getZ();
            this.step = step;
            this.candidateCells = candidateRadius / step;
            this.half = (candidateRadius + neighbourhoodRadius) / step;
            this.span = half * 2 + 1;
            this.warpedSlices = new int[span * span];
            for (int i = 0; i < span; i++) {
                int qx = QuartPos.fromBlock(cx + (i - half) * step);
                for (int j = 0; j < span; j++) {
                    int qz = QuartPos.fromBlock(cz + (j - half) * step);
                    int n = 0;
                    for (int y : SPAWN_SLICES) {
                        if (level.getUncachedNoiseBiome(qx, QuartPos.fromBlock(y), qz)
                                .is(Biomes.WARPED_FOREST)) n++;
                    }
                    warpedSlices[i * span + j] = n;
                }
            }
        }

        /** Fraction of the sampled volume within {@code radius} of a block position, 0..1. */
        double shareAround(BlockPos at, int radius) {
            return share(cell(at.getX() - cx), cell(at.getZ() - cz), radius);
        }

        private int cell(int offset) {
            return Math.max(0, Math.min(span - 1, half + Math.round((float) offset / step)));
        }

        private double share(int i, int j, int radius) {
            int reach = radius / step;
            int warped = 0, total = 0;
            for (int di = -reach; di <= reach; di++) {
                for (int dj = -reach; dj <= reach; dj++) {
                    if ((di * di + dj * dj) * step * step > radius * radius) continue;
                    int ii = i + di, jj = j + dj;
                    // Out of the survey: skipped, not counted as wastes. Callers either sit far
                    // enough from the edge that this cannot fire (a candidate keeps a whole
                    // neighbourhood inside the grid, by construction) or are asking about the grid
                    // they just built around themselves.
                    if (ii < 0 || jj < 0 || ii >= span || jj >= span) continue;
                    warped += warpedSlices[ii * span + jj];
                    total += SPAWN_SLICES.length;
                }
            }
            return total == 0 ? 0 : (double) warped / total;
        }

        /**
         * The candidate column with the densest warped volume around it, or null if none is warped.
         *
         * <p>Ties go to the NEAREST column. A plateau of equally good ground is the common case in a
         * large forest, and picking its far side would buy a longer crossing for nothing — and this
         * crossing is the part of the rung that has actually been watched fail.
         */
        BlockPos densest() {
            double best = 0;
            for (int i = half - candidateCells; i <= half + candidateCells; i++) {
                for (int j = half - candidateCells; j <= half + candidateCells; j++) {
                    if (warpedSlices[i * span + j] == 0) continue;
                    best = Math.max(best, share(i, j, (int) ENDERMAN_SEARCH));
                }
            }
            if (best <= 0) return null;
            BlockPos closest = null;
            long closestD2 = Long.MAX_VALUE;
            for (int i = half - candidateCells; i <= half + candidateCells; i++) {
                for (int j = half - candidateCells; j <= half + candidateCells; j++) {
                    if (warpedSlices[i * span + j] == 0) continue;
                    if (share(i, j, (int) ENDERMAN_SEARCH) < best - SHARE_TIE) continue;
                    long dx = (long) (i - half) * step, dz = (long) (j - half) * step;
                    long d2 = dx * dx + dz * dz;
                    if (d2 < closestD2) {
                        closestD2 = d2;
                        closest = new BlockPos(cx + (int) dx, 0, cz + (int) dz);
                    }
                }
            }
            return closest;
        }

        /**
         * The nearest warped column ANYWHERE in the sampled square, said in blocks.
         *
         * <p>This exists so a survey that found nothing worth walking to cannot be read as a survey
         * that found nothing. Those are different worlds — one wants a wider radius, the other wants
         * a different plan — and the row that could not tell them apart cost a run.
         */
        String nearestWarped() {
            BlockPos closest = null;
            long closestD2 = Long.MAX_VALUE;
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    if (warpedSlices[i * span + j] == 0) continue;
                    long dx = (long) (i - half) * step, dz = (long) (j - half) * step;
                    long d2 = dx * dx + dz * dz;
                    if (d2 < closestD2) {
                        closestD2 = d2;
                        closest = new BlockPos(cx + (int) dx, 0, cz + (int) dz);
                    }
                }
            }
            if (closest == null) {
                return "采样的 " + (half * step) + " 格见方里一柱 " + WARPED + " 都没有";
            }
            return "采到的最近一柱 " + WARPED + " 在 " + closest.getX() + ", ?, " + closest.getZ()
                    + "（" + (int) Math.round(Math.sqrt(closestD2)) + " 格）";
        }

        String howSampled() {
            // The candidate clause only when there ARE candidates: a grid built to measure ONE
            // point has none, and printing "0 格以内的柱才可以当目标" beside a standing measurement
            // reads as a truncated survey rather than as a survey that was not ranking anything.
            return "每 " + step + " 格一柱、每柱 " + SPAWN_SLICES.length + " 层高度，采样半径 "
                    + (half * step) + " 格"
                    + (candidateCells > 0 ? "、其中 " + (candidateCells * step) + " 格以内的柱可以当目标" : "")
                    + "，直接问生成器不装载区块";
        }
    }

    /**
     * What is actually alive around the body, at two radii and split into endermen and everything
     * else.
     *
     * <p><b>{@code enderman.found=0/6} on its own cannot name a cause, and it ends the search.</b>
     * Three different worlds print it: one where nothing spawns at all (a body outside
     * {@code level.players()}, a gamerule, a difficulty), one where plenty spawns and endermen are
     * merely rare (the wrong biome), and one where endermen exist but outside
     * {@code ENDERMAN_SEARCH} — vanilla spawns 24 to 128 blocks from a player and this rung looks 48.
     * They want a flag, a walk and a bigger box respectively.
     *
     * <p>The far count is honest about its own blind spot: entities exist only in loaded chunks, so
     * a zero at 128 could be a statement about the tickets rather than about the Nether. It used to
     * quote {@link #SEE_CHUNKS} for that — "this rung pins 4 chunks = 64 blocks" — which is a row
     * that names the wrong bound and would have ended a search in the wrong place. That pin is a
     * FLOOR, not a limit: a body that joined the server also holds its own view-distance tickets, and
     * the same run whose census claimed a 64-block horizon counted 106 monsters inside 128. So the
     * line asks instead, and prints the answer: is the chunk on the census's own rim loaded right
     * now.
     */
    private static String census(ServerLevel level, ServerPlayer body) {
        BlockPos from = body.blockPosition();
        int near = level.getEntitiesOfClass(EnderMan.class, box(from, ENDERMAN_SEARCH)).size();
        int far = level.getEntitiesOfClass(EnderMan.class, box(from, ENDERMAN_CENSUS)).size();
        List<Monster> mobs = level.getEntitiesOfClass(Monster.class, box(from, ENDERMAN_CENSUS));
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Monster m : mobs)
            byType.merge(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath(), 1, Integer::sum);
        boolean rimLoaded = level.getChunkSource().hasChunk(
                SectionPos.blockToSectionCoord(from.getX() + (int) ENDERMAN_CENSUS),
                SectionPos.blockToSectionCoord(from.getZ()));
        return "末影人 " + near + " 只在 " + (int) ENDERMAN_SEARCH + " 格内、" + far + " 只在 "
                + (int) ENDERMAN_CENSUS + " 格内；" + (int) ENDERMAN_CENSUS + " 格内怪物共 "
                + mobs.size() + " 只 " + byType
                + "（远处那个数只在已加载区块里算数：" + (int) ENDERMAN_CENSUS + " 格外沿那一格现在"
                + (rimLoaded ? "装着" : "没装 —— 这个数是被票截断的")
                + "，本级至少钉 " + SEE_CHUNKS + " 区块，进了 players() 的身体另有 view-distance 的票）；"
                + spawnGate(level, body);
    }

    /**
     * Whether vanilla would spawn ANYTHING where the body stands — its three gates, read one by one.
     *
     * <p>The census alone cannot say which world it is in. It counts what is alive; when that is
     * zero, "the biome is wrong", "the chunks are not ticking", "the game does not think a player is
     * here" and "the cap is already full elsewhere" all print the same number, and only the first of
     * them is about the biome row printed beside it. This asks {@code ServerChunkCache.tickChunks}'s
     * own conditions, in its order:
     *
     * <ol>
     *   <li>{@code level.isNaturalSpawningAllowed(chunk)} — the chunk is entity-ticking;</li>
     *   <li>{@code chunkMap.getPlayersCloseForSpawning(chunk)} — the public twin of the
     *       {@code anyPlayerCloseEnoughForSpawning} the loop actually calls. It is two tests: a live
     *       distance check against the player's real position, AND
     *       {@code distanceManager.hasPlayersNearby}, which reads a tracker fed <b>only</b> by
     *       {@code ChunkMap.updatePlayerStatus} (join / dimension change) and {@code ChunkMap.move}.
     *       Nothing else moves it — for a real player {@code move} is called by the movement-packet
     *       handler, once per packet;</li>
     *   <li>the category cap: global is {@code maxPerChunk × spawnableChunks / 289}, and mobs
     *       anywhere in the level count against it.</li>
     * </ol>
     *
     * <p>Hence the drift figure: the chunk the ChunkMap has on file for this body against the chunk
     * the body is standing in. It is a plain observation of two fields, and it is here because it is
     * the only one of the three inputs above that a WALK can change on its own.
     */
    private static String spawnGate(ServerLevel level, ServerPlayer body) {
        ChunkPos here = body.chunkPosition();
        ChunkPos tracked = body.getLastSectionPos().chunk();
        int drift = Math.max(Math.abs(tracked.x - here.x), Math.abs(tracked.z - here.z));
        ServerChunkCache source = level.getChunkSource();
        NaturalSpawner.SpawnState state = source.getLastSpawnState();
        String cap;
        if (state == null) {
            cap = "本层还没算过刷怪账";
        } else {
            int monsters = state.getMobCategoryCounts().getInt(MobCategory.MONSTER);
            int chunks = state.getSpawnableChunkCount();
            // 289 = 17², vanilla's NaturalSpawner.MAGIC_NUMBER: the cap is quoted per 17×17 chunks.
            int allowed = MobCategory.MONSTER.getMaxInstancesPerChunk() * chunks / 289;
            cap = "本层怪物 " + monsters + "/" + allowed + " 只（上限 = "
                    + MobCategory.MONSTER.getMaxInstancesPerChunk() + " × 可刷区块 " + chunks + " / 289）";
        }
        return "刷怪三闸：身体在区块 " + here + "，实体在跑=" + level.isNaturalSpawningAllowed(here)
                + "，ChunkMap 认为这一格近旁有 " + source.chunkMap.getPlayersCloseForSpawning(here).size()
                + " 个玩家（它记的身体在区块 " + tracked + "，差 " + drift
                + " 区块；那张表只认 " + SPAWN_WINDOW_CHUNKS
                + " 区块以内，而只有 join 和 ChunkMap.move 会更新它）；" + cap;
    }

    private static void huntOne(SceneContext ctx, JourneyRig rig, int roundsLeft,
                                int[] foundAndKilled, StringBuilder tally) {
        ServerLevel nether = rig.player().serverLevel();
        if (roundsLeft <= 0) { pearlVerdict(ctx, rig, foundAndKilled, tally); return; }

        final int round = ENDERMAN_HUNTS - roundsLeft + 1;
        EnderMan target = nearestEnderman(nether, rig.player().blockPosition());
        if (target == null) {
            int[] waited = {0};
            rig.await(() -> nearestEnderman(nether, rig.player().blockPosition()) != null
                            || ++waited[0] >= ENDERMAN_WAIT_TICKS,
                    ENDERMAN_WAIT_TICKS + 100, () -> {
                if (nearestEnderman(nether, rig.player().blockPosition()) == null) {
                    // A DRY SPELL COSTS A ROUND, NOT THE RUNG. This used to go straight to the
                    // verdict, so one quiet minute ended a hunt with 118 000 ticks of its budget
                    // unspent and printed a single `×没找到` where six rounds were promised — an
                    // enderman that wandered into range at minute two was never going to be met.
                    // Six waits is still bounded and still says "found nothing" if that is the world.
                    tally.append(tally.length() == 0 ? "" : ",").append("×没找到");
                    rig.evidence("hunt." + round + ".dry", "等了 " + ENDERMAN_WAIT_TICKS
                            + " tick 没等到；" + census(nether, rig.player()));
                    huntOne(ctx, rig, roundsLeft - 1, foundAndKilled, tally);
                } else {
                    huntOne(ctx, rig, roundsLeft, foundAndKilled, tally);
                }
            });
            return;
        }

        foundAndKilled[0]++;
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
            // Re-held after the approach: the walk may have swapped a pickaxe into the hand — see
            // the same note on the blaze fight.
            final String weapon = holdBestWeapon(rig);
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
                rig.evidence("hunt." + round,
                        (dead ? "打死" : "没打死") + "，用了 " + waited[0] + " tick，手里 " + weapon);
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
        BlockPos ended = rig.player().blockPosition();
        rig.evidence("hunt.endedIn", biomeAt(rig, ended) + "（" + ended.toShortString() + "）");
        rig.evidence("hunt.censusAfter", census(nether, rig.player()));
        if (found == 0) {
            // The census AND the ground go in the FAILURE, not only in the evidence. "No enderman
            // came" is the one sentence four different worlds print, and a reader who has to go
            // looking for the rows that separate them usually stops at the sentence. The share is
            // there because it is the only one of the four this rung can act on: a hunt that ended
            // on 90% warped ground and a hunt that ended on 5% want opposite next moves.
            ctx.fail("身边 " + ENDERMAN_SEARCH + " 格内一只末影人都没有，"
                    + ENDERMAN_HUNTS + " 轮都等了也没等到 —— " + census(nether, rig.player())
                    + "；站的地方：" + standingIn(nether, ended)
                    + "；" + whyNothingSpawns(nether));
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

    /**
     * The blazes near the spawner that are still ALIVE.
     *
     * <p>The filter is the whole method. A killed mob stays in the world for its twenty-tick death
     * animation, so the unfiltered query hands the next round the corpse of the previous one — and
     * every check downstream agrees it is dead: {@code !target.isAlive()} is true on the first tick,
     * the round books a kill and ends in 0 ticks. The first run to get this far reported
     * {@code blaze.killed=8 只} and {@code fight.2…8 = 打死，用了 0 tick} against ONE real fight of 54
     * ticks, with {@code rods.perKill=0,0,0,0,0,0,0,0} beside it. Eight kills and no drops reads as
     * the {@code killed_by_player} gate this rung was written to expect; one kill and no drops is a
     * blaze's ordinary 50/50. The rung could not tell those apart while it was counting corpses.
     */
    private static List<Blaze> blazesNear(ServerLevel level, BlockPos centre) {
        return level.getEntitiesOfClass(Blaze.class, box(centre, BLAZE_SEARCH), Blaze::isAlive);
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
        return biomeName(rig.player().serverLevel(), at);
    }

    private static String biomeName(ServerLevel level, BlockPos at) {
        return level.getBiome(at).unwrapKey().map(k -> k.location().toString()).orElse("?");
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
     * Walk to a far XZ column in BOUNDED HOPS along the straight line to it.
     *
     * <h2>Why not one goal</h2>
     *
     * Because a 397-block goal is not a question this pathfinder answers, and the run that proved it
     * had never been measured before — every earlier reading was a photograph of where the body
     * ended. Three attempts at one distant {@code Goal.XZ}, rehearsed 2026-08-16:
     *
     * <pre>
     * flight.1 = 走了 15/397 格 … 13/289 tick 身上没有计划 … no route progress (best dist=3622)
     * flight.2 = 走了 69/383 格 … 1203/1582 tick 身上没有计划 … no progress for 1200 ticks
     * flight.3 = 走了  0/314 格 … 1203/1203 tick 身上没有计划 … no progress for 1200 ticks
     * </pre>
     *
     * <p>and the server log carries <b>2402 {@code search-begin} lines from the one cell
     * {@code 66,43,67}</b> — one full A* budget per tick, for two solid minutes, every one of them
     * returning nothing the walker would adopt. The body was not stuck on terrain: it stood on
     * netherrack, dry, with air on three sides. It was stuck on the QUESTION. Two thirds of the
     * crossing's ticks went to a body that had no plan at all, which is the reading that separates
     * this from "the nether is hard terrain" and it did not exist until {@code JourneyFlight}
     * counted it.
     *
     * <p>So the crossing is cut into hops of {@link #NETHER_HOP} blocks. Each hop is a question the
     * search can finish, and each one re-aims from where the body actually is — which is also the
     * repair for the second half of that run: the old retry gave up its midpoint whenever the
     * ATTEMPT had moved more than four blocks, and attempt 2 moved 69 blocks and then stood still
     * for 1203 ticks, so attempt 3 re-asked the identical question from the identical cell and got
     * the identical answer. Measuring a whole attempt cannot detect a body that wedged at the end
     * of it; a hop is short enough to be judged on its own.
     *
     * <p><b>A hop that goes nowhere must change the question, not repeat it.</b> First by halving
     * the reach — a shorter question is a different one — and then by turning off the straight line,
     * because the thing a bee-line runs into in the Nether is usually a lava sea with ground either
     * side of it. Four such hops in a row ends the crossing with every reading attached.
     *
     * <p>What "goes nowhere" MEANS is {@link #PROGRESS_UNDER} — read it before touching this, because
     * the obvious answer is wrong twice over and has cost this crossing two rounds.
     */
    private static void crossToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                      int hopTicks, Runnable onArrived, Runnable onStuck) {
        BlockPos from = rig.player().blockPosition();
        recordBudget(rig, what, Math.hypot(x - from.getX(), z - from.getZ()), hopTicks);
        oneHop(rig, what, x, z, tolerance, hopTicks, new Crossing(), onArrived, onStuck);
    }

    /**
     * What this crossing would cost if every hop were a clean one — written down BEFORE the first
     * hop is walked.
     *
     * <p><b>Because「it ran out of budget」is the first thing a short crossing gets accused of, and
     * it has now been wrong once.</b> The run of 2026-08-19 stopped 294 blocks out after three hops
     * and read as a cap. It was not: the arithmetic below is 10 hops against a ceiling of
     * {@link #MAX_HOPS} = 24, and roughly 3 300 ticks against 24 × {@link #HOP_TICKS} = 21 600 and a
     * rung budget of 360 000. The crossing ended because the body was in lava and
     * {@link #hazardBlockingARetry} correctly refused to spend a fourth hop on it — a cause the hop
     * lines name and the numbers cannot. Recorded so that the NEXT reader of a short crossing starts
     * from「the budget is 2.4× what this needs, so read the death」rather than re-deriving it.
     *
     * <p>Net progress per hop is the reach minus the waypoint's own radius, and that is not a
     * shortfall: {@link #HOP_ARRIVE_WITHIN} is where a hop is allowed to stop, so a healthy hop of
     * 48 lands 43 further on by design. The run above measured exactly that, twice.
     */
    private static void recordBudget(JourneyRig rig, String what, double away, int hopTicks) {
        int perHop = NETHER_HOP - HOP_ARRIVE_WITHIN;
        int need = (int) Math.ceil(away / perHop);
        rig.evidence(what + ".budget", Math.round(away) + " 格 ÷ 每段净进 " + perHop + " 格（伸手 "
                + NETHER_HOP + " 减路点半径 " + HOP_ARRIVE_WITHIN + "，不是走不满）≈ " + need
                + " 段；上限 " + MAX_HOPS + " 段 × " + hopTicks + " tick = " + (MAX_HOPS * hopTicks)
                + " tick。段数和 tick 都不是这一趟的瓶颈（余量 "
                + String.format(Locale.ROOT, "%.1f", MAX_HOPS / (double) Math.max(1, need))
                + " 倍）—— 它要是半路停了，死因在 crossing 那一行，不在这里");
    }

    /**
     * Make the planner pay for a DIAGONAL ascent, so a nether route climbs squarely or not at all.
     *
     * <h2>The measurement this is</h2>
     *
     * The rehearsal that fell into lava for the fourth time finally said why, in one row:
     *
     * <pre>
     * 上一 tick：位置 (79.950, 41.0000, 81.963) 速度 (0.037, -0.078, -0.054) onGround=true 潜行=true
     *   vanilla 自己那一问（脚下 0.0784 格内有碰撞吗）=有（和 onGround 一致 —— 它没有迟一拍）
     *   实心接触面积 0.1180/0.36   支撑行 y=40 [79,40,81=netherrack(0.1180) …其余三格 air]
     *   这一 tick 速度 y=0.333（是起跳，不是走出去的）
     *   立足面 5×5：#####/#####/###!!/##!!!/#!!!!   （!=空的且下面有岩浆）
     * </pre>
     *
     * <p>Three things at once, and none of them is what the previous three rounds went looking for.
     * {@code onGround} was <b>not</b> lagging — vanilla's own ground question agreed with it on the
     * same tick. The body was <b>already sneaking</b>: the walker's lethal-edge brake had seen the
     * lava bay and pinned it. And it left the ground <b>by jumping</b> (+0.333 of upward velocity is
     * a 0.42 jump one tick old), from a cell where a third of one sole was on rock and the rest was
     * over an eleven-block drop into that bay. Vanilla's sneak pin clamps a body's WALK off a ledge;
     * it has never clamped a jump, and the plan's next edge was a {@code diagUp}.
     *
     * <h2>Why the planner and not the jump</h2>
     *
     * Because the move itself is the bet. A diagonal ascent launches ACROSS the open corner between
     * two shelves — here {@code 80,41,81}, which is the first {@code !} cell of the bay — and its
     * cost says nothing about what is under that corner. {@link DiagonalAscend} already carries the
     * knob for it, added when the same move proved "unmountable on steep terrain", and the note on
     * it names the alternative this buys: <i>route around via cardinal stepUp</i>. A cardinal one is
     * the same climb squared up, and it is the one the executor gates on being aligned and close
     * before it launches ({@code ascendJumpReady}); the diagonal has no such gate and never had.
     *
     * <p>A PRICE, not a ban, and that distinction is the reason this is safe on terrain nobody has
     * looked at: where a cardinal way up exists A* now takes it, and where the diagonal is the only
     * way up it is still legal, merely dear. A ban would turn "this shelf is awkward" into "there is
     * no route", 300 blocks from anywhere.
     *
     * <p>Restored with the rest of the config by {@link JourneyRig#generousPathfinding}'s pin, so it
     * is this rung's policy and not the suite's — the six gates never see it.
     */
    private static void dontCutCornersOverLava(JourneyRig rig) {
        BotConfig.pathfinderDiagAscendPenalty = DIAG_ASCEND_PENALTY;
        rig.evidence("crossing.diagAscendPenalty", BotConfig.pathfinderDiagAscendPenalty
                + "（对角上跳的加价；判断它有没有生效看 flight 行末尾的「走过的边」，不是看这一行）");
    }

    /** What a diagonal ascent costs on a nether rung, on top of its base 19. A cardinal way up is
     *  walk(10) + stepUp(15) = 25, so 100 makes A* spend four squared-up steps rather than one
     *  corner-cutting leap — and still take the leap when there is no other way up. */
    private static final double DIAG_ASCEND_PENALTY = 100;

    /**
     * The mobility envelope a nether crossing gets: everything except a LEAP.
     *
     * <p>Measured, twice, in two rehearsals of this rung. A crossing plans aerial moves over nether
     * terrain and the executor does not land where the plan says:
     *
     * <pre>
     * 计划下一格 50, 50, 49[fall4]     … 距身体 1.66 格 → 落到 51, 44, 52，坠 9 格
     * 计划下一格 16, 53, 22[parkour3]  … 距身体 2.90 格 → 落进岩浆 14, 29, 23，坠 24 格
     * </pre>
     *
     * <p>The second one ended the crossing fifteen blocks in. Both landing cells were real ground —
     * {@code 16,52,22=netherrack（撑得住）} — so neither plan was wrong about the world. What is
     * wrong is the BET: <b>a leap's cost does not include what is under the gap.</b> Over rock a
     * missed {@code parkour3} costs a few hearts; over a lava chasm it costs the run, and the stride
     * floor-guard is explicitly disarmed on a parkour tick ({@code guardParkourTick}) because a leap's
     * landing is supposed to be the plan.
     *
     * <p>So the crossing does not leap. It still bridges — {@code BridgePlace} is not a
     * {@code PARKOUR} move and the rung arrives carrying 128 blocks — which is what a player does at
     * a lava chasm, and it still walks, climbs and steps down.
     *
     * <p><b>This is not the general fix for a fall, and must not be read as one.</b> The rehearsal
     * after it fell again, on a plain {@code walk} edge to a cell 0.97 blocks away standing on
     * netherrack. What all four falls share is not the move: it is that the previous tick's whole
     * footprint was air while {@code onGround} still read true.
     *
     * <p>Scoped to the crossing hops on purpose. This is a statement about walking a kilometre over
     * lava, not about the driver: the approach to a spawner three blocks away has no chasm to leap
     * and no reason to lose a capability.
     */
    private static final CapabilityProfile NO_PARKOUR =
            new CapabilityProfile(EnumSet.of(Capability.PARKOUR));

    /** What a crossing carries from hop to hop. A chain of continuations cannot keep locals. */
    private static final class Crossing {
        int hop;                       // hops spent
        int wedged;                    // hops since the crossing last got closer than it had ever been
        int reach = NETHER_HOP;        // how far the next hop aims, halved after a wedge
        int turn;                      // degrees off the straight line, spent after halving fails
        double best = Double.MAX_VALUE; // the closest this crossing has ever been to the goal — the
                                        // bar a hop must beat. See PROGRESS_UNDER for why a ratchet.
        double best0 = Double.MAX_VALUE; // where the crossing started, kept so the pace row can say
                                        // what the WHOLE walk would cost at the measured rate.
        int falls;
        int ticks;                     // ticks, summed over hops — the denominator for noPlan
        int noPlan;                    // ticks, summed over hops — the crossing's headline reading
        String firstLava;
        String why = "";
        final List<String> lines = new ArrayList<>();
    }

    private static void oneHop(JourneyRig rig, String what, int x, int z, int tolerance,
                               int hopTicks, Crossing c, Runnable onArrived, Runnable onStuck) {
        BlockPos before = rig.player().blockPosition();
        double away = Math.hypot(x - before.getX(), z - before.getZ());
        if (c.hop == 0) { c.best = away; c.best0 = away; }   // the record starts wherever the crossing does
        if (away <= tolerance + ARRIVED_WITHIN) {
            recordCrossing(rig, what, c, away);
            onArrived.run();
            return;
        }
        if (c.hop >= MAX_HOPS) {
            c.why = "走完了 " + MAX_HOPS + " 段还没到（还差 " + Math.round(away) + " 格）";
            recordCrossing(rig, what, c, away);
            onStuck.run();
            return;
        }
        c.hop++;
        final int hop = c.hop;
        double bearing = Math.atan2(z - before.getZ(), x - before.getX()) + Math.toRadians(c.turn);
        double reach = Math.min(c.reach, away);
        // A hop that reaches the goal IS the goal, and must be judged by the caller's tolerance —
        // a fortress is 24 blocks of bridges around its locate position and a hop tolerance would
        // walk the body past it.
        boolean lastHop = c.turn == 0 && reach >= away - 0.5;
        int wx = (int) Math.round(before.getX() + Math.cos(bearing) * reach);
        int wz = (int) Math.round(before.getZ() + Math.sin(bearing) * reach);
        int hopTolerance = lastHop ? tolerance : HOP_ARRIVE_WITHIN;
        // The only reading of this crossing that is not a photograph of the wreckage. See
        // JourneyFlight: three different bugs all end with a body hanging in cave_air, and the
        // `around.N` line prints the same sentence for all three.
        JourneyFlight flight = JourneyFlight.watching(rig, before, wx, wz);
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(wx, wz, hopTolerance),
                        List.of(), NO_PARKOUR, List.of())), hopTicks,
                flight, () -> settleToGround(rig, what, hop, () -> {
            BlockPos at = rig.player().blockPosition();
            double left = Math.hypot(x - at.getX(), z - at.getZ());
            // THE quantity. Not how far the body moved — how much closer to the goal the crossing
            // has ever got. See PROGRESS_UNDER: displacement cannot see a shuttle, and per-hop net
            // progress cannot either, because a shuttle's two halves cancel one hop apart.
            double gained = c.best - left;
            c.falls += flight.fallCount();
            c.ticks += flight.ticks();
            c.noPlan += flight.noPlanTicks();
            if (flight.lavaLine() != null && c.firstLava == null)
                c.firstLava = "第 " + hop + " 段 " + flight.lavaLine();
            // 纪录/净进 are the two numbers the shuttle was invisible without: every one of those 21
            // hops printed a healthy 「走 44/48 格」, and only the pair (record, gain-against-record)
            // says the crossing was standing still. c.best is still the PRE-hop record here on purpose
            // — it is the bar this hop had to clear.
            c.lines.add("#" + hop + " " + before.toShortString() + "→" + wx + "," + wz
                    + (c.turn == 0 ? "" : "（偏 " + c.turn + "°）")
                    + " " + flight.brief() + "，还差 " + Math.round(left)
                    + "（纪录 " + Math.round(c.best) + "，净进 " + Math.round(gained) + "）");
            // Falls get their own rows whatever the hop's outcome. A crossing that ARRIVES after
            // dropping nine blocks into a canyon arrived by the goal's definition and is still the
            // finding — and a PASS prints no evidence, so this is the only place it can be read.
            List<String> fell = flight.falls();
            for (int i = 0; i < fell.size(); i++) rig.evidence(what + ".fell." + hop + "." + i, fell.get(i));
            // The physics under the body on the tick before each of those. A fall line names a cell
            // and a move; this names whether the body JUMPED off it, whether onGround agreed with
            // vanilla's own ground question, and how much of the sole was still on rock — the three
            // readings three rounds of this crossing each had to guess at.
            List<String> ground = flight.grounds();
            for (int i = 0; i < ground.size(); i++) rig.evidence(what + ".ground." + hop + "." + i, ground.get(i));

            // WALKING IS AN ORDER ABOUT THE GROUND. A body inside lava swims; it cannot carry one
            // out, so the next hop would be the retry-that-changes-nothing in its purest form.
            String hazard = hazardBlockingARetry(rig.player(), at);
            if (hazard != null) {
                c.why = "第 " + hop + " 段之后停手：" + hazard
                        + " —— 再走一段只会得到同样的答案，先要把身体从这里弄出来，那是另一件事";
                rig.evidence(what + ".flight." + hop, flight.report());
                rig.evidence(what + ".around." + hop, surroundings(rig, at));
                recordCrossing(rig, what, c, left);
                onStuck.run();
                return;
            }
            if (gained >= PROGRESS_UNDER) {
                c.best = left;
                c.wedged = 0;
                c.reach = NETHER_HOP;
                c.turn = 0;
                oneHop(rig, what, x, z, tolerance, hopTicks, c, onArrived, onStuck);
                return;
            }
            // The record is deliberately NOT lowered by a hop that gained less than the bar. A hop
            // that comes 2 blocks closer is a wedge, but the next hop still gets credit for those 2:
            // it needs PROGRESS_UNDER against the same record, so small gains accumulate instead of
            // each being re-owed. Only walking backwards is charged nothing.
            //
            // A HOP THAT WENT NOWHERE. Everything about it goes on the record — this is the state
            // the whole crossing used to die in, and the readings that name it (ticks with no plan,
            // the goto's own verdict, what is touching the body) are only worth having together.
            c.wedged++;
            rig.evidence(what + ".flight." + hop, flight.report());
            rig.evidence(what + ".goto." + hop, "end=" + rig.body().botState().mc_goto.endReason
                    + " err=" + rig.body().botState().mc_goto.lastError);
            rig.evidence(what + ".around." + hop, surroundings(rig, at));
            if (c.wedged >= MAX_WEDGED_HOPS) {
                // Says NOTHING about whether the body moved — it may have walked 200 blocks. What it
                // says is that four hops in a row failed to get the crossing closer than its own
                // record, which is the only sense of "stuck" that a shuttle cannot fake.
                c.why = "连着 " + c.wedged + " 段没比纪录（" + Math.round(c.best)
                        + " 格）更近（最后停在 " + at.toShortString()
                        + "，还差 " + Math.round(left) + " 格）";
                recordCrossing(rig, what, c, left);
                onStuck.run();
                return;
            }
            if (c.wedged == 1) c.reach = Math.max(HOP_MIN, NETHER_HOP / 2);
            else c.turn = c.turn <= 0 ? HOP_TURN : -HOP_TURN;
            rig.evidence(what + ".reaim." + hop, "下一段改问 " + c.reach + " 格、偏 " + c.turn
                    + "° —— 同一个问题问第二遍只会得到同一个答案");
            oneHop(rig, what, x, z, tolerance, hopTicks, c, onArrived, onStuck);
        }));
    }

    /**
     * Let the body finish falling before a hop is judged from where it is.
     *
     * <p><b>A leg's verdict is taken from a body at rest, or it is taken from a photograph of one
     * tick of a fall.</b> {@link #hazardBlockingARetry} is right that a walk order cannot act on a
     * falling body — its own note says why, "its position is not where the next plan will start
     * from" — and on 2026-08-20 it stopped a healthy crossing 141 blocks short with this:
     *
     * <pre>
     * 第 6 段之后停手：身体还在下坠（179, 43, 198，落速 -0.38 格/tick，脚下到实心 0 格）
     * </pre>
     *
     * <p>{@code 脚下到实心 0}: the body was a hair above netherrack and would have been standing on
     * it on the next tick. Eighteen of twenty-four hops and 16 200 hop ticks went unspent, against
     * 141 blocks that the same leg's own pace (11.4 tick/block) prices at ~1 600 ticks. The reading
     * was not wrong; it was taken too early.
     *
     * <p>So the wait goes HERE, wrapping the whole continuation, rather than into the verdict: the
     * verdict is also what decides the next hop's starting position, its distance and its progress,
     * and all four want the same settled body. Under {@link HoldStill}, because a landing allowance
     * that keeps the walk's impulse would walk the body off whatever it lands on.
     *
     * <p>Costs nothing on a hop that ends on the ground, which is nearly all of them — the predicate
     * is asked first and the settle is skipped outright.
     */
    private static void settleToGround(JourneyRig rig, String what, int hop, Runnable then) {
        if (!stillFalling(rig.player())) { then.run(); return; }
        rig.evidence(what + ".landing." + hop, "这一段结束时身体还在下坠（"
                + surroundings(rig, rig.player().blockPosition()) + "） —— 先给 " + LANDING_TICKS
                + " tick 落地余量，再判决");
        rig.settle(new HoldStill(LANDING_TICKS), LANDING_TICKS + 4, then);
    }

    /** Everything the crossing did, in three rows rather than one per hop. */
    private static void recordCrossing(JourneyRig rig, String what, Crossing c, double left) {
        rig.evidence(what + ".hops", c.lines.isEmpty() ? "一段都没走" : String.join(" | ", c.lines));
        // 无计划 is the headline, and it is the reading that made the hop crossing worth writing:
        // one distant goal spent 2406 of its ticks with nothing to steer at, so a crossing that
        // reports a big number here has NOT been fixed by being cut up, whatever its distance says.
        // 全程最近 is not the same as 还差, and the gap between them IS the finding when a crossing
        // shuttles: the run that named this ended 265 blocks out having once been 244 out.
        rig.evidence(what + ".crossing", c.hop + " 段，还差 " + Math.round(left) + " 格（全程最近 "
                + Math.round(Math.min(c.best, left)) + " 格），离地 "
                + c.falls + " 次，全程无计划 " + c.noPlan + " tick"
                + (c.firstLava == null ? "，没进过岩浆" : "，" + c.firstLava)
                + (c.why.isEmpty() ? "" : "；" + c.why));
        // The denominator 无计划 never had. 67 ticks with nothing to steer at is a re-planning
        // problem at 900 and a rounding error at 1517, and only the pair says which — so a future
        // reader deciding between「give it more budget」and「it cannot plan here」has the number in
        // front of them instead of a hop line to add up.
        int walked = (int) Math.round(Math.max(0, c.best0 - Math.min(c.best, left)));
        rig.evidence(what + ".pace", c.hop + " 段共 " + c.ticks + " tick，净走 " + walked + " 格"
                + (walked > 0 && c.ticks > 0
                    ? "（" + String.format(Locale.ROOT, "%.1f", c.ticks / (double) walked)
                      + " tick/格，照这个脚程走完全程要 "
                      + Math.round(c.best0 * c.ticks / (double) walked) + " tick）" : "")
                + "；其中无计划 " + c.noPlan + "/" + c.ticks + " tick = "
                + (c.ticks > 0 ? Math.round(100.0 * c.noPlan / c.ticks) : 0) + "%");
        rig.evidence(what + ".arrivedDistance", Math.round(left));
    }

    /**
     * The cells touching the body, printed for every attempt that did not arrive.
     *
     * <p><b>Two different failures read alike without this, and they want opposite fixes.</b> A
     * {@code goto} that ends {@code no path (expanded=1)} popped the start node and found not one
     * legal move out of it: the body is sealed in, and more walking budget, more attempts and a
     * nearer midpoint all change nothing. A {@code goto} that ends {@code no route progress after N
     * consecutive searches (best dist=…)} is the opposite — the search worked, repeatedly, and the
     * terrain beat it. The first is a hole the body dug or fell into; the second is a nether
     * crossing that is genuinely too hard. The goto evidence alone cannot tell them apart, which is
     * how one run reported both and read as a single flaky walk.
     *
     * <p>Read straight from the level rather than through the bot's world view on purpose: when the
     * question is "is the view lying about where the body is", the view is not the witness to ask.
     * The body's own chunk is loaded by definition, so this costs no chunk load.
     */
    private static String surroundings(JourneyRig rig, BlockPos at) {
        ServerLevel level = rig.player().serverLevel();
        ServerPlayer fp = rig.player();
        StringBuilder sb = new StringBuilder();
        sb.append("脚下=").append(blockName(level, at.below()))
          .append(" 身处=").append(blockName(level, at))
          .append(" 头顶=").append(blockName(level, at.above()));
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = at.relative(d);
            if (level.getBlockState(side).blocksMotion()) walls++;
            sb.append(' ').append(d.getName()).append('=').append(blockName(level, side));
        }
        // WHICH expanded=1. Four walls is one way to have no legal move out of the start node;
        // being submerged is another, and it looks like the opposite (0/4 walls). One run spent a
        // round misreading `脚下=lava … 0/4 面是墙` as "the body is entombed", because the line said
        // how many walls there were and never said the body was under the lava.
        if (fp.isInLava()) sb.append("（0/4 面是墙但身体泡在岩浆里 —— expanded=1 是这个原因）");
        else sb.append(walls == 4 ? "（四面封死 —— 这是 expanded=1 的样子）"
                : "（" + walls + "/4 面是墙）");
        // The three readings that separate "the terrain beat the search" from "the body is not on
        // any terrain". A plan that ends AIRBORNE OVER A CAVE is the open half of this rung's
        // diagnosis, and it is invisible in a line that only names blocks: the body has walked
        // itself off a ceiling and every later reading is about wherever it lands.
        //
        // NOT fallDistance. `ServerPlayer.checkFallDamage` — the override Entity.move() calls — is
        // an EMPTY method: the accumulating one is `doCheckFallDamage`, which runs only when a
        // movement packet arrives, and a FakePlayer sends none. So `fp.fallDistance` is 0 for this
        // body always, and the `坠=0.0` this line used to print was a diagnostic that answered
        // "not falling" about a body measured dropping 1.14 blocks in a single tick. The body's own
        // vertical velocity is the reading that survives having no client.
        sb.append(" onGround=").append(fp.onGround())
          .append(" 落速=").append(String.format(java.util.Locale.ROOT, "%.2f", fp.getDeltaMovement().y))
          .append(" 血=").append(Math.round(fp.getHealth()))
          .append(" 脚下到实心=").append(dropBelow(level, at));
        return sb.toString();
    }

    /** How far it is straight down to the first block that would hold the body, or {@code ">N"}
     *  when nothing does within {@link #DROP_PROBE}. A body reporting a drop of 8 has not stopped
     *  walking — it is still on its way to wherever the plan actually ends. */
    private static String dropBelow(ServerLevel level, BlockPos at) {
        for (int d = 1; d <= DROP_PROBE; d++) {
            BlockPos p = at.below(d);
            if (p.getY() < level.getMinBuildHeight()) return "虚空";
            if (level.getBlockState(p).blocksMotion()) return String.valueOf(d - 1);
        }
        return ">" + DROP_PROBE;
    }

    /** How far down the drop probe looks. Sixteen: a nether cave ceiling is rarely thicker than
     *  that above its floor, and a body more than sixteen blocks off the ground is in free fall
     *  whatever the exact number. */
    private static final int DROP_PROBE = 16;

    /**
     * Why another identical walk order would be pointless, or null when it would not be.
     *
     * <p>Deliberately narrow. This is not "is the body in trouble" — it is "is the body somewhere a
     * WALK cannot act on", which is a different and much smaller question: fluid the body is inside
     * (it swims, it does not walk), and a body still falling (its position is not where the next
     * plan will start from). Anything else — low health, a mob on it, awkward terrain — is a reason
     * a retry may fail, not a reason it cannot be attempted, and stopping on those would turn a
     * hard crossing into a rung that never tries twice.
     *
     * <p>Takes the BODY and not the rig, so {@code JourneyCrossingScenes} can put the same verdict
     * over a staged fall. A copy of these three clauses in an arena would be a scene measuring
     * itself.
     */
    static String hazardBlockingARetry(ServerPlayer fp, BlockPos at) {
        if (fp.isInLava())
            return "身体泡在岩浆里（" + at.toShortString() + "，血 " + Math.round(fp.getHealth()) + "）";
        if (fp.isInWater())
            return "身体泡在水里（" + at.toShortString() + "）";
        if (stillFalling(fp))
            return "身体还在下坠（" + at.toShortString() + "，落速 "
                    + String.format(java.util.Locale.ROOT, "%.2f", fp.getDeltaMovement().y)
                    + " 格/tick，脚下到实心 "
                    + dropBelow(fp.serverLevel(), at) + " 格）";
        return null;
    }

    /**
     * Whether the body is on its way down rather than standing somewhere.
     *
     * <p>One predicate, two readers: {@link #hazardBlockingARetry} refuses to judge a leg from a
     * body in this state, and {@link #settleToGround} is what gives it the chance to leave it. They
     * must not be able to disagree — a wait that stops one tick before the verdict starts is a wait
     * that does nothing, and it would look exactly like a wait that works.
     */
    static boolean stillFalling(ServerPlayer fp) {
        return !fp.onGround() && fp.getDeltaMovement().y < FALLING_OVER;
    }

    /**
     * Downward velocity past which the body counts as falling rather than stepping down.
     *
     * <p>This used to read {@code fallDistance > 2.0f}, and <b>that branch could never fire</b>:
     * {@code ServerPlayer.checkFallDamage} is an empty override, the accumulating
     * {@code doCheckFallDamage} runs only off a movement packet, and this body has no connection —
     * so the field is 0 through an eleven-block drop. The guard that this rung's whole "a retry that
     * changes nothing" note is about therefore only ever worked through its lava and water branches.
     *
     * <p>−0.3 blocks per tick is roughly four ticks of gravity, which is past any step-down and well
     * short of the −0.7 a three-block fall reaches. Measured on the crossing: 1.14 blocks in one
     * tick, against a {@code fallDistance} of 0.0 for the same fall.
     */
    private static final double FALLING_OVER = -0.3;

    /**
     * How long a leg is allowed to keep falling before its verdict is taken anyway.
     *
     * <p>Twenty-six ticks, and the number is arithmetic rather than a guess: vanilla gravity covers
     * {@code 23.4} blocks in 26 ticks, and {@code SurvivalMath.survivableFall(20) = 22} is the
     * deepest DRY drop this body takes at full health. So the allowance is「as long as the deepest
     * fall the body can walk away from」. Past it the fall is a genuine one — a chasm, or the void —
     * and {@link #hazardBlockingARetry} is right to stop the crossing on it.
     *
     * <p>Spent only by a hop that ends mid-air, which is rare: measured on the 2026-08-20 rehearsal,
     * one hop out of six, and that one needed a single tick. 26 ticks against a hop's own
     * {@link #HOP_TICKS} = 900 is under 3% even when it is spent in full.
     */
    static final int LANDING_TICKS = 26;

    /** A block's short id, so a surroundings line stays readable. */
    private static String blockName(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
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

    /**
     * How far one hop of a crossing aims, and how long it may take.
     *
     * <p><b>48 is the length of a question this pathfinder finishes.</b> The measurement is in
     * {@link #crossToColumn}: a 397-block goal produced 2402 searches from one cell and not one
     * adopted path, while the same run's healthy stretches covered 15 and 69 blocks between
     * re-plans. Four chunks is also what the rung already pins ({@link #SEE_CHUNKS}), so a hop never
     * aims at ground the run is not holding.
     *
     * <p>900 ticks is 45 seconds for a walk of 48 blocks, which is five times the ~270 ticks a
     * healthy hop costs. It is deliberately under the walker's own 1200-tick no-progress stall, so a
     * wedged hop is ended by THIS budget with the hop's readings attached rather than by a generic
     * stall verdict inside the walker.
     */
    private static final int NETHER_HOP = 48;
    private static final int HOP_TICKS = 900;

    /** The shortest a halved hop may get. Under a chunk, a hop stops being a different question
     *  from the one that just failed and starts being the same one asked slower. */
    private static final int HOP_MIN = 16;

    /** Degrees off the straight line a detour hop aims, once halving has failed. Sixty rather than
     *  ninety: the obstacle a bee-line meets in the Nether is a lava sea with ground either side,
     *  and a hop that turns square to the goal spends its whole reach going nowhere useful. */
    private static final int HOP_TURN = 60;

    /** How many hops a crossing may spend. Twenty-four covers the 397-block fortress leg (nine
     *  clean hops) with room for halved hops and detours, and bounds the crossing at
     *  24 × {@link #HOP_TICKS} — see {@link #rungs()} for how that adds up. */
    private static final int MAX_HOPS = 24;

    /** Hops that may pass without the crossing beating its own record before it gives up. Four,
     *  because the crossing has exactly four different questions to ask: the hop, the halved hop,
     *  and the halved hop turned each way. A fifth would be the first repeat, and a repeat is the
     *  thing this whole crossing was rewritten to stop doing. Note this counts hops since the
     *  RECORD moved, not hops the body stood still for — see {@link #PROGRESS_UNDER}. */
    private static final int MAX_WEDGED_HOPS = 4;

    /**
     * How much closer to the goal than the crossing has EVER been a hop must get, to count as a hop
     * rather than a wedge.
     *
     * <h2>This repo has now been caught by displacement twice, one level apart</h2>
     *
     * Both times the code measured how far the body MOVED and concluded it was therefore getting
     * somewhere. Written out together because the second one was not recognised as the same mistake:
     *
     * <ul>
     *   <li><b>A whole attempt hides a wedge at its end.</b> The old walk retried only when the
     *       ATTEMPT had moved under four blocks. Attempt 2 moved 69 blocks and then stood still for
     *       1203 ticks — displacement over the attempt was 69, so the wedge was invisible and
     *       attempt 3 re-asked the identical question from the identical cell. Fixed by measuring a
     *       hop instead of an attempt ({@link #crossToColumn}).
     *   <li><b>Displacement cannot see a shuttle.</b> The fortress crossing of 2026-08-16 spent hops
     *       4–24 bouncing between {@code (87,77)} and {@code (110,116)}. Every hop displaced 40+
     *       blocks, so every hop passed the four-block test, so the counter reset every time and the
     *       ladder below (halve, then turn) never fired once. 21 hops, <b>5 blocks</b> of net
     *       progress, 18 339 ticks, and every line read healthy:
     *       <pre>
     *       #9  110,41,116→141,152 走 44/48 格 900t，还差 292   ← moved 44, and LOST 44
     *       #10  89,41, 77→119,114 走 42/48 格 900t，还差 251
     *       #11 108,41,114→139,150 走 43/48 格 900t，还差 293   ← moved 43, and LOST 42
     *       </pre>
     * </ul>
     *
     * <h2>Why a ratchet and not simply "net progress this hop"</h2>
     *
     * Because that fails too, and the archive says so. Replaying all 24 recorded hops under three
     * criteria, counting how many hops it takes to reach {@link #MAX_WEDGED_HOPS}:
     *
     * <pre>
     * moved >= 4          (what shipped)   never fires — 24 hops, ladder never used
     * (away - left) >= 4  (per hop)        never fires — the shuttle's gains alternate −48, +41,
     *                                      −49, +47, so a CONSECUTIVE counter resets every 2nd hop
     * (best - left) >= 4  (this)           fires at hop 11
     * </pre>
     *
     * A shuttle is exactly a sequence whose per-hop gains cancel one hop apart, so any criterion
     * with a memory of one hop is blind to it. Measuring against the closest the crossing has ever
     * been gives walking back and forth the credit it has earned, which is none.
     *
     * <p>(The replay is over the RECORDED trajectory. From the hop the ladder first fires, the body
     * goes somewhere else, so this says the criterion fires — it does not say the crossing arrives.)
     *
     * <p>Four blocks, same as the displacement bar it replaces: under a chunk-quarter of gain, a hop
     * has not bought a materially different vantage point on a 400-block crossing.
     */
    private static final int PROGRESS_UNDER = 4;

    /** Arrival slack. {@link #ARRIVED_WITHIN} is added to the caller's own tolerance for the final
     *  hop; {@link #HOP_ARRIVE_WITHIN} is a waypoint's own radius, and it is generous because a
     *  waypoint is a direction, not a destination — nothing is there. */
    private static final int ARRIVED_WITHIN = 5;
    private static final int HOP_ARRIVE_WITHIN = 6;

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

    /**
     * The radius of the window {@code DistanceManager.hasPlayersNearby} answers from, in chunks.
     *
     * <p>Copied rather than imported, because vanilla writes it as a bare {@code 8} — the field is
     * {@code new FixedPlayerDistanceChunkTracker(8)} and there is no constant to read. The metric is
     * CHEBYSHEV: {@code ChunkTracker.checkNeighborsAfterUpdate} propagates {@code level+1} into all
     * eight neighbours, so the window is a square and {@code max(|dx|,|dz|)} is the distance to
     * compare against it.
     */
    private static final int SPAWN_WINDOW_CHUNKS = 8;

    /** Six hunts and a bar of four — a MAJORITY. See {@link #enderPearl} for why not six of six. */
    private static final int ENDERMAN_HUNTS = 6;
    private static final int ENDERMEN_TO_KILL = 4;
    private static final double ENDERMAN_SEARCH = 48.0;
    private static final int ENDERMAN_WAIT_TICKS = 1_200;
    private static final int ENDERMAN_APPROACH_TICKS = 4_000;
    private static final int ENDERMAN_FIGHT_TICKS = 4_000;

    /** How far {@link #census} looks, as against how far the hunt looks. Deliberately WIDER than
     *  {@link #ENDERMAN_SEARCH} and deliberately not used to pick a target: vanilla spawns 24 to 128
     *  blocks from a player, so "there are none within 48" and "there are none at all" are different
     *  worlds and the hunt's own radius cannot tell them apart. Reading only, never a goal. */
    private static final double ENDERMAN_CENSUS = 128.0;

    /**
     * How far to ask the generator for a warped forest, and how finely.
     *
     * <p>A WALK budget wearing a search radius. Vanilla's own locate uses 6400 here; a forest at 2000
     * blocks would be a true answer this rung cannot use, so past this radius "none in range" is a
     * row that says hunt where you stand, which is a legal way to get pearls.
     *
     * <p><b>384 rather than 256, and the number is this seed's, not a guess.</b> The survey run on
     * 2026-08-16 sampled the whole 256-block square around the nether entry at {@code 8, 41, 7} and
     * the nearest warped column in it was {@code 136, ?, -233} — <b>272 blocks out</b>, on the rim.
     * At 256 this rung could therefore only ever report "hunt where you stand" at this seed, which is
     * what both of its runs did. The crossing has been watched carry a body 272 blocks to that same
     * forest once already, and {@link #MAX_HOPS} × {@link #NETHER_HOP} is 1152 blocks of reach, so
     * the extra 128 is inside what the walk is built for — it is the SURVEY that was the binding
     * constraint, not the legs.
     *
     * <p>The step is the survey's stride: 16 blocks is fine enough not to step over a forest and
     * coarse enough to keep the whole survey to a few tens of thousands of climate samples on the
     * tick thread — the 256-block version measured 3 ms. The biome source's own resolution is 4
     * blocks, so this samples one column in sixteen and every number it prints is an estimate of a
     * share, never a count of cells.
     */
    private static final int WARPED_SEARCH_RADIUS = 384;
    private static final int SURVEY_STEP = 16;

    /**
     * The heights a column is sampled at, and why there are eight of them.
     *
     * <p>{@code NaturalSpawner.getRandomPosWithin} draws y uniformly between the build floor and the
     * {@code WORLD_SURFACE} heightmap, which in the Nether is the bedrock roof — so a spawn attempt
     * is as likely to roll its mob list at y=8 as at y=120, and a survey that read one height would
     * be answering a question vanilla never asks. Eight slices 16 apart cover 0–128 evenly.
     */
    private static final int[] SPAWN_SLICES = {8, 24, 40, 56, 72, 88, 104, 120};

    /** How close two shares have to be to count as the same ground. Two points of an estimate made
     *  from one column in sixteen is noise, and spending a longer crossing to chase it is the kind of
     *  precision this survey does not have. */
    private static final double SHARE_TIE = 0.02;

    /** The biome this rung is walking into, spelled once. */
    private static final String WARPED = "minecraft:warped_forest";

    /** Arrival tolerance for the walk into the forest. Eight blocks, and it stopped mattering when
     *  the aim moved from the biome's nearest EDGE sample to its densest interior column — off by
     *  eight at the rim decides which biome the boots are in, off by eight in the middle of a forest
     *  does not. Kept where it was so the crossing is the same question rung 14 asks. */
    private static final int WARPED_ARRIVE_WITHIN = 8;

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
