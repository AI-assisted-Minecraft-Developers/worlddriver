package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.movement.BlastFooting;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
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
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.end.EndDragonFight;
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
 *       {@code useOn} and nothing else, so it must go through {@code Body.useBlock}; a body that
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
 * {@code Body.useBlock} builds its own {@code BlockHitResult}: no ray trace, no reach gate. So does
 * {@code Body.attackEntity} — it calls {@code Player.attack} straight through, and vanilla's
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

    /** How close the walk home has to land. Tighter than the stronghold's, because what follows is
     *  not a hundred-block scan but a 24-block look for the portal itself — arriving further out
     *  than that would put the doorway outside the very search this walk exists to feed. */
    private static final int RETURN_ARRIVED_WITHIN = 12;

    /** How far a leg must move for the next one to be a different question. Four blocks: a body that
     *  shuffled inside its own cell has found no new vantage point, and asking the same pathfinder
     *  the same question from it spends a whole leg to learn nothing. */
    private static final int WEDGED_UNDER = 4;

    /** How far sideways a wedged march steps before trying again — perpendicular to the goal, so the
     *  next search is genuinely a different one rather than the same refusal from one cell over. */
    private static final int SIDESTEP_BLOCKS = 24;


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

    /** How near a frame the body walks before setting its eye. {@code Body.useBlock} would accept
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
     * nothing in the judgement looked at Y at all — so a bot in free fall through the void reported
     * "entered the End". A criterion that can be fully satisfied by a run that achieved nothing is missing a
     * dimension of the thing it claims to measure.
     *
     * <p>Four is what vanilla's own geometry allows: {@code EndPlatformFeature.createEndPlatform}
     * lays obsidian at {@code y = 48} and air at 49–51, and {@code EndPortalBlock} puts a
     * {@code ServerPlayer} at {@code END_SPAWN_POINT.getBottomCenter().subtract(0, 1, 0)}, i.e. y=49
     * — one row below {@code END_SPAWN_POINT} before anything moves. So 48..52 is "on the platform"
     * and ±4 covers it with a row to spare, while still catching a 4426-block fall by three orders
     * of magnitude.
     */
    private static final int END_ARRIVAL_FALL = 4;

    /** Chunks pinned around the body in the End. The obsidian pillars stand ~43 blocks from the
     *  centre and the walking radius of 2 (32 blocks) cannot see them — an entity search over
     *  unloaded chunks does not report that it was blind, it reports that there are no crystals. */
    private static final int END_SIGHT_CHUNKS = 5;

    /** How far a search reaches for the dragon and its crystals.
     *  <p>Half-extent of the box the duel looks for the dragon in.
     *
     *  <p>Was 128, and 128 is why the bow never fired once across five runs holding it: the dragon
     *  circles the End far wider than that, the {@code dragon == null} branch returns before the
     *  draw, and the give-up counter then ran to 20000 consecutive ticks of "out of reach" while the
     *  bot stood on the fountain with 256 arrows in the bag. A search radius that decides whether the
     *  fight can SEE its target must be wider than the arena the target flies in — the ranged half
     *  of this fight is worth nothing if the dragon is invisible for the whole circling phase. */
    private static final int DRAGON_SEARCH = 320;

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
    /** How close the pre-swing walk asks to get. Inside {@link #MELEE_REACH} rather than equal to it:
     *  a goal met exactly on the reach boundary is a hit the next tick's drift can take away. */
    private static final int CRYSTAL_APPROACH = 3;

    /** Budget for the step onto a blast-proof stand. Short: it is one cell away or it is not
     *  reachable, and a long budget here only delays the crystal after it. */
    private static final int CRYSTAL_RESEAT_TICKS = 400;
    /** Budget for that walk. Small on purpose — it is closing a few blocks, not crossing the island,
     *  and a body that cannot close them has a finding to report rather than a budget to spend. */
    private static final int CRYSTAL_APPROACH_TICKS = 600;
    /** Passes over the crystal list. Every survivor heals the dragon, so one pass that leaves five
     *  of them alive has not "mostly finished" the job — it has made the fight unwinnable. */
    private static final int CRYSTAL_SWEEPS = 3;

    /** The End main island's walking band. A body that has sunk below this is not going to walk
     *  anywhere useful — every stalled leg measured this run ended at y=52 with the island at 58+. */
    private static final int ISLAND_WALK_Y = 60;
    /** Which pass over the crystal list is running. Static because the rung is one scene at a time
     *  and the recursion that walks the list cannot carry it without threading it through every
     *  continuation; reset where the list is built. */
    private static int sweep;

    /** How long to wait for a dragon to exist before reporting that none does. */
    private static final int DRAGON_WAIT_TICKS = 600;

    /** How long the duel runs. Two hundred health at six damage a swing and one swing per twenty
     *  ticks is ~680 ticks of CONTACT; the rest of this number is the waiting, because a dragon that
     *  is flying is not a dragon that can be hit. */
    private static final int DUEL_TICKS = 200_000;
    /** Budget for walking back to (0,0) before the duel. Generous next to a crystal leg (3 000)
     *  because the body starts this walk on top of whatever tower the last crystal needed. */
    private static final int DUEL_MARCH_TICKS = 6_000;
    /** Attempts at the podium walk before the fight starts wherever the body got to. */
    private static final int DUEL_MARCH_ROUNDS = 6;
    /** How close to the podium counts as "at the centre". A 3D radius, unlike {@code Goal.XZ}.
     *
     *  <p>A radius of 6 lost a fight that had already been earned: with every crystal down the bot
     *  took the stand {@code 5,58,-1} — 5.5 blocks off-centre and TWO BELOW the platform — and the
     *  run's own result message was "for 4000 consecutive ticks the dragon never came within 4.5
     *  blocks; the fight was not too hard, the bot was not in position". A perched dragon's head
     *  sits over the fountain, so a radius wider than melee reach admits stands from which the fight
     *  is unwinnable while reporting "arrived". The radius that
     *  decides where to fight must be smaller than the reach that decides whether a hit lands. */
    private static final int DUEL_STAND_RADIUS = 2;
    /** Ticks the duel tolerates with the dragon never once inside reach before it stops waiting.
     *
     *  <p>{@link #DUEL_TICKS} is 200 000 — 2.8 hours at the server's own rate — and it is spent
     *  standing still. That is the right budget for a fight the body is IN; it is the wrong one for a
     *  body whose position the dragon's circle never passes, which is what a duel started off-centre
     *  is. Measured: 11 400 ticks off-centre with `closest` never falling to reach. Any approach
     *  resets it, so a long fight with lulls is unaffected. */
    private static final int DUEL_OUT_OF_REACH_TICKS = 20_000;

    /** Everything a shaft yields that a tower can stand on, commonest first — copied from the
     *  overworld rungs, where the lesson was learned that a tower asked for a block the body does not
     *  hold reports "out of blocks?" while the inventory is full. */
    private static final List<String> PILLAR_BLOCKS = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
            "minecraft:end_stone", "minecraft:tuff", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite");

    // The weapon list moved to JourneyRig with the two helpers that read it — this copy had already
    // drifted from the Nether one (no golden_sword, no pickaxe fallback).

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
     * part.</b> The wording here used to be "{@code BLAZE_ROD} and {@code ENDER_PEARL} are still
     * {@code unscripted}", which stopped being true — both are written, and {@code BLAZE_ROD} was
     * measured green on 2026-08-22 (two blaze rods obtained). The reason survives the correction: a
     * rung below that FAILS makes this one BLOCKED via {@link JourneyRig#enter}, so the only way to
     * arrive here empty-handed is a rung below that PASSED and still banked nothing. When that
     * happens the honest report is "the rungs below owe this one its materials", not "the driver
     * cannot craft" — a red row for somebody else's shortfall buries the row that would have said
     * something.
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
            rig.attempting("inputs not delivered: the two rungs below passed but banked nothing,"
                    + " so this rung starts empty-handed");
            ctx.skip("MISSING_INPUTS: eye of ender = blaze powder + ender pearl. Currently blaze_rod=" + rods
                    + " blaze_powder=" + powder + " ender_pearl=" + pearls
                    + " — what is missing is the output of the two rungs before this one;"
                    + " the crafting route itself is not blocked");
            return;
        }

        int want = Math.max(0, EYES_A_PORTAL_COSTS - already);
        want = Math.min(want, couldMake);
        rig.evidence("ender_eye.want", want + " (a full portal takes " + EYES_A_PORTAL_COSTS
                + ", already carrying " + already + ")");
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
        rig.attempting("craft blaze rods into blaze powder (2x2 recipe, no crafting table needed)");
        rig.drive(new CraftProcess("minecraft:blaze_powder", wantPowder), 8_000, () -> {
            rig.evidence("blaze_powder.after", rig.carrying("minecraft:blaze_powder"));
            rig.evidence("blaze_powder.craftError", String.valueOf(rig.slotError("craft")));
            then.run();
        });
    }

    private static void craftTheEyes(SceneContext ctx, JourneyRig rig, int want) {
        if (want <= 0) { judgeTheEyes(ctx, rig); return; }
        rig.attempting("craft eyes of ender: blaze powder + ender pearl");
        rig.drive(new CraftProcess("minecraft:ender_eye", want), 12_000, () -> {
            rig.evidence("ender_eye.craftError", String.valueOf(rig.slotError("craft")));
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
        rig.reach("eyes of ender x" + eyes + (shortfall == 0 ? " (enough for a full portal)"
                : " (a full portal takes " + EYES_A_PORTAL_COSTS + ", " + shortfall + " short)"));
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
        if (WorldDriverJourneyScenes.requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        rig.evidence("stronghold.baked", xyz(JourneyRoute.stronghold));
        rig.evidence("start.dimension", rig.dimension());
        rig.evidence("start.at", xyz(rig.player().blockPosition()));
        rig.evidence("ender_eye.carried", rig.carrying("minecraft:ender_eye"));
        // Only meaningful once the bot is back in the overworld. Measured from the Nether it compares
        // a nether coordinate with an overworld one across a 1:8 scale change — on 2026-08-22 it
        // printed "1774 blocks" for a bot that was ~470 nether blocks from its portal and 1731
        // overworld blocks from the stronghold, i.e. a number that is neither. So it is recorded
        // after the return.
        rig.evidence("start.dimension.awayNote",
                OVERWORLD.equals(rig.dimension()) ? "in the overworld; the distance below is comparable"
                        : "still in " + rig.dimension() + "; a straight-line distance across dimensions"
                                + " is meaningless, so it is measured after the return");

        backToTheOverworld(ctx, rig, () -> {
            rig.evidence("stronghold.away", Math.round(flatDistance(rig.player().blockPosition(),
                    JourneyRoute.stronghold)) + " blocks (measured after returning to the overworld)");
            march(ctx, rig, new Trek(JourneyRoute.stronghold, STRONGHOLD_ARRIVED_WITHIN,
                    "march", "the stronghold", () -> surveyThePortalRoom(ctx, rig)), 0);
        });
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
            // Not beside it — which is the NORMAL case, not a broken one. The rungs between the
            // doorway and here hunt blazes at a fortress and endermen in a warped forest, and on
            // 2026-08-22 that left the body 470 blocks from its own portal. The local scan is only
            // the fast path; the way home is the coordinate the entry rung banked.
            BlockPos home = JourneyLedger.netherPortal();
            rig.evidence("return.banked", xyz(home));
            if (home == null) {
                ctx.fail("cannot return to the overworld: no nether_portal block within 24 blocks,"
                        + " and this run did not record where it entered the Nether (bot at "
                        + rig.dimension() + " " + rig.player().blockPosition() + ") — the"
                        + " stronghold is in the overworld, so this rung has to walk back first."
                        + " The arrival point should have been recorded by rung 13's"
                        + " JourneyLedger.noteNetherPortal");
                return;
            }
            long away = Math.round(flatDistance(rig.player().blockPosition(), home));
            rig.attempting("walk back to the portal this run lit at " + xyz(home) + " (" + away
                    + " blocks left)");
            march(ctx, rig, new Trek(home, RETURN_ARRIVED_WITHIN, "home", "the portal this run lit",
                    () -> stepBackThrough(ctx, rig, then)), 0);
            return;
        }
        stepThroughPortal(ctx, rig, portal, then);
    }

    /**
     * Arrived at the banked doorway — now the portal itself has to actually still be there.
     *
     * <p>A second local scan rather than trusting the coordinate, because the banked position is
     * where the body CAME OUT, which is beside the portal rather than inside it, and because a
     * doorway can be gone by the time a run walks back to it (a ghast fireball, or the run's own
     * pathfinding breaking a frame block on the way past). Failing here says something different
     * from failing above, so it gets its own sentence.
     */
    private static void stepBackThrough(SceneContext ctx, JourneyRig rig, Runnable then) {
        BlockPos again = rig.nearestBlock("minecraft:nether_portal", 24);
        rig.evidence("return.portalAfterWalk", xyz(again));
        if (again != null) {
            stepThroughPortal(ctx, rig, again, then);
            return;
        }
        climbToTheDoor(ctx, rig, then);
    }

    /**
     * The march home arrived, and there is still no door in sight — because the march never looked
     * up.
     *
     * <p>{@link #march} judges arrival with {@link #flatDistance} and steers with {@code Goal.XZ}:
     * y-blind by construction, which is right for a surface trek to an XZ target and wrong for the
     * one target in this rung that is a specific CELL. The confirmation scan is a 3D radius, so the
     * two disagree, and the disagreement is silent — measured 2026-08-22 on rung 17's first
     * rehearsal:
     *
     * <pre>{@code
     * return.banked   104, 93, 7      home.3   99, 41, 11   6 blocks from the portal
     * return.portalAfterWalk none
     * }</pre>
     *
     * <p>Six blocks away and fifty-two below. A message of "the portal was destroyed, or the recorded
     * arrival point is too far from it" would be true in every word with neither cause correct, and
     * it names the two things a reader would then go and check. A bot that has walked to the right
     * column has not walked to the portal.
     *
     * <p>So the flat march gets a 3D finish: one settle on {@code Goal.Near}, which is a sphere and
     * therefore does include y, letting the pathfinder close a gap it already knows how to close.
     * Only if THAT fails is the door genuinely out of reach — and then the message says so with the
     * vertical gap in it, so the next reader is not sent after a destroyed portal that is standing.
     */
    private static void climbToTheDoor(SceneContext ctx, JourneyRig rig, Runnable then) {
        BlockPos home = JourneyLedger.netherPortal();
        BlockPos at = rig.player().blockPosition();
        int dy = home.getY() - at.getY();
        rig.evidence("return.verticalGap", "horizontally arrived (" + Math.round(flatDistance(at, home))
                + " blocks), but the portal is at y=" + home.getY() + " and the bot at y=" + at.getY()
                + ", " + dy + " blocks apart — the march judges horizontal distance while the portal"
                + " check uses a 24-block sphere, and when the two disagree the result is"
                + " 'arrived but no portal'");
        climbLeg(ctx, rig, home, 1, (int) Math.round(Math.sqrt(at.distSqr(home))), then);
    }

    /** How many tries the last stretch gets. Not one — see {@link #climbLeg}. */
    private static final int CLIMB_LEGS = 5;

    /**
     * One try at the last stretch, then a different try, until the door is in scan range.
     *
     * <p><b>Why this is not a single settle.</b> It was, and two rehearsals off the same staged
     * world, from a byte-identical body position, went opposite ways:
     *
     * <pre>{@code
     * start 99,41,11 portal 104,93,7   second run → 121, 75, 10  (up 34, portal found by the scan)
     * start 99,41,11 portal 104,93,7   third run  → 100, 23, 20  (down 18, not found by the scan)
     * }</pre>
     *
     * <p>Same state, same goal, opposite outcome — the walker's per-tick search budget is spent
     * against a wall-clock slice, so the route it has found when the settle ends is not a function
     * of the world alone. A stretch that gets exactly one attempt against a nondeterministic search
     * is a coin flip, and reporting a coin flip as "cannot climb up" names the wrong thing.
     *
     * <p><b>And the legs have to differ, or it is the same refused question five times</b> — the
     * shape {@code a-retry-that-changes-nothing} is about. Two things vary. The scan happens after
     * EVERY leg rather than only the last, which matters more than it looks: the radius is 24 and
     * the second rehearsal found the door from 24.3 blocks away, so a body that passes through
     * range mid-climb and drifts out again used to throw that away. And a leg that ends no closer
     * than it began, with the door overhead, stops asking the pathfinder and pillars up instead —
     * {@code TowerProcess} builds the route rather than searching for one, which is the answer when
     * the terrain genuinely has no way up.
     */
    private static void climbLeg(SceneContext ctx, JourneyRig rig, BlockPos home, int leg, int best,
                                 Runnable then) {
        BlockPos at = rig.player().blockPosition();
        BlockPos found = rig.nearestBlock("minecraft:nether_portal", 24);
        int away = (int) Math.round(Math.sqrt(at.distSqr(home)));
        if (found != null) {
            rig.evidence("return.portalAfterClimb", xyz(found) + " (found by the scan after attempt "
                    + leg + ", bot at " + xyz(at) + ", " + away + " blocks from the portal)");
            stepThroughPortal(ctx, rig, found, then);
            return;
        }
        if (leg > CLIMB_LEGS) {
            rig.evidence("return.portalAfterClimb", "none (after " + CLIMB_LEGS + " attempts the bot is at "
                    + xyz(at) + ", " + away + " blocks from the portal; closest reached was " + best
                    + " blocks)");
            if (Math.abs(home.getY() - at.getY()) > RETURN_ARRIVED_WITHIN) {
                ctx.fail("walked back to the recorded column but cannot reach the portal itself: the"
                        + " portal is at " + xyz(home) + ", the bot stopped at " + xyz(at) + ", still "
                        + Math.abs(home.getY() - at.getY()) + " blocks apart vertically, and none of the "
                        + CLIMB_LEGS + " attempts (pillaring included) closed the gap. The portal was not"
                        + " destroyed and the walk back did not fail — the final stretch could not be"
                        + " climbed or descended; the position after each attempt is in return.climb.*");
                return;
            }
            ctx.fail("stood next to the recorded arrival point " + xyz(home) + " (bot at " + xyz(at)
                    + ", vertical gap already closed), and there is still no nether_portal block within"
                    + " 24 blocks — the portal is genuinely gone: either it was destroyed, or the"
                    + " coordinate rung 13 recorded is wrong");
            return;
        }
        // A leg that gained nothing and a door overhead is the one case where asking again is
        // pointless and building is not. Below the door only: TowerProcess climbs, it cannot descend.
        boolean stalled = leg > 1 && away >= best;
        boolean overhead = home.getY() - at.getY() > RETURN_ARRIVED_WITHIN;
        BotProcess run;
        String what;
        if (stalled && overhead) {
            String pillar = pillarBlock(rig);
            run = new TowerProcess(home.getY(), pillar, true);   // reachIntoBag — see JourneyShaft's note
            what = "the previous attempt got no closer; pillar up to y=" + home.getY() + " (using "
                    + pillar + ")";
        } else {
            run = new IntentProcess(new Intent(new Goal.Near(home, RETURN_ARRIVED_WITHIN)));
            what = "close in on the portal cell (3D goal; the march only handles the horizontal plane)";
        }
        rig.attempting("attempt " + leg + "/" + CLIMB_LEGS + ": " + what + " → " + xyz(home));
        rig.settle(run, MARCH_LEG_TICKS, () -> {
            BlockPos now = rig.player().blockPosition();
            int ended = (int) Math.round(Math.sqrt(now.distSqr(home)));
            rig.evidence("return.climb." + leg, what + ": " + xyz(at) + " → " + xyz(now)
                    + " (distance to the portal " + away + " → " + ended + " blocks)"
                    + JourneyLeg.walkerEnd(rig));
            climbLeg(ctx, rig, home, leg + 1, Math.min(best, ended), then);
        });
    }

    /**
     * Stand in the doorway and be taken by it — driven by {@link JourneyPortalEntry#crossThrough},
     * because rung 13 has already paid for every mistake this used to make.
     *
     * <p><b>What it used to be, and why that could never work.</b> A {@code settle} onto the portal
     * cell followed by an {@code await} for the dimension to change. Both halves look right and the
     * pair is inert: {@code settle} ends — and unregisters the driver — the instant its process
     * reports finished, and an {@code IntentProcess} already at its goal finishes on tick one. So
     * the body was placed in the doorway and then stopped being ticked, and vanilla only notices a
     * portal through {@code Entity.move → checkInsideBlocks → NetherPortalBlock.entityInside}, a
     * one-tick flag that nothing but a {@code move()} re-arms. Measured on rung 17's first two
     * rehearsals — the body stood INSIDE a {@code nether_portal} block for the entire 1600-tick
     * wait and was never taken:
     *
     * <pre>{@code
     * return.portalAfterClimb = 105, 93, 7   (at the end the bot was at 121, 75, 10, 25 blocks away)
     * return.at               = 105, 93, 7   the cell it stood in is Block{minecraft:nether_portal}
     * }</pre>
     *
     * <p>Rung 13 hit exactly this, diagnosed it, and fixed it with {@link HoldStill} — a process
     * that does nothing and <em>keeps being ticked</em>. Writing the wait a second time meant
     * writing the bug a second time, so the second copy is gone and both directions share one
     * driver. The departure world is read here rather than named, which is what the crossing
     * actually needs to watch: {@code changeDimension} has put a body somewhere unexpected before,
     * and "is it still where it started" stays answerable wherever it lands.
     */
    private static void stepThroughPortal(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                          Runnable then) {
        rig.attempting("step back through the portal this run lit, to the overworld");
        final String leaving = rig.dimension();
        JourneyPortalEntry.crossThrough(ctx, rig, portal,
                new JourneyPortalEntry.Crossing(leaving, () -> {
            rig.evidence("return.dimension", rig.dimension());
            BlockPos now = rig.player().blockPosition();
            rig.evidence("return.at", xyz(now));
            // The crossing only promises the body LEFT. Where it landed is this rung's problem: the
            // stronghold is in the overworld, and a body that came out somewhere else would go on
            // to march hundreds of blocks through the wrong world before anything noticed.
            ctx.expect(rig.dimension()).as("after stepping through the portal this run lit, the bot"
                            + " must arrive in the overworld")
                    .isEqualTo(OVERWORLD);
            // The mirror of rung 13's check, and this direction MULTIPLIES by 8 where that one
            // divides. Asserting only the dimension is what let a run report a clean crossing while
            // standing in an aquifer 1500 blocks from where the pair should have put it — the
            // dimension was right, so nothing objected, and the march spent its whole budget
            // walking out of the hole. Tolerance is the forcer's own horizontal search radius:
            // landing up to 128 away is vanilla doing its job, further is a different doorway.
            int wantX = portal.getX() * 8, wantZ = portal.getZ() * 8;
            int drift = Math.max(Math.abs(now.getX() - wantX), Math.abs(now.getZ() - wantZ));
            rig.evidence("return.scaledXZ", wantX + "," + wantZ + " (drift " + drift + " blocks)");
            ctx.expect(drift).as("the return trip must also land near the 8:1 scaled point, not at"
                            + " an arbitrary portal")
                    .isAtMost(128);
            then.run();
        }));
    }

    /**
     * One long walk to a fixed column: where to, how close counts, what to call the rows, what next.
     *
     * <p>Parameterised because there are two of these and they were one hardcoded route. The march
     * to the stronghold and the walk back to the run's own portal are the same problem — cross
     * hundreds of blocks in bounded legs, notice when a leg goes nowhere, step sideways rather than
     * ask the same refused question again — and the second one arrived when rung 17 turned out to
     * need the doorway it came in through.
     */
    private record Trek(BlockPos goal, int arriveWithin, String key, String what, Runnable onArrive) {}

    /**
     * One leg of the march, then the next, until the stronghold's column is underfoot.
     *
     * <p>Recursive rather than looped, and that is not a style choice: each leg is its own
     * {@code await} step, so the recursion queues a step and returns rather than nesting a stack.
     * The body has to actually walk between legs, and a loop inside one scene tick would plan
     * twenty-eight routes in a world that never advanced.
     */
    private static void march(SceneContext ctx, JourneyRig rig, Trek trek, int leg) {
        march(ctx, rig, trek, leg, 0);
    }

    /** @param stuck how many legs in a row have gone nowhere — see {@link #sidestep}, which needs it
     *               to ask a DIFFERENT question each time rather than the same one again. */
    private static void march(SceneContext ctx, JourneyRig rig, Trek trek, int leg, int stuck) {
        BlockPos goal = trek.goal();
        BlockPos at = rig.player().blockPosition();
        double away = flatDistance(at, goal);
        rig.evidence(trek.key() + "." + leg, xyz(at) + ", " + Math.round(away) + " blocks from "
                + trek.what());
        if (away <= trek.arriveWithin()) {
            rig.evidence(trek.key() + ".legs", leg);
            trek.onArrive().run();
            return;
        }
        if (leg >= MAX_MARCH_LEGS) {
            ctx.fail("cannot reach " + trek.what() + ": after " + MAX_MARCH_LEGS + " march segments"
                    + " the bot is still at " + at + ", " + Math.round(away) + " blocks from "
                    + xyz(goal) + " (each segment is " + MARCH_LEG_BLOCKS + " blocks / "
                    + MARCH_LEG_TICKS + " ticks; the position after each segment is in "
                    + trek.key() + ".*)");
            return;
        }
        double f = Math.min(1.0, MARCH_LEG_BLOCKS / away);
        int wx = (int) Math.round(at.getX() + (goal.getX() - at.getX()) * f);
        int wz = (int) Math.round(at.getZ() + (goal.getZ() - at.getZ()) * f);
        rig.attempting("march toward " + trek.what() + ": segment " + leg + ", heading for " + wx + ","
                + wz);
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(wx, wz, MARCH_LEG_TOLERANCE))),
                MARCH_LEG_TICKS, () -> {
            BlockPos now = rig.player().blockPosition();
            // MOVED decides whether to sidestep; CLOSED decides whether the wedge streak is over.
            // They are not the same question, and conflating them made the escalation below dead
            // code. Measured 2026-08-22: `stuck` reset on "moved ≥ 4 blocks", and a sidestep MOVES
            // THE BODY 24 BLOCKS by construction — so the recovery satisfied its own counter's
            // reset condition every time, no streak ever reached 3, and the ±135°/±45° turns and
            // the widening reach never once executed. The body oscillated inside a 20-block pocket
            // (x −794…−816, z 1060…1097) for 22 legs and the march died 412 blocks out.
            // Closing on the goal is the only movement that proves the wedge was escaped.
            boolean closed = away - flatDistance(now, goal) >= WEDGED_UNDER;
            if (flatDistance(at, now) >= WEDGED_UNDER) {
                march(ctx, rig, trek, leg + 1, closed ? 0 : stuck);
                return;
            }
            sidestep(ctx, rig, trek, leg, now, stuck);
        });
    }

    /**
     * A leg that went nowhere does not simply repeat.
     *
     * <p>The re-plan assumes each attempt starts somewhere better, and usually it does — but a body
     * can also be WEDGED, and then three attempts are three identical searches with three identical
     * refusals, each burning a leg's whole budget. Stepping sideways asks the pathfinder a question
     * it has not already answered. That is what a player does when a route will not come, and it
     * needs nothing from the engine.
     *
     * <p><b>Which is why the offset has to depend on how many times this has already failed.</b> It
     * used to be a fixed perpendicular computed from {@code at} and {@code goal} alone — and both of
     * those are unchanged precisely when the body has not moved, so every retry produced the
     * identical target. Measured 2026-08-22, rung 17: legs 37 through 48 all sat at
     * {@code -1076,67,1260} and all stepped to {@code -1085,1238}, twelve times, with the identical
     * {@code best dist=970} refusal, until the march ran out of legs 99 blocks short of the
     * stronghold. The paragraph above claims the sidestep "asks a question it has not already
     * answered", and with a fixed offset that is true of the first one and false of the eleven
     * after it.
     *
     * <p>{@code stuck} therefore turns the offset: 90° off the goal bearing, then −90°, then ±135°,
     * then ±45°, widening by {@link #SIDESTEP_BLOCKS} each full cycle. The FIRST attempt is
     * arithmetically identical to what it always was, so a body that used to escape on its first
     * sidestep still does, on the same cell, by the same route.
     */
    private static final int[] SIDESTEP_TURNS = {90, -90, 135, -135, 45, -45};

    private static void sidestep(SceneContext ctx, JourneyRig rig, Trek trek, int leg, BlockPos at,
                                 int stuck) {
        BlockPos goal = trek.goal();
        double bearing = Math.atan2(goal.getZ() - at.getZ(), goal.getX() - at.getX());
        int turn = SIDESTEP_TURNS[stuck % SIDESTEP_TURNS.length];
        int reach = SIDESTEP_BLOCKS * (1 + stuck / SIDESTEP_TURNS.length);
        double aim = bearing + Math.toRadians(turn);
        int sx = (int) Math.round(at.getX() + Math.cos(aim) * reach);
        int sz = (int) Math.round(at.getZ() + Math.sin(aim) * reach);
        rig.evidence(trek.key() + "." + leg + ".wedged", xyz(at) + " did not move this segment ("
                + (stuck + 1) + " in a row); turn " + turn + "° and step " + reach + " blocks sideways to "
                + sx + "," + sz + " (goto " + JourneyLeg.walkerEnd(rig) + ")");
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(sx, sz, 3))), MARCH_LEG_TICKS / 2,
                () -> march(ctx, rig, trek, leg + 1, stuck + 1));
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
        rig.attempting("find the portal room in the stronghold");
        List<BlockPos> frames = framesAround(level, JourneyRoute.stronghold, ROOM_SCAN_CHUNKS);
        rig.evidence("frames.found", frames.size());
        if (frames.isEmpty()) {
            ctx.fail("reached the stronghold coordinate but the scan finds no end portal frame:"
                    + " end_portal_frame = 0 within " + ROOM_SCAN_CHUNKS + " chunks of "
                    + xyz(JourneyRoute.stronghold) + ". Either the hard-coded coordinate is stale"
                    + " (rerun wd.journey01Recon and read its stronghold row), or this seed's"
                    + " stronghold is not here");
            return;
        }
        BlockPos centre = centreOf(frames);
        int withEye = 0;
        for (BlockPos f : frames) if (hasEye(level, f)) withEye++;
        rig.evidence("frames.centre", xyz(centre));
        rig.evidence("frames.withEye", withEye + "/" + frames.size()
                + " (generated with the world, not placed by this run)");

        BlockPos stand = standingCellInTheRoom(level, centre);
        rig.evidence("room.standing", xyz(stand));
        if (stand == null) {
            ctx.fail("found the frames (" + xyz(centre) + ") but no cell to stand in: no cell "
                    + ROOM_STAND_MIN + "–" + ROOM_STAND_SEARCH + " blocks from the centre of the room is"
                    + " 'empty, with empty headroom and a solid, non-fluid floor'");
            return;
        }
        digToTheRoom(ctx, rig, stand, centre);
    }

    private static void digToTheRoom(SceneContext ctx, JourneyRig rig, BlockPos stand, BlockPos centre) {
        ServerLevel level = levelOf(rig);
        int surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(stand.getX(), 0, stand.getZ())).getY();
        rig.evidence("shaft.top", stand.getX() + "," + surface + "," + stand.getZ()
                + " → room y=" + stand.getY());
        WorldDriverJourneyScenes.walkToColumn(rig, "shaftTop", stand.getX(), stand.getZ(), 2,
                12_000, WorldDriverJourneyScenes.MAX_WALK_ATTEMPTS, () -> {
            // The shaft must not be bridged over. A walker allowed to place while descending fills
            // in the hole it is standing in, which reads as "the block broke but the body did not
            // sink" — the same confusion the overworld shafts already had to disarm.
            BotConfig.allowPlace = false;
            int depth = Math.max(0, rig.player().blockPosition().getY() - stand.getY());
            int cap = depth * 4 + 40;
            rig.evidence("shaft.depth", depth + " blocks, allowed " + cap + " attempts");
            digDownTo(ctx, rig, stand.getY(), cap, cap, () -> {
                BotConfig.allowPlace = true;
                judgeTheRoom(ctx, rig, centre);
            });
        }, () -> ctx.fail("cannot reach the surface directly above the portal room: target "
                + stand.getX() + "," + stand.getZ() + " (surface y=" + surface + "), stopped at "
                + rig.player().blockPosition()));
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
        rig.evidence("room.nearestFrame", nearest == null ? "none"
                : xyz(nearest) + ", " + Math.round(away) + " blocks away");
        rig.evidence("room.frameCentre", xyz(centre));
        rig.noteAdvancement("minecraft:story/follow_ender_eye");
        ctx.expect(nearest != null && away <= REACHED_ROOM_WITHIN)
                .as("the body is standing in the portal room, within reach of the frame").isTrue();
        rig.reach("walked to the stronghold at " + xyz(JourneyRoute.stronghold) + " and descended into"
                + " the portal room, landing at " + xyz(at) + ", nearest frame " + Math.round(away)
                + " blocks away");
    }

    // =====================================================================================
    // 18 — the end portal. Twelve useOn-only interactions, and nothing else.
    // =====================================================================================

    /**
     * Set an eye into every empty frame until the portal opens.
     *
     * <p><b>The verb is the whole rung.</b> {@code EnderEyeItem} overrides {@code useOn(UseOnContext)}
     * and has no {@code use} at all, so the eye must go through {@code Body.useBlock(cell, face)};
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
        rig.evidence("frames.inReach", frames.size() + " (within a cube of radius " + FRAME_SEARCH + ")");
        rig.evidence("ender_eye.before", eyes);
        if (frames.isEmpty()) {
            ctx.fail("no end_portal_frame within " + FRAME_SEARCH + " blocks — STRONGHOLD reported"
                    + " reaching the portal room, but the scan here finds no frame at all (bot at " + at
                    + ", standing on " + blockAt(rig, at.below()) + ")");
            return;
        }

        List<BlockPos> empty = new ArrayList<>();
        for (BlockPos f : frames) if (!hasEye(level, f)) empty.add(f);
        empty.sort(Comparator.comparingDouble(at::distSqr));
        rig.evidence("frames.empty", empty.size());
        if (eyes < empty.size()) {
            rig.evidence("eyes.short", (empty.size() - eyes) + " short — EYE_OF_ENDER delivered only "
                    + eyes + ", and there are " + empty.size() + " empty frames");
        }
        rig.attempting("place one eye of ender into each of the " + empty.size() + " empty frames"
                + " (useBlock, not useItemInHand)");
        setOneEye(ctx, rig, empty, 0, frames);
    }

    private static void setOneEye(SceneContext ctx, JourneyRig rig, List<BlockPos> todo, int i,
                                  List<BlockPos> all) {
        if (i >= todo.size()) { judgeThePortal(ctx, rig, all); return; }
        BlockPos frame = todo.get(i);
        if (rig.carrying("minecraft:ender_eye") < 1) {
            rig.evidence("eyes.ranOutAt", xyz(frame) + " (empty frame index " + i + ")");
            judgeThePortal(ctx, rig, all);
            return;
        }
        // Walk into reach first. useBlock would take the click from across the room — see the class
        // note on why this file will not let it.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(frame, EYE_REACH))), 600, () -> {
            ServerLevel level = levelOf(rig);
            // Both bodies: `useBlock` reaches the frame through `handleUseItemOn`, which reads the
            // SERVER's hand. See JourneyHands.holdBoth.
            boolean held = JourneyHands.holdBoth(rig, Items.ENDER_EYE);
            if (!held) {
                rig.evidence("eye." + i + ".hand", "could not hold ender_eye; holding " + heldItem(rig));
            }
            double reach = Math.sqrt(rig.player().blockPosition().distSqr(frame));
            rig.hands().useBlock(frame, Direction.UP);
            rig.settle(new HoldStill(2), 10, () -> {
                if (!hasEye(level, frame)) {
                    rig.evidence("eye." + i + ".missed", xyz(frame) + " is still "
                            + level.getBlockState(frame) + " (holding " + heldItem(rig)
                            + ", " + String.format(Locale.ROOT, "%.1f", reach) + " blocks away)");
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
        rig.reach("frames filled " + filled + "/" + frames.size() + ", end portal opened with " + cells
                + " cells, entrance at " + xyz(doorway));
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
            rig.attempting("this runtime has no End dimension");
            ctx.skip("NO_END_DIMENSION: this runtime has no minecraft:the_end (removed by a data pack),"
                    + " so there is nowhere to go");
            return;
        }
        ServerLevel level = levelOf(rig);
        BlockPos at = rig.player().blockPosition();
        List<BlockPos> doorways = portalCellsNear(level, at, PORTAL_SEARCH);
        rig.evidence("from", xyz(at));
        rig.evidence("portal.cells", doorways.size());
        if (doorways.isEmpty()) {
            ctx.fail("no end_portal block within " + PORTAL_SEARCH + " blocks — END_PORTAL reported the"
                    + " portal open, but none can be found here (bot at " + at + ")");
            return;
        }
        rig.attempting("walk into the end portal and wait for it to transfer the bot");
        stepIn(ctx, rig, doorways, 0);
    }

    private static void stepIn(SceneContext ctx, JourneyRig rig, List<BlockPos> doorways, int attempt) {
        if (THE_END.equals(rig.dimension())) { judgeTheCrossing(ctx, rig); return; }
        if (attempt >= doorways.size() || attempt >= MAX_PORTAL_STEPS) {
            BlockPos at = rig.player().blockPosition();
            rig.evidence("stand.at", xyz(at));
            rig.evidence("stand.in", blockAt(rig, at));
            rig.evidence("stand.goto", JourneyLeg.walkerEnd(rig));
            ctx.fail("cannot walk into the end portal: tried " + attempt + " portal cells, the bot is"
                    + " still at " + rig.dimension() + " " + at + ". The position after each attempt is"
                    + " in step.* (the stronghold portal sits over a lava pool, so walking into it and"
                    + " standing beside it are different outcomes)");
            return;
        }
        BlockPos cell = doorways.get(attempt);
        // WHY THIS ONE MAY WAIT UNDRIVEN AND THE NETHER ONE MAY NOT. `waitFor` is `rig.await`, which
        // ticks the SCENE and not the BODY — no process is registered, so no `move()`, so vanilla's
        // one-tick portal flag is never re-armed. An End portal survives that only because its
        // transition time is ZERO: the flag is armed by the walk's own last `move()` and consumed on
        // that same tick, before `settle` unregisters anything. A nether portal's is 80, which is
        // 80 ticks this loop would not supply — see stepThroughPortal, where writing this shape a
        // second time cost two rehearsals. So the resemblance is a coincidence, not a pattern to
        // copy: the thing that makes it safe here is a constant nothing in this file controls.
        rig.settle(new IntentProcess(new Intent(new Goal.Block(cell))), PORTAL_WALK_TICKS,
                () -> waitFor(rig, () -> THE_END.equals(rig.dimension()), PORTAL_TRANSIT_TICKS, () -> {
                    rig.evidence("step." + attempt, "aimed at " + xyz(cell) + " → stopped at "
                            + xyz(rig.player().blockPosition()) + " (" + rig.dimension() + ")");
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
     * <p><b>And Y alone would not have been enough either.</b> "Y is correct" and "something is
     * underfoot" are different claims: the bot can be at y=49 in the instant it steps off the
     * platform's edge, and it can be at y=49 over a hole. So the third quantity is the block
     * underfoot, read through {@code blocksMotion()} rather than {@code onGround} — this bot's
     * {@code onGround} is wrong in both directions, and {@code void_air} is what
     * {@code getBlockState} returns for anything below the build limit, so it cannot be told from
     * "no platform" by name alone.
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
        rig.evidence("underfoot", blockAt(rig, now.below()) + " (blocks motion: " + standing + ")");
        rig.evidence("spawnPoint", xyz(want) + " (horizontal drift " + drift + " blocks, vertical offset "
                + off + " blocks)");
        rig.evidence("platform.obsidian", obsidian + "/25 obsidian blocks in the layer at " + xyz(floor)
                + " — 25 = the platform was built (so the bot left it on its own), 0 = the platform"
                + " was never built (so this is a dimension-transfer defect in the driver, not a"
                + " walking problem)");
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
        rig.reach("crossed from the stronghold portal to the End, landing at " + xyz(now)
                + " (END_SPAWN_POINT " + xyz(want) + ", horizontal " + drift + " blocks, vertical " + off
                + " blocks, standing on " + blockAt(rig, now.below()) + ", platform " + obsidian + "/25)");
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
     * blocks up its own obsidian pillar. {@code Body.attackEntity} would break one from the ground —
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
            ctx.fail("not in the End: END reported arriving, but the dimension here reads "
                    + rig.dimension() + " (bot at " + rig.player().blockPosition() + ")");
            return;
        }
        ServerLevel end = levelOf(rig);
        rig.evidence("start.at", xyz(rig.player().blockPosition()));
        rig.evidence("weapon.best", rig.bestWeaponOwned());
        rig.evidence("blocks.forBridging", pillarBlock(rig) + " ×" + rig.carrying(pillarBlock(rig)));
        recordTheFight(rig, end);

        rig.attempting("bridge from the arrival platform to the centre of the main island");
        marchInTheEnd(ctx, rig, 0);
    }

    /**
     * What vanilla's own bookkeeping says about this fight, read before anything is attempted —
     * because "there is no dragon" has a cause that lives here rather than in the combat loop.
     *
     * <p><b>Every row written here is a START-OF-RUNG reading, and now says so in its own text.</b>
     * At that instant the fight has usually not scanned even once: {@code EndDragonFight.tick} takes
     * the arena ticket, runs {@code scanState}, {@code findOrCreateDragon} and
     * {@code updateCrystalCount} <i>only</i> while {@code dragonEvent.getPlayers()} is non-empty, and
     * that set is refilled every twenty ticks from {@code level.getPlayers(validPlayer)}. So
     * {@code crystalsAlive = 0} and {@code dragonUUID = null} here are the <b>healthy</b> reading —
     * "not counted yet, not created yet", not "no crystals, no dragon". Measured 2026-08-17: this ran
     * at 10:49:07 and vanilla logged "Scanning for legacy world dragon fight…" in the same second,
     * i.e. immediately after it, and by the end of that run the same fight held
     * {@code crystalsAlive = 5} and a real {@code dragonUUID}.
     *
     * <p>The failure-time half is {@link #recordTheFightNow}, under {@code dragonFight.now.*}. The
     * two moments must keep <b>separate keys</b>: {@link JourneyRig#evidence} is a map put, so one
     * shared key would silently keep only the later reading — and a row that exists only at failure
     * cannot say what healthy looked like.
     */
    private static void recordTheFight(JourneyRig rig, ServerLevel end) {
        rig.evidence("level.realPlayers", end.players().size());
        recordTheFight(rig, end, "at rung start", "");
    }

    /** The same three readings taken again at the moment of the failure, under {@code now.} keys. */
    private static void recordTheFightNow(JourneyRig rig, ServerLevel end) {
        recordTheFight(rig, end, "at failure", "now.");
    }

    private static void recordTheFight(JourneyRig rig, ServerLevel end, String when, String key) {
        String at = "[" + when + "] ";
        var fight = end.getDragonFight();
        if (fight == null) {
            rig.evidence("dragonFight." + key + "absent", at + "none — this End has no EndDragonFight");
            return;
        }
        rig.evidence("dragonFight." + key + "crystalsAlive", at + fight.getCrystalsAlive()
                + " (only EndDragonFight.updateCrystalCount writes this number, and it runs once every"
                + " 100 ticks, only while the fight ticks with a valid player and the arena loaded —"
                + " 0 can mean 'not counted yet', which is not the same as 'no crystals')");
        rig.evidence("dragonFight." + key + "previouslyKilled", at + fight.hasPreviouslyKilledDragon()
                + " (written by scanState, which likewise needs a valid player first)");
        rig.evidence("dragonFight." + key + "dragonUUID", at + fight.getDragonUUID()
                + " (non-null = createNewDragon has run and the dragon exists)");
    }

    /**
     * The End's own build floor. Its dimension type is {@code min_y = 0}, so a bot below this is not
     * "low" — it is outside the world, in free fall, and every further segment is an order issued to
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
     * {@code island.N.plan} carries {@code placed K blocks}, {@code pathLen}, the name of the move entering
     * the current node, and the run's own verdict. The three read differently — {@code K > 0} is the
     * footing fault; {@code K = 0} with a bridge move planned is the ordering fault; {@code K = 0}
     * with {@code move=walk} over void, or with no path at all while the body still moved, is the
     * planner fault.
     *
     * <p><b>And the march now stops at the first leg that starts in the void.</b> Six wasted legs cost
     * this run 33 minutes and produced six copies of one fact. The guard cannot rescue anything — it
     * only fails sooner, in words that name the void rather than "cannot reach the centre of the
     * main island".
     */
    private static void marchInTheEnd(SceneContext ctx, JourneyRig rig, int leg) {
        BlockPos at = rig.player().blockPosition();
        double away = Math.hypot(at.getX(), at.getZ());
        String pillar = pillarBlock(rig);
        int stock = rig.carrying(pillar);
        rig.evidence("island." + leg, xyz(at) + ", " + Math.round(away) + " blocks from the centre,"
                + " standing on " + blockAt(rig, at.below()) + ", " + pillar + " x" + stock);
        if (at.getY() < END_VOID_BELOW) { fellOffTheIsland(ctx, rig, leg, at, pillar, stock); return; }
        if (away <= 8) {
            rig.evidence("island.legs", leg);
            gatherTheCrystals(ctx, rig);
            return;
        }
        if (leg >= 8) {
            rig.evidence("island.reached", false);
            ctx.fail("cannot reach the centre of the main island: after " + leg + " segments the bot"
                    + " is still at " + at + ", " + Math.round(away) + " blocks from the centre. There"
                    + " is void between the arrival platform and the main island, so crossing it needs"
                    + " a bridge — the bot carries " + pillar + " x" + stock + ", allowPlace="
                    + BotConfig.allowPlace + ". Which segment spent blocks and which had no plan at"
                    + " all is in island.*.plan");
            return;
        }
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(0, 0, 6))), 6_000, () -> {
            BlockPos now = rig.player().blockPosition();
            rig.evidence("island." + leg + ".plan", planOf(rig, pillar, stock));
            if (flatDistance(at, now) >= WEDGED_UNDER) { marchInTheEnd(ctx, rig, leg + 1); return; }
            rig.evidence("island." + leg + ".wedged",
                    xyz(now) + " did not move this segment (goto " + JourneyLeg.walkerEnd(rig) + ")");
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
     *
     * <p><b>{@code canPlace}/{@code placeableBlockCount} are printed because they are the only pair
     * that separates the two ways a bridge fails to be PLANNED.</b> Every other row here describes
     * what the body did; these two describe what the search was allowed to consider.
     * {@code BridgePlace.eval} opens with {@code if (!w.canPlace()) return null}, and
     * {@link net.magicterra.worlddriver.bot.world.LevelWorldView#placeableBlockCount} counts only
     * the HOTBAR, and only items {@code BotConfig.isUsableBuildBlock} accepts — so a body carrying
     * 1024 cobblestone in its backpack reads {@code canPlace=false} and no bridge edge is ever
     * generated. That is a different defect from a bridge edge that IS generated and then loses on
     * price: a {@code parkour3} across the same two cells costs 32, while the bridge chain costs
     * {@code 20+20+10}-ish per cell and lands near 170, so the leap wins by more than five to one
     * and "the planner never intended to bridge" is true for two unrelated reasons. Without this
     * pair the two are one row.
     */
    private static String planOf(JourneyRig rig, String pillar, int stockBefore) {
        // ⚠️ THIS ROW HAS TWO CHANNELS, and on the integrated helm only one of them has an author.
        // `rig.slot` reads whichever half is driving; `slot` below is the SERVER's BotState, which
        // nothing writes when the process was handed to the client (JourneyRig.slot says why). The
        // three fields taken from it — pathMove, parkourTakeoff, firstPlan — are excluded from
        // ProcessSlot.snapshot() by design, so there is no routed reading to take instead. Read
        // `move=` / `first takeoff` / `first plan` as "this half cannot read it" there, never as
        // "there was none". They become readable once status() carries them; the other four are
        // correct now.
        var routed = rig.slot("goto");
        boolean active = Boolean.TRUE.equals(routed.get("active"));
        var slot = rig.body().botState().mc_goto;
        var view = rig.body().world();
        return "placed " + blocksSpent(rig, pillar, stockBefore) + " blocks of " + pillar
                + "; active=" + active
                + " pathLen=" + routed.get("pathLen") + " move=" + slot.pathMove
                + " end=" + routed.get("endReason") + " err=" + routed.get("lastError")
                + (active ? "" : " (the process has ended, so pathLen/move are the empty values left"
                        + " by reset; do not read them as 'there was no plan at all')")
                // ⚠️ pathLen/move above are the state at the END of the leg, and a leg that fell out
                // of the world spends most of itself in the void — where BridgePlace.eval's every
                // premise holds, because it deliberately does not check for support underfoot. So
                // that half describes the planning of a falling body. The place tally's FIRST rows
                // are the ones taken while there was still ground under the question.
                + "; first takeoff " + slot.parkourTakeoff
                + "; first plan " + slot.firstPlan
                + "; place " + rig.body().avatar().placeTally()
                + "; canPlace=" + view.canPlace()
                + " placeableBlockCount=" + view.placeableBlockCount()
                + " (counts only buildable blocks in the hotbar — the backpack does not count, so 0"
                + " means this segment could not generate a bridge edge at all, which is a different"
                + " defect from 'generated, but outpriced by parkour3 at 32')";
    }

    /**
     * The bot is under the End's build floor: stop, and hand the reader the fork rather than a
     * distance.
     *
     * <p>Without this check the failure would read "cannot reach the centre of the main island"
     * after eight segments. That sentence is true and useless — it names the goal instead of naming that
     * the bot left the only ground there was, and it arrives half an hour late.
     */
    private static void fellOffTheIsland(SceneContext ctx, JourneyRig rig, int leg, BlockPos at,
                                         String pillar, int stock) {
        rig.evidence("island.fellAt", xyz(at) + " (the End's build floor is y=" + END_VOID_BELOW
                + ", so this is the void, not merely 'low')");
        // The staging measured this once, before anything moved. It is wrong by now and the whole
        // point of re-reading it here is that the stale one reads like an all-clear.
        rig.evidence("dragon.rangeNow", fightRangeNow(rig));
        ctx.fail("fell out of the End: at the start of segment " + leg + " the bot was already at "
                + xyz(at) + ", below the End's build floor y=" + END_VOID_BELOW + " — this is the void,"
                + " not slow walking. The arrival platform is 5x5 and the main island is "
                + Math.round(Math.hypot(at.getX(), at.getZ())) + " blocks away, so the gap needs a"
                + " bridge; the bot still carries " + pillar + " x" + stock + ", allowPlace="
                + BotConfig.allowPlace + ". island.*.plan tells which failure it was: placed >0"
                + " blocks = placed but not stepped onto (footing criterion); placed 0 blocks with a"
                + " bridge in the plan = stepped out without placing (execution order); placed 0"
                + " blocks with move=walk or no path at all = the pathfinder never intended to bridge"
                + " (cost/passability criterion). ⚠️ rehearsal.fightRange was measured during test"
                + " setup; the current distance is in dragon.rangeNow");
    }

    /** How far the body is from the dragon fight's own centre, <b>right now</b>. {@code EndDragonFight}
     *  builds {@code validPlayer} as {@code EntitySelector.withinDistance(0, 128, 0, 192.0)} and its
     *  {@code tick()} does nothing at all while no valid player is in range — so this number, taken at
     *  the moment of the failure, is the difference between "cannot win the fight" and "there is no
     *  opponent, because the bot is not in the arena". */
    private static String fightRangeNow(JourneyRig rig) {
        double away = Math.sqrt(rig.player().distanceToSqr(0.0, 128.0, 0.0));
        return String.format(Locale.ROOT,
                "%.1f blocks from (0,128,0) (EndDragonFight.validPlayer threshold 192) — %s", away,
                away <= 192.0 ? "in range"
                        : "out of range: updatePlayers cannot see this bot, so no dragon will be created");
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
        sweep = 0;
        smashCrystal(ctx, rig, List.copyOf(crystals), 0);
    }

    private static void smashCrystal(SceneContext ctx, JourneyRig rig, List<EndCrystal> crystals, int i) {
        // BEFORE the size check, so a fall during the island march — which leaves no crystals to
        // find — still fails with the fall rather than with "not a single crystal found". The loop
        // is where this rung spends its budget, so it is where a bot that can no longer act must
        // stop it.
        if (rig.lostTheWorld() != null) {
            ctx.fail("the dragon fight was interrupted before crystal " + i + " — " + rig.lostTheWorld()
                    + " " + smashedSoFar(crystals) + "/" + crystals.size() + " crystals smashed so far."
                    + " ⚠️ The result message this line replaces would be 'cannot hit the dragon',"
                    + " which is a consequence of this fall and not its cause: once the bot has left"
                    + " the world, every reading of the dragon, crystals, pillars and walking segments"
                    + " is void, and what to check is which segment's crystal.N.leg the fall happened"
                    + " in.");
            return;
        }
        if (i >= crystals.size()) {
            int left = 0;
            for (EndCrystal c : crystals) if (c.isAlive()) left++;
            // SWEEP AGAIN before fighting. Every surviving crystal heals the dragon, so a duel begun
            // with any of them alive is a duel that cannot be won — and the first pass has been
            // leaving 2 to 8 of them (measured 3→3→2→2→8→1→5 smashed across seven runs). A crystal
            // is skipped for reasons that are usually LOCAL and transient — the leg timed out, the
            // tower stopped short, the body was one block out of reach — and the pass that follows
            // starts from somewhere else entirely, so "go through the list again" is a genuinely
            // different attempt rather than the retry-that-changes-nothing this repo has been bitten by.
            rig.evidence("crystals.left" + (sweep == 0 ? ".pass1" : ""),
                    left + "/" + crystals.size() + (sweep == 0 && left > 0 ? " — sweeping again" : ""));
            if (left > 0 && sweep + 1 < CRYSTAL_SWEEPS) {
                sweep++;
                // Unwedge before re-asking, for the reason the podium march already proved: the
                // walks that lose a crystal end "no progress for 1200 ticks" with the bot sunk
                // below the island band, and a fresh sweep from down there asks the identical
                // question that already failed. One tower back to walking height changed six
                // straight march failures into an arrival on the next round.
                // ...to the island BAND, never relative to where the body happens to be now.
                // The old form (max(now + 8, ISLAND_WALK_Y)) escalated: a sweep ending on a
                // 103-high tower re-swept from 111, the next from 119, and a tower only goes
                // UP, so each re-sweep started structurally further from a y=80 crystal than
                // the one before it. Measured 2026-08-19: crystal 0 was missed three times,
                // its climb row reading "not needed (feetY=119 already ≥ targetY=78)" while the
                // body sat 41 blocks ABOVE the thing it was trying to reach. Below the band,
                // lift to it; at or above it, the tower reports "not needed" in one tick and
                // costs nothing.
                int back = ISLAND_WALK_Y;
                rig.settle(new TowerProcess(back, pillarBlock(rig), true), 2_000, () -> {
                    rig.evidence("crystals.sweep" + sweep + ".unwedge", "pillar back up to y=" + back
                            + " before sweeping again → feet at y=" + rig.player().blockPosition().getY()
                            + ", " + rig.slotError("builder"));
                    smashCrystal(ctx, rig, crystals, 0);
                });
                return;
            }
            duel(ctx, rig, 0);
            return;
        }
        EndCrystal crystal = crystals.get(i);
        if (!crystal.isAlive()) { smashCrystal(ctx, rig, crystals, i + 1); return; }
        BlockPos base = crystal.blockPosition();
        int top = Mth.floor(crystal.getY()) - 2;
        rig.attempting("smash the end crystal on pillar " + i + " (" + xyz(base) + ")");
        rig.evidence("crystal." + i + ".at", xyz(base));
        // Read the leg's STARTING state before the leg, not after it. The body mines and places as
        // it walks, so `blockAt(from.below())` asked in the continuation describes the world at the
        // END of the leg while claiming to describe its start.
        BlockPos from = rig.player().blockPosition();
        String fromUnder = blockAt(rig, from.below());
        String legItem = pillarBlock(rig);
        int legStock = rig.carrying(legItem);
        LegWatch walk = new LegWatch(rig);
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(base.getX(), base.getZ(), 2))),
                CRYSTAL_WALK_TICKS, walk, () -> {
            rig.evidence("crystal." + i + ".leg", legRow(rig, from, fromUnder, legItem, legStock, walk));
            String pillar = pillarBlock(rig);
            // Put the block in the HAND first: TowerProcess can only look in the hotbar, so a body
            // whose hotbar is tools reports "no placeable block" while carrying a stack of stone.
            // Both bodies — the tower places through the server. See
            // JourneyHands.holdBoth.
            JourneyHands.holdBoth(rig, itemOf(pillar));
            stockHotbar(rig, pillar);
            int climbStock = rig.carrying(pillar);
            // Read BEFORE the tower runs. Taken afterwards it is the tower's own answer, and the one
            // question this row exists to settle is whether the tower had anything to do.
            int climbFromY = rig.player().blockPosition().getY();
            // `.climb.plan`, not `.climb`: this is what the climb SET OUT to do, and `.climb` is now
            // what it achieved. One key for both would be the silent overwrite JourneyRig.evidence
            // exists to shout about, and the two rows answer different questions.
            rig.evidence("crystal." + i + ".climb.plan",
                    "climb to y=" + top + " using " + pillar + " x" + climbStock);
            LegWatch climb = new LegWatch(rig);
            rig.settle(new TowerProcess(top, pillar, true), CRYSTAL_CLIMB_TICKS, climb, () -> {
                rig.evidence("crystal." + i + ".climb",
                        climbRow(rig, crystal, top, pillar, climbStock, climbFromY, climb));
                // "feetY already ≥ targetY" is the tower's success wording, and it is the WRONG
                // wording when the body is above the target rather than at it: a tower cannot
                // descend, so being 41 up is exactly as unreachable as being 41 down, and the row
                // read like an accomplishment for three straight sweeps. Say so in its own key.
                if (climbFromY > top + 3) {
                    rig.evidence("crystal." + i + ".climb.tooHigh",
                            "feet at y=" + climbFromY + " when the pillar started, " + (climbFromY - top)
                                    + " blocks above the target y=" + top + ". A pillar only goes up, so"
                                    + " its 'not needed' means stacked too high, not in position;"
                                    + " whether the crystal comes within reach now depends entirely on"
                                    + " the approach walk descending on its own");
                }
                rig.evidence("weapon", rig.holdBestWeapon());
                // CLOSE THE LAST FEW BLOCKS. SwingAt's first statement is `commandMove(0,0)` — it
                // stands still and swings whatever comes within reach, and a crystal never moves. So
                // the whole rung rested on the walk and the tower happening to land inside 4.5, and
                // when they did not, nothing tried: measured 2026-08-18, crystal 0 ended
                // "closest 5.2 blocks, 0 swings" and crystal 4 "closest 7.4 blocks, 0 swings" — both a
                // short step from a
                // hit that was never attempted. Radius 3 rather than MELEE_REACH so arriving at the
                // goal is comfortably inside reach instead of exactly on its edge.
                // ONLY when out of reach. Measured on the run that introduced this walk: crystal 0
                // started at 3.6 — already a hit — and the walk left it at 13.3, costing a crystal
                // that needed no walking at all. A remedy must not run where there is nothing to
                // remedy; the goal cell here is the pillar the crystal sits on, so "go nearer" can
                // mean "come down off the tower you are standing on".
                double beforeApproach = rig.player().distanceTo(crystal);
                BotProcess approach = beforeApproach <= MELEE_REACH - 0.5
                        ? new HoldStill(1)
                        : new IntentProcess(new Intent(new Goal.Near(base, CRYSTAL_APPROACH)));
                rig.settle(approach, CRYSTAL_APPROACH_TICKS, () -> {
                rig.evidence("crystal." + i + ".approach", String.format(Locale.ROOT,
                        "final approach before the swing: %.1f blocks → %.1f blocks (threshold %.1f)%s%s",
                        beforeApproach, rig.player().distanceTo(crystal), MELEE_REACH,
                        beforeApproach <= MELEE_REACH - 0.5 ? " (already within reach, did not move)" : "",
                        rig.player().distanceTo(crystal) <= MELEE_REACH ? "" : " — still out of reach"));
                // "First stand on blast-proof footing, then swing" — the two-step the guard's javadoc
                // says attackEntity
                // cannot do, done here because this rung owns both steps. The tower is raised
                // BESIDE the spike, so its top is cobblestone (R=6.0) however high it goes and no
                // change to the climb target can fix that; the blast-proof cells are the spike's
                // own obsidian and the bedrock under the crystal, a step away.
                // The stand list is candidates to WALK to, never "can stand here": reachability is unverified
                // by construction (vanilla's cage lid is a solid 5x5 of iron bars over the only
                // qualifying floor), so the walk is the test. A candidate must also be within reach
                // of the crystal — a perfect stand 4 cells away that cannot swing is not a remedy.
                String refusal = BlastFooting.refuseSwing(rig.player(), crystal);
                BlockPos betterStand = null;
                if (refusal != null) {
                    double reach = (MELEE_REACH - 0.5) * (MELEE_REACH - 0.5);
                    for (BlockPos st : BlastFooting.qualifyingStands(rig.player().level(),
                            rig.player().blockPosition(), BlastFooting.needFor(crystal))) {
                        if (st.distSqr(base) <= reach) { betterStand = st; break; }
                    }
                }
                final BlockPos chosen = betterStand;
                BotProcess reseat = chosen == null ? new HoldStill(1)
                        : new IntentProcess(new Intent(new Goal.Block(chosen)));
                rig.settle(reseat, CRYSTAL_RESEAT_TICKS, () -> {
                rig.evidence("crystal." + i + ".reseat", chosen != null
                        ? "footing would be destroyed by the blast; moving to " + chosen.toShortString()
                          + " (standing on " + blockAt(rig, chosen.below()) + ") → after the move at "
                          + xyz(rig.player().blockPosition()) + ", "
                          + (BlastFooting.refuseSwing(rig.player(), crystal) == null
                                  ? "no longer refused" : "still refused")
                        : refusal == null ? "footing already withstands this blast; did not move"
                        : "swing refused, but no footing within radius " + BlastFooting.STAND_SURVEY_RADIUS
                          + " both withstands this blast and reaches the crystal");
                SwingAt swing = new SwingAt(crystal, CRYSTAL_SWING_TICKS, MELEE_REACH);
                rig.settle(swing, CRYSTAL_SWING_TICKS + 50, () -> {
                    rig.evidence("crystal." + i + ".result", (crystal.isAlive() ? "still alive" : "smashed")
                            + " (standing at y=" + rig.player().blockPosition().getY() + ", closest "
                            + String.format(Locale.ROOT, "%.1f", swing.closest()) + " blocks, "
                            + swing.swings() + " swings"
                            + (swing.refused() == 0 ? "" : ", refused " + swing.refused() + " times: »"
                                    + swing.refusal() + "«") + ")");
                    smashCrystal(ctx, rig, crystals, i + 1);
                });
                });
                });
            });
        });
    }

    /** How many of this rung's crystals are already gone — the only part of the tally that stays
     *  true after the body has left the world, since everything else it could report is a reading
     *  taken in the void. */
    private static int smashedSoFar(List<EndCrystal> crystals) {
        int gone = 0;
        for (EndCrystal c : crystals) if (!c.isAlive()) gone++;
        return gone;
    }

    /**
     * One settle's worth of tick-by-tick bookkeeping: how long it ran, and how low the body got
     * <b>inside</b> it.
     *
     * <h2>Why the first sample is thrown away</h2>
     *
     * {@code SceneContext.advance} drains steps greedily — the tick that registers a settle also
     * evaluates its wait condition once, before the world has moved — so sample 0 is taken at the
     * STARTING position. A {@code lowestY} that includes its own start is a reading that can never
     * contradict the start, and this ladder has already been misled by exactly that shape once: a
     * healthy arm was judged red because its minimum was polluted by the spawn point the walk began
     * on. Dropping sample 0 is what makes this a property of the walk.
     *
     * <p>It follows that a walk which finished before its second sample reports {@code not sampled} rather
     * than a number. That is the honest answer: nothing between the start and the end was observed,
     * because there was nothing between them.
     *
     * <h2>What it does not do</h2>
     *
     * It never steers, never fails and never touches the world — {@link JourneyFlight} is the full
     * trajectory recorder and this is the two-number version, for walks that want a cost and a floor
     * without a per-tick narrative.
     */
    private static final class LegWatch implements JourneyRig.TickWatcher {

        private final JourneyRig rig;
        private int samples;
        private int lowest = Integer.MAX_VALUE;

        LegWatch(JourneyRig rig) { this.rig = rig; }

        @Override public void tick() {
            if (samples++ == 0) return;
            lowest = Math.min(lowest, rig.player().blockPosition().getY());
        }

        /** Ticks this walk actually ran. Sample 0 costs no tick of the world, so it does not count. */
        int ticks() { return Math.max(0, samples - 1); }

        /** The lowest y reached after the start, or "not sampled" when the walk never got a second
         *  sample. */
        String lowestY() { return lowest == Integer.MAX_VALUE ? "not sampled" : String.valueOf(lowest); }
    }

    /**
     * What one crystal-to-crystal move cost and where it left the body — the row {@code island.*}
     * has for the march and the crystal phase had for nothing at all.
     *
     * <p>Measured 2026-08-17: after the sixth crystal the body stood on its own tower at
     * {@code 31,100,24} with crystal 7 at {@code (-34,-25)}, did not come down, and ended at
     * {@code -43,94,20} before falling to {@code y=-32453}. Every number in that sentence was
     * RECONSTRUCTED — from an inventory delta, a handful of guard log lines and the two coordinates
     * that happen to be recorded. The crystal phase recorded no start, no end, no cost and no floor
     * for any of its moves, so "it bridged about eighty blocks through the sky" was an inference,
     * and the next failure would have had to be inferred again.
     *
     * <p><b>Same counting method as {@code island.*.plan}</b> — {@link #blocksSpent}, called from
     * both — so a crystal walk and a march segment can be compared without first asking which counter each used.
     *
     * <p><b>{@code end}/{@code err} are the walker's own words</b>: {@code IntentProcess} copies
     * {@code Walker.lastEndReason} into {@code mc_goto.endReason} and {@code Walker.lastError} into
     * {@code mc_goto.lastError} at every terminal exit, and both survive {@code reset()} precisely so
     * they can be read afterwards. Nothing here invents a verdict word of its own.
     */
    private static String legRow(JourneyRig rig, BlockPos from, String fromUnder,
                                 String item, int stockBefore, LegWatch watch) {
        BlockPos to = rig.player().blockPosition();
        // ONE routed read, not two: "how did it end" and "what went wrong" are a pair, and asking
        // twice asks two moments (JourneyRig.slot; the same reason BotApi.userTaskLeg publishes its
        // whole reading as one snapshot).
        var slot = rig.slot("goto");
        return "start=" + xyz(from) + " underfoot=" + fromUnder
                + " → end=" + xyz(to) + " underfoot=" + blockAt(rig, to.below())
                + "  placed " + blocksSpent(rig, item, stockBefore) + " blocks of " + item
                + "  lowestY=" + watch.lowestY() + " (excluding the start y=" + from.getY() + ")"
                + "  took " + watch.ticks() + " ticks"
                + "  end=" + slot.get("endReason") + " err=" + slot.get("lastError");
    }

    /**
     * Whether the tower got where it was sent — stated, not left to be subtracted.
     *
     * <p>Crystals 1 and 2 of the 2026-08-17 run ended with the body at {@code y=56} and {@code y=59}
     * under crystals 20.8 and 38.0 blocks away, and zero swings. All of that was readable only by
     * taking {@code crystal.N.at}, subtracting {@code crystal.N.result}'s parenthesised y and
     * knowing that {@code TowerProcess} aims two blocks under the crystal. A row that says
     * {@code result=did not reach the top} needs none of that.
     *
     * <p>{@code gap} is {@code targetY − reachedY}: positive is how far short it stopped. <b>Negative
     * does NOT mean it overshot</b>. It means the bot was already above the target when the tower
     * was ordered, so nothing was built; the walk to the previous crystal left it up there and
     * {@code Goal.XZ.ignoresY()} gives that walk no reason to come down. Four consecutive climbs of
     * the 2026-08-18 run read {@code gap=-1, placed 0 blocks, result=reached the top}, which is what
     * "finished building" looks like, and the tower had in fact never once been exercised.
     *
     * <p>Hence three outcomes, not two, and the starting height stated on the row rather than left to
     * be inferred from the previous crystal's block: a climb that built and a climb that was never
     * needed must not be readable as the same event.
     */
    private static String climbRow(JourneyRig rig, EndCrystal crystal, int top, String pillar,
                                   int stockBefore, int fromY, LegWatch watch) {
        int y = rig.player().blockPosition().getY();
        String verdict = fromY >= top
                ? "no pillar needed (feet already at y=" + fromY + " ≥ target " + top + " when it started)"
                : (y >= top ? "reached the top (built from y=" + fromY + " to y=" + y + ")"
                            : "did not reach the top (from y=" + fromY + ", only to y=" + y + ")");
        return "targetY=" + top + " (crystal at y=" + Mth.floor(crystal.getY()) + ", the pillar stops 2"
                + " blocks below it) startY=" + fromY + " reachedY=" + y + " gap=" + (top - y)
                + " placed " + blocksSpent(rig, pillar, stockBefore) + " blocks of " + pillar
                + " took " + watch.ticks() + " ticks"
                // The tower's OWN account of why it stopped. It carries placed / holding / phase /
                // apexFeetY, which separate "nothing to place" from "the jump never left its own
                // cell" from "the bot was carried off its own column" — and none of that is
                // derivable from the heights on this row, so it must be read from the process.
                + " ownReport=" + rig.slotError("builder")
                + " result=" + verdict;
    }

    /**
     * Move more of the pillar block into the hotbar before a climb.
     *
     * <p>{@code TowerProcess.ensureHoldingPlaceable} scans hotbar slots 0..8 only (9..35 are read in
     * creative alone), so a survival body stops the moment the ONE stack it was handed runs out —
     * measured 2026-08-18, {@code crystal.4.climb} stopped short with
     * {@code ownReport=no placeable block in hotbar} and 542 cobblestone still in the bag.
     *
     * <p>This is staging around a product limit, not a fix for it: a bot that has to be handed a
     * pre-arranged hotbar will stall the same way on a live run. It is done here because widening
     * {@code ensureHoldingPlaceable} changes a {@code Body} contract the client path implements
     * with container interactions, and {@code wd.serverTowersWithAFullBackpack} pins today's
     * behaviour on purpose. Arranging one's own hotbar is also something a player does.
     */
    private static void stockHotbar(JourneyRig rig, String pillar) {
        var inv = rig.player().getInventory();
        Item want = itemOf(pillar);
        for (int slot = 0; slot < 9; slot++) {
            if (!inv.getItem(slot).isEmpty()) continue;
            for (int from = 9; from < inv.getContainerSize(); from++) {
                ItemStack stack = inv.getItem(from);
                if (stack.isEmpty() || !stack.is(want)) continue;
                inv.setItem(slot, stack.copy());
                inv.setItem(from, ItemStack.EMPTY);
                break;
            }
        }
    }

    /** Top of the central bedrock fountain — where the dragon perches, and therefore the only cell
     *  a stand-still melee fight can be won from. Scanned rather than hard-coded so a world whose
     *  podium sits at a different height still answers correctly.
     *  <p>A cell on the fountain a body can actually STAND on.
     *
     * <p>The first cut scanned the column at exactly {@code x=0,z=0} and returned the first non-air
     * cell's {@code above()}. That column is the exit portal's own hole: it is not floor, and the
     * cell above whatever the scan hits is not supported. The march then walked the body to it, the
     * walker reported {@code ARRIVED}, and the departure trace caught it: a step of ARRIVED with the
     * bot at -0.75,59.00,-0.76, horizontal speed 0.007 and sole support 0.000 — standing still, at the
     * podium, with nothing whatsoever under the sole. Five rounds of gating leap and diagonal
     * families had been chasing island-rim coordinates while the fall was happening at the target.
     *
     * <p>So require support: scan the 5x5 around the centre and take the highest cell whose floor
     * is solid and whose own two body cells are clear. Nearest-to-centre breaks ties, because the
     * perched head hovers over the middle and every block outward is reach spent.
     */
    private static BlockPos podiumTop(ServerLevel end) {
        BlockPos best = null;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = 100; y > 40; y--) {
                    BlockPos at = new BlockPos(dx, y, dz);
                    if (end.getBlockState(at).isAir()) continue;
                    BlockPos stand = at.above();
                    if (!end.getBlockState(stand).isAir()
                            || !end.getBlockState(stand.above()).isAir()) break;
                    if (best == null || stand.getY() > best.getY()
                            || (stand.getY() == best.getY()
                                && stand.distSqr(BlockPos.ZERO) < best.distSqr(BlockPos.ZERO)))
                        best = stand;
                    break;
                }
        return best != null ? best : new BlockPos(0, 65, 0);
    }

    /** Walk to the podium, retrying: one stall on ground the body broke and bridged itself is not
     *  proof the centre cannot be reached. Runs {@code then} either way — the duel's evidence row
     *  says where it actually ended up, and a fight from the wrong cell is a finding, not a crash. */
    private static void marchToPodium(JourneyRig rig, BlockPos podium, int left, Runnable then) {
        // A body in the void gets no more orders. settle() already refuses to run a process for one,
        // but a recursion that keeps calling settle() turns that refusal into SILENCE: the six rows
        // this produced all reported "stopped at -47,-66,23, not arrived" with end=null, and each
        // tower row read "not needed (feetY=111 already ≥ targetY=90)" — a targetY nobody asked for
        // this run, left in builder.lastError by the crystal sweep's tower because the new one never
        // ran. Every one of those rows described a bot that had already left the world 126 blocks
        // earlier.
        if (rig.lostTheWorld() != null) { then.run(); return; }
        if (left <= 0) { then.run(); return; }
        // Alternate the goal SHAPE between rounds, because a retry that asks the identical question
        // gets the identical answer: this rung has already spent a run watching ninety repeats of
        // one 22-block query. The body finishes the crystals on top of whatever tower the last one
        // needed (measured: -32,91,-24 — 39.4 blocks off-centre and 31 up), and a 3D goal at the
        // podium has to solve "come down 31" and "cross 39" at once. Goal.XZ ignores Y, so the odd rounds
        // ask only for the horizontal half and let the descent fall out of it; the even rounds then
        // finish the last cells in 3D. Either shape alone has been observed to stall.
        boolean flat = (left % 2) == 0;
        Goal goal = flat ? new Goal.XZ(podium.getX(), podium.getZ(), DUEL_STAND_RADIUS)
                : new Goal.Near(podium, DUEL_STAND_RADIUS);
        rig.settle(new IntentProcess(new Intent(goal)), DUEL_MARCH_TICKS, () -> {
            // Height is its own clause, not a component of the distance: standing 2 below the
            // platform is 2 units of error in a radius but the whole fight in reach, because the
            // head hovers ABOVE the fountain and every block down is a block of reach spent.
            BlockPos me = rig.player().blockPosition();
            // ...and it must be STANDING there. ARRIVED is not the same as supported: the trace
            // that found this bug reports a sole support of 0.000 on an ARRIVED tick at the podium.
            // Radius and height, and NOTHING else. A support clause here fails in two opposite
            // ways: `!getBlockState(me.below()).isAir()` rejected a bot held by its footprint
            // overlapping the next cell (five rounds of "stopped at 0,60,-1, 1.0 blocks away, not
            // arrived, end=arrived", then a 41-block wander), and sampling soleOnSolid rejected a
            // bot that was airborne for the one tick the walk happened to finish on (six rounds of
            // "stopped at -1,59,-1, not arrived"). A gate on an instantaneous reading of
            // a quantity that blinks is not a stricter gate, it is a random one.
            //
            // The portal hole — the thing those clauses were reaching for — is already handled
            // where it belongs: podiumTop() only ever returns a cell whose floor is solid and whose
            // body cells are clear. Guard the TARGET once, not the arrival every round.
            boolean close = me.distSqr(podium) <= DUEL_STAND_RADIUS * DUEL_STAND_RADIUS
                    && me.getY() >= podium.getY() - 1;
            rig.evidence("duel.march." + left, (flat ? "XZ" : "3D") + " target "
                    + podium.toShortString() + " → stopped at " + me.toShortString() + " ("
                    + String.format(Locale.ROOT, "%.1f", Math.sqrt(me.distSqr(podium)))
                    + " blocks away, height difference " + (me.getY() - podium.getY()) + ")"
                    + (close ? " arrived" : " not arrived " + JourneyLeg.walkerEnd(rig)));
            if (close) { then.run(); return; }
            // Six rounds of the same stall at (43,52,13) — 46 blocks out and EIGHT BELOW the podium —
            // is not bad luck a seventh round fixes. Below the target the walk has to solve "climb
            // back onto the island" and "cross 46" at once, and the climb is a different verb: the
            // crystal legs already tower when they need height. Do that here before re-asking, so
            // the retry differs from the attempt it repeats by more than its serial number.
            if (rig.lostTheWorld() != null) { then.run(); return; }
            if (me.getY() < podium.getY() - 2) {
                rig.settle(new TowerProcess(podium.getY(), pillarBlock(rig), true), DUEL_MARCH_TICKS / 3,
                        () -> {
                    rig.evidence("duel.march." + left + ".tower", "pillar up to the podium height y="
                            + podium.getY() + " first → feet at y=" + rig.player().blockPosition().getY()
                            + ", " + rig.slotError("builder"));
                    marchToPodium(rig, podium, left - 1, then);
                });
                return;
            }
            marchToPodium(rig, podium, left - 1, then);
        });
    }

    /**
     * How many of {@code item} a leg put into the world, as a NET inventory difference.
     *
     * <p>The one place this file counts placements, called by {@code island.*.plan} and by both
     * crystal rows, because two counters that disagree are worse than either alone. Net, so a leg
     * that mined more of the item than it placed reports a negative number — that is a fact about
     * the leg and not a reason to clamp it to zero.
     *
     * <p>Distinct from {@code ServerPlayerBody.placeTally()}, which is a lifetime counter of the
     * ACTUATOR's calls and refusals and cannot be differenced per leg. The two answer different
     * questions and {@code island.*.plan} prints both.
     */
    private static int blocksSpent(JourneyRig rig, String item, int stockBefore) {
        return stockBefore - rig.carrying(item);
    }

    private static void duel(SceneContext ctx, JourneyRig rig, int round) {
        ServerLevel end = levelOf(rig);
        EnderDragon dragon = nearestDragon(end, rig.player().position());
        if (dragon == null) {
            if (round == 0) {
                rig.attempting("wait for the ender dragon to appear");
                waitFor(rig, () -> nearestDragon(end, rig.player().position()) != null,
                        DRAGON_WAIT_TICKS, () -> duel(ctx, rig, 1));
                return;
            }
            noDragonHere(ctx, rig, end);
            return;
        }
        rig.evidence("dragon.hp0", String.format(Locale.ROOT, "%.1f", dragon.getHealth()));
        rig.evidence("dragon.at", xyz(dragon.blockPosition()));
        rig.evidence("weapon", rig.holdBestWeapon());
        // WALK TO THE CENTRE FIRST. DuelTheDragon's first statement is `commandMove(0,0)` — it stands
        // still and lets the dragon come to it — so a duel's "at the centre" is only true if a
        // walk put the bot there first. Measured 2026-08-18: the duel began wherever the last
        // crystal left the body, 42 blocks off-centre on a pillar top at y=103, and burned 11 400 of
        // its 200 000 ticks without the dragon once coming within reach. The dragon circles (0,y,0);
        // a body that is not there is not in the fight.
        // A Y-AWARE goal, and retried. `Goal.XZ.ignoresY()` is true, so "walk to the centre" was satisfied on
        // top of whatever tower the last crystal needed — measured, the duel began at y=103 while
        // the dragon perches on the bedrock fountain near y=63, six blocks away horizontally and
        // forty vertically. Vanilla's melee window IS the perch; a body above it never gets one.
        // Three attempts because the walk crosses ground the body itself broke and bridged, and one
        // stall there is not evidence that the centre is unreachable.
        BlockPos podium = podiumTop(end);
        rig.attempting("walk back to the bedrock podium at the arena centre (" + xyz(podium)
                + "); the dragon is only within reach when it perches there");
        marchToPodium(rig, podium, DUEL_MARCH_ROUNDS, () -> {
        rig.evidence("duel.stand", xyz(rig.player().blockPosition()) + ", "
                + String.format(Locale.ROOT, "%.1f",
                        Math.hypot(rig.player().getX(), rig.player().getZ())) + " blocks from the centre, "
                + (rig.player().blockPosition().getY() - podium.getY()) + " blocks above the podium"
                + " (DuelTheDragon does not move, so this cell is where the whole fight takes place)");
        rig.attempting("wait at the centre until the dragon is within reach, then hit the head (the"
                + " head takes full damage, every other part divides it by four)");
        DuelTheDragon fight = new DuelTheDragon(DUEL_TICKS, MELEE_REACH);
        rig.settle(fight, DUEL_TICKS + 200, () -> {
            EnderDragon still = nearestDragon(end, rig.player().position());
            // `still == null` is NOT death. nearestDragon is a box search around the body over the
            // LOADED entity index, and this rung's own failure path carries a paragraph about exactly
            // that ambiguity — an unloaded arena answers null for a dragon in perfect health. Treating
            // null as death declared "dragon killed" on a 2026-08-18 run with 8 of 10 crystals still
            // healing the dragon, ZERO swings, and `advancement.kill_dragon = not-earned`. Three
            // independent readings said no kill and the criterion said yes.
            //
            // vanilla's own record is the authority: EndDragonFight.setDragonKilled writes
            // `previouslyKilled`, and it survives the arena unloading. A dragon SEEN dying also
            // counts; a dragon merely out of the box never does.
            boolean seenDying = still != null && still.isDeadOrDying();
            boolean fightSaysKilled = end.getDragonFight() != null
                    && end.getDragonFight().hasPreviouslyKilledDragon();
            boolean dead = seenDying || fightSaysKilled;
            rig.evidence("duel.bow", fight.bowRow(rig.player()));
            rig.evidence("duel.end", fight.why() + " (fought for " + fight.elapsedTicks() + " ticks)");
            rig.evidence("duel.swings", fight.swings() + " (" + fight.headHits() + " of them hit the"
                    + " head); " + fight.arrows() + " arrows shot (melee only works during the few"
                    + " seconds the dragon dives and perches, and vanilla divides non-head hits by four,"
                    + " so all damage during the circling phase has to come from arrows)");
            rig.evidence("duel.closest", String.format(Locale.ROOT, "%.1f blocks (%s)",
                    fight.closest(), fight.closestPart())
                    + (fight.gaveUp() ? "; ⚠️ gave up: for " + DUEL_OUT_OF_REACH_TICKS
                        + " consecutive ticks the dragon never came within " + MELEE_REACH + " blocks"
                        + " — the fight was not too hard, the bot was not in position" : ""));
            rig.evidence("dragon.hp", still == null
                    ? "not in the search box — this does not mean it is dead, see dragon.dead"
                    : String.format(Locale.ROOT, "%.1f", still.getHealth()));
            rig.evidence("dragon.dead", dead + " (seen dying=" + seenDying
                    + ", EndDragonFight.hasPreviouslyKilledDragon=" + fightSaysKilled
                    + "; absence from the search box does not count on its own)");
            rig.noteAdvancement("minecraft:end/kill_dragon");
            ctx.expect(dead).as("the ender dragon is dead — seen dying, or EndDragonFight says it was"
                    + " killed; a dragon merely absent from the search box does not count").isTrue();
            rig.reach("dragon killed: " + fight.swings() + " swings (" + fight.headHits()
                    + " hit the head)");
        });
        });
    }

    /**
     * {@link #nearestDragon} came back empty — <b>and that is the only thing this method knows.</b>
     *
     * <p>It used to know more, and it was wrong. The old text asserted one mechanism:
     * {@code EndDragonFight.tick} rescans {@code ServerLevel.getPlayers(validPlayer)} every twenty
     * ticks and does nothing at all while that set is empty — no arena ticket, no {@code scanState},
     * no {@code createNewDragon} — and this track's body is a {@code FakePlayer} that never went
     * through {@code PlayerList.placeNewPlayer}. Every clause of that is a real vanilla fact and the
     * conclusion was still false: measured 2026-08-17 the body WAS in {@code level.players()} (it was a
     * {@code JoinedBody}, which joins), the fight
     * HAD run — {@code dragonUUID = 967f837e-…}, {@code crystalsAlive = 5} — and the search still
     * found nothing, because the body had fallen 32 500 blocks out of the world and
     * {@code nearestDragon} centres its box on the body.
     *
     * <p>So the readings come first and the sentence is assembled from them in {@link #whyNoDragon}.
     * The rows this writes are the failure-time half of a pair; the start-of-rung half is
     * {@link #recordTheFight}, and neither is readable without the other.
     */
    private static void noDragonHere(SceneContext ctx, JourneyRig rig, ServerLevel end) {
        var fight = end.getDragonFight();
        rig.evidence("dragon.present", false);
        rig.evidence("level.realPlayers", end.players().size());
        boolean inList = end.players().contains(rig.player());
        rig.evidence("body.inPlayerList", inList);
        // BEFORE blaming the player list, read the distance AGAIN. The list and the range are two
        // independent halves of `validPlayer`, a rehearsal records the range once at staging time,
        // and a body that has since moved makes that stale row read as an all-clear for the one
        // cause that is actually in play. Measured 2026-08-17: staged at 127.8 blocks (in range),
        // failed 23 000 blocks below the island, and `dragonUUID = null` was read as "there is no
        // opponent" rather than as "the bot is not in the arena".
        rig.evidence("dragon.rangeNow", fightRangeNow(rig));
        recordTheFightNow(rig, end);
        // BEFORE the UUID lookup, not after it: `dragon.byUuid` cannot interpret its own miss, and
        // this is the row that interprets it. Printing them in this order is the whole fix — a
        // reader who meets "not found" first has already formed the conclusion the next row exists
        // to forbid.
        rig.evidence("dragon.arenaLoaded", arenaLoaded(end));
        rig.evidence("dragon.byUuid", dragonByUuid(end, fight));
        ctx.fail(whyNoDragon(rig, end, fight, inList));
    }

    /**
     * Ask the LEVEL for the dragon by the fight's own UUID — the reading that splits the two worlds
     * {@code dragon.present=false} otherwise prints identically.
     *
     * <p>{@link #nearestDragon} builds its box around <b>the bot</b>, so it can only ever answer
     * "is there a dragon in the box". A bot that has fallen to {@code y=-32453} is guaranteed an empty box
     * whatever the End contains — and on 2026-08-17 that is exactly what it reported, beside a
     * {@code dragonFight.now.dragonUUID=967f837e-…} that says vanilla had built one.
     * {@code ServerLevel.getEntity(UUID)} goes to the level's entity index instead, so its answer
     * does not depend on where the bot is.
     *
     * <h2>What this row is NOT allowed to conclude</h2>
     *
     * Its miss branch must not end on "so the dragon was removed": <b>that is one claim past what the
     * call can support</b>. {@code ServerLevel.getEntity(UUID)} reads the level's <i>loaded</i> entity
     * index — the same index an unloaded chunk's entities are absent from — so "not found" covers two
     * different worlds and this call cannot tell them apart:
     *
     * <ul>
     *   <li>the dragon was genuinely removed; or</li>
     *   <li>the dragon is alive in a chunk that is no longer loaded, and the index simply does not
     *       hold it. This is not hypothetical on this rung: a bot more than 192 blocks from
     *       {@code (0,128,0)} fails {@code EndDragonFight.validPlayer}, so {@code updatePlayers}
     *       empties {@code dragonEvent}, so {@code tick()} takes its {@code else} branch and calls
     *       {@code removeRegionTicket(TicketType.DRAGON, ChunkPos(0,0), 9, …)} — the arena's only
     *       ticket. Losing the ticket <b>permits</b> the unload rather than performing it, which is
     *       exactly why whether it happened has to be MEASURED and not inferred.</li>
     * </ul>
     *
     * <h2>How to read it — all four of these print {@code dragon.present=false}</h2>
     *
     * <ul>
     *   <li><b>found</b> ⇒ <b>the dragon IS there and the bot is not with it.</b> A hit is
     *       conclusive in a way a miss is not: an entity in the index exists. The rung's problem is
     *       the bot's position, not the fight. The coordinates, health and {@code isRemoved}
     *       printed beside it say whether it is also still where it was born.</li>
     *   <li><b>not found, UUID non-null, {@code dragon.arenaLoaded} = fully loaded</b> ⇒ <b>the dragon
     *       was removed.</b> Vanilla built one — only {@code createNewDragon}/{@code findOrCreateDragon}
     *       write that field — the arena chunks are loaded, and the index still does not hold it.
     *       This is the only combination that supports "removed".</li>
     *   <li><b>not found, UUID non-null, {@code dragon.arenaLoaded} = anything else</b> ⇒ <b>no
     *       verdict.</b> Alive-but-unloaded and removed print the same {@code null} here. Say so
     *       rather than pick one.</li>
     *   <li><b>no UUID</b> ⇒ this row has NO opinion: nothing was ever built, and that fork belongs to
     *       {@code dragonFight.now.dragonUUID} and {@link #whyNoDragon}. Printed as a word rather
     *       than left blank, because an empty row reads like a lookup that came back empty.</li>
     * </ul>
     *
     * @see #arenaLoaded the row that decides which of the two miss branches applies
     */
    private static String dragonByUuid(ServerLevel end, EndDragonFight fight) {
        UUID id = fight == null ? null : fight.getDragonUUID();
        if (id == null) {
            return "no UUID — " + (fight == null ? "this End has no EndDragonFight"
                            : "the dragon fight never recorded a dragonUUID")
                    + ", so this row makes no judgement (a third case, outside 'is the dragon there')";
        }
        Entity e = end.getEntity(id);
        if (e == null) {
            return "not found: level.getEntity(" + id + ")=null — the dragon fight has built a dragon"
                    + " (dragonUUID is non-null), and this End's loaded entity index does not hold that"
                    + " entity now. ⚠️ There are only two possibilities and this row cannot separate"
                    + " them: (a) the dragon was removed; (b) the dragon is alive but its chunk is not"
                    + " loaded — getEntity(UUID) reads the loaded entity index, which excludes entities"
                    + " in unloaded chunks. dragon.arenaLoaded decides: arena fully loaded and still not"
                    + " found ⇒ genuinely removed; not fully loaded ⇒ this lookup cannot tell, so do"
                    + " not read it as 'the dragon is gone'";
        }
        String hp = e instanceof LivingEntity le
                ? String.format(Locale.ROOT, "%.1f/%.1f", le.getHealth(), le.getMaxHealth())
                : "not a LivingEntity (" + e.getClass().getSimpleName() + ")";
        return "found: " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " at "
                + xyz(e.blockPosition()) + ", health " + hp + ", isRemoved=" + e.isRemoved()
                + " — the dragon is still there and the bot is not with it (dragon.present=false"
                + " describes the search box, not the world)";
    }

    /** Half-width, in chunks, of the square {@code EndDragonFight} calls the arena — vanilla's own
     *  {@code ARENA_SIZE_CHUNKS}, and the radius of the {@code TicketType.DRAGON} region ticket its
     *  {@code tick()} adds and drops around {@code ChunkPos(0,0)}. */
    private static final int ARENA_CHUNKS = 8;

    /**
     * Is the End's dragon arena still loaded? — <b>the reading that makes {@link #dragonByUuid}'s
     * miss mean something.</b>
     *
     * <p>{@code getEntity(UUID)} answers "is it among the loaded entities". On its own that is not a
     * statement about the world, because vanilla itself takes the arena's loading away on this exact rung:
     * {@code EndDragonFight.tick()} keeps {@code TicketType.DRAGON} on {@code ChunkPos(0,0)} only
     * while {@code dragonEvent} has players, and {@code updatePlayers} refills that set from
     * {@code level.getPlayers(validPlayer)} — a body 192+ blocks from {@code (0,128,0)} is not in it.
     * So a fallen body drops the ticket for the very chunks the dragon lives in, and the resulting
     * empty lookup says nothing about whether there is a dragon.
     *
     * <p>This reproduces vanilla's own private {@code EndDragonFight.isArenaLoaded()} rather than
     * approximating it, so the row and the engine agree by construction: every chunk in
     * {@code [-8,8]^2} must be a {@code LevelChunk} at {@code ChunkStatus.FULL} whose
     * {@code FullChunkStatus} is at least {@code BLOCK_TICKING}. {@code getChunk(..., false)} is the
     * non-generating overload — asking this question must never be what generates the arena, both
     * because that would block the server thread on worldgen and because a reading that changes what
     * it measures is not a reading.
     *
     * <h2>How to read it</h2>
     *
     * <ul>
     *   <li><b>fully loaded (289/289)</b> — the arena is there. A {@code dragon.byUuid} miss beside this
     *       DOES mean the dragon was removed: the index covers the chunks it would be in.</li>
     *   <li><b>anything less</b> — part or all of the arena is unloaded. {@code dragon.byUuid} has
     *       no verdict at all in this state, and neither does anything else that queries entities
     *       by position or by id. The question to ask next is why the ticket went away, which is
     *       {@code dragon.rangeNow}'s 192-block half.</li>
     * </ul>
     *
     * <p>Note the asymmetry, because it is easy to state backwards: "fully loaded" lets a miss become
     * a verdict, but "not fully loaded" does <b>not</b> turn a miss into "the dragon is alive". It
     * turns it into no answer.
     */
    private static String arenaLoaded(ServerLevel end) {
        int loaded = 0, total = 0;
        for (int cx = -ARENA_CHUNKS; cx <= ARENA_CHUNKS; cx++) {
            for (int cz = -ARENA_CHUNKS; cz <= ARENA_CHUNKS; cz++) {
                total++;
                if (chunkTicking(end, cx, cz)) loaded++;
            }
        }
        boolean all = loaded == total;
        return (all ? "fully loaded" : "not fully loaded") + ": " + loaded + "/" + total
                + " chunks at FULL+BLOCK_TICKING (the criterion of EndDragonFight.isArenaLoaded,"
                + " chunks [-" + ARENA_CHUNKS + "," + ARENA_CHUNKS + "]^2 around ChunkPos(0,0));"
                + " the dragon's spawn cell (0,128,0) has level.isLoaded="
                + end.isLoaded(new BlockPos(0, 128, 0))
                + " — " + (all
                        ? "so a 'not found' from dragon.byUuid is a valid conclusion this time: the"
                                + " dragon really was removed"
                        : "so a 'not found' from dragon.byUuid proves nothing this time: even a living"
                                + " dragon would not be in the loaded entity index. ⚠️ The converse does"
                                + " not hold either — this does not mean 'the dragon is alive', it means"
                                + " the lookup has no answer");
    }

    /** One chunk of {@link #arenaLoaded}'s square, by vanilla's own two-part test. Non-generating:
     *  {@code getChunk(..., false)} returns null rather than building the chunk, so the diagnostic
     *  cannot manufacture the state it is asking about. */
    private static boolean chunkTicking(ServerLevel end, int cx, int cz) {
        ChunkAccess c = end.getChunk(cx, cz, ChunkStatus.FULL, false);
        return c instanceof LevelChunk lc
                && lc.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    /**
     * The sentence {@link #noDragonHere} ends on — <b>derived from the readings beside it, never
     * asserted ahead of them.</b>
     *
     * <p>A fixed message would name one mechanism unconditionally: "this track's bot is a FakePlayer
     * that never went through PlayerList.placeNewPlayer, so it is never in that list". On 2026-08-17
     * such a message was printed beside
     * its own interpolated {@code dragonUUID=967f837e-…}, {@code crystalsAlive=5} and an evidence row
     * reading {@code body.inPlayerList=true} — three values that each refute it — and cost a round of
     * investigation. Two rules follow, and they are the whole reason this method exists rather than a
     * string literal:
     *
     * <ul>
     *   <li><b>{@code dragonUUID != null} forbids "there is no dragon".</b> Only {@code createNewDragon} and
     *       {@code scanState}/{@code findOrCreateDragon} write that field, so a non-null value is
     *       vanilla saying the dragon was built.</li>
     *   <li><b>{@code inPlayerList == true} forbids "the bot never went through placeNewPlayer".</b>
     *       That half of
     *       {@code validPlayer} is satisfied; whatever is wrong is the other half.</li>
     * </ul>
     *
     * <p>And {@code dragon.present=false} is stated as what it measures: {@link #nearestDragon}
     * builds its box around <b>the bot</b>, so it answers "is there a dragon in the box", never "is
     * there a dragon in the world".
     */
    private static String whyNoDragon(JourneyRig rig, ServerLevel end,
                                      EndDragonFight fight, boolean inList) {
        UUID dragon = fight == null ? null : fight.getDragonUUID();
        double away = Math.sqrt(rig.player().distanceToSqr(0.0, 128.0, 0.0));
        StringBuilder s = new StringBuilder();
        s.append("cannot hit the dragon: nearestDragon found no EnderDragon in the ±").append(DRAGON_SEARCH)
                .append(" box centred on the bot (bot at ").append(xyz(rig.player().blockPosition()))
                .append(", ").append(String.format(Locale.ROOT, "%.1f", away))
                .append(" blocks from the fight centre (0,128,0)). ⚠️ This line means 'not in the box',"
                        + " not 'not in the world' — the box moves with the bot. ");
        if (fight == null) {
            s.append("This End has no EndDragonFight (dragonFight.now.absent), so there really will be"
                    + " no dragon.");
            return s.toString();
        }
        if (dragon != null) {
            // The branch the old text could not say: the fight HAS a dragon and the search still
            // came back empty. Naming the missing reading is the point — the rung records the UUID
            // and the box, and nothing that resolves one against the other.
            s.append("Yet the dragon fight itself says a dragon has been built: dragonFight.now.dragonUUID=")
                    .append(dragon)
                    .append(", crystalsAlive=").append(fight.getCrystalsAlive())
                    .append(" (compare with the start-of-rung dragonFight.dragonUUID /"
                            + " dragonFight.crystalsAlive). ")
                    .append("So this rung's problem is not 'no opponent'; the bot and the opponent are"
                            + " not together: the dragon spawns at (0,128,0) and the bot is ")
                    .append(String.format(Locale.ROOT, "%.1f", away))
                    .append(" blocks away. dragon.byUuid and dragon.arenaLoaded decide this fork"
                            + " together: byUuid asks level.getEntity() for the dragonUUID directly,"
                            + " which reads the loaded entity index — found = the dragon is still there"
                            + " and the box simply missed it; not found only means 'not among the loaded"
                            + " entities', which can be removal or an unloaded chunk. Only when"
                            + " arenaLoaded=fully loaded does 'not found' mean 'removed'. ");
            if (away > 192.0) {
                s.append("In addition, the 192-block half of validPlayer does not hold right now, so the"
                        + " dragon fight has stopped ticking (empty dragonEvent ⇒ tick() takes the else"
                        + " branch and even releases the arena ticket), so the dragon has most likely"
                        + " stopped moving as well — and releasing the ticket is exactly what can unload"
                        + " the arena, so check dragon.arenaLoaded before treating this run's"
                        + " dragon.byUuid as a valid conclusion. ");
            }
            return s.toString();
        }
        s.append("The dragon fight has no dragonUUID either (dragonFight.now.dragonUUID=null), so"
                + " createNewDragon has not run. EndDragonFight.tick only holds the arena ticket, runs"
                + " scanState and findOrCreateDragon while dragonEvent is non-empty, and dragonEvent is"
                + " refilled every 20 ticks by updatePlayers from level.getPlayers(validPlayer);"
                + " validPlayer = ENTITY_STILL_ALIVE.and(withinDistance(0,128,0,192)) has two halves,"
                + " and missing either one gives the same result. ");
        if (!inList) {
            s.append("This run is missing the player-list half: body.inPlayerList=false,"
                            + " level.realPlayers=")
                    .append(end.players().size())
                    .append(" — every server-created bot goes through PlayerList.placeNewPlayer, so"
                            + " being absent from this dimension's level.players() means it has been"
                            + " removed or is in another dimension. ");
        } else if (away > 192.0) {
            s.append("The player-list half holds: body.inPlayerList=true, level.players() has ")
                    .append(end.players().size())
                    .append(" players — so placeNewPlayer is not the cause. The distance half does not"
                            + " hold; see dragon.rangeNow. ");
        } else {
            s.append("Both halves hold (body.inPlayerList=true, ")
                    .append(String.format(Locale.ROOT, "%.1f", away))
                    .append(" blocks from the centre ≤ 192), yet no dragon has been built — the suspects"
                            + " are then isArenaLoaded() ahead of findOrCreateDragon in tick(), or"
                            + " dragonKilled already being true. Neither has a reading yet. ");
        }
        s.append("⚠️ rehearsal.fightRange was measured during test setup; do not use it to explain away"
                + " the current distance.");
        return s.toString();
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
        private int refused;
        private String refusal;
        private double closest = Double.MAX_VALUE;

        SwingAt(Entity target, int maxTicks, double reach) {
            this.target = target;
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        @Override public String kind() { return "journeySwingAt"; }
        @Override public String failure() { return null; }

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Body a, WorldView w, BotState st) {
            a.commandMove(0, 0);
            a.commandJump(false);
            Hands h = a.hands().orElse(null);
            LivingEntity p = a.entity();
            if (p == null || h == null || !target.isAlive()) return true;
            h.breakHold(false);
            double d = p.distanceTo(target);
            if (d < closest) closest = d;
            sinceSwing++;
            if (d <= reach && sinceSwing >= SWING_EVERY) {
                h.attackEntity(target);
                sinceSwing = 0;
                // A swing the driver DECLINED is not a swing. Rung 20 broke because a crystal took
                // the cage lid out from under the body, and BlastFooting now refuses that hit — so
                // this loop can legitimately run its whole budget without the sword ever moving,
                // and "60 swings, crystal still alive" would describe that as a damage problem. Count what
                // happened, and carry the reason out: a refusal nobody prints is the silent failure
                // this rung has already been bitten by.
                String why = h.lastAttackRefusal();
                if (why != null) { refused++; if (refusal == null) refusal = why; }
                else swings++;
            }
            return ++elapsed >= maxTicks;
        }

        int swings() { return swings; }

        int refused() { return refused; }

        /** Why the driver declined the first refused swing, or {@code null} if it never declined. */
        String refusal() { return refusal; }

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
        private int outOfReach;
        private int arrows;
        private int ammoBefore = -1;
        private int drawTicks;
        private boolean bowBroken;
        private String bowError;
        private int bowTicks;   // ticks the ranged branch actually ran
        private int arrowsAtLastReach;
        private boolean gaveUp;
        /** Why this fight stopped. Four exits produce the identical outside view — a live dragon and
         *  a body standing still — and guessing between them has already cost a round. */
        private String why = "still fighting";
        private String closestPart = "none";

        DuelTheDragon(int maxTicks, double reach) {
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        @Override public String kind() { return "journeyDragonDuel"; }
        @Override public String failure() { return null; }

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Body a, WorldView w, BotState st) {
            a.commandMove(0, 0);
            a.commandJump(false);
            Hands h = a.hands().orElse(null);
            Player p = a.asPlayer();
            if (p == null || h == null) {
                why = "the bot's player entity is gone (a.asPlayer()==null or no hands)"; return true;
            }
            h.breakHold(false);
            EnderDragon dragon = nearestDragon(p.level(), p.position());
            if (dragon != null && dragon.isDeadOrDying()) { why = "the dragon is dying"; return true; }
            if (dragon == null) {
                // "Not in the search box" does not mean dead — this file's own death criterion says
                // exactly that, and the fight loop has to apply it too: a dragon that merely flew
                // past the search box would otherwise end the duel as if it were finished. Measured:
                // 5 swings, 5 of them on the head, then the dragon crossed to -66,87,26 and the fight
                // stopped there with the bot standing on the fountain doing nothing wrong. A circling
                // dragon leaving and returning is the NORMAL shape of this fight; only death or the
                // budget ends it.
                if (++outOfReach >= DUEL_OUT_OF_REACH_TICKS) {
                    gaveUp = true;
                    why = "gave up: no dragon in the search box for " + outOfReach + " consecutive ticks";
                    return true;
                }
                if (++elapsed >= maxTicks) { why = "budget exhausted (" + maxTicks + " ticks)"; return true; }
                return false;
            }

            Entity head = null;
            Entity nearest = null;
            String nearestName = "none";
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
                if (p.isUsingItem()) h.commandUseItem(false);   // drop the draw, this is melee now
                holdSword(p);
                h.attackEntity(aim);
                sinceSwing = 0;
                swings++;
                if (aim == head) headHits++;
            }
            // Out of melee reach is the NORMAL state of this fight, not a lull: a dragon only
            // brings its head down when it perches, and hits on any other part are quartered by
            // vanilla (measured: 1 body hit took 200.0 -> 198.8). So the circling phase is where a
            // bow earns the fight, and the engine has had the draw all along — CombatProcess.
            // rangedTick draws for BOW_FULL_DRAW ticks and releases on the up-edge. This is the
            // same mechanism, aimed at the head rather than at whatever part is nearest: an arrow
            // into the body is worth a quarter of one into the head, and the dragon presents its
            // body far more often.
            // A process that throws dies silently: the driver marks it finished and the rig's settle
            // returns as if it had completed, so the evidence row still reads "still fighting" while the
            // fight has in fact stopped. That is exactly what 14594 ticks of a 200000-tick budget
            // looked like. Name it instead of letting it read as a healthy fight that ran out.
            try {
            if (aim == null && head != null && holdBow(p)) {
                aimAtPart(p, head, headAway * 0.12);      // lead high for arrow drop
                // Vanilla's own draw, on vanilla's own counter. getTicksUsingItem() is not frozen for
                // this bot: the server-side player runs LivingEntity.tick, whose updatingUsingItem()
                // advances the timer exactly as it does for a real player. Counting ticks and calling
                // releaseUsing by hand would route around a defect that does not exist — and a test
                // that drives an engine path by hand stops testing it, which is the worst possible
                // trade for a rung whose whole job is to exercise the engine. The two real causes of
                // "0 arrows shot" were the HAND (the bow sat in the bag) and the SEARCH BOX (128 was
                // narrower than the arena the dragon flies in).
                bowTicks++;
                if (bowBroken) { /* one failure is enough; melee still works */ }
                else {
                drawTicks = p.isUsingItem() ? p.getTicksUsingItem() : 0;
                if (drawTicks >= BOW_FULL_DRAW) {
                    h.commandUseItem(false);              // up-edge = release = shoot
                    // Count AMMO, not releases. The first cut incremented here and reported 9516
                    // shots from a quiver of 256: once the arrows run out stopUsingItem still gets
                    // called every cycle, so the counter went on climbing while nothing was fired.
                    // A number that keeps rising after the thing it counts has stopped happening is
                    // worse than no number.
                    int now = p.getInventory().countItem(net.minecraft.world.item.Items.ARROW);
                    if (ammoBefore < 0) ammoBefore = now;
                    if (now < ammoBefore) { arrows += ammoBefore - now; ammoBefore = now; }
                } else if (p.isUsingItem()) {
                    h.commandUseItem(true);               // already drawing: keep holding
                } else {
                    // RE-ARM. commandUseItem is edge-triggered on the avatar's own useHeld flag, so
                    // once vanilla stops the use by itself — which the melee branch's release does
                    // every time the dragon comes into reach — isUsingItem() goes false while
                    // useHeld stays true, and every later commandUseItem(true) is a no-op. The draw
                    // is then dead for the rest of the fight. Measured: "ranged branch entered for
                    // 21982 ticks, draw count 7, arrows in stock 256" — it reached 7, was stopped
                    // once, and never drew again in the remaining 21975 ticks. The down-edge resets
                    // the flag; the up-edge starts a real draw.
                    // The down-edge is safe here and does NOT waste an arrow: we only reach this
                    // branch when isUsingItem() is false, i.e. useItem is already EMPTY, and
                    // releaseUsingItem() skips the fire on an empty stack and just resets.
                    h.commandUseItem(false);
                    h.commandUseItem(true);
                }
                }
            }
            } catch (RuntimeException e) {
                // Its OWN key. Writing this into `why` would let the later give-up message overwrite
                // it, so an exception on tick 30 vanished behind "gave up" on tick 21102 — one key, two
                // writers, last one wins, and the first write was the interesting one.
                bowError = String.valueOf(e);
                bowBroken = true;
            }
            // Never once in reach for DUEL_OUT_OF_REACH_TICKS: stop waiting. Reset by any approach,
            // so this ends a duel the body is not in, not a fight with lulls. An arrow in flight
            // counts as being in the fight — giving up while landing hits would report "not in
            // position" about a bot that is winning.
            if (aim == null && arrows == arrowsAtLastReach) {
                if (++outOfReach >= DUEL_OUT_OF_REACH_TICKS) {
                    gaveUp = true;
                    why = "gave up: out of reach for " + outOfReach + " consecutive ticks";
                    return true;
                }
            } else {
                outOfReach = 0;
                arrowsAtLastReach = arrows;
            }
            if (++elapsed >= maxTicks) { why = "budget exhausted (" + maxTicks + " ticks)"; return true; }
            return false;
        }

        int swings() { return swings; }

        /** True when it stopped because the dragon never came within reach, not because time ran
         *  out — the two look identical from the outside and mean different things. */
        boolean gaveUp() { return gaveUp; }

        String why() { return why; }

        /** Everything about the ranged half, as one row. Five runs reported "0 arrows shot" and each
         *  time the cause was somewhere else entirely — wrong hand, a suspected frozen use-timer, a
         *  search box narrower than the arena. A count of zero says nothing about which. */
        String bowRow(Player p) {
            return "ranged branch entered for " + bowTicks + " ticks, draw count " + drawTicks
                    + ", arrows in stock " + (p == null ? -1
                        : p.getInventory().countItem(net.minecraft.world.item.Items.ARROW))
                    + ", main hand=" + (p == null ? "?" : p.getMainHandItem().getItem())
                    + (bowError == null ? "" : ", exception=" + bowError);
        }

        int arrows() { return arrows; }

        int elapsedTicks() { return elapsed; }

        /** Vanilla's own full-draw window: 20 ticks of use is maximum power. */
        private static final int BOW_FULL_DRAW = 20;

        /** Put the wanted item in the main hand, swapping up from the bag if need be, or say it is
         *  not carried. Reading the main hand alone was worth exactly zero arrows: the body walks
         *  this rung holding a sword or a stack of cobblestone, so a draw gated on "bow in hand" never
         *  ran once in a whole fight while 256 arrows sat in the bag. Owning is not holding —
         *  the same shape that cost this rung its buckets. */
        private static boolean select(Player p, java.util.function.Predicate<ItemStack> want) {
            var inv = p.getInventory();
            if (want.test(p.getMainHandItem())) return true;
            for (int i = 0; i < 9; i++)
                if (want.test(inv.items.get(i))) { inv.selected = i; return true; }
            for (int i = 9; i < inv.items.size(); i++) {
                if (!want.test(inv.items.get(i))) continue;
                ItemStack bag = inv.items.get(i);
                inv.items.set(i, inv.items.get(inv.selected));
                inv.items.set(inv.selected, bag);
                return true;
            }
            return false;
        }

        private static boolean holdBow(Player p) {
            return select(p, st -> st.getItem() instanceof net.minecraft.world.item.BowItem);
        }

        /** A bow in the main hand melees for 1. Swinging without this swap turns every perch —
         *  the only window vanilla gives full damage — into a wasted one. */
        private static void holdSword(Player p) {
            select(p, st -> st.getItem() instanceof net.minecraft.world.item.SwordItem);
        }

        private static void aimAtPart(Player p, Entity part, double lead) {
            double dx = part.getX() - p.getX();
            double dy = (part.getY() + part.getBbHeight() * 0.5 + lead) - (p.getY() + p.getEyeHeight());
            double dz = part.getZ() - p.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            p.setXRot((float) -Math.toDegrees(Math.atan2(dy, flat)));
            p.yHeadRot = p.getYRot();
            p.yBodyRot = p.getYRot();
        }

        int headHits() { return headHits; }

        double closest() { return closest == Double.MAX_VALUE ? -1 : closest; }

        String closestPart() { return closestPart; }
    }

    // =====================================================================================
    // Reading the world.
    //
    // Four of these are package-private rather than private, for JourneyRehearsal: a rehearsal of
    // rung 18 or 19 has to put the body in the room the RUNG would find, and two scans that
    // disagree put "where the staging thinks the room is" and "where the rung thinks it is" in
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
     *
     * <p><b>Not {@code JourneyPortalEntry.standable}, and not interchangeable with it.</b> This is
     * the only "can stand here" predicate in the package that refuses a cell whose FLOOR is fluid —
     * which is the whole point here, since that fluid is the lava pool under the end portal — and
     * the only one that asks nothing about fire. {@code standable} is the A*-matching predicate for
     * choosing a doorstep to WALK to; this one chooses a cell to DIG DOWN INTO, so it refuses things a
     * walk would happily accept and accepts hazards a walk would refuse. See that method's note for
     * the three-way comparison.
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
    // Driving — the shared walk, NOT a copy. See `WorldDriverJourneyScenes.walkToColumn`.
    // =====================================================================================
    //
    // There used to be a private copy here, and it was the version from BEFORE two fixes the
    // shared one has since received — because a copy does not receive fixes:
    //
    //   * `<what>.arrivedY`. `Goal.XZ` has no y term, so `arrivedDistance=0` is equally true of a
    //     body standing in the right column and thirteen blocks up its own pillar. Every rung
    //     after a walk is planned as if the body were on the ground.
    //   * `<what>.gotoEnd.<attempt>` on the ARRIVAL branch. `arrivedDistance=4, walkAttempts=1`
    //     is the same two digits for a body that walked here and stopped, and one the walker GAVE
    //     UP on four blocks out — and the tolerance accepts both.
    //
    // Neither had reached this file. The one call site passed `tolerance=2`, where the old test
    // `away <= tolerance + 3` and the shared `away <= ARRIVED_WITHIN` are both `<= 5`, so the swap
    // changed nothing but what gets recorded. (The same morning, 2026-08-23, a different pair of
    // "six identical lines" turned out to be the same defect twice — a duplicate is not merely
    // untidy, it is a place fixes do not arrive.)

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
            ctx.fail("the shaft cannot reach the portal room: target y=" + targetY + ", still at " + at
                    + " after " + cap + " attempts (per-block readings are in shaft.*)");
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
     *
     * <p><b>Twin of {@code JourneyShaft.supportUnder}, and the one line that differs is the whole
     * reason both exist.</b> That one reads {@code JourneyShaft.sceneLevel} — the scene's arena —
     * while this one reads {@link #levelOf}, the level the BODY is in, because after rung 19 the
     * body is in the end and the scene is not. Merging them onto either rule breaks the other
     * caller: this one would scan overworld terrain at end coordinates, that one would change
     * behaviour for callers that are correct today. Fix a bug in the SHARED part — the corner
     * fallback, the {@code blocksMotion} rule — in both. */
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

    /** The level the BODY is in, which after rung 19 is not {@code ctx.level()}. Every read in this
     *  file goes through here for that reason — a scan of the overworld for an end crystal finds
     *  nothing and does not say it looked in the wrong world. */
    private static ServerLevel levelOf(JourneyRig rig) {
        return (ServerLevel) rig.player().level();
    }

    /** What to pillar with: whichever spoil the body is actually carrying most of. A tower asked for
     *  a block the body does not hold reports "stuck (no Y gain — out of blocks?)" while the
     *  inventory is full, which names the wrong problem convincingly enough to cost a round.
     *
     *  <p>The argmax is {@link JourneyShaft}'s; only {@link #PILLAR_BLOCKS} is this family's, and it
     *  differs by exactly one entry — {@code minecraft:end_stone}, which no shaft ever yields and
     *  which is most of what an outer island is made of. Same shape as the note below: two copies
     *  of a loop over two lists, except this one was caught before it drifted. */
    private static String pillarBlock(JourneyRig rig) {
        return JourneyShaft.pillarBlock(rig, PILLAR_BLOCKS);
    }

    // bestWeapon / holdBestWeapon moved to JourneyRig — they existed here AND in JourneyNetherRungs,
    // had drifted apart, and the rungs that fight FIRST (FOOD, BED) could reach neither.

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

    /** A position for a record line, or "none" — every evidence key in this file may be asked about
     *  something that was not found, and "null" is a worse answer than a word. */
    private static String xyz(BlockPos at) {
        return at == null ? "none" : at.toShortString();
    }
}
