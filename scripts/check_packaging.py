#!/usr/bin/env python3
"""Gate: what the build publishes carries what a consumer of it is owed.

Run from the repo root (exit 1 on violation):

    python3 scripts/check_packaging.py              # every check
    python3 scripts/check_packaging.py repos        # just the named ones

Every check except `repos` reads BUILT artifacts, so assemble them first:

    ./gradlew :common:assemble :fabric:assemble :neoforge:assemble \\
        :common:generatePomFileForMavenJavaPublication \\
        :fabric:generatePomFileForMavenJavaPublication \\
        :neoforge:generatePomFileForMavenJavaPublication

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
pom     Every module's POM declares the licence (gradle.properties `mod_license`, with its
        `mod_license_url`), the project url and the scm coordinates. Without them a
        licence scanner reports the artifact as "unknown".
metadata
        The mod metadata inside the shipped loader jars says what gradle.properties says:
        licence, description and authors, and dependency ranges derived from the pinned
        versions (Architectury from its pin up to the next major, the Fabric loader from
        its pin, Minecraft from `minecraft_version_range` in each loader's syntax). A value
        hardcoded in one loader's file drifts from the other's on the next edit.
"""
import json
import os
import re
import sys
import tomllib
import zipfile
import xml.etree.ElementTree as ET

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


def check_pom():
    props = gradle_properties()
    source = props.get("mod_source_url")
    scm = props.get("mod_scm_url")
    licence_url = props.get("mod_license_url")
    if not (source and scm and licence_url):
        return ["gradle.properties: mod_source_url, mod_scm_url and mod_license_url must all be set"]
    want = {
        "licenses/license/name": props["mod_license"],
        "licenses/license/url": licence_url,
        "url": source,
        "scm/url": source,
        "scm/connection": f"scm:git:{source}.git",
        "scm/developerConnection": f"scm:git:{scm}",
    }
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    problems = []
    for module in MODULES:
        path = os.path.join(ROOT, module, "build", "publications", "mavenJava", "pom-default.xml")
        rel = os.path.relpath(path, ROOT)
        if not os.path.isfile(path):
            problems.append(f"{rel}: not generated (see this script's docstring)")
            continue
        project = ET.parse(path).getroot()
        for key, value in want.items():
            found = [e.text for e in project.findall("/".join("m:" + p for p in key.split("/")), ns)]
            if found != [value]:
                problems.append(f"{rel}: <{key}> is {found or 'absent'}, want {value!r}")
    return problems


def fabric_range(maven):
    """A single Maven interval such as `[1.21.1,1.22)` in Fabric's space-joined predicate form."""
    m = re.fullmatch(r"([\[(])([^,\])]*)(?:,([^\])]*))?([\])])", maven.strip())
    if not m:
        raise ValueError(f"not a single Maven interval: {maven!r}")
    lo_open, lo, hi, hi_close = m.groups()
    if hi is None:
        return lo
    parts = []
    if lo:
        parts.append((">=" if lo_open == "[" else ">") + lo)
    if hi:
        parts.append(("<=" if hi_close == "]" else "<") + hi)
    return " ".join(parts)


def check_metadata():
    props = gradle_properties()
    arch = props["architectury_api_version"]
    arch_next = int(arch.split(".")[0]) + 1
    authors = [a.strip() for a in props["mod_authors"].split(",")]
    problems = []

    # A literal that happens to equal today's property passes the jar checks below and drifts on
    # the next edit, so the templates themselves must name every value they carry.
    templates = {
        "fabric/src/main/resources/fabric.mod.json": (
            "mod_license", "mod_description", "mod_authors",
            "minecraft_range", "architectury_range", "fabric_loader_range"),
        "neoforge/src/main/resources/META-INF/neoforge.mods.toml": (
            "mod_license", "mod_description", "mod_authors", "loader_version_range",
            "minecraft_version_range", "neoforge_version_range", "architectury_version_range"),
    }
    for path, keys in templates.items():
        with open(os.path.join(ROOT, path), encoding="utf-8") as f:
            text = f.read()
        for key in keys:
            if "${" + key + "}" not in text:
                problems.append(f"{path}: does not expand ${{{key}}}")

    jar = dict(published_jars("fabric"))["jar"]
    rel = os.path.relpath(jar, ROOT)
    if not os.path.isfile(jar):
        problems.append(f"{rel}: not built (see this script's docstring)")
    else:
        with zipfile.ZipFile(jar) as z:
            meta = json.loads(z.read("fabric.mod.json"))
        depends = meta.get("depends", {})
        for key, got, want in [
            ("license", meta.get("license"), props["mod_license"]),
            ("description", meta.get("description"), props["mod_description"]),
            ("authors", meta.get("authors"), authors),
            ("depends.minecraft", depends.get("minecraft"), fabric_range(props["minecraft_version_range"])),
            ("depends.architectury", depends.get("architectury"), f">={arch} <{arch_next}"),
            ("depends.fabricloader", depends.get("fabricloader"), f">={props['fabric_loader_version']}"),
        ]:
            if got != want:
                problems.append(f"{rel}!fabric.mod.json: {key} is {got!r}, want {want!r}")

    jar = dict(published_jars("neoforge"))["jar"]
    rel = os.path.relpath(jar, ROOT)
    if not os.path.isfile(jar):
        problems.append(f"{rel}: not built (see this script's docstring)")
    else:
        with zipfile.ZipFile(jar) as z:
            meta = tomllib.loads(z.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mod = meta["mods"][0]
        ranges = {d["modId"]: d.get("versionRange") for d in meta["dependencies"][props["mod_id"]]}
        for key, got, want in [
            ("license", meta.get("license"), props["mod_license"]),
            ("loaderVersion", meta.get("loaderVersion"), props["loader_version_range"]),
            ("description", mod.get("description", "").strip(), props["mod_description"]),
            ("authors", mod.get("authors"), ", ".join(authors)),
            ("minecraft range", ranges.get("minecraft"), props["minecraft_version_range"]),
            ("neoforge range", ranges.get("neoforge"), props["neoforge_version_range"]),
            ("architectury range", ranges.get("architectury"), f"[{arch},{arch_next})"),
        ]:
            if got != want:
                problems.append(f"{rel}!META-INF/neoforge.mods.toml: {key} is {got!r}, want {want!r}")
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
    "pom": check_pom,
    "metadata": check_metadata,
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
