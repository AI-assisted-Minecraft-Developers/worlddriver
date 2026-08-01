#!/usr/bin/env python3
"""Chained random-position journey runner with stall detection.

Fires goto (xz goal) from the bot's CURRENT position (no TP — avoids cliff-edge
artifacts), polls player position ~1Hz, detects arrival (XZ within 2.5 + onGround)
or stall windows (>=2 consecutive ~1s polls with <0.6 block XZ movement while not
arrived). pathArchive stays ON so each journey leaves a replayable archive.
"""
import asyncio, json, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent / ".claude/skills/worlddriver-rpc"))
import websockets

PORT = int((Path(__file__).parent.parent / "fabric/run/worlddriver-rpc.port").read_text().strip())
URI = f"ws://127.0.0.1:{PORT}/rpc"

GOALS = json.loads(sys.argv[1]) if len(sys.argv) > 1 else [
    {"x": 2480, "z": 1640}, {"x": 2480, "z": 1860}, {"x": 2280, "z": 1850},
]

_rid = [0]
async def call(ws, method, params=None, timeout=35):
    _rid[0] += 1; rid = _rid[0]
    await ws.send(json.dumps({"id": rid, "method": method, "params": params or {}}))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
        if msg.get("id") != rid:
            continue
        if "error" in msg:
            return None, msg["error"]
        return msg.get("result"), None

async def pos(ws):
    r, e = await call(ws, "mc.observe.player")
    if e or not r: return None
    p = r["pos"]; return (p["x"], p["y"], p["z"], r.get("onGround", False))

async def run():
    async with websockets.connect(URI, max_size=16*1024*1024, ping_interval=None) as ws:
        await call(ws, "mc.bot.setting", {"pathArchive": True, "walkerDebug": True})
        for ji, g in enumerate(GOALS):
            tag = f"J{ji+1}"
            start = await pos(ws)
            print(f"\n=== {tag} goal=({g['x']},{g['z']}) start=({start[0]:.0f},{start[1]:.0f},{start[2]:.0f}) ===", flush=True)
            await call(ws, "mc.bot.goto", {"xz": g, "awaitMs": 1000})
            t0 = time.time(); last = await pos(ws); arrived_polls = 0
            stall_run = 0; stalls = []; maxstall = 0.0; polls = 0
            while True:
                await asyncio.sleep(1.0)
                p = await pos(ws)
                if p is None: continue
                polls += 1
                dxz = ((p[0]-last[0])**2 + (p[2]-last[2])**2) ** 0.5
                gdist = ((p[0]-g['x'])**2 + (p[2]-g['z'])**2) ** 0.5
                arrived = gdist < 2.5 and p[3]
                if arrived:
                    arrived_polls += 1
                    if arrived_polls >= 2:
                        print(f"  {tag} ARRIVED t={time.time()-t0:.1f}s gdist={gdist:.1f} stalls={len(stalls)} maxstall={maxstall:.1f}s", flush=True)
                        break
                else:
                    arrived_polls = 0
                    if dxz < 0.6:
                        stall_run += 1
                        if stall_run == 2:
                            stalls.append(time.time()-t0)
                        if stall_run >= 2:
                            maxstall = max(maxstall, stall_run * 1.0)
                    else:
                        stall_run = 0
                # progress trace every 5 polls
                if polls % 5 == 0:
                    print(f"  {tag} t={time.time()-t0:.0f}s p=({p[0]:.0f},{p[1]:.0f},{p[2]:.0f}) gdist={gdist:.0f} mov={dxz:.1f} stallRun={stall_run}", flush=True)
                last = p
                if time.time()-t0 > 180:
                    print(f"  {tag} TIMEOUT(180s) gdist={gdist:.1f} stalls={len(stalls)} maxstall={maxstall:.1f}s", flush=True)
                    break
            # report stall windows
            if stalls:
                print(f"  {tag} STALL windows (s into journey): {[f'{s:.0f}' for s in stalls]}", flush=True)

asyncio.run(run())
