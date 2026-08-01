#!/usr/bin/env python3
"""Source-budget gate: caps both FILE length and METHOD length in hand-written Java.

Run from the repo root (exit 1 on violation):

    python3 scripts/check_source_budget.py       # 'python' on the Windows checkout

FILE budget (3000 lines)
------------------------
Walker.java grew to 5849 lines before being split into the WalkerTick* phase classes
(task#94). The cap keeps the next monolith from re-forming silently.

METHOD budget — why this exists
-------------------------------
The file cap alone measured the wrong unit. The problem it was written to prevent is
named in its own original rationale: "a single ~4650-line tick method". Splitting
Walker.java satisfied the file cap by MOVING TEXT — the giant method survived. Nine
WalkerTick* phase classes between them declare ten methods total, and seven of those
files have no helper method at all. A cap you can satisfy by relocating the problem is
not a cap.

So methods are budgeted too, against a grandfather list rather than a flat limit:

  * a method NOT in GRANDFATHERED may not exceed METHOD_LIMIT;
  * a method IN GRANDFATHERED may not exceed the length recorded for it.

That freezes today's offenders (they may shrink, never grow) without demanding a risky
refactor first, and it makes the eventual decomposition measurable: every entry deleted
from GRANDFATHERED is progress that cannot silently regress. Update an entry DOWNWARD
when you shrink a method. Adding a new entry, or raising an existing one, should be a
deliberate reviewed commit — not a reflex to make the gate quiet.

Measurement is brace balance from a signature line to its closing brace, so it counts
exactly what a reader has to hold in their head: comments and blanks included. String
literals, char literals and comments are blanked before brace counting.

Excluded from the METHOD budget only: the testmod scene sources. Arena bodies are long
by nature and their real problem is duplicated scaffolding, not control-flow depth.
"""
import os
import re
import sys

LIMIT = 3000
METHOD_LIMIT = 200

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOTS = [
    "common/src",
    "fabric/src",
    "neoforge/src",
    "testkit-common/src",
]

METHOD_EXEMPT_DIRS = ("common/src/testmod",)

# "File.java::method" -> max allowed lines. Measured 2026-07-26; all of these predate the
# gate. The five WalkerTick* entries are the original ~4650-line tick method, redistributed
# rather than decomposed — they are the backlog this list exists to shrink.
GRANDFATHERED = {
    "WalkerTickDrive.java::run": 1262,
    "WalkerTickClimb.java::run": 1008,
    "WalkerTickAim.java::run": 851,
    # 794 -> 408 by deleting 100 branches that duplicated the method's own reflective
    # write path. The remainder is clamps, aliases, list validation and 3 side-effecting
    # keys; shrinking it further means moving the clamps to a table.
    "SettingsCommand.java::apply": 408,
    "BotTools.java::tools": 762,
    "WalkerTickProgress.java::run": 734,
    "WalkerTickStallDetect.java::run": 389,
    "WalkerTickSearch.java::run": 278,
    "AgentApi.java::AgentApi": 272,
    "ClientTools.java::tools": 250,
    "WalkerTickPrelude.java::run": 245,
    "ElytraProcess.java::tick": 243,
    "WalkerTickEdgeGuards.java::run": 241,
    "BotApiImpl.java::clientTick": 239,
    "ObserveActionTools.java::tools": 234,
    "WalkerTickRepath.java::run": 211,
    "Walker.java::adoptPath": 206,
}

# A member declaration that opens a body. Deliberately conservative: it must begin with a
# modifier/type token and carry a parameter list.
_SIG = re.compile(
    r"^(?:public|private|protected|static|final|synchronized|abstract|default|native|strictfp"
    r"|void|boolean|byte|char|short|int|long|float|double|var"
    r"|[A-Z][\w.<>,\[\]?]*)\b.*\("
)
_NAME = re.compile(r"([A-Za-z_$][\w$]*)\s*\(")


def _blank_literals(line):
    """Blank string/char literals and drop a line comment, so their braces don't count."""
    out, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        if c in "\"'":
            quote = c
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == quote:
                    i += 1
                    break
                i += 1
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def methods(path):
    """Yield (name, start_line, length) for every method body in a Java file."""
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().split("\n")
    depth, start, name, in_block = 0, None, None, False
    for i, raw in enumerate(lines):
        line = raw
        if in_block:
            end = line.find("*/")
            if end < 0:
                continue
            line, in_block = line[end + 2:], False
        while True:
            begin = line.find("/*")
            if begin < 0:
                break
            end = line.find("*/", begin + 2)
            if end < 0:
                line, in_block = line[:begin], True
                break
            line = line[:begin] + " " + line[end + 2:]
        code = _blank_literals(line)
        s = code.strip()
        if (depth == 1 and start is None and not s.endswith(";")
                and _SIG.match(s) and "=" not in s.split("(")[0]):
            m = _NAME.search(s)
            if m:
                name, start = m.group(1), i
        new_depth = depth + code.count("{") - code.count("}")
        if start is not None and new_depth <= 1 and i > start:
            yield name, start + 1, i - start + 1
            start, name = None, None
        depth = new_depth


def main():
    file_violations, method_violations, seen = [], [], set()

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
                rel = os.path.relpath(path, ROOT).replace(os.sep, "/")

                with open(path, "rb") as f:
                    n = sum(1 for _ in f)
                if n > LIMIT:
                    file_violations.append((n, rel))

                if any(rel.startswith(d) for d in METHOD_EXEMPT_DIRS):
                    continue
                for name, line, length in methods(path):
                    key = "%s::%s" % (fn, name)
                    allowed = GRANDFATHERED.get(key)
                    if allowed is not None:
                        seen.add(key)
                        if length > allowed:
                            method_violations.append((
                                length, "%s:%d" % (rel, line), name,
                                "grandfathered at %d — it may shrink, not grow" % allowed))
                    elif length > METHOD_LIMIT:
                        method_violations.append((
                            length, "%s:%d" % (rel, line), name,
                            "limit %d for methods not on the grandfather list" % METHOD_LIMIT))

    failed = False
    if file_violations:
        failed = True
        print("source-budget gate FAILED: files over %d lines:" % LIMIT)
        for n, rel in sorted(file_violations, reverse=True):
            print("  %6d  %s" % (n, rel))
        print("Split the file (see the WalkerTick* mechanical phase split for the pattern).")

    if method_violations:
        failed = True
        print("source-budget gate FAILED: methods over budget:")
        for length, where, name, why in sorted(method_violations, reverse=True):
            print("  %6d  %s  %s() — %s" % (length, where, name, why))
        print("Extract helpers; a method is the unit a reader has to hold in their head.")

    stale = sorted(set(GRANDFATHERED) - seen)
    if stale:
        # Not a failure: an entry whose method no longer exists (renamed, split, deleted)
        # is the outcome this list exists to encourage. Say so, so it gets removed rather
        # than quietly protecting nothing.
        print("note: grandfather entries no longer found (delete them): %s" % ", ".join(stale))

    if failed:
        return 1
    print("source-budget gate OK: no Java source over %d lines, no method over budget" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
