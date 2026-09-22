#!/usr/bin/env python3
"""Gate: what the build publishes carries what a consumer of it is owed.

Run from the repo root (exit 1 on violation):

    python3 scripts/check_packaging.py              # every check
    python3 scripts/check_packaging.py repos        # just the named ones

Checks
------
repos   Every maven.latvian.dev repository is fenced to its own group. Gradle takes the
        first repository that answers, and an expired domain's parking page once answered
        200 to every path, black-holing every healthy repository declared after it.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ("common", "fabric", "neoforge")


def check_repos():
    problems = []
    for module in ("",) + MODULES:
        path = os.path.join(ROOT, module, "build.gradle")
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for m in re.finditer(r"maven\s*\{", text):
            block = _braced(text, m.end() - 1)
            if "maven.latvian.dev" in block and "includeGroup 'dev.latvian.mods'" not in block:
                line = text.count("\n", 0, m.start()) + 1
                problems.append(f"{os.path.relpath(path, ROOT)}:{line}: maven.latvian.dev without"
                                " content { includeGroup 'dev.latvian.mods' }")
    return problems


def _braced(text, open_index):
    """The text of the brace block opening at open_index, braces included."""
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index:i + 1]
    return text[open_index:]


CHECKS = {
    "repos": check_repos,
}


def main(argv):
    names = argv or list(CHECKS)
    unknown = [n for n in names if n not in CHECKS]
    if unknown:
        print(f"unknown check(s): {', '.join(unknown)}; known: {', '.join(CHECKS)}")
        return 2
    failed = False
    for name in names:
        problems = CHECKS[name]()
        if problems:
            failed = True
            print(f"packaging check '{name}' FAILED:")
            for p in problems:
                print(f"    {p}")
        else:
            print(f"packaging check '{name}' OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
