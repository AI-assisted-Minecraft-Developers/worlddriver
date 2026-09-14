package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 5, the CombatSense family</b>: the 2
 * {@code AgentGameTestCombatSense} sensing tests for the dist-neutral {@link ThreatScanner}
 * core (gap #55), migrated verbatim to testkit {@code wd.*} scenes and their legacy twin
 * class retired in the same commit.
 *
 * <p><b>Both are INSTANTANEOUS (build floor → summon → scan → assert in one tick), no driving
 * loop</b> — exactly as the legacy class javadoc states. The scene body runs once on the first
 * RUN tick and resolves immediately; the summoned mobs are {@code setNoAi(true)} +
 * {@code setPersistenceRequired()}, so they neither move nor despawn, and — critically —
 * <b>NO ticks elapse for them</b>, so the documented "daytime zombie auto-burn is a false-signal
 * source" hazard cannot fire here: burning requires the entity to tick in daylight over multiple
 * ticks, and this scan completes in the same tick the entity is added. The legacy rig carried NO
 * roof/helmet/night protection precisely because it never needed any (single-tick, NoAI); that
 * protection (NoAI + persistence) is copied verbatim. Time of day is therefore irrelevant to these
 * two scenes either way — verified observationally byte-identical across both loaders ×2 (see
 * migration-log wave-5) back when the harness still let the world's clock run.
 *
 * <p><b>{@code makeMockPlayer} substitution.</b> The legacy used the GameTest-only
 * {@code helper.makeMockPlayer(GameType.SURVIVAL)} — a plain vulnerable {@link Player} (NOT a
 * {@code FakePlayer}/{@code ServerPlayerBody}, whose {@code isInvulnerableTo} returns true so
 * {@code hurt()} no-ops and {@code getLastDamageSource()} stays null, defeating the attacker
 * test). The scene harness has no {@code GameTestHelper}, so {@link #makeMockPlayer} below
 * reproduces vanilla {@code GameTestHelper.makeMockPlayer} byte-for-byte (anonymous vulnerable
 * {@code Player} with the same {@code test-mock-player} profile + overrides). The mock is never
 * added to the level entity list (the legacy did not either — {@code ThreatScanner.compute} takes
 * it by reference), and is discarded via {@code ctx.cleanup} on any outcome.
 */
public final class WorldDriverCombatScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.threatScanZombie", 200, WorldDriverCombatScenes::threatScanZombie),
                Scene.of("wd.threatScanHurtAttacker", 200, WorldDriverCombatScenes::threatScanHurtAttacker));
    }

    /** Reproduces vanilla {@code GameTestHelper.makeMockPlayer(GameType)} — a plain, VULNERABLE
     *  {@link Player} (default {@code isInvulnerableTo}), so {@code hurt()} lands and sets
     *  {@code getLastDamageSource()}. Same {@code test-mock-player} profile + overrides as vanilla. */
    private static Player makeMockPlayer(ServerLevel level, GameType gameType) {
        return new Player(level, BlockPos.ZERO, 0.0F, new GameProfile(UUID.randomUUID(), "test-mock-player")) {
            @Override public boolean isSpectator() { return gameType == GameType.SPECTATOR; }
            @Override public boolean isCreative() { return gameType.isCreative(); }
            @Override public boolean isLocalPlayer() { return true; }
        };
    }

    /** A ±24-block cube around the arena centre — the entity-visibility poll box (matches the scan
     *  radius). Used only to detect when a freshly-added mob has entered the level's section index. */
    private static AABB entityBox(int cx, int floorY, int cz) {
        return new AABB(cx - 24, floorY - 24, cz - 24, cx + 24, floorY + 24, cz + 24);
    }

    /** Ported from {@code AgentGameTestCombatSense#threatScanZombieArena}: control — an adjacent
     *  zombie (a true {@code Enemy}) must be visible to {@link ThreatScanner#compute}. Documents the
     *  scan has NO light/LOS drop-filter for Enemy mobs (the "blind in dark tunnels" hypothesis is
     *  wrong for Enemy mobs; canSee only shades the score). */
    private static void threatScanZombie(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y(200)+20
        SceneArena.buildFloor(level, cx, cz, floorY);

        Player p = makeMockPlayer(level, GameType.SURVIVAL);
        Zombie z = EntityType.ZOMBIE.create(level);
        ctx.cleanup(() -> { if (z != null) z.discard(); });
        ctx.cleanup(() -> p.discard());

        p.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0f, 0f);
        if (z == null) { ctx.fail("threatScanZombie: zombie create failed"); return; }
        z.setNoAi(true);
        z.setPersistenceRequired();
        z.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
        level.addFreshEntity(z);

        // ENTITY-VISIBILITY WAIT (faithful adaptation, not a semantic change). On a real dedicated
        // server a freshly force-loaded arena chunk is not yet ENTITY_TICKING when the scene body
        // runs, so an entity added by addFreshEntity is alive in the level but NOT yet in the
        // queryable section index (measured: getEntitiesOfClass returns 0 with the mob alive at its
        // exact coords). ThreatScanner scans via Level.getEntities, so it must be queryable. The
        // legacy GameTestServer placed its arena in an already-ticking template, masking this. We poll
        // (bounded) until the mob is indexed, then run the byte-identical scan + assertion. No manual
        // level.tick() — the harness ticks the server between polls, promoting the chunk.
        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ThreatScanner.Scan scan = ThreatScanner.compute(level, p, 24);
            boolean seen = scan.threats().stream().anyMatch(t -> t.id() == z.getId());
            WorldDriverCommon.LOG.info("[wd.threatScanZombie] threats={} seen={} visibleAtTick={}",
                    scan.threats().size(), seen, ctx.ticks());
            if (!seen)
                ctx.fail("threatScanZombie: adjacent zombie invisible to ThreatScanner: threats="
                        + scan.threats().size());
        });
    }

    /** Ported from {@code AgentGameTestCombatSense#threatScanHurtAttackerArena} (gap #55 core): a
     *  NEUTRAL mob (wolf — not {@code instanceof Enemy}) that has just HURT the player must appear in
     *  the scan, marked {@code attackedMe}, and rank top. Being hit is the highest-confidence threat
     *  signal there is; it must not depend on the attacker's registry interface. */
    private static void threatScanHurtAttacker(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20
        SceneArena.buildFloor(level, cx, cz, floorY);

        Player p = makeMockPlayer(level, GameType.SURVIVAL);
        Wolf w = EntityType.WOLF.create(level);
        ctx.cleanup(() -> { if (w != null) w.discard(); });
        ctx.cleanup(() -> p.discard());

        p.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0f, 0f);
        if (w == null) { ctx.fail("threatScanHurtAttacker: wolf create failed"); return; }
        w.setNoAi(true);
        w.setPersistenceRequired();
        w.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
        level.addFreshEntity(w);

        // ENTITY-VISIBILITY WAIT — see wd.threatScanZombie for the rationale (a freshly-added mob is
        // not in the queryable section index until the force-loaded arena chunk becomes ENTITY_TICKING;
        // the after-scan iterates Level.getEntities, so the wolf must be indexed — even to be found as
        // the attacker). The before/hurt/after sequence and assertions below are byte-identical to the
        // legacy. No manual level.tick().
        ctx.await(() -> !level.getEntitiesOfClass(Wolf.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            // Sanity: without the hit, a neutral wolf is (correctly) not a threat.
            ThreatScanner.Scan before = ThreatScanner.compute(level, p, 24);
            if (before.threats().stream().anyMatch(t -> t.id() == w.getId()))
                ctx.fail("threatScanHurtAttacker: idle neutral wolf must NOT be a threat");

            boolean hurt = p.hurt(level.damageSources().mobAttack(w), 2.0f);
            if (!hurt)
                ctx.fail("threatScanHurtAttacker: rig broken: mock player refused mobAttack damage");

            ThreatScanner.Scan scan = ThreatScanner.compute(level, p, 24);
            ThreatScanner.Threat found = scan.threats().stream()
                    .filter(t -> t.id() == w.getId()).findFirst().orElse(null);
            WorldDriverCommon.LOG.info("[wd.threatScanHurtAttacker] threats={} found={} top={}",
                    scan.threats().size(), found, scan.top());
            if (found == null)
                ctx.fail("threatScanHurtAttacker: wolf that just hit the player is invisible to "
                        + "ThreatScanner: threats=" + scan.threats().size());
            if (!found.attackedMe())
                ctx.fail("threatScanHurtAttacker: attacker threat not marked attackedMe");
            if (scan.top().id() != w.getId())
                ctx.fail("threatScanHurtAttacker: attacker must rank top: top=" + scan.top());
        });
    }
}
