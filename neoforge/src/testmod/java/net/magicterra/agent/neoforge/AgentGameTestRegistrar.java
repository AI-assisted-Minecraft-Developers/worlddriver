package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * P4a Task 1: RegisterGameTestsEvent seam for the legacy @GameTest suite, moved
 * here (neoforge testmod source set) together with the arena classes so no test
 * code remains in the production jar.
 *
 * <p>Discovered by FML's annotation scan because the testmod source set is attached
 * to the {@code agent_driver} mod via {@code loom.mods} in {@code neoforge/build.gradle}
 * — so this @EventBusSubscriber fires exactly as the old inline listener in
 * {@link AgentDriverNeoForge}'s constructor did. After P4b wave 5 the legacy suite is
 * Server-only: {@code AgentGameTest} (main class, 12 tests), {@code AgentGameTestCombatSense}
 * (2) and {@code AgentGameTestBuildBlock} (2) were all migrated to {@code ad.*} testkit
 * scenes and their classes deleted — together with the earlier-retired Terrain (wave 2),
 * WaterBank + WaterCross (wave 4) and Bias (wave 3, @GameTestHolder-only) families. So the
 * ONLY class carrying @GameTest methods left in this source set is {@code AgentGameTestServer};
 * {@code AgentGameTestSupport} / this class hold none and are intentionally not registered.
 *
 * <p>This explicit registration is belt-and-suspenders alongside NeoForge's @GameTestHolder
 * auto-scan (with {@code neoforge.enableGameTest=true}); GameTestRegistry dedupes, so keeping
 * the single Server registration preserves the registered set byte-for-byte across the move.
 */
@EventBusSubscriber(modid = AgentDriverCommon.MOD_ID)
public final class AgentGameTestRegistrar {
    private AgentGameTestRegistrar() {}

    @SubscribeEvent
    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        // Server-only after P4b wave 5. The main class AgentGameTest (12) + AgentGameTestCombatSense (2)
        // + AgentGameTestBuildBlock (2) migrated to ad.* testkit scenes (AgentDriverCoreScenes /
        // AgentDriverCombatScenes / AgentDriverBuildScenes) and were deleted this wave; the Terrain
        // (wave 2), Bias (wave 3, @GameTestHolder-only), and WaterBank + WaterCross (wave 4) families
        // retired earlier. See docs/testkit/migration-log.md wave-5. AgentGameTestServer (P4c) remains.
        event.register(AgentGameTestServer.class);
    }
}
