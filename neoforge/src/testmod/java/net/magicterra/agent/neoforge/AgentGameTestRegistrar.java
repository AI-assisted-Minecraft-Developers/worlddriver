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
 * {@link AgentDriverNeoForge}'s constructor did. The per-class register calls are
 * copied from that listener (originally 7 classes; AgentGameTestTerrain retired in P4b
 * wave 2 and AgentGameTestWaterBank + AgentGameTestWaterCross in P4b wave 4, so 4 remain):
 * AgentGameTest was split by arena family for file-size hygiene,
 * and AgentGameTestSupport / this class hold no @GameTest methods so they are
 * intentionally not registered here.
 *
 * <p>These explicit registrations are belt-and-suspenders alongside NeoForge's
 * @GameTestHolder auto-scan (which — with {@code neoforge.enableGameTest=true} — also
 * registers all @GameTestHolder classes, including AgentGameTestBias which was never in
 * this list); GameTestRegistry dedupes, so keeping the exact original wiring preserves
 * the registered set byte-for-byte across the move.
 */
@EventBusSubscriber(modid = AgentDriverCommon.MOD_ID)
public final class AgentGameTestRegistrar {
    private AgentGameTestRegistrar() {}

    @SubscribeEvent
    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(AgentGameTest.class);
        // AgentGameTestTerrain retired (P4b wave 2 close, controller-adjudicated): all 13 arenas
        // migrated to ad.* testkit scenes (12) or retired-without-scene (descentDriftArena) — the
        // class is empty and deleted, so no registration.
        event.register(AgentGameTestServer.class);
        // AgentGameTestWaterBank + AgentGameTestWaterCross retired (P4b wave 4): all 21 Water arenas
        // migrated to ad.* testkit scenes (WaterBank 11 + WaterCross 10; riverSheerBank + vineOverWaterClimb
        // + vineClingFidelityProbe carried optional), the two classes deleted in this same commit — so no
        // registration. See docs/testkit/migration-log.md wave-4.
        event.register(AgentGameTestCombatSense.class);
        event.register(AgentGameTestBuildBlock.class);
    }
}
