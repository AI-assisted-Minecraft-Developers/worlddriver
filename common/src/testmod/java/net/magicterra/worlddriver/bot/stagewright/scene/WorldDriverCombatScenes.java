package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;

/**
 * What a bot's threat scan sees, read through {@code mc.observe.threats} for a server bot.
 *
 * <p><b>Both are instantaneous (build floor → summon → scan → assert), no driving loop.</b> The
 * summoned mobs are {@code setNoAi(true)} + {@code setPersistenceRequired()}, so they neither move
 * nor despawn, and no ticks elapse for them between the summon and the scan, so a daytime zombie
 * has no time to burn. The bot is spawned parked: it stands where it was put and scans only when
 * asked.
 *
 * <p>A bot cannot be hurt ({@code isInvulnerableTo} is true for every driver player), so the
 * attacker scene records the hit the way a client records one it was told about, through vanilla's
 * {@code handleDamageEvent}, which sets the last damage source the scan reads.
 */
public final class WorldDriverCombatScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.threatScanZombie", 200, WorldDriverCombatScenes::threatScanZombie),
                Scene.of("wd.threatScanHurtAttacker", 200, WorldDriverCombatScenes::threatScanHurtAttacker));
    }

    /** A ±24-block cube around the arena centre — the entity-visibility poll box (matches the scan
     *  radius). Used only to detect when a freshly-added mob has entered the level's section index. */
    private static AABB entityBox(int cx, int floorY, int cz) {
        return new AABB(cx - 24, floorY - 24, cz - 24, cx + 24, floorY + 24, cz + 24);
    }

    /** Control: an adjacent zombie (a true {@code Enemy}) must be in the scan. Documents the scan has
     *  NO light/LOS drop-filter for Enemy mobs (the "blind in dark tunnels" hypothesis is wrong for
     *  Enemy mobs; canSee only shades the score). */
    private static void threatScanZombie(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;
        SceneArena.buildFloor(level, cx, cz, floorY);

        SceneBot bot = SceneBot.spawn(ctx, cx + 0.5, floorY + 1, cz + 0.5);
        Zombie z = EntityType.ZOMBIE.create(level);
        ctx.cleanup(() -> { if (z != null) z.discard(); });

        if (z == null) { ctx.fail("threatScanZombie: zombie create failed"); return; }
        z.setNoAi(true);
        z.setPersistenceRequired();
        z.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
        level.addFreshEntity(z);

        // On a dedicated server a freshly force-loaded arena chunk is not yet ENTITY_TICKING when the
        // scene body runs, so an entity added by addFreshEntity is alive in the level but NOT yet in the
        // queryable section index (measured: getEntitiesOfClass returns 0 with the mob alive at its
        // exact coords). The scan reads Level.getEntities, so the scene waits (bounded) until the mob is
        // indexed; the harness ticks the server between polls, promoting the chunk.
        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ObserveContracts.Threats scan = bot.threats(24);
            boolean seen = scan.threats().stream().anyMatch(t -> t.id() == z.getId());
            TestMod.LOG.info("[wd.threatScanZombie] threats={} seen={} visibleAtTick={}",
                    scan.threats().size(), seen, ctx.ticks());
            if (!seen)
                ctx.fail("threatScanZombie: adjacent zombie invisible to the threat scan: threats="
                        + scan.threats().size());
        });
    }

    /** A NEUTRAL mob (wolf — not {@code instanceof Enemy}) that has just hit the player must appear in
     *  the scan, marked {@code attackedMe}, and rank top. Being hit is the highest-confidence threat
     *  signal there is; it must not depend on the attacker's registry interface. */
    private static void threatScanHurtAttacker(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;
        SceneArena.buildFloor(level, cx, cz, floorY);

        SceneBot bot = SceneBot.spawn(ctx, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer p = bot.player();
        Wolf w = EntityType.WOLF.create(level);
        ctx.cleanup(() -> { if (w != null) w.discard(); });

        if (w == null) { ctx.fail("threatScanHurtAttacker: wolf create failed"); return; }
        w.setNoAi(true);
        w.setPersistenceRequired();
        w.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
        level.addFreshEntity(w);

        // See wd.threatScanZombie: the wolf must be indexed before the scan can find it, even as the
        // attacker.
        ctx.await(() -> !level.getEntitiesOfClass(Wolf.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            // Sanity: without the hit, a neutral wolf is (correctly) not a threat.
            ObserveContracts.Threats before = bot.threats(24);
            if (before.threats().stream().anyMatch(t -> t.id() == w.getId()))
                ctx.fail("threatScanHurtAttacker: idle neutral wolf must NOT be a threat");

            // The bot takes no damage, so the hit is recorded as a client records one: the last damage
            // source, stamped now, which is all the scan reads.
            p.handleDamageEvent(level.damageSources().mobAttack(w));
            if (p.getLastDamageSource() == null || p.getLastDamageSource().getEntity() != w)
                ctx.fail("threatScanHurtAttacker: rig broken: the wolf's hit was not recorded as the last damage");

            ObserveContracts.Threats scan = bot.threats(24);
            ObserveContracts.Threat found = scan.threats().stream()
                    .filter(t -> t.id() == w.getId()).findFirst().orElse(null);
            ObserveContracts.Threat top = scan.threats().isEmpty() ? null : scan.threats().get(0);
            TestMod.LOG.info("[wd.threatScanHurtAttacker] threats={} found={} top={}",
                    scan.threats().size(), found, top);
            if (found == null) {
                ctx.fail("threatScanHurtAttacker: wolf that just hit the player is invisible to "
                        + "the threat scan: threats=" + scan.threats().size());
                return;
            }
            if (!found.attackedMe())
                ctx.fail("threatScanHurtAttacker: attacker threat not marked attackedMe");
            if (top.id() != w.getId())
                ctx.fail("threatScanHurtAttacker: attacker must rank top: top=" + top);
        });
    }
}
