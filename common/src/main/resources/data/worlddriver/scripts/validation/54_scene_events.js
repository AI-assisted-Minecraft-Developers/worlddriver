function clientAvailable(){ try { Driver.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ ScriptTest.run("54_scene_events: skipped (no client)", function(t){ /* PASS */ }); }
else {
  ScriptTest.run("54_scene_events: client scene exposes the fields the events are derived from", function(t){
    var s = Driver.invoke("mc.client.scene", {});
    t.assertTrue("exposedAtNight" in s, "scene has exposedAtNight");
    t.assertTrue("cornered" in s, "scene has cornered");
  });
}
