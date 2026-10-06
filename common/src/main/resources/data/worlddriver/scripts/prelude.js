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
    container: function (pos)  { return Driver.invoke('mc.observe.container', { pos: pos }); },
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

// Phase H — persistent skill library (Voyager). Write a JS skill once, reuse it
// by name across sessions. The skill reads its call args from a SKILL global.
Driver.skill = {
    save:   function (name, source) { return Driver.invoke('mc.skill', { op: 'save', name: name, source: source }); },
    list:   function ()             { return Driver.invoke('mc.skill', { op: 'list' }); },
    get:    function (name)         { return Driver.invoke('mc.skill', { op: 'get', name: name }); },
    run:    function (name, args)   { return Driver.invoke('mc.skill', { op: 'run', name: name, args: args || {} }); },
    remove: function (name)         { return Driver.invoke('mc.skill', { op: 'delete', name: name }); }
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

// Client helpers — symmetric with system/observe/action. On dedicated server
// these all error with 'mc.client.* not available', which the caller's
// try/catch can handle.
Driver.client = {
    screen: {
        info:          function () { return Driver.invoke('mc.client.screen.info', {}); },
        tree:          function () { return Driver.invoke('mc.client.screen.tree', {}); },
        // Inventory / pause are reachable by synthesizing the bound key — same
        // path the player takes — so no dedicated open* tool is needed.
        openInventory: function () { return Driver.invoke('mc.client.input.key', { key: 'E' }); },
        openPause:     function () { return Driver.invoke('mc.client.input.key', { key: 'ESCAPE' }); },
        close:         function () { return Driver.invoke('mc.client.screen.close', {}); }
    },
    input: {
        click:     function (x, y, button) { return Driver.invoke('mc.client.input.click', { x: x, y: y, button: button | 0 }); },
        mouseMove: function (x, y)         { return Driver.invoke('mc.client.input.mouseMove', { x: x, y: y }); }
    },
    chat: {
        send:    function (text, awaitReplyMs) {
            var p = { text: text };
            if (awaitReplyMs !== undefined) p.awaitReplyMs = awaitReplyMs | 0;
            return Driver.invoke('mc.client.chat.send', p);
        },
        history: function (opts) { return Driver.invoke('mc.client.chat.history', opts || {}); }
    },
    overlays:   function (opts) { return Driver.invoke('mc.client.overlays',  opts || {}); },
    screenshot: function (opts) { return Driver.invoke('mc.client.screenshot', opts || {}); },
    // Client-AUTHORITATIVE reads — always the LocalPlayer / ClientLevel, even
    // when a server is attached (unlike Driver.observe.player / Driver.query which
    // prefer the server). player() adds pose, eyePos, isInWall/inWater, and the
    // eye/feet block from the ClientLevel — diff vs Driver.observe.player() to
    // spot a client/server desync. blocks({center,filter}) scans ClientLevel.
    player:     function ()     { return Driver.invoke('mc.client.player', {}); },
    blocks:     function (opts) { return Driver.invoke('mc.client.blocks', opts || {}); },
    scene:      function ()     { return Driver.invoke('mc.client.scene',  {}); }
};

// The body tasks (goto, mine, craft, …), the hand verbs (lookAt, useItem, …), status, cancel and
// waypoint come from the generated wrappers loaded after this file.
Driver.bot = {
    // Pass key/value pairs to write tuning settings; pass {} (or no arg) to read.
    setting:   function (opts) { return Driver.invoke('mc.bot.setting',   opts || {}); },
    // Phase G boss playbooks — {name:'dragon'|'wither'} to start (async, background),
    // {op:'status'} to poll, {op:'cancel'} to stop.
    playbook:  function (opts) { return Driver.invoke('mc.bot.playbook',  opts || {}); },
    pause:     function ()     { return Driver.invoke('mc.bot.setting',   { paused: true  }); },
    resume:    function ()     { return Driver.invoke('mc.bot.setting',   { paused: false }); },
    // Baritone-style survival toggles — sugar over mc.bot.setting.
    autoEat:    function (on, opts) {
        var p = { autoEat: !!on };
        if (opts && opts.threshold !== undefined) p.autoEatFoodThreshold = opts.threshold | 0;
        return Driver.invoke('mc.bot.setting', p);
    },
    autoRespawn: function (on) { return Driver.invoke('mc.bot.setting', { autoRespawn: !!on }); },
    // Baritone BackfillProcess analogue — when on, the bot tracks the cells its
    // own digs break (never air it only walks through) and refills them with
    // `block` (default cobblestone) whenever it would otherwise be idle. Pass
    // {radius:N} to cap how far from the player the auto-fill considers
    // (default 6, range 1..16).
    autoBackfill: function (on, opts) {
        var p = { autoBackfill: !!on };
        if (opts && opts.block !== undefined)  p.autoBackfillBlock  = String(opts.block);
        if (opts && opts.radius !== undefined) p.autoBackfillRadius = opts.radius | 0;
        return Driver.invoke('mc.bot.setting', p);
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
