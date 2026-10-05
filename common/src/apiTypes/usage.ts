// Checked by `./gradlew :common:checkApiTypes` against the generated declarations: every call below
// must type-check, and every line under @ts-expect-error must be rejected.
/// <reference path="../../build/generated/worlddriver-api/worlddriver.d.ts" />
/// <reference path="../../build/generated/worlddriver-api/driver-function.d.ts" />

const reply = Driver.bot.clearArea({
    body: 'player:builder',
    target: { kind: 'tunnel', direction: 'forward', distance: 12, width: 1, height: 2 },
    awaitMs: 2500,
});
if (reply.outcome === 'refused') {
    const why: WorldDriver.RefusalReason = reply.reason;
    void why;
} else if (reply.action.state === 'succeeded') {
    const from: WorldDriver.BlockPosition = reply.action.result.from;
    void from;
} else if (reply.action.state === 'superseded') {
    const next: string = reply.action.replacementActionId;
    void next;
}

Driver.invoke('mc.bot.clearArea', { target: { kind: 'box', from: { x: 1, y: 2, z: 3 }, to: { x: 4, y: 5, z: 6 } },
    edit: { kind: 'replace', from: 'minecraft:stone', to: 'minecraft:dirt' } });
driver('mc.bot.clearArea', { target: { kind: 'tunnel', distance: 12 } });

const read = Driver.wait.result({ kind: 'action', actionId: 'a1' });
if (read.kind === 'action' && read.action.state === 'succeeded' && read.action.result.kind === 'clearArea') {
    void read.action.result.to;
}

// @ts-expect-error a string where a number belongs
Driver.bot.clearArea({ target: { kind: 'tunnel', distance: '12' } });
// @ts-expect-error another variant's field
driver('mc.bot.clearArea', { target: { kind: 'tunnel', distance: 12, from: { x: 1, y: 2, z: 3 } } });
// @ts-expect-error not a body ref
Driver.bot.clearArea({ body: 'builder', target: { kind: 'tunnel', distance: 1 } });
// @ts-expect-error the target is required
Driver.bot.clearArea({});
// @ts-expect-error no such method
Driver.invoke('mc.bot.nope', {});
