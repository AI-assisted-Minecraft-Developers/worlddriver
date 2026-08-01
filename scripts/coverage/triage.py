#!/usr/bin/env python3
"""Group uncovered movement-package lines into mechanism families for gap triage.

Reads coverage-out/uncovered-movement.txt (from report.py) and, for every
uncovered/partial line in the WalkerTick*/Walker sources, attributes it to the
nearest enclosing mechanism: the closest preceding `BotConfig.<flag>` gate or
section banner comment. Prints families sorted by uncovered weight so scene
work can start with the biggest holes.
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "common/src/main/java/net/magicterra/worlddriver/bot/movement")
DUMP = os.path.join(ROOT, "coverage-out/uncovered-movement.txt")
ONLY = re.compile(r"movement/(Walker\w*|Walker)\.java")

FLAG = re.compile(r"BotConfig\.(walker\w+|pathfinder\w+|descentCameraDecouple|allowParkour\w*|allowBreak|allowPlace|pathArchive|pathDebug|breakTimeoutTicks)")
BANNER = re.compile(r"^\s*// ([A-Z][A-Za-z0-9 \-+/#§']{8,60})")

def families_for(path):
    lines = open(path).read().split("\n")
    fam_at = {}
    cur = "(top)"
    for i, ln in enumerate(lines, 1):
        m = FLAG.search(ln)
        if m:
            cur = "flag:" + m.group(1)
        else:
            b = BANNER.match(ln)
            if b:
                cur = b.group(1).strip()
        fam_at[i] = cur
    return fam_at

def main():
    fam_weight = defaultdict(int)
    fam_where = defaultdict(list)
    cur_file = None
    fam_at = {}
    for raw in open(DUMP):
        raw = raw.rstrip()
        if raw.startswith("== "):
            m = ONLY.search(raw)
            cur_file = None
            if m:
                p = os.path.join(SRC, m.group(1) + ".java")
                if os.path.exists(p):
                    cur_file = os.path.basename(p)
                    fam_at = families_for(p)
            continue
        if cur_file is None:
            continue
        m = re.match(r"\s+miss (\d+)-(\d+)", raw)
        w = 0
        lo = None
        if m:
            lo, hi = int(m.group(1)), int(m.group(2))
            w = hi - lo + 1
        else:
            m = re.match(r"\s+branch (\d+) \(missed (\d+) of", raw)
            if m:
                lo, w = int(m.group(1)), int(m.group(2))
        if lo is None:
            continue
        fam = fam_at.get(lo, "(?)")
        key = "%s :: %s" % (cur_file, fam)
        fam_weight[key] += w
        if len(fam_where[key]) < 4:
            fam_where[key].append(lo)
    ranked = sorted(fam_weight.items(), key=lambda kv: -kv[1])
    total = sum(fam_weight.values())
    print("uncovered weight total (lines+missed branches): %d across %d families\n" % (total, len(ranked)))
    for key, w in ranked:
        print("%5d  %-95s lines~%s" % (w, key[:95], fam_where[key]))

if __name__ == "__main__":
    main()
