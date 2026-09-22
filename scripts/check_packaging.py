#!/usr/bin/env python3
"""Gate: what the build publishes carries what a consumer of it is owed.

Run from the repo root (exit 1 on violation):

    python3 scripts/check_packaging.py              # every check
    python3 scripts/check_packaging.py repos        # just the named ones

Every check except `repos` reads BUILT artifacts, so assemble them first:

    ./gradlew :common:assemble :fabric:assemble :neoforge:assemble

Never `publish` to get them: every worktree shares one ~/.m2.

Checks
------
repos   Every maven.latvian.dev repository is fenced to its own group. Gradle takes the
        first repository that answers, and an expired domain's parking page once answered
        200 to every path, black-holing every healthy repository declared after it.
licence Every published jar (the shipped one, `-sources` and `-dev`) of every module carries
        META-INF/COPYING and META-INF/COPYING.LESSER, once each and byte-identical to the
        repository's. The LGPL (through the GPL sections it incorporates) requires its text
        to accompany the object code.
"""
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ("common", "fabric", "neoforge")
LICENCE_FILES = ("COPYING", "COPYING.LESSER")


def gradle_properties():
    props = {}
    with open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def published_jars(module):
    """The jar files the module's publication ships, as (label, path)."""
    props = gradle_properties()
    base = f"{props['archives_name']}-{module}-{props['mod_version']}"
    build = os.path.join(ROOT, module, "build")
    return [
        ("jar", os.path.join(build, "libs", base + ".jar")),
        ("sources", os.path.join(build, "libs", base + "-sources.jar")),
        ("dev", os.path.join(build, "devlibs", base + "-dev.jar")),
    ]


def check_licence():
    problems = []
    expected = {}
    for name in LICENCE_FILES:
        with open(os.path.join(ROOT, name), "rb") as f:
            expected[name] = f.read()
    for module in MODULES:
        for _, path in published_jars(module):
            rel = os.path.relpath(path, ROOT)
            if not os.path.isfile(path):
                problems.append(f"{rel}: not built (see this script's docstring)")
                continue
            with zipfile.ZipFile(path) as jar:
                names = jar.namelist()
                for name in LICENCE_FILES:
                    entry = "META-INF/" + name
                    count = names.count(entry)
                    if count != 1:
                        problems.append(f"{rel}: {entry} present {count} times, want 1")
                    elif jar.read(entry) != expected[name]:
                        problems.append(f"{rel}: {entry} differs from the repository's {name}")
    return problems


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
    "licence": check_licence,
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
