function clientAvailable(){ try { Driver.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if (!clientAvailable()) {
    ScriptTest.run("52_client_scene: skipped (no client)", function(t){ /* PASS */ });
} else {
    ScriptTest.run("52_client_scene: returns present client snapshot", function(t){
        var s = Driver.invoke("mc.client.scene", {});
        t.assertEqual(s.present, true, "client scene present");
        t.assertTrue(typeof s.dayPhase === "string", "has dayPhase");
        t.assertTrue("cornered" in s, "has cornered fact");
    });
}
