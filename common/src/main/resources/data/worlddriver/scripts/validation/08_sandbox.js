// Sandbox: dangerous JVM classes must not be reachable from JS, while legitimate
// scripts still reach console (System.out) and DriverApi return values
// (java.util Map/List).
//
// ⚠️ THESE SIX GREENS ARE NOT EVIDENCE THAT ScriptClassFilter WORKS, AND AGENTS.md
// HARD RULE #3 POINTS AT THIS FILE AS THOUGH THEY WERE. Read that rule ("don't
// widen the Rhino sandbox without a matching negative test here") knowing the
// following, all of it read off the code and off two run logs:
//
//   * ScriptClassFilter.isAllowed opens with `if (DISABLED) return true;`, and
//     DISABLED is true unless -Dworlddriver.sandbox=on. Nothing in the build sets
//     it (grep the *.gradle files; docs/dev/architecture.md says the same).
//   * So in every gate run the filter denies NOTHING — every name is allowed.
//   * And every check below still passes. Measured on both loaders' most recent
//     dogfood runs: six PASS here plus "11_script_eval: sandbox still applies
//     inside eval", zero failures, filter off throughout.
//
// Whatever is refusing `new java.io.File(...)`, it is not this filter, so a change
// that widened the filter would not redden a single line of this file. The default
// being off is a standing decision (see ScriptClassFilter's header — the caller
// owns the trust boundary); what is wrong is only this suite's claim to guard it.
//
// WHAT IS NOT ESTABLISHED — and the one probe that would settle it. The scope is
// built by `cx.initStandardObjects()` (ScriptManager.loadAll) with no LiveConnect
// or Packages install, and ScriptClassFilter's own header says this Rhino fork
// strips the Packages global. The leading hypothesis is therefore that `java`
// never resolves at all, so each denied() below is catching a TypeError on
// `undefined` rather than a refusal. Two checks cannot discriminate even in
// principle: Runtime.exec("id") throws on any host without `id` on PATH, and
// Socket("127.0.0.1", 1) throws on connection refused. The separating reading is
// `typeof java` and `typeof java.io.File` inside this scope.
//
// ⛔ Do NOT simply add that probe as a seventh ScriptTest here.
// WorldDriverCoreScenes pins the suite's EXACT check count on both topologies
// (RPC_SMOKE_EXPECTED_TOTAL_DEDICATED / _INTEGRATED) as a coverage-drift guard, so
// any added or removed check reddens wd.agentRpcSmoke until both constants move in
// the same commit.

function denied(thunk) {
    try { thunk(); return false; }
    catch (e) { return true; }
}

ScriptTest.run("08_sandbox: java.lang.Runtime is denied", function(t) {
    t.assertTrue(denied(function() { java.lang.Runtime.getRuntime().exec("id"); }),
        "Runtime.getRuntime().exec must throw");
});

ScriptTest.run("08_sandbox: java.io.File is denied", function(t) {
    t.assertTrue(denied(function() {
        var f = new java.io.File("/etc/passwd");
        f.exists();
    }), "java.io.File construction must throw");
});

ScriptTest.run("08_sandbox: java.net.Socket is denied", function(t) {
    t.assertTrue(denied(function() { new java.net.Socket("127.0.0.1", 1); }),
        "java.net.Socket must throw");
});

ScriptTest.run("08_sandbox: java.lang.ProcessBuilder is denied", function(t) {
    t.assertTrue(denied(function() {
        new java.lang.ProcessBuilder(["id"]).start();
    }), "ProcessBuilder must throw");
});

ScriptTest.run("08_sandbox: java.lang.Thread is denied", function(t) {
    t.assertTrue(denied(function() {
        new java.lang.Thread(function() {}).start();
    }), "java.lang.Thread must throw");
});

ScriptTest.run("08_sandbox: legitimate API still works after sandbox", function(t) {
    // If the sandbox is over-zealous, this would fail.
    var v = Driver.system.version();
    t.assertEqual(v.modid, "worlddriver");
    var origin = Driver.system.testOrigin();
    t.assertTrue(typeof origin.x === "number");
});
