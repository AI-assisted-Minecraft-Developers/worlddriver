// Run by kit.js's 'wd.validation' step, which says what ScriptTest is and what the check counts are.
test('53_flee_safety', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '53_flee_safety', checks: { dedicated: 1, integrated: 1 }, DriverApiError: DriverApiError, body: (ScriptTest) => {

function clientAvailable(){ try { Driver.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ ScriptTest.run("53_flee_safety: skipped (no client)", function(t){ /* PASS */ }); }
else {
  ScriptTest.run("53_flee_safety: client scene carries the cornered fact used by the flee fallback", function(t){
    var s = Driver.invoke("mc.client.scene", {});
    t.assertEqual(s.present, true, "client scene present");
    t.assertTrue("cornered" in s, "scene carries cornered fact");
  });
}

}}));
