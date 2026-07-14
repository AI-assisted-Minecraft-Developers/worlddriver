package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static net.magicterra.agent.neoforge.AgentGameTestSupport.buildFloor;

/**
 * Gap #55 sensing tests for the dist-neutral {@link ThreatScanner} core. Both are
 * instantaneous (build → act → assert in one tick), no driving loop.
 *
 * <p>Background: a live bot was being melee'd while {@code observe.threats} returned
 * empty. The scanner's hostile filter is {@code instanceof Enemy} — angered NEUTRAL
 * mobs (wolf, bee, polar bear …) never implement it, so a mob actively hitting the
 * player can be invisible to every reflex chain. The fix threads the player's
 * {@code getLastDamageSource()} attacker (a vanilla-maintained 40-tick window, set
 * on BOTH sides: server {@code hurt()}, client {@code handleDamageEvent}) through
 * the same scan loop, so "it just hit me" is sufficient to be a threat.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestCombatSense {
    private AgentGameTestCombatSense() {}

    /**
     * Control: an adjacent zombie (a true {@code Enemy}) must be visible to
     * {@link ThreatScanner#compute}. Documents that the scan has NO light/LOS
     * drop-filter — the original "scanner blind in dark tunnels" hypothesis is
     * wrong for Enemy mobs (canSee only shades the score).
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void threatScanZombieArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtSkip(helper, "threatScanZombieArena")) return; // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 940, cz = 940, floorY = 220;
        buildFloor(level, cx, cz, floorY);

        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        Zombie z = EntityType.ZOMBIE.create(level);
        try {
            p.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0f, 0f);
            if (z == null) throw new GameTestAssertException("zombie create failed");
            z.setNoAi(true);
            z.setPersistenceRequired();
            z.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
            level.addFreshEntity(z);

            ThreatScanner.Scan scan = ThreatScanner.compute(level, p, 24);
            boolean seen = scan.threats().stream().anyMatch(t -> t.id() == z.getId());
            AgentDriverCommon.LOG.info("[threatScanZombieArena] threats={} seen={}",
                    scan.threats().size(), seen);
            if (!seen)
                throw new GameTestAssertException("adjacent zombie invisible to ThreatScanner: threats="
                        + scan.threats().size());
        } finally {
            if (z != null) z.discard();
            p.discard();
        }
        helper.succeed();
    }

    /**
     * Gap #55 core: a NEUTRAL mob (wolf — not {@code instanceof Enemy}) that has
     * just HURT the player must appear in the scan, marked {@code attackedMe},
     * and rank top. Being hit is the highest-confidence threat signal there is;
     * it must not depend on the attacker's registry interface.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void threatScanHurtAttackerArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtSkip(helper, "threatScanHurtAttackerArena")) return; // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 960, cz = 940, floorY = 220;
        buildFloor(level, cx, cz, floorY);

        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        Wolf w = EntityType.WOLF.create(level);
        try {
            p.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0f, 0f);
            if (w == null) throw new GameTestAssertException("wolf create failed");
            w.setNoAi(true);
            w.setPersistenceRequired();
            w.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0f, 0f);
            level.addFreshEntity(w);

            // Sanity: without the hit, a neutral wolf is (correctly) not a threat.
            ThreatScanner.Scan before = ThreatScanner.compute(level, p, 24);
            if (before.threats().stream().anyMatch(t -> t.id() == w.getId()))
                throw new GameTestAssertException("idle neutral wolf must NOT be a threat");

            boolean hurt = p.hurt(level.damageSources().mobAttack(w), 2.0f);
            if (!hurt)
                throw new GameTestAssertException("rig broken: mock player refused mobAttack damage");

            ThreatScanner.Scan scan = ThreatScanner.compute(level, p, 24);
            ThreatScanner.Threat found = scan.threats().stream()
                    .filter(t -> t.id() == w.getId()).findFirst().orElse(null);
            AgentDriverCommon.LOG.info("[threatScanHurtAttackerArena] threats={} found={} top={}",
                    scan.threats().size(), found, scan.top());
            if (found == null)
                throw new GameTestAssertException("wolf that just hit the player is invisible to "
                        + "ThreatScanner: threats=" + scan.threats().size());
            if (!found.attackedMe())
                throw new GameTestAssertException("attacker threat not marked attackedMe");
            if (scan.top().id() != w.getId())
                throw new GameTestAssertException("attacker must rank top: top=" + scan.top());
        } finally {
            if (w != null) w.discard();
            p.discard();
        }
        helper.succeed();
    }
}
