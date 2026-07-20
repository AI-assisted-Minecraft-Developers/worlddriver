#!/usr/bin/env python3
"""Generate + digest a JaCoCo coverage report for the movement/executor packages.

Usage:
    python3 scripts/coverage/report.py <exec1> [exec2 ...] [--html]

Steps:
  1. Merge the given .exec files (jacoco CLI).
  2. Emit CSV (+ optional HTML under coverage-out/html) against :common main classes.
  3. Print a per-class digest for net.magicterra.agent.bot.movement.* sorted by
     missed branches, and dump uncovered-line detail via the XML report into
     coverage-out/uncovered-movement.txt for gap triage.

The jacoco jars live in .local/jacoco (see fetch instructions in AGENTS.md /
TODO); everything under coverage-out/ is gitignored.
"""
import csv
import os
import re
import shutil
import subprocess
import sys
try:
    import defusedxml.ElementTree as ET   # hardened parser if available
except ImportError:
    # Fallback: stdlib parser. Acceptable here — the XML is jacoco's own report
    # generated two lines above from local .exec files, not untrusted input.
    import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLI = os.path.join(ROOT, ".local/jacoco/org.jacoco.cli-0.8.12-nodeps.jar")
OUT = os.path.join(ROOT, "coverage-out")
# IMPORTANT: report against the classes the game JVM ACTUALLY loaded — the
# architectury-transformer agent rewrites every :common class at load time, so
# build/classes ids never match (silent 0% across the board, 2026-07-19). The
# jacoco agent's `classdumpdir=` option dumps the post-transform bytes; point
# CLASSES there (override via COVERAGE_CLASSES env if needed).
CLASSES = os.environ.get("COVERAGE_CLASSES",
                         os.pathsep.join(p for p in (os.path.join(OUT, "classes-t0"),
                                                     os.path.join(OUT, "classes-t1"))
                                         if os.path.isdir(p)))
SOURCES = os.path.join(ROOT, "common/src/main/java")
PKG_PREFIX = "net/magicterra/agent/bot"

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("FAILED: %s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    html = "--html" in sys.argv
    if not args:
        sys.exit(__doc__)
    os.makedirs(OUT, exist_ok=True)
    merged = os.path.join(OUT, "merged.exec")
    run(["java", "-jar", CLI, "merge", *args, "--destfile", merged])

    # The classdumpdir can hold SEVERAL variants of one class (same name, different
    # class id — multiple classloaders/JVMs). jacoco report refuses duplicates, so
    # build a picked tree keeping, per class name, the variant with the most probe
    # hits in the merged exec (ids are embedded in the dump filenames).
    hits = {}   # class id hex -> hits
    info = run(["java", "-jar", CLI, "execinfo", merged])
    for line in info.stdout.splitlines():
        m = re.match(r"([0-9a-f]{16})\s+(\d+) of\s+(\d+)\s+(\S+)", line.strip())
        if m:
            hits[m.group(1)] = int(m.group(2))
    picked = os.path.join(OUT, "classes-picked")
    if os.path.isdir(picked):
        shutil.rmtree(picked)
    best = {}   # relative classname path -> (hits, srcfile)
    for root_dir in CLASSES.split(os.pathsep):
        for dirpath, _dirs, files in os.walk(root_dir):
            for fn in files:
                if not fn.endswith(".class"):
                    continue
                m = re.match(r"(.+)\.([0-9a-f]{16})\.class$", fn)
                if not m:
                    continue
                rel = os.path.relpath(os.path.join(dirpath, m.group(1)), root_dir)
                h = hits.get(m.group(2), -1)
                if rel not in best or h > best[rel][0]:
                    best[rel] = (h, os.path.join(dirpath, fn))
    for rel, (_h, src) in best.items():
        dst = os.path.join(picked, rel + ".class")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
    global CLASSES_EFFECTIVE
    CLASSES_EFFECTIVE = picked

    xml_path = os.path.join(OUT, "report.xml")
    csv_path = os.path.join(OUT, "report.csv")
    cmd = ["java", "-jar", CLI, "report", merged,
           "--classfiles", CLASSES_EFFECTIVE, "--sourcefiles", SOURCES,
           "--xml", xml_path, "--csv", csv_path]
    if html:
        cmd += ["--html", os.path.join(OUT, "html")]
    run(cmd)

    # ---- digest: movement + process/pathfinder classes by missed branches ----
    rows = []
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            pkg = row["PACKAGE"].replace(".", "/")
            if not pkg.startswith(PKG_PREFIX):
                continue
            bm, bc = int(row["BRANCH_MISSED"]), int(row["BRANCH_COVERED"])
            lm, lc = int(row["LINE_MISSED"]), int(row["LINE_COVERED"])
            rows.append((bm, bc, lm, lc, row["PACKAGE"] + "." + row["CLASS"]))
    rows.sort(reverse=True)
    print("%-70s %14s %14s" % ("class (net.magicterra.agent.bot.*)", "branch miss/tot", "line miss/tot"))
    tb = tbm = tl = tlm = 0
    for bm, bc, lm, lc, name in rows:
        tbm += bm; tb += bm + bc; tlm += lm; tl += lm + lc
        print("%-70s %8d/%-5d %8d/%-5d" % (name.replace("net.magicterra.agent.bot.", ""), bm, bm + bc, lm, lm + lc))
    print("%-70s %8d/%-5d %8d/%-5d  (branch %.1f%%, line %.1f%%)"
          % ("TOTAL bot.*", tbm, tb, tlm, tl,
             100.0 * (tb - tbm) / tb if tb else 0, 100.0 * (tl - tlm) / tl if tl else 0))

    # ---- uncovered-line dump for movement package (gap triage) ----
    tree = ET.parse(xml_path)
    out_lines = []
    for pkg in tree.getroot().iter("package"):
        if not pkg.get("name", "").startswith(PKG_PREFIX):
            continue
        for sf in pkg.iter("sourcefile"):
            misses = []
            for ln in sf.iter("line"):
                nr = int(ln.get("nr")); mi = int(ln.get("mi")); mb = int(ln.get("mb")); cb = int(ln.get("cb"))
                if mi > 0 or mb > 0:
                    misses.append((nr, mi, mb, cb))
            if misses:
                out_lines.append("== %s/%s (%d uncovered/partial lines)" % (pkg.get("name"), sf.get("name"), len(misses)))
                # compress into ranges of fully-missed lines; list partial branches individually
                run_start = prev = None
                def flush():
                    if run_start is not None:
                        out_lines.append("   miss %d-%d" % (run_start, prev))
                for nr, mi, mb, cb in misses:
                    if mi > 0 and mb == 0 and cb == 0:
                        if run_start is None:
                            run_start = nr
                        elif nr != prev + 1:
                            flush(); run_start = nr
                        prev = nr
                    else:
                        flush(); run_start = None
                        out_lines.append("   branch %d (missed %d of %d)" % (nr, mb, mb + cb))
                        prev = nr
                flush()
    dump = os.path.join(OUT, "uncovered-movement.txt")
    with open(dump, "w") as f:
        f.write("\n".join(out_lines) + "\n")
    print("\nuncovered-line dump:", dump, "(%d lines)" % len(out_lines))

if __name__ == "__main__":
    main()
