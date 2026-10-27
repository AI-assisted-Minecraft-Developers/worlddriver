// Script tests against the live game: what a test body's t offers, and the Driver calls a script already has.
// Registrations stay at the top level and unconditional: the test engine outside the game reads them without it.
// The script engine is Rhino, which has arrow functions and let/const but no shorthand properties: { pos: pos }.

const TOP = { x: 0, y: 319, z: 0 };

step('wd.blockType', (t, pos) => t.call('mc.world.block', { pos: pos }).type);

test('a script reads a block through t.call, a named step and Driver', { scene: 'wd.jupiterSmoke' }, (t) => {
    t.expect(t.call('mc.world.block', { pos: TOP }).type).equals('minecraft:air');
    t.expect(t.step('wd.blockType', TOP)).equals('minecraft:air');
    const reading = Driver.world.block({ pos: TOP });
    t.expect(reading.pos).equals(TOP);
    t.expect(reading.light.sky).atLeast(0);
    t.record('sky light', reading.light.sky);
});

test('a script waits on server ticks', { scene: 'wd.jupiterSmoke' }, (t) => {
    let checks = 0;
    t.waitUntil(() => ++checks >= 3, { within: 20, as: 'the third tick' });
    t.expect(checks).equals(3);
    let held = 0;
    t.settle(() => { held++; return true; }, { ticks: 5, within: 20 });
    t.expect(held).equals(5);
    t.wait(2);
});

// A failed t.expect cannot be caught by the script, so a test cannot swallow its own failure; a Driver error can.
test('a script catches a Driver error by its code', { scene: 'wd.jupiterSmoke' }, (t) => {
    let code = null;
    try {
        Driver.invoke('mc.world.block', { pos: 'nowhere' });
    } catch (e) {
        code = e.code;
    }
    t.expect(code).equals('invalid_params');
});
