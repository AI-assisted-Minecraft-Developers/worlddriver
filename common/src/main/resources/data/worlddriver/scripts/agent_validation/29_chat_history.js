// Client-only — Phase D7: mc.client.chat.history + chat.send awaitReplyMs +
// mc.client.overlays. Happy paths need a live LocalPlayer (chat history reads
// ChatComponent from the gui). On dedicated-server CI we PASS-as-skipped.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("29_chat_history: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("29_chat_history: history returns shape {ok, count, nextSeq, messages}",
        function(t) {
            var r = Agent.invoke("mc.client.chat.history", {});
            t.assertEqual(r.ok, true, "ok");
            t.assertTrue(typeof r.count === "number", "count is number");
            t.assertTrue(typeof r.nextSeq === "number", "nextSeq is number");
            t.assertTrue(Array.isArray(r.messages), "messages is array");
            t.assertTrue(r.count === r.messages.length, "count matches messages length");
        });

    AgentTest.run("29_chat_history: limit caps result", function(t) {
        var r = Agent.invoke("mc.client.chat.history", { limit: 1 });
        t.assertEqual(r.ok, true, "ok");
        t.assertTrue(r.messages.length <= 1, "at most 1 message");
    });

    AgentTest.run("29_chat_history: sinceSeq filters out older messages",
        function(t) {
            var full = Agent.invoke("mc.client.chat.history", {});
            var nextSeq = full.nextSeq;
            // Asking for messages beyond nextSeq → empty.
            var empty = Agent.invoke("mc.client.chat.history", { sinceSeq: nextSeq + 100 });
            t.assertEqual(empty.count, 0, "no messages after sinceSeq beyond end");
        });

    AgentTest.run("29_chat_history: chat.send accepts awaitReplyMs without throwing",
        function(t) {
            // We can't actually receive a reply on a TitleScreen / pre-connect,
            // but the call must accept the param and time out gracefully.
            var r = Agent.invoke("mc.client.chat.send",
                { text: "/say worlddriver test", awaitReplyMs: 100 });
            // ok=false (no player) OR ok=true with replyTimeout=true (no server reply).
            t.assertTrue(r.ok === false || r.replyTimeout === true || r.reply,
                "chat.send returned a known shape: " + JSON.stringify(r));
        });

    AgentTest.run("29_chat_history: overlays default-true returns {ok}", function(t) {
        var r = Agent.invoke("mc.client.overlays", {});
        t.assertEqual(r.ok, true, "ok");
        // Either both fields populated, or error fields if reflection failed.
        var ok = r.tutorial !== undefined || r.tutorialError !== undefined;
        var ok2 = r.toasts !== undefined || r.toastsError !== undefined;
        t.assertTrue(ok, "tutorial field present");
        t.assertTrue(ok2, "toasts field present");
    });

    AgentTest.run("29_chat_history: overlays{tutorial:false,toasts:false} skips both",
        function(t) {
            var r = Agent.invoke("mc.client.overlays", { tutorial: false, toasts: false });
            t.assertEqual(r.ok, true, "ok");
            t.assertTrue(r.tutorial === undefined && r.tutorialError === undefined,
                "no tutorial field when disabled");
            t.assertTrue(r.toasts === undefined && r.toastsError === undefined,
                "no toasts field when disabled");
        });

    AgentTest.run("29_chat_history: 3-transport parity on history shape",
        function(t) {
            var direct = Agent.invoke("mc.client.chat.history", { limit: 5 });
            var viaTcp = Agent.system.rpcRoundtrip("mc.client.chat.history", { limit: 5 });
            var viaMcp = Agent.system.mcpRoundtrip("mc.client.chat.history", { limit: 5 });
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            // count/nextSeq are time-dependent (new messages between calls)
            // but should be monotonically non-decreasing.
            t.assertTrue(viaTcp.nextSeq >= direct.nextSeq, "RPC nextSeq monotone");
            t.assertTrue(viaMcp.nextSeq >= viaTcp.nextSeq, "MCP nextSeq monotone");
        });

}
