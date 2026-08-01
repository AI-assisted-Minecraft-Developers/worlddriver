#!/usr/bin/env python3
"""Gate: no reflective member lookup may use a Mojang-mapped NAME against an
owner class that Loom REMAPPED in the shipped jar.

WHY THIS EXISTS
---------------
The build declares `mappings loom.officialMojangMappings()` (build.gradle:63), so
dev runs -- `runClient`, `runDogfoodServer`, every testkit gate -- see Mojang names.
`remapJar` (fabric/build.gradle:111) then rewrites the SHIPPED fabric jar into the
`intermediary` namespace. tiny-remapper rewrites type/field/method *references*; it
does NOT rewrite string CONSTANTS. So this, which works in every gate we run:

    MouseHandler.class.getDeclaredField("xpos")

becomes this in the artifact a Fabric user actually installs:

    class_312.class.getDeclaredField("xpos")     -> NoSuchFieldException
                                                    (the field is `field_1795`)

Verified empirically 2026-07-26 by disassembling both jars: the owner became
`net/minecraft/class_312` while the literal stayed `xpos`.

NeoForge is NOT affected -- its 1.21.1 runtime namespace IS Mojang-mapped, so its
remapJar leaves `net/minecraft/client/MouseHandler` intact. Fabric only.

Every known site degrades (catch -> warn -> feature off) rather than crashing, which
is exactly why this went unnoticed: no gate ever loads a remapped jar, and the mod
still boots. The cost is silent: mining-progress sensing, GUI driving, and screen
introspection are all dead in the shipped fabric jar.

HOW IT DETECTS
--------------
Pure bytecode, no mapping data needed. In the REMAPPED jar it looks for

    ldc  <class net/minecraft/class_NNNN>    # owner was remapped ...
    ldc  <String someName>                   # ... but the name literal was not
    invokevirtual java/lang/Class.get{Declared,}{Field,Method}

That finds CANDIDATES. It is not by itself proof: "the owner was remapped, therefore
the member is too" is a heuristic, and it is wrong often enough to matter --
`Component.getString()` survives remapping verbatim because intermediary only has an
entry for the `getString(int)` overload, so the no-arg one keeps its name.

So when Loom's mapping table is on disk (`~/.gradle/caches/fabric-loom/<mc>/loom.
mappings.*-v2/mappings.tiny`, namespaces `official intermediary named`) each candidate
is confirmed against it and only genuinely-renamed members are reported. Without the
table the gate still runs, but says so and falls back to the heuristic.

Members whose name has SEVERAL overloads are reported as AMBIGUOUS rather than
silently passed: the bytecode scan does not recover the parameter types the
reflective lookup asked for, so only a human can say which overload is meant.

Dynamic owners (`x.getClass().getMethod("id")`) are invisible to this check by
construction; those are tracked in DYNAMIC_OWNER_BACKLOG below and reviewed by hand.

REMEDIATION (for anything this reports)
---------------------------------------
Access widener + access transformer -- ESTABLISHED, piloted on MouseHandler.xpos/ypos
2026-07-26 and green on both loaders. Add the member to BOTH:

    common/src/main/resources/agent_driver.accesswidener     (fabric + compile)
    neoforge/src/main/resources/META-INF/accesstransformer.cfg   (neoforge)

then delete the reflection and use the member directly. The source stays Mojang-named
and readable; Loom remaps the widener with the jar (verified: the shipped fabric jar
carries `accessWidener v2 intermediary` / `class_312 field_1795`, and the call site is
a plain `putfield class_312.field_1795` with no string constant anywhere).

Two files because architectury-loom 1.11 cannot convert one to the other for NeoForge
(`convertAccessWideners` is ForgeExtensionAPI-only; NeoForgeExtensionAPI offers just
accessTransformer/getAccessTransformers). check_widener_sync() below is what keeps
them identical.

Fallbacks, if a member ever resists the above:
  - Mixin @Accessor/@Invoker. Also correct, more boilerplate, and needs mixin infra
    this repo does not have (0 *.mixins.json). What altoclef/KubeJS use.
  - Delete the reflection entirely when it targets PUBLIC API through getClass() --
    a typed call remaps correctly and costs nothing. Three sites went this way.
  - Deliberately accept the loss: add the site to BASELINE with a reason, and make
    the degradation LOUD (log once), never silent.

Exit 0 = no NEW remap-unsafe reflection. Exit 1 = drift.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Sites already known to break in the shipped fabric jar. Each entry is
# "Class#member" and MUST carry a reason. Shrink this list; never grow it without
# the remediation note above.
BASELINE: dict[str, str] = {
    # EMPTY, and it must stay that way. All 15 original sites were migrated to the
    # access widener + access transformer on 2026-07-26/27; the last of them went in the
    # same day the gate was written. An entry here means a member is knowingly left
    # broken in the shipped fabric jar, which is now a deliberate exception, not a
    # backlog: justify it, make the degradation loud (log once), and prefer widening.
}

# Reflective lookups whose owner is computed (`x.getClass()`), so bytecode cannot
# prove the owner is a Minecraft type. Reviewed by hand; listed so they are not
# mistaken for "checked".
DYNAMIC_OWNER_BACKLOG = {
    "ScreenIntrospection#getSummary": "duck-typed label probe over arbitrary list entries; "
                                      "misses after remap, but entryLabel now falls through to "
                                      "AbstractWidget#getMessage / ObjectSelectionList.Entry#getNarration",
    "ScreenIntrospection#getLevelName": "same probe chain as getSummary",
}
# Resolved 2026-07-26 by deleting the reflection outright — all three called PUBLIC
# API through getClass(), so a typed call is correct after remap AND compiler-checked:
#   ClientEventDetector#getString -> Component#getString via pattern-match
#   ClientEventDetector#isDone    -> AdvancementProgress#isDone
#   ClientEventDetector#id        -> AdvancementHolder#id
# The surrounding ClientAdvancements progress-map lookup is NOT in either list: it
# selects the field by TYPE (first Map-typed field), carries no member-name literal,
# and is therefore already remap-safe.

REFLECT_CALL = re.compile(
    r"//\s*Method java/lang/Class\.(getDeclaredField|getField|getDeclaredMethod|getMethod):")
LDC_CLASS = re.compile(r"\bldc\w*\s+#\d+\s*//\s*class\s+(net/minecraft/\S+)")
LDC_STRING = re.compile(r"\bldc\w*\s+#\d+\s*//\s*String\s+(\S+)")
INSTRUCTION = re.compile(r"^\s+\d+: \S")
# A name Loom already rewrote (intermediary) is fine; a Mojang name is not.
REMAPPED_OWNER = re.compile(r"net/minecraft/(class_\d+|.*\$class_\d+)")

# How far back to look for the owner/name operands. It must clear the argument-array
# construction that `getDeclaredMethod(name, Class...)` emits between them: a 5-param
# lookup (ClientInput#keyPress) puts 21 instructions -- anewarray + 5x dup/iconst/
# getstatic TYPE/aastore -- between the owner `ldc` and the call. A window of 14 missed
# it. The walk stops at the previous reflective call so two adjacent lookups can never
# borrow each other's operands.
WINDOW = 64


def find_jar(explicit: str | None) -> pathlib.Path:
    if explicit:
        p = pathlib.Path(explicit)
        if not p.is_file():
            sys.exit(f"remap-safety: --jar {p} does not exist")
        return p
    libs = ROOT / "fabric" / "build" / "libs"
    cands = [p for p in libs.glob("agent_driver-fabric-*.jar")
             if not p.name.endswith(("-sources.jar", "-dev-shadow.jar"))]
    if not cands:
        sys.exit("remap-safety: no remapped fabric jar found.\n"
                 f"  looked in {libs}\n"
                 "  build it first:  ./gradlew :fabric:build")
    return max(cands, key=lambda p: p.stat().st_mtime)


AW_PATH = ROOT / "common" / "src" / "main" / "resources" / "agent_driver.accesswidener"
AT_PATH = ROOT / "neoforge" / "src" / "main" / "resources" / "META-INF" / "accesstransformer.cfg"


def check_widener_sync() -> list[str]:
    """The access widener (fabric/common) and access transformer (neoforge) must open
    exactly the same members.

    They are separate files only because architectury-loom 1.11 cannot convert one into
    the other for NeoForge (NeoForgeExtensionAPI has no convertAccessWideners). Nothing
    in the build ties them together, so a member added to one and not the other compiles
    and ships: the feature works on one loader and throws IllegalAccessError on the
    other, at runtime, in whichever code path happens to touch it. This check is the
    substitute for the build-level guarantee we don't get.
    """
    if not AW_PATH.exists() and not AT_PATH.exists():
        return []
    problems = []
    if AW_PATH.exists() != AT_PATH.exists():
        return [f"WIDENER-SYNC: only one of {AW_PATH.name} / {AT_PATH.name} exists"]

    aw = set()
    for raw in AW_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or line.startswith("accessWidener"):
            continue
        p = line.split()
        # accessible|mutable|extendable  field|method|class  <owner> [<name> <desc>]
        if len(p) >= 4 and p[1] in ("field", "method"):
            aw.add((p[1], p[2].replace("/", "."), p[3]))

    at = set()
    for raw in AT_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        p = line.split()
        if len(p) >= 3:
            # <access> <class> <member>[<descriptor>]  -- a method carries "(" in the member
            member = p[2]
            kind = "method" if "(" in member else "field"
            at.add((kind, p[1], member.split("(", 1)[0]))

    for missing in sorted(aw - at):
        problems.append(f"WIDENER-SYNC: {missing[1]}#{missing[2]} ({missing[0]}) is in "
                        f"{AW_PATH.name} but not {AT_PATH.name} — open on fabric, private on neoforge")
    for missing in sorted(at - aw):
        problems.append(f"WIDENER-SYNC: {missing[1]}#{missing[2]} ({missing[0]}) is in "
                        f"{AT_PATH.name} but not {AW_PATH.name} — open on neoforge, private on fabric")
    return problems


def load_mappings() -> tuple[dict, dict] | None:
    """-> ({intermediary_class: named_class}, {(named_class, kind, named_member): [(desc, inter)]})

    Reads the tiny v2 table Loom already downloaded. Returns None when it is absent
    (a fresh clone that has never run a Loom task), in which case the caller falls
    back to the heuristic and says so.
    """
    base = pathlib.Path.home() / ".gradle" / "caches" / "fabric-loom"
    files = sorted(base.glob("*/loom.mappings.*-v2/mappings.tiny"))
    if not files:
        return None
    path = max(files, key=lambda p: p.stat().st_mtime)
    inter2named: dict[str, str] = {}
    members: dict[tuple[str, str, str], list[tuple[str, str]]] = {}
    cur_named = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        p = line.split("\t")
        if line.startswith("c\t") and len(p) > 3:
            inter2named[p[2]] = p[3]
            cur_named = p[3]
        elif cur_named and len(p) >= 6 and p[1] in ("f", "m"):
            members.setdefault((cur_named, p[1], p[5]), []).append((p[2], p[4]))
    return inter2named, members


def classes_using_reflection(jar: pathlib.Path) -> list[str]:
    """Cheap constant-pool prefilter so javap runs on a handful of classes."""
    needles = [b"getDeclaredField", b"getField", b"getDeclaredMethod", b"getMethod"]
    out = []
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not (n.startswith("net/magicterra/") and n.endswith(".class")):
                continue
            blob = z.read(n)
            if any(b"\x01" + struct.pack(">H", len(x)) + x in blob for x in needles):
                out.append(n[:-6].replace("/", "."))
    return sorted(out)


def scan(jar: pathlib.Path, names: list[str]) -> list[tuple[str, str, str, str]]:
    """-> [(simpleClass, memberName, remappedOwner, "f"|"m")] candidate lookups."""
    if not names:
        return []
    javap = shutil.which("javap") or "javap"
    proc = subprocess.run([javap, "-p", "-c", "-cp", str(jar), *names],
                          capture_output=True, text=True, errors="replace")
    if proc.returncode != 0 and not proc.stdout:
        sys.exit(f"remap-safety: javap failed\n{proc.stderr[:2000]}")

    findings: list[tuple[str, str, str, str]] = []
    current = "?"
    code: list[str] = []          # instruction lines of the class, in order
    owners: list[str] = []        # index -> class name, for the same positions
    for line in proc.stdout.splitlines():
        m = re.match(r"(?:public |final |abstract |static |)*class ([\w.$]+)", line.strip())
        if m:
            current = m.group(1).rsplit(".", 1)[-1]
        if not INSTRUCTION.match(line):
            continue
        code.append(line)
        owners.append(current)

    for i, line in enumerate(code):
        ms = LDC_STRING.search(line)
        if not ms:
            continue
        # A STATIC owner compiles to `ldc class X` IMMEDIATELY before `ldc String name`.
        # A dynamic one (`entry.getClass().getMethod("getSummary")`) has an
        # `invokevirtual getClass()` there instead, so it is correctly skipped -- the
        # adjacency requirement is what keeps unrelated `ldc class` operands from
        # being paired with a name they have nothing to do with.
        if i == 0:
            continue
        mo = LDC_CLASS.search(code[i - 1])
        if not (mo and REMAPPED_OWNER.fullmatch(mo.group(1))):
            continue
        # The reflective call follows, past any getDeclaredMethod(...) argument array.
        for nxt in code[i + 1:i + 1 + WINDOW]:
            if LDC_STRING.search(nxt):
                break  # a new lookup started; this one was not reflective
            mr = REFLECT_CALL.search(nxt)
            if mr:
                # Field vs method matters: MouseHandler has BOTH a private field
                # `xpos` and a public accessor `xpos()`, and only one of the two
                # mapping entries describes what getDeclaredField asked for.
                kind = "f" if "Field" in mr.group(1) else "m"
                findings.append((owners[i], ms.group(1), mo.group(1), kind))
                break
    # dedupe, keep order
    seen, uniq = set(), []
    for f in findings:
        if f[:2] not in seen:
            seen.add(f[:2])
            uniq.append(f)
    return uniq


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--jar", help="remapped fabric jar (default: newest in fabric/build/libs)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    jar = find_jar(args.jar)
    names = classes_using_reflection(jar)
    candidates = scan(jar, names)

    maps = load_mappings()
    confirmed: list[tuple[str, str, str]] = []
    cleared: list[tuple[str, str, str]] = []
    ambiguous: list[tuple[str, str, str]] = []
    if maps is None:
        confirmed = candidates
    else:
        inter2named, members = maps
        for cls, mem, owner, kind in candidates:
            named_owner = inter2named.get(owner)
            if named_owner is None:
                ambiguous.append((cls, mem, f"{owner} (owner not in mapping table)"))
                continue
            entries = members.get((named_owner, kind, mem), [])
            if not entries:
                # No mapping entry: intermediary keeps this name, so the lookup
                # still resolves. Component.getString() is the canonical example.
                cleared.append((cls, mem, named_owner))
            elif len(entries) > 1:
                ambiguous.append((cls, mem, f"{named_owner} ({len(entries)} overloads)"))
            else:
                confirmed.append((cls, mem, f"{named_owner} -> {entries[0][1]}"))

    found_keys = {f"{cls}#{mem}" for cls, mem, _ in confirmed}
    baseline_keys = set(BASELINE)

    new = sorted(found_keys - baseline_keys)
    stale = sorted(baseline_keys - found_keys)

    if args.verbose:
        print(f"jar      : {jar.relative_to(ROOT)}")
        print(f"scanned  : {len(names)} class(es) that reference java.lang.Class reflection")
        print(f"mappings : {'ground truth (Loom tiny v2)' if maps else 'NOT FOUND - heuristic only'}")
        for cls, mem, owner in sorted(confirmed):
            print(f"  breaks : {cls}#{mem}  ({owner})")
        for cls, mem, owner in sorted(cleared):
            print(f"  safe   : {cls}#{mem}  ({owner}: no intermediary entry, name survives)")

    sync = check_widener_sync()
    for s in sync:
        print(s)
    for cls, mem, why in sorted(ambiguous):
        print(f"AMBIGUOUS: {cls}#{mem} on {why} - the scan cannot tell which overload the "
              f"reflective lookup asks for; confirm by hand and baseline it if it renames")
    for k in new:
        print(f"REMAP-UNSAFE: {k} reflects a Mojang-mapped name that intermediary renames; "
              f"guaranteed NoSuchField/MethodException in the shipped fabric jar")
    for k in stale:
        print(f"STALE-BASELINE: {k} is listed in BASELINE but no longer detected - drop the entry")

    if new or stale or ambiguous or sync:
        print("\nSee the module docstring for the remediation options.")
        return 1

    print(f"remap-safety gate OK: {len(found_keys)} confirmed remap-unsafe site(s) on the "
          f"tracked baseline, {len(cleared)} candidate(s) cleared against the mapping table, "
          f"{len(DYNAMIC_OWNER_BACKLOG)} with dynamic owners (hand-tracked), 0 new; "
          f"widener/transformer in sync")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
