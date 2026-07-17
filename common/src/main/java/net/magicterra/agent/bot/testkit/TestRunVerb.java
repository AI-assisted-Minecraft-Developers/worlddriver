package net.magicterra.agent.bot.testkit;

import java.util.Map;

import net.magicterra.agent.mcp.ToolCatalog;
import net.magicterra.agent.mcp.schema.ToolSchema;
import net.magicterra.testkit.TestkitCommon;
import net.magicterra.testkit.TestkitVerbHook;

import static net.magicterra.agent.mcp.schema.Schemas.object;
import static net.magicterra.agent.mcp.schema.Schemas.tool;

/**
 * The first <b>testkit-runtime</b> self-consumption of the paired verb-registration SPI
 * ({@link ToolCatalog#registerVerb}): the hidden {@code mc.test.run} on-demand suite trigger
 * (testkit P3a). Unlike {@code mc.test.reset} — which registers from agent-driver's own bootstrap
 * ({@code AgentDriverCommon.ensureRpcUp}) — this verb is registered by the <b>testkit runtime's own
 * hook</b>: it implements the testkit-common {@link TestkitVerbHook} SPI, discovered by
 * {@link TestkitCommon#onServerStarted} via {@link java.util.ServiceLoader} and invoked once per
 * server (in BOTH autorun states). That inverts the module dependency cleanly — testkit-common
 * never imports {@code ToolCatalog}; this glue class, living on the agent-driver side of the edge,
 * does the {@code registerVerb} call.
 *
 * <h2>Why the hook, not the bootstrap</h2>
 * {@code mc.test.run} triggers the <b>testkit harness</b>, which only exists on a testkit runtime
 * (a server whose {@code TestkitCommon.onServerStarted} fired). Registering it from the SPI hook —
 * rather than from agent-driver's unconditional bootstrap — keeps it OFF plain agent-driver servers
 * that never arm the harness (nothing to trigger there), and requires ZERO agent-driver
 * (non-testkit) Java changes: the wiring point is entirely inside the testkit runtime + this testkit
 * glue package. The paired SPI's namespace policy grants {@code mc.test.*} to exactly this consumer.
 *
 * <h2>Boot ordering</h2>
 * {@code registerVerb} refuses a pre-boot (sink-less) call. The hook runs from
 * {@code TestkitCommon.onServerStarted} (SERVER_STARTED), which is strictly after agent-driver wired
 * its route sink at SERVER_STARTING ({@code AgentDriverCommon.ensureRpcUp}) — so the sink is always
 * present by the time this fires. {@link #register()} is idempotent-guarded so a duplicate
 * server-started (or a second ServiceLoader pass) cannot append the schema supplier twice.
 */
public final class TestRunVerb implements TestkitVerbHook {

    /** Public no-arg constructor required for {@link java.util.ServiceLoader} instantiation. */
    public TestRunVerb() {}

    /** Hidden ToolSchema — declared (satisfies the boot schema invariant + drives route-layer
     *  validation) but kept out of MCP {@code tools/list}, exactly like {@code mc.test.reset} and the
     *  {@code mc.test.yaml} harness precedent. No params. */
    public static final ToolSchema SCHEMA = tool(
            "mc.test.run",
            "Trigger the mc-testkit scene suite ON DEMAND (dev/test harness verb; reachable over RPC "
            + "only). Runs the same suite path -Dtestkit.autorun uses (Scenes.all -> execute on server "
            + "ticks -> JSONL results -> done footer), scheduled onto the server thread. Returns "
            + "{accepted:true, scenes:N} once the run is ACCEPTED; the JSONL done footer remains the "
            + "sole completion signal. Idempotent: if the suite already ran or is running it fails "
            + "loudly rather than re-running. No params.",
            object().additionalProperties(false)).asHidden();

    private static volatile boolean registered;

    /** {@link TestkitVerbHook} entry — delegates to the idempotent {@link #register()}. */
    @Override
    public void registerVerbs() {
        register();
    }

    /**
     * Register {@code mc.test.run} through the paired SPI. Idempotent (guarded) so a duplicate
     * discovery/boot call cannot append the schema supplier twice. Must run after the route sink is
     * wired (see class javadoc) — a pre-boot call throws from {@code registerVerb}.
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ToolCatalog.registerVerb(SCHEMA, TestRunVerb::handle);
    }

    /** Route handler (runs on the RPC socket thread): hop the trigger into the testkit runtime,
     *  which idempotency-checks + schedules the harness onto the server thread, or throws loudly. */
    static Object handle(Map<String, Object> params) {
        return TestkitCommon.triggerOnDemandRun();
    }
}
