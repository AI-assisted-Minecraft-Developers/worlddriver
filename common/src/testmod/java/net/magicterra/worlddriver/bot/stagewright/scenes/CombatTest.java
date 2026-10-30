package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import org.junit.jupiter.api.Test;

/**
 * What a bot's threat scan sees, read through {@code mc.observe.threats} for a server bot.
 *
 * <p>The summoned mobs are {@code setNoAi(true)} + {@code setPersistenceRequired()}, so they neither move nor despawn.
 * The bot is spawned parked: it stands where it was put and scans only when asked.
 *
 * <p>A bot cannot be hurt ({@code isInvulnerableTo} is true for every driver player), so the attacker test records the
 * hit the way a client records one it was told about, through vanilla's {@code handleDamageEvent}, which sets the last
 * damage source the scan reads.
 */
@Scene("wd.scenes")
class CombatTest {

    /**
     * A NEUTRAL mob (wolf — not {@code instanceof Enemy}) that has just hit the player must appear in the scan, marked
     * {@code attackedMe}, and rank top. Being hit is the highest-confidence threat signal there is; it must not depend
     * on the attacker's registry interface.
     */
    @Test
    void threatScanHurtAttacker(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        BlockPos wolfAt = plot.at(2, 1, 0);
        int wolf = game.onServer(server -> {
            ServerLevel level = bot.level();
            Wolf w = EntityType.WOLF.create(level);
            assertNotNull(w, "the wolf was not created");
            w.setNoAi(true);
            w.setPersistenceRequired();
            w.moveTo(wolfAt.getX() + 0.5, wolfAt.getY(), wolfAt.getZ() + 0.5, 0f, 0f);
            level.addFreshEntity(w);
            return w.getId();
        });

        // The wolf must be in the level's entity index before the scan can find it, even as the attacker.
        game.await()
                .within(100)
                .as("the wolf enters the level's entity index")
                .until(() -> !bot.level()
                        .getEntitiesOfClass(Wolf.class, Staging.entityBox(plot.at(0, 0, 0)))
                        .isEmpty());

        // Without the hit, a neutral wolf is (correctly) not a threat.
        ObserveContracts.Threats before = bot.threats(24);
        if (before.threats().stream().anyMatch(t -> t.id() == wolf)) fail("an idle neutral wolf must not be a threat");

        // The bot takes no damage, so the hit is recorded as a client records one: the last damage source, stamped
        // now, which is all the scan reads.
        game.runOnServer(server -> {
            Wolf w = (Wolf) bot.level().getEntity(wolf);
            bot.player().handleDamageEvent(bot.level().damageSources().mobAttack(w));
            assertSame(
                    w,
                    bot.player().getLastDamageSource() == null
                            ? null
                            : bot.player().getLastDamageSource().getEntity(),
                    "rig broken: the wolf's hit was not recorded as the last damage");
        });

        ObserveContracts.Threats scan = bot.threats(24);
        ObserveContracts.Threat found =
                scan.threats().stream().filter(t -> t.id() == wolf).findFirst().orElse(null);
        if (found == null) {
            fail("the wolf that just hit the player is invisible to the threat scan: threats="
                    + scan.threats().size());
        }
        game.check(found.attackedMe(), "the attacker's threat is marked attackedMe: " + found);
        game.check(
                scan.threats().get(0).id() == wolf,
                "the attacker ranks top: top=" + scan.threats().get(0));
    }
}
