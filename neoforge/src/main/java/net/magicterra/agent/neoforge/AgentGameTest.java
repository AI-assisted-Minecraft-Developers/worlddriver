package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the JS validation suite as a vanilla GameTest so it shows up in
 * {@code /test runall} and runs under {@code gradlew :neoforge:runGameTestServer}.
 *
 * Requires {@code data/agent_driver/structure/empty.nbt} (singular "structure",
 * the 1.21 datapack convention — {@code StructureTemplateManager.STRUCTURE_RESOURCE_DIRECTORY_NAME}).
 * Vanilla MC needs a structure template per @GameTest even when the test body
 * does not touch the spawned structure (we just run our own validation arena
 * at y=200).
 *
 * Threading: @GameTest bodies run on the server thread. Sub-tests in
 * 06_rpc_parity / 07_mcp_parity do TCP/HTTP round-trips back into our own
 * server; their handlers marshal work via {@code server.execute()} which only
 * drains on each server tick — so if we hold the server thread we deadlock.
 * Same constraint as {@code AgentDriverCommon.onServerStarted} / {@code cmdTest};
 * see comments there. Resolution: kick validation onto a worker thread, then
 * poll completion from the tick path via {@code startSequence().thenWaitUntil},
 * which is the only API surface that documents proper GameTestAssertException
 * retry semantics — {@code succeedWhen} treats the very first throw as a hard
 * failure, which becomes a race once the validation suite takes longer than
 * the framework's first tick.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTest {
    private AgentGameTest() {}

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void agentRpcSmoke(GameTestHelper helper) {
        if (AgentDriverCommon.api() == null) {
            helper.fail("AgentApi not initialized — was the mod loaded?");
            return;
        }
        AgentDriverCommon.api().seedTestArea();

        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(AgentDriverCommon.runValidation()); }
            catch (Throwable e) { crash.set(e); }
        }, "AgentDriver-GameTest");
        worker.setDaemon(true);
        worker.start();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable c = crash.get();
                    if (c != null) throw new GameTestAssertException("validation crashed: " + c.getMessage());
                    Integer v = result.get();
                    if (v == null) throw new GameTestAssertException("validation still running");
                    if (v != 0)    throw new GameTestAssertException("validation reported " + v + " failure(s); see server log");
                })
                .thenSucceed();
    }
}
