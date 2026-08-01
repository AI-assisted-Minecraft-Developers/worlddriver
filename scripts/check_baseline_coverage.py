#!/usr/bin/env python3
"""Gate: the testkit baseline may not silently diverge from what users actually run.

Run from the repo root (exit 1 on violation):

    python3 scripts/check_baseline_coverage.py    # 'python' on the Windows checkout

The problem
-----------
`BotConfig.applyGameTestBaseline()` re-pins a set of knobs before the scene suite runs —
it exists because the arena assertions were authored against the historical default-OFF
flag set, and flipping those flags default-ON for live play broke arenas that never
touch them (§78). Both loader entrypoints call it under `-Dstagewright.autorun`, and scenes
call `pinnedBaseline()` around 120 arenas, so EVERY t0/t1/t2 run is measured in that
configuration.

Measured 2026-07-26: all 38 assignments differ from the shipped default. So the suite
proves the bot works in a configuration no user runs, and each baselined knob is a code
path that ships enabled while CI exercises it disabled. Two of them are planner cost
weights (`pathfinderBreakCostMultiplier` 2.5→1.0, `pathfinderLogBreakTax` 3.0→1.0), so
even path SELECTION is untested at shipped weights.

Nothing detected this: the list is hand-written, referenced by no script and no test.

What this gate enforces
-----------------------
1. NO-OP — every baseline assignment must actually differ from the declared default.
   An assignment equal to the default is dead text pretending to pin something; delete it.
2. COVERAGE — every baselined field must be restored to its SHIPPED default by at least
   one scene, or appear in UNEXERCISED below.
3. DOC-DEFAULT — a flag's javadoc may not state a default the declaration contradicts.
   35 boolean flags carried "Default OFF." above a `= true` declaration: the §87 wave
   flipped the code and left the prose. Those javadocs are the ONLY documentation of what
   each flag does and when it is safe to touch, so a maintainer reasoning "this is off by
   default, my change is inert" was wrong 35 times — and the error had already propagated
   into call-site reasoning at Walker.java:1123.

UNEXERCISED is a checked-in backlog, not an exemption. It is the honest starting state
(28 names); shrinking it is the work, and a 29th name cannot appear without editing this
file — which is the review moment that was missing. Same contract as the grandfather list
in check_source_budget.py: entries may leave, not arrive, without a deliberate commit.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOT_CONFIG = os.path.join(ROOT, "common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java")
SCENE_GLOB = os.path.join(ROOT, "common/src/testmod/**/*.java")

# Baselined fields that NO scene restores to its shipped default. Each one is a code path
# that ships ON and has never executed in CI. Delete a name here once a scene covers it.
UNEXERCISED = {
    "allowWaterBucketFall",
    "pathfinderBreakCostMultiplier",
    "pathfinderFloatingBreakTax",
    "pathfinderForbidParkourFromFloatingWater",
    "pathfinderLogBreakTax",
    "walkerAscentRamBobBreak",
    "walkerBankDigForwardExit",
    "walkerBankDigGroundBlip",
    "walkerBankDigSkipOverhang",
    "walkerBankDigSkipWhenCwpSwims",
    "walkerBridgeHoldRepath",
    "walkerBuoyantSearchFromSurface",
    "walkerCarrotBodyLos",
    "walkerClimbGaveUpSticky",
    "walkerDigAimPriority",
    "walkerDigCommitHoldRepath",
    "walkerDryReanchor",
    "walkerFloatingBankBobFreeze",
    "walkerFutileBankDigRelease",
    "walkerPhysicalStallClock",
    "walkerPillarSurfacePlace",
    "walkerRamNodeAimRelease",
    "walkerRouteHysteresis",
    "walkerStuckStepMonotonic",
    "walkerSwimAshorePillarDespiteDeepDig",
    "walkerVineDescentDrop",
    "walkerWaterStepDownFloat",
    "walkerWaterWalkReach",
}
# The last four are worth calling out: they DO appear in scene sources, but never assigned
# the shipped value — a scene mentioning a flag is not a scene exercising it. That gap is
# why this gate matches on `BotConfig.<field> = <shipped value>;` rather than on the name.


def _norm(value):
    """Compare numeric literals by value so 1.0 == 1 and 2.5f == 2.5."""
    v = value.strip().rstrip("fFdDlL")
    try:
        return repr(float(v))
    except ValueError:
        return value.strip()


def read_baseline(src):
    marker = "public static void applyGameTestBaseline()"
    if marker not in src:
        sys.exit("check_baseline_coverage: applyGameTestBaseline() not found — "
                 "the gate's anchor moved; update this script deliberately.")
    body = src.split(marker, 1)[1]
    body = body[:body.index("\n    }")]
    return re.findall(r"^\s{8}(\w+)\s*=\s*([^;]+);", body, re.M)


_BOOL_DECL = re.compile(r"public static volatile boolean (\w+)\s*=\s*(true|false);")
_DOC_CLAIM = re.compile(r"[Dd]efaults? (ON|OFF)\b")


def doc_default_conflicts(src):
    """Yield (name, line, stated, actual) for javadocs that contradict their declaration.

    The javadoc block is the run of comment lines immediately above the declaration; only
    the FIRST 'Default ON/OFF' claim in it is treated as the statement of record.
    """
    lines = src.split("\n")
    for i, line in enumerate(lines):
        m = _BOOL_DECL.search(line)
        if not m:
            continue
        name, actual = m.group(1), m.group(2)
        j = i - 1
        block = []
        while j >= 0 and (lines[j].strip().startswith(("*", "/*", "//"))
                          or lines[j].strip().endswith("*/")):
            block.append(lines[j])
            j -= 1
        claim = _DOC_CLAIM.search("\n".join(reversed(block)))
        if not claim:
            continue
        stated = claim.group(1)
        if (stated == "OFF") != (actual == "false"):
            yield name, i + 1, stated, actual


def main():
    src = open(BOT_CONFIG, encoding="utf-8").read()
    assigns = read_baseline(src)
    defaults = dict(re.findall(
        r"public static volatile \w+ (\w+)\s*=\s*([^;]+);", src))

    problems = []

    noops = [k for k, v in assigns
             if k in defaults and _norm(defaults[k]) == _norm(v)]
    for k in sorted(noops):
        problems.append(f"NO-OP: applyGameTestBaseline() sets {k} to its own default "
                        f"({defaults[k].strip()}) — the line pins nothing; delete it")

    unknown = [k for k, _ in assigns if k not in defaults]
    for k in sorted(unknown):
        problems.append(f"UNKNOWN-FIELD: applyGameTestBaseline() assigns {k}, which is not "
                        f"a declared `public static volatile` BotConfig field")

    for name, line, stated, actual in doc_default_conflicts(src):
        problems.append(
            f"DOC-DEFAULT: BotConfig.java:{line} {name} is declared `= {actual}` but its "
            f"javadoc says 'Default {stated}' — the javadoc is a flag's only documentation, "
            f"so fix the prose (or the declaration) rather than leaving them to disagree")

    scene_src = "\n".join(
        open(p, encoding="utf-8", errors="replace").read()
        for p in glob.glob(SCENE_GLOB, recursive=True))

    uncovered = set()
    for k, v in assigns:
        if k not in defaults or _norm(defaults[k]) == _norm(v):
            continue
        # A scene covers the shipped behaviour only by writing the SHIPPED value back.
        want = defaults[k].strip()
        if not re.search(r"BotConfig\.%s\s*=\s*%s\s*;" % (re.escape(k), re.escape(want)),
                         scene_src):
            uncovered.add(k)

    for k in sorted(uncovered - UNEXERCISED):
        problems.append(
            f"UNCOVERED: {k} is pinned to {dict(assigns)[k].strip()} for the whole suite but "
            f"ships as {defaults[k].strip()}, and no scene restores it — that code path would "
            f"never execute in CI. Add a scene that sets BotConfig.{k} = {defaults[k].strip()}; "
            f"or, if that is genuinely not yet possible, add '{k}' to UNEXERCISED in "
            f"scripts/check_baseline_coverage.py with a reason in the commit message")

    stale = sorted(UNEXERCISED - uncovered)

    if problems:
        print("baseline-coverage gate FAILED:")
        for p in problems:
            print("  " + p)
        return 1

    print("baseline-coverage gate OK: %d baselined knob(s), %d covered by a scene, "
          "%d on the tracked backlog" % (len(assigns), len(assigns) - len(uncovered),
                                         len(uncovered)))
    if stale:
        print("note: now covered by a scene — remove from UNEXERCISED: %s" % ", ".join(stale))
    return 0


if __name__ == "__main__":
    sys.exit(main())
