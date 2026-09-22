// ScriptEvents: KubeJS-style subscription API for long-lived scripts.
//
// Script evaluation order:
//   1. ScriptManager evaluates each *.js (this file)
//   2. After ALL scripts finish, ScriptManager.fireAttach()
//   3. Each tick on the server thread → ScriptEvents.fireTick()
//
// ScriptTest.run executes its body inline at call time. So a test that asserts
// "onAttach has fired" must be registered FROM INSIDE an onAttach callback —
// the callback runs during step 2, which is well after script eval.

var attachCount = 0;
ScriptEvents.onAttach(function () { attachCount++; });

// This test runs inline during script eval — before fireAttach() has run.
ScriptTest.run("09_events: before attach, onAttach callback has not yet fired", function (t) {
    t.assertEqual(attachCount, 0, "onAttach should defer until fireAttach()");
});

ScriptTest.run("09_events: tickListenerCount reflects registration", function (t) {
    var before = ScriptEvents.tickListenerCount();
    ScriptEvents.tick(function () { /* no-op */ });
    var after = ScriptEvents.tickListenerCount();
    t.assertEqual(after, before + 1, "tick listener count should increment by 1");
});

// Defer assertions into the attach phase by registering an onAttach that
// itself runs ScriptTest.run inside the post-eval callback.
ScriptEvents.onAttach(function () {
    ScriptTest.run("09_events: onAttach callback ran (post-attach assert)", function (t) {
        t.assertTrue(attachCount >= 1, "expected attachCount >= 1, got " + attachCount);
    });

    ScriptTest.run("09_events: late-registered onAttach fires immediately", function (t) {
        var lateFired = 0;
        ScriptEvents.onAttach(function () { lateFired++; });
        t.assertEqual(lateFired, 1, "post-attach onAttach should fire immediately");
    });

    ScriptTest.run("09_events: tickCount is a non-negative number", function (t) {
        var n = ScriptEvents.tickCount();
        // Rhino may surface Java longs as java.lang.Long; coerce.
        var v = Number(n);
        t.assertTrue(!isNaN(v) && v >= 0, "tickCount must be a non-negative number, got " + n);
    });
});
