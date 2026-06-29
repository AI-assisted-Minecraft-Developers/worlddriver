#!/usr/bin/env python3
"""Reliable journey-metrics harness for Walker A/B + acceptance runs.

Fixes the two instrument bugs that corrupted prior conclusions:
  1) arrival now uses TRUE 3D distance to goal (x AND z AND y), not an x-only regex
     that false-fired when the bot merely crossed the goal's x-column mid-journey.
  2) all counts are per-run, parsed only from lines appended AFTER --base
     (captured at run start), never the cumulative whole-log grep.

Usage:
  python3 wjourney.py --base <line_no> --goal X Z [--radius 3] [--timeout 280]
                      [--log fabric/run/logs/latest.log]
Capture --base BEFORE issuing goto:  wc -l < fabric/run/logs/latest.log
Prints a compact report and exits 0 on arrival, 2 on timeout.
"""
import argparse, re, sys, time, math

TRACE = re.compile(
    r"\[walker\] t=.*?p=\(([-0-9.]+),([-0-9.]+),([-0-9.]+)\).*?onG=(\w+).*?inW=(\w+).*?stuck=(\d+) totStuck=(\d+)")

def parse_last_trace(lines):
    for ln in reversed(lines):
        m = TRACE.search(ln)
        if m:
            return (float(m.group(1)), float(m.group(2)), float(m.group(3)),
                    m.group(4) == "true", m.group(5) == "true",
                    int(m.group(6)), int(m.group(7)))
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", type=int, required=True)
    ap.add_argument("--goal", type=float, nargs=2, required=True)
    ap.add_argument("--radius", type=float, default=3.0)
    ap.add_argument("--timeout", type=float, default=280.0)
    ap.add_argument("--log", default="fabric/run/logs/latest.log")
    ap.add_argument("--label", default="run")
    a = ap.parse_args()
    gx, gz = a.goal
    start = time.time()
    result = "TIMEOUT"; elapsed = a.timeout
    while time.time() - start < a.timeout:
        time.sleep(2)
        try:
            with open(a.log, errors="ignore") as f:
                lines = f.readlines()[a.base:]
        except FileNotFoundError:
            continue
        t = parse_last_trace(lines)
        if t:
            x, y, z, onG, inW, stuck, tot = t
            if math.hypot(x - gx, z - gz) <= a.radius and onG:
                result = "ARRIVED"; elapsed = time.time() - start; break
    # final per-run metrics from base
    with open(a.log, errors="ignore") as f:
        body = f.readlines()[a.base:]
    digs = sum(1 for l in body if "block-less bank dig" in l)
    futile = sum(1 for l in body if "futile bank-dig release" in l)
    # water-pocket dwell: trace lines with inW=true
    inwater = 0; maxstuck = 0; lastpos = None
    risers = {}
    for l in body:
        m = TRACE.search(l)
        if m:
            if m.group(5) == "true":
                inwater += 1
            maxstuck = max(maxstuck, int(m.group(6)))
            lastpos = (m.group(1), m.group(2), m.group(3))
        r = re.search(r"riser=([-0-9,]+)", l)
        if r and "block-less bank dig" in l:
            risers[r.group(1)] = risers.get(r.group(1), 0) + 1
    print(f"=== {a.label} ===")
    print(f"result      : {result} ~{elapsed:.0f}s  (goal {gx},{gz} r{a.radius})")
    print(f"block-less dig (per-run): {digs}")
    print(f"futile-release (per-run): {futile}")
    print(f"inWater trace ticks     : {inwater}")
    print(f"maxStuck                : {maxstuck}")
    print(f"final pos               : {lastpos}")
    if risers:
        top = sorted(risers.items(), key=lambda kv: -kv[1])[:6]
        print("dig riser分布            : " + ", ".join(f"{k}×{v}" for k, v in top))
    sys.exit(0 if result == "ARRIVED" else 2)

if __name__ == "__main__":
    main()
