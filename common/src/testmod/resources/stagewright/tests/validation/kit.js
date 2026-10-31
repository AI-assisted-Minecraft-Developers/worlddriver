// The validation scripts' kit. Each NN_*.js beside this file is one test that hands its checks to the step
// 'wd.validation' as a function of `ScriptTest`, and the step runs them as the scripts were written to run:
//
//   * ScriptTest.run(name, body) runs body(t) at once, and a check that throws fails ALONE, as a soft check reported
//     when the test ends, the checks after it still running. t holds assertTrue, assertFalse, assertEqual and
//     assertHasKey.
//   * ScriptTest.rpcRoundtrip / mcpRoundtrip make the same call over the WebSocket RPC and MCP servers and answer as
//     Driver.invoke does, throwing the caller's own DriverApiError: the transport-parity checks compare those
//     answers with Driver.invoke's (in-JVM) byte for byte.
//   * Before the checks the arena at mc.system.testOrigin is seeded afresh and the player, if any, stood on it, so no
//     script depends on what another left behind or on the order they run in; after them the bot is stood down.
//   * A client-face script takes its real branch only where the server's registry answers mc.client.*, and runs no
//     checks elsewhere.
//
// Every test is isolated: they share the arena and the player.

function message(e) {
    if (e === null || e === undefined) return String(e);
    if (e.name === "DriverApiError") return "DriverApiError " + e.code + ": " + e.message;
    return e.message !== undefined ? String(e.message) : String(e);
}

// The old ScriptTest's t: Java's TestContext, whose numbers compare by value and everything else by equals.
function asserts(name) {
    function fail(what) {
        throw new Error("[" + name + "] " + what);
    }
    function same(a, b) {
        if (typeof a === "number" && typeof b === "number") return a === b;
        return a === null || a === undefined ? b === a : a === b;
    }
    return {
        assertTrue: (cond, msg) => {
            if (cond !== true)
                fail(
                    (msg === undefined ? "expected true" : msg) +
                        (typeof cond === "boolean" ? "" : " (not a boolean: " + cond + ")")
                );
        },
        assertFalse: (cond, msg) => {
            if (cond !== false)
                fail(
                    (msg === undefined ? "expected false" : msg) +
                        (typeof cond === "boolean" ? "" : " (not a boolean: " + cond + ")")
                );
        },
        assertEqual: (a, b, msg) => {
            if (!same(a, b)) fail("not equal" + (msg ? ": " + msg : "") + "\n  expected: " + a + "\n  actual:   " + b);
        },
        assertHasKey: (obj, key) => {
            if (obj === null || typeof obj !== "object" || Array.isArray(obj)) fail("not a Map: " + obj);
            if (!Object.prototype.hasOwnProperty.call(obj, key))
                fail("missing key '" + key + "' in " + Object.keys(obj));
        },
    };
}

step("wd.validation", (t, given) => {
    t.step("wd.validation.arena");
    const dedicated = t.step("wd.validation.dedicated");
    const checks = [];
    function bridge(stepName, method, params) {
        const reply = JSON.parse(
            String(
                t.step(stepName, {
                    method: method,
                    params: params === undefined ? null : JSON.stringify(params),
                })
            )
        );
        if (reply.error) throw new given.DriverApiError(reply.error);
        return reply.result;
    }
    const ScriptTest = {
        run: (name, body) => {
            const t0 = Date.now();
            let error = null;
            try {
                body(asserts(name));
            } catch (e) {
                error = message(e);
            }
            checks.push({ name: name, passed: error === null, ms: Date.now() - t0, error: error });
            t.check(error === null, name + (error === null ? "" : ": " + error));
        },
        rpcRoundtrip: (method, params) => bridge("wd.validation.rpc", method, params),
        mcpRoundtrip: (method, params) => bridge("wd.validation.mcp", method, params),
        record: (key, value) => t.record(key, value),
    };
    try {
        given.body(ScriptTest);
    } finally {
        t.step("wd.validation.end", { script: given.script, checks: checks });
    }
    t.record("checks", checks.length + " on the " + (dedicated ? "dedicated" : "integrated") + " server");
});

// Also what puts this file, and so the step above, in the scenes' games: a file with no test there is not loaded.
test(
    "the arena the validation scripts are written against seeds with its props",
    {
        scene: ["wd.validation", "wd.validationIntegrated"],
        isolated: true,
    },
    (t) => {
        t.step("wd.validation.arena");
    }
);
