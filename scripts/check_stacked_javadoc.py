"""Manual gate: no javadoc block may be silently discarded by javac.

Javadoc attaches only the LAST doc comment immediately preceding a declaration.
Two /** */ blocks back to back, with nothing but whitespace between them, mean the
EARLIER one is thrown away without a warning -- so it documents nothing, or worse,
sits on top of a member it does not describe and reads as that member's contract.

NOT WIRED TO ANYTHING. No gradle task and no CI workflow invokes this file, or any
of the five scripts/check_*.py beside it -- verified by grepping *.gradle and
.github; the single hit is a COMMENT in neoforge/build.gradle:157. They are hand-run
gates listed in docs/dev/testing.md. Nothing turns red on its own if this rots: someone has to
type `python scripts/check_stacked_javadoc.py`. Do not read a green build as a green
here, and do not assume a later reader knows that -- an instrument nobody calls is
not an instrument.

BASELINE: this family was 51 blocks when first measured (main 22 / testmod 29), and
is 0 now. The gate exists so it stays at 0: the sites were created one at a time, by
inserting a member between a doc and the member it belonged to, and nothing warned
about any of them. Do not take a number written in this header as the current count
-- run the scan. A count in prose is a claim about the past.

WHAT THE 51 TURNED OUT TO BE, since the shape decides the repair: a doc separated
from its member by an inserted member (move it back); one document cut in half, the
second half opening with <p> or @param (rejoin the halves); two generations of doc
for the same member (merge, and say which generation the code follows -- one pair
argued OPPOSITE rules, and reattaching the loser would have reintroduced the defect
it was written before); and a superseded copy whose member had moved away entirely
(delete, after checking the new home carries the same text). Only the last is a
deletion, and it is the one to be slowest about.

TODO -- THE WORSE FORM OF THIS DEFECT HAS NO INSTRUMENT YET.
    An abandoned block that lands above an UNDOCUMENTED member is not discarded:
    javac silently ADOPTS it, and the member now ships someone else's contract as
    its own. Same cause, worse outcome, and structurally invisible to this scanner
    -- the 51 were catchable only because each happened to land on another doc.
    The missing reading is a MISMATCH between a block and the declaration it is
    attached to, and it does not need natural language to find:
      * an @param tag naming a parameter the attached declaration does not have;
      * an @return on a void member, or none on a non-void one that documents a
        returned value in prose;
      * a first sentence whose {@code x} / {@link #x} subject names a member that
        exists elsewhere in the file.
    The first of those is purely mechanical and would have caught the whole family
    a generation earlier. Nobody has written it. Write it rather than widening this
    scanner: this one answers "was a block dropped", that one answers "is this block
    on the right member", and they are different questions.

A MECHANICAL SHORTCUT THAT DOES NOT WORK, so nobody tries it twice: "the surviving
block opens with <p> or @param, so the two were one document" covers only 5 of the
51. The other 46 had to be read one at a time.

TWO MORE THINGS THIS CANNOT SEE:
  * Which HALF of a split document is missing. A description and its @return can end
    up hundreds of lines apart in the same file (measured: Walker.adoptPath, whose
    surviving half was a lone @return block that reads like a complete document).
    This reports that a block was dropped, never what it was separated from.
  * A block comment deliberately demoted to an ordinary one. Section headers are
    legitimately not javadoc and are invisible here by design -- which is also why
    demoting a /** */ to silence this gate needs the same evidence as deleting it.

AND THE RULE THE WHOLE FAMILY TEACHES: a doc's POSITION relative to code is not its
meaning. Every one of the 51 was created by trusting position. So when reattaching
one invalidates a neighbour's "the survey above" / "see below", replace the
direction with the NAME. Positions rot; names do not.

Exit codes
    0  no discarded blocks           (the only passing state)
    1  at least one discarded block  (reattach or rejoin it)
    2  the detector failed its own calibration and refused to scan
"""
import os
import sys

BACKSLASH = chr(92)
NL = chr(10)

DEFAULT_ROOTS = [
    "common/src/main/java",
    "common/src/testmod/java",
    "common/src/test",
    "fabric/src",
    "neoforge/src",
]


def block_comments(src):
    """[(start, end, is_javadoc)] for every /* */ block in source order.

    String and char literals and // line comments are skipped, so a '/*' inside a
    string literal is never mistaken for the start of a comment.
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            q = c
            i += 1
            while i < n:
                if src[i] == BACKSLASH:
                    i += 2
                    continue
                if src[i] == q:
                    i += 1
                    break
                if src[i] == NL and q == '"':
                    break
                i += 1
            continue
        if c == '/' and i + 1 < n:
            if src[i + 1] == '/':
                while i < n and src[i] != NL:
                    i += 1
                continue
            if src[i + 1] == '*':
                start = i
                i += 2
                # /** opens javadoc; /**/ is an empty ordinary block, not javadoc
                is_jd = i < n and src[i] == '*' and not (i + 1 < n and src[i + 1] == '/')
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    i += 1
                i = min(i + 2, n)
                out.append((start, i, is_jd))
                continue
        i += 1
    return out


def dropped_blocks(src):
    """Javadoc blocks javac will discard: a javadoc immediately followed by another."""
    cs = block_comments(src)
    return [a for a, b in zip(cs, cs[1:])
            if a[2] and b[2] and src[a[1]:b[0]].strip() == ""]


# Positive and negative cases the detector must reproduce before it is allowed to
# scan. Two independently written detectors disagreed (29 vs 51) until both were
# calibrated -- the first missed every single-line first block -- and an
# uncalibrated count gets quoted as a conclusion by the next reader.
CASES = [
    ("two javadoc in a row",     "/** a */" + NL + "/** b */" + NL + "void f(){}", 1),
    ("three in a row",           "/** a */" + NL + "/** b */" + NL + "/** c */" + NL + "int x;", 2),
    ("javadoc then plain block", "/** a */" + NL + "/* b */" + NL + "void f(){}", 0),
    ("plain block then javadoc", "/* a */" + NL + "/** b */" + NL + "void f(){}", 0),
    ("single javadoc",           "/** a */" + NL + "void f(){}", 0),
    ("javadoc, code, javadoc",   "/** a */" + NL + "void f(){}" + NL + "/** b */" + NL + "void g(){}", 0),
    ("'/**' inside a string",    'String s = "/** x */";' + NL + "/** b */" + NL + "void f(){}", 0),
    ("multiline first block",    "/**" + NL + " * a" + NL + " */" + NL + "/** b */" + NL + "void f(){}", 1),
    ("empty plain block /**/",   "/**/" + NL + "/** b */" + NL + "void f(){}", 0),
    ("blank line between",       "/** a */" + NL + NL + "/** b */" + NL + "void f(){}", 1),
]


def calibrate():
    bad = 0
    for name, src, want in CASES:
        got = len(dropped_blocks(src))
        if got != want:
            bad += 1
            print("FAIL  calibration {!r}: got {} want {}".format(name, got, want))
    return bad == 0


def first_line(src, start):
    """The dropped block's opening sentence, so the report names it rather than a number."""
    tail = src[start:start + 400].replace("/**", " ")
    for raw in tail.split(NL):
        t = raw.strip().lstrip("*").strip()
        if t and not t.startswith("*/"):
            return t[:88]
    return ""


def main(roots):
    if not calibrate():
        print(NL + "check_stacked_javadoc: detector failed its own calibration -- refusing to scan")
        return 2

    found = []
    scanned = 0
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            for f in sorted(filenames):
                if not f.endswith(".java"):
                    continue
                p = os.path.join(dirpath, f).replace(os.sep, "/")
                scanned += 1
                src = open(p, encoding="utf-8").read()
                for start, _e, _j in dropped_blocks(src):
                    found.append((p, src[:start].count(NL) + 1, first_line(src, start)))

    if not found:
        print("stacked-javadoc gate OK: no javadoc block is discarded "
              "({} files scanned)".format(scanned))
        return 0

    print("stacked-javadoc gate FAILED: {} javadoc block(s) javac will discard "
          "({} files scanned):".format(len(found), scanned))
    for p, ln, text in found:
        print("    {}:{}".format(p, ln))
        print("        {}".format(text))
    print()
    print("Each one is a doc comment immediately followed by another, so javac keeps only")
    print("the second. Move the first onto the member it describes, or -- if both describe")
    print("the same member -- join them into a single block. Do not reword while moving:")
    print("a move can be checked against the diff, a rewrite cannot.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or DEFAULT_ROOTS))
