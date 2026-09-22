function clientAvailable(){ try { Driver.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ ScriptTest.run("53_flee_safety: skipped (no client)", function(t){ /* PASS */ }); }
else {
  ScriptTest.run("53_flee_safety: client scene carries the cornered fact used by the flee fallback", function(t){
    var s = Driver.invoke("mc.client.scene", {});
    t.assertEqual(s.present, true, "client scene present");
    t.assertTrue("cornered" in s, "scene carries cornered fact");
  });
}
