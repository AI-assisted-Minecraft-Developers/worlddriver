// AgentEvents: KubeJS-style subscription API for long-lived scripts.
//
// Script evaluation order:
//   1. AgentScriptManager evaluates each *.js (this file)
//   2. After ALL scripts finish, AgentScriptManager.fireAttach()
//   3. Each tick on the server thread → AgentEvents.fireTick()
//
// AgentTest.run executes its body inline at call time. So a test that asserts
// "onAttach has fired" must be registered FROM INSIDE an onAttach callback —
// the callback runs during step 2, which is well after script eval.

var attachCount = 0;
AgentEvents.onAttach(function () { attachCount++; });

// This test runs inline during script eval — before fireAttach() has run.
AgentTest.run("09_events: before attach, onAttach callback has not yet fired", function (t) {
    t.assertEqual(attachCount, 0, "onAttach should defer until fireAttach()");
});

AgentTest.run("09_events: tickListenerCount reflects registration", function (t) {
    var before = AgentEvents.tickListenerCount();
    AgentEvents.tick(function () { /* no-op */ });
    var after = AgentEvents.tickListenerCount();
    t.assertEqual(after, before + 1, "tick listener count should increment by 1");
});

// Defer assertions into the attach phase by registering an onAttach that
// itself runs AgentTest.run inside the post-eval callback.
AgentEvents.onAttach(function () {
    AgentTest.run("09_events: onAttach callback ran (post-attach assert)", function (t) {
        t.assertTrue(attachCount >= 1, "expected attachCount >= 1, got " + attachCount);
    });

    AgentTest.run("09_events: late-registered onAttach fires immediately", function (t) {
        var lateFired = 0;
        AgentEvents.onAttach(function () { lateFired++; });
        t.assertEqual(lateFired, 1, "post-attach onAttach should fire immediately");
    });

    AgentTest.run("09_events: tickCount is a non-negative number", function (t) {
        var n = AgentEvents.tickCount();
        // Rhino may surface Java longs as java.lang.Long; coerce.
        var v = Number(n);
        t.assertTrue(!isNaN(v) && v >= 0, "tickCount must be a non-negative number, got " + n);
    });
});
