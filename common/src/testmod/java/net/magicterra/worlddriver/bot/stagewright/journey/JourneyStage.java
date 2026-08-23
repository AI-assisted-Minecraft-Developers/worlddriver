package net.magicterra.worlddriver.bot.stagewright.journey;

/**
 * The rungs of one vanilla playthrough, in the order they must be climbed.
 *
 * <p>This enum is the spine of the journey suite: it is the stage list, the prerequisite chain, and
 * the ratchet's vocabulary all at once. Everything else in this package is machinery for getting
 * from one rung to the next and writing down what happened.
 *
 * <h2>What the journey is actually testing</h2>
 *
 * <b>Not whether the bot can work the game out.</b> Every stage is scripted against a world whose
 * seed is fixed and whose landmarks have been surveyed (see {@link JourneyRoute}), so the caller
 * plays the part of an agent with perfect knowledge of this world. What is left under test is the
 * only thing worth testing here: <b>given a correct plan, can worlddriver's API surface execute
 * it to completion?</b> A stage that fails names a verb that cannot do its job — a missing route, a
 * process that never finishes, an interaction the driver cannot express — rather than a planner
 * that guessed wrong. Those are different bugs with different owners, and a suite that cannot tell
 * them apart reports neither.
 *
 * <p>The corollary, and the rule for anyone extending this: <b>when a stage cannot be scripted,
 * that is the finding.</b> Write the stage anyway, let it fail, and record what the API could not
 * express. Reaching for a new engine capability before the scripted attempt has failed inverts the
 * whole exercise — it grows the surface on a guess instead of on a measurement.
 *
 * <h2>Nothing is staged</h2>
 *
 * There is no {@code give}, no {@code setblock}, no {@code fill} and no teleport anywhere in this
 * ladder. Every item is obtained by playing, from an empty inventory at world spawn. That is what
 * makes reaching a rung mean anything: {@code N3_IRON} is true because the run smelted iron, not
 * because a scene handed it over. {@link JourneyLedger} counts staging calls so the claim is
 * measured rather than asserted — see {@code JourneyLedger#stagingCalls()}.
 *
 * <h2>required vs optional</h2>
 *
 * A rung the engine has already climbed ships {@code required=true} and any regression is a RED. A
 * rung ahead of the engine ships {@code required=false}: it still runs, still fails honestly, and
 * still records why, but it does not break the gate for everyone else. Promotion is deliberate and
 * one-way — when a stage first goes green, it is flipped to required in the same commit, which is
 * what stops a frontier from quietly sliding backwards. {@link JourneyLedger#FLOOR} is the same
 * ratchet expressed as a single number, and the verdict scene is where it bites.
 */
public enum JourneyStage {

    // ---- Chapter A: the overworld ladder (ROADMAP N0-N5) ----

    /** The survey itself: this world is the world the rest of the ladder was written against. */
    RECON("侦察", Chapter.SETUP, true),

    /** A body at world spawn, empty-handed and alive. Everything downstream stands on this. */
    SPAWN("出生", Chapter.OVERWORLD, true),

    /** Logs in hand — ROADMAP N0's first half. */
    WOOD("木头", Chapter.OVERWORLD, true),

    /** Planks, a crafting table, and the wooden tools that open stone. */
    WOOD_TOOLS("木工具", Chapter.OVERWORLD, true),

    /** Cobblestone and the stone tier — ROADMAP N2. */
    STONE_TOOLS("石工具", Chapter.OVERWORLD, true),

    /** Fed: something killed or foraged and eaten, hunger recovered. */
    FOOD("食物", Chapter.OVERWORLD, true),

    /** A bed, slept in, spawn point moved — ROADMAP N1's durability keystone. */
    BED("床", Chapter.OVERWORLD, false),

    /** A furnace and fuel: the first station that turns time into materials. */
    FURNACE("熔炉", Chapter.OVERWORLD, true),

    /** Iron ore mined and smelted into ingots — ROADMAP N3. Gating as of 2026-08-10; see
     *  {@link JourneyLedger#FLOOR} for what "reliable" had to mean before it could be. */
    IRON("铁", Chapter.OVERWORLD, true),

    /**
     * A bucket and a flint-and-steel: the portal toolkit.
     *
     * <p>It said "two buckets" until the ladder tried to pay for them, and the reason first written
     * here for cutting it to one was wrong: "pour water over the lava sources and they turn to
     * obsidian where they stand". They do — and that obsidian is unusable, because taking it out of
     * a lava lake needs a DIAMOND pickaxe, which is four rungs above anything this route has. A
     * portal is not found, it is CAST: you build a mould, place lava sources into it one bucket at a
     * time, and let water convert them where you want the frame to be.
     *
     * <p>One bucket is still the right answer, for the other reason. Water only has to be carried
     * ONCE — placed as a source at the build site, it stays there and flows over each cell as it is
     * filled — so the same bucket can shuttle lava for every one of the ten frame blocks. Two
     * buckets would only save the walk that places the water. Four ingots, not seven, and that
     * matters because seed 5471's veins yield one to three ore each.
     */
    PORTAL_KIT("桶与打火石", Chapter.OVERWORLD, true),

    /**
     * Obsidian cast from lava with water — ROADMAP N4.
     *
     * <p>What a green row claims: the body fetched its own lava from the seed's nearest reachable
     * pool — a 36-block shaft it dug itself — and cast obsidian into a cell it named beforehand,
     * with {@code staging.calls=0}. What it does not claim: that the scripted ascent works alone
     * (every green so far carries {@code exit.walkerFallback=true}), or that the body survives
     * anything ({@code body.invulnerable=true} rides on every row of this track).
     *
     * <p><b>Promoted and demoted twice: on three greens, then on five.</b> Both regressions were the
     * same hazard — the tunnel mines horizontally through unsurveyed rock, holes a cave roof, and the
     * body falls out of reach of the pool. And both times the qualifying runs had never once hit it:
     * {@code tunnel.fell} appears in none of the three, and in none of the five.
     *
     * <p>Raising the bar from three to five was therefore treating the wrong quantity. <b>A run that
     * does not exercise a known recovery says nothing about that recovery</b>, and no number of such
     * runs adds up to evidence. The demotion after five is not "unlucky twice"; it is what a criterion
     * measuring the wrong thing produces.
     *
     * <p>What the next promotion has to show, then, is not a count: <b>each known hazard's recovery
     * observed working at least once</b>. For this rung that currently means {@code tunnel.fell}
     * followed by a run that still reaches the pool. The fall recovery has been seen twice and failed
     * both times — the second with {@code climb.0.stalled} on a body standing on solid ground, ceiling
     * clear, holding 104 cobblestone — so it does not work yet, and five green runs that skirted it
     * never said otherwise.
     *
     * <p>Four other hazards were found and fixed along the way, each invisible to the sample before
     * it: a pour whose line a seagrass blocks, a cast target chosen by a fluid test that a waterlogged
     * plant also passes, an approach ending eight blocks past its own five-block ray, and — two rungs
     * below the floor — a smelt that never asked whether it had room to set a furnace down.
     */
    OBSIDIAN("黑曜石", Chapter.OVERWORLD, false),

    /** A lit nether portal — ROADMAP N5. */
    PORTAL_LIT("传送门点火", Chapter.OVERWORLD, false),

    // ---- Chapter B: the nether ----

    /** Standing in {@code minecraft:the_nether}. */
    NETHER("进入下界", Chapter.NETHER, false),

    /** Blaze rods, which means a fortress reached and blazes fought. */
    BLAZE_ROD("烈焰棒", Chapter.NETHER, false),

    /** Ender pearls, which means endermen fought (overworld night or the nether's warped forest). */
    ENDER_PEARL("末影珍珠", Chapter.NETHER, false),

    /** Eyes of ender crafted from the two above. */
    EYE_OF_ENDER("末影之眼", Chapter.NETHER, false),

    // ---- Chapter C: the end ----

    /** The stronghold's portal room reached. */
    STRONGHOLD("要塞", Chapter.END, false),

    /** Every frame filled, portal active. */
    END_PORTAL("末地传送门", Chapter.END, false),

    /** Standing in {@code minecraft:the_end}. */
    END("进入末地", Chapter.END, false),

    /** The dragon dead. The whole point. */
    DRAGON("屠龙", Chapter.END, false);

    /** Which act of the playthrough a stage belongs to — used only for reporting. */
    public enum Chapter { SETUP, OVERWORLD, NETHER, END }

    private final String label;
    private final Chapter chapter;
    private final boolean gating;

    JourneyStage(String label, Chapter chapter, boolean gating) {
        this.label = label;
        this.chapter = chapter;
        this.gating = gating;
    }

    /** The stage's name in the language the ROADMAP is written in, for log lines and records. */
    public String label() { return label; }

    public Chapter chapter() { return chapter; }

    /**
     * Whether this rung is currently held to a REQUIRED standard.
     *
     * <p>This is the source the scene registration reads, so flipping a stage here is what promotes
     * it — there is no second list to keep in step. It deliberately duplicates nothing: the scene's
     * {@code required} flag IS this flag.
     */
    public boolean gating() { return gating; }

    /** The rung immediately below this one, or null for {@link #RECON}. */
    public JourneyStage previous() {
        return ordinal() == 0 ? null : values()[ordinal() - 1];
    }

    /**
     * The rung that must be climbed before this one is attempted.
     *
     * <p>Normally the one below, because a playthrough really is mostly a line. The exception is
     * {@link #BED}, and it is worth stating why the ladder needed this method at all rather than
     * just being reordered.
     *
     * <p><b>A prerequisite is not the same thing as an order.</b> Every rung blocks everything above
     * it — that is what makes the chain honest — so a rung sitting in the middle of the list is
     * asserting that nothing above it can happen until it does. For {@code BED} that assertion is
     * simply false: a bed is a durability keystone for a real player (it moves the spawn point so a
     * death does not undo the run), and <b>nothing on the road to the dragon needs one</b>. On this
     * track it is doubly irrelevant, because the headless body cannot die.
     *
     * <p>It came to matter because of the terrain. This paragraph used to say "a swamp whose only
     * animals are cows and frogs", which the rung disproved the first time it actually ran: there is
     * a flock of four to six white sheep around {@code -25,64,70}, and the food rung's own scan
     * reports {@code [chicken, cow, frog, pig]}. The true statement is narrower and still decisive —
     * <b>the sheep are ~83 blocks from where the food rung ends and ~90 from spawn</b>, far enough
     * that reaching them is its own leg with its own ways to fail. Left in the line, "could not find
     * a sheep" would have blocked iron, the portal and the whole nether — a side quest holding the
     * critical path hostage, and every rung above it reporting BLOCKED for a reason that has nothing
     * to do with them.
     */
    public JourneyStage requires() {
        return switch (this) {
            case RECON -> null;
            // Skips BED deliberately — see above.
            case FURNACE -> FOOD;
            default -> previous();
        };
    }

    /**
     * Whether this rung is on the road to the dragon.
     *
     * <p>Only the critical path counts toward {@link JourneyLedger#height()}: a run that got a bed
     * but no iron has not climbed higher than one that got iron and no bed, and a height that said
     * otherwise would make the ratchet reward the wrong work.
     */
    public boolean criticalPath() {
        return this != BED;
    }

    // An `upTo()` used to sit here —「every rung at or below this one, what reaching this stage
    // implies」— with no callers and, worse, a different answer from the one the ladder actually
    // uses. It went by ORDINAL, so it swept BED in; `JourneyLedger.height()` walks the same list
    // and skips exactly the rungs `criticalPath()` excludes. Two definitions of「what
    // this rung implies」that disagree about a side quest, one of them unreachable — the next
    // caller to reach for the convenient one would have re-armed BED as a prerequisite silently.
    // If the concept is wanted again, derive it from criticalPath() rather than from ordinal().

    /** The highest rung declared, i.e. what finishing the game means here. */
    public static JourneyStage summit() {
        return values()[values().length - 1];
    }
}
