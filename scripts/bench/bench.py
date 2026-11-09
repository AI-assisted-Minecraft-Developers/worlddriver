#!/usr/bin/env python3
"""bench.py — run a course with the bot or a human at the keyboard, and judge the bot against the human.

A course is a JSON file under courses/: a start, ordered checkpoints, a goal, and how its terrain is
put back; or, with "kind": "combat", an arena and a wave of mobs that appears on Go! and has to be
killed. The wave is either fixed or drawn from a pool by a seed. With "kind": "mine", a block of stone with ores in
it, and a number of ores to collect from it. With "kind": "clear", a marked volume of mixed blocks to break until it
holds only air. With "kind": "build", a blueprint to build from a kit of blocks, next to a full-size copy of it that
shows the person what to build. With "kind": "farm", a plot of ripe crops to harvest and replant. With "kind": "elytra",
rings in the air to fly through in order with an elytra and rockets, from a tower to a landing pad. Both drivers run the
same course in the same client, with the same kit, markers and clock, so their recordings compare directly.
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
QUARTERS = 4           # a clearing, building or farming run's splits: one at each quarter of its cells done
# An elytra course. A crossing of the next ring's plane, going the ring's way, outside its opening but this close to its
# centre is a missed ring; further out the flyer was not trying for it.
RING_REACH = 32.0
# After a miss, the same ring is missed again only once the body has been back this far in front of its plane: a body
# that hangs about the plane outside the opening crosses it back and forth on every sample.
RING_REARM = 2.0
FLY_HALF_HEIGHT, STAND_HALF_HEIGHT = 0.3, 0.9   # half the body's height gliding and upright: its centre above the feet
LAND_SETTLE_S = 1.0    # samples kept after touchdown, so that the damage of a hard landing is read
FIREWORK = "minecraft:firework_rocket"


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


def is_clear(course):
    return course["kind"] == "clear"


def is_build(course):
    return course["kind"] == "build"


def is_farm(course):
    return course["kind"] == "farm"


def is_elytra(course):
    return course["kind"] == "elytra"


def has_checkpoints(course):
    """A course driven from checkpoint to checkpoint, with markers, a compass and the checkpoint on the action bar."""
    return course["kind"] in ("route", "elytra")


def body_centre(sample):
    """The middle of the body in a recorded sample: a gliding body is 0.6 tall, an upright one 1.8."""
    return (sample["x"], sample["y"] + (FLY_HALF_HEIGHT if sample.get("fly") else STAND_HALF_HEIGHT), sample["z"])


def ring_ahead(cp, sample):
    """How far the body's centre is in front of the ring's plane, against its normal; negative past it."""
    nx, _, nz = cp["ring"]["normal"]
    x, _, z = body_centre(sample)
    return ((cp["pos"][0] - x) * nx + (cp["pos"][2] - z) * nz) / math.hypot(nx, nz)


def ring_crossing(cp, a, b):
    """How the body's move from sample a to sample b went past a ring: "pass" when it crossed the ring's plane inside
    the opening, either way; "miss" when it crossed the plane going the ring's way outside the opening, within
    RING_REACH of its centre; None otherwise. Rings stand upright, so the normal is horizontal and the opening is a
    square `half` either side of the centre, across and up."""
    nx, _, nz = cp["ring"]["normal"]
    norm = math.hypot(nx, nz)
    nx, nz = nx / norm, nz / norm
    cx, cy, cz = cp["pos"]
    (ax, ay, az), (bx, by, bz) = body_centre(a), body_centre(b)
    sa, sb = (ax - cx) * nx + (az - cz) * nz, (bx - cx) * nx + (bz - cz) * nz
    forward, backward = sa < 0 <= sb, sb < 0 <= sa
    if not (forward or backward):
        return None
    f = sa / (sa - sb)
    px, py, pz = ax + f * (bx - ax), ay + f * (by - ay), az + f * (bz - az)
    across, up = (px - cx) * -nz + (pz - cz) * nx, py - cy
    half = cp["ring"]["half"]
    if abs(across) < half and abs(up) < half:
        return "pass"
    if forward and abs(across) <= RING_REACH and abs(up) <= RING_REACH:
        return "miss"
    return None


def ring_step(cp, a, b, armed):
    """The move from sample a to sample b against the next ring: (passed, missed, armed). A miss counts only while
    `armed`, which a miss clears and which comes back once the body is RING_REARM in front of the plane again."""
    crossing = ring_crossing(cp, a, b)
    if crossing == "pass":
        return True, False, True
    missed = crossing == "miss" and armed
    if missed:
        armed = False
    elif not armed and ring_ahead(cp, b) >= RING_REARM:
        armed = True
    return False, missed, armed


def on_pad(sample, pad):
    """Whether a sample stands on the landing pad: on the ground, over the pad's top layer."""
    (x0, _, z0), (x1, top, z1) = pad
    return (bool(sample.get("g")) and abs(sample["y"] - (top + 1)) < 0.5
            and min(x0, x1) - 0.3 <= sample["x"] <= max(x0, x1) + 1.3
            and min(z0, z1) - 0.3 <= sample["z"] <= max(z0, z1) + 1.3)


def plot_cells(course):
    """A farming course's crop cells as (x, y, z), in the order of the plot box: every cell of its crop layer except
    those over the water, which is part of the plot's shape but holds no crop."""
    (x0, y0, z0), (x1, y1, z1) = course["farm"]["plot"]
    water = {tuple(w) for w in course["farm"].get("water", [])}
    return [(x, y, z) for y in range(min(y0, y1), max(y0, y1) + 1) for z in range(min(z0, z1), max(z0, z1) + 1)
            for x in range(min(x0, x1), max(x0, x1) + 1) if (x, y - 1, z) not in water]


def blueprint_cells(course, offset=(0, 0, 0)):
    """A building course's blueprint as {(x, y, z): block id}, air left out, shifted by `offset`.

    The blueprint's `layers` go bottom-up; each layer's rows go north to south (+z) and each row's characters west to
    east (+x), so the file reads like a map of each layer. The dict keeps that order: bottom-up, then by z, then by x."""
    b = course["blueprint"]
    ox, oy, oz = (o + d for o, d in zip(b["origin"], offset))
    cells = {}
    for dy, layer in enumerate(b["layers"]):
        for dz, row in enumerate(layer):
            for dx, ch in enumerate(row):
                block = b["palette"][ch]
                if block != "minecraft:air":
                    cells[(ox + dx, oy + dy, oz + dz)] = block
    return cells


def blueprint_box(course):
    b = course["blueprint"]
    x, y, z = b["origin"]
    return [[x, y, z], [x + len(b["layers"][0][0]) - 1, y + len(b["layers"]) - 1, z + len(b["layers"][0]) - 1]]


def copy_commands(course):
    """The commands that put a building course's full-size copy beside it, the one a person builds from. None on any
    other course."""
    if not is_build(course):
        return []
    return [f"setblock {x} {y} {z} {block}"
            for (x, y, z), block in blueprint_cells(course, course["blueprint"]["copyOffset"]).items()]


def bot_schematic(course):
    """The blueprint as mc.bot.build's `blocks` schematic, with offsets from the blueprint's origin."""
    b = course["blueprint"]
    ox, oy, oz = b["origin"]
    cells = blueprint_cells(course)
    palette = sorted(set(cells.values()))
    (_, _, _), (x1, y1, z1) = blueprint_box(course)
    return {"kind": "blocks", "w": x1 - ox + 1, "h": y1 - oy + 1, "d": z1 - oz + 1, "palette": palette,
            "data": [[x - ox, y - oy, z - oz, palette.index(block)] for (x, y, z), block in cells.items()]}


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
    """Every marker as (feet position, role): checkpoints in order, then the goal, then guide beacons. A checkpoint in
    the air, a ring, names its own `beacon` cell below it, so that the beam goes up through the ring."""
    pts = [(cp.get("beacon", cp["pos"]), "checkpoint") for cp in course["checkpoints"]]
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
    if not has_checkpoints(course):
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
        commands = course["setup"] + copy_commands(course)
        for c in commands:
            res = await r.cmd(c, as_me=False)
            if not res.get("success", True):
                # A fill over blocks that are already the target block reports failure too.
                print(f"[setup] no change: {c}: {res.get('feedback')}")
        print(f"[setup] built, {len(commands)} commands")


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
            commands = t.get("commands", course.get("setup", []) + copy_commands(course))
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


def volume_cells(box):
    (x0, y0, z0), (x1, y1, z1) = box
    return (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1) * (abs(z1 - z0) + 1)


def quarter_marks(cells):
    """The counts at which a clearing or building run takes its splits: each quarter of its cells, rounded up."""
    return [math.ceil(cells * k / QUARTERS) for k in range(1, QUARTERS + 1)]


async def blocks_in_box(r, box, select):
    """Every non-air block in the box. One block query over the cube around the box's centre, read on the server, so
    the answer is never a frame behind a break or a placement the client has not drawn yet."""
    (x0, y0, z0), (x1, y1, z1) = box
    lo, hi = (min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1))
    centre = [(a + b) // 2 for a, b in zip(lo, hi)]
    radius = max(max(b - c, c - a) for a, b, c in zip(lo, hi, centre))
    res = await r.call("mc.query", {"q": "blocks", "center": dict(zip("xyz", centre)),
                                    "filter": {"in_radius": radius}, "select": select})
    return [b for b in res["blocks"] if in_box(b["pos"], box)]


async def cells_cleared(r, box):
    """How many cells of the volume hold air."""
    return volume_cells(box) - len(await blocks_in_box(r, box, ["pos"]))


async def cells_built(r, course, cells):
    """How many cells of the blueprint hold their block. A block of another kind in a cell, or any block outside the
    blueprint, does not count against it here: those are wrong blocks, scored from the events."""
    found = {(b["pos"]["x"], b["pos"]["y"], b["pos"]["z"]): b["type"]
             for b in await blocks_in_box(r, blueprint_box(course), ["pos", "type"])}
    return sum(found.get(c) == block for c, block in cells.items())


async def order_build(r, course):
    res = await r.call("mc.bot.build", {"origin": dict(zip("xyz", course["blueprint"]["origin"])),
                                        "schematic": bot_schematic(course)})
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"build refused: {res}")
    return res["action"]["actionId"]


async def order_clear(r, course):
    (x0, y0, z0), (x1, y1, z1) = course["volume"]
    res = await r.call("mc.bot.clearArea", {"target": {"kind": "box", "from": {"x": x0, "y": y0, "z": z0},
                                                       "to": {"x": x1, "y": y1, "z": z1}},
                                            "edit": {"kind": "clear"}})
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"clearArea refused: {res}")
    return res["action"]["actionId"]


async def plot_state(r, course, cells):
    """(replanted, trampled) on a farming plot: how many crop cells hold a crop at age 0, and the crop cells whose
    farmland is gone, sorted. Random ticks are off, so nothing grows during a run and every crop was ripe at the start:
    a crop at age 0 was harvested and planted again. Farmland a body jumped or fell onto turns to dirt, which no block
    event reports, so it is read here. One block query over the plot and the farmland under it."""
    (x0, y0, z0), (x1, y1, z1) = course["farm"]["plot"]
    box = [[x0, min(y0, y1) - 1, z0], [x1, max(y0, y1), z1]]
    found = {(b["pos"]["x"], b["pos"]["y"], b["pos"]["z"]): b for b in await blocks_in_box(r, box, ["pos", "type",
                                                                                                    "state"])}
    crop = course["farm"]["crop"]
    replanted = sum(1 for c in cells if (found.get(c) or {}).get("type") == crop
                    and str((found[c].get("state") or {}).get("age")) == "0")
    trampled = sorted(c for c in cells
                      if (found.get((c[0], c[1] - 1, c[2])) or {}).get("type") != "minecraft:farmland")
    return replanted, trampled


async def order_farm(r, course):
    (x0, y0, z0), (x1, y1, z1) = course["farm"]["plot"]
    res = await r.call("mc.bot.farm", {"from": {"x": x0, "y": y0, "z": z0}, "to": {"x": x1, "y": y1, "z": z1},
                                       "crops": [course["farm"]["crop"]], "replant": True})
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"farm refused: {res}")
    return res["action"]["actionId"]


def fly_order(course, reached):
    """The bot's elytra order: land on the pad, flying through the centres of the rings not yet passed on the way.

    The bot keeps its speed through each ring and turns onto its own line through it; a ring it misses stays next and it
    turns back for it. An order that ends early is sent again with the rings still ahead."""
    return {"pos": bp(course["goal"]["pos"]), "via": [bp(cp["pos"]) for cp in course["checkpoints"][reached:]]}


async def order_fly(r, params):
    res = await r.call("mc.bot.elytraFly", params)
    if res.get("outcome") != "accepted":
        raise RuntimeError(f"elytraFly refused: {res}")
    return res["action"]["actionId"]


def rockets_held(p):
    held = (p.get("inventory") or {}).get("items") or {}
    return held.get(FIREWORK, 0)


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


async def action_ended(r, action_id):
    """How a bot order ended, or None while it runs. A fight runs as a reflex chain, so mc.bot.status never reports it
    busy; the action's own state is what says it ended."""
    a = (await r.call("mc.wait.result", {"kind": "action", "actionId": action_id}))["action"]
    if a["state"] == "running":
        return None
    return a["state"] + "".join(f" {a[k]}" for k in ("reason", "message") if a.get(k))


async def block_events(r, cursor, tinted):
    """The blocks placed and broken since `cursor`, as [type, pos, block id], leaving out the beam glass the run tints
    itself; and the cursor to read from next."""
    page = await r.call("mc.observe.eventsSince", {"cursor": cursor, "limit": 200})
    evs = [[e["type"], e.get("pos"), e.get("data")] for e in page["events"]
           if e.get("type") in ("block.place", "block.break")
           and (e.get("pos") or {}).get("x") is not None
           and (e["pos"]["x"], e["pos"]["y"], e["pos"]["z"]) not in tinted]
    return page["cursor"], evs


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
        combat, mine, clear, build = is_combat(course), is_mine(course), is_clear(course), is_build(course)
        farm, elytra = is_farm(course), is_elytra(course)
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
        elif mine or clear or build or farm:
            # The first hotbar slot in hand for both drivers, and no compass: the work is in plain sight.
            await r.call("mc.client.input.setHotbarSlot", {"slot": 0})
            await clear_drops(r, course)
        else:
            first = cps[0]["pos"] if cps else course["goal"]["pos"]
            await compass_to(r, first)
            await tint(r, course, 0)
            if elytra:
                # The rockets in hand for both drivers, and no stack left on the pad or a ring by an earlier run's
                # death to pick up on the way.
                await r.call("mc.client.input.setHotbarSlot", {"slot": 0})
                await clear_drops(r, course)
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
            elif clear:
                print(f"[run] waiting for the player to press sneak; break every block inside the glass frame, "
                      "not the floor or the frame, place nothing", flush=True)
            elif build:
                print(f"[run] waiting for the player to press sneak; build the copy to the east on the white footprint "
                      f"in front of you, {len(blueprint_cells(course))} blocks; break only blocks you placed",
                      flush=True)
            elif farm:
                print(f"[run] waiting for the player to press sneak; harvest all {len(plot_cells(course))} wheat and "
                      "plant a seed in each cell; don't jump on the farmland, break nothing but wheat", flush=True)
            elif elytra:
                print(f"[run] waiting for the player to press sneak; glide off the tower and fly through the "
                      f"{len(cps)} rings in order, following the red beam, then land on the hay pad", flush=True)
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
        elif args.driver == "bot" and clear:
            action_id = await order_clear(r, course)
        elif args.driver == "bot" and build:
            action_id = await order_build(r, course)
        elif args.driver == "bot" and farm:
            action_id = await order_farm(r, course)
        elif args.driver == "bot" and combat:
            # An engage order sent before the client has the new mobs finds the area clear and ends at once.
            seen_by = time.monotonic() + 3.0
            while (not (await r.call("mc.observe.threats")).get("threats")) and time.monotonic() < seen_by:
                await asyncio.sleep(0.05)
            action_id = await order_combat(r, course)
        elif args.driver == "bot" and elytra:
            # One elytraFly order for the whole course: the rings are its via points (fly_order).
            action_id = await order_fly(r, fly_order(course, 0))
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
        if clear:
            # The wrong-block rule of a mining course with no target blocks: anything broken outside the volume.
            meta.update(kind="clear", cells=volume_cells(course["volume"]), blocks=[], workVolume=course["volume"],
                        placeAllowed=course.get("placeAllowed", False))
        if build:
            # The blueprint goes into the recording, so that the wrong blocks are scored from the recording alone.
            meta.update(kind="build", blueprint=[[x, y, z, b] for (x, y, z), b in blueprint_cells(course).items()])
        if farm:
            # The crop cells and the crop go into the recording, so that the wrong blocks are scored from it alone.
            meta.update(kind="farm", crop=course["farm"]["crop"], plot=[list(c) for c in plot_cells(course)])
        if elytra:
            # The rings and the pad go into the recording, so that misses and the landing are scored from it alone.
            meta.update(kind="elytra", rings=cps, pad=course["goal"]["pad"])
        if seed is not None:
            meta.update(seed=seed, wave=mobs)
        f = open(path, "w")
        f.write(json.dumps(meta) + "\n")
        # The glass that tints each beam is set by command at every checkpoint; it is not the driver's doing.
        tinted = set() if not has_checkpoints(course) else {
            (int(math.floor(p[0])), int(math.floor(p[1])) - 1, int(math.floor(p[2])))
            for p, role in marker_points(course) if role != "guide"}
        reached, splits, end_reason = 0, [], "max-s"
        next_ev = next_hud = next_status = 0.0
        moved_at = None
        wave = alive = len(mobs) if combat else 0
        reissues = 0
        target = course["mine"]["count"] if mine else 0   # the kit's `clear` leaves no ore in the bag at Go!
        if clear:
            target = volume_cells(course["volume"])
            marks = quarter_marks(target)
        if build:
            blueprint = blueprint_cells(course)
            target = len(blueprint)
            marks = quarter_marks(target)
        if farm:
            crop_cells = plot_cells(course)
            target = len(crop_cells)
            marks = quarter_marks(target)
        # Ores in the bag, cells of the volume cleared, blueprint cells built, or crop cells replanted.
        done = 0
        # An elytra run: the sample before this one, for the ring crossing; the touchdown on the pad; the rings missed;
        # and since when the bot's order has been over.
        prev_rec, landed_at, misses, miss_armed, order_ended_at = None, None, 0, True, None
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
            if elytra:
                rec["fly"] = body.get("pose") == "FALL_FLYING"
                rec["rk"] = rockets_held(p)
            if combat:
                alive = await mobs_alive(r)
                rec["n"] = alive
                rec["near"] = await mobs_alive(r, FIGHT_REACH)
                if seed is not None:
                    rec["inRange"] = await mobs_alive(r, WORK_RANGE)
            if mine:
                rec["ores"] = ores_held(p, course["mine"]["items"])
            if clear:
                rec["cleared"] = await cells_cleared(r, course["volume"])
            if build:
                rec["built"] = await cells_built(r, course, blueprint)
            if farm:
                rec["replanted"], trampled = await plot_state(r, course, crop_cells)
                if trampled:
                    rec["trampled"] = [list(c) for c in trampled]
            if now >= next_ev:
                cursor, evs = await block_events(r, cursor, tinted)
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
            elif mine or clear or build or farm:
                # Mining: an ore is collected when its drop reaches the bag, not when the block breaks, and each one is
                # a split. Clearing, building and farming: a split at each quarter of the cells done. A building or
                # farming run's count can go down, when a driver breaks a block it placed; a split already taken stays.
                # Every run ends on the sample that sees the whole task done.
                done = (rec["ores"] if mine else rec["cleared"] if clear else rec["built"] if build
                        else rec["replanted"])
                due = min(done, target) if mine else sum(done >= m for m in marks)
                while reached < due:
                    reached += 1
                    splits.append(round(now, 3))
                    f.write(json.dumps({"split": reached, "t": round(now, 3)}) + "\n")
                if splits and splits[-1] == round(now, 3):
                    await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                    next_hud = now
                if done >= target:
                    end_reason = "collected" if mine else "cleared" if clear else "built" if build else "farmed"
                    break
                if vit.get("health") is not None and vit["health"] <= 0:
                    end_reason = "died"
                    break
                if now >= next_hud:
                    hud = (f"Ore {reached}/{target}" if mine else f"Cleared {done}/{target}" if clear
                           else f"Built {done}/{target}" if build else f"Replanted {done}/{target}")
                    await r.cmd(f"title @s actionbar \"{hud}  {now:.1f} s\"")
                    next_hud = now + 0.5
            elif elytra:
                # A ring is passed when the body crosses its plane inside the opening, and missed when it crosses the
                # plane outside it; the run ends when the body stands on the pad, and the clock stops there.
                if landed_at is None and reached < len(cps) and prev_rec is not None:
                    passed, missed, miss_armed = ring_step(cps[reached], prev_rec, rec, miss_armed)
                    if passed:
                        reached += 1
                        splits.append(round(now, 3))
                        f.write(json.dumps({"split": reached, "t": round(now, 3)}) + "\n")
                        await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                        await r.cmd(f"title @s actionbar {{\"text\":\"Ring {reached}/{len(cps)} ✓ {now:.1f} s\","
                                    "\"color\":\"green\"}")
                        await compass_to(r, cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"])
                        await tint(r, course, reached)
                        next_hud = now + 1.0
                    elif missed:
                        misses += 1
                        f.write(json.dumps({"miss": reached + 1, "t": round(now, 3)}) + "\n")
                        await r.cmd(f"{SOUND}block.note_block.bass master @s ~ ~ ~ 1 0.6")
                        await r.cmd(f"title @s actionbar {{\"text\":\"Missed ring {reached + 1}, turn back\","
                                    "\"color\":\"red\"}")
                        next_hud = now + 1.5
                elif landed_at is None and reached == len(cps) and on_pad(rec, course["goal"]["pad"]):
                    landed_at, end_reason = now, "landed"
                    await r.cmd(f"{SOUND}entity.experience_orb.pickup master @s ~ ~ ~ 1 1.2")
                prev_rec = rec
                if vit.get("health") is not None and vit["health"] <= 0:
                    end_reason = "died"
                    break
                if landed_at is not None and y < course["goal"]["pad"][1][1] + 0.5:
                    # Touched the pad and slid or flew on off its edge: a landing is one that stays on the pad.
                    end_reason = "overshot"
                    break
                if landed_at is not None and now - landed_at >= LAND_SETTLE_S:
                    break
                if landed_at is None and y < course.get("floorY", -64):
                    end_reason = "fell"
                    break
                if landed_at is None and now >= next_hud:
                    tgt = cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"]
                    dist = math.dist((x, y, z), tgt)
                    name = f"Ring {reached + 1}/{len(cps)}" if reached < len(cps) else "Land on the pad"
                    await r.cmd(f"title @s actionbar \"{name}  {dist:.0f} blocks  rockets {rec['rk']}  "
                                f"{now:.1f} s\"")
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
            if course["kind"] == "route" and now >= next_hud:
                tgt = cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"]
                dist = math.hypot(x - tgt[0], z - tgt[2])
                name = f"Checkpoint {reached + 1}/{len(cps)}" if reached < len(cps) else "Goal"
                await r.cmd(f"title @s actionbar \"{name}  {dist:.0f} blocks  {now:.1f} s\"")
                next_hud = now + 0.5
            if args.driver == "bot" and combat and now >= next_status:
                ended = await action_ended(r, action_id)
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
            elif args.driver == "bot" and clear and now >= next_status:
                # The order's own state says when it ended. Ended with the volume all air, the next sample ends the
                # run cleared; ended with blocks left, the bot gave up on them.
                ended = await action_ended(r, action_id)
                if ended and await cells_cleared(r, course["volume"]) < target:
                    end_reason = "bot-stopped: " + ended
                    break
                next_status = now + 0.5
            elif args.driver == "bot" and build and now >= next_status:
                # As for clearing: ended with the blueprint built, the next sample ends the run built.
                ended = await action_ended(r, action_id)
                if ended and await cells_built(r, course, blueprint) < target:
                    end_reason = "bot-stopped: " + ended
                    break
                next_status = now + 0.5
            elif args.driver == "bot" and farm and now >= next_status:
                # As for clearing: ended with every cell replanted, the next sample ends the run farmed.
                ended = await action_ended(r, action_id)
                if ended and (await plot_state(r, course, crop_cells))[0] < target:
                    end_reason = "bot-stopped: " + ended
                    break
                next_status = now + 0.5
            elif args.driver == "bot" and elytra and landed_at is None and now >= next_status:
                # An order can end before the body is on the pad: it gave up on a stall, or the bot counted a ring
                # passed that the course did not. The order is sent again, with the rings still ahead, only once it has
                # been over for a moment, and each time is written to the recording.
                ended = await action_ended(r, action_id)
                if not ended:
                    order_ended_at = None
                elif order_ended_at is None:
                    order_ended_at = now
                elif now - order_ended_at >= 1.5 and reissues >= REISSUE_MAX:
                    end_reason = "bot-stopped: " + ended
                    break
                elif now - order_ended_at >= 1.5:
                    reissues += 1
                    f.write(json.dumps({"reissue": reissues, "t": round(now, 3), "ring": reached + 1,
                                        "after": ended}) + "\n")
                    print(f"[run] elytraFly ended at {now:.1f}s before ring {reached + 1} ({ended}); re-issued",
                          flush=True)
                    order_ended_at = None
                    action_id = await order_fly(r, fly_order(course, reached))
                next_status = now + 0.25
            elif args.driver == "bot" and not elytra and now >= next_status and now > 2:
                st = (await r.call("mc.bot.status")).get("status", {})
                if not st.get("busy", True):
                    end_reason = "bot-stopped: " + str(st.get("lastProcessEnd"))
                    break
                next_status = now + 1.0
            if now > course["maxSeconds"]:
                break
            await asyncio.sleep(max(0.0, 1 / HZ - (time.monotonic() - tick)))
        # A combat run's time is the sample that saw the last mob dead, not the moment the loop got here.
        # So is a mining run's the sample that saw the last ore in the bag, and a clearing run's the one that saw the
        # volume all air, a building run's the one that saw the blueprint built, and a farming run's the one that saw
        # every crop cell replanted. An elytra run's is the sample that saw the body on the pad.
        total = (splits[-1] if end_reason in ("cleared", "collected", "built", "farmed")
                 else landed_at if end_reason == "landed" else time.monotonic() - t0)
        if args.driver == "bot":
            await r.call("mc.bot.cancel")
        # The events are read every half second and the run ends on a sample, so the blocks placed or broken since the
        # last read, the last ones of a build among them, are read once more here. They belong to the last sample.
        cursor, evs = await block_events(r, cursor, tinted)
        if evs:
            f.write(json.dumps({"lateEvents": evs}) + "\n")
        if restore:
            await r.call("mc.bot.setting", {"op": "patch", "values": restore})
        colour = "green" if end_reason in ("arrived", "cleared", "collected", "built", "farmed", "landed") else "red"
        await r.cmd("title @s times 0 60 10")
        await r.cmd(f"title @s title {{\"text\":\"{total:.1f} s\",\"color\":\"{colour}\"}}")
        await r.cmd(f"title @s subtitle {json.dumps(end_reason, ensure_ascii=False)}")
        end = {"end": end_reason, "t": round(total, 3), "splits": splits, "firstMove": moved_at}
        if combat:
            end.update(kills=reached, reissues=reissues)
            await clear_arena(r, course)
        if mine:
            end.update(ores=reached)
        if clear:
            end.update(cleared=done)
        if build:
            end.update(built=done)
        if farm:
            # The farmland once more after the last sample: a landing in the last moment shows here.
            _, trampled = await plot_state(r, course, crop_cells)
            end.update(replanted=done, trampled=[list(c) for c in trampled])
        if elytra:
            end.update(rings=reached, misses=misses, reissues=reissues)
        f.write(json.dumps(end) + "\n")
        f.close()
        more = f" kills={reached}/{wave} reissues={reissues}" if combat else ""
        more += f" ores={reached}/{target}" if mine else ""
        more += f" cleared={done}/{target}" if clear else ""
        more += f" built={done}/{target}" if build else ""
        more += f" replanted={done}/{target}" if farm else ""
        more += f" rings={reached}/{len(cps)} misses={misses} reissues={reissues}" if elytra else ""
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
        elif "lateEvents" in rec:
            if rows:
                rows[-1]["ev"] = rows[-1].get("ev", []) + rec["lateEvents"]
        elif "reissue" not in rec and "miss" not in rec:
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


def flying_contacts(rows, until):
    """Wall contacts in the air on an elytra run: each run of horizontalCollision samples while the body glides or is
    off the ground, up to the touchdown at `until`. A ring's frame counts as a wall; so does the tower. A bump on the pad
    after touchdown is not counted, and in the air nothing is a step to climb, so nothing is a graze."""
    bumps, touching = 0, False
    for r in rows:
        hit = bool(r.get("hc")) and (bool(r.get("fly")) or not r.get("g")) and r["t"] <= until
        bumps += hit and not touching
        touching = hit
    return bumps


def ring_misses(meta, rows):
    """Rings an elytra run missed, from the recording: the run checks each sample's move against the ring that was
    next when the sample was taken, which is the ring the sample's own `cp` names."""
    rings, misses, armed = meta["rings"], 0, True
    for a, b in zip(rows, rows[1:]):
        if b["cp"] < len(rings):
            _, missed, armed = ring_step(rings[b["cp"]], a, b, armed)
            misses += missed
    return misses


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
    are in. Digging through that block is the way to a hidden ore; the floor and the walls around it are not.

    A clearing course has no target blocks and its work volume is the volume to clear, so the same rule counts every
    block broken outside it. Unless the course allows placing, every cell a block was placed in counts too, inside the
    volume or out: the task is only breaking, and a placed block is one more to break or a change to the border."""
    targets = set(meta["blocks"])
    cells = set()
    placed = set()
    for r in rows:
        for kind, pos, block in r.get("ev", []):
            # The payload names the block that was there; a state suffix would not change which block it was.
            ore = str(block).split("[")[0] in targets
            if kind == "block.break" and not ore and not in_box(pos, meta["workVolume"]):
                cells.add((pos["x"], pos["y"], pos["z"]))
            if kind == "block.place":
                placed.add((pos["x"], pos["y"], pos["z"]))
    return len(cells) + (len(placed) if meta.get("kind") == "clear" and not meta.get("placeAllowed") else 0)


def wrong_built(meta, rows):
    """Cells a building run got wrong: a block placed outside the blueprint, a block placed in a blueprint cell that is
    not the blueprint's block there, or a block broken that was not placed during the run, such as the floor or the
    copy. Breaking a block placed during the run is the way to fix a mistake: it costs time, not an error, and the
    mistake it fixes still counts. Each cell counts once, however many times it went wrong."""
    want = {(x, y, z): b for x, y, z, b in meta["blueprint"]}
    placed, wrong = set(), set()
    for r in rows:
        for kind, pos, block in r.get("ev", []):
            cell = (pos["x"], pos["y"], pos["z"])
            if kind == "block.place":
                placed.add(cell)
                # The payload is the placed block's id; a state suffix would not change which block it is.
                if want.get(cell) != str(block).split("[")[0]:
                    wrong.add(cell)
            elif kind == "block.break" and cell not in placed:
                wrong.add(cell)
    return len(wrong)


def wrong_farmed(meta, rows, end):
    """Cells a farming run got wrong: a block broken that is not the crop, such as the farmland, the water's rim or the
    floor; a block placed anywhere but a crop cell, or of another kind; and a crop cell whose farmland was trampled to
    dirt, read from the samples and once more at the end. Harvesting a crop that is not ripe is not one: every crop is
    ripe at the start and nothing grows, so an unripe crop is one the driver planted, and breaking it undoes the
    driver's own placement, as on a building course. It costs time, and the seed. Each cell counts once."""
    crop, plot = meta["crop"], {tuple(c) for c in meta["plot"]}
    wrong = set()
    for r in rows:
        for kind, pos, block in r.get("ev", []):
            cell = (pos["x"], pos["y"], pos["z"])
            # The payload is the block's id; a state suffix would not change which block it is.
            is_crop = str(block).split("[")[0] == crop
            if kind == "block.break" and not is_crop:
                wrong.add(cell)
            if kind == "block.place" and (cell not in plot or not is_crop):
                wrong.add(cell)
        # A trampled crop cell is scored by its farmland's cell, under it.
        wrong.update((x, y - 1, z) for x, y, z in r.get("trampled", []))
    wrong.update((x, y - 1, z) for x, y, z in (end or {}).get("trampled", []))
    return len(wrong)


def analyse(path):
    meta, rows, end, splits = load(path)
    combat = meta.get("kind") == "combat"
    if cleared_in_range(meta, end, splits):
        end = {**end, "end": "cleared", "t": splits[-1]}
        rows = [q for q in rows if q["t"] <= splits[-1]]   # the re-sent orders after it are not the run
    mine = meta.get("kind") == "mine"
    clear = meta.get("kind") == "clear"
    build = meta.get("kind") == "build"
    farm = meta.get("kind") == "farm"
    elytra = meta.get("kind") == "elytra"
    # None has checkpoints to go back from: a fight backs off from a mob, a miner goes from one ore to the next, and
    # clearing, building and farming walk around the work from one face or row to another. An elytra run turns wide
    # between rings, and a missed ring is counted as one instead.
    roaming = combat or mine or clear or build or farm or elytra
    targets = None if roaming else [tuple(c) for c in meta["checkpoints"]] + [tuple(meta["goal"]["pos"])]
    finished = ("cleared" if combat or clear else "collected" if mine else "built" if build else "farmed" if farm
                else "landed" if elytra else "arrived")
    out = {"file": os.path.basename(path), "label": meta["label"], "driver": meta["driver"],
           "ok": bool(end) and end["end"] == finished,
           "time": end["t"] if end else (rows[-1]["t"] if rows else 0),
           "splits": splits, "firstMove": end.get("firstMove") if end else None,
           "kills": len(splits), "reissues": (end or {}).get("reissues", 0),
           "wave": meta.get("mobs"), "seed": meta.get("seed")}
    # A slow spell with one of these during it is work, not a stall. Building stands still to place as mining does to
    # break, so on a building course a placement counts too, and so does planting a seed on a farming course.
    work = ("block.break", "block.place") if build or farm else ("block.break",)
    breaks = [r["t"] for r in rows for e in r.get("ev", []) if e[0] in work]
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
    if clear:
        out["cleared"] = (end or {}).get("cleared", rows[-1].get("cleared", 0) if rows else 0)
    if build:
        out["built"] = (end or {}).get("built", rows[-1].get("built", 0) if rows else 0)
    if farm:
        out["replanted"] = (end or {}).get("replanted", rows[-1].get("replanted", 0) if rows else 0)
    if mine or clear or build or farm:
        out["wrong"] = (wrong_built(meta, rows) if build else wrong_farmed(meta, rows, end) if farm
                        else wrong_blocks(meta, rows))
        out["errors"] += out["wrong"]
    if elytra:
        # In the air nothing stands still, so there are no stalls, and no step to graze. The errors are the wall
        # contacts in flight, the rings missed and the damage, a hard landing's included.
        rockets = [q["rk"] for q in rows if q.get("rk") is not None]
        out.update(stalls=None, stall_t=None, grazes=None, bumps=flying_contacts(rows, out["time"]),
                   misses=ring_misses(meta, rows), rings=len(splits),
                   rockets=rockets[0] - min(rockets) if rockets else None)
        out["errors"] = out["bumps"] + out["misses"] + hurt_n
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
    combat, mine, clear, build = is_combat(course), is_mine(course), is_clear(course), is_build(course)
    farm = is_farm(course)
    if combat:
        cols += (("kills", "{}"), ("reissues", "{}"))
    if mine:
        cols += (("wrong", "{}"), ("ores", "{}"))
    if clear:
        cols += (("wrong", "{}"), ("cleared", "{}"))
    if build:
        cols += (("wrong", "{}"), ("built", "{}"))
    if farm:
        cols += (("wrong", "{}"), ("replanted", "{}"))
    if is_elytra(course):
        cols += (("misses", "{}"), ("rings", "{}"), ("rockets", "{}"), ("reissues", "{}"))
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
    # On a mining course a leg ends at an ore in the bag, and the last ore ends the run. On a clearing course a leg ends
    # at each quarter of the volume cleared, and the last quarter ends the run; on a building course, at each quarter of
    # the blueprint built, and on a farming course at each quarter of the crop cells replanted.
    legs = (max(r["wave"] or 0 for r in runs) if combat else course["mine"]["count"] if mine
            else QUARTERS if clear or build or farm else len(course["checkpoints"]) + 1)
    print("\nleg      human-best   bot-median   bot-worst")
    for i in range(legs):
        def leg(r):
            marks = [0.0] + r["splits"] + ([] if combat or mine or clear or build or farm else [r["time"]])
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
