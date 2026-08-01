#!/usr/bin/env python3
"""Gate: every scene's block footprint must fit inside its force-loaded arena.

WHY THIS EXISTS
---------------
StageWrightHarness lays scenes out on a 1-D grid along X (`originFor(slot) =
GRID_X0 + slot*GRID_STEP`, GRID_STEP=512) and force-loads a (2r+1)x(2r+1)
chunk window around the origin chunk, where r is the scene's `chunkRadius`
(default 1). GRID_X0/GRID_Z0 are chunk-aligned, so the usable offsets are

    dx, dz in [-16r, 16r+15]

`Scene.withChunkRadius(r)` widens it. That relation — "the terrain this body
builds fits in the window the harness forced" — is currently maintained BY
HAND: a scene author works out the span, writes it in a javadoc paragraph (see
WorldDriverWaterCrossScenes' class comment, which does this exactly right) and
adds `.withChunkRadius(n)` to the registration. Nothing checks it.

The failure mode is silent and looks like flakiness. `setBlockAndUpdate` on a
chunk outside the forced window still succeeds — it loads the chunk on demand —
so the scene builds fine and usually passes. What it loses is the guarantee
PREP exists to provide: `allChunksLoaded()` only waits for the forced window, so
terrain outside it is written into chunks whose load/tick state is nobody's
contract. Scenes that overflow therefore fail *sometimes*, in ways that read as
bot bugs rather than arena bugs — the same trap CLAUDE.local.md documents for
dirty-world reuse ("which scenes fail varies per run, which reads exactly like
flakiness. It isn't.").

WHAT IT CHECKS
--------------
For every registered scene, statically evaluate the offsets its body applies to
the origin as INTERVALS, using the method's own `final int` bindings and `for`
loop bounds, and compare the hull against the scene's declared window. Four
terrain idioms are in the corpus and all four are modelled:

    ctx.setBlock(dx, dy, dz, b)            offsets ARE the arguments
    new BlockPos(cx + dx, y, cz + dz)      offset in the position expression
    for (int x = cx; x <= cx + RUN; x++)   offset in the loop header
    ctx.origin().above(2)                  BlockPos arithmetic, X/Z fixed

The first needs no analysis at all. That is the concrete case for the scene DSL:
a body written with `ctx.setBlock` states its footprint in a form a gate can
read, while the other three have to be recovered by interval-evaluating Java
source — and each one of them was, at some point in writing this, silently
scored as "fits" because the analyzer could not see it.

Three outcomes per scene, and the middle one is the point:

  OK        footprint hull fits the declared window
  OVERFLOW  it does not — hard failure, with the offending offset and the
            radius that would cover it
  UNKNOWN   an offset expression did not reduce to an interval (a helper call,
            a runtime value). Reported and counted, never silently passed: a
            gate that treats "I couldn't tell" as "fine" is how the invariant
            got unchecked in the first place. --strict makes them fail.

This is a source-level gate: it needs no build, no run, and no game. It cannot
regress the testkit because it does not execute it.

    python scripts/check_scene_arena.py
    python scripts/check_scene_arena.py --verbose   # per-scene hull table
    python scripts/check_scene_arena.py --self-test

Exit 0 = every scene fits (or is UNKNOWN without --strict). Exit 1 = overflow.
"""
from __future__ import annotations

import argparse
import ast
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCENE_DIR = ROOT / "common/src/testmod/java/net/magicterra/worlddriver/bot/testkit/scene"

# Mirrors StageWrightHarness.forceChunks + the chunk-aligned GRID_X0/GRID_Z0.
# radius r forces chunks [-r, +r] around the origin chunk; the origin sits at
# the chunk's low corner, so blocks [-16r, 16r+15] are covered.
def window(radius: int) -> tuple[int, int]:
    return (-16 * radius, 16 * radius + 15)


def radius_for(lo: int, hi: int) -> int:
    """Smallest radius whose window contains [lo, hi]."""
    r = 1
    while r < 64:
        w = window(r)
        if w[0] <= lo and hi <= w[1]:
            return r
        r += 1
    return r


class Unknown(Exception):
    """An expression that did not reduce to an integer interval."""


# ---------------------------------------------------------------- expressions

def eval_interval(expr: str, env: dict[str, tuple[int, int]]) -> tuple[int, int]:
    """Evaluate a Java integer expression to an [lo, hi] interval.

    Java and Python agree on + - * and parentheses for ints, which is all these
    scene bodies use for coordinates. Anything else — a method call, an unknown
    name, a bit op — raises Unknown rather than guessing.
    """
    expr = expr.replace("_", "")  # Java's 100_000 digit separators
    try:
        tree = ast.parse(expr, mode="eval")
    except SyntaxError as e:
        raise Unknown(f"unparseable: {expr}") from e
    return _walk(tree.body, env)


def _walk(node, env) -> tuple[int, int]:
    if isinstance(node, ast.Constant):
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise Unknown(f"non-numeric constant {node.value!r}")
        v = int(node.value) if float(node.value).is_integer() else node.value
        return (v, v)
    if isinstance(node, ast.Name):
        if node.id not in env:
            raise Unknown(f"unbound name {node.id}")
        return env[node.id]
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        lo, hi = _walk(node.operand, env)
        return (-hi, -lo)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.UAdd):
        return _walk(node.operand, env)
    if isinstance(node, ast.BinOp):
        a, b = _walk(node.left, env), _walk(node.right, env)
        if isinstance(node.op, ast.Add):
            return (a[0] + b[0], a[1] + b[1])
        if isinstance(node.op, ast.Sub):
            return (a[0] - b[1], a[1] - b[0])
        if isinstance(node.op, ast.Mult):
            c = [a[0] * b[0], a[0] * b[1], a[1] * b[0], a[1] * b[1]]
            return (min(c), max(c))
        if isinstance(node.op, ast.Div):  # Java `/` on ints truncates; interval hull is safe
            if b[0] <= 0 <= b[1]:
                raise Unknown("division by an interval spanning zero")
            c = [a[0] / b[0], a[0] / b[1], a[1] / b[0], a[1] / b[1]]
            return (int(min(c)) - 1, int(max(c)) + 1)
        raise Unknown(f"unsupported operator {type(node.op).__name__}")
    raise Unknown(f"unsupported node {type(node).__name__}")


# ---------------------------------------------------------------- Java slicing

def method_body(src: str, name: str) -> str | None:
    """Source text of `... void <name>(SceneContext ...) { ... }`, brace-matched."""
    m = re.search(r"\b(?:private|public|static|final|\s)+void\s+" + re.escape(name)
                  + r"\s*\(\s*SceneContext\b[^)]*\)\s*\{", src)
    if not m:
        return None
    i, depth = m.end() - 1, 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i + 1:j]
    return None


def strip_comments(s: str) -> str:
    """Drop // and /* */ so prose about coordinates never counts as code.

    The scene files document spans in comments (`// dx,dz 0..16`), which is
    exactly the text that would otherwise be mistaken for an offset.
    """
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    return s


def bindings(body: str) -> dict[str, tuple[int, int]]:
    """`final int a = 3, b = a + 1;` and `for (int dx = -2; dx <= 5; dx++)`.

    Loop variables become the interval of the whole loop; a name bound more than
    once takes the hull of every binding, which is the conservative direction.
    """
    env: dict[str, tuple[int, int]] = {}

    def bind(name: str, iv: tuple[int, int]) -> None:
        if name in env:
            env[name] = (min(env[name][0], iv[0]), max(env[name][1], iv[1]))
        else:
            env[name] = iv

    # A `for (int dx = -6; ...)` header also looks like a declaration of dx, and
    # taking it as one would pin dx to its START value instead of its range.
    # Collect the headers, then blank them out of the declaration scan.
    loops = list(re.finditer(r"for\s*\(\s*int\s+(\w+)\s*=\s*([^;]+);\s*"
                             r"(\w+)\s*(<=|<|>=|>)\s*([^;]+);", body))
    decl_src = body
    for m in reversed(loops):
        decl_src = decl_src[:m.start()] + " " * (m.end() - m.start()) + decl_src[m.end():]

    def scan_decls() -> None:
        # final int a = ..., b = ...;  (and plain `int a = ...;`)
        for m in re.finditer(r"\b(?:final\s+)?int\s+([^;=(){}]*=[^;{}]*);", decl_src):
            for part in _split_top(m.group(1), ","):
                if "=" not in part:
                    continue
                lhs, rhs = part.split("=", 1)
                nm = lhs.strip().split()[-1] if lhs.strip() else ""
                if not re.fullmatch(r"\w+", nm):
                    continue
                try:
                    bind(nm, eval_interval(rhs.strip(), env))
                except Unknown:
                    continue

    def scan_loops() -> None:
        for m in loops:
            var, start, cmp_var, op, bound = (m.group(1), m.group(2), m.group(3),
                                              m.group(4), m.group(5))
            if cmp_var != var:
                continue
            try:
                a = eval_interval(start, env)
                b = eval_interval(bound, env)
            except Unknown:
                continue
            if op in ("<=", "<"):
                hi = b[1] - 1 if op == "<" else b[1]
                bind(var, (a[0], max(a[1], hi)))
            else:
                lo = b[0] + 1 if op == ">" else b[0]
                bind(var, (min(a[0], lo), a[1]))

    # Declarations first (a loop bound may name one), then loops, then once more:
    # a declaration inside a loop body may name the loop variable. Two rounds
    # reach a fixed point for every shape these files use; more would only widen
    # already-widened hulls, which is the safe direction anyway.
    for _ in range(2):
        scan_decls()
        scan_loops()
    return env


def _split_top(s: str, sep: str) -> list[str]:
    """Split on `sep` at paren/bracket depth 0."""
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        if ch == sep and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    out.append(cur)
    return out


# `cx`/`cz` is the dominant convention but not a rule — WorldDriverStationScenes
# binds the same thing to `x0`/`z0`. Read the names out of the body instead of
# assuming them, or those scenes silently contribute no footprint at all.
ORIGIN_BIND_RE = re.compile(r"\b(\w+)\s*=\s*ctx\.origin\(\)\.get([XZ])\(\)")


def origin_names(body: str) -> list[str]:
    names = set(m.group(1) for m in ORIGIN_BIND_RE.finditer(body))
    # ...and pure aliases of those: `final int ax = cx, az = cz;`
    # (WorldDriverStationScenes). Arithmetic is NOT followed — `floorY = ...getY()
    # + 20` is a Y binding and must not be read as an X/Z origin.
    for _ in range(3):
        for m in re.finditer(r"\b(\w+)\s*=\s*(\w+)\s*[,;]", body):
            if m.group(2) in names:
                names.add(m.group(1))
    return sorted(names) or ["cx", "cz"]


# Third idiom: `BlockPos anchor = ctx.origin();` then `anchor.above(2)`. Pure
# BlockPos arithmetic — the safest form there is, since above/below cannot leave
# the origin's X/Z column, but a coordinate-expression scan sees no offset at all
# and would score the body unresolved.
Y_ONLY = {"above", "below"}
XZ_SHIFT = {"east": 1, "west": 1, "north": 1, "south": 1}


def pos_names(body: str) -> tuple[dict[str, tuple[int, int]], list[str]]:
    """BlockPos locals derived from ctx.origin(), as X/Z offset intervals."""
    pos: dict[str, tuple[int, int]] = {}
    bad: list[str] = []
    for m in re.finditer(r"BlockPos\s+(\w+)\s*=\s*ctx\.origin\(\)\s*;", body):
        pos[m.group(1)] = (0, 0)
    for _ in range(3):
        for m in re.finditer(r"BlockPos\s+(\w+)\s*=\s*(\w+)\.(\w+)\(([^;]*)\)\s*;", body):
            name, src, meth, arg = m.group(1), m.group(2), m.group(3), m.group(4)
            if src not in pos or name in pos:
                continue
            lo, hi = pos[src]
            if meth in Y_ONLY:
                pos[name] = (lo, hi)
            elif meth in XZ_SHIFT:
                try:
                    n = eval_interval(arg.strip(), {})[1] if arg.strip() else 1
                except Unknown:
                    bad.append(f"BlockPos {name} = {src}.{meth}({arg.strip()[:20]})")
                    continue
                pos[name] = (lo - abs(int(n)), hi + abs(int(n)))
            else:
                bad.append(f"BlockPos {name} = {src}.{meth}(...) — unmodelled displacement")
    # The fixed-point loop revisits an unresolvable binding every round, since it
    # never lands in `pos`. Report each one once.
    return pos, list(dict.fromkeys(bad))


def offset_re(names: list[str]) -> re.Pattern:
    alt = "|".join(re.escape(n) for n in names)
    return re.compile(r"\b(" + alt + r")\b\s*(?:([+-])\s*)?")

# Only coordinates handed to a world read/write count as footprint. A far-away
# GOAL is not a footprint: wd.serverElytra aims an ElytraProcess at cx+400 so the
# bot has something to glide toward, runs 60 ticks and asserts only that it
# entered fall-flying — it never approaches that block, and no terrain is built
# there. Counting target positions flagged that scene as a 25-radius arena, which
# is the gate being wrong, not the scene.
WORLD_CALL_RE = re.compile(
    r"\.(setBlockAndUpdate|setBlock|removeBlock|destroyBlock|getBlockState|"
    r"assertBlock|rel|isWater|isEmptyBlock|getFluidState)\s*\(")


def world_call_args(body: str) -> list[str]:
    """Argument text of every world read/write call, paren-matched."""
    out = []
    for m in WORLD_CALL_RE.finditer(body):
        depth, arg = 1, ""
        for ch in body[m.end():]:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
                if depth == 0:
                    break
            arg += ch
        out.append(arg)
    return out


WRITE_RE = re.compile(r"\.setBlockAndUpdate\s*\(|\bctx\.setBlock\s*\(|\.destroyBlock\s*\(")


def coord_bindings(body: str, env: dict[str, tuple[int, int]]) -> dict[str, tuple[int, int]]:
    """Loop variables that ARE origin-relative coordinates, as offset intervals.

    The other half of the terrain idiom: instead of `new BlockPos(cx + dx, ...)`
    a body writes `for (int x = cx; x <= cx + RUN; x++) ... new BlockPos(x, y, z)`,
    putting the offset in the loop header where a position-argument scan cannot
    see it. A loop whose start or bound mentions cx/cz is such a coordinate; it is
    evaluated with cx = cz = 0 so the result is an offset, not an absolute.
    """
    names = origin_names(body)
    coords: dict[str, tuple[int, int]] = {}
    cenv = dict(env)
    for n in names:
        cenv[n] = (0, 0)
    origin_ref = re.compile(r"\b(" + "|".join(re.escape(n) for n in names) + r")\b")
    for m in re.finditer(r"for\s*\(\s*int\s+(\w+)\s*=\s*([^;]+);\s*"
                         r"(\w+)\s*(<=|<|>=|>)\s*([^;]+);", body):
        var, start, cmp_var, op, bound = (m.group(1), m.group(2), m.group(3),
                                          m.group(4), m.group(5))
        if cmp_var != var or not origin_ref.search(start + bound):
            continue
        try:
            a, b = eval_interval(start, cenv), eval_interval(bound, cenv)
        except Unknown:
            continue
        if op in ("<=", "<"):
            iv = (a[0], (b[1] - 1) if op == "<" else b[1])
        else:
            iv = ((b[0] + 1) if op == ">" else b[0], a[1])
        prev = coords.get(var)
        coords[var] = (min(prev[0], iv[0]), max(prev[1], iv[1])) if prev else iv
    return coords


def footprint(body: str, env: dict[str, tuple[int, int]]) -> tuple[int, int, list[str]]:
    """Interval hull of every origin offset reached by a world call.

    Two idioms contribute: `cx + <expr>` in a position argument, and a bare
    coordinate loop variable (see coord_bindings). `cx` alone is offset 0. An
    expression ends at the first top-level `,` or `)` — the argument boundary
    inside `new BlockPos(cx + dx, y, cz + dz)`.

    A body that writes blocks but yields NO attribution is reported unresolved,
    not zero. Silently scoring an unrecognised idiom as "fits" is how this
    invariant went unchecked in the first place.
    """
    lo, hi, unknown = 0, 0, []
    scope = "\n".join(world_call_args(body))
    coords = coord_bindings(body, env)
    attributed = 0

    # The DSL form: ctx.setBlock(dx, dy, dz, block) / ctx.rel(dx, dy, dz) state
    # their offsets as arguments, so the footprint is readable without recovering
    # anything. Scenes written this way need none of the machinery below — which
    # is the strongest argument for the DSL that this gate can make.
    for m in re.finditer(r"\bctx\.(setBlock|assertBlock|rel)\s*\(", body):
        depth, arg = 1, ""
        for ch in body[m.end():]:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
                if depth == 0:
                    break
            arg += ch
        parts = _split_top(arg, ",")
        if len(parts) < 3:
            continue
        for idx in (0, 2):                       # dx and dz; dy is unconstrained
            try:
                a, b = eval_interval(parts[idx].strip(), env)
            except Unknown as e:
                unknown.append(f"ctx.{m.group(1)} arg{idx}: {parts[idx].strip()[:40]}  ({e})")
                continue
            attributed += 1
            lo, hi = min(lo, int(a // 1)), max(hi, int(-(-b // 1)))

    for m in offset_re(origin_names(body)).finditer(scope):
        attributed += 1
        sign = m.group(2)
        if sign is None:
            continue  # bare cx/cz — offset 0, already in the hull
        expr, depth = "", 0
        for ch in scope[m.end():]:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                if depth == 0:
                    break
                depth -= 1
            elif ch in ",;" and depth == 0:
                break
            expr += ch
        try:
            a, b = eval_interval(expr.strip(), env)
        except Unknown as e:
            unknown.append(f"{m.group(1)} {sign} {expr.strip()[:40]}  ({e})")
            continue
        if sign == "-":
            a, b = -b, -a
        lo, hi = min(lo, int(a // 1)), max(hi, int(-(-b // 1)))

    for var, (a, b) in coords.items():
        if re.search(r"\b" + re.escape(var) + r"\b", scope):
            attributed += 1
            lo, hi = min(lo, a), max(hi, b)

    positions, pos_bad = pos_names(body)
    unknown.extend(pos_bad)
    for var, (a, b) in positions.items():
        if re.search(r"\b" + re.escape(var) + r"\b", scope):
            attributed += 1
            lo, hi = min(lo, a), max(hi, b)

    if attributed == 0 and WRITE_RE.search(body):
        unknown.append("body writes blocks but no offset was attributable to the origin "
                       "(unrecognised terrain idiom — the hull below is NOT trustworthy)")
    return lo, hi, unknown


# ---------------------------------------------------------------- registration

REG_RE = re.compile(
    r"Scene\.(of|canary)\(\s*\"(?P<name>[^\"]+)\"(?P<rest>.*?)(?=Scene\.(?:of|canary)\(|\Z)",
    re.S)
METHOD_RE = re.compile(r"::\s*(\w+)")
RADIUS_RE = re.compile(r"\.withChunkRadius\(\s*(\d+)\s*\)")


def registrations(src: str) -> list[tuple[str, str, int]]:
    """-> [(scene name, body method, declared radius)] from a `scenes()` list.

    `.withChunkRadius(n)` is frequently on its own continuation line, so the
    scan runs to the next `Scene.of(` rather than to end-of-line.
    """
    out = []
    for m in REG_RE.finditer(src):
        rest = m.group("rest")
        meth = METHOD_RE.search(rest)
        rad = RADIUS_RE.search(rest)
        # A lambda-bodied registration (stagewright's own Scenes.java uses them)
        # has no method to analyze. Report it as unanalyzed rather than skipping
        # it silently — an wd.* scene that switched to a lambda would otherwise
        # drop out of the gate's coverage without changing its output.
        out.append((m.group("name"), meth.group(1) if meth else None,
                    int(rad.group(1)) if rad else 1))
    return out


def analyze():
    rows, missing = [], []
    for path in sorted(SCENE_DIR.glob("*.java")):
        raw = path.read_text(encoding="utf-8")
        src = strip_comments(raw)
        for name, meth, radius in registrations(src):
            body = method_body(src, meth) if meth else None
            if body is None:
                missing.append((name, meth or "<lambda body>", path.name))
                continue
            env = bindings(body)
            lo, hi, unknown = footprint(body, env)
            rows.append({
                "scene": name, "file": path.name, "radius": radius,
                "lo": lo, "hi": hi, "unknown": unknown,
                "window": window(radius), "need": radius_for(lo, hi),
            })
    return rows, missing


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--verbose", action="store_true", help="print every scene's hull")
    ap.add_argument("--strict", action="store_true",
                    help="fail on scenes with unresolved offset expressions")
    ap.add_argument("--self-test", action="store_true", help="check the analyzer itself")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if not SCENE_DIR.is_dir():
        print(f"scene-arena: {SCENE_DIR} not found")
        return 1

    rows, missing = analyze()
    over = [r for r in rows if r["need"] > r["radius"]]
    unk = [r for r in rows if r["unknown"]]

    if args.verbose:
        for r in sorted(rows, key=lambda r: -(r["hi"] - r["lo"])):
            flag = "OVERFLOW" if r["need"] > r["radius"] else ("UNKNOWN " if r["unknown"] else "OK      ")
            print(f"  {flag} {r['scene']:34s} r={r['radius']} "
                  f"hull=[{r['lo']:+4d},{r['hi']:+4d}] window={r['window']}")

    for r in over:
        print(f"\nARENA OVERFLOW: {r['scene']} ({r['file']})")
        print(f"  declared chunkRadius={r['radius']} -> usable dx/dz {r['window']}")
        print(f"  body reaches [{r['lo']}, {r['hi']}]")
        print(f"  fix: .withChunkRadius({r['need']}) on the registration, or shrink the arena.")

    if args.strict and unk:
        for r in unk:
            print(f"\nARENA UNKNOWN: {r['scene']} ({r['file']}) — "
                  f"{len(r['unknown'])} offset expression(s) did not reduce:")
            for u in r["unknown"][:4]:
                print(f"    {u}")

    for name, meth, f in missing:
        print(f"  note: {name} -> {meth} not analyzable in {f}")

    if over:
        return 1
    if args.strict and (unk or missing):
        return 1
    print(f"scene-arena gate OK: {len(rows)} wd.* scene(s) fit their forced-chunk window; "
          f"{len(unk)} with unresolved offset expression(s), "
          f"{len(missing)} body not located "
          f"(scope: {SCENE_DIR.relative_to(ROOT).as_posix()})")
    return 0


# ---------------------------------------------------------------- self-test

def self_test() -> int:
    checks, fails = 0, 0

    def ck(label, got, want):
        nonlocal checks, fails
        checks += 1
        if got != want:
            fails += 1
            print(f"  FAIL {label}: got {got!r}, want {want!r}")
        else:
            print(f"  PASS {label}")

    ck("window(1)", window(1), (-16, 31))
    ck("window(2)", window(2), (-32, 47))
    ck("window(4)", window(4), (-64, 79))
    ck("radius_for(0,72)", radius_for(0, 72), 4)
    ck("radius_for(0,31)", radius_for(0, 31), 1)
    ck("radius_for(-20,10)", radius_for(-20, 10), 2)

    env = {"spanX": (56, 56), "dx": (-6, 72)}
    ck("interval literal", eval_interval("16", {}), (16, 16))
    ck("interval name+lit", eval_interval("spanX + 16", env), (72, 72))
    ck("interval sub", eval_interval("0 - dx", env), (-72, 6))
    try:
        eval_interval("helper(3)", {})
        ck("interval call rejected", "no raise", "Unknown")
    except Unknown:
        ck("interval call rejected", "Unknown", "Unknown")

    # A body shaped like the real ones: bindings feed loop bounds feed offsets.
    body = """
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int spanX = 56;
        for (int dx = -6; dx <= spanX + 16; dx++)
            for (int dz = -5; dz <= 5; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), AIR);
    """
    env2 = bindings(body)
    ck("bindings spanX", env2.get("spanX"), (56, 56))
    ck("bindings dx", env2.get("dx"), (-6, 72))
    lo, hi, unknown = footprint(body, env2)
    ck("footprint hull", (lo, hi), (-6, 72))
    ck("footprint clean", unknown, [])
    ck("that body needs r=4", radius_for(lo, hi), 4)

    # Comments must not contribute offsets: this prose would read as cx + 999.
    ck("comment stripped",
       footprint(strip_comments("// reaches cx + 999\n"
                                "level.setBlockAndUpdate(new BlockPos(cx + 2, y, cz), S);"), {})[1], 2)

    # An unresolvable offset must surface as UNKNOWN, never as a silent 0.
    _, _, unk = footprint("level.setBlockAndUpdate(new BlockPos(cx + helper(q), y, cz), S);", {})
    ck("unresolved -> UNKNOWN", len(unk), 1)

    # The wd.serverElytra shape: a far GOAL handed to a process is not terrain.
    elytra = """
        level.setBlockAndUpdate(new BlockPos(cx + 1, floorY, cz), STONE);
        driver.runProcess(new ElytraProcess(new BlockPos(cx + 400, floorY + 40, cz), null));
    """
    ck("far goal is not footprint", footprint(elytra, {"floorY": (0, 0)})[1], 1)

    # The wd.ledgeOvershoot idiom: the offset lives in the loop header and the
    # position argument is a bare `x`. Scoping to position arguments alone scored
    # this body [0,0] "fits" while it actually reaches +44.
    ledge = """
        final int RUN = 26;
        for (int x = cx + RUN + 1; x <= cx + RUN + 18; x++)
            for (int z = cz - 2; z <= cz + 2; z++)
                level.setBlockAndUpdate(new BlockPos(x, y, z), STONE);
    """
    lenv = bindings(ledge)
    ck("coord loop var", coord_bindings(ledge, lenv).get("x"), (27, 44))
    ck("loop-header hull", footprint(ledge, lenv)[:2], (-2, 44))

    # The safety net: an idiom the analyzer does not model must not read as OK.
    _, _, unres = footprint("level.setBlockAndUpdate(helper.pos(7), STONE);", {})
    ck("unmodelled write -> unresolved", len(unres), 1)
    ck("no writes -> no complaint", footprint("int q = 3;", {})[2], [])

    # DSL form: offsets are arguments, no cx/cz recovery needed. dy (arg 1) is
    # deliberately ignored — the arena is bounded in X/Z only.
    dsl = """
        for (int i = 0; i < 3; i++) ctx.setBlock(-9, 0, 4, Blocks.STONE);
        ctx.assertBlock(20, 99, -2, Blocks.AIR);
    """
    ck("DSL hull", footprint(dsl, bindings(dsl))[:2], (-9, 20))
    ck("DSL clean", footprint(dsl, bindings(dsl))[2], [])

    # The origin may be bound to any name — WorldDriverStationScenes uses x0/z0.
    named = """
        final int x0 = ctx.origin().getX(), z0 = ctx.origin().getZ();
        level.setBlockAndUpdate(new BlockPos(x0 + 12, y, z0 - 3), STONE);
    """
    ck("origin names read", origin_names(named), ["x0", "z0"])
    ck("origin names default", origin_names("nothing here"), ["cx", "cz"])
    ck("aliased origin hull", footprint(named, bindings(named))[:2], (-3, 12))
    ck("alias of alias",
       origin_names("final int cx = ctx.origin().getX();\nfinal int ax = cx, az = cz;"),
       ["ax", "cx"])

    # BlockPos chains: above/below cannot leave the origin column; east/west can.
    chain = """
        BlockPos anchor = ctx.origin();
        BlockPos water = anchor.above(2);
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
    """
    ck("above() stays at 0", pos_names(chain)[0].get("water"), (0, 0))
    ck("BlockPos chain hull", footprint(chain, {})[:2], (0, 0))
    ck("BlockPos chain clean", footprint(chain, {})[2], [])
    shifted = ("BlockPos a = ctx.origin();\nBlockPos b = a.east(9);\n"
               "level.setBlockAndUpdate(b, S);")
    ck("east() shifts", pos_names(shifted)[0].get("b"), (-9, 9))
    unmodelled = ("BlockPos a = ctx.origin();\nBlockPos b = a.mystery(3);\n"
                  "level.setBlockAndUpdate(b, S);")
    # Two complaints, both correct and both wanted: the displacement itself, and
    # the net effect that nothing at all was attributable to the origin.
    ck("unmodelled displacement flagged", len(footprint(unmodelled, {})[2]), 2)

    # Registration parsing: radius on a continuation line must still be seen.
    reg = '''scenes() { return List.of(
        Scene.of("ad.a", 200, X::a),
        Scene.of("ad.b", 200, X::b)
                .withChunkRadius(4)); }'''
    ck("registrations", registrations(reg), [("ad.a", "a", 1), ("ad.b", "b", 4)])
    ck("lambda registration surfaced",
       registrations('Scene.of("t.lam", 100, ctx -> { ctx.floor(5, S); }),'),
       [("t.lam", None, 1)])

    print(f"\n{checks - fails} PASS / {fails} FAIL")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
