// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('49_events', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '49_events', DriverApiError: DriverApiError, body: (ScriptTest) => {

// The event stream's server side: mc.events (emit, watchers), its replay and its waits. Pure server
// work (the ring buffer, watchers polling pollable reads), so it runs identically on the dedicated
// server and in a client; the live push (WebSocket subscribe / MCP SSE) needs a persistent
// connection and is covered by the transport's own tests. Watchers poll mc.observe.cursor because it
// is a typed, pollable read on every topology.

function issueOf(fn) {
    try { fn(); return null; } catch (e) { return { code: e.code, path: e.issues && e.issues.length ? e.issues[0].path : null }; }
}

ScriptTest.run("49_events: emit injects a custom event that replays via eventsSince", function (t) {
    var c0 = Driver.observe.cursor().cursor;
    var em = Driver.events({ op: "emit", type: "test.custom", data: { hello: "world" } });
    t.assertEqual(em.op, "emit", "a reply tagged with its op");
    t.assertEqual(typeof em.seq, "string", "seq is a decimal string (" + em.seq + ")");
    t.assertEqual(em.type, "test.custom", "echoes type");

    var page = Driver.observe.eventsSince({ cursor: c0, types: ["test.custom"] });
    t.assertTrue(page.events.length >= 1, "the custom event is replayable (got " + page.events.length + ")");
    var ev = page.events[page.events.length - 1];
    t.assertEqual(ev.seq, em.seq, "the replayed event is the emitted one");
    t.assertEqual(ev.type, "test.custom", "type matches");
    // data is a real object on the wire, not JSON escaped inside a string.
    t.assertEqual(ev.data.hello, "world", "object data round-tripped as an object");
    t.assertTrue(typeof ev.timestamp === "number", "a timestamp stays a number");
});

ScriptTest.run("49_events: emit requires a type; bad op rejected", function (t) {
    var noType = issueOf(function () { Driver.events({ op: "emit" }); });
    t.assertEqual(noType && noType.path, "/type", "emit without type is refused at /type");

    var bogus = issueOf(function () { Driver.events({ op: "bogus" }); });
    t.assertEqual(bogus && bogus.path, "/op", "an unknown op is refused at /op");
});

ScriptTest.run("49_events: a rising-edge watcher emits emitAs into the stream", function (t) {
    var c0 = Driver.observe.cursor().cursor;
    // The cursor is a non-empty string every poll, so the test is true on the first poll: it fires
    // once (after 200ms) and, being once, removes itself.
    var w = Driver.events({ op: "watch", invoke: "mc.observe.cursor", field: "cursor",
        emitAs: "watch.fired", everyMs: 200, once: true });
    t.assertEqual(w.op, "watch", "watch acknowledged");
    t.assertTrue(w.id > 0, "watch got an id");

    Driver.invoke("mc.system.waitTicks", { ticks: 10 }); // ~500ms > one poll interval

    var page = Driver.observe.eventsSince({ cursor: c0, types: ["watch.fired"] });
    t.assertTrue(page.events.length >= 1, "watcher emitted its event (got " + page.events.length + ")");
    t.assertEqual(page.events[0].data.watch, w.id, "event data carries the watcher id");
    t.assertEqual(typeof page.events[0].data.value, "string", "and the polled value");

    var ls = Driver.events({ op: "list" });
    var present = false;
    for (var i = 0; i < ls.watchers.length; i++) if (ls.watchers[i].id === w.id) present = true;
    t.assertFalse(present, "once-watcher is gone from the list after firing");
});

ScriptTest.run("49_events: watch / list / unwatch lifecycle", function (t) {
    // everyMs 60000: the first poll is a minute out, so it cannot fire during the test.
    var w = Driver.events({ op: "watch", invoke: "mc.observe.cursor", emitAs: "lifecycle.x", everyMs: 60000 });
    t.assertTrue(w.id > 0, "got id");

    var l1 = Driver.events({ op: "list" });
    var found = false;
    for (var i = 0; i < l1.watchers.length; i++) if (l1.watchers[i].id === w.id) found = true;
    t.assertTrue(found, "watcher appears in list");

    t.assertEqual(Driver.events({ op: "unwatch", id: w.id }).removed, true, "unwatch removed it");
    var l2 = Driver.events({ op: "list" });
    var still = false;
    for (var j = 0; j < l2.watchers.length; j++) if (l2.watchers[j].id === w.id) still = true;
    t.assertFalse(still, "gone from list after unwatch");
    t.assertEqual(Driver.events({ op: "unwatch", id: w.id }).removed, false, "unwatching twice is a no-op");
});

ScriptTest.run("49_events: only a pollable read may be watched or waited on", function (t) {
    // A goto repeated by a wait would start a new walk every poll.
    var go = issueOf(function () {
        Driver.wait.condition({ invoke: "mc.bot.goto",
            params: { target: { kind: "position", pos: { x: 10, y: 64, z: 20 } } }, field: "completed" });
    });
    t.assertEqual(go && go.code, "invalid_params", "a write as a condition source is invalid_params");
    t.assertEqual(go && go.path, "/invoke", "reported at /invoke");

    // A watcher that waits would hold a poll for its whole budget.
    var nested = issueOf(function () {
        Driver.events({ op: "watch", invoke: "mc.wait.condition",
            params: { invoke: "mc.observe.cursor", field: "cursor" }, emitAs: "ready" });
    });
    t.assertEqual(nested && nested.path, "/invoke", "a nested wait is refused at /invoke");

    var badParams = issueOf(function () {
        Driver.wait.condition({ invoke: "mc.observe.cursor", params: { extra: 1 }, timeoutMs: 100 });
    });
    t.assertEqual(badParams && badParams.path, "/params/extra", "the polled params meet the target's own contract");
});

ScriptTest.run("49_events: wait.event answers an event after the cursor, and chains", function (t) {
    var c0 = Driver.observe.cursor().cursor;
    var em = Driver.events({ op: "emit", type: "test.waited", data: 7 });
    var r = Driver.wait.event({ cursor: c0, types: ["test.waited"], timeoutMs: 2000 });
    t.assertEqual(r.outcome, "done", "a foreground wait answers done");
    t.assertFalse(r.timedOut, "the event was already there");
    t.assertEqual(r.events[0].seq, em.seq, "it is the emitted event");
    t.assertEqual(r.cursor, em.seq, "the reply's cursor is the last event's seq");

    var empty = Driver.wait.event({ cursor: r.cursor, types: ["test.waited"], timeoutMs: 200 });
    t.assertTrue(empty.timedOut, "nothing after the chained cursor");
    t.assertEqual(empty.cursor, r.cursor, "an empty reply keeps the cursor");
});

ScriptTest.run("49_events: a background wait answers a waitId, then its result and a wait.done", function (t) {
    var c0 = Driver.observe.cursor().cursor;
    var ack = Driver.wait.condition({ invoke: "mc.observe.cursor", field: "cursor", background: true });
    t.assertEqual(ack.outcome, "started", "background answers at once");
    var got = null;
    for (var i = 0; i < 40 && got === null; i++) {
        var r = Driver.wait.result({ kind: "wait", waitId: ack.waitId, consume: false });
        if (!r.pending) got = r; else Driver.invoke("mc.system.waitTicks", { ticks: 1 });
    }
    t.assertTrue(got !== null, "the background wait finished");
    t.assertEqual(got.result.outcome, "done", "its result is the wait's done reply");
    t.assertTrue(got.result.satisfied, "and it was satisfied");

    var done = Driver.observe.eventsSince({ cursor: c0, types: ["wait.done"] }).events.filter(function (e) {
        return e.data.waitId === ack.waitId;
    });
    t.assertEqual(done.length, 1, "one wait.done event for it");
    t.assertEqual(done[0].data.method, "mc.wait.condition", "naming the wait");
    t.assertEqual(done[0].data.result.satisfied, true, "carrying the same result");
});

}}));
