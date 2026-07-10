// Client-only — mc.bot.useItem now covers BOTH the bare right-click ("use the
// held item") and the right-click-on-block ("use the held item on a face")
// modes. Pass `pos` to switch into the second mode; the tool routes through
// MultiPlayerGameMode.useItem / useItemOn with a synthetic BlockHitResult.
// On dedicated server / GameTest CI the client classes aren't loaded; record
// a single skipped-PASS so headless runs stay green.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("12_use_item: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {

    AgentTest.run("12_use_item: useItem returns shape with empty hand (no crash)", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) {
            var res = Agent.invoke("mc.bot.useItem", {});
            t.assertEqual(res.ok, false, "useItem with no player must report ok:false");
            t.assertTrue(typeof res.error === "string", "must include error string");
            return;
        }
        var res = Agent.invoke("mc.bot.useItem", { hand: "main" });
        t.assertTrue(res.ok, "useItem must succeed when a player exists (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.hand, "main", "hand must round-trip");
        t.assertTrue(typeof res.result === "string", "result must be an InteractionResult name string");
        t.assertEqual(typeof res.consumed, "boolean", "consumed must be a boolean");
    });

    AgentTest.run("12_use_item: pos-mode rejects malformed pos", function(t) {
        var res = Agent.invoke("mc.bot.useItem", { pos: "not-a-pos" });
        t.assertEqual(res.ok, false, "string-pos must report ok:false");
    });

    AgentTest.run("12_use_item: pos-mode echoes effective face", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) {
            var res = Agent.invoke("mc.bot.useItem", { pos: { x: 0, y: 200, z: 0 }, face: "up" });
            t.assertTrue(res.ok === false || typeof res.error === "string" || res.ok === undefined,
                "no-player path must not pretend success");
            return;
        }
        var res = Agent.invoke("mc.bot.useItem", {
            pos:  { x: 0, y: 200, z: 0 },
            face: "up",
            lookAt: false
        });
        t.assertTrue(res.ok, "pos-mode against test arena stone must route (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.face, "up", "face must echo back as requested");
        t.assertEqual(res.hand, "main", "hand default must be main");
        t.assertEqual(res.pos.x, 0, "pos.x must round-trip");
        t.assertEqual(res.pos.y, 200, "pos.y must round-trip");
        t.assertEqual(res.pos.z, 0, "pos.z must round-trip");
        t.assertTrue(typeof res.result === "string", "result must be an InteractionResult name");
    });

    AgentTest.run("12_use_item: pos-mode auto-picks face when omitted", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) return;
        var res = Agent.invoke("mc.bot.useItem", {
            pos: { x: 0, y: 200, z: 0 },
            lookAt: false
        });
        t.assertTrue(res.ok, "pos-mode without explicit face must still route");
        t.assertTrue(typeof res.face === "string" && res.face.length > 0,
            "auto-picked face must be returned (got " + JSON.stringify(res) + ")");
        var allowed = ["up", "down", "north", "south", "east", "west"];
        t.assertTrue(allowed.indexOf(res.face) >= 0,
            "auto-picked face must be a cardinal direction, got '" + res.face + "'");
    });

    AgentTest.run("12_use_item: entity-mode rejects non-integer entityId", function(t) {
        var r = Agent.invoke("mc.bot.useItem", { entityId: "abc" });
        t.assertEqual(r.ok, false, "non-integer entityId must be ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("integer") >= 0,
            "error must mention integer (got " + JSON.stringify(r) + ")");
    });

    AgentTest.run("12_use_item: entity-mode rejects nonexistent entity id", function(t) {
        // 2^30 is well past any real entity id in a fresh world
        var r = Agent.invoke("mc.bot.useItem", { entityId: 1073741824 });
        t.assertEqual(r.ok, false, "nonexistent entity must be ok:false");
        t.assertTrue(typeof r.error === "string", "must include error string");
    });

    AgentTest.run("12_use_item: entity-mode mounts a boat with an empty hand", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) return;
        var me = Agent.invoke("mc.observe.player", {});
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        // Mounting needs an EMPTY main hand (a held saddle/item would saddle/feed
        // per the tool doc instead of mounting) — clear it before summoning.
        Agent.invoke("mc.action.runCommand", { cmd: "item replace entity @p weapon.mainhand with minecraft:air" });
        Agent.invoke("mc.action.runCommand",
            { cmd: "summon minecraft:boat " + x + " " + y + " " + z + " {Tags:[\"t12_boat\"]}" });
        try {
            // center is REQUIRED on mc.query — it defaults to world origin otherwise.
            var rows = Agent.invoke("mc.query", {
                q: "entities",
                center: { x: x, y: y, z: z },
                filter: { in_radius: 8, type: "boat" },
                select: ["id"]
            });
            t.assertTrue(Array.isArray(rows) && rows.length >= 1,
                "boat must be findable via mc.query, got " + JSON.stringify(rows));
            var res = Agent.invoke("mc.bot.useItem", { entityId: rows[0].id });
            t.assertEqual(res.ok, true, "useItem on boat must succeed (got " + JSON.stringify(res) + ")");
            t.assertEqual(res.riding, "minecraft:boat", "riding must report the mounted boat type");
        } finally {
            // This suite runs on a worker (RPC) thread, never the client thread, so
            // the post-interact poll in useItemOnEntity always runs and 'riding' above
            // reflects the server-applied mount — safe by construction. Guards a
            // future "move validation onto the client thread" refactor: that would
            // hit the new isSameThread() skip, and this assertion would need to wait
            // and re-check riding explicitly instead of trusting the immediate return.
            Agent.invoke("mc.action.runCommand", { cmd: "ride @p dismount" });
            Agent.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t12_boat]" });
        }
    });
}
