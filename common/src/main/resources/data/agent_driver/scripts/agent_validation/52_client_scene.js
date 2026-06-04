function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if (!clientAvailable()) {
    AgentTest.run("52_client_scene: skipped (no client)", function(t){ /* PASS */ });
} else {
    AgentTest.run("52_client_scene: returns present client snapshot", function(t){
        var s = Agent.invoke("mc.client.scene", {});
        t.assertEqual(s.present, true, "client scene present");
        t.assertTrue(typeof s.dayPhase === "string", "has dayPhase");
        t.assertTrue("cornered" in s, "has cornered fact");
    });
}
