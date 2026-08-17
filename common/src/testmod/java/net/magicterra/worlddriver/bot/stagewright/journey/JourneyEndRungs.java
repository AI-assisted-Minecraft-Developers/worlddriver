package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The last five rungs of the ladder: eyes, the stronghold, the portal, the End, the dragon.
 *
 * <p>Chapter C of {@link JourneyStage}, written to the same rules as everything below it — the seed
 * is known, every step is spelled out, and <b>nothing is staged</b>. No {@code setblock}, no
 * {@code give}, no teleport, no {@code level.setBlockAndUpdate}. What is under test is not whether
 * an agent could work the End out; it is whether worlddriver's API can EXECUTE a plan somebody else
 * already worked out. A red row here names a verb that could not do its job.
 *
 * <h2>Why these five live in their own file</h2>
 *
 * {@code WorldDriverJourneyScenes} is already at the source-budget ceiling, and these rungs share
 * almost nothing with the overworld ones: they walk a kilometre instead of a hundred blocks, they
 * hunt a structure instead of an ore, and two of them run in a dimension {@code ctx.level()} is not.
 * The handful of helpers they DO share — the walk-and-replan, the shaft, the pillar block — are
 * copied rather than imported, because those live as private statics over there and reaching into
 * them would couple two files that are being edited by different hands.
 *
 * <h2>What each rung is really asking</h2>
 *
 * <ul>
 *   <li><b>16 EYE_OF_ENDER</b> — a craft, and the first one on the ladder whose ingredients come
 *       from two rungs that are still unscripted. It therefore SKIPS rather than fails when the bag
 *       is empty: "nobody has written rung 14 yet" is not a finding about crafting.</li>
 *   <li><b>17 STRONGHOLD</b> — the longest walk in the game. 1745 blocks of real terrain from world
 *       spawn to {@link JourneyRoute#stronghold}, in legs, re-planning from wherever the walker
 *       actually stopped. Then a scan for the frame, then a shaft down to it.</li>
 *   <li><b>18 END_PORTAL</b> — twelve {@code useOn}-only interactions. {@code EnderEyeItem} overrides
 *       {@code useOn} and nothing else, so it must go through {@code Avatar.useBlock}; a body that
 *       reaches for {@code useItemInHand} gets {@code PASS} and a frame that never fills. That is the
 *       same trap the flint-and-steel set, and the mirror image of the bucket's.</li>
 *   <li><b>19 END</b> — the crossing, asserted on POSITION and not merely on dimension. An earlier
 *       bug delivered a body to the Nether 87 501 blocks off while a dimension check passed, because
 *       {@code ServerPlayer.changeDimension} hands the destination to {@code connection.teleport}
 *       and both loaders' fake players used to swallow it. The End's destination is the fixed
 *       {@code ServerLevel.END_SPAWN_POINT}, so the drift is exactly measurable.</li>
 *   <li><b>20 DRAGON</b> — crystals first, then the boss. {@code EnderDragon.hurt} refuses every
 *       direct hit: damage lands only through an {@code EnderDragonPart}, and only the head takes it
 *       undivided. And the fight has a prerequisite nothing below it needed — see
 *       {@link #noDragonHere}.</li>
 * </ul>
 *
 * <h2>Two places this deliberately does NOT take the shortcut the driver offers</h2>
 *
 * {@code Avatar.useBlock} builds its own {@code BlockHitResult}: no ray trace, no reach gate. So does
 * {@code Avatar.attackEntity} — it calls {@code Player.attack} straight through, and vanilla's
 * {@code Player.attack} never checks distance either. Either one would let this file fill a frame
 * from across the room or shatter an end crystal forty blocks overhead without climbing anything.
 * Both are gated here by the script instead: every eye is set after walking within
 * {@link #EYE_REACH} of its frame, and every swing is refused unless the target is within
 * {@link #MELEE_REACH}. A rung that used the ungated call would go green while proving nothing about
 * the climb, which is the shape of coverage this suite exists to refuse.
 */
public final class JourneyEndRungs {

    private JourneyEndRungs() {}

    /**
     * The five scenes, in ladder order, for the provider to register.
     *
     * <p>Order is registration order AND name order, the same contract the overworld rungs keep:
     * {@code wd.journey16…} through {@code wd.journey20…} sort into the order they must run in.
     * Each carries {@code withArena(false)} because the journey never enters its plot — it plays at
     * world spawn and then walks for kilometres — and {@code withRequired} straight off
     * {@link JourneyStage#gating()}, so promoting a rung stays one edit in one place.
     */
    public static List<Scene> rungs() {
        List<Scene> out = new ArrayList<>();
        // 20 000: three crafts on a 2x2 grid, no station, no walking. The budget is for the recipe
        // resolver's own retries, not for travel.
        out.add(stage("wd.journey16EyeOfEnder", JourneyStage.EYE_OF_ENDER, 20_000,
                JourneyEndRungs::eyeOfEnder));
        // 500 000, and the number is a trip bill rather than caution. The walk alone is 1745 blocks
        // over generated-on-arrival terrain; at MARCH_LEG_TICKS per leg the march can spend 200 000
        // before the shaft is even started, and a body that has to sidestep a wedge spends more.
        // A budget smaller than the plan turns "this seed's stronghold is a long way off" into a
        // timeout — the wrong sentence about the right world.
        out.add(stage("wd.journey17Stronghold", JourneyStage.STRONGHOLD, 500_000,
                JourneyEndRungs::stronghold));
        // 30 000: twelve short walks and twelve clicks, inside one room.
        out.add(stage("wd.journey18EndPortal", JourneyStage.END_PORTAL, 30_000,
                JourneyEndRungs::endPortal));
        // 20 000: a player's own portal wait is ~80 ticks; the rest is the couple of steps into it
        // and the retries for the cells the walker will not path onto.
        out.add(stage("wd.journey19End", JourneyStage.END, 20_000, JourneyEndRungs::end));
        // 500 000: bridging to the island, ten pillar climbs, and a duel whose tempo vanilla sets —
        // 20 ticks of invulnerability per hit, and a dragon that is only in reach while it perches.
        out.add(stage("wd.journey20Dragon", JourneyStage.DRAGON, 500_000, JourneyEndRungs::dragon));
        return List.copyOf(out);
    }

    /** One rung, at the required-ness {@link JourneyStage#gating()} declares. Mirrors the overworld
     *  rungs' own registration exactly — see that file for why the arena wait is dropped. */
    private static Scene stage(String name, JourneyStage rung, int budget, Consumer<SceneContext> body) {
        return Scene.of(name, budget, body).withRequired(rung.gating()).withArena(false);
    }

    // =====================================================================================
    // Numbers this file plays by.
    // =====================================================================================

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String THE_END = "minecraft:the_end";

    /** What a full frame costs. Vanilla pre-fills each of the twelve with probability 0.1, so a real
     *  room usually wants ten or eleven — this is the bill when it is generous with none of them. */
    private static final int EYES_A_PORTAL_COSTS = 12;

    /** What one blaze rod grinds into. */
    private static final int BLAZE_POWDER_PER_ROD = 2;

    /** How far a leg of the long march aims. Ninety-six blocks is three chunks and change: far
     *  enough that 1745 blocks is a couple of dozen legs, short enough that the pathfinder is being
     *  asked a question it can answer over terrain that is being generated as the body arrives. */
    private static final int MARCH_LEG_BLOCKS = 96;

    /** Ticks one leg may take. Ninety-six blocks at a walk is ~440 ticks; the rest is for the
     *  detours real ground imposes and for chunk generation stalling the search. */
    private static final int MARCH_LEG_TICKS = 4_000;

    /** How many legs before the march is called off. Forty-eight legs is 4600 blocks of progress
     *  against 1745 blocks of distance — the margin is for terrain that does not run straight. */
    private static final int MAX_MARCH_LEGS = 48;

    /** How close a leg has to land to count as having reached its waypoint. */
    private static final int MARCH_LEG_TOLERANCE = 6;

    /** How close to the baked stronghold coordinate the march stops. {@code /locate} answers with
     *  the START piece, and a stronghold sprawls a hundred blocks from it, so the scan that follows
     *  is what actually finds the room — arriving nearer than this buys nothing. */
    private static final int STRONGHOLD_ARRIVED_WITHIN = 24;

    /** How far a leg must move for the next one to be a different question. Four blocks: a body that
     *  shuffled inside its own cell has found no new vantage point, and asking the same pathfinder
     *  the same question from it spends a whole leg to learn nothing. */
    private static final int WEDGED_UNDER = 4;

    /** How far sideways a wedged march steps before trying again — perpendicular to the goal, so the
     *  next search is genuinely a different one rather than the same refusal from one cell over. */
    private static final int SIDESTEP_BLOCKS = 24;

    /** How many times a short walk re-plans before its caller calls the target unreachable. */
    private static final int MAX_WALK_ATTEMPTS = 3;

    /** Chunks either side of the baked stronghold that the frame scan loads and reads. Six is
     *  thirteen chunks square — 208 blocks — which comfortably contains a stronghold's own sprawl
     *  from its start piece. */
    private static final int ROOM_SCAN_CHUNKS = 6;

    /** How far from the frame centre the shaft may land, and still be "in the portal room". */
    private static final int ROOM_STAND_SEARCH = 7;

    /** …and how far it must stay CLEAR of that centre. The frames sit at |d| = 2 and the portal's own
     *  interior is inside them, with vanilla's lava pool below it. Three keeps the shaft outside both:
     *  a hole punched into the interior drops the body into lava, and a hole punched into a frame
     *  destroys the thing the next rung came for. */
    private static final int ROOM_STAND_MIN = 3;

    /** How near a frame the body has to end up for STRONGHOLD to claim the room. */
    private static final int REACHED_ROOM_WITHIN = 8;

    /** Cube radius rung 18 scans around the body for frames. The body is standing in the room, so
     *  this is a short look rather than a search. */
    private static final int FRAME_SEARCH = 16;

    /** How near a frame the body walks before setting its eye. {@code Avatar.useBlock} would accept
     *  the click from anywhere — see the class note on why this file refuses to let it. */
    private static final int EYE_REACH = 3;

    /** Cube radius rung 19 scans for the portal it is about to step into. */
    private static final int PORTAL_SEARCH = 12;

    /** How many portal cells the crossing tries before calling it. Vanilla opens nine; a walker that
     *  will not path onto one may still path onto another, and trying a second is what a player does. */
    private static final int MAX_PORTAL_STEPS = 4;

    /** Ticks for one attempt to walk into a portal cell. */
    private static final int PORTAL_WALK_TICKS = 1_200;

    /** Ticks to stand in it afterwards. A player's own portal timer is ~80; this is generous on
     *  purpose, because a run that spends it all has found a body the timer never STARTS for, which
     *  is a different finding from one it never fires for. */
    private static final int PORTAL_TRANSIT_TICKS = 600;

    /** How far the End arrival may sit from {@code ServerLevel.END_SPAWN_POINT}, <b>horizontally</b>.
     *  Sixteen blocks: the platform vanilla builds for arrivals is 5x5, so anything inside this is
     *  over it, and anything outside it is the swallowed-teleport bug wearing a correct dimension.
     *  <p><b>Horizontal alone is not arrival</b> — see {@link #END_ARRIVAL_FALL}. */
    private static final int END_ARRIVAL_DRIFT = 16;

    /**
     * How far the End arrival may sit from {@code END_SPAWN_POINT} <b>vertically</b>. Four, and the
     * tightness is the point.
     *
     * <p><b>Measured 2026-08-17, this rung passed with the body 4426 blocks below the platform.</b>
     * {@code arrived.at = 87,-4376,-1} against {@code END_SPAWN_POINT = 100,50,0}: the dimension was
     * right, {@link #END_ARRIVAL_DRIFT} is a horizontal quantity and 13 ≤ 16 satisfied it, and
     * nothing in the judgement looked at Y at all — so a body in free fall through the void reported
     * 「进入末地」. A criterion that can be fully satisfied by a run that achieved nothing is missing a
     * dimension of the thing it claims to measure.
     *
     * <p>Four is what vanilla's own geometry allows: {@code EndPlatformFeature.createEndPlatform}
     * lays obsidian at {@code y = 48} and air at 49–51, and {@code EndPortalBlock} puts a
     * {@code ServerPlayer} at {@code END_SPAWN_POINT.getBottomCenter().subtract(0, 1, 0)}, i.e. y=49
     * — one row below {@code END_SPAWN_POINT} before anything moves. So 48..52 is「on the platform」
     * and ±4 covers it with a row to spare, while still catching a 4426-block fall by three orders
     * of magnitude.
     */
    private static final int END_ARRIVAL_FALL = 4;

    /** Chunks pinned around the body in the End. The obsidian pillars stand ~43 blocks from the
     *  centre and the walking radius of 2 (32 blocks) cannot see them — an entity search over
     *  unloaded chunks does not report that it was blind, it reports that there are no crystals. */
    private static final int END_SIGHT_CHUNKS = 5;

    /** How far a search reaches for the dragon and its crystals. */
    private static final int DRAGON_SEARCH = 128;

    /** Melee reach the script holds itself to. Vanilla's own attack range is 3; four and a half is
     *  forgiving about where in its cell the body stopped without being a different game. */
    private static final double MELEE_REACH = 4.5;

    /** Ticks between swings. Twenty, because that is {@code LivingEntity.invulnerableTime} — a
     *  second swing inside the window is refused, or worse, silently reduced to the difference. It is
     *  also long enough for the attack-strength ticker to recharge any weapon this ladder owns.
     *  <b>The arena probes reset {@code invulnerableTime} to hit faster; a journey rung may not</b> —
     *  that is cheating the game, and the whole claim of this ladder is that it does not. */
    private static final int SWING_EVERY = 20;

    /** How many crystals the rung will work through. Vanilla builds ten pillars; a couple more is
     *  slack for a respawn arrangement rather than a real expectation. */
    private static final int MAX_CRYSTALS = 12;

    private static final int CRYSTAL_WALK_TICKS = 3_000;
    private static final int CRYSTAL_CLIMB_TICKS = 6_000;
    private static final int CRYSTAL_SWING_TICKS = 400;

    /** How long to wait for a dragon to exist before reporting that none does. */
    private static final int DRAGON_WAIT_TICKS = 600;

    /** How long the duel runs. Two hundred health at six damage a swing and one swing per twenty
     *  ticks is ~680 ticks of CONTACT; the rest of this number is the waiting, because a dragon that
     *  is flying is not a dragon that can be hit. */
    private static final int DUEL_TICKS = 200_000;

    /** Everything a shaft yields that a tower can stand on, commonest first — copied from the
     *  overworld rungs, where the lesson was learned that a tower asked for a block the body does not
     *  hold reports "out of blocks?" while the inventory is full. */
    private static final List<String> PILLAR_BLOCKS = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
            "minecraft:end_stone", "minecraft:tuff", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite");

    /** Weapons in the order a player would reach for them. Axes are in the list because this ladder
     *  can genuinely arrive at the End with an axe and no sword, and an axe hits harder than a fist. */
    private static final List<String> WEAPONS = List.of(
            "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword",
            "minecraft:stone_sword", "minecraft:wooden_sword",
            "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe",
            "minecraft:stone_axe", "minecraft:wooden_axe");

    // =====================================================================================
    // 16 — the eye of ender. A craft whose ingredients two unscripted rungs owe it.
    // =====================================================================================

    /**
     * Grind the rods, then marry powder to pearls.
     *
     * <p>Both recipes are shapeless two-ingredient jobs, so both fit the player's own 2x2 grid and
     * neither needs a crafting table — which matters, because the body arrives here from the Nether
     * where the ladder's table has been through several rungs of best-effort reclaim.
     *
     * <p><b>This rung skips rather than fails when the bag is empty, and that is the deliberate
     * part.</b> {@code BLAZE_ROD} and {@code ENDER_PEARL} are still {@code unscripted}, so on today's
     * ladder this rung is normally never reached at all — {@link JourneyRig#enter} records BLOCKED
     * below it. When it IS reached with nothing in hand, the honest report is "the rungs below owe
     * this one its materials", not "the driver cannot craft". A red row for somebody else's unwritten
     * work buries the row that would have said something.
     *
     * <p>The assertion is {@code >= 1}, not {@code >= 12}, and the shortfall is recorded beside it.
     * The claim this rung makes is that the CRAFT works; the claim that there are enough eyes for a
     * frame belongs to {@link #endPortal}, which is the rung that actually spends them and which can
     * name the exact number it was short.
     */
    private static void eyeOfEnder(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.EYE_OF_ENDER);
        rig.generousPathfinding();

        int rods = rig.carrying("minecraft:blaze_rod");
        int powder = rig.carrying("minecraft:blaze_powder");
        int pearls = rig.carrying("minecraft:ender_pearl");
        int already = rig.carrying("minecraft:ender_eye");
        rig.evidence("dimension", rig.dimension());
        rig.evidence("blaze_rod.before", rods);
        rig.evidence("blaze_powder.before", powder);
        rig.evidence("ender_pearl.before", pearls);
        rig.evidence("ender_eye.before", already);

        int powderCeiling = powder + rods * BLAZE_POWDER_PER_ROD;
        int couldMake = Math.min(pearls, powderCeiling);
        if (couldMake < 1 && already < 1) {
            rig.attempting("原料没送到：BLAZE_ROD / ENDER_PEARL 还没脚本化，这一级手上是空的");
            ctx.skip("MISSING_INPUTS: 末影之眼 = 烈焰粉 + 末影珍珠。当前 blaze_rod=" + rods
                    + " blaze_powder=" + powder + " ender_pearl=" + pearls
                    + " —— 缺的是上面两级的产出，不是合成这条路走不通");
            return;
        }

        int want = Math.max(0, EYES_A_PORTAL_COSTS - already);
        want = Math.min(want, couldMake);
        rig.evidence("ender_eye.want", want + "（一套门 " + EYES_A_PORTAL_COSTS + " 只，已有 " + already + "）");
        final int target = want;
        grindBlazePowder(rig, target, () -> craftTheEyes(ctx, rig, target));
    }

    /** Turn rods into powder, but only as much as the pearls can actually be married to — grinding
     *  the whole stock would burn rods the run may want for something else and prove nothing extra. */
    private static void grindBlazePowder(JourneyRig rig, int wantPowder, Runnable then) {
        if (wantPowder <= 0 || rig.carrying("minecraft:blaze_powder") >= wantPowder) {
            rig.evidence("blaze_powder.enough", rig.carrying("minecraft:blaze_powder"));
            then.run();
            return;
        }
        rig.attempting("把烈焰棒磨成烈焰粉（2×2 配方，不需要工作台）");
        rig.drive(new CraftProcess("minecraft:blaze_powder", wantPowder), 8_000, () -> {
            rig.evidence("blaze_powder.after", rig.carrying("minecraft:blaze_powder"));
            rig.evidence("blaze_powder.craftError", String.valueOf(rig.body().botState().craft.lastError));
            then.run();
        });
    }

    private static void craftTheEyes(SceneContext ctx, JourneyRig rig, int want) {
        if (want <= 0) { judgeTheEyes(ctx, rig); return; }
        rig.attempting("合成末影之眼：烈焰粉 + 末影珍珠");
        rig.drive(new CraftProcess("minecraft:ender_eye", want), 12_000, () -> {
            rig.evidence("ender_eye.craftError", String.valueOf(rig.body().botState().craft.lastError));
            judgeTheEyes(ctx, rig);
        });
    }

    private static void judgeTheEyes(SceneContext ctx, JourneyRig rig) {
        int eyes = rig.carrying("minecraft:ender_eye");
        int shortfall = Math.max(0, EYES_A_PORTAL_COSTS - eyes);
        rig.evidence("ender_eye", eyes);
        rig.evidence("blaze_powder.left", rig.carrying("minecraft:blaze_powder"));
        rig.evidence("ender_pearl.left", rig.carrying("minecraft:ender_pearl"));
        rig.evidence("ender_eye.shortfall", shortfall);
        ctx.expect(eyes).as("eyes of ender in the bag, crafted from blaze powder and ender pearls")
                .isAtLeast(1);
        rig.reach("末影之眼 ×" + eyes + (shortfall == 0 ? "（够一套门）"
                : "（一套门要 " + EYES_A_PORTAL_COSTS + " 只，还差 " + shortfall + "）"));
    }

    // =====================================================================================
    // 17 — the stronghold. The longest walk in the game, then a shaft into somebody else's rock.
    // =====================================================================================

    /**
     * Walk to {@link JourneyRoute#stronghold} and get down into the portal room.
     *
     * <p>No eyes are thrown. A player finds a stronghold by throwing an ender eye and following it,
     * which is a search; this ladder is scripted against a known seed, so the coordinate is simply
     * given — that is the whole premise, and it is what keeps a failure here attributable to the
     * DRIVER rather than to a search that guessed wrong. {@code /locate} put it 1745 blocks from
     * world spawn, which is the real subject of this rung: nothing below it has walked further than
     * a hundred.
     *
     * <p>Three legs, in order, and each has its own way of going wrong:
     *
     * <ol>
     *   <li><b>Get back to the overworld.</b> The rung above this one ends in the Nether, and there
     *       are no strongholds there. The way back is the portal the run lit itself.</li>
     *   <li><b>March.</b> In legs, re-planning from wherever the walker actually stopped —
     *       {@code IntentProcess} reports its goal reached for a partial path, so one drive can come
     *       back "done" with the body eighty blocks short. A leg that moves nothing is not repeated:
     *       it sidesteps first, because three identical questions get three identical answers.</li>
     *   <li><b>Find the room and sink a shaft to it.</b> The frames are scanned out of the loaded
     *       chunks rather than guessed at, and the shaft is deliberately aimed <em>beside</em> the
     *       frame ring — see {@link #ROOM_STAND_MIN}.</li>
     * </ol>
     */
    private static void stronghold(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.STRONGHOLD);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        rig.evidence("stronghold.baked", xyz(JourneyRoute.stronghold));
        rig.evidence("start.dimension", rig.dimension());
        rig.evidence("start.at", xyz(rig.player().blockPosition()));
        rig.evidence("ender_eye.carried", rig.carrying("minecraft:ender_eye"));
        rig.evidence("stronghold.away", Math.round(flatDistance(rig.player().blockPosition(),
                JourneyRoute.stronghold)) + " 格");

        backToTheOverworld(ctx, rig, () -> march(ctx, rig, 0));
    }

    /**
     * Walk back through the portal the run lit, if the body is not already home.
     *
     * <p>Deliberately not a search for a NEW portal and deliberately not a fresh cast: the run
     * already owns one, it is the thing {@code PORTAL_LIT} claimed, and stepping back through it is
     * the cheapest honest route. A body that cannot find it is a real finding — it means the rungs
     * in the Nether wandered further from the doorway than they can navigate back.
     */
    private static void backToTheOverworld(SceneContext ctx, JourneyRig rig, Runnable then) {
        if (OVERWORLD.equals(rig.dimension())) {
            rig.evidence("return.needed", false);
            then.run();
            return;
        }
        rig.evidence("return.needed", true);
        BlockPos portal = rig.nearestBlock("minecraft:nether_portal", 24);
        rig.evidence("return.portal", xyz(portal));
        if (portal == null) {
            ctx.fail("回不去主世界：身边 24 格内没有 nether_portal 方块（身体在 " + rig.dimension()
                    + " " + rig.player().blockPosition() + "）——要塞在主世界，这一级必须先走回去，"
                    + "而这个运行没有留下能走回去的门");
            return;
        }
        rig.attempting("走回自己点亮的那道门，回主世界");
        rig.settle(new IntentProcess(new Intent(new Goal.Block(portal))), 3_000,
                () -> waitFor(rig, () -> OVERWORLD.equals(rig.dimension()), 1_600, () -> {
                    rig.evidence("return.dimension", rig.dimension());
                    rig.evidence("return.at", xyz(rig.player().blockPosition()));
                    if (!OVERWORLD.equals(rig.dimension())) {
                        ctx.fail("站进门里也没回去：仍在 " + rig.dimension() + " "
                                + rig.player().blockPosition() + "（站的格子是 "
                                + blockAt(rig, rig.player().blockPosition()) + "）");
                        return;
                    }
                    then.run();
                }));
    }

    /**
     * One leg of the march, then the next, until the stronghold's column is underfoot.
     *
     * <p>Recursive rather than looped, and that is not a style choice: each leg is its own
     * {@code await} step, so the recursion queues a step and returns rather than nesting a stack.
     * The body has to actually walk between legs, and a loop inside one scene tick would plan
     * twenty-eight routes in a world that never advanced.
     */
    private static void march(SceneContext ctx, JourneyRig rig, int leg) {
        BlockPos goal = JourneyRoute.stronghold;
        BlockPos at = rig.player().blockPosition();
        double away = flatDistance(at, goal);
        rig.evidence("march." + leg, xyz(at) + " 距要塞 " + Math.round(away) + " 格");
        if (away <= STRONGHOLD_ARRIVED_WITHIN) {
            rig.evidence("march.legs", leg);
            surveyThePortalRoom(ctx, rig);
            return;
        }
        if (leg >= MAX_MARCH_LEGS) {
            ctx.fail("走不到要塞：" + MAX_MARCH_LEGS + " 段行军之后仍在 " + at + "，距 " + xyz(goal)
                    + " 还有 " + Math.round(away) + " 格（每段 " + MARCH_LEG_BLOCKS + " 格 / "
                    + MARCH_LEG_TICKS + " tick，逐段落点见 march.*）");
            return;
        }
        double f = Math.min(1.0, MARCH_LEG_BLOCKS / away);
        int wx = (int) Math.round(at.getX() + (goal.getX() - at.getX()) * f);
        int wz = (int) Math.round(at.getZ() + (goal.getZ() - at.getZ()) * f);
        rig.attempting("向要塞行军：第 " + leg + " 段，走向 " + wx + "," + wz);
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(wx, wz, MARCH_LEG_TOLERANCE))),
                MARCH_LEG_TICKS, () -> {
            BlockPos now = rig.player().blockPosition();
            if (flatDistance(at, now) >= WEDGED_UNDER) { march(ctx, rig, leg + 1); return; }
            sidestep(ctx, rig, leg, goal, now);
        });
    }

    /**
     * A leg that went nowhere does not simply repeat.
     *
     * <p>The re-plan assumes each attempt starts somewhere better, and usually it does — but a body
     * can also be WEDGED, and then three attempts are three identical searches with three identical
     * refusals, each burning a leg's whole budget. Stepping sideways, perpendicular to the goal,
     * asks the pathfinder a question it has not already answered. That is what a player does when a
     * route will not come, and it needs nothing from the engine.
     */
    private static void sidestep(SceneContext ctx, JourneyRig rig, int leg, BlockPos goal, BlockPos at) {
        double dx = goal.getX() - at.getX();
        double dz = goal.getZ() - at.getZ();
        double len = Math.max(1.0, Math.hypot(dx, dz));
        int sx = (int) Math.round(at.getX() - dz / len * SIDESTEP_BLOCKS);
        int sz = (int) Math.round(at.getZ() + dx / len * SIDESTEP_BLOCKS);
        rig.evidence("march." + leg + ".wedged", xyz(at) + " 一段没挪动，先横走到 " + sx + "," + sz
                + "（goto end=" + rig.body().botState().mc_goto.endReason
                + " err=" + rig.body().botState().mc_goto.lastError + "）");
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(sx, sz, 3))), MARCH_LEG_TICKS / 2,
                () -> march(ctx, rig, leg + 1));
    }

    /**
     * Find the twelve frames, pick a cell to land in, and dig down to it.
     *
     * <p>The scan is over CHUNKS rather than blocks, through {@code ChunkAccess.findBlocks}, which
     * asks each section's palette whether it could possibly contain the block before reading any of
     * it. The block-by-block alternative over the same volume is about ten million lookups in one
     * server tick; this is a few hundred palette probes and then a handful of sections.
     *
     * <p>It scans around the BAKED coordinate rather than around the body, because that is the claim
     * being tested — a run that walked to the right place and found no frame there has learned that
     * the constant is stale, and a scan centred on the body could not tell that apart from a march
     * that stopped somewhere else.
     */
    private static void surveyThePortalRoom(SceneContext ctx, JourneyRig rig) {
        ServerLevel level = levelOf(rig);
        rig.attempting("在要塞里找到传送门房间");
        List<BlockPos> frames = framesAround(level, JourneyRoute.stronghold, ROOM_SCAN_CHUNKS);
        rig.evidence("frames.found", frames.size());
        if (frames.isEmpty()) {
            ctx.fail("到了要塞坐标却扫不到末地传送门框架：以 " + xyz(JourneyRoute.stronghold) + " 为心、"
                    + ROOM_SCAN_CHUNKS + " 区块见方内 end_portal_frame = 0。要么烘死的坐标过期了"
                    + "（重跑 wd.journey01Recon 看 stronghold 一行），要么这颗种子的要塞不在这里");
            return;
        }
        BlockPos centre = centreOf(frames);
        int withEye = 0;
        for (BlockPos f : frames) if (hasEye(level, f)) withEye++;
        rig.evidence("frames.centre", xyz(centre));
        rig.evidence("frames.withEye", withEye + "/" + frames.size() + "（世界自带的，不是这次放的）");

        BlockPos stand = standingCellInTheRoom(level, centre);
        rig.evidence("room.standing", xyz(stand));
        if (stand == null) {
            ctx.fail("找到了框架（" + xyz(centre) + "）却没有能落脚的格子：房间里 " + ROOM_STAND_MIN + "–"
                    + ROOM_STAND_SEARCH + " 格范围内没有一格是「空、头顶空、脚下实心且不是流体」的");
            return;
        }
        digToTheRoom(ctx, rig, stand, centre);
    }

    private static void digToTheRoom(SceneContext ctx, JourneyRig rig, BlockPos stand, BlockPos centre) {
        ServerLevel level = levelOf(rig);
        int surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(stand.getX(), 0, stand.getZ())).getY();
        rig.evidence("shaft.top", stand.getX() + "," + surface + "," + stand.getZ()
                + " → 房间 y=" + stand.getY());
        walkToColumn(rig, "shaftTop", stand.getX(), stand.getZ(), 2, 12_000, MAX_WALK_ATTEMPTS, () -> {
            // The shaft must not be bridged over. A walker allowed to place while descending fills
            // in the hole it is standing in, which reads as "the block broke but the body did not
            // sink" — the same confusion the overworld shafts already had to disarm.
            BotConfig.allowPlace = false;
            int depth = Math.max(0, rig.player().blockPosition().getY() - stand.getY());
            int cap = depth * 4 + 40;
            rig.evidence("shaft.depth", depth + " 格，给 " + cap + " 次尝试");
            digDownTo(ctx, rig, stand.getY(), cap, cap, () -> {
                BotConfig.allowPlace = true;
                judgeTheRoom(ctx, rig, centre);
            });
        }, () -> ctx.fail("走不到传送门房间正上方：想去 " + stand.getX() + "," + stand.getZ()
                + "（地表 y=" + surface + "），停在 " + rig.player().blockPosition()));
    }

    /**
     * The outcome, and it is a POSITION rather than a discovery.
     *
     * <p>"The scan found twelve frames" is true from anywhere on the surface — the rung has to say
     * the body is standing next to them, because everything above it works at arm's length.
     */
    private static void judgeTheRoom(SceneContext ctx, JourneyRig rig, BlockPos centre) {
        ServerLevel level = levelOf(rig);
        BlockPos at = rig.player().blockPosition();
        BlockPos nearest = nearestFrame(level, at, FRAME_SEARCH);
        double away = nearest == null ? -1 : Math.sqrt(at.distSqr(nearest));
        rig.evidence("room.landedAt", xyz(at));
        rig.evidence("room.underfoot", blockAt(rig, at.below()));
        rig.evidence("room.nearestFrame", nearest == null ? "无"
                : xyz(nearest) + " 距 " + Math.round(away) + " 格");
        rig.evidence("room.frameCentre", xyz(centre));
        rig.noteAdvancement("minecraft:story/follow_ender_eye");
        ctx.expect(nearest != null && away <= REACHED_ROOM_WITHIN)
                .as("the body is standing in the portal room, within reach of the frame").isTrue();
        rig.reach("走到 " + xyz(JourneyRoute.stronghold) + " 的要塞并下到传送门房间，落在 " + xyz(at)
                + "，最近的框架 " + Math.round(away) + " 格");
    }

    // =====================================================================================
    // 18 — the end portal. Twelve useOn-only interactions, and nothing else.
    // =====================================================================================

    /**
     * Set an eye into every empty frame until the portal opens.
     *
     * <p><b>The verb is the whole rung.</b> {@code EnderEyeItem} overrides {@code useOn(UseOnContext)}
     * and has no {@code use} at all, so the eye must go through {@code Avatar.useBlock(cell, face)};
     * called the other way it returns {@code Item.use}'s default {@code PASS} and the world does not
     * move. That silent nothing is byte-identical to a click that missed, which is exactly the trap
     * the flint-and-steel set two chapters ago and the mirror image of the bucket's — a bucket has no
     * {@code useOn} and must go through {@code useItemInHand}.
     *
     * <p>Frames are re-derived from the body's surroundings rather than carried over from
     * {@link #stronghold}. That is deliberate: two dogfood passes share one JVM, so a static holding
     * the last run's coordinates would be read by the next as its own, and a rung whose subject came
     * from a previous world is not testing anything. Scanning a cube around a body that is standing
     * in the room costs nothing worth saving.
     */
    private static void endPortal(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.END_PORTAL);
        rig.generousPathfinding();

        ServerLevel level = levelOf(rig);
        BlockPos at = rig.player().blockPosition();
        List<BlockPos> frames = framesNearBody(level, at, FRAME_SEARCH);
        int eyes = rig.carrying("minecraft:ender_eye");
        rig.evidence("body.at", xyz(at));
        rig.evidence("frames.inReach", frames.size() + " 个（" + FRAME_SEARCH + " 格立方内）");
        rig.evidence("ender_eye.before", eyes);
        if (frames.isEmpty()) {
            ctx.fail("身边 " + FRAME_SEARCH + " 格内没有 end_portal_frame —— STRONGHOLD 说进了传送门房间，"
                    + "这里却一个框架都扫不到（身体在 " + at + "，脚下 " + blockAt(rig, at.below()) + "）");
            return;
        }

        List<BlockPos> empty = new ArrayList<>();
        for (BlockPos f : frames) if (!hasEye(level, f)) empty.add(f);
        empty.sort(Comparator.comparingDouble(at::distSqr));
        rig.evidence("frames.empty", empty.size());
        if (eyes < empty.size()) {
            rig.evidence("eyes.short", "缺 " + (empty.size() - eyes) + " 只 —— EYE_OF_ENDER 只交了 "
                    + eyes + "，空框架有 " + empty.size() + " 个");
        }
        rig.attempting("往 " + empty.size() + " 个空框架里各放一只末影之眼（useBlock，不是 useItemInHand）");
        setOneEye(ctx, rig, empty, 0, frames);
    }

    private static void setOneEye(SceneContext ctx, JourneyRig rig, List<BlockPos> todo, int i,
                                  List<BlockPos> all) {
        if (i >= todo.size()) { judgeThePortal(ctx, rig, all); return; }
        BlockPos frame = todo.get(i);
        if (rig.carrying("minecraft:ender_eye") < 1) {
            rig.evidence("eyes.ranOutAt", xyz(frame) + "（第 " + i + " 个空框架）");
            judgeThePortal(ctx, rig, all);
            return;
        }
        // Walk into reach first. useBlock would take the click from across the room — see the class
        // note on why this file will not let it.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(frame, EYE_REACH))), 600, () -> {
            ServerLevel level = levelOf(rig);
            boolean held = rig.body().avatar().holdItem(Items.ENDER_EYE);
            if (!held) {
                rig.evidence("eye." + i + ".hand", "拿不到 ender_eye，手上是 " + heldItem(rig));
            }
            double reach = Math.sqrt(rig.player().blockPosition().distSqr(frame));
            rig.body().avatar().useBlock(frame, Direction.UP);
            rig.settle(new HoldStill(2), 10, () -> {
                if (!hasEye(level, frame)) {
                    rig.evidence("eye." + i + ".missed", xyz(frame) + " 仍是 "
                            + level.getBlockState(frame) + "（手上 " + heldItem(rig)
                            + "，距 " + String.format(Locale.ROOT, "%.1f", reach) + " 格）");
                }
                setOneEye(ctx, rig, todo, i + 1, all);
            });
        });
    }

    /**
     * The outcome, and it is the PORTAL rather than the frames.
     *
     * <p>Twelve filled frames is an intermediate that can be entirely true while the goal failed: a
     * frame laid without facings fills with eyes and never becomes a portal, and a frame missing one
     * of its twelve is eleven successful interactions and no door. So the assertion reads the world
     * for {@code minecraft:end_portal} blocks, which vanilla creates nine of, or none.
     */
    private static void judgeThePortal(SceneContext ctx, JourneyRig rig, List<BlockPos> frames) {
        ServerLevel level = levelOf(rig);
        int filled = 0;
        for (BlockPos f : frames) if (hasEye(level, f)) filled++;
        BlockPos centre = centreOf(frames);
        int cells = 0;
        BlockPos doorway = null;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    if (!level.getBlockState(c).is(Blocks.END_PORTAL)) continue;
                    cells++;
                    if (doorway == null) doorway = c;
                }
        rig.evidence("frames.filled", filled + "/" + frames.size());
        rig.evidence("ender_eye.left", rig.carrying("minecraft:ender_eye"));
        rig.evidence("portal.cells", cells);
        rig.evidence("portal.at", xyz(doorway));
        ctx.expect(cells).as("the twelfth eye opens the portal (end_portal blocks in the frame)")
                .isAtLeast(1);
        rig.reach("框架填到 " + filled + "/" + frames.size() + "，末地门开了 " + cells + " 格，门口在 "
                + xyz(doorway));
    }

    // =====================================================================================
    // 19 — the End. The crossing, judged on where the body landed.
    // =====================================================================================

    /**
     * Step into the portal and come out on the End's own platform.
     *
     * <p>A dimension check alone would pass for a body standing in the void beside the island, and
     * that is not a hypothetical: {@code ServerPlayer.changeDimension} delivers the destination
     * through {@code connection.teleport}, both loaders' fake players used to swallow it, and the
     * Nether rung once arrived 87 501 blocks from where it should have. The End's destination is the
     * fixed {@code ServerLevel.END_SPAWN_POINT}, so the drift is exactly measurable — and it is
     * asserted, because a body that is not on the platform cannot fight anything.
     *
     * <p>Up to {@link #MAX_PORTAL_STEPS} cells are tried. Vanilla opens nine, they sit over the
     * stronghold's lava pool, and a walker that refuses to path onto one may well take another —
     * trying a second door is what a player does, and it costs a few hundred ticks.
     */
    private static void end(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.END);
        rig.generousPathfinding();

        if (ctx.level().getServer().getLevel(Level.END) == null) {
            rig.attempting("这个运行时没有末地维度");
            ctx.skip("NO_END_DIMENSION: 这个运行时没有 minecraft:the_end（数据包移除了），没有可去的地方");
            return;
        }
        ServerLevel level = levelOf(rig);
        BlockPos at = rig.player().blockPosition();
        List<BlockPos> doorways = portalCellsNear(level, at, PORTAL_SEARCH);
        rig.evidence("from", xyz(at));
        rig.evidence("portal.cells", doorways.size());
        if (doorways.isEmpty()) {
            ctx.fail("身边 " + PORTAL_SEARCH + " 格内没有 end_portal 方块 —— END_PORTAL 说门开了，"
                    + "这里却找不到门（身体在 " + at + "）");
            return;
        }
        rig.attempting("走进末地传送门并等它把身体送过去");
        stepIn(ctx, rig, doorways, 0);
    }

    private static void stepIn(SceneContext ctx, JourneyRig rig, List<BlockPos> doorways, int attempt) {
        if (THE_END.equals(rig.dimension())) { judgeTheCrossing(ctx, rig); return; }
        if (attempt >= doorways.size() || attempt >= MAX_PORTAL_STEPS) {
            BlockPos at = rig.player().blockPosition();
            rig.evidence("stand.at", xyz(at));
            rig.evidence("stand.in", blockAt(rig, at));
            rig.evidence("stand.goto", "end=" + rig.body().botState().mc_goto.endReason
                    + " err=" + rig.body().botState().mc_goto.lastError);
            ctx.fail("走不进末地传送门：试了 " + attempt + " 格门，身体还在 " + rig.dimension() + " " + at
                    + "。逐次落点见 step.*（要塞的门开在熔岩池上方，走进去和站到旁边是两码事）");
            return;
        }
        BlockPos cell = doorways.get(attempt);
        rig.settle(new IntentProcess(new Intent(new Goal.Block(cell))), PORTAL_WALK_TICKS,
                () -> waitFor(rig, () -> THE_END.equals(rig.dimension()), PORTAL_TRANSIT_TICKS, () -> {
                    rig.evidence("step." + attempt, "瞄 " + xyz(cell) + " → 停在 "
                            + xyz(rig.player().blockPosition()) + "（" + rig.dimension() + "）");
                    stepIn(ctx, rig, doorways, attempt + 1);
                }));
    }

    /**
     * Judge the crossing on three independent quantities, because any two of them can hold while the
     * rung has achieved nothing.
     *
     * <p><b>The horizontal / vertical split is not pedantry, it is the bug this method shipped
     * with.</b> See {@link #END_ARRIVAL_FALL}: {@code dimension} and a 13-block horizontal drift both
     * held for a body 4426 blocks down the void.
     *
     * <p><b>And Y alone would not have been enough either.</b>「y 对了」and「脚下有东西」are different
     * claims: a body can be at y=49 in the instant it steps off the platform's edge, and a body can
     * be at y=49 over a hole. So the third quantity is the block underfoot, read through
     * {@code blocksMotion()} rather than {@code onGround} — this body's {@code onGround} is wrong in
     * both directions, and {@code void_air} is what {@code getBlockState} returns for anything below
     * the build limit, so it cannot be told from「no platform」by name alone.
     *
     * <h2>The reading that separates the two ways this fails</h2>
     *
     * A body that ends in the void got there one of two ways, and they need opposite fixes:
     * <b>(a)</b> vanilla never built the arrival platform, or <b>(b)</b> it did and the body left it.
     * Nothing about the body can tell them apart after the fall — but the platform is world state and
     * <b>stays</b>, so {@code platform.obsidian} counts the 5×5 that
     * {@code EndPlatformFeature.createEndPlatform} lays at {@code y = 48} and answers it outright, for
     * the cost of twenty-five block reads. It is recorded on every crossing, pass or fail, because a
     * reading that only appears on failures cannot establish what the healthy case looks like.
     */
    private static void judgeTheCrossing(SceneContext ctx, JourneyRig rig) {
        BlockPos now = rig.player().blockPosition();
        BlockPos want = ServerLevel.END_SPAWN_POINT;
        int drift = Math.max(Math.abs(now.getX() - want.getX()), Math.abs(now.getZ() - want.getZ()));
        int off = Math.abs(now.getY() - want.getY());
        ServerLevel end = levelOf(rig);
        // Vanilla's own arithmetic, not a guessed offset: createEndPlatform is called with
        // BlockPos.containing(END_SPAWN_POINT.getBottomCenter()).below() and lays its floor one row
        // under that.
        BlockPos floor = BlockPos.containing(want.getBottomCenter()).below().below();
        int obsidian = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (end.getBlockState(floor.offset(dx, 0, dz)).is(Blocks.OBSIDIAN)) obsidian++;
        boolean standing = end.getBlockState(now.below()).blocksMotion();
        rig.evidence("dimension", rig.dimension());
        rig.evidence("arrived.at", xyz(now));
        rig.evidence("underfoot", blockAt(rig, now.below()) + "（挡得住 " + standing + "）");
        rig.evidence("spawnPoint", xyz(want) + "（水平漂移 " + drift + " 格，垂直差 " + off + " 格）");
        rig.evidence("platform.obsidian", obsidian + "/25 格黑曜石在 " + xyz(floor)
                + " 那一层 —— 25 = 台子建好了（那么身体是自己离开的），0 = 台子根本没建"
                + "（那么这是驱动的过界缺陷，不是走路问题）");
        rig.noteAdvancement("minecraft:story/enter_the_end");
        ctx.expect(rig.dimension()).as("the body is in the End").isEqualTo(THE_END);
        // Three quantities, three claims. "somewhere in the End", "over the platform", "on the
        // platform" and "standing on anything at all" are different things, and this rung passed once
        // on the first two alone.
        ctx.expect(drift).as("the arrival is over the End's spawn platform, not a swallowed teleport")
                .isAtMost(END_ARRIVAL_DRIFT);
        ctx.expect(off).as("the arrival is at the platform's own height, not falling past it")
                .isAtMost(END_ARRIVAL_FALL);
        ctx.expect(standing).as("the body has something under it, not void").isTrue();
        rig.reach("从要塞的门过到末地，落在 " + xyz(now) + "（END_SPAWN_POINT " + xyz(want)
                + "，水平 " + drift + " 格、垂直 " + off + " 格，脚下 " + blockAt(rig, now.below())
                + "，台子 " + obsidian + "/25）");
    }

    // =====================================================================================
    // 20 — the dragon. Crystals first, then a boss that cannot be hit as itself.
    // =====================================================================================

    /**
     * Bridge to the island, break the crystals, kill the dragon.
     *
     * <p>Three sub-goals and every one of them is a first for this ladder.
     *
     * <p><b>The island has to be reached at all.</b> Vanilla drops an arrival on a 5x5 obsidian
     * platform at {@code (100, 50, 0)}, roughly forty blocks of void short of the main island. A
     * player bridges. So does this — with the spoil the overworld rungs left in the bag, which is
     * why {@code blocks.forBridging} is recorded before anything else: a body that arrives here with
     * an empty inventory has already lost, and it should say so rather than walk into the void.
     *
     * <p><b>A crystal heals the dragon, so the crystals come first</b>, and each sits twenty to forty
     * blocks up its own obsidian pillar. {@code Avatar.attackEntity} would break one from the ground —
     * it calls {@code Player.attack} straight through and vanilla checks no distance — so the script
     * refuses to swing outside {@link #MELEE_REACH} and climbs instead. That makes the tower the
     * thing under test, which is the point: {@code ascendByTowering} is the mechanism this ladder has
     * the least confidence in, and a green dragon rung that skipped it would be a lie.
     *
     * <p><b>The dragon cannot be hit as itself.</b> {@code EnderDragon.hurt} refuses every direct hit;
     * damage lands only through an {@code EnderDragonPart}, the head takes it undivided and every
     * other part divides it by four. And unlike the arena probe that established this, a journey rung
     * may not reset {@code invulnerableTime} — so the duel runs at vanilla's tempo, one swing per
     * twenty ticks, which means it can only make progress while the dragon is perched or diving.
     */
    private static void dragon(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.DRAGON);
        rig.generousPathfinding();
        // Entities exist only in loaded chunks, and the walking radius cannot see the pillars. A scan
        // that is blind does not report blindness — it reports that there are no crystals.
        JourneyRig.seeAtLeast(END_SIGHT_CHUNKS);
        ctx.cleanup(JourneyRig::seeNormally);

        if (!THE_END.equals(rig.dimension())) {
            ctx.fail("不在末地：END 说进来了，这里读到 " + rig.dimension() + "（身体在 "
                    + rig.player().blockPosition() + "）");
            return;
        }
        ServerLevel end = levelOf(rig);
        rig.evidence("start.at", xyz(rig.player().blockPosition()));
        rig.evidence("weapon.best", bestWeapon(rig));
        rig.evidence("blocks.forBridging", pillarBlock(rig) + " ×" + rig.carrying(pillarBlock(rig)));
        recordTheFight(rig, end);

        rig.attempting("从降落台架桥走到主岛中央");
        marchInTheEnd(ctx, rig, 0);
    }

    /** What vanilla's own bookkeeping says about this fight, read before anything is attempted —
     *  because "there is no dragon" has a cause that lives here rather than in the combat loop. */
    private static void recordTheFight(JourneyRig rig, ServerLevel end) {
        rig.evidence("level.realPlayers", end.players().size());
        var fight = end.getDragonFight();
        if (fight == null) {
            rig.evidence("dragonFight", "无 —— 这个末地没有 EndDragonFight");
            return;
        }
        rig.evidence("dragonFight.crystalsAlive", fight.getCrystalsAlive());
        rig.evidence("dragonFight.previouslyKilled", fight.hasPreviouslyKilledDragon());
        rig.evidence("dragonFight.dragonUUID", String.valueOf(fight.getDragonUUID()));
    }

    /**
     * The End's own build floor. Its dimension type is {@code min_y = 0}, so a body below this is not
     *「low」— it is outside the world, in free fall, and every further leg is an order issued to
     * something that cannot obey it. <b>Not a tuned threshold</b>: it is the build limit, so it needs
     * no calibration and cannot drift.
     */
    private static final int END_VOID_BELOW = 0;

    /**
     * The walk from the arrival platform to the middle of the island, in the same re-planning legs
     * the overworld march uses — shorter, because forty blocks of bridging is not a kilometre.
     *
     * <h2>Why every leg carries a stock count and a plan</h2>
     *
     * Measured 2026-08-17, a rehearsal of this rung fell out of the world on leg 0 and then issued
     * six more legs to a body dropping 23 500 blocks each: {@code island.1 = 145,-23228,0} …
     * {@code island.6 = 383,-140809,8}. It carried 1024 cobblestone and the bridge never happened.
     * <b>Three different mechanisms produce exactly that trace</b> and the rung recorded nothing that
     * could tell them apart:
     *
     * <ul>
     *   <li><b>The placement never happened</b> — the body stepped out before putting a block down.
     *       An execution-order fault.</li>
     *   <li><b>It happened and the body did not end up on it</b> — a footing fault.</li>
     *   <li><b>The planner never intended to bridge</b> — it treated the void as walkable, or gave up
     *       and the executor pushed the body anyway. A cost/passability fault.</li>
     * </ul>
     *
     * So each leg now records what a climb SPENT and what the walker was actually holding:
     * {@code island.N.plan} carries {@code 放了 K 块}, {@code pathLen}, the name of the move entering
     * the current node, and the run's own verdict. The three read differently — {@code K > 0} is the
     * footing fault; {@code K = 0} with a bridge move planned is the ordering fault; {@code K = 0}
     * with {@code move=walk} over void, or with no path at all while the body still moved, is the
     * planner fault.
     *
     * <p><b>And the march now stops at the first leg that starts in the void.</b> Six wasted legs cost
     * this run 33 minutes and produced six copies of one fact. The guard cannot rescue anything — it
     * only fails sooner, in words that name the void rather than「走不到主岛中央」.
     */
    private static void marchInTheEnd(SceneContext ctx, JourneyRig rig, int leg) {
        BlockPos at = rig.player().blockPosition();
        double away = Math.hypot(at.getX(), at.getZ());
        String pillar = pillarBlock(rig);
        int stock = rig.carrying(pillar);
        rig.evidence("island." + leg, xyz(at) + " 距中心 " + Math.round(away) + " 格，脚下 "
                + blockAt(rig, at.below()) + "，" + pillar + " ×" + stock);
        if (at.getY() < END_VOID_BELOW) { fellOffTheIsland(ctx, rig, leg, at, pillar, stock); return; }
        if (away <= 8) {
            rig.evidence("island.legs", leg);
            gatherTheCrystals(ctx, rig);
            return;
        }
        if (leg >= 8) {
            rig.evidence("island.reached", false);
            ctx.fail("走不到主岛中央：" + leg + " 段之后仍在 " + at + "，距中心 " + Math.round(away)
                    + " 格。降落台和主岛之间是虚空，过去要架桥 —— 身上有 " + pillar + " ×"
                    + stock + "，allowPlace=" + BotConfig.allowPlace
                    + "。哪一段花掉了方块、哪一段根本没有计划，见 island.*.plan");
            return;
        }
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(0, 0, 6))), 6_000, () -> {
            BlockPos now = rig.player().blockPosition();
            rig.evidence("island." + leg + ".plan", planOf(rig, pillar, stock));
            if (flatDistance(at, now) >= WEDGED_UNDER) { marchInTheEnd(ctx, rig, leg + 1); return; }
            rig.evidence("island." + leg + ".wedged", xyz(now) + " 一段没挪动（goto end="
                    + rig.body().botState().mc_goto.endReason + " err="
                    + rig.body().botState().mc_goto.lastError + "）");
            marchInTheEnd(ctx, rig, leg + 1);
        });
    }

    /**
     * What the leg spent and what the walker was holding — the row that separates the three ways a
     * bridge fails to happen.
     *
     * <p><b>{@code active} is printed because {@code pathLen}/{@code move} lie without it.</b>
     * {@code ProcessSlot.reset()} clears both at every terminal exit while keeping {@code endReason}
     * and {@code lastError}, so a leg whose process FINISHED reports {@code pathLen=0 move=null}
     * — indistinguishable, without {@code active}, from a planner that never produced a path. A leg
     * that merely ran out of ticks still holds live values.
     */
    private static String planOf(JourneyRig rig, String pillar, int stockBefore) {
        var slot = rig.body().botState().mc_goto;
        int spent = stockBefore - rig.carrying(pillar);
        return "放了 " + spent + " 块 " + pillar + "；active=" + slot.active
                + " pathLen=" + slot.pathLen + " move=" + slot.pathMove
                + " end=" + slot.endReason + " err=" + slot.lastError
                + (slot.active ? "" : "（进程已终止，pathLen/move 是 reset 之后的空值，"
                        + "不要读成「压根没有计划」）")
                // ⚠️ pathLen/move above are the state at the END of the leg, and a leg that fell out
                // of the world spends most of itself in the void — where BridgePlace.eval's every
                // premise holds, because it deliberately does not check for support underfoot. So
                // that half describes the planning of a falling body. The place tally's FIRST rows
                // are the ones taken while there was still ground under the question.
                + "；首个计划 " + slot.firstPlan
                + "；place " + rig.body().avatar().placeTally();
    }

    /**
     * The body is under the End's build floor: stop, and hand the reader the fork rather than a
     * distance.
     *
     * <p>The failure this replaces said「走不到主岛中央」after eight legs. That sentence is true and
     * useless — it names the goal instead of naming that the body left the only ground there was, and
     * it arrives half an hour late.
     */
    private static void fellOffTheIsland(SceneContext ctx, JourneyRig rig, int leg, BlockPos at,
                                         String pillar, int stock) {
        rig.evidence("island.fellAt", xyz(at) + "（末地建筑下限 y=" + END_VOID_BELOW
                + "，所以这是虚空，不是「低」）");
        // The staging measured this once, before anything moved. It is wrong by now and the whole
        // point of re-reading it here is that the stale one reads like an all-clear.
        rig.evidence("dragon.rangeNow", fightRangeNow(rig));
        ctx.fail("掉出末地：第 " + leg + " 段开始时身体已在 " + xyz(at) + "，低于末地的建筑下限 y="
                + END_VOID_BELOW + " —— 这是虚空，不是走得慢。降落台是 5×5，主岛在 "
                + Math.round(Math.hypot(at.getX(), at.getZ())) + " 格外，中间要架桥；"
                + "身上还有 " + pillar + " ×" + stock + "，allowPlace=" + BotConfig.allowPlace
                + "。哪一种失败看 island.*.plan：放了>0 块 = 放下了却没踩上（落脚判据）；"
                + "放了 0 块且计划里有 bridge = 没放就迈出去（执行顺序）；"
                + "放了 0 块且 move=walk 或压根没有路 = 寻路没打算架桥（代价/可通行判据）。"
                + "⚠️ rehearsal.fightRange 是布景时刻测的，此刻的距离见 dragon.rangeNow");
    }

    /** How far the body is from the dragon fight's own centre, <b>right now</b>. {@code EndDragonFight}
     *  builds {@code validPlayer} as {@code EntitySelector.withinDistance(0, 128, 0, 192.0)} and its
     *  {@code tick()} does nothing at all while no valid player is in range — so this number, taken at
     *  the moment of the failure, is the difference between「打不过」and「没有对手，因为身体不在场」. */
    private static String fightRangeNow(JourneyRig rig) {
        double away = Math.sqrt(rig.player().distanceToSqr(0.0, 128.0, 0.0));
        return String.format(Locale.ROOT, "距 (0,128,0) %.1f 格（EndDragonFight.validPlayer 门限 192）—— %s",
                away, away <= 192.0 ? "在范围内" : "超出：updatePlayers 看不到这具身体，龙不会被创建");
    }

    /** Snapshot the crystals once, nearest first, then work the list by index — re-taking "the
     *  nearest crystal" every round would send the rung back to the same unreachable one forever,
     *  which is the retry-that-changes-nothing this ladder has already paid for once. */
    private static void gatherTheCrystals(SceneContext ctx, JourneyRig rig) {
        ServerLevel end = levelOf(rig);
        Vec3 here = rig.player().position();
        List<EndCrystal> crystals = new ArrayList<>(end.getEntitiesOfClass(EndCrystal.class,
                boxAround(Vec3.ZERO, DRAGON_SEARCH)));
        crystals.sort(Comparator.comparingDouble(c -> c.distanceToSqr(here)));
        if (crystals.size() > MAX_CRYSTALS) crystals = new ArrayList<>(crystals.subList(0, MAX_CRYSTALS));
        rig.evidence("crystals.found", crystals.size());
        smashCrystal(ctx, rig, List.copyOf(crystals), 0);
    }

    private static void smashCrystal(SceneContext ctx, JourneyRig rig, List<EndCrystal> crystals, int i) {
        if (i >= crystals.size()) {
            int left = 0;
            for (EndCrystal c : crystals) if (c.isAlive()) left++;
            rig.evidence("crystals.left", left + "/" + crystals.size());
            duel(ctx, rig, 0);
            return;
        }
        EndCrystal crystal = crystals.get(i);
        if (!crystal.isAlive()) { smashCrystal(ctx, rig, crystals, i + 1); return; }
        BlockPos base = crystal.blockPosition();
        int top = Mth.floor(crystal.getY()) - 2;
        rig.attempting("砸掉第 " + i + " 座柱子上的末影水晶（" + xyz(base) + "）");
        rig.evidence("crystal." + i + ".at", xyz(base));
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(base.getX(), base.getZ(), 2))),
                CRYSTAL_WALK_TICKS, () -> {
            String pillar = pillarBlock(rig);
            // Put the block in the HAND first: TowerProcess can only look in the hotbar, so a body
            // whose hotbar is tools reports "no placeable block" while carrying a stack of stone.
            rig.body().avatar().holdItem(itemOf(pillar));
            rig.evidence("crystal." + i + ".climb", "爬到 y=" + top + "，用 " + pillar + " ×"
                    + rig.carrying(pillar));
            rig.settle(new TowerProcess(top, pillar), CRYSTAL_CLIMB_TICKS, () -> {
                holdBestWeapon(rig);
                SwingAt swing = new SwingAt(crystal, CRYSTAL_SWING_TICKS, MELEE_REACH);
                rig.settle(swing, CRYSTAL_SWING_TICKS + 50, () -> {
                    rig.evidence("crystal." + i + ".result", (crystal.isAlive() ? "还在" : "碎了")
                            + "（站到 y=" + rig.player().blockPosition().getY() + "，最近 "
                            + String.format(Locale.ROOT, "%.1f", swing.closest()) + " 格，挥 "
                            + swing.swings() + " 刀）");
                    smashCrystal(ctx, rig, crystals, i + 1);
                });
            });
        });
    }

    private static void duel(SceneContext ctx, JourneyRig rig, int round) {
        ServerLevel end = levelOf(rig);
        EnderDragon dragon = nearestDragon(end, rig.player().position());
        if (dragon == null) {
            if (round == 0) {
                rig.attempting("等末地龙出现");
                waitFor(rig, () -> nearestDragon(end, rig.player().position()) != null,
                        DRAGON_WAIT_TICKS, () -> duel(ctx, rig, 1));
                return;
            }
            noDragonHere(ctx, rig, end);
            return;
        }
        rig.evidence("dragon.hp0", String.format(Locale.ROOT, "%.1f", dragon.getHealth()));
        rig.evidence("dragon.at", xyz(dragon.blockPosition()));
        holdBestWeapon(rig);
        rig.attempting("在中央等龙够得着，够得着就打头（头部不分摊伤害，其余部位除以四）");
        DuelTheDragon fight = new DuelTheDragon(DUEL_TICKS, MELEE_REACH);
        rig.settle(fight, DUEL_TICKS + 200, () -> {
            EnderDragon still = nearestDragon(end, rig.player().position());
            boolean dead = still == null || still.isDeadOrDying();
            rig.evidence("duel.swings", fight.swings() + "（其中打到头 " + fight.headHits() + " 次）");
            rig.evidence("duel.closest", String.format(Locale.ROOT, "%.1f 格（%s）",
                    fight.closest(), fight.closestPart()));
            rig.evidence("dragon.hp", still == null ? "不在了"
                    : String.format(Locale.ROOT, "%.1f", still.getHealth()));
            rig.evidence("dragon.dead", dead);
            rig.noteAdvancement("minecraft:end/kill_dragon");
            ctx.expect(dead).as("the ender dragon is dead").isTrue();
            rig.reach("屠龙成功：挥 " + fight.swings() + " 刀（打到头 " + fight.headHits() + " 次）");
        });
    }

    /**
     * There is no dragon, and the reason is not the combat loop.
     *
     * <p>{@code EndDragonFight.tick} rescans {@code ServerLevel.getPlayers(...)} every twenty ticks
     * and does <b>nothing at all</b> — no arena ticket, no state scan, no {@code createNewDragon} —
     * while that list is empty. This track's body is a {@code FakePlayer}: it was never placed
     * through {@code PlayerList.placeNewPlayer}, so it is not in {@code level.players()} and the
     * fight cannot see it. The crystals are there (worldgen places those), the arena is there, and
     * the boss simply is never created.
     *
     * <p>So this is a capability finding about the BODY rather than a defeat, and it is reported as
     * one, naming both halves: which vanilla method makes the decision, and the flag that changes
     * the answer. {@code JoinedPlayerBodies} — {@code -Dworlddriver.realPlayerBodies=true} — joins
     * the server for real and puts the body in that list; the same seam that flipped three
     * advancements is the one this rung is waiting on.
     */
    private static void noDragonHere(SceneContext ctx, JourneyRig rig, ServerLevel end) {
        var fight = end.getDragonFight();
        rig.evidence("dragon.present", false);
        rig.evidence("level.realPlayers", end.players().size());
        rig.evidence("body.inPlayerList", end.players().contains(rig.player()));
        // BEFORE blaming the player list, read the distance AGAIN. The list and the range are two
        // independent halves of `validPlayer`, a rehearsal records the range once at staging time,
        // and a body that has since moved makes that stale row read as an all-clear for the one
        // cause that is actually in play. Measured 2026-08-17: staged at 127.8 blocks (in range),
        // failed 23 000 blocks below the island, and `dragonUUID = null` was read as「没有对手」
        // rather than as「身体不在场」.
        rig.evidence("dragon.rangeNow", fightRangeNow(rig));
        ctx.fail("末地里没有龙可打。EndDragonFight.tick 每 20 tick 重扫一次 ServerLevel 的玩家列表，"
                + "列表为空时它什么都不做 —— 不占 arena ticket、不 scanState、更不会 createNewDragon。"
                + "这条赛道的身体是 FakePlayer，从没走过 PlayerList.placeNewPlayer，所以永远不在那张表里"
                + "（level.realPlayers=" + end.players().size() + "，crystalsAlive="
                + (fight == null ? "无龙战" : String.valueOf(fight.getCrystalsAlive()))
                + "，dragonUUID=" + (fight == null ? "无" : String.valueOf(fight.getDragonUUID()))
                + "）。⚠️ 先读 dragon.rangeNow：玩家列表和 192 格是 validPlayer 的两半，"
                + "任何一半不成立都会得到同一个 dragonUUID=null，而排练的 rehearsal.fightRange "
                + "是布景时刻测的、此刻多半已过期。这不是打不过，是这一级没有对手：要让它有对手，身体得真的加入服务器"
                + "（-Dworlddriver.realPlayerBodies=true，见 JoinedPlayerBodies）");
    }

    // =====================================================================================
    // Two processes this file needs and the engine does not have.
    // =====================================================================================

    /**
     * Stand still and hit one thing, at vanilla's own tempo, only when it is genuinely in reach.
     *
     * <p>The reach test is the reason this exists rather than a bare {@code attackEntity} call.
     * {@code Player.attack} checks no distance, so a script could shatter a crystal forty blocks
     * overhead; gating on {@link #MELEE_REACH} is what makes the climb above it load-bearing. The
     * twenty-tick cadence is {@code LivingEntity.invulnerableTime} — swinging faster does not do more
     * damage, it does less, and it also charges the attack-strength meter for free.
     */
    private static final class SwingAt implements BotProcess {

        private final Entity target;
        private final int maxTicks;
        private final double reach;
        private int elapsed;
        private int sinceSwing = SWING_EVERY;
        private int swings;
        private double closest = Double.MAX_VALUE;

        SwingAt(Entity target, int maxTicks, double reach) {
            this.target = target;
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        @Override public String kind() { return "journeySwingAt"; }

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Avatar a, WorldView w, BotState st) {
            a.commandMove(0, 0);
            a.commandJump(false);
            a.breakHold(false);
            Player p = a.player();
            if (p == null || !target.isAlive()) return true;
            double d = p.distanceTo(target);
            if (d < closest) closest = d;
            sinceSwing++;
            if (d <= reach && sinceSwing >= SWING_EVERY) {
                a.attackEntity(target);
                sinceSwing = 0;
                swings++;
            }
            return ++elapsed >= maxTicks;
        }

        int swings() { return swings; }

        double closest() { return closest == Double.MAX_VALUE ? -1 : closest; }
    }

    /**
     * Hold the middle of the arena and hit the dragon whenever a part of it comes within reach.
     *
     * <p>Aims at the HEAD by preference and settles for whatever else is close, because the head
     * takes a hit undivided and every other part takes a quarter of it plus one — a loop that is
     * only ever clipping a wing is winning four times slower than its swing count suggests, and
     * {@code duel.swings} beside {@code headHits} is what says which happened.
     *
     * <p>It does not chase. A flying dragon cannot be caught by a walking body, and the fight vanilla
     * designs is one of waiting: the boss perches on the fountain, lowers its head, and that is the
     * window. Steering during that window is worse than standing still, which is why every input is
     * released on every tick — the same reason {@link HoldStill} exists.
     */
    private static final class DuelTheDragon implements BotProcess {

        private final int maxTicks;
        private final double reach;
        private int elapsed;
        private int sinceSwing = SWING_EVERY;
        private int swings;
        private int headHits;
        private double closest = Double.MAX_VALUE;
        private String closestPart = "无";

        DuelTheDragon(int maxTicks, double reach) {
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        @Override public String kind() { return "journeyDragonDuel"; }

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Avatar a, WorldView w, BotState st) {
            a.commandMove(0, 0);
            a.commandJump(false);
            a.breakHold(false);
            Player p = a.player();
            if (p == null) return true;
            EnderDragon dragon = nearestDragon(p.level(), p.position());
            if (dragon == null || dragon.isDeadOrDying()) return true;

            Entity head = null;
            Entity nearest = null;
            String nearestName = "无";
            float headAway = Float.MAX_VALUE;
            float nearestAway = Float.MAX_VALUE;
            for (var part : dragon.getSubEntities()) {
                float d = p.distanceTo(part);
                if ("head".equals(part.name)) { head = part; headAway = d; }
                if (d < nearestAway) { nearestAway = d; nearest = part; nearestName = part.name; }
            }
            if (nearestAway < closest) { closest = nearestAway; closestPart = nearestName; }

            sinceSwing++;
            Entity aim = head != null && headAway <= reach ? head
                    : nearestAway <= reach ? nearest : null;
            if (aim != null && sinceSwing >= SWING_EVERY) {
                a.attackEntity(aim);
                sinceSwing = 0;
                swings++;
                if (aim == head) headHits++;
            }
            return ++elapsed >= maxTicks;
        }

        int swings() { return swings; }

        int headHits() { return headHits; }

        double closest() { return closest == Double.MAX_VALUE ? -1 : closest; }

        String closestPart() { return closestPart; }
    }

    // =====================================================================================
    // Reading the world.
    //
    // Four of these are package-private rather than private, for JourneyRehearsal: a rehearsal of
    // rung 18 or 19 has to put the body in the room the RUNG would find, and two scans that
    // disagree put「where the staging thinks the room is」and「where the rung thinks it is」in
    // different places, with no reading afterwards that tells them apart. Duplicating a CONSTANT
    // across with a stated reason is licensed here (see PORTAL_FRAME_CELLS over there);
    // duplicating an ALGORITHM is not.
    // =====================================================================================

    /**
     * Every end-portal frame in a square of chunks around {@code centre}.
     *
     * <p>Through {@code ChunkAccess.findBlocks}, which asks each 16³ section's palette whether it
     * could possibly hold the block before reading a single state. Scanning the same volume cell by
     * cell is about ten million lookups inside one server tick; this is a few hundred palette probes
     * and then only the sections that answered yes.
     *
     * <p>{@code level.getChunk} generates what is not there yet, which is what makes this reliable at
     * the far end of a march — but it is also why the radius is fixed and modest. A scan that reaches
     * past what it loaded does not report "nothing out there", it reports whatever ungenerated chunks
     * say, which is nothing, in exactly the shape of a real answer.
     */
    static List<BlockPos> framesAround(ServerLevel level, BlockPos centre, int chunkRadius) {
        List<BlockPos> out = new ArrayList<>();
        java.util.function.Predicate<BlockState> isFrame = s -> s.is(Blocks.END_PORTAL_FRAME);
        ChunkPos c = new ChunkPos(centre);
        for (int cx = c.x - chunkRadius; cx <= c.x + chunkRadius; cx++) {
            for (int cz = c.z - chunkRadius; cz <= c.z + chunkRadius; cz++) {
                level.getChunk(cx, cz).findBlocks(isFrame, (pos, state) -> out.add(pos.immutable()));
            }
        }
        return out;
    }

    /** The frames within a cube of the body — the short look rung 18 takes when it is already
     *  standing in the room, as opposed to the chunk sweep rung 17 needs to FIND the room. */
    private static List<BlockPos> framesNearBody(ServerLevel level, BlockPos at, int radius) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = at.offset(dx, dy, dz);
                    if (level.getBlockState(c).is(Blocks.END_PORTAL_FRAME)) out.add(c);
                }
        return out;
    }

    private static BlockPos nearestFrame(ServerLevel level, BlockPos at, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos f : framesNearBody(level, at, radius)) {
            double d = at.distSqr(f);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    /** The open portal's cells, nearest first — rung 19 walks into them in that order. */
    private static List<BlockPos> portalCellsNear(ServerLevel level, BlockPos at, int radius) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = at.offset(dx, dy, dz);
                    if (level.getBlockState(c).is(Blocks.END_PORTAL)) out.add(c);
                }
        out.sort(Comparator.comparingDouble(at::distSqr));
        return out;
    }

    static boolean hasEye(ServerLevel level, BlockPos frame) {
        BlockState st = level.getBlockState(frame);
        return st.hasProperty(EndPortalFrameBlock.HAS_EYE) && st.getValue(EndPortalFrameBlock.HAS_EYE);
    }

    /** The middle of a set of frames — the doorway sits inside their ring, so this is where the
     *  portal will appear and where the interior cells are read from. */
    static BlockPos centreOf(List<BlockPos> frames) {
        long x = 0;
        long y = 0;
        long z = 0;
        for (BlockPos f : frames) { x += f.getX(); y += f.getY(); z += f.getZ(); }
        int n = Math.max(1, frames.size());
        return new BlockPos((int) Math.round(x / (double) n), (int) Math.round(y / (double) n),
                (int) Math.round(z / (double) n));
    }

    /**
     * A cell in the portal room the body could actually stand in — and land in, from above.
     *
     * <p>The four conditions are the four ways a shaft into somebody else's building goes wrong:
     * the cell is solid, its head room is solid, there is nothing under it, or it is the lava vanilla
     * pools under the portal's own interior. {@link #ROOM_STAND_MIN} keeps the whole search clear of
     * the frame ring, because a shaft that lands on a frame destroys the thing the next rung came for.
     */
    static BlockPos standingCellInTheRoom(ServerLevel level, BlockPos centre) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -ROOM_STAND_SEARCH; dx <= ROOM_STAND_SEARCH; dx++)
            for (int dz = -ROOM_STAND_SEARCH; dz <= ROOM_STAND_SEARCH; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < ROOM_STAND_MIN) continue;
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    if (level.getBlockState(c).blocksMotion()) continue;
                    if (!level.getFluidState(c).isEmpty()) continue;
                    if (level.getBlockState(c.above()).blocksMotion()) continue;
                    if (!level.getBlockState(c.below()).blocksMotion()) continue;
                    if (!level.getFluidState(c.below()).isEmpty()) continue;
                    double d = centre.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        return best;
    }

    private static EnderDragon nearestDragon(Level level, Vec3 from) {
        EnderDragon best = null;
        double bestD = Double.MAX_VALUE;
        for (EnderDragon d : level.getEntitiesOfClass(EnderDragon.class, boxAround(from, DRAGON_SEARCH))) {
            double away = d.distanceToSqr(from);
            if (away < bestD) { bestD = away; best = d; }
        }
        return best;
    }

    private static AABB boxAround(Vec3 centre, double r) {
        return new AABB(centre.x - r, centre.y - r, centre.z - r,
                centre.x + r, centre.y + r, centre.z + r);
    }

    // =====================================================================================
    // Driving — copied from the overworld rungs rather than shared, see the class note.
    // =====================================================================================

    /**
     * Walk to a surface COLUMN, and try again from wherever the walker actually stopped.
     *
     * <p>Not defensive padding: {@code IntentProcess} reports its goal reached for a partial path, so
     * one drive can come back "done" with the body somewhere else entirely — measured elsewhere on
     * this ladder at eighty-eight blocks short, reported as "cannot reach the descent point". Each
     * attempt is a {@link JourneyRig#settle} rather than a drive, so a leg that burns its budget
     * costs an attempt instead of the rung, and the distance check is what decides.
     */
    private static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, int left, Runnable onArrived, Runnable onStuck) {
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(x, z, tolerance))), budget, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - x, at.getZ() - z);
            int attempt = MAX_WALK_ATTEMPTS - left + 1;
            rig.evidence(what + ".arrivedDistance", Math.round(away));
            rig.evidence(what + ".walkAttempts", attempt);
            if (away <= tolerance + 3) { onArrived.run(); return; }
            rig.evidence(what + ".goto." + attempt, "end=" + rig.body().botState().mc_goto.endReason
                    + " err=" + rig.body().botState().mc_goto.lastError);
            if (left <= 1) { onStuck.run(); return; }
            walkToColumn(rig, what, x, z, tolerance, budget, left - 1, onArrived, onStuck);
        });
    }

    /**
     * Dig the block under the body, let it fall in, repeat until its feet reach {@code targetY}.
     *
     * <p>Recursive because each block is its own await leg — the body has to actually fall between
     * them, and a loop inside one scene tick would break forty blocks in a world that never advanced
     * and leave the body standing on air.
     *
     * <p>Each cell is a {@link JourneyRig#mineCellOrGiveUp} rather than a {@code mineBlock}, and that
     * distinction is worth restating: {@code mineBlock}'s timeout IS the failure, which is right for
     * a dig the rung cannot proceed without and wrong for one cell of a long shaft — a cell that
     * cannot be reached would kill the rung on the framework's generic "await step exceeded", with no
     * evidence at all about WHICH cell, because the continuation that would have recorded it never
     * runs.
     */
    private static void digDownTo(SceneContext ctx, JourneyRig rig, int targetY, int budget, int cap,
                                 Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= targetY) { then.run(); return; }
        if (budget <= 0) {
            ctx.fail("竖井挖不到传送门房间：目标 y=" + targetY + "，试了 " + cap + " 次仍停在 " + at
                    + "（逐格读数见 shaft.*）");
            return;
        }
        ServerLevel level = levelOf(rig);
        BlockPos below = supportUnder(rig, at);
        int step = cap - budget;
        rig.evidence("shaft." + step, at.getX() + "," + at.getY() + "," + at.getZ()
                + " below=" + level.getBlockState(below).getBlock());
        // Already open — the previous pass broke it and the body has not dropped in yet. Mining air
        // is a no-op that still costs an attempt, and three of those in a row is how a shaft with
        // budget for four blocks runs out after one.
        if (!level.getBlockState(below).blocksMotion()) {
            rig.settle(new HoldStill(40), 60, () -> digDownTo(ctx, rig, targetY, budget - 1, cap, then));
            return;
        }
        rig.mineCellOrGiveUp(below, 600, () -> {
            rig.evidence("shaft." + step + ".broke", level.getBlockState(below).getBlock()
                    + " body=" + xyz(rig.player().blockPosition()));
            // Breaking the floor is not falling through it: this body is stepped only while a driver
            // is ticking it, and the single-block mine ends on the tick the block turns to air —
            // one avatar step, a tenth of a block of gravity. HoldStill is the settle that does not
            // steer; every process that DOES steer walks the body off its own hole.
            rig.settle(new HoldStill(40), 60,
                    () -> digDownTo(ctx, rig, targetY, budget - 1, cap, then));
        });
    }

    /**
     * The still-solid cell under the body's footprint — the one actually holding it up.
     *
     * <p>A player box is 0.6 wide, so a body standing near a cell edge is supported by TWO cells and
     * breaking only the centre one leaves it resting on the neighbour. {@code blocksMotion}, not
     * {@code !isAir}: fluid is not air and it is not a floor either, and a support test that accepted
     * it once cost a whole run of "the block broke but the body did not sink" about a body that was
     * swimming.
     */
    private static BlockPos supportUnder(JourneyRig rig, BlockPos at) {
        ServerLevel level = levelOf(rig);
        BlockPos centre = at.below();
        if (level.getBlockState(centre).blocksMotion()) return centre;
        var box = rig.player().getBoundingBox();
        int y = centre.getY();
        for (int x : new int[]{Mth.floor(box.minX), Mth.floor(box.maxX)})
            for (int z : new int[]{Mth.floor(box.minZ), Mth.floor(box.maxZ)}) {
                BlockPos corner = new BlockPos(x, y, z);
                if (level.getBlockState(corner).blocksMotion()) return corner;
            }
        return centre;
    }

    /**
     * Wait for something the WORLD decides, and carry on either way.
     *
     * <p>{@link JourneyRig#await}'s timeout is the framework's generic "await step exceeded", which
     * kills the rung before the continuation that would have said why ever runs. Counting inside the
     * predicate — the only code that runs on every tick of a wait — turns the timeout into an
     * ordinary outcome the caller can report on. The outer bound is deliberately larger so the
     * framework's own timeout stays a backstop for a wait that somehow never evaluates.
     */
    private static void waitFor(JourneyRig rig, BooleanSupplier done, int ticks, Runnable then) {
        int[] waited = {0};
        rig.await(() -> done.getAsBoolean() || ++waited[0] >= ticks, ticks + 100, then);
    }

    // =====================================================================================
    // Small readings.
    // =====================================================================================

    /** Stop a rung that needs surveyed coordinates before anyone has surveyed them. */
    private static boolean requireSurvey(SceneContext ctx, JourneyRig rig) {
        if (JourneyRoute.surveyed()) return false;
        rig.attempting("路线未标定：JourneyRoute 常量还是 UNSURVEYED");
        ctx.skip("UNSURVEYED: 先跑 wd.journey01Recon，把日志里的 [journey/recon] 常量烘进 JourneyRoute");
        return true;
    }

    /** The level the BODY is in, which after rung 19 is not {@code ctx.level()}. Every read in this
     *  file goes through here for that reason — a scan of the overworld for an end crystal finds
     *  nothing and does not say it looked in the wrong world. */
    private static ServerLevel levelOf(JourneyRig rig) {
        return (ServerLevel) rig.player().level();
    }

    /** What to pillar with: whichever spoil the body is actually carrying most of. A tower asked for
     *  a block the body does not hold reports "stuck (no Y gain — out of blocks?)" while the
     *  inventory is full, which names the wrong problem convincingly enough to cost a round. */
    private static String pillarBlock(JourneyRig rig) {
        String best = "minecraft:cobblestone";
        int most = 0;
        for (String id : PILLAR_BLOCKS) {
            int n = rig.carrying(id);
            if (n > most) { most = n; best = id; }
        }
        return best;
    }

    private static String bestWeapon(JourneyRig rig) {
        for (String id : WEAPONS) if (rig.carrying(id) > 0) return id;
        return "空手";
    }

    private static void holdBestWeapon(JourneyRig rig) {
        String id = bestWeapon(rig);
        if ("空手".equals(id)) { rig.evidence("weapon", "空手"); return; }
        boolean ok = rig.body().avatar().holdItem(itemOf(id));
        rig.evidence("weapon", id + (ok ? "" : "（拿不到手上，手里是 " + heldItem(rig) + "）"));
    }

    private static Item itemOf(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private static String heldItem(JourneyRig rig) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()));
    }

    private static String blockAt(JourneyRig rig, BlockPos at) {
        return String.valueOf(levelOf(rig).getBlockState(at).getBlock());
    }

    private static double flatDistance(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** A position for a record line, or 无 — every evidence key in this file may be asked about
     *  something that was not found, and "null" is a worse answer than a word. */
    private static String xyz(BlockPos at) {
        return at == null ? "无" : at.toShortString();
    }
}
