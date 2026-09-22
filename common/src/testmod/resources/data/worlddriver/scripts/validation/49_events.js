// ROADMAP (post-H) — driver→agent event channel, server-side surface (mc.events).
// Pure server work (emit into the ring buffer + replay via eventsSince + condition
// watchers on a background scheduler), so it runs identically on the dedicated
// GameTest server AND in runClient — no client gate. The live PUSH transport
// (WebSocket subscribe frames / the MCP SSE stream) needs a persistent connection
// and is verified separately in runClient; here we prove the event generation that
// feeds it.

ScriptTest.run("49_events: emit injects a custom event that replays via eventsSince", function (t) {
    var c0 = Driver.invoke("mc.observe.cursor", {});
    var em = Driver.invoke("mc.events", { op: "emit", type: "test.custom", data: { hello: "world" } });
    t.assertEqual(em.ok, true, "emit ok");
    t.assertTrue(em.seq > 0, "emit returned a seq (" + em.seq + ")");
    t.assertEqual(em.type, "test.custom", "echoes type");

    var page = Driver.invoke("mc.observe.eventsSince", { cursor: c0, types: ["test.custom"] });
    t.assertTrue(page.length >= 1, "the custom event is replayable (got " + page.length + ")");
    var ev = page[page.length - 1];
    t.assertEqual(ev.type, "test.custom", "type matches");
    // data is a real object on the wire now, not JSON escaped inside a string.
    t.assertEqual(ev.data.hello, "world", "object data round-tripped as an object");
    t.assertTrue(typeof ev.data === "object", "data must NOT arrive as a string");
});

ScriptTest.run("49_events: emit requires a type; bad op rejected", function (t) {
    var threw = false;
    try { Driver.invoke("mc.events", { op: "emit" }); }
    catch (e) { threw = true; t.assertTrue(String(e.message).indexOf("type") >= 0, "error names type (" + e.message + ")"); }
    t.assertTrue(threw, "emit without type throws");

    var threw2 = false;
    try { Driver.invoke("mc.events", { op: "bogus" }); }
    catch (e2) { threw2 = true; }
    t.assertTrue(threw2, "unknown op throws");
});

ScriptTest.run("49_events: a rising-edge watcher emits emitAs into the stream", function (t) {
    var c0 = Driver.invoke("mc.observe.cursor", {});
    // mc.system.version returns a truthy object every poll, so the predicate is
    // true on the first tick → fires once immediately (200ms), then self-cancels.
    var w = Driver.invoke("mc.events", {
        op: "watch", invoke: "mc.system.version",
        emitAs: "watch.fired", everyMs: 200, once: true
    });
    t.assertEqual(w.ok, true, "watch ok");
    t.assertTrue(w.id > 0, "watch got an id");
    t.assertEqual(w.watching, true, "watching flag");

    Driver.invoke("mc.system.waitTicks", { ticks: 10 }); // ~500ms > one poll interval

    var page = Driver.invoke("mc.observe.eventsSince", { cursor: c0, types: ["watch.fired"] });
    t.assertTrue(page.length >= 1, "watcher emitted its event (got " + page.length + ")");
    t.assertEqual(page[0].data.watch, w.id, "event data carries the watcher id");

    // once:true → it removed itself after firing.
    var ls = Driver.invoke("mc.events", { op: "list" });
    var present = false;
    for (var i = 0; i < ls.watchers.length; i++) if (ls.watchers[i].id === w.id) present = true;
    t.assertFalse(present, "once-watcher is gone from the list after firing");
});

ScriptTest.run("49_events: watch / list / unwatch lifecycle", function (t) {
    // everyMs 60000 with a one-shot rising edge → first poll is 60s out, so it
    // won't fire during the test; lets us exercise unwatch on a live watcher.
    var w = Driver.invoke("mc.events", {
        op: "watch", invoke: "mc.system.version", emitAs: "lifecycle.x", everyMs: 60000
    });
    t.assertTrue(w.id > 0, "got id");

    var l1 = Driver.invoke("mc.events", { op: "list" });
    var found = false;
    for (var i = 0; i < l1.watchers.length; i++) if (l1.watchers[i].id === w.id) found = true;
    t.assertTrue(found, "watcher appears in list");

    var un = Driver.invoke("mc.events", { op: "unwatch", id: w.id });
    t.assertEqual(un.removed, true, "unwatch removed it");

    var l2 = Driver.invoke("mc.events", { op: "list" });
    var still = false;
    for (var j = 0; j < l2.watchers.length; j++) if (l2.watchers[j].id === w.id) still = true;
    t.assertFalse(still, "gone from list after unwatch");

    var un2 = Driver.invoke("mc.events", { op: "unwatch", id: w.id });
    t.assertEqual(un2.removed, false, "unwatching twice is a no-op");
});
