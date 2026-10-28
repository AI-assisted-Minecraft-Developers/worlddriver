// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('29_chat_history', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '29_chat_history', DriverApiError: DriverApiError, body: (ScriptTest) => {
Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

// mc.client.chat.history + chat.send awaitReplyMs + mc.client.overlays. The request check runs on
// every topology; the happy paths need a live client.

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

ScriptTest.run("29_chat_history: chat.send needs text", function(t) {
    var e = errorOf(function() { Driver.client.chat.send({ awaitReplyMs: 100 }); });
    t.assertEqual(e.code, "invalid_params", "code");
    t.assertEqual(e.issues[0].path, "/text", "path");
    t.assertEqual(e.issues[0].code, "required", "issue code");
});

if (!clientAvailable()) {
    ScriptTest.run("29_chat_history: a dedicated server keeps no chat readback", function(t) {
        var e = errorOf(function() { Driver.client.chat.history({}); });
        t.assertEqual(e.code, "capability_unavailable", "code");
    });
} else {

    ScriptTest.run("29_chat_history: history returns shape {nextSeq, messages}",
        function(t) {
            var r = Driver.client.chat.history({});
            t.assertTrue(typeof r.nextSeq === "number", "nextSeq is number");
            t.assertTrue(Array.isArray(r.messages), "messages is array");
        });

    ScriptTest.run("29_chat_history: limit caps result", function(t) {
        var r = Driver.client.chat.history({ limit: 1 });
        t.assertTrue(r.messages.length <= 1, "at most 1 message");
    });

    ScriptTest.run("29_chat_history: sinceSeq filters out older messages",
        function(t) {
            var full = Driver.client.chat.history({});
            // Asking for messages beyond nextSeq → empty.
            var empty = Driver.client.chat.history({ sinceSeq: full.nextSeq + 100 });
            t.assertEqual(empty.messages.length, 0, "no messages after sinceSeq beyond end");
        });

    ScriptTest.run("29_chat_history: chat.send with awaitReplyMs reports what came later",
        function(t) {
            var r = Driver.client.chat.send({ text: "/say worlddriver test", awaitReplyMs: 100 });
            // Refused (no player), or done with the later lines or a timeout.
            t.assertTrue(r.reason === "no_player"
                    || (r.outcome === "done" && r.later && (r.later.timedOut === true || r.later.lines.length > 0)),
                "chat.send returned a known shape: " + JSON.stringify(r));
        });

    ScriptTest.run("29_chat_history: overlays default-true dismisses both", function(t) {
        var r = Driver.client.overlays({});
        t.assertTrue(r.tutorial === true || r.tutorialError !== undefined, "tutorial handled");
        t.assertTrue(r.toasts === true || r.toastsError !== undefined, "toasts handled");
    });

    ScriptTest.run("29_chat_history: overlays{tutorial:false,toasts:false} skips both",
        function(t) {
            var r = Driver.client.overlays({ tutorial: false, toasts: false });
            t.assertTrue(r.tutorial === false && r.tutorialError === undefined, "tutorial left alone");
            t.assertTrue(r.toasts === false && r.toastsError === undefined, "toasts left alone");
        });

    ScriptTest.run("29_chat_history: 3-transport parity on history shape",
        function(t) {
            var direct = Driver.client.chat.history({ limit: 5 });
            var viaTcp = Driver.system.rpcRoundtrip("mc.client.chat.history", { limit: 5 });
            var viaMcp = Driver.system.mcpRoundtrip("mc.client.chat.history", { limit: 5 });
            t.assertTrue(Array.isArray(viaTcp.messages) && Array.isArray(viaMcp.messages), "messages on every path");
            // nextSeq is time-dependent (new messages between calls) but monotonically non-decreasing.
            t.assertTrue(viaTcp.nextSeq >= direct.nextSeq, "RPC nextSeq monotone");
            t.assertTrue(viaMcp.nextSeq >= viaTcp.nextSeq, "MCP nextSeq monotone");
        });

}

}}));
