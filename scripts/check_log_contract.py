#!/usr/bin/env python3
"""Gate: the game-log formats that dev tools parse must keep parsing.

WHY THIS EXISTS
---------------
Five scripts recover bot state by scraping the game log with regexes:

    scripts/forensic.py             P_T ([walker] t=), P_K (walk-keys), P_E ([expect])
    scripts/pmcs/telemetry.py       _RE ([walker] t=), _RE_YAW
    scripts/pmcs/run_case.py        polls [walker] lines for arrival
    scripts/accept_cycle.py         [expect] lines
    scripts/probe_unreachable_churn.py  "lastError" out of a JSON blob

The producing side is two `LOG.info` format strings deep in the tick path
(WalkerTickClimb.java:97 for `[walker] t=`, WalkerTickDrive.java:1300 for
`walk-keys`). Nothing connected the two: adding a field, renaming `totStuck`, or
moving `yaw` would compile, pass every scene, ship — and silently turn every one
of those tools into a no-op. A regex that matches nothing does not raise; it
yields an empty iterator, and the tool reports "no ticks" as if the bot had
never moved.

Checked 2026-07-27 against a real 5593-line sample: all four regexes matched
100%. So this gate is not repairing a break, it is pinning a contract that was
never pinned — which is the whole point, because the failure mode is silent.

DO NOT RUN THIS CONCURRENTLY WITH t0
------------------------------------
The sample is a real run log — `<loader>/run-dogfood/logs/latest.log` — which is
exactly the file t0.py's dogfood server is writing while it runs. Running the gate
during a t0 reads a half-written log and can report a RED that has nothing to do
with the code (observed 2026-07-27). Let t0 finish first; the log it leaves behind
is the sample this wants anyway.

WHAT IT CHECKS
--------------
For each (discriminator, regex) pair below, against a real run log:

  1. at least one line matches the DISCRIMINATOR. For a contract marked REQUIRED
     that is a hard failure: the dogfood log carries ~5600 `[walker] t=` lines, so
     zero of them means the emitter was deleted, not that the path was quiet. For
     the rest it is UNVERIFIED, never PASS — "we saw nothing" is not evidence of
     agreement, and letting silence pass is exactly how an emitter disappears
     without anyone noticing.
  2. EVERY discriminated line matches the consumer REGEX — a partial match means
     the format drifted for some subset, which is worse than a total break
     because the tools keep working on the lines they still understand

Run it on whatever log a t0 run just produced:

    python scripts/check_log_contract.py --log fabric/run-dogfood/logs/latest.log

`[expect]` lines come from the INTERACTIVE client run (fabric/run/logs), not from
the dogfood server, so they normally land as UNVERIFIED here. Point --log at a
client log to cover them too.

Exit 0 = every contract with a sample agrees, and every REQUIRED one had a sample.
Exit 1 = a format drifted, or a required emitter produced nothing.
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _load(rel: str, name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / rel)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def contracts():
    """-> [(label, discriminator, regex, producer, required)] built from the REAL consumer
    modules, never from a copy of their patterns: a gate holding its own duplicate
    of the regex would pass while the tool it claims to protect is broken."""
    forensic = _load("scripts/forensic.py", "_forensic")
    telemetry = _load("scripts/pmcs/telemetry.py", "_telemetry")
    return [
        ("forensic.P_T", "[walker] t=", forensic.P_T,
         "WalkerTickClimb.java LOG.info(\"[walker] t={} step=...\")", True),
        ("forensic.P_K", "walk-keys", forensic.P_K,
         "WalkerTickDrive.java LOG.info(\"[walker] walk-keys ...\")", True),
        # [expect] is emitted by the interactive CLIENT run (fabric/run/logs), not by
        # the dogfood server, so it is genuinely absent from the default log.
        ("forensic.P_E", "[expect]", forensic.P_E,
         "ClutchController.java and friends LOG.warn(\"[expect] ...\")", False),
        ("telemetry._RE", "[walker] t=", telemetry._RE,
         "same line as forensic.P_T — two consumers, one format", True),
        ("telemetry._RE_YAW", "[walker] t=", telemetry._RE_YAW,
         "yaw= inside the [walker] t= line", True),
    ]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--log", default="fabric/run-dogfood/logs/latest.log",
                    help="game log to check (default: the fabric dogfood run's latest.log)")
    ap.add_argument("--require-all", action="store_true",
                    help="treat UNVERIFIED (no sample in this log) as failure")
    args = ap.parse_args()

    path = pathlib.Path(args.log)
    if not path.is_absolute():
        path = ROOT / path
    if not path.exists():
        print(f"log-contract: {path} does not exist — run a t0 first "
              f"(python scripts/testkit/t0.py --loader fabric ...)")
        return 1

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    print(f"log      : {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path} "
          f"({len(lines)} lines)")

    failures, unverified, missing = [], [], []
    for label, disc, rx, producer, required in contracts():
        sample = [ln for ln in lines if disc in ln]
        if not sample:
            if required:
                missing.append((label, disc, producer))
                print(f"  {label:22s} MISSING     no line contains {disc!r} — emitter gone?")
            else:
                unverified.append((label, disc))
                print(f"  {label:22s} UNVERIFIED  (no line contains {disc!r} in this log)")
            continue
        misses = [ln for ln in sample if not rx.search(ln)]
        if misses:
            failures.append((label, len(misses), len(sample), misses[0], producer))
            print(f"  {label:22s} DRIFT       {len(sample) - len(misses)}/{len(sample)} parse")
        else:
            print(f"  {label:22s} OK          {len(sample)}/{len(sample)} parse")

    for label, nmiss, ntotal, example, producer in failures:
        print(f"\nLOG-CONTRACT DRIFT: {label} failed on {nmiss} of {ntotal} matching lines.")
        print(f"  producer: {producer}")
        print(f"  example : {example.strip()[:200]}")
        print("  Either the emitter's format string changed and the consumer must follow,")
        print("  or the consumer is stale. Both live in this repo; fix them together.")

    for label, disc, producer in missing:
        print(f"\nLOG-CONTRACT MISSING: {label} found no {disc!r} line at all.")
        print(f"  producer: {producer}")
        print("  This log normally carries thousands of them. Either the emitter was removed")
        print("  (the consumer is now a permanent no-op) or --log points at the wrong run.")

    if missing:
        return 1
    if args.require_all and unverified:
        print(f"\n--require-all: {len(unverified)} contract(s) had no sample in this log; "
              f"point --log at a run that exercises them")
        return 1
    if failures:
        return 1
    print(f"log-contract gate OK: {len(contracts()) - len(unverified)} contract(s) verified, "
          f"{len(unverified)} unverified (no sample in this log)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
