#!/usr/bin/env python3
"""GameTest suite-integrity reconciler (task#85 P0).

Verdict = ALL of:
  1. gradle log has BUILD SUCCESSFUL;
  2. gradle log has no "required tests failed";
  3. every registered test (any batch) has an enter record  -> else SWALLOWED;
  4. every enter record maps to a registered test           -> else DRIFTED (guard-string typo).
Matching is case-insensitive (vanilla testName() is lowercase, guard strings are mixed case).
NEVER consults the mod reporter's "TOTAL:" line (it can read all-green while required tests fail).
"""
import argparse
import json
import os
import re
import sys


def norm(name):
    """Case-insensitive; also strip any dotted prefix — vanilla testName() is expected
    to be the bare lowercased method name, but if a batch/class prefix ever appears
    ("agentvalidation.agentrpcsmoke") the gate must not false-positive on it."""
    return name.lower().rsplit(".", 1)[-1]


def parse_manifest(text):
    registered, entered = {}, set()
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        name = norm(rec["name"])
        if rec["type"] == "registered":
            registered[name] = {"batch": rec["batch"], "required": rec["required"]}
        elif rec["type"] == "enter":
            entered.add(name)
    return registered, entered


def parse_log(text):
    return {
        "build_success": "BUILD SUCCESSFUL" in text,
        "required_failed": "required tests failed" in text,
    }


def reconcile(registered, entered, log_facts):
    swallowed = sorted(n for n in registered if n not in entered)
    drifted = sorted(n for n in entered if n not in registered)
    ok = (log_facts["build_success"] and not log_facts["required_failed"]
          and not swallowed and not drifted and bool(registered))
    return {"ok": ok, "swallowed": swallowed, "drifted": drifted,
            "registered": len(registered), "entered": len(entered), **log_facts}


def report(r):
    print(f"[gt_reconcile] registered={r['registered']} entered={r['entered']} "
          f"build_success={r['build_success']} required_failed={r['required_failed']}")
    if r["swallowed"]:
        print(f"[gt_reconcile] SWALLOWED ({len(r['swallowed'])}) — registered but body never ran (#85):")
        for n in r["swallowed"]:
            print(f"  - {n}")
    if r["drifted"]:
        print(f"[gt_reconcile] DRIFTED ({len(r['drifted'])}) — enter name matches no registered test "
              f"(guard-string typo):")
        for n in r["drifted"]:
            print(f"  - {n}")
    print(f"[gt_reconcile] VERDICT: {'GREEN' if r['ok'] else 'RED'}")
    return 0 if r["ok"] else 1


FIXTURE_MANIFEST_GREEN = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"registered","name":"betaarena","batch":"defaultBatch","required":false}\n'
    '{"type":"enter","name":"alphaArena"}\n'
    '{"type":"enter","name":"betaArena"}\n'
)
FIXTURE_MANIFEST_SWALLOWED = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"registered","name":"betaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"enter","name":"alphaArena"}\n'
)
FIXTURE_MANIFEST_DRIFTED = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"enter","name":"alphaArena"}\n'
    '{"type":"enter","name":"alphaArena2"}\n'
)
FIXTURE_LOG_GREEN = "irrelevant\nAll 2 required tests passed :)\nBUILD SUCCESSFUL in 1m\n"
FIXTURE_LOG_REQFAIL = "TOTAL: 2   PASS: 2   FAIL: 0\n1 required tests failed :(\nBUILD FAILED in 1m\n"
FIXTURE_LOG_REQFAIL_ONLY = "All done\n1 required tests failed :(\nBUILD SUCCESSFUL in 1m\n"


def self_test():
    checks = []
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_GREEN), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("green run passes", r["ok"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_SWALLOWED), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("swallowed test fails the run", not r["ok"] and r["swallowed"] == ["betaarena"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_DRIFTED), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("drifted guard fails the run", not r["ok"] and r["drifted"] == ["alphaarena2"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_GREEN), parse_log(FIXTURE_LOG_REQFAIL))
    checks.append(("required-failed beats green TOTAL line", not r["ok"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_GREEN), parse_log(FIXTURE_LOG_REQFAIL_ONLY))
    checks.append(("required-failed alone forces red (isolated gate coverage)", not r["ok"]))
    r = reconcile(*parse_manifest(""), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("empty manifest fails (manifest never armed)", not r["ok"]))
    prefixed = ('{"type":"registered","name":"somebatch.alphaarena","batch":"b","required":true}\n'
                '{"type":"enter","name":"alphaArena"}\n')
    r = reconcile(*parse_manifest(prefixed), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("dotted registered prefix still matches bare enter name", r["ok"]))
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    return 0 if not failed else 1


GAMETEST_RX = re.compile(r"@GameTest\b[^)]*\)?\s*(?:@\w+[^\n]*\n\s*)*public\s+static\s+void\s+(\w+)\s*\(")
GUARD_RX = re.compile(r"gtOnlySkips\(\s*\"([^\"]+)\"\s*\)|gtSkip\(\s*\w+\s*,\s*\"([^\"]+)\"\s*\)")


def audit_source(src_dir):
    problems = []
    for fname in sorted(os.listdir(src_dir)):
        if not (fname.startswith("AgentGameTest") and fname.endswith(".java")):
            continue
        if fname == "AgentGameTestSupport.java":
            continue
        text = open(os.path.join(src_dir, fname), encoding="utf-8").read()
        methods = GAMETEST_RX.findall(text)
        # The audit must not be silently blind itself: every raw @GameTest occurrence
        # must have been parsed into a method name, or the regex missed one.
        raw = text.count("@GameTest(") + len(re.findall(r"@GameTest\s*\n", text)) \
            + len(re.findall(r"@GameTest\s+(?=@|public)", text))
        if raw != len(methods):
            problems.append(f"{fname}: {raw} raw @GameTest occurrences but regex parsed "
                            f"{len(methods)} methods — audit regex is blind to the difference")
        guards = {(a or b).lower() for a, b in GUARD_RX.findall(text)}
        for m in methods:
            if m.lower() not in guards:
                problems.append(f"{fname}: @GameTest {m} has no matching gtOnlySkips/gtSkip "
                                f"guard — enter probe blind for it")
    for p in problems:
        print(f"  [AUDIT] {p}")
    print(f"[gt_reconcile] source audit: {'CLEAN' if not problems else str(len(problems)) + ' problem(s)'}")
    return 0 if not problems else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest")
    ap.add_argument("--log")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--audit-source")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if args.audit_source:
        sys.exit(audit_source(args.audit_source))
    if not args.manifest or not args.log:
        ap.error("--manifest and --log are required (or use --self-test)")
    try:
        manifest_text = open(args.manifest, encoding="utf-8").read()
    except FileNotFoundError:
        print(f"[gt_reconcile] manifest missing: {args.manifest} — manifest never armed => RED")
        sys.exit(1)
    log_text = open(args.log, encoding="utf-8", errors="replace").read()
    r = reconcile(*parse_manifest(manifest_text), parse_log(log_text))
    sys.exit(report(r))


if __name__ == "__main__":
    main()
