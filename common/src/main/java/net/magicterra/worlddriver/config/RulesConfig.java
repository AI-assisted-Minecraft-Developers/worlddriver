package net.magicterra.worlddriver.config;

import net.magicterra.worlddriver.api.methods.setting.RulesSettings;

/**
 * Settings read by the move catalogue, its constraints and its costs ({@code bot/rules}). Each sits in the lowest layer
 * that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class RulesConfig {

    /**
     * Per-axis cap, blocks, on the box one search scans for entities ({@code SearchScope.gather});
     * {@code Level.getEntities} costs by volume, and a capped scope reports {@code snapshotTruncated}.
     */
    public static volatile int snapshotBoxMax = RulesSettings.DEFAULTS.snapshotBoxMax();

    /**
     * Extra g-cost on a DRY diagUp (base 19); &gt;0 prefers the cardinal stepUp+walk L over a diagonal riser's late
     * jump.
     */
    public static volatile double pathfinderDiagAscendPenalty = RulesSettings.DEFAULTS.pathfinderDiagAscendPenalty();

    /**
     * Extra g-cost on a step down with a block at launch head height past it (R1: 6 took 148.8 s to 142.1 s); see
     * {@code StepDown}.
     */
    public static volatile double pathfinderHeadStrikeTax = RulesSettings.DEFAULTS.pathfinderHeadStrikeTax();

    /**
     * TOTAL g-cost of one {@code bridgePlace} edge — placing a block into an air gap and walking onto it. Aerial
     * bridging is SLOW (sneak-place ~1 block/15 ticks), RISKY (overshoot off the fresh 1-wide block) and consumes
     * inventory, so it must be a LAST resort, not a casual default: when it is too cheap the planner happily commits a
     * ~30-block aerial bridge straight over a deep valley instead of descending and crossing the floor — a route the
     * Navigator can barely execute, so the bot freezes (the sudden-slowdown or blocked stall on mountain terrain). A
     * high cost makes A* prefer any ground route (descend a valley, go around) and bridge only a genuinely unavoidable
     * short gap. Does NOT touch the depth penalty, so basin-dive protection is unchanged. Old hard-coded value was 30
     * (walk 10 + {@link net.magicterra.worlddriver.bot.rules.Move#PLACE_COST 20}). Default {@value}.
     */
    public static volatile double pathfinderBridgeCost = RulesSettings.DEFAULTS.pathfinderBridgeCost();

    /**
     * Base cost of a {@link net.magicterra.worlddriver.bot.rules.moves.PillarUp} rung (was the hard-coded
     * {@code Move.PILLAR_COST} 30). Placement consumes inventory, and the old price made a single pillar rung BEAT a
     * ~6-walk stair detour — the bot spent build blocks where a free walk route existed (wd.bridgeStepTwoBypassNoPlace,
     * 2026-07-20; the single-plank bridge battery's "bypass exists → don't spend materials" contract). 150 prices in
     * material scarcity AND execution reality the way {@link #pathfinderBridgeCost} does for horizontal bridging: a
     * pillar rung live is a failure-prone jump-place (deep-water takeovers measured ~27s) plus a spent block, so a
     * ~10-walk stair detour should win; a pillar with NO walkable alternative (water climb-outs, sealed pits) is still
     * far cheaper than futile. Live-tunable via mc.bot.setting.
     */
    public static volatile double pathfinderPillarCost = RulesSettings.DEFAULTS.pathfinderPillarCost();

    /**
     * Rung price at {@link #pathfinderPillarStockBlocks}+ blocks, sliding from {@link #pathfinderPillarCost} at none. A
     * dry rung is ~14 ticks live vs ~21 for stairUpBreak (34-level hill: 53 s at 150, 41 s at 20). Default = no slide.
     */
    public static volatile double pathfinderPillarCostStocked = RulesSettings.DEFAULTS.pathfinderPillarCostStocked();

    /** Stock at which a pillar rung costs {@link #pathfinderPillarCostStocked}. */
    public static volatile int pathfinderPillarStockBlocks = RulesSettings.DEFAULTS.pathfinderPillarStockBlocks();

    /**
     * Max DRY (no-water) fall the planner takes as a plain {@code Fall} move, in blocks. <b>Default 4 — one block PAST
     * Baritone's no-damage cap of 3</b>, so {@code Fall(4)} is live on every default run and only {@code Fall(5)} is
     * inert; set 3 for damage-free routing. Raising it (≤5) buys a small-damage drop (vanilla charges
     * {@code ceil(distance-3)} HP: 4 = 1 HP = 0.5♥, 5 = 2 HP = 1♥) instead of a dirt staircase with {@code BridgePlace}
     * — the smooth-jungle-descent lever. A higher fall is cheaper than a place-bridge (Fall(5)=35 vs BridgePlace≈80),
     * so once enabled A* prefers the natural drop. Survival-sensitive (the bot takes the damage). Capped at 5 (1
     * heart); taller no-bucket descents stay {@link net.magicterra.worlddriver.bot.rules.moves.FallIntoWater} (into
     * water) or place-bridges.
     *
     * <p>Default raised 3→4 (2026-06-09): a 4-block fall is the MINIMUM non-zero fall (0.5♥, what a vanilla player
     * takes constantly) and an A/B over a vine jungle canopy HALVED place-bridges (23→11) and cleared a descent the
     * cap-3 bot wedged on for 90 s — the dirt staircase was the long-haul start wedge. fall5 (1♥) stays
     * catalogued-but-inert at 4 (raise to 5 to enable). autoEat/regen absorb the small drip; survival can lower it back
     * to 3 via mc.bot.setting.
     */
    public static volatile int pathfinderMaxDryFall = RulesSettings.DEFAULTS.pathfinderMaxDryFall();

    /**
     * Baritone {@code allowParkour4} analogue — enables 4-block cardinal leaps in A*. Off by default because the leap
     * is at the edge of vanilla sprint-jump physics; turning it on without jump-boost / Speed makes the bot pick
     * unreachable goals. Read every Move.Parkour4.valid call.
     */
    public static volatile boolean allowParkour4 = RulesSettings.DEFAULTS.allowParkour4();

    /**
     * Refuse to PLAN a parkour leap whose gap falls out of the world, whenever the bot could bridge instead. See
     * {@code Move.overTheVoid}: the cost model cannot express this preference without pricing a placed block below a
     * walked one, and the two mistakes are not symmetric — a misjudged leap over a pit costs a climb, a misjudged one
     * over the void ends the run for a bot that cannot die.
     */
    public static volatile boolean pathfinderForbidParkourOverTheVoid =
            RulesSettings.DEFAULTS.pathfinderForbidParkourOverTheVoid();

    /**
     * Planner gate: forbid a PARKOUR leap whose landing is the SURFACE of a DEEP floating-water pocket
     * ({@link net.magicterra.worlddriver.bot.world.WorldView#isFloatingWater}: water at the landing foot AND the cell
     * below it, i.e. ≥2 deep with no floor under the surface).
     *
     * <p>Every descending/leaping move already refuses a FULLY-submerged landing ({@code isWater(to) &&
     * isWater(to+1)}), but a leap onto the SURFACE of a deep pocket (head in air, but ≥2 of water below the feet)
     * passes that gate — and a buoyant bot that lands there cannot climb back out: every grounded climb-out
     * ({@code pillarUp}/{@code stepUp}/{@code diagUp}) gates itself off floating water, so the only exit is the
     * ~25×-slow toolless underwater bank-dig or a swim to a gentler shore, costing tens of seconds (live #47 -870: a
     * {@code parkourDescend2d1} from the dry bank at y63 landed on the y62 surface of a 4-deep pocket; the bot then
     * bob-stalled ~60-77 s climbing out — and a probe from inside the pocket shows A* immediately routes BACKWARD to
     * y68, proving the dive is a pure dead-end trap the committed plan would have BRIDGED over). Forbidding the leap
     * leaves the leap's cheaper alternatives (continue the bridge-over, or a shallower entry) for A*.
     *
     * <p>Scoped to PARKOUR leaps only (never necessary — you swim or bridge, you don't LEAP into deep water), so plain
     * {@code stepDown}/{@code fall}/{@code diagDown} water entries (which a real river crossing needs) are untouched,
     * and it is a hard per-move FORBID, not a cost-tax (so it can't inflate the A* node budget the way a floating
     * climb-out surcharge did — that was reverted). 1-deep shallow water (solid floor below → {@code isFloatingWater}
     * false) and leaping OVER water onto a dry far bank are unaffected.
     *
     * <p>Default ON: live A/B at the #47 -870 lake west-pocket (tp the bot to the dry bank, goto across the deep SW
     * pocket, repeated) — OFF leaps a {@code parkourDescend2d1} onto the -866,62 deep-pocket surface and bobs (max
     * totStuck 17, 56-94 inW ticks); ON routes a {@code stepDown} chain instead (max totStuck 7, 38 inW ticks, fewer
     * total ticks, ARRIVES every run). GameTest 53/53 required + every water arena (deepWaterCross/
     * buoyantWall/riverSheerBank/waterLowBank/tallBankDig/waterClimbOutRoute/waterFarAimBankCorner/
     * vineOverWater/parkourAscend) still ARRIVED with it ON, so deep-water CROSSINGS (which swim, not
     * parkour-stepping-stone) are unaffected. Flip OFF via {@code mc.bot.setting} if a future geometry needs a parkour
     * leap into deep water as its only exit.
     */
    public static volatile boolean pathfinderForbidParkourIntoDeepWater =
            RulesSettings.DEFAULTS.pathfinderForbidParkourIntoDeepWater();

    /**
     * Sibling of {@link #pathfinderForbidParkourIntoDeepWater}, the TAKEOFF complement: forbid any parkour LAUNCH from
     * a DEEP floating-water source ({@link net.magicterra.worlddriver.bot.world.WorldView#isFloatingWater}: water at
     * foot AND below). A floating bot has no floor to push off, so it physically can't sprint-jump a gap — the grounded
     * climb-out siblings (StepUp/StepUp2/DiagonalAscend/PillarUp/StairUpBreak) ALL already gate
     * {@code isFloatingWater(from)} for exactly this reason, but the whole Parkour family
     * (Ascend/2/3/4/2d/3d/Descend/Place) was the gap A* exploited: when the grounded climb-outs are forbidden at a
     * water edge, A* picks an unguarded parkour leap FROM the floating cell, which the executor can only bob +
     * fall-back against.
     *
     * <p>Live #47 J2 (-799,429 → -680,280, 2026-06-26): A* committed a {@code parkourAscend2} takeoff from a floating
     * water cell at -682,70,308; the buoyant bot couldn't launch it (cur2=3.1, ~2.2-block gap), bobbed 185 ticks (~9s)
     * on that one node with repeated {@code fall2/3/4} re-entries, totStuck 409, ~90s region of jank — the DOMINANT
     * residual on tractable water terrain (the ascent-ram domain was non-dominant here).
     *
     * <p>A hard per-move FORBID (not a tax), mirroring the landing guard: the leap is physically unexecutable, so
     * removing it just routes A* through the swim-to-edge + {@code stepUp}/{@code diagUp} climb-out instead. 1-deep
     * shallow water (solid floor → {@code isFloatingWater} false) is unaffected. Default ON; the gate below predates
     * the flip and describes the live A/B (the landing-side sibling defaults ON once proven; flip after A/B confirms
     * 409→low + no regression).
     */
    public static volatile boolean pathfinderForbidParkourFromFloatingWater =
            RulesSettings.DEFAULTS.pathfinderForbidParkourFromFloatingWater();

    /**
     * Approach-runway gate for {@link net.magicterra.worlddriver.bot.rules.moves.ParkourAscend} (the +1-up cardinal
     * parkour leap): a rising sprint-jump needs horizontal MOMENTUM at launch (~5.6 b/s), but a bot climbing OUT of a
     * bank reaches the crest via a {@code stepUp}/{@code diagUp} that decelerates it to ~0 b/s, then fires the leap
     * from a standstill → it lands short and falls back down the staircase (live #47 J2 dry crest -682,69,310: no
     * runway behind the lip, 49/82/14 fall-back ticks, totStuck 579 — the DOMINANT climb-out bob after the
     * floating-water and ascent-ram fixes). When ON, {@code ParkourAscend.valid} additionally requires the cell
     * directly BEHIND the launch (opposite the leap, same Y) to be {@code canStandAt} — a flat run-up the bot can
     * accelerate along. A thin-lip crest (staircase drops away behind → behind cell not standable) is forbidden, so A*
     * substitutes a {@code stepUp} staircase the executor CAN make from rest; a flat-topped crest (standable behind →
     * real run-up) is unaffected. Per the diagnosis this is the single highest-leverage lever on the bank-crest
     * ascending-mount class — but it can SHIFT thrash onto the substitute chain at a true +2 gap with no stepUp
     * alternative, and totStuck is A*-route-bimodal (unprovable by clean A/B), so validate by committed-plan + video
     * (no-runway parkourAscend2 gone, smooth stepUp climb-out), NOT totStuck.
     *
     * <p><b>Default ON (task#86, 2026-07-19).</b> The gap #53 self-shaft dig-up backslide ({@code wd.selfShaftDigUp})
     * is the same class of bug on the ASCENT side: a bare-hand {@code Goal.YLevel} climb pillars a 1-wide free-standing
     * column up beside the slab, and near the top A* re-plans a {@code parkourAscend2} leap from the pillar TOP onto
     * the slab (cheaper than 2 more pillars) — but a stationary 1-wide pillar top has no run-up, so the executor
     * launches into the void and free-falls ~20 blocks straight down its own column (strideFloorGuard cannot arrest an
     * airborne straight-down fall — there is no face to place a floor against). The launch cell's below-neighbour is
     * the pillar (so the coarse {@link net.magicterra.worlddriver.bot.rules.Move#hasRunway} passes); only THIS
     * approach-runway gate — the cell BEHIND the launch must be {@code canStandAt} — rejects the leap, so A*
     * substitutes the straight-up pillar and tops out clean. Dogfood A/B (neoforge, byte-identical ×3): OFF ⇒
     * {@code worstBackslide=20.252203415101263}; ON ⇒ {@code 1.2522034151012633} (the normal pillar-jump-arc settle),
     * {@code reached=true}, NO required scene regressed.
     */
    public static volatile boolean pathfinderParkourAscendNeedRunway =
            RulesSettings.DEFAULTS.pathfinderParkourAscendNeedRunway();

    // Last, after every field above: see BotConfig.initialize.
    static {
        BotConfig.initialize();
    }

    private RulesConfig() {}
}
