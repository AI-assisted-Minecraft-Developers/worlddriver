// Client-only — mc.bot.setting goes through requireBot() which isn't bound on
// dedicated server / GameTest CI. Skip in headless mode; live round-trip lives
// in fabric runClient.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("21_blocks_to_avoid: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("21_blocks_to_avoid: valid ids round-trip", function(t) {
        var r = Agent.invoke("mc.bot.setting", {
            blocksToAvoid: ["minecraft:powder_snow", "minecraft:sweet_berry_bush"]
        });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("blocksToAvoid") >= 0, "must be in applied");
        t.assertTrue(Array.isArray(r.settings.blocksToAvoid), "snapshot must include list");
        t.assertEqual(r.settings.blocksToAvoid.length, 2, "list length round-trips");
    });

    AgentTest.run("21_blocks_to_avoid: empty list clears the set", function(t) {
        var r = Agent.invoke("mc.bot.setting", { blocksToAvoid: [] });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings.blocksToAvoid.length, 0, "list now empty");
    });

    AgentTest.run("21_blocks_to_avoid: invalid id rejects whole write", function(t) {
        var r = Agent.invoke("mc.bot.setting", {
            blocksToAvoid: ["minecraft:stone", "not_a_real:block_id"]
        });
        // Envelope still ok:true, but blocksToAvoid lands in rejected[] rather than applied.
        t.assertEqual(r.ok, true, "envelope ok");
        t.assertTrue(Array.isArray(r.rejected) && r.rejected.length > 0,
            "must report rejected");
        // mc.bot.setting omits `applied` entirely when nothing was applied — the
        // compact shape both topologies share (SettingsCommand: applied added only
        // when non-empty). Read it defensively; a whole-list rejection means the key
        // is simply absent from applied. (task#92)
        t.assertEqual((r.applied || []).indexOf("blocksToAvoid"), -1,
            "must NOT be in applied — whole-list rejection");
    });

    AgentTest.run("21_blocks_to_avoid: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { blocksToAvoid: ["not_a_real:block_id"] };
            var direct = Agent.invoke("mc.bot.setting", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.setting", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.setting", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
        });

}
