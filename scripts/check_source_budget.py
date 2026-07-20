#!/usr/bin/env python3
"""Source-budget gate: no hand-maintained Java source file may exceed 3000 lines.

Rationale: Walker.java grew to 5849 lines (a single ~4650-line tick method)
before being split into the WalkerTick* phase classes (task#94). A hard cap
keeps the next monolith from re-forming silently. Run from the repo root:

    python3 scripts/check_source_budget.py            # exit 1 on violation

Excluded: build outputs and generated sources (none today).
"""
import os
import sys

LIMIT = 3000
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOTS = [
    "common/src",
    "fabric/src",
    "neoforge/src",
    "testkit-common/src",
]

violations = []
for src_root in SRC_ROOTS:
    base = os.path.join(ROOT, src_root)
    if not os.path.isdir(base):
        continue
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, "rb") as f:
                n = sum(1 for _ in f)
            if n > LIMIT:
                violations.append((n, os.path.relpath(path, ROOT)))

if violations:
    print("source-budget gate FAILED: files over %d lines:" % LIMIT)
    for n, rel in sorted(violations, reverse=True):
        print("  %6d  %s" % (n, rel))
    print("Split the file (see the WalkerTick* mechanical phase split for the pattern).")
    sys.exit(1)

print("source-budget gate OK: no Java source over %d lines" % LIMIT)
