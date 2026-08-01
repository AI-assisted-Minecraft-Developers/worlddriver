function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ AgentTest.run("54_scene_events: skipped (no client)", function(t){ /* PASS */ }); }
else {
  AgentTest.run("54_scene_events: client scene exposes the fields the events are derived from", function(t){
    var s = Agent.invoke("mc.client.scene", {});
    t.assertTrue("exposedAtNight" in s, "scene has exposedAtNight");
    t.assertTrue("cornered" in s, "scene has cornered");
  });
}
