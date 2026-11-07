#!/usr/bin/env python3
"""bench.py — run a course with the bot or a human at the keyboard, and judge the bot against the human.

A course is a JSON file under courses/: a start, ordered checkpoints, a goal, and how its terrain is
put back. Both drivers run the same course in the same client, with the same kit, markers and clock,
so their recordings compare directly. docs/dev/benchmark.md has the method and the pass criterion.

  markers COURSE --place | --remove   Beacons with coloured beams on the checkpoints and the goal.
  terrain COURSE --capture | --reset  Snapshot the course's terrain, or put it back after a run.
  run     COURSE --driver bot|human   Countdown, run, record one JSON line per sample.
  report  COURSE [--png]              Every recorded run, the errors in each, and the verdict.

Recordings go to fabric/run/bench/<course>/, next to the client's own output, unless --out names
another place.
"""
import argparse, asyncio, json, math, os, statistics, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".agents", "skills", "worlddriver-rpc"))
import rpc  # noqa: E402  resolve_host / resolve_port / call

import websockets  # noqa: E402

HZ = 20
OUT = os.path.join(HERE, "..", "..", "fabric", "run", "bench")
KIT = ("clear @s", "give @s minecraft:iron_pickaxe", "give @s minecraft:iron_shovel",
       "give @s minecraft:iron_axe", "give @s minecraft:dirt 64", "give @s minecraft:cobblestone 64",
       "give @s minecraft:cooked_beef 16", "give @s minecraft:water_bucket")
# The glass under a marker's feet cell tints its beam: the checkpoint to reach next is red.
NEXT, LATER, DONE, GOAL = "red_stained_glass", "white_stained_glass", "lime_stained_glass", "yellow_stained_glass"
BEAM_CLEAR = 48          # cells above a marker cleared of beam-blocking blocks
MAX_TILE = 64            # mc.world.snapshot's edge limit
# `as` alone leaves the command at the server's origin; the sound has to play where the player stands.
SOUND = "execute at @s run playsound minecraft:"
STALL_SPEED, STALL_S = 1.0, 0.5
BACKTRACK = 2.0
GRAZE_S, GRAZE_CLIMB_S, GRAZE_RISE = 0.2, 1.0, 0.9


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


def load_course(path):
    if not os.path.exists(path):
        path = os.path.join(HERE, "courses", path + ".json")
    with open(path) as f:
        c = json.load(f)
    c.setdefault("checkpoints", [])
    c.setdefault("beacons", [])
    c.setdefault("maxSeconds", 900)
    return c


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
            for c in t["commands"]:
                await r.cmd(c, as_me=False)
            print(f"[terrain] rebuilt, {len(t['commands'])} commands")
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
    async with await connect() as ws:
        r = Rpc(ws)
        await r.call("mc.bot.cancel")
        sx, sy, sz = course["start"]
        for c in (f"tp @s {sx} {sy} {sz} {course.get('yaw', 0)} 0", "effect clear @s",
                  "effect give @s minecraft:instant_health 1 10 true",
                  "effect give @s minecraft:saturation 1 10 true", *course.get("kit", KIT)):
            await r.cmd(c)
        for c in course.get("world", ("time set 1000", "weather clear")):
            await r.cmd(c, as_me=False)
        first = cps[0]["pos"] if cps else course["goal"]["pos"]
        await compass_to(r, first)
        await tint(r, course, 0)
        await asyncio.sleep(2.0)                       # chunks settle after the tp
        p = (await r.call("mc.client.player"))["pos"]
        rest = (p["x"], p["z"])
        if args.driver == "human":
            print(f"[run] waiting for the player to press sneak; {len(cps)} checkpoints, follow the red beam "
                  "and the off-hand compass", flush=True)
            await ready(r)
            p = (await r.call("mc.client.player"))["pos"]
            rest = (p["x"], p["z"])
        rest = await countdown(r, rest, course.get("title", course["id"]))
        cursor = (await r.call("mc.observe.cursor"))["cursor"]
        t0 = time.monotonic()
        if args.driver == "bot":
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
        f = open(path, "w")
        f.write(json.dumps({"meta": True, "course": course["id"], "label": label, "driver": args.driver,
                            "start": course["start"], "checkpoints": [cp["pos"] for cp in cps],
                            "goal": course["goal"], "hz": HZ, "rest": rest}) + "\n")
        reached, splits, end_reason = 0, [], "max-s"
        next_ev = next_hud = next_status = 0.0
        moved_at = None
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
            if now >= next_ev:
                page = await r.call("mc.observe.eventsSince", {"cursor": cursor, "limit": 200})
                cursor = page["cursor"]
                evs = [[e["type"], e.get("pos"), e.get("data")] for e in page["events"]
                       if e.get("type") in ("block.place", "block.break")]
                if evs:
                    rec["ev"] = evs
                next_ev = now + 0.5
            f.write(json.dumps(rec) + "\n")
            if reached < len(cps):
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
            if now >= next_hud:
                tgt = cps[reached]["pos"] if reached < len(cps) else course["goal"]["pos"]
                dist = math.hypot(x - tgt[0], z - tgt[2])
                name = f"Checkpoint {reached + 1}/{len(cps)}" if reached < len(cps) else "Goal"
                await r.cmd(f"title @s actionbar \"{name}  {dist:.0f} blocks  {now:.1f} s\"")
                next_hud = now + 0.5
            if args.driver == "bot" and now >= next_status and now > 2:
                st = (await r.call("mc.bot.status")).get("status", {})
                if not st.get("busy", True):
                    end_reason = "bot-stopped: " + str(st.get("lastProcessEnd"))
                    break
                next_status = now + 1.0
            if now > course["maxSeconds"]:
                break
            await asyncio.sleep(max(0.0, 1 / HZ - (time.monotonic() - tick)))
        total = time.monotonic() - t0
        if args.driver == "bot":
            await r.call("mc.bot.cancel")
        colour = "green" if end_reason == "arrived" else "red"
        await r.cmd("title @s times 0 60 10")
        await r.cmd(f"title @s title {{\"text\":\"{total:.1f} s\",\"color\":\"{colour}\"}}")
        await r.cmd(f"title @s subtitle {json.dumps(end_reason, ensure_ascii=False)}")
        f.write(json.dumps({"end": end_reason, "t": round(total, 3), "splits": splits,
                            "firstMove": moved_at}) + "\n")
        f.close()
        print(f"[run] {end_reason} {total:.1f}s splits={splits} → {path}", flush=True)


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
        else:
            rows.append(rec)
    return meta, rows, end, splits


def contacts(rows):
    """(bumps, grazes): each run of horizontalCollision samples is one contact. A graze is a contact that lasts
    at most GRAZE_S and is followed within GRAZE_CLIMB_S by standing at least GRAZE_RISE above the floor the body
    stood on before it: the face of a step it went on to climb. Grazes are reported but are not errors."""
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
        if short and climbed:
            grazes += 1
        else:
            bumps += 1
        i = j
    return bumps, grazes


def analyse(path):
    meta, rows, end, splits = load(path)
    targets = [tuple(c) for c in meta["checkpoints"]] + [tuple(meta["goal"]["pos"])]
    out = {"file": os.path.basename(path), "label": meta["label"], "driver": meta["driver"],
           "ok": bool(end) and end["end"] == "arrived", "time": end["t"] if end else (rows[-1]["t"] if rows else 0),
           "splits": splits, "firstMove": end.get("firstMove") if end else None}
    breaks = [r["t"] for r in rows for e in r.get("ev", []) if e[0] == "block.break"]
    stalls, stall_t, run_start = 0, 0.0, None
    backtracks, hurt, hurt_n, water, placed, broken, walked = 0, 0.0, 0, 0.0, 0, 0, 0.0
    best = None
    for a, b in zip(rows, rows[1:]):
        dt = max(b["t"] - a["t"], 1e-3)
        step = math.hypot(b["x"] - a["x"], b["z"] - a["z"])
        walked += step
        slow = step / dt < STALL_SPEED
        if slow and run_start is None:
            run_start = a["t"]
        if not slow and run_start is not None:
            span = b["t"] - run_start
            mining = any(run_start - 0.5 <= t <= b["t"] + 0.5 for t in breaks)
            if span >= STALL_S and not mining:
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
    out.update(stalls=stalls, stall_t=stall_t, bumps=bumps, grazes=grazes, backtracks=backtracks, hurt=hurt,
               hurt_n=hurt_n, water=water, placed=placed, broken=broken, walked=walked)
    out["errors"] = stalls + bumps + backtracks + hurt_n
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
    print(f"{'run':34}" + "".join(f"{k:>11}" for k, _ in cols))
    for r in runs:
        cells = []
        for k, fmt in cols:
            v = r[k]
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
    print(f"  every bot run beats human best  {'PASS' if not slow and not failed else 'FAIL ' + str(len(slow))}")
    print(f"  bot errors <= fewest human .... {'PASS' if not sloppy else 'FAIL ' + str(len(sloppy))}")
    print(f"VERDICT: {'PASS' if not (failed or slow or sloppy) else 'FAIL'}")
    # Where the time goes: each leg between checkpoints, bot median against the human best.
    legs = len(course["checkpoints"]) + 1
    print("\nleg      human-best   bot-median   bot-worst")
    for i in range(legs):
        def leg(r):
            marks = [0.0] + r["splits"] + [r["time"]]
            return marks[i + 1] - marks[i] if i + 1 < len(marks) else float("nan")
        bt = [leg(r) for r in bots if r["ok"]]
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
    p = sub.add_parser("report")
    p.add_argument("course")
    p.add_argument("--png")
    p.add_argument("--since", help="only runs whose file name sorts at or after this, e.g. 20261108-1200")
    a = ap.parse_args()
    if a.cmd == "markers":
        asyncio.run(markers(a))
    elif a.cmd == "terrain":
        asyncio.run(terrain(a))
    elif a.cmd == "run":
        asyncio.run(run(a))
    else:
        report(a)


if __name__ == "__main__":
    main()
