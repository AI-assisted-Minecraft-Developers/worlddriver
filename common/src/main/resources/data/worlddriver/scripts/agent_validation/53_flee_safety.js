function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ AgentTest.run("53_flee_safety: skipped (no client)", function(t){ /* PASS */ }); }
else {
  AgentTest.run("53_flee_safety: client scene carries the cornered fact used by the flee fallback", function(t){
    var s = Agent.invoke("mc.client.scene", {});
    t.assertEqual(s.present, true, "client scene present");
    t.assertTrue("cornered" in s, "scene carries cornered fact");
  });
}
