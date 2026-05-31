#!/usr/bin/env python3
"""Scan surface heights along a line and find a route with a tall peak in the
MIDDLE (endpoints lower than midpoint) — for testing elytra obstacle avoidance
against real terrain. Prints a height profile + a suggested start/goal/altitude
that flies straight THROUGH the peak."""
import asyncio, sys
import react_smoke as rs
import websockets


async def is_solid(rpc, x, y, z):
    r = await rpc.call("mc.query", {"q": "blocks", "center": {"x": x, "y": y, "z": z},
                                    "filter": {"in_radius": 0}})
    if not r:
        return False
    t = r[0].get("type", "") if isinstance(r, list) else ""
    return ("air" not in t) and ("water" not in t)


async def height(rpc, x, z, lo=62, hi=240):
    """Highest solid y in [lo,hi] via binary search (terrain ~monotonic)."""
    if not await is_solid(rpc, x, lo, z):
        return None
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if await is_solid(rpc, x, mid, z):
            lo = mid
        else:
            hi = mid
    return lo


async def main():
    axis = sys.argv[1] if len(sys.argv) > 1 else "x"   # scan along x or z
    fixed = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    lo = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    hi = int(sys.argv[4]) if len(sys.argv) > 4 else 400
    step = int(sys.argv[5]) if len(sys.argv) > 5 else 20

    port = await rs.discover_port()
    ws = await websockets.connect(f"ws://127.0.0.1:{port}/rpc", max_size=8 * 1024 * 1024, ping_interval=None)
    async with ws:
        rpc = rs.Rpc(ws)
        prof = []
        for c in range(lo, hi + 1, step):
            x, z = (c, fixed) if axis == "x" else (fixed, c)
            h = await height(rpc, x, z)
            prof.append((c, h))
            bar = "#" * (((h or 60) - 60) // 4)
            print(f"  {axis}={c:>4} z/x={fixed:<4} surfaceY={str(h):>4}  {bar}")
        # find peak with lower neighbours (a real mid-route obstacle)
        best = None
        for i in range(1, len(prof) - 1):
            c, h = prof[i]
            if h is None:
                continue
            lh = prof[i - 1][1] or 60
            rh = prof[i + 1][1] or 60
            prom = h - max(lh, rh)
            if best is None or prom > best[0]:
                best = (prom, i, c, h)
        if best:
            _, i, c, h = best
            a = prof[max(0, i - 3)][0]
            b = prof[min(len(prof) - 1, i + 3)][0]
            alt = h - 12
            if axis == "x":
                print(f"\nPEAK at x={c} surfaceY={h}. Suggested straight-through flight:")
                print(f"  start tp: {a} {alt} {fixed}   goal: {{'x':{b},'y':{alt},'z':{fixed}}}  (peak top {h} > alt {alt})")
            else:
                print(f"\nPEAK at z={c} surfaceY={h}. Suggested straight-through flight:")
                print(f"  start tp: {fixed} {alt} {a}   goal: {{'x':{fixed},'y':{alt},'z':{b}}}  (peak top {h} > alt {alt})")


if __name__ == "__main__":
    asyncio.run(main())
