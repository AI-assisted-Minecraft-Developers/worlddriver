// Run by kit.js's 'wd.validation' step, which says what ScriptTest is and what the check counts are.
test('14_type_text_and_key', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '14_type_text_and_key', checks: { dedicated: 2, integrated: 4 }, DriverApiError: DriverApiError, body: (ScriptTest) => {
Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

// mc.client.input.typeText and mc.client.input.key. The request check runs on every topology; the
// rest needs a client's screen API.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

ScriptTest.run("14_type_text_and_key: key rejects bad action", function(t) {
    var e = errorOf(function() { Driver.client.input.key({ key: "ENTER", action: "smash" }); });
    t.assertEqual(e.code, "invalid_params", "code");
    t.assertEqual(e.issues[0].path, "/action", "path");
});

if (!clientAvailable()) {
    ScriptTest.run("14_type_text_and_key: a dedicated server has no keyboard", function(t) {
        var e = errorOf(function() { Driver.client.input.key({ key: "ENTER" }); });
        t.assertEqual(e.code, "capability_unavailable", "code");
    });
} else {

    ScriptTest.run("14_type_text_and_key: typeText answers done or a refusal", function(t) {
        // Title screen / world screen always has a Screen, so we can't test the
        // no-screen path here without disrupting state. Instead, exercise that
        // the reply is one of the two outcomes.
        var r = Driver.client.input.typeText({ text: "" });
        t.assertTrue(r.outcome === "done" || (r.outcome === "refused" && r.reason === "no_screen"),
            "typeText must answer done or no_screen, got " + JSON.stringify(r));
    });

    ScriptTest.run("14_type_text_and_key: key rejects unknown name", function(t) {
        var e = errorOf(function() { Driver.client.input.key({ key: "BLAHBLAH" }); });
        t.assertEqual(e.code, "invalid_params", "code");
        t.assertEqual(e.issues[0].path, "/key", "path");
    });

    ScriptTest.run("14_type_text_and_key: the same rejection over in-JVM, RPC and MCP", function(t) {
        var args = { key: "WUT" };
        var direct = errorOf(function() { Driver.invoke("mc.client.input.key", args); });
        var viaTcp = errorOf(function() { Driver.system.rpcRoundtrip("mc.client.input.key", args); });
        var viaMcp = errorOf(function() { Driver.system.mcpRoundtrip("mc.client.input.key", args); });
        t.assertEqual(JSON.stringify(viaTcp.issues), JSON.stringify(direct.issues), "RPC issues");
        t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
    });

}

}}));
