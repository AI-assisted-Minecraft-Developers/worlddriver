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

const page = Driver.observe.eventsSince({ cursor: Driver.observe.cursor().cursor, types: ['block.break'] });
const seq: string = page.events[0].seq;
void seq;
const arrived = Driver.wait.event({ cursor: page.cursor, timeoutMs: 1000 });
if (arrived.outcome === 'done' && !arrived.timedOut) {
    void arrived.events[0].type;
} else if (arrived.outcome === 'started') {
    void Driver.wait.result({ kind: 'wait', waitId: arrived.waitId });
}
Driver.events({ op: 'watch', invoke: 'mc.bot.status', field: 'status.busy', value: false, once: true });
// @ts-expect-error a cursor is a decimal string
Driver.wait.event({ cursor: 5 });

const walk = Driver.bot.goto({
    target: { kind: 'block', block: '#minecraft:logs', radius: 24 },
    route: { mode: ['walk'], break: 'never', leash: { anchor: { kind: 'column', x: 0, z: 0 }, radius: 2, hard: true } },
});
if (walk.outcome === 'scored') {
    void walk.segments[0].risk.exposedTo;
} else if (walk.outcome === 'accepted' && walk.action.state === 'succeeded' && walk.action.result.kind === 'routePreview') {
    const planId: string = walk.action.result.planId;
    driver('mc.bot.goto', { planId });
}
Driver.bot.goto({ plan: 'score', route: { corridor: { points: [{ x: 0, y: 64, z: 0 }] } } });

// @ts-expect-error a leash anchor names its kind
Driver.bot.goto({ target: { kind: 'axis' }, route: { leash: { anchor: { entity: 'PlayerB' }, radius: 4 } } });
// @ts-expect-error plan is preview or score
Driver.bot.goto({ target: { kind: 'axis' }, plan: true });
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
