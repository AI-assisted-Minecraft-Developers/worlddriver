// Client-only — Phase D2 additions (allowParkour4 PathFinder gate, autoTool
// hotbar swap). Both are settings exposed through mc.bot.setting; behavior
// needs a real client+world. On dedicated-server CI: record skipped-PASS.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("24_phase_d2: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("24_phase_d2: allowParkour4 toggle round-trips", function(t) {
        var on = Agent.invoke("mc.bot.setting", { allowParkour4: true });
        t.assertEqual(on.ok, true, "write must succeed");
        t.assertTrue(on.applied.indexOf("allowParkour4") >= 0, "applied includes allowParkour4");
        t.assertEqual(on.settings.allowParkour4, true, "snapshot reflects allowParkour4=true");
        var off = Agent.invoke("mc.bot.setting", { allowParkour4: false });
        t.assertEqual(off.settings.allowParkour4, false, "snapshot reflects allowParkour4=false");
    });

    AgentTest.run("24_phase_d2: autoTool toggle round-trips", function(t) {
        var on = Agent.invoke("mc.bot.setting", { autoTool: true });
        t.assertEqual(on.ok, true, "write must succeed");
        t.assertTrue(on.applied.indexOf("autoTool") >= 0, "applied includes autoTool");
        t.assertEqual(on.settings.autoTool, true, "snapshot reflects autoTool=true");
        var off = Agent.invoke("mc.bot.setting", { autoTool: false });
        t.assertEqual(off.settings.autoTool, false, "snapshot reflects autoTool=false");
    });

    AgentTest.run("24_phase_d2: non-boolean for toggle leaves state unchanged", function(t) {
        // setting() typing guard — wrong-type values are silently skipped, not applied.
        var before = Agent.invoke("mc.bot.setting", {}).settings.autoTool;
        var r = Agent.invoke("mc.bot.setting", { autoTool: "yes" });
        t.assertEqual(r.settings.autoTool, before,
            "string 'yes' must not flip the boolean: before=" + before + " after=" + r.settings.autoTool);
        t.assertTrue(!r.applied || r.applied.indexOf("autoTool") < 0,
            "applied must NOT include autoTool when type was wrong");
    });

    AgentTest.run("24_phase_d2: byte-identical setting reads across in-JVM/RPC/MCP",
        function(t) {
            var direct = Agent.invoke("mc.bot.setting", {});
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.setting", {});
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.setting", {});
            t.assertEqual(viaTcp.settings.autoTool,      direct.settings.autoTool,      "autoTool mismatch RPC");
            t.assertEqual(viaMcp.settings.autoTool,      direct.settings.autoTool,      "autoTool mismatch MCP");
            t.assertEqual(viaTcp.settings.allowParkour4, direct.settings.allowParkour4, "allowParkour4 mismatch RPC");
            t.assertEqual(viaMcp.settings.allowParkour4, direct.settings.allowParkour4, "allowParkour4 mismatch MCP");
        });

}
