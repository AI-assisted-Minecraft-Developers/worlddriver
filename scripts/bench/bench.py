#!/usr/bin/env python3
"""bench.py — run a course with the bot or a human at the keyboard, and judge the bot against the human.

A course is a JSON file under courses/: a start, ordered checkpoints, a goal, and how its terrain is
put back; or, with "kind": "combat", an arena and a wave of mobs that appears on Go! and has to be
killed. The wave is either fixed or drawn from a pool by a seed. With "kind": "mine", a block of stone with ores in
it, and a number of ores to collect from it. Both drivers run the same course in the
same client, with the same kit, markers and clock, so their recordings compare directly.
docs/dev/benchmark.md has the method and the pass criterion.

  setup   COURSE                      Build a course from the commands in its file.
  markers COURSE --place | --remove   Beacons with coloured beams on the checkpoints and the goal.
  terrain COURSE --capture | --reset  Snapshot the course's terrain, or put it back after a run.
  run     COURSE --driver bot|human   Countdown, run, record one JSON line per sample. [--seed N]
  report  COURSE [--png]              Every recorded run, the errors in each, and the verdict.

Recordings go to fabric/run/bench/<course>/, next to the client's own output, unless --out names
another place.
"""
import argparse, asyncio, json, math, os, random, statistics, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".agents", "skills", "worlddriver-rpc"))
import rpc  # noqa: E402  resolve_host / resolve_port / call

import websockets  # noqa: E402

HZ = 20
OUT = os.path.join(HERE, "..", "..", "fabric", "run", "bench")
KIT = ("clear @s", "give @s minecraft:iron_pickaxe", "give @s minecraft:iron_shovel",
       "give @s minecraft:iron_axe", "give @s minecraft:dirt 64", "give @s minecraft:cobblestone 64",
       "give @s minecraft:cooked_beef 16", "give @s minecraft:water_bucket")
# Bot reflexes that move the player on their own; off for a human run.
HUMAN_OFF = {"autoFloatWhenDrowning": False, "autoDrownEscape": False}
# The glass under a marker's feet cell tints its beam: the checkpoint to reach next is red.
NEXT, LATER, DONE, GOAL = "red_stained_glass", "white_stained_glass", "lime_stained_glass", "yellow_stained_glass"
BEAM_CLEAR = 48          # cells above a marker cleared of beam-blocking blocks
MAX_TILE = 64            # mc.world.snapshot's edge limit
# `as` alone leaves the command at the server's origin; the sound has to play where the player stands.
SOUND = "execute at @s run playsound minecraft:"
STALL_SPEED, STALL_S = 1.0, 0.5
LADDER_S, LADDER_RISE = 0.4, 0.8   # a contact this long that rose this far without dipping is a ladder climb
CLIMB_HALF_S = 0.4     # blocks of height in each half second that make a sideways-still body a climbing one
BACKTRACK = 2.0
GRAZE_S, GRAZE_CLIMB_S, GRAZE_RISE = 0.2, 1.0, 0.9
# A combat course: the wave is tagged so that the end condition and the cleanup touch only it.
MOB_TAG = "bench_mob"
COMBAT_KIT = ("clear @s", "item replace entity @s hotbar.0 with minecraft:iron_sword",
              "item replace entity @s hotbar.1 with minecraft:cooked_beef 16",
              "item replace entity @s weapon.offhand with minecraft:shield",
              "item replace entity @s armor.head with minecraft:iron_helmet",
              "item replace entity @s armor.chest with minecraft:iron_chestplate",
              "item replace entity @s armor.legs with minecraft:iron_leggings",
              "item replace entity @s armor.feet with minecraft:iron_boots")
FIGHT_REACH = 4.0      # a slow spell with a mob this close is fighting in place, not a stall
WORK_RANGE = 32.0      # a random wave is cleared when no mob is left this close: the bot's threat scan radius
REISSUE_MAX = 5        # combat orders re-sent while tagged mobs remain, before the run gives up
DEATH_ANIM_S = 1.5     # a mob drops its XP when its death animation ends, about a second after it dies


class Rpc:
    def __init__(self, ws):
        self.ws, self.rid, self.me = ws, 0, None

    async def call(self, method, params=None, timeout=30):
        self.rid += 1
        ok, res = await rpc.call(self.ws, self.rid, method, params or {}, timeout)
        if not ok:
            raise RuntimeError(f"{method}: {res}")
        return res

    async def cmd(self, c, as_me=True):
        """A vanilla command, as this client's own player so that @s is it."""
        p = {"cmd": c}
        if as_me:
            if self.me is None:
                self.me = "player:" + (await self.call("mc.observe.player"))["player"]["name"]
            p["as"] = self.me
        return await self.call("mc.action.runCommand", p)


async def connect():
    uri = f"ws://{rpc.resolve_host(None)}:{rpc.resolve_port(None)}/rpc"
    return await websockets.connect(uri, max_size=16 * 1024 * 1024, ping_interval=None)


def read_course(path):
    """A course file, laid over the course it names as its base: a variant states only what it changes."""
    if not os.path.exists(path):
        path = os.path.join(HERE, "courses", path + ".json")
    with open(path) as f:
        c = json.load(f)
    if "base" in c:
        c = {**read_course(c.pop("base")), **c}
    return c


def load_course(path):
    c = read_course(path)
    c.setdefault("checkpoints", [])
    c.setdefault("beacons", [])
    c.setdefault("maxSeconds", 900)
    c.setdefault("kind", "route")
    return c


def is_combat(course):
    return course["kind"] == "combat"


def is_mine(course):
    return course["kind"] == "mine"


def in_box(pos, box):
    (x0, y0, z0), (x1, y1, z1) = box
    return (min(x0, x1) <= pos["x"] <= max(x0, x1) and min(y0, y1) <= pos["y"] <= max(y0, y1)
            and min(z0, z1) <= pos["z"] <= max(z0, z1))


def ores_held(p, items):
    """How many of a mining course's ore drops the player carries."""
    held = (p.get("inventory") or {}).get("items") or {}
    return sum(held.get(i, 0) for i in items)


def course_dir(args, course):
    d = os.path.join(args.out, course["id"])
    os.makedirs(os.path.join(d, "runs"), exist_ok=True)
    return d


def bp(p):
    return {"x": int(math.floor(p[0])), "y": int(math.floor(p[1])), "z": int(math.floor(p[2]))}


def state_string(reading):
    s = reading.get("state")
    return reading["type"] + ("[" + ",".join(f"{k}={v}" for k, v in s.items()) + "]" if s else "")


def marker_points(course):
    """Every marker as (feet position, role): checkpoints in order, then the goal, then guide beacons."""
    pts = [(cp["pos"], "checkpoint") for cp in course["checkpoints"]]
    pts.append((course["goal"]["pos"], "goal"))
    pts += [(b, "guide") for b in course["beacons"]]
    return pts


def glass_for(i, n_cp, reached, role):
    """The tint of marker i when `reached` checkpoints are behind the driver."""
    if role == "guide":
        return LATER
    if i < reached:
        return DONE
    if i == reached:
        return NEXT
    return GOAL if role == "goal" else LATER


# ---------------------------------------------------------------- markers

async def markers(args):
    course = load_course(args.course)
    if course["kind"] != "route":
        print(f"[markers] a {course['kind']} course has no checkpoints to mark")
        return
    state = os.path.join(course_dir(args, course), "markers.json")
    async with await connect() as ws:
        r = Rpc(ws)
        if args.remove:
            if not os.path.exists(state):
                print("[markers] none placed")
                return
            saved = json.load(open(state))
            for cell in reversed(saved):
                p = cell["pos"]
                await r.cmd(f"setblock {p['x']} {p['y']} {p['z']} {cell['block']}", as_me=False)
            os.remove(state)
            print(f"[markers] removed, {len(saved)} cells put back")
            return
        if os.path.exists(state):
            print("[markers] already placed; --remove first")
            return
        saved, n_cp = [], len(course["checkpoints"])
        for i, (pos, role) in enumerate(marker_points(course)):
            x, y, z = (int(math.floor(v)) for v in pos)
            cells = [(x + dx, y - 3, z + dz, "iron_block") for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
            cells += [(x, y - 2, z, "beacon"), (x, y - 1, z, glass_for(i, n_cp, 0, role))]
            for dy in range(0, BEAM_CLEAR):
                b = await r.call("mc.world.block", {"pos": {"x": x, "y": y + dy, "z": z}})
                t = b["type"]
                if t == "minecraft:air" or "leaves" in t or "glass" in t:
                    continue
                if dy < 2:
                    print(f"[markers] warning: {t} in the walking cells at {x},{y + dy},{z}; pick a clear cell")
                cells.append((x, y + dy, z, "glass"))
            for cx, cy, cz, block in cells:
                b = await r.call("mc.world.block", {"pos": {"x": cx, "y": cy, "z": cz}})
                saved.append({"pos": {"x": cx, "y": cy, "z": cz}, "block": state_string(b)})
                await r.cmd(f"setblock {cx} {cy} {cz} minecraft:{block}", as_me=False)
        json.dump(saved, open(state, "w"))
        print(f"[markers] {len(marker_points(course))} beacons placed, {len(saved)} cells changed")


async def tint(r, course, reached):
    n_cp = len(course["checkpoints"])
    for i, (pos, role) in enumerate(marker_points(course)):
        if role == "guide":
            continue
        x, y, z = (int(math.floor(v)) for v in pos)
        await r.cmd(f"setblock {x} {y - 1} {z} minecraft:{glass_for(i, n_cp, reached, role)}", as_me=False)


async def compass_to(r, pos):
    x, y, z = (int(math.floor(v)) for v in pos)
    await r.cmd("item replace entity @s weapon.offhand with minecraft:compass[minecraft:lodestone_tracker="
                f"{{target:{{pos:[I;{x},{y},{z}],dimension:\"minecraft:overworld\"}},tracked:false}}]")


# ---------------------------------------------------------------- setup

async def setup(args):
    """Build a course from its `setup` commands, standing at its start so that its chunks are loaded."""
    course = load_course(args.course)
    if not course.get("setup"):
        print("[setup] this course has no setup commands")
        return
    async with await connect() as ws:
        r = Rpc(ws)
        sx, sy, sz = course["start"]
        await r.cmd(f"tp @s {sx} {sy} {sz} {course.get('yaw', 0)} 0")
        await asyncio.sleep(3.0)
        for c in course["setup"]:
            res = await r.cmd(c, as_me=False)
            if not res.get("success", True):
                # A fill over blocks that are already the target block reports failure too.
                print(f"[setup] no change: {c}: {res.get('feedback')}")
        print(f"[setup] built, {len(course['setup'])} commands")


# ---------------------------------------------------------------- terrain

def tiles(box):
    (x0, y0, z0), (x1, y1, z1) = box
    for x in range(min(x0, x1), max(x0, x1) + 1, MAX_TILE):
        for y in range(min(y0, y1), max(y0, y1) + 1, MAX_TILE):
            for z in range(min(z0, z1), max(z0, z1) + 1, MAX_TILE):
                yield ((x, y, z), (min(x + MAX_TILE - 1, max(x0, x1)), min(y + MAX_TILE - 1, max(y0, y1)),
                                   min(z + MAX_TILE - 1, max(z0, z1))))


async def terrain(args):
    course = load_course(args.course)
    t = course.get("terrain", {})
    async with await connect() as ws:
        r = Rpc(ws)
        if t.get("kind") == "build":
            # Without its own commands, a build rebuilds the course from its setup. The player goes back to the start
            # first: a run can end inside what the rebuild fills, and the fill would bury them there.
            commands = t.get("commands", course.get("setup", []))
            sx, sy, sz = course["start"]
            await r.cmd(f"tp @s {sx} {sy} {sz} {course.get('yaw', 0)} 0")
            for c in commands:
                await r.cmd(c, as_me=False)
            print(f"[terrain] rebuilt, {len(commands)} commands")
            return
        if t.get("kind") != "snapshot":
            print("[terrain] this course has no terrain reset")
            return
        boxes = list(tiles(t["box"]))
        t0 = time.monotonic()
        for i, (a, b) in enumerate(boxes):
            sid = f"bench-{course['id']}-{i}"
            if args.capture:
                await r.call("mc.world.snapshot", {"id": sid, "from": dict(zip("xyz", a)), "to": dict(zip("xyz", b)),
                                                   "blockEntities": True, "entities": True}, timeout=120)
            else:
                await r.call("mc.world.restore", {"id": sid}, timeout=120)
        verb = "captured" if args.capture else "restored"
        print(f"[terrain] {verb} {len(boxes)} tiles in {time.monotonic() - t0:.1f}s")


# ---------------------------------------------------------------- run

def goal_dist(course, x, y, z):
    g = course["goal"]
    gx, gy, gz = g["pos"]
    d = math.hypot(x - gx, z - gz)
    return d if g.get("column") else math.hypot(d, y - gy)


def arena_selector(course):
    (x0, y0, z0), (x1, y1, z1) = course["arena"]
    return (f"x={min(x0, x1)},y={min(y0, y1)},z={min(z0, z1)},"
            f"dx={abs(x1 - x0)},dy={abs(y1 - y0)},dz={abs(z1 - z0)}")


async def clear_arena(r, course):
    """No wave left over from an earlier run, none of its drops, XP or arrows, and no other mob in the arena: a cave
    can hold a bat or a mob that spawned before spawning was turned off."""
    await r.cmd(f"kill @e[tag={MOB_TAG}]", as_me=False)
    await r.cmd(f"kill @e[type=!minecraft:player,{arena_selector(course)}]", as_me=False)
    await asyncio.sleep(DEATH_ANIM_S)
    for kind in ("item", "experience_orb", "arrow"):
        await r.cmd(f"kill @e[type=minecraft:{kind},{arena_selector(course)}]", as_me=False)


def draw_wave(course, seed):
    """The mobs of one run: a course's fixed `mobs`, or a wave drawn from its `wave` pool by `seed`.

    The draw depends on the seed and the course file alone, so a seed gives the same wave in any session, to either
    driver, and on every variant of a course that shares the pool: first the count, then the kinds by weight, then a
    shuffle of the floor cells taken greedily while each is `spacing` from the ones already taken. A floor cell is
    eligible when its walking distance from the start, listed with it, is within minDistance..maxDistance and it is at
    least minDistance from the start in a straight line, so that a mob across a thin wall is not on top of the start.
    """
    if "wave" not in course:
        return course["mobs"]
    w = course["wave"]
    rng = random.Random(seed)
    n = rng.randint(*w["count"])
    kinds = rng.choices(w["pool"], weights=[k["weight"] for k in w["pool"]], k=n)
    lo, hi = w["minDistance"], w["maxDistance"]
    start = course["start"]
    cells = [c for c in w["cells"] if lo <= c[3] <= hi and math.dist((c[0] + 0.5, c[1], c[2] + 0.5), start) >= lo]
    rng.shuffle(cells)
    taken = []
    for c in cells:
        if all(math.dist(c[:3], t[:3]) >= w["spacing"] for t in taken):
            taken.append(c)
            if len(taken) == n:
                break
    if len(taken) < n:
        raise RuntimeError(f"only {len(taken)} floor cells {w['spacing']} apart for a wave of {n}")
    mobs = []
    for k, (x, y, z, _) in zip(kinds, taken):
        m = {"type": k["type"], "pos": [x + 0.5, y, z + 0.5]}
        if k.get("nbt"):
            m["nbt"] = k["nbt"]
        mobs.append(m)
    return mobs


def next_seed(d, driver):
    """One more than the recordings this driver already has of the course: bot run i and human run i meet the same
    wave."""
    runs = os.path.join(d, "runs")
    n = 0
    for f in os.listdir(runs):
        if f.endswith(".jsonl"):
            with open(os.path.join(runs, f)) as fh:
                n += json.loads(fh.readline() or "{}").get("driver") == driver
    return n + 1


async def spawn_wave(r, mobs):
    for m in mobs:
        x, y, z = m["pos"]
        extra = "," + m["nbt"] if m.get("nbt") else ""
        await r.cmd(f"summon {m['type']} {x} {y} {z} {{Tags:[\"{MOB_TAG}\"],PersistenceRequired:1b{extra}}}",
                    as_me=False)


async def clear_drops(r, course):
    """No drop or XP left in the arena from an earlier run: a lying ore drop would be a free ore."""
    for kind in ("item", "experience_orb"):
        await r.cmd(f"kill @e[type=minecraft:{kind},{arena_selector(course)}]", as_me=False)


async def order_mine(r, course):
    m = course["mine"]
    res = await r.call("mc.bot.mine", {"blocks": m["blocks"], "quantity": m["count"],
                                       "radius": m.get("radius", 16)})
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"mine refused: {res}")


async def mobs_alive(r, within=None):
    """Living tagged mobs, anywhere or within `within` blocks of the player."""
    # A killed mob stays in the level through its death animation; DeathTime turns non-zero the tick it dies.
    sel = f"@e[tag={MOB_TAG},nbt={{DeathTime:0s}}" + (f",distance=..{within}]" if within else "]")
    res = await (r.cmd(f"execute at @s if entity {sel}") if within else
                 r.cmd(f"execute if entity {sel}", as_me=False))
    if not res.get("ok", True) or res.get("error"):
        raise RuntimeError(f"counting the wave failed: {res}")   # a zero from a broken count would end the run
    return int(res.get("value") or 0)


async def order_combat(r, course):
    res = await r.call("mc.bot.combat", {"mode": "engage", **course.get("botCombat", {})})
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"combat refused: {res}")
    return res["action"]["actionId"]


async def combat_ended(r, action_id):
    """How the combat order ended, or None while it runs. A fight runs as a reflex chain, so mc.bot.status never
    reports it busy; the action's own state is what says it ended."""
    a = (await r.call("mc.wait.result", {"kind": "action", "actionId": action_id}))["action"]
    if a["state"] == "running":
        return None
    return a["state"] + "".join(f" {a[k]}" for k in ("reason", "message") if a.get(k))


async def ready(r):
    """Hold the countdown until the person at the keyboard sneaks once: a key that does not move them."""
    await r.cmd("title @s times 0 40 0")
    while True:
        await r.cmd("title @s title {\"text\":\"Press sneak when ready\",\"color\":\"yellow\"}")
        end = time.monotonic() + 1.5
        while time.monotonic() < end:
            if (await r.call("mc.client.player")).get("body", {}).get("crouching"):
                while (await r.call("mc.client.player")).get("body", {}).get("crouching"):
                    await asyncio.sleep(0.05)
                return
            await asyncio.sleep(0.05)


async def countdown(r, rest, title):
    """3-2-1 on screen with a tick of sound each; a move before the last one starts it again."""
    while True:
        await r.cmd("title @s times 0 25 5")
        await r.cmd(f"title @s subtitle {json.dumps(title, ensure_ascii=False)}")
        moved = False
        for n in ("3", "2", "1"):
            await r.cmd(f"title @s title \"{n}\"")
            await r.cmd(f"{SOUND}block.note_block.hat master @s ~ ~ ~ 1 1")
            end = time.monotonic() + 1.0
            while time.monotonic() < end:
                p = (await r.call("mc.client.player"))["pos"]
                if math.hypot(p["x"] - rest[0], p["z"] - rest[1]) > 0.3:
                    moved = True
                    break
                await asyncio.sleep(0.05)
            if moved:
                break
        if not moved:
            break
        await r.cmd("title @s title {\"text\":\"False start, again\",\"color\":\"red\"}")
        await asyncio.sleep(2.0)
        p = (await r.call("mc.client.player"))["pos"]
        rest = (p["x"], p["z"])
    await r.cmd("title @s times 0 15 5")
    await r.cmd("title @s title {\"text\":\"Go!\",\"color\":\"green\"}")
    await r.cmd(f"{SOUND}block.note_block.pling master @s ~ ~ ~ 1 2")
    return rest


async def run(args):
    course = load_course(args.course)
    d = course_dir(args, course)
    cps = course["checkpoints"]
    label = args.label or args.driver
    path = os.path.join(d, "runs", f"{time.strftime('%Y%m%d-%H%M%S')}-{args.driver}-{label}.jsonl")
    seed = mobs = None
    if "wave" in course:
        seed = args.seed if args.seed is not None else next_seed(d, args.driver)
        mobs = draw_wave(course, seed)
        print(f"[run] seed {seed}: {len(mobs)} mobs", flush=True)
    elif is_combat(course):
        mobs = course["mobs"]
    async with await connect() as ws:
        r = Rpc(ws)
        await r.call("mc.bot.cancel")
        combat, mine = is_combat(course), is_mine(course)
        sx, sy, sz = course["start"]
        # A dev client joins under a new name each launch, and a new player gets the world's default game mode, which
        # on the Lab world is creative: no mob targets a creative player, so a run in it takes no damage and proves
        # nothing.
        for c in ("gamemode survival @s", f"tp @s {sx} {sy} {sz} {course.get('yaw', 0)} 0", "effect clear @s",
                  "effect give @s minecraft:instant_health 1 10 true",
                  "effect give @s minecraft:saturation 1 10 true",
                  *course.get("kit", COMBAT_KIT if combat else KIT)):
            await r.cmd(c)
        for c in course.get("world", ("time set 1000", "weather clear")):
            await r.cmd(c, as_me=False)
        if combat:
            # The sword in hand for both drivers. There is no compass: the off hand holds the shield, or stays empty on
            # a course without one.
            await r.call("mc.client.input.setHotbarSlot", {"slot": 0})
            await clear_arena(r, course)
        elif mine:
            # The pickaxe in hand for both drivers, and no compass: the ore block is in plain sight.
            await r.call("mc.client.input.setHotbarSlot", {"slot": 0})
            await clear_drops(r, course)
        else:
            first = cps[0]["pos"] if cps else course["goal"]["pos"]
            await compass_to(r, first)
            await tint(r, course, 0)
        await asyncio.sleep(2.0)                       # chunks settle after the tp
        p = (await r.call("mc.client.player"))["pos"]
        rest = (p["x"], p["z"])
        restore = None
        if args.driver == "human":
            # The bot's reflexes act on the player even with no goto running: floating up for air or escaping
            # water would steer the person's swim. They go back once the run ends.
            restore = (await r.call("mc.bot.setting", {"op": "read", "keys": list(HUMAN_OFF)}))["values"]
            await r.call("mc.bot.setting", {"op": "patch", "values": HUMAN_OFF})
            if combat:
                print(f"[run] waiting for the player to press sneak; {len(mobs)} mobs appear on Go!, "
                      "kill them all", flush=True)
            elif mine:
                print(f"[run] waiting for the player to press sneak; collect {course['mine']['count']} ores, "
                      "dig only inside the ore block, place nothing", flush=True)
            else:
                print(f"[run] waiting for the player to press sneak; {len(cps)} checkpoints, follow the red beam "
                      "and the off-hand compass", flush=True)
            await ready(r)
            # Back to the start line: whatever the person walked before pressing sneak would be a head start.
            await r.cmd(f"tp @s {sx} {sy} {sz} {course.get('yaw', 0)} 0")
            await asyncio.sleep(0.5)
            rest = (sx, sz)
        rest = await countdown(r, rest, course.get("title", course["id"]))
        cursor = (await r.call("mc.observe.cursor"))["cursor"]
        t0 = time.monotonic()
        if combat:
            # The wave appears on Go!, not before, so that neither driver can start on it early.
            await spawn_wave(r, mobs)
        # A course can forbid the bot moves that would bypass what it tests (botSettings, e.g. allowBreak).
        # A patch is saved across restarts, so the old values go back once the run ends.
        if args.driver == "bot" and course.get("botSettings"):
            restore = (await r.call("mc.bot.setting", {"op": "read",
                                                       "keys": list(course["botSettings"])}))["values"]
            await r.call("mc.bot.setting", {"op": "patch", "values": course["botSettings"]})
        if args.driver == "bot" and mine:
            await order_mine(r, course)
        elif args.driver == "bot" and combat:
            # An engage order sent before the client has the new mobs finds the area clear and ends at once.
            seen_by = time.monotonic() + 3.0
            while (not (await r.call("mc.observe.threats")).get("threats")) and time.monotonic() < seen_by:
                await asyncio.sleep(0.05)
            action_id = await order_combat(r, course)
        elif args.driver == "bot":
            g = course["goal"]
            gx, gy, gz = g["pos"]
            target = ({"kind": "column", "x": int(math.floor(gx)), "z": int(math.floor(gz))} if g.get("column")
                      else {"kind": "position", "pos": bp(g["pos"])})
            route = dict(course.get("botRoute", {}))
            if cps:
                route["via"] = [bp(cp["pos"]) for cp in cps]
            params = {"target": target}
            if route:
                params["route"] = route
            res = await r.call("mc.bot.goto", params)
            if res.get("outcome") != "accepted":
                raise RuntimeError(f"goto refused: {res}")
        meta = {"meta": True, "course": course["id"], "label": label, "driver": args.driver,
                "start": course["start"], "checkpoints": [cp["pos"] for cp in cps],
                "goal": course.get("goal"), "hz": HZ, "rest": rest}
        if combat:
            meta.update(kind="combat", mobs=len(mobs))
        if mine:
            meta.update(kind="mine", target=course["mine"]["count"], blocks=course["mine"]["blocks"],
                        workVolume=course["workVolume"])
        if seed is not None:
            meta.update(seed=seed, wave=mobs)
        f = open(path, "w")
        f.write(json.dumps(meta) + "\n")
        # The glass that tints each beam is set by command at every checkpoint; it is not the driver's doing.
        tinted = set() if combat or mine else {
            (int(math.floor(p[0])), int(math.floor(p[1])) - 1, int(math.floor(p[2])))
            for p, role in marker_points(course) if role != "guide"}
        reached, splits, end_reason = 0, [], "max-s"
        next_ev = next_hud = next_status = 0.0
        moved_at = None
        wave = alive = len(mobs) if combat else 0
        reissues = 0
        target = course["mine"]["count"] if mine else 0   # the kit's `clear` leaves no ore in the bag at Go!
        while True:
            tick = time.monotonic()
            p = await r.call("mc.client.player")
            now = time.monotonic() - t0
            if not p.get("present"):
                await asyncio.sleep(0.2)
                continue
            x, y, z = p["pos"]["x"], p["pos"]["y"], p["pos"]["z"]
            if moved_at is None and math.hypot(x - rest[0], z - rest[1]) > 0.3:
                moved_at = now
            body, vit = p.get("body", {}), p.get("vitals", {})
            rec = {"t": round(now, 3), "x": round(x, 3), "y": round(y, 3), "z": round(z, 3),
                   "yaw": round(p["look"]["yaw"], 1), "g": body.get("onGround"), "w": body.get("inWater"),
                   "hc": body.get("horizontalCollision"), "sn": body.get("crouching"), "hp": vit.get("health"),
                   "cp": reached}
            if combat:
                alive = await mobs_alive(r)
                rec["n"] = alive
                rec["near"] = await mobs_alive(r, FIGHT_REACH)
                if seed is not None:
                    rec["inRange"] = await mobs_alive(r, WORK_RANGE)
            if mine:
                rec["ores"] = ores_held(p, course["mine"]["items"])
            if now >= next_ev:
                page = await r.call("mc.observe.eventsSince", {"cursor": cursor, "limit": 200})
                cursor = page["cursor"]
                evs = [[e["type"], e.get("pos"), e.get("data")] for e in page["events"]
                       if e.get("type") in ("block.place", "block.break")
                       and (e.get("pos") or {}).get("x") is not None
                       and (e["pos"]["x"], e["pos"]["y"], e["pos"]["z"]) not in tinted]
                if evs:
                    rec["ev"] = evs
                next_ev = now + 0.5
            f.write(json.dumps(rec) + "\n")
            if combat:
                # A kill is a split; two mobs dying in one sample are two splits at the same time.
                while reached < wave - alive:
                    reached += 1
                    splits.append(round(now, 3))
                    f.write(json.dumps({"split": reached, "t": round(now, 3)}) + "\n")
                if splits and splits[-1] == round(now, 3):
                    await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                    next_hud = now
                # A random wave is won when nothing is left within WORK_RANGE: a mob out there never joined the
                # fight, and neither driver is asked to go looking for one.
                if alive == 0 or (seed is not None and rec["inRange"] == 0 and splits):
                    end_reason = "cleared"
                    break
                if vit.get("health") is not None and vit["health"] <= 0:
                    end_reason = "died"
                    break
                if now >= next_hud:
                    await r.cmd(f"title @s actionbar \"Mobs left {alive}/{wave}  {now:.1f} s\"")
                    next_hud = now + 0.5
            elif mine:
                # An ore is collected when its drop reaches the bag, not when the block breaks: that is a split, and
                # the run ends on the sample that sees the target count there.
                held = rec["ores"]
                while reached < min(held, target):
                    reached += 1
                    splits.append(round(now, 3))
                    f.write(json.dumps({"split": reached, "t": round(now, 3)}) + "\n")
                if splits and splits[-1] == round(now, 3):
                    await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                    next_hud = now
                if held >= target:
                    end_reason = "collected"
                    break
                if vit.get("health") is not None and vit["health"] <= 0:
                    end_reason = "died"
                    break
                if now >= next_hud:
                    await r.cmd(f"title @s actionbar \"Ore {reached}/{target}  {now:.1f} s\"")
                    next_hud = now + 0.5
            elif reached < len(cps):
                cp = cps[reached]
                cx, cy, cz = cp["pos"]
                if math.hypot(x - cx, z - cz) <= cp.get("radius", 1.5) and abs(y - cy) <= 2:
                    reached += 1
                    splits.append(round(now, 3))
                    f.write(json.dumps({"split": reached, "t": round(now, 3)}) + "\n")
                    await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                    await r.cmd(f"title @s actionbar {{\"text\":\"Checkpoint {reached}/{len(cps)} ✓ {now:.1f} s\","
                                "\"color\":\"green\"}")
                    nxt = cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"]
                    await compass_to(r, nxt)
                    await tint(r, course, reached)
                    next_hud = now + 1.0
            elif goal_dist(course, x, y, z) <= course["goal"].get("radius", 2):
                end_reason = "arrived"
                break
            if not combat and not mine and now >= next_hud:
                tgt = cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"]
                dist = math.hypot(x - tgt[0], z - tgt[2])
                name = f"Checkpoint {reached + 1}/{len(cps)}" if reached < len(cps) else "Goal"
                await r.cmd(f"title @s actionbar \"{name}  {dist:.0f} blocks  {now:.1f} s\"")
                next_hud = now + 0.5
            if args.driver == "bot" and combat and now >= next_status:
                ended = await combat_ended(r, action_id)
                if ended and reissues >= REISSUE_MAX:
                    end_reason = "bot-stopped: " + ended
                    break
                if ended:
                    # The order ended with tagged mobs alive: send it again, and say so in the recording.
                    reissues += 1
                    f.write(json.dumps({"reissue": reissues, "t": round(now, 3), "alive": alive,
                                        "after": ended}) + "\n")
                    print(f"[run] combat ended at {now:.1f}s with {alive} alive ({ended}); re-issued", flush=True)
                    action_id = await order_combat(r, course)
                next_status = now + 0.5
            elif args.driver == "bot" and now >= next_status and now > 2:
                st = (await r.call("mc.bot.status")).get("status", {})
                if not st.get("busy", True):
                    end_reason = "bot-stopped: " + str(st.get("lastProcessEnd"))
                    break
                next_status = now + 1.0
            if now > course["maxSeconds"]:
                break
            await asyncio.sleep(max(0.0, 1 / HZ - (time.monotonic() - tick)))
        # A combat run's time is the sample that saw the last mob dead, not the moment the loop got here.
        # So is a mining run's the sample that saw the last ore in the bag.
        total = splits[-1] if end_reason in ("cleared", "collected") else time.monotonic() - t0
        if args.driver == "bot":
            await r.call("mc.bot.cancel")
        if restore:
            await r.call("mc.bot.setting", {"op": "patch", "values": restore})
        colour = "green" if end_reason in ("arrived", "cleared", "collected") else "red"
        await r.cmd("title @s times 0 60 10")
        await r.cmd(f"title @s title {{\"text\":\"{total:.1f} s\",\"color\":\"{colour}\"}}")
        await r.cmd(f"title @s subtitle {json.dumps(end_reason, ensure_ascii=False)}")
        end = {"end": end_reason, "t": round(total, 3), "splits": splits, "firstMove": moved_at}
        if combat:
            end.update(kills=reached, reissues=reissues)
            await clear_arena(r, course)
        if mine:
            end.update(ores=reached)
        f.write(json.dumps(end) + "\n")
        f.close()
        more = f" kills={reached}/{wave} reissues={reissues}" if combat else ""
        more += f" ores={reached}/{target}" if mine else ""
        more += f" seed={seed}" if seed is not None else ""
        print(f"[run] {end_reason} {total:.1f}s splits={splits}{more} → {path}", flush=True)


# ---------------------------------------------------------------- report

def load(path):
    meta, rows, end, splits = None, [], None, []
    for line in open(path):
        rec = json.loads(line)
        if rec.get("meta"):
            meta = rec
        elif "end" in rec:
            end = rec
        elif "split" in rec:
            splits.append(rec["t"])
        elif "reissue" not in rec:
            rows.append(rec)
    return meta, rows, end, splits


def contacts(rows):
    """(bumps, grazes): each run of horizontalCollision samples is one contact. A graze is a contact that lasts
    at most GRAZE_S and is followed within GRAZE_CLIMB_S by standing at least GRAZE_RISE above the floor the body
    stood on before it: the face of a step it went on to climb. Grazes are reported but are not errors, and a
    contact the body rose along without dipping (a ladder or vine) is neither."""
    bumps = grazes = 0
    floor = None
    i = 0
    while i < len(rows):
        r = rows[i]
        if r.get("g"):
            floor = r["y"]
        if not r.get("hc"):
            i += 1
            continue
        j = i
        while j < len(rows) and rows[j].get("hc"):
            j += 1
        short = rows[min(j, len(rows) - 1)]["t"] - r["t"] <= GRAZE_S
        climbed = floor is not None and any(
            q.get("g") and q["y"] >= floor + GRAZE_RISE
            for q in rows[i:] if q["t"] - r["t"] <= GRAZE_CLIMB_S)
        # Pressing into a ladder or vine is how it is climbed. A jump into a wall stops rising within 0.3 s,
        # so rising without a dip for longer than that is a climb.
        ys = [q["y"] for q in rows[i:j]]
        ladder = (rows[j - 1]["t"] - r["t"] >= LADDER_S and ys[-1] - ys[0] >= LADDER_RISE
                  and all(b >= a - 1e-3 for a, b in zip(ys, ys[1:])))
        if ladder:
            pass
        elif short and climbed:
            grazes += 1
        else:
            bumps += 1
        i = j
    return bumps, grazes


def in_reach_or_just_killed(rows, j):
    """Whether sample j of a combat recording has a mob of the wave in reach, or one in reach before died on it."""
    q = rows[j]
    if q.get("near"):
        return True
    prev = rows[j - 1] if j > 0 else None
    return prev is not None and bool(prev.get("near")) and q.get("n", 0) < prev.get("n", 0)


def cleared_in_range(meta, end, splits):
    """Whether a random-wave run that ended with mobs alive had cleared everything within WORK_RANGE.

    A recording made before `inRange` was sampled has one witness to it: the bot's own engage order ended
    "succeeded", which its threat scan of the same radius reports only when no hostile is left in it. Such a run is
    cleared at its last kill."""
    return (bool(meta.get("wave")) and end is not None and end["end"] == "bot-stopped: succeeded"
            and 0 < len(splits) < meta.get("mobs", 0))


def wrong_blocks(meta, rows):
    """Blocks a mining run broke that were neither a target ore nor inside the work volume, the block of stone the ores
    are in. Digging through that block is the way to a hidden ore; the floor and the walls around it are not."""
    targets = set(meta["blocks"])
    cells = set()
    for r in rows:
        for kind, pos, block in r.get("ev", []):
            # The payload names the block that was there; a state suffix would not change which block it was.
            ore = str(block).split("[")[0] in targets
            if kind == "block.break" and not ore and not in_box(pos, meta["workVolume"]):
                cells.add((pos["x"], pos["y"], pos["z"]))
    return len(cells)


def analyse(path):
    meta, rows, end, splits = load(path)
    combat = meta.get("kind") == "combat"
    if cleared_in_range(meta, end, splits):
        end = {**end, "end": "cleared", "t": splits[-1]}
        rows = [q for q in rows if q["t"] <= splits[-1]]   # the re-sent orders after it are not the run
    mine = meta.get("kind") == "mine"
    # Neither has checkpoints to go back from: a fight backs off from a mob, a miner goes from one ore to the next.
    roaming = combat or mine
    targets = None if roaming else [tuple(c) for c in meta["checkpoints"]] + [tuple(meta["goal"]["pos"])]
    finished = "cleared" if combat else "collected" if mine else "arrived"
    out = {"file": os.path.basename(path), "label": meta["label"], "driver": meta["driver"],
           "ok": bool(end) and end["end"] == finished,
           "time": end["t"] if end else (rows[-1]["t"] if rows else 0),
           "splits": splits, "firstMove": end.get("firstMove") if end else None,
           "kills": len(splits), "reissues": (end or {}).get("reissues", 0),
           "wave": meta.get("mobs"), "seed": meta.get("seed")}
    breaks = [r["t"] for r in rows for e in r.get("ev", []) if e[0] == "block.break"]
    stalls, stall_t, run_start = 0, 0.0, None
    backtracks, hurt, hurt_n, water, placed, broken, walked = 0, 0.0, 0, 0.0, 0, 0, 0.0
    best = None
    half = HZ // 2
    for i, (a, b) in enumerate(zip(rows, rows[1:])):
        dt = max(b["t"] - a["t"], 1e-3)
        step = math.hypot(b["x"] - a["x"], b["z"] - a["z"])
        walked += step
        # Climbing a ladder or vine, or pillaring, moves the body up or down without moving it sideways. It counts
        # as progress when the height went the same way in both halves of the last second, which a jump in place
        # never does.
        y0, y1, y2 = (rows[max(0, i + 1 - k)]["y"] for k in (2 * half, half, 0))
        climbing = min(y1 - y0, y2 - y1) >= CLIMB_HALF_S or max(y1 - y0, y2 - y1) <= -CLIMB_HALF_S
        slow = step / dt < STALL_SPEED and not climbing
        if slow and run_start is None:
            run_start, spell_i = a["t"], i
        if not slow and run_start is not None:
            span = b["t"] - run_start
            mining = any(run_start - 0.5 <= t <= b["t"] + 0.5 for t in breaks)
            # Standing still to swing at a mob is fighting, but only while one is in reach on every sample of the
            # spell: a mob passing by for a moment does not excuse the rest of it. The spell is rows[spell_i..i]. A
            # sample with nothing in reach still counts as fighting when a mob that was in reach on the sample
            # before died on it: a creeper's blast that kills the pack around the bot ends the fight, not a stall.
            fighting = combat and all(in_reach_or_just_killed(rows, j) for j in range(spell_i, i + 1))
            if span >= STALL_S and not mining and not fighting:
                stalls += 1
                stall_t += span
            run_start = None
        if b.get("hp") is not None and a.get("hp") is not None and b["hp"] < a["hp"]:
            hurt += a["hp"] - b["hp"]
            hurt_n += 1
        if b.get("w"):
            water += dt
        if b["cp"] != a["cp"]:
            best = None
        # Not scored on a combat course, where backing off from a creeper or a skeleton is often the right move, nor on
        # a mining course, where the next ore can be behind the player.
        if not roaming:
            tx, _, tz = targets[min(b["cp"], len(targets) - 1)]
            dist = math.hypot(tx - b["x"], tz - b["z"])
            best = dist if best is None else best
            if dist > best + BACKTRACK:
                backtracks += 1
                best = dist
            best = min(best, dist)
        for e in {(e[0], json.dumps(e[1], sort_keys=True)) for e in b.get("ev", [])}:
            placed += e[0] == "block.place"
            broken += e[0] == "block.break"
    bumps, grazes = contacts(rows)
    out.update(stalls=stalls, stall_t=stall_t, bumps=bumps, grazes=grazes,
               backtracks=None if roaming else backtracks, hurt=hurt,
               hurt_n=hurt_n, water=water, placed=placed, broken=broken, walked=walked)
    out["errors"] = stalls + bumps + (0 if roaming else backtracks) + hurt_n
    if mine:
        out["ores"] = (end or {}).get("ores", len(splits))
        out["wrong"] = wrong_blocks(meta, rows)
        out["errors"] += out["wrong"]
    out["rows"] = rows
    return out


def report(args):
    course = load_course(args.course)
    d = course_dir(args, course)
    files = sorted(os.path.join(d, "runs", f) for f in os.listdir(os.path.join(d, "runs")) if f.endswith(".jsonl"))
    if args.since:
        files = [f for f in files if os.path.basename(f) >= args.since]
    runs = [analyse(f) for f in files]
    if not runs:
        print("no runs")
        return
    cols = (("driver", "{}"), ("ok", "{}"), ("time", "{:.1f}"), ("firstMove", "{}"), ("errors", "{}"),
            ("stalls", "{}"), ("stall_t", "{:.1f}"), ("bumps", "{}"), ("grazes", "{}"), ("backtracks", "{}"),
            ("hurt", "{:.0f}"), ("water", "{:.1f}"), ("placed", "{}"), ("broken", "{}"), ("walked", "{:.0f}"))
    combat, mine = is_combat(course), is_mine(course)
    if combat:
        cols += (("kills", "{}"), ("reissues", "{}"))
    if mine:
        cols += (("wrong", "{}"), ("ores", "{}"))
    if "wave" in course:
        cols += (("wave", "{}"), ("seed", "{}"))
    print(f"{'run':34}" + "".join(f"{k:>11}" for k, _ in cols))
    for r in runs:
        cells = []
        for k, fmt in cols:
            v = r[k]
            if v is None and k != "firstMove":
                cells.append(f"{'-':>11}")    # not scored on this course
                continue
            cells.append(f"{(fmt.format(round(v, 2)) if k == 'firstMove' and v is not None else fmt.format(v)):>11}")
        print(f"{r['file'][:34]:34}" + "".join(cells))
    bots = [r for r in runs if r["driver"] == "bot"]
    humans = [r for r in runs if r["driver"] == "human" and r["ok"]]
    print()
    if not bots or not humans:
        print("verdict: needs bot runs and at least one finished human run")
        return
    h_best = min(humans, key=lambda r: r["time"])
    h_err = min(r["errors"] for r in humans)
    failed = [r for r in bots if not r["ok"]]
    slow = [r for r in bots if r["ok"] and r["time"] >= h_best["time"]]
    sloppy = [r for r in bots if r["errors"] > h_err]
    times = [r["time"] for r in bots if r["ok"]]
    print(f"human best {h_best['time']:.1f}s ({h_best['file']}), fewest human errors {h_err}")
    if times:
        print(f"bot {len(bots)} runs: median {statistics.median(times):.1f}s, worst {max(times):.1f}s")
    print(f"  every bot run finished ........ {'PASS' if not failed else 'FAIL ' + str(len(failed))}")
    if combat:
        # A fight is partly luck: how many mobs a creeper's blast takes, and where the skeletons back off to, swing a
        # run by several seconds and hits for either driver. So the bot's median run is judged, not its worst.
        med_t = statistics.median(times) if times else float("inf")
        med_e = statistics.median(r["errors"] for r in bots)
        slow = [] if med_t < h_best["time"] else [med_t]
        sloppy = [] if med_e <= h_err else [med_e]
        print(f"  bot median beats human best ... {'PASS' if not slow else f'FAIL {med_t:.1f}s'}")
        print(f"  bot median errors <= fewest ... {'PASS' if not sloppy else f'FAIL {med_e:g}'}")
    else:
        print(f"  every bot run beats human best  {'PASS' if not slow and not failed else 'FAIL ' + str(len(slow))}")
        print(f"  bot errors <= fewest human .... {'PASS' if not sloppy else 'FAIL ' + str(len(sloppy))}")
    print(f"VERDICT: {'PASS' if not (failed or slow or sloppy) else 'FAIL'}")
    # Where the time goes: each leg between checkpoints, bot median against the human best. On a combat course a
    # leg ends at a kill, and the last kill is the end of the run, so there is no leg after it. A drawn wave varies in
    # size, so there are as many legs as the largest wave, and a run whose wave was smaller has no time for the rest.
    # On a mining course a leg ends at an ore in the bag, and the last ore ends the run.
    legs = (max(r["wave"] or 0 for r in runs) if combat else course["mine"]["count"] if mine
            else len(course["checkpoints"]) + 1)
    print("\nleg      human-best   bot-median   bot-worst")
    for i in range(legs):
        def leg(r):
            marks = [0.0] + r["splits"] + ([] if combat or mine else [r["time"]])
            return marks[i + 1] - marks[i] if i + 1 < len(marks) else float("nan")
        bt = [t for t in (leg(r) for r in bots if r["ok"]) if not math.isnan(t)]
        hb = leg(h_best)
        flag = " *" if bt and max(bt) >= hb else ""
        med = statistics.median(bt) if bt else float("nan")
        print(f"{i + 1:>3}   {hb:>12.1f} {med:>12.1f} {max(bt) if bt else float('nan'):>11.1f}{flag}")
    if args.png:
        draw(runs, args.png)


def draw(runs, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ((ax_map, ax_t), (ax_y, ax_sp)) = plt.subplots(2, 2, figsize=(16, 11))
    for r in runs:
        rows = r["rows"]
        lbl = f"{r['label']} ({r['driver']}, {r['time']:.0f}s, err {r['errors']})"
        style = "-" if r["driver"] == "bot" else "--"
        ax_map.plot([q["x"] for q in rows], [q["z"] for q in rows], style, lw=1, label=lbl)
        walked, acc = [0.0], 0.0
        for a, b in zip(rows, rows[1:]):
            acc += math.hypot(b["x"] - a["x"], b["z"] - a["z"])
            walked.append(acc)
        ax_t.plot([q["t"] for q in rows], walked, style, lw=1, label=lbl)
        ax_y.plot(walked, [q["y"] for q in rows], style, lw=1, label=lbl)
        sp = [0.0] + [math.hypot(b["x"] - a["x"], b["z"] - a["z"]) / max(b["t"] - a["t"], 1e-3)
                      for a, b in zip(rows, rows[1:])]
        k = 10
        ax_sp.plot(walked, [sum(sp[max(0, i - k):i + 1]) / (i + 1 - max(0, i - k)) for i in range(len(sp))],
                   style, lw=1, label=lbl)
        for q in rows:
            if q.get("hc"):
                ax_map.plot(q["x"], q["z"], "r.", ms=3)
    ax_map.set_title("top-down X/Z (red dot: wall contact)")
    ax_map.invert_yaxis()
    ax_map.set_aspect("equal", "datalim")
    ax_t.set_title("distance walked vs time")
    ax_y.set_title("elevation vs distance walked")
    ax_sp.set_title("horizontal speed, blocks/s (0.5 s mean; dotted: sprint 5.6, walk 4.3)")
    ax_sp.axhline(5.6, ls=":", c="gray")
    ax_sp.axhline(4.3, ls=":", c="gray")
    for ax in (ax_map, ax_t, ax_y, ax_sp):
        ax.grid(alpha=.3)
        ax.legend(fontsize=7)
    fig.tight_layout()
    fig.savefig(out, dpi=90)
    print(f"[chart] {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.normpath(OUT))
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("setup")
    s.add_argument("course")
    m = sub.add_parser("markers")
    m.add_argument("course")
    g = m.add_mutually_exclusive_group(required=True)
    g.add_argument("--place", action="store_true")
    g.add_argument("--remove", action="store_true")
    t = sub.add_parser("terrain")
    t.add_argument("course")
    g = t.add_mutually_exclusive_group(required=True)
    g.add_argument("--capture", action="store_true")
    g.add_argument("--reset", action="store_true")
    r = sub.add_parser("run")
    r.add_argument("course")
    r.add_argument("--driver", choices=("bot", "human"), required=True)
    r.add_argument("--label")
    r.add_argument("--seed", type=int, help="the wave's seed on a course that draws one; default: one more than this "
                                             "driver's recordings of the course")
    p = sub.add_parser("report")
    p.add_argument("course")
    p.add_argument("--png")
    p.add_argument("--since", help="only runs whose file name sorts at or after this, e.g. 20261108-1200")
    a = ap.parse_args()
    if a.cmd == "setup":
        asyncio.run(setup(a))
    elif a.cmd == "markers":
        asyncio.run(markers(a))
    elif a.cmd == "terrain":
        asyncio.run(terrain(a))
    elif a.cmd == "run":
        asyncio.run(run(a))
    else:
        report(a)


if __name__ == "__main__":
    main()
