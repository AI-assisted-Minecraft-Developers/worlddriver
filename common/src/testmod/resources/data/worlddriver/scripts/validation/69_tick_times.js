// mc.system.waitTicks also reports how the server's ticks went while it waited. Every topology runs the
// server in this JVM, so the reply always carries them here.

ScriptTest.run("69_tick_times: waitTicks times the server ticks it waited through", function(t) {
    var r = Driver.invoke("mc.system.waitTicks", { ticks: 20 });
    t.assertEqual(r.waited, 20, "waited");
    t.assertTrue(r.server !== undefined && r.server !== null, "server tick times present");
    var s = r.server;
    t.assertTrue(s.ticks >= 10 && s.ticks <= 30, "ticks ran " + s.ticks);
    t.assertTrue(s.meanMs >= 0 && s.meanMs <= s.maxMs, "mean " + s.meanMs + " within max " + s.maxMs);
    t.assertTrue(s.medianMs >= 0 && s.medianMs <= s.maxMs, "median " + s.medianMs + " within max " + s.maxMs);
});
