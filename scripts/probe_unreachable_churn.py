#!/usr/bin/env python3
"""gap #49-③ probe: goto an UNREACHABLE goal (60 blocks up, place/break disabled)
and measure how the walker ends the journey.

PASS (fixed) criteria, asserted at the end:
  1. the goto slot goes idle within HARD_WAIT seconds AND in <= MAX_END_S seconds;
  2. lastError names unreachability (contains 'no route progress' / 'unreachable'),
     not the generic 1200-tick stall text;
  3. the walker burned <= MAX_FULL_SEARCHES full-budget searches (STOP cause=maxNodes).
Run from scripts/. Prints a verdict line: PROBE=RED|GREEN plus the raw numbers.
"""
import asyncio, json, re, sys, time

sys.path.insert(0, ".claude/skills/agent-driver-rpc")
import websockets

LOG = "../fabric/run/rc-gap49.log"
GOAL = {"pos": {"x": 800, "y": 0, "z": 800}}   # 60 above the y=-60 superflat floor
HARD_WAIT = 150          # give the current (broken) build room to show itself
MAX_END_S = 40           # fixed build must end the journey this fast
MAX_FULL_SEARCHES = 6    # walkerFutileSearchCap(5) futile + 1 baseline completion
URI = "ws://127.0.0.1:39801/rpc"


async def call(ws, method, params=None, rid=[0]):
    rid[0] += 1
    await ws.send(json.dumps({"id": rid[0], "method": method, "params": params or {}}))
    while True:
        msg = json.loads(await ws.recv())
        if msg.get("id") == rid[0]:
            return msg.get("result", msg)


async def main():
    log_start = 0
    try:
        with open(LOG, "rb") as f:
            f.seek(0, 2); log_start = f.tell()
    except FileNotFoundError:
        pass

    async with websockets.connect(URI, max_size=8 * 1024 * 1024, ping_interval=None) as ws:
        t0 = time.time()
        r = await call(ws, "mc.bot.goto", GOAL)
        print(f"[probe] goto started: {json.dumps(r)[:200]}")
        end_s, last = None, {}
        while time.time() - t0 < HARD_WAIT:
            await asyncio.sleep(2)
            st = await call(ws, "mc.bot.status")
            slot = st.get("user") or st.get("goto") or st
            active = slot.get("active", st.get("active"))
            if not active:
                end_s = time.time() - t0
                last = st
                break
        if end_s is None:
            last = await call(ws, "mc.bot.status")

    text = ""
    try:
        with open(LOG, "rb") as f:
            f.seek(log_start); text = f.read().decode("utf-8", "replace")
    except FileNotFoundError:
        pass
    # A "full" search on live ends at cause=maxMs(2000) (the wall-clock cap trips
    # before the 100k node cap); big-slice rigs end at maxNodes(100000). Count both.
    full = len(re.findall(r"STOP cause=(?:maxMs\(\d+\)|maxNodes\(\d{5,}\))", text))
    begins = text.count("search-begin")
    rejects = text.count("reject mis-anchored")
    lb = json.dumps(last, ensure_ascii=False)
    err = ""
    m = re.search(r'"lastError"\s*:\s*"([^"]*)"', lb)
    if m: err = m.group(1)

    ended = end_s is not None
    reason_ok = bool(re.search(r"no route progress|unreachable", err, re.I))
    fast_ok = ended and end_s <= MAX_END_S
    searches_ok = full <= MAX_FULL_SEARCHES
    verdict = "GREEN" if (fast_ok and reason_ok and searches_ok) else "RED"
    print(f"[probe] ended={ended} end_s={None if end_s is None else round(end_s,1)} "
          f"fullSearches={full} searchBegins={begins} rejects={rejects} lastError={err!r}")
    print(f"PROBE={verdict} (fast_ok={fast_ok} reason_ok={reason_ok} searches_ok={searches_ok})")


asyncio.run(main())
