// Run by kit.js's 'wd.validation' step, which says what ScriptTest is and what the check counts are.
test('54_scene_events', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '54_scene_events', checks: { dedicated: 1, integrated: 1 }, DriverApiError: DriverApiError, body: (ScriptTest) => {

function clientAvailable(){ try { Driver.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ ScriptTest.run("54_scene_events: skipped (no client)", function(t){ /* PASS */ }); }
else {
  ScriptTest.run("54_scene_events: client scene exposes the fields the events are derived from", function(t){
    var s = Driver.invoke("mc.client.scene", {});
    t.assertTrue("exposedAtNight" in s, "scene has exposedAtNight");
    t.assertTrue("cornered" in s, "scene has cornered");
  });
}

}}));
