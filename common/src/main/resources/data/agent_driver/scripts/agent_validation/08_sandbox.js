// Sandbox: AgentClassFilter should block dangerous JVM classes from JS, but
// still let legitimate scripts reach console (System.out) and AgentApi
// return values (java.util Map/List).
//
// In this Rhino fork (KubeJS-Mods), `java`, `javax`, `org` are top-level
// JavaPackage globals; denied class names resolve back to JavaPackage objects
// so invoking constructors / static methods throws TypeError.

function denied(thunk) {
    try { thunk(); return false; }
    catch (e) { return true; }
}

AgentTest.run("08_sandbox: java.lang.Runtime is denied", function(t) {
    t.assertTrue(denied(function() { java.lang.Runtime.getRuntime().exec("id"); }),
        "Runtime.getRuntime().exec must throw");
});

AgentTest.run("08_sandbox: java.io.File is denied", function(t) {
    t.assertTrue(denied(function() {
        var f = new java.io.File("/etc/passwd");
        f.exists();
    }), "java.io.File construction must throw");
});

AgentTest.run("08_sandbox: java.net.Socket is denied", function(t) {
    t.assertTrue(denied(function() { new java.net.Socket("127.0.0.1", 1); }),
        "java.net.Socket must throw");
});

AgentTest.run("08_sandbox: java.lang.ProcessBuilder is denied", function(t) {
    t.assertTrue(denied(function() {
        new java.lang.ProcessBuilder(["id"]).start();
    }), "ProcessBuilder must throw");
});

AgentTest.run("08_sandbox: java.lang.Thread is denied", function(t) {
    t.assertTrue(denied(function() {
        new java.lang.Thread(function() {}).start();
    }), "java.lang.Thread must throw");
});

AgentTest.run("08_sandbox: legitimate API still works after sandbox", function(t) {
    // If the sandbox is over-zealous, this would fail.
    var v = Agent.system.version();
    t.assertEqual(v.modid, "agent_driver");
    var origin = Agent.system.testOrigin();
    t.assertTrue(typeof origin.x === "number");
});
