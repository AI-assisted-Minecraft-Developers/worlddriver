// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('04_action_place', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '04_action_place', DriverApiError: DriverApiError, body: (ScriptTest) => {

ScriptTest.run("04_action_place: placeMany then observe shows new block", function(t) {
    var origin = Driver.system.testOrigin();
    var target = { x: origin.x, y: origin.y + 3, z: origin.z };
    var r = Driver.invoke("mc.action.placeMany",
        { blocks: [{ pos: target, type: "minecraft:cobblestone" }] });
    t.assertEqual(r.placed, 1, "exactly one block should be placed");

    var after = Driver.query({ q: "blocks", center: target, filter: { in_radius: 0 } });
    t.assertEqual(after.blocks.length, 1, "expected exactly 1 block at target");
    t.assertEqual(after.blocks[0].type, "minecraft:cobblestone");
    t.assertEqual(after.blocks[0].pos.x, target.x);
    t.assertEqual(after.blocks[0].pos.y, target.y);
    t.assertEqual(after.blocks[0].pos.z, target.z);
});

}}));
