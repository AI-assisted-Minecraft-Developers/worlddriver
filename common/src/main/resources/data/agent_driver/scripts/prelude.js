// Prelude loaded into every mc.script.eval scope. Defines the global
// console, Agent.invoke (with Java-exception-prefix stripping) and the
// Agent.{system,observe,action,query,wait,client,bot} surface that wraps
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

var Agent = {};

// Strip "java.lang.IllegalArgumentException:" or "net.x.y.SomeException:" prefixes
// off Rhino-wrapped Java exception messages so JS users see "unknown block: foo"
// rather than "java.lang.IllegalArgumentException: unknown block: foo".
Agent.invoke = function (method, params) {
    try {
        var json = __api.invokeJson(method, JSON.stringify(params || {}));
        return JSON.parse(json);
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e);
        var m = /^([a-zA-Z_$][\w.$]*Exception|[a-zA-Z_$][\w.$]*Error):\s+(.*)$/.exec(msg);
        if (m && /\./.test(m[1])) msg = m[2];
        throw new Error(msg);
    }
};

Agent.system = {
    version:    function ()      { return Agent.invoke('mc.system.version',    {}); },
    testOrigin: function ()      { return Agent.invoke('mc.system.testOrigin', {}); },
    waitTicks:  function (ticks) { return Agent.invoke('mc.system.waitTicks', { ticks: ticks | 0 }); }
};

Agent.observe = {
    // Sugar over mc.query q='blocks' — returns {blocks:[...]} for the same
    // shape callers used before observe.area was merged into query.
    area: function (p) {
        var q = { q: 'blocks', filter: { in_radius: (p && p.radius) | 0 } };
        if (p && p.center) q.center = p.center;
        if (p && p.filter && p.filter.type) q.filter.type = p.filter.type;
        var rows = Agent.invoke('mc.query', q);
        return { blocks: rows || [] };
    },
    cursor:      function ()  { return Agent.invoke('mc.observe.cursor', {}); },
    eventsSince: function (c, opts) {
        var p = { cursor: c };
        if (opts && opts.types) p.types = opts.types;
        if (opts && opts.limit !== undefined) p.limit = opts.limit;
        return Agent.invoke('mc.observe.eventsSince', p);
    },
    player:    function (name) { return Agent.invoke('mc.observe.player', name ? { name: name } : {}); },
    container: function (pos)  { return Agent.invoke('mc.observe.container', { pos: pos }); },
    // Phase G boss sensing — nearest dragon/wither + End-crystal list.
    boss:      function (opts)  { return Agent.invoke('mc.observe.boss', opts || {}); },
    // ASCII spatial map — opts {plane:'xz'|'xy'|'zy', radius, height, center?}.
    // Top-down heightmap (xz) or vertical cross-section (xy/zy); mobs overlaid.
    map:       function (opts)  { return Agent.invoke('mc.observe.map', opts || {}); }
};

Agent.action = {
    placeBlock: function (p) {
        // Sugar over placeMany for the single-block case.
        var ev = (p && p.returnEvents) || undefined;
        var args = { blocks: [{ pos: p.pos, type: p.type }] };
        if (ev) args.returnEvents = true;
        return Agent.invoke('mc.action.placeMany', args);
    },
    placeMany:  function (blocks)         { return Agent.invoke('mc.action.placeMany', { blocks: blocks }); },
    fill:       function (from, to, type) { return Agent.invoke('mc.action.fill', { from: from, to: to, type: type }); },
    runCommand: function (c)              { return Agent.invoke('mc.action.runCommand', { cmd: c }); }
};

Agent.query = function (p) { return Agent.invoke('mc.query', p); };

// Phase H — goal-directed acquisition planner: "I want X" → ordered mine/farm/
// smelt/craft steps (recipe.resolve + how to get every missing leaf).
Agent.plan = {
    acquire: function (opts) { return Agent.invoke('mc.plan.acquire', opts || {}); }
};

// Phase H — persistent skill library (Voyager). Write a JS skill once, reuse it
// by name across sessions. The skill reads its call args from a SKILL global.
Agent.skill = {
    save:   function (name, source) { return Agent.invoke('mc.skill', { op: 'save', name: name, source: source }); },
    list:   function ()             { return Agent.invoke('mc.skill', { op: 'list' }); },
    get:    function (name)         { return Agent.invoke('mc.skill', { op: 'get', name: name }); },
    run:    function (name, args)   { return Agent.invoke('mc.skill', { op: 'run', name: name, args: args || {} }); },
    remove: function (name)         { return Agent.invoke('mc.skill', { op: 'delete', name: name }); }
};

// Driver→agent event channel (server-side surface). The live push rides the
// transports (subscribe over WebSocket / the MCP SSE stream at /mcp/events);
// these helpers cover injecting custom events and registering condition watchers.
Agent.events = {
    // Inject a custom event into the stream (the manual "自定义条件满足" path).
    emit:    function (type, data, pos) {
        var p = { op: 'emit', type: type };
        if (data !== undefined) p.data = data;
        if (pos !== undefined) p.pos = pos;
        return Agent.invoke('mc.events', p);
    },
    // Register a rising-edge watcher. opts: {invoke, params?, field?, value?|above?|
    // below?, emitAs?, everyMs?, once?}. First poll where the predicate flips
    // false→true emits emitAs (default 'condition.met') into the stream.
    watch:   function (opts) {
        var p = Object.assign({ op: 'watch' }, opts || {});
        return Agent.invoke('mc.events', p);
    },
    unwatch: function (id) { return Agent.invoke('mc.events', { op: 'unwatch', id: id }); },
    list:    function ()   { return Agent.invoke('mc.events', { op: 'list' }); }
};

// Wait helpers — convenience wrappers; full options accepted on the opts object.
Agent.wait = {
    worldReady: function (opts) { return Agent.invoke('mc.wait.worldReady', opts || {}); },
    event:      function (cursor, opts) {
        var p = Object.assign({ cursor: cursor | 0 }, opts || {});
        return Agent.invoke('mc.wait.event', p);
    },
    condition:  function (opts) { return Agent.invoke('mc.wait.condition', opts || {}); }
};

// Client helpers — symmetric with system/observe/action. On dedicated server
// these all error with 'mc.client.* not available', which the caller's
// try/catch can handle.
Agent.client = {
    screen: {
        info:          function () { return Agent.invoke('mc.client.screen.info', {}); },
        tree:          function () { return Agent.invoke('mc.client.screen.tree', {}); },
        // Inventory / pause are reachable by synthesizing the bound key — same
        // path the player takes — so no dedicated open* tool is needed.
        openInventory: function () { return Agent.invoke('mc.client.input.key', { key: 'E' }); },
        openPause:     function () { return Agent.invoke('mc.client.input.key', { key: 'ESCAPE' }); },
        close:         function () { return Agent.invoke('mc.client.screen.close', {}); }
    },
    input: {
        click:     function (x, y, button) { return Agent.invoke('mc.client.input.click', { x: x, y: y, button: button | 0 }); },
        mouseMove: function (x, y)         { return Agent.invoke('mc.client.input.mouseMove', { x: x, y: y }); }
    },
    chat: {
        send:    function (text, awaitReplyMs) {
            var p = { text: text };
            if (awaitReplyMs !== undefined) p.awaitReplyMs = awaitReplyMs | 0;
            return Agent.invoke('mc.client.chat.send', p);
        },
        history: function (opts) { return Agent.invoke('mc.client.chat.history', opts || {}); }
    },
    overlays:   function (opts) { return Agent.invoke('mc.client.overlays',  opts || {}); },
    screenshot: function (opts) { return Agent.invoke('mc.client.screenshot', opts || {}); },
    // Client-AUTHORITATIVE reads — always the LocalPlayer / ClientLevel, even
    // when a server is attached (unlike Agent.observe.player / Agent.query which
    // prefer the server). player() adds pose, eyePos, isInWall/inWater, and the
    // eye/feet block from the ClientLevel — diff vs Agent.observe.player() to
    // spot a client/server desync. blocks({center,filter}) scans ClientLevel.
    player:     function ()     { return Agent.invoke('mc.client.player', {}); },
    blocks:     function (opts) { return Agent.invoke('mc.client.blocks', opts || {}); }
};

Agent.bot = {
    goto:      function (opts) { return Agent.invoke('mc.bot.goto',      opts || {}); },
    mine:      function (opts) { return Agent.invoke('mc.bot.mine',      opts || {}); },
    clearArea: function (opts) { return Agent.invoke('mc.bot.clearArea', opts || {}); },
    farm:      function (opts) { return Agent.invoke('mc.bot.farm',      opts || {}); },
    sleep:     function (opts) { return Agent.invoke('mc.bot.sleep',     opts || {}); },
    construct: function (opts) { return Agent.invoke('mc.bot.construct', opts || {}); },
    // Convenience aliases — Baritone calls these `pillar`/`tower` and `bridge`.
    tower:     function (opts) {
        var p = Object.assign({}, opts || {}, { mode: 'tower' });
        return Agent.invoke('mc.bot.construct', p);
    },
    bridge:    function (opts) {
        var p = Object.assign({}, opts || {}, { mode: 'bridge' });
        return Agent.invoke('mc.bot.construct', p);
    },
    // Baritone tunnel — clears a 1×2 (or wider/taller) corridor in a direction.
    // Pure prelude sugar over mc.bot.clearArea: reads player pos, computes the
    // corridor bbox, dispatches clearArea. opts:
    //   direction:  'forward'|'back'|'left'|'right'|'north'|'south'|'east'|'west'|'up'|'down'
    //   distance:   1..64 (number of cells in the travel direction)
    //   width:      1..8  default 1 — corridor width perpendicular to direction
    //   height:     1..8  default 2 — corridor height (Y)
    //   fill:       optional block id to backfill (otherwise just clear)
    tunnel: function (opts) {
        opts = opts || {};
        var dir = String(opts.direction || 'forward');
        var dist = Math.max(1, Math.min(64, (opts.distance | 0) || 0));
        if (!dist) return { ok: false, error: 'distance required (1..64)' };
        var width = Math.max(1, Math.min(8, (opts.width | 0) || 1));
        var height = Math.max(1, Math.min(8, (opts.height | 0) || 2));
        var pl = Agent.invoke('mc.observe.player', {});
        if (!pl || !pl.pos) return { ok: false, error: 'no player' };
        var px = Math.floor(pl.pos.x), py = Math.floor(pl.pos.y), pz = Math.floor(pl.pos.z);
        // Resolve direction → unit forward (fx,fz/fy) + right (rx,rz).
        // 'forward'/'back'/'left'/'right' need player yaw; absolute compass dirs don't.
        var fx = 0, fy = 0, fz = 0, rx = 0, rz = 0;
        function abs(d) {
            switch (d) {
                case 'north': fx = 0;  fz = -1; rx = -1; rz = 0;  break;
                case 'south': fx = 0;  fz = 1;  rx = 1;  rz = 0;  break;
                case 'east':  fx = 1;  fz = 0;  rx = 0;  rz = -1; break;
                case 'west':  fx = -1; fz = 0;  rx = 0;  rz = 1;  break;
                case 'up':    fy = 1;  rx = 1;  rz = 0;  break;
                case 'down':  fy = -1; rx = 1;  rz = 0;  break;
                default: return false;
            }
            return true;
        }
        if (!abs(dir)) {
            // Player-relative: snap yaw to nearest cardinal.
            var yaw = ((pl.look && pl.look.yaw) || 0) % 360;
            if (yaw < 0) yaw += 360;
            // 0=south, 90=west, 180=north, 270=east in MC yaw convention.
            var face;
            if (yaw >= 315 || yaw < 45) face = 'south';
            else if (yaw < 135)         face = 'west';
            else if (yaw < 225)         face = 'north';
            else                        face = 'east';
            if (dir === 'forward') abs(face);
            else if (dir === 'back') {
                var back = { south:'north', north:'south', east:'west', west:'east' }[face];
                abs(back);
            }
            else if (dir === 'right') {
                var right = { south:'west', west:'north', north:'east', east:'south' }[face];
                abs(right);
            }
            else if (dir === 'left') {
                var left = { south:'east', east:'north', north:'west', west:'south' }[face];
                abs(left);
            }
            else return { ok: false, error: 'unknown direction: ' + dir };
        }
        // Travel axis: from player+1step to player+dist steps (don't dig the
        // cell the player is standing in — that'd suffocate or knock them).
        var ax = px + fx, ay = py + fy, az = pz + fz;
        var bx = px + fx * dist, by = py + fy * dist, bz = pz + fz * dist;
        // Perpendicular spread (width) — centered on travel axis. odd widths
        // straddle; even widths bias right.
        var halfL = Math.floor((width - 1) / 2);
        var halfR = Math.ceil((width - 1) / 2);
        var x0 = Math.min(ax, bx) - rx * halfL, x1 = Math.max(ax, bx) + rx * halfR;
        var z0 = Math.min(az, bz) - rz * halfL, z1 = Math.max(az, bz) + rz * halfR;
        if (x0 > x1) { var t = x0; x0 = x1; x1 = t; }
        if (z0 > z1) { var t2 = z0; z0 = z1; z1 = t2; }
        // Height: from feet Y up to feet Y + (height-1).
        var y0 = Math.min(ay, by), y1 = Math.max(ay, by) + (height - 1);
        var args = { from: { x: x0, y: y0, z: z0 }, to: { x: x1, y: y1, z: z1 } };
        if (opts.fill) args.fill = opts.fill;
        if (opts.awaitMs !== undefined) args.awaitMs = opts.awaitMs;
        return Agent.invoke('mc.bot.clearArea', args);
    },
    build:     function (opts) { return Agent.invoke('mc.bot.build',     opts || {}); },
    follow:    function (opts) { return Agent.invoke('mc.bot.follow',    opts || {}); },
    explore:   function (opts) { return Agent.invoke('mc.bot.explore',   opts || {}); },
    runAway:   function (opts) { return Agent.invoke('mc.bot.runAway',   opts || {}); },
    lookAt:    function (opts) { return Agent.invoke('mc.bot.lookAt',    opts || {}); },
    // Pass key/value pairs to write tuning settings; pass {} (or no arg) to read.
    setting:   function (opts) { return Agent.invoke('mc.bot.setting',   opts || {}); },
    status:    function ()     { return Agent.invoke('mc.bot.status',    {}); },
    cancel:    function (opts) { return Agent.invoke('mc.bot.cancel',    opts || {}); },
    // Phase G boss playbooks — {name:'dragon'|'wither'} to start (async, background),
    // {op:'status'} to poll, {op:'cancel'} to stop.
    playbook:  function (opts) { return Agent.invoke('mc.bot.playbook',  opts || {}); },
    pause:     function ()     { return Agent.invoke('mc.bot.setting',   { paused: true  }); },
    resume:    function ()     { return Agent.invoke('mc.bot.setting',   { paused: false }); },
    // Baritone-style survival toggles — sugar over mc.bot.setting.
    autoEat:    function (on, opts) {
        var p = { autoEat: !!on };
        if (opts && opts.threshold !== undefined) p.autoEatFoodThreshold = opts.threshold | 0;
        return Agent.invoke('mc.bot.setting', p);
    },
    autoRespawn: function (on) { return Agent.invoke('mc.bot.setting', { autoRespawn: !!on }); },
    // Baritone BackfillProcess analogue — when on, the bot tracks cells it walks
    // through and auto-fills them with `block` (default cobblestone) whenever
    // it would otherwise be idle. Pass {radius:N} to cap how far from the
    // player the auto-fill considers (default 6, range 1..16).
    autoBackfill: function (on, opts) {
        var p = { autoBackfill: !!on };
        if (opts && opts.block !== undefined)  p.autoBackfillBlock  = String(opts.block);
        if (opts && opts.radius !== undefined) p.autoBackfillRadius = opts.radius | 0;
        return Agent.invoke('mc.bot.setting', p);
    },
    // Baritone-style waypoints — see mc.bot.waypoint for op semantics.
    waypoint:   function (opts) { return Agent.invoke('mc.bot.waypoint', opts || {}); },
    saveWaypoint:   function (name, pos) { return Agent.invoke('mc.bot.waypoint', { op: 'save', name: name, pos: pos }); },
    gotoWaypoint:   function (name, opts) {
        var p = Object.assign({ waypoint: name }, opts || {});
        return Agent.invoke('mc.bot.goto', p);
    },
    listWaypoints:  function () { return Agent.invoke('mc.bot.waypoint', { op: 'list' }); },
    deleteWaypoint: function (name) { return Agent.invoke('mc.bot.waypoint', { op: 'delete', name: name }); },
    waitArrive: function (timeoutMs) {
        return Agent.invoke('mc.wait.condition', {
            invoke: 'mc.bot.status', params: {},
            field: 'goto.active', value: false,
            timeoutMs: timeoutMs || 60000, pollMs: 200
        });
    },
    waitMine: function (timeoutMs) {
        return Agent.invoke('mc.wait.condition', {
            invoke: 'mc.bot.status', params: {},
            field: 'mine.active', value: false,
            timeoutMs: timeoutMs || 120000, pollMs: 300
        });
    },
    waitBuild: function (timeoutMs) {
        return Agent.invoke('mc.wait.condition', {
            invoke: 'mc.bot.status', params: {},
            field: 'builder.active', value: false,
            timeoutMs: timeoutMs || 300000, pollMs: 500
        });
    }
};
