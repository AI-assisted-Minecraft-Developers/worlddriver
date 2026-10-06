// Prelude loaded into every mc.script.eval scope. Defines the global
// console, Driver.invoke (with Java-exception-prefix stripping) and the
// Driver.{system,observe,action,query,wait,client,bot} surface that wraps
// the canonical MCP routes. Loaded from classpath by ScriptEvaluator.

var __log = [];
var __result = null, __error = null;

function __fmt(v) {
    try { return (typeof v === 'string') ? v : JSON.stringify(v); }
    catch (_) { return String(v); }
}

var console = {
    log:   function (m) { __log.push(__fmt(m)); },
    error: function (m) { __log.push('[err] ' + __fmt(m)); }
};

// The Java class name of a wrapped Java object, e.g. "java.util.ArrayList"; null for
// null/undefined and for plain JS values. Scripts cannot call obj.getClass() in this
// Rhino: it never exposes methods only java.lang.Object declares (see ScriptJava).
// A value typeof calls a string, number or boolean is a JS value here even when Java
// produced it (a List element, a method returning long): it behaves as one and cannot
// be told apart from a literal, so it is null too. A Minecraft class's name depends on
// the loader's runtime mappings (net.minecraft.class_1657 in a Fabric production game).
function javaClassName(v) {
    if (v === null || v === undefined) return null;
    var t = typeof v;
    if (t === 'string' || t === 'number' || t === 'boolean') return null;
    var name = __java.className(v);
    return name === null ? null : String(name);
}

var Driver = {};

// The error every Driver call throws: code, issues and execution as the API reported them,
// message for a person. `e.code === 'invalid_params'` is how a script tells a bad request
// from anything else; the message is not parsed.
function DriverApiError(error) {
    this.name = 'DriverApiError';
    this.message = String(error.message);
    this.code = String(error.code);
    this.issues = error.issues || [];
    this.execution = String(error.execution);
}
DriverApiError.prototype = Object.create(Error.prototype);
DriverApiError.prototype.constructor = DriverApiError;

// The one generic call. Omitted params are sent as none, never as {} made up here: a method
// that takes nothing accepts that, and null, false or an array are still reported as what they are.
Driver.invoke = function (method, params) {
    var reply = JSON.parse(String(__api.invokeJson(method, params === undefined ? null : JSON.stringify(params))));
    if (reply.error) throw new DriverApiError(reply.error);
    return reply.result;
};

Driver.system = {
    version:    function ()      { return Driver.invoke('mc.system.version',    {}); },
    testOrigin: function ()      { return Driver.invoke('mc.system.testOrigin', {}); },
    waitTicks:  function (ticks) { return Driver.invoke('mc.system.waitTicks', { ticks: ticks | 0 }); }
};

Driver.observe = {
    // Sugar over mc.query q='blocks' — returns {blocks:[...]} for the same
    // shape callers used before observe.area was merged into query.
    area: function (p) {
        var q = { q: 'blocks', filter: { in_radius: (p && p.radius) | 0 } };
        if (p && p.center) q.center = p.center;
        if (p && p.filter && p.filter.type) q.filter.type = p.filter.type;
        var rows = Driver.invoke('mc.query', q);
        return { blocks: rows || [] };
    },
    cursor:      function ()  { return Driver.invoke('mc.observe.cursor', {}); },
    eventsSince: function (c, opts) {
        var p = { cursor: c };
        if (opts && opts.types) p.types = opts.types;
        if (opts && opts.limit !== undefined) p.limit = opts.limit;
        return Driver.invoke('mc.observe.eventsSince', p);
    },
    player:    function (name) { return Driver.invoke('mc.observe.player', name ? { name: name } : {}); },
    // Phase G boss sensing — nearest dragon/wither + End-crystal list.
    boss:      function (opts)  { return Driver.invoke('mc.observe.boss', opts || {}); },
    // Hazard-field spatial scene — opts {radius?, center?, render?, overlays?}.
    scene:     function (opts)  { return Driver.invoke('mc.observe.scene', opts || {}); },
    // ASCII spatial map — opts {plane:'xz'|'xy'|'zy', radius, height, center?}.
    // Top-down heightmap (xz) or vertical cross-section (xy/zy); mobs overlaid.
    map:       function (opts)  { return Driver.invoke('mc.observe.map', opts || {}); }
};

Driver.action = {
    placeBlock: function (p) {
        // Sugar over placeMany for the single-block case.
        var ev = (p && p.returnEvents) || undefined;
        var args = { blocks: [{ pos: p.pos, type: p.type }] };
        if (ev) args.returnEvents = true;
        return Driver.invoke('mc.action.placeMany', args);
    },
    placeMany:  function (blocks)         { return Driver.invoke('mc.action.placeMany', { blocks: blocks }); },
    fill:       function (from, to, type) { return Driver.invoke('mc.action.fill', { from: from, to: to, type: type }); },
    runCommand: function (c)              { return Driver.invoke('mc.action.runCommand', { cmd: c }); }
};

Driver.query = function (p) { return Driver.invoke('mc.query', p); };

// Phase H — goal-directed acquisition planner: "I want X" → ordered mine/farm/
// smelt/craft steps (recipe.resolve + how to get every missing leaf).
Driver.plan = {
    acquire: function (opts) { return Driver.invoke('mc.plan.acquire', opts || {}); }
};

// Driver→agent event channel (server-side surface). The live push rides the
// transports (subscribe over WebSocket / the MCP SSE stream at /mcp/events);
// these helpers cover injecting custom events and registering condition watchers.
Driver.events = {
    // Inject a custom event into the stream (the manual "custom condition met" path).
    emit:    function (type, data, pos) {
        var p = { op: 'emit', type: type };
        if (data !== undefined) p.data = data;
        if (pos !== undefined) p.pos = pos;
        return Driver.invoke('mc.events', p);
    },
    // Register a rising-edge watcher. opts: {invoke, params?, field?, value?|above?|
    // below?, emitAs?, everyMs?, once?}. First poll where the predicate flips
    // false→true emits emitAs (default 'condition.met') into the stream.
    watch:   function (opts) {
        var p = Object.assign({ op: 'watch' }, opts || {});
        return Driver.invoke('mc.events', p);
    },
    unwatch: function (id) { return Driver.invoke('mc.events', { op: 'unwatch', id: id }); },
    list:    function ()   { return Driver.invoke('mc.events', { op: 'list' }); }
};

// Wait helpers — convenience wrappers; full options accepted on the opts object.
Driver.wait = {
    worldReady: function (opts) { return Driver.invoke('mc.wait.worldReady', opts || {}); },
    event:      function (cursor, opts) {
        var p = Object.assign({ cursor: cursor | 0 }, opts || {});
        return Driver.invoke('mc.wait.event', p);
    },
    condition:  function (opts) { return Driver.invoke('mc.wait.condition', opts || {}); }
};

// The mc.client.* methods come from the generated wrappers loaded after this file
// (Driver.client.screen.info(), Driver.client.input.click({x, y}), …). The inventory and pause
// screens open through their bound keys, the path a player takes.
Driver.client = {
    screen: {
        openInventory: function () { return Driver.invoke('mc.client.input.key', { key: 'E' }); },
        openPause:     function () { return Driver.invoke('mc.client.input.key', { key: 'ESCAPE' }); }
    }
};

// The body tasks (goto, mine, craft, …), the hand verbs (lookAt, useItem, …), status, cancel,
// waypoint and setting come from the generated wrappers loaded after this file.
Driver.bot = {
    // Sugar over mc.bot.setting's {key, value} lists. patchSettings({autoEat: true, …}) writes those
    // keys together and answers the method's reply; readSettings(['autoEat', …]) answers
    // {autoEat: true, …}, every readable key when no list is given.
    patchSettings: function (values) {
        var updates = [];
        for (var k in values) if (Object.prototype.hasOwnProperty.call(values, k)) updates.push({ key: k, value: values[k] });
        return Driver.invoke('mc.bot.setting', { op: 'patch', updates: updates });
    },
    readSettings: function (keys) {
        var p = { op: 'read' };
        if (keys !== undefined) p.keys = keys;
        var values = Driver.invoke('mc.bot.setting', p).values, out = {};
        for (var i = 0; i < values.length; i++) out[values[i].key] = values[i].value;
        return out;
    },
    pause:     function ()     { return Driver.invoke('mc.bot.setting', { op: 'apply', update: { key: 'paused', value: true } }); },
    resume:    function ()     { return Driver.invoke('mc.bot.setting', { op: 'apply', update: { key: 'paused', value: false } }); },
    // Baritone-style survival toggles — sugar over mc.bot.setting.
    autoEat:    function (on, opts) {
        var p = { autoEat: !!on };
        if (opts && opts.threshold !== undefined) p.autoEatFoodThreshold = opts.threshold | 0;
        return Driver.bot.patchSettings(p);
    },
    autoRespawn: function (on) { return Driver.bot.patchSettings({ autoRespawn: !!on }); },
    // Baritone BackfillProcess analogue — when on, the bot tracks the cells its
    // own digs break (never air it only walks through) and refills them with
    // `block` (default cobblestone) whenever it would otherwise be idle. Pass
    // {radius:N} to cap how far from the player the auto-fill considers
    // (default 6, range 1..16).
    autoBackfill: function (on, opts) {
        var p = { autoBackfill: !!on };
        if (opts && opts.block !== undefined)  p.autoBackfillBlock  = String(opts.block);
        if (opts && opts.radius !== undefined) p.autoBackfillRadius = opts.radius | 0;
        return Driver.bot.patchSettings(p);
    },
    // Baritone-style waypoints — sugar over mc.bot.waypoint; pos defaults to where the player stands.
    saveWaypoint:   function (name, pos) {
        var op = { op: 'save', name: name };
        if (pos !== undefined) op.pos = pos;
        return Driver.invoke('mc.bot.waypoint', op);
    },
    gotoWaypoint:   function (name, opts) {
        var p = Object.assign({ target: { kind: 'waypoint', name: name } }, opts || {});
        return Driver.invoke('mc.bot.goto', p);
    },
    listWaypoints:  function () { return Driver.invoke('mc.bot.waypoint', { op: 'list' }); },
    deleteWaypoint: function (name) { return Driver.invoke('mc.bot.waypoint', { op: 'delete', name: name }); }
};
