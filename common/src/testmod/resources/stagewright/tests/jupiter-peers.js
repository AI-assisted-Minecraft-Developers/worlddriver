// A scene with a peer: steps the peer runs with a t of its own, handles that call through to it, and failures there
// reported here. The steps are this file's, which the peer loads too.

const SCENE = "wd.peers";

step("peer.holding", (t, args) => {
    const bot = t.bot(args.name);
    return { name: bot.name, uuid: bot.uuid, count: bot.count(args.item) };
});

step("peer.placed", (t, args) => t.call("mc.world.block", { pos: args.pos }).type === args.block);

step("peer.fails", (t, args) => {
    throw new Error("the peer step failed on purpose: " + args.why);
});

test("a bot logs out and back in with what it carried", { scene: SCENE }, (t) => {
    // Not alice: the tests of a file run at once, and a name is one player.
    const carol = t.bot("carol");
    t.expect(carol.name).equals("carol");
    carol.give("minecraft:diamond", 2);
    carol.disconnect();
    carol.reconnect();
    t.expect(carol.count("minecraft:diamond")).equals(2);
});

test("a bot of the same name on the peer is the same player, with the peer's own save", { scene: SCENE }, (t) => {
    const alice = t.bot("alice");
    alice.give("minecraft:diamond", 2);
    alice.disconnect();
    const there = t.peer("b").step("peer.holding", { name: "alice", item: "minecraft:diamond" });
    t.expect(there.name).equals("alice");
    t.expect(there.uuid).equals(alice.uuid);
    t.expect(there.count).equals(0);
});

test("handles from the peer call through to it", { scene: SCENE }, (t) => {
    const b = t.peer("b");
    const bob = b.bot("bob");
    t.expect(bob.name).equals("bob");
    bob.give("minecraft:emerald", 3);
    t.expect(bob.count("minecraft:emerald")).equals(3);
    const plot = b.plot({ size: 4 });
    const at = plot.at(1, 1, 1);
    plot.place(1, 1, 1, "minecraft:stone");
    t.expect(b.call("mc.world.block", { pos: at }).type).equals("minecraft:stone");
    b.waitUntil("peer.placed", { pos: at, block: "minecraft:stone" }, { within: 20, as: "the stone stands on b" });
});

test(
    "the bot the peer leased is gone with the test that leased it",
    {
        scene: SCENE,
        dependsOn: "handles from the peer call through to it",
    },
    (t) => {
        // A name still on the server would refuse the join.
        t.expect(t.peer("b").bot("bob").name).equals("bob");
    }
);

test("a failure on the peer is reported here with the peer's log", { scene: SCENE }, (t) => {
    let caught = null;
    try {
        t.peer("b").step("peer.fails", { why: "to see it here" });
    } catch (e) {
        caught = String(e);
    }
    t.expect(caught !== null).equals(true);
    t.expect(caught.includes("on peer 'b'")).equals(true);
    t.expect(caught.includes("the peer step failed on purpose: to see it here")).equals(true);
    t.expect(caught.includes("the peer's log is")).equals(true);
});

test("a function cannot be sent to the peer", { scene: SCENE }, (t) => {
    let caught = null;
    try {
        t.peer("b").waitUntil(() => true, {}, {});
    } catch (e) {
        caught = String(e);
    }
    t.expect(caught !== null && caught.includes("name of a step")).equals(true);
});
