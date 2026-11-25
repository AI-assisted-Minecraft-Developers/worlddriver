// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("69_tick_times", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "69_tick_times",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // mc.system.waitTicks also reports how the server's ticks went while it waited. Every topology runs the
            // server in this JVM, so the reply always carries them here.

            ScriptTest.run("69_tick_times: waitTicks times the server ticks it waited through", function (t) {
                var r = Driver.invoke("mc.system.waitTicks", { ticks: 20 });
                t.assertEqual(r.waited, 20, "waited");
                t.assertTrue(r.server !== undefined && r.server !== null, "server tick times present");
                var s = r.server;
                // The wait is a second of wall clock. A server behind its clock runs ticks back to back to catch up,
                // for up to two seconds' worth before it skips them, and a stalled one runs few.
                t.assertTrue(s.ticks >= 1 && s.ticks <= 60, "ticks ran " + s.ticks);
            });
        },
    })
);
