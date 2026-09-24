package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/**
 * What this ladder's difficulty actually is — measured, not asserted.
 *
 * <p>Its own file because the two things in it are one subject read twice: the verdict row that
 * TELLS a reader how hard the climb was, and the sweep that MAKES that sentence true. Splitting
 * them would let one drift from the other, which is exactly the failure this file was written to
 * close.
 *
 * <p><b>The failure.</b> A hardcoded verdict of "the world is pinned by StageWright (clock frozen
 * at midnight, doMobSpawning=false), so this run has no hostile mobs at any point" is wrong: every
 * clause before the "so" is true and the clause after it is false. The run of 2026-08-22 falsified
 * it in its own results file: {@code Player659 was killed by Witch using magic}, eight blows deep,
 * on rung 7. A gamerule switches off a MECHANISM, not a PHENOMENON — {@code doMobSpawning} gates
 * {@code NaturalSpawner}, and this witch never went through it:
 *
 * <pre>{@code
 * // SwampHutPiece.postProcess, at WORLD GENERATION time
 * Witch $$14 = EntityType.WITCH.create(worldGenLevel.getLevel());
 * $$14.setPersistenceRequired();                      // and therefore never despawns
 * $$14.finalizeSpawn(worldGenLevel, …, MobSpawnType.STRUCTURE, null);
 * worldGenLevel.addFreshEntityWithPassengers($$14);   // straight into the level
 * }</pre>
 *
 * <p>Seed 5471 spawns in a swamp, so for THIS seed that sentence is false on every run. The
 * measurement is cheap, so it is taken rather than assumed.
 *
 * <p><b>Why removal rather than a reflex.</b> The driver ships every threat reflex OFF
 * ({@code BotConfig.autoRetreat/autoFight/autoDodge/autoHeal/autoShield} are all {@code false},
 * each with a "so a quiet bot stays quiet" note), so the bot took six poison ticks and two potions
 * without reacting — not because a reflex failed but because none was armed. Arming them is a
 * different topology's question: the world-pin javadoc already committed to "a topology that runs
 * the same ladder in a live world — a separate run, not a flag". This ladder's contract is a
 * peaceful climb, and the honest fix is to make the world match the contract and say so, rather
 * than to quietly change what every rung is being asked.
 *
 * <p>A hut's coordinates are also exactly the kind of thing the ladder is allowed to know: the
 * standing rule for this suite is "assume the caller has complete knowledge of this seed", and a
 * permanent, generation-placed
 * witch is seed knowledge in the same way the first tree and the third iron vein are.
 */
final class JourneyPeace {

    private JourneyPeace() {}

    /**
     * How far out to look for the hut, <b>counted in placement regions, not chunks</b> — the same
     * trap {@link JourneyRoute#surveyStructure} documents for the fortress. The swamp-hut structure
     * set has spacing 32, so two rings is roughly a thousand blocks in every direction, and the
     * search returns at the FIRST ring that yields anything: a seed whose spawn IS a swamp pays for
     * ring 0 alone. Bounded rather than generous, because this runs on the ladder's critical path.
     */
    private static final int HUT_SEARCH_RINGS = 2;

    /** Chunk-ticket radius around the hut. 3 clears the level an entity section needs to load. */
    private static final int HUT_PIN_RADIUS = 3;

    /**
     * Remove the hostiles world GENERATION placed on this seed's route, and write down every one.
     *
     * <p><b>Runs at SPAWN, not at RECON</b>, for the reason rung 2 already records about the herd
     * survey: recon runs before there is a player entity, and this needs {@link JourneyRig#settle}
     * to spend a beat while chunks arrive. Six runs died on the error "no player entity yet: the
     * SPAWN stage did not create an avatar" before that lesson was first learned; it is the same
     * lesson.
     *
     * <p><b>The false zero this is shaped to avoid.</b> Locating asks the GENERATOR and needs no
     * chunk loaded, but reading entities needs the chunk in memory AND a tick for its entity
     * sections to arrive. A scan on the same line as the pin reports "0 mobs" — an answer that
     * looks exactly like a clean world and actually means "I cannot see that far". So: locate, pin,
     * WAIT, then look, and record the radius that was searched so the zero is readable.
     *
     * <p><b>{@code discard()} and not a kill.</b> A kill drops items and plays a sound; those drops
     * would sit in the world for the rest of the climb and land in the {@code dropCensus} rows that
     * later rungs read to decide whether a hunt banked anything. Removing a mob silently is the
     * only removal that does not change a reading somewhere else.
     *
     * <p><b>Bounded to this box on purpose.</b> Rungs 14 and 15 call {@code rig.liveWorld(true)} and
     * need blazes and endermen alive; a global "remove all hostiles" would make the top of the ladder impossible
     * while looking like a safety improvement.
     */
    static void sweepStructureHostiles(SceneContext ctx, JourneyRig rig, Runnable then) {
        ServerLevel level = ctx.level();
        BlockPos spawn = level.getSharedSpawnPos();

        JourneyRoute.Located hut = JourneyRoute.surveyStructure(ctx, Level.OVERWORLD,
                BuiltinStructures.SWAMP_HUT, "swamp hut", spawn, HUT_SEARCH_RINGS);
        BlockPos at = hut.found().where();
        rig.evidence("peace.hut", at == null
                ? "no swamp hut within " + HUT_SEARCH_RINGS + " rings on this seed (searched for "
                        + hut.millis() + " ms)"
                : at.toShortString() + " (" + Math.round(hut.found().distance()) + " blocks from spawn,"
                        + " searched for " + hut.millis() + " ms, " + hut.rings() + " rings)");
        if (at == null) {
            rig.evidence("peace.swept", "nothing to remove: no hut");
            then.run();
            return;
        }

        rig.pinDistant(at, HUT_PIN_RADIUS);
        // The beat. `pinDistant` applies the ticket; the chunks it pulls in — and the entity
        // sections inside them — arrive on later ticks, and a witch that has not arrived cannot be
        // found by a scan that runs now.
        rig.settle(new HoldStill(40), 100, () -> {
            // EVERY loaded overworld monster, not a box around the hut, and the reason is that a
            // witch WALKS. `setPersistenceRequired` stops her despawning; it does not pin her to the
            // hut, and the bot that died on 2026-08-22 died at -51,62,67 — wherever she had got to
            // by rung 7, which no radius chosen at SPAWN can predict. With `doMobSpawning=false` the
            // only monsters an overworld can hold are the ones generation placed, so a sweep this
            // wide cannot take anything the ladder needs: the rungs that DO need mobs turn spawning
            // back on themselves (`rig.liveWorld(true)`), later, in the nether.
            //
            // `ServerLevel.getEntities(EntityTypeTest, Predicate)` materialises a List, so the
            // discards below cannot mutate a storage that is still being iterated. The no-argument
            // `getEntities()` on Level does NOT work here — ServerLevel overloads the name.
            var doomed = level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Monster.class), m -> true);
            int n = 0;
            for (Monster m : doomed) {
                rig.evidence("peace.removed#" + (++n), BuiltInRegistries.ENTITY_TYPE.getKey(m.getType())
                        + " @ " + m.blockPosition().toShortString()
                        + " (" + Math.round(Math.sqrt(m.blockPosition().distSqr(at))) + " blocks from the hut, "
                        + "persistent=" + m.isPersistenceRequired() + ", health "
                        + String.format(java.util.Locale.ROOT, "%.1f", m.getHealth()) + ")");
                WorldDriverCommon.LOG.info("[journey/peace] discarding {} at {}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()), m.blockPosition());
                m.discard();
            }
            // Recorded even when it is zero, and WITH the scope it was taken over: a bare "0" reads
            // identically whether the world is clean or the scan could not see that far, and this
            // ladder has already paid once for a hostile-count sentence that carried no scope.
            rig.evidence("peace.swept", n + " hostile mobs removed (scanned every Monster in the "
                    + level.getChunkSource().getLoadedChunksCount() + " overworld chunks loaded at the"
                    + " time; the hut at " + at.toShortString() + " was pinned for " + HUT_PIN_RADIUS
                    + " rings and 40 ticks were waited)");
            then.run();
        });
    }

    /**
     * The verdict's world-pin row, read from the live rules instead of asserted.
     *
     * <p>Three things, in the order a reader needs them: what the pin actually SAYS right now, what
     * the pin does NOT cover, and where to find what was done about it. The middle clause is the
     * whole point — it is the sentence without which a constant verdict would be false.
     *
     * <p>Deliberately does NOT census hostiles here. A verdict-time scan only sees loaded chunks, so
     * on a run that ended in the nether it would report a confident zero about an overworld nobody
     * had in memory. The measurement that means something was taken at SPAWN, with its radius
     * written beside it; this row points at it rather than inventing a weaker one.
     */
    static String worldPinReading(SceneContext ctx) {
        GameRules rules = ctx.level().getServer().getGameRules();
        long dayTime = ctx.level().getDayTime() % 24_000L;
        return "world pinned by StageWright: doMobSpawning=" + rules.getBoolean(GameRules.RULE_DOMOBSPAWNING)
                // RULE_DAYLIGHT, not RULE_DAYLIGHT_CYCLE — the field is named after the concept and
                // the rule after its command id ("doDaylightCycle"), and only the second is visible
                // from a log line.
                + ", doDaylightCycle=" + rules.getBoolean(GameRules.RULE_DAYLIGHT)
                + ", doWeatherCycle=" + rules.getBoolean(GameRules.RULE_WEATHER_CYCLE)
                + ", dayTime=" + dayTime + " (" + (dayTime >= 13_000 ? "night" : "day") + ")"
                + ". ⚠️ doMobSpawning disables NaturalSpawner, which does not mean \"no hostile mobs\":"
                + " mobs placed during world generation bypass it (SwampHutPiece calls"
                + " addFreshEntityWithPassengers for a witch with setPersistenceRequired), and the run"
                + " of 2026-08-22 was killed by one. For what this run did about generation-placed"
                + " hostiles, see the peace.hut and peace.swept rows of the SPAWN rung."
                + " \"No test setup\" refers to items, not to difficulty.";
    }
}
