// What a script test leases and overrides through t, each given back or undone when the test ends.

const SCENE = 'wd.jupiterSmoke';

function dayTime(t) {
    return t.call('mc.observe.player', { name: t.player().name }).player.time.dayTime;
}

test('a plot is leased empty and keeps what the test places', { scene: SCENE }, (t) => {
    const plot = t.plot({ size: 8 });
    t.expect(plot.size).equals(8);
    const at = plot.at(1, 1, 1);
    t.expect(t.call('mc.world.block', { pos: at }).type).equals('minecraft:air');
    plot.place(1, 1, 1, 'minecraft:stone');
    t.expect(t.call('mc.world.block', { pos: at }).type).equals('minecraft:stone');
});

test('built-in steps place a chest and fill it', { scene: SCENE }, (t) => {
    t.plot({ size: 8 });
    t.step('place', { at: [1, 1, 1], block: 'minecraft:chest', as: 'chest' });
    t.step('insert', { into: 'chest', items: { 'minecraft:iron_ingot': 3 } });
    t.expect(t.step('container', 'chest')).equals({ 'minecraft:iron_ingot': 3 });
});

test('perf runs each phase before measuring it', { scene: SCENE }, (t) => {
    let ran = 0;
    const result = t.perf({
        warmup: 5, sample: 20, repeat: 1,
        phases: [{ name: 'baseline' }, { name: 'after', run: () => { ran++; } }],
    });
    t.expect(ran).equals(1);
    t.expect(result.after.ticks).equals(20);
    t.expect(result.after).contains({ ticks: 20 });
});

test('a bot joins for the test and holds what it is given', { scene: SCENE }, (t) => {
    const bot = t.bot();
    t.expect(bot.ref).contains('player:');
    bot.give('minecraft:diamond', 3);
    t.expect(bot.count('minecraft:diamond')).equals(3);
});

test('the client sees the player the server has', { scene: SCENE, requires: 'client' }, (t) => {
    t.expect(t.client().call('mc.client.player', {}).name).equals(t.player().name);
});

// Script tests run concurrently, so an override names the lock that keeps the others off what it changes.
test('an override holds for the test', {
    scene: SCENE, requires: 'client', locks: ['worldClock', 'worldRules'],
}, (t) => {
    t.overrideTime(1234);
    t.expect(dayTime(t)).equals(1234);
    t.override('randomTickSpeed', 0);
});

// The scene declares no time, so its clock stands where a quiet world's does: 18000, frozen.
test('and is undone when it ends', {
    scene: SCENE, requires: 'client', dependsOn: ['an override holds for the test'], locks: ['worldClock'],
}, (t) => {
    t.expect(dayTime(t)).equals(18000);
});

// Script tests run beside the Java tests, so either kind waits for the other it depends on.
const SMOKE = 'net.magicterra.worlddriver.bot.stagewright.jupiter.JupiterSmokeTest';

// JupiterSmokeTest#runsAfterTheScriptTestItDependsOn depends on this, and finds the mark.
test('a Java test depends on this', { scene: SCENE }, (t) => {
    t.wait(20);
    t.step('wd.mark', 'a Java test depends on this');
});

test('runs after the Java test it depends on passed', { scene: SCENE, dependsOn: [SMOKE + '#ticksPassOnTheServer'] },
    (t) => {
        t.wait(1);
    });

// Aborted, never run: reported as skipped with "is not in this run", which is what this test is here to show.
test('is aborted when the Java test it depends on is not in the run', {
    scene: SCENE, dependsOn: [SMOKE + '#noSuchTest'],
}, (t) => {
    t.expect('ran').equals('aborted before running');
});

test('a known issue is reported as one', {
    scene: SCENE, knownIssue: 'fails on purpose, so the run shows knownIssue turning a failure into an abort',
}, (t) => {
    t.expect(1 + 1).equals(3);
});
