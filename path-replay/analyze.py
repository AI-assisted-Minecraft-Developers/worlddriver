"""
path-replay/analyze.py  —  Path archive loader and per-step reporter.

Usage:
    analyze.py <archive.json> [--all] [--step N] [--replay PATH]
               [--deviation-threshold FLOAT]

The archive JSON is written by PathArchive.toMap() in the mod.  This script
loads it and prints a per-step report of node physics facts.

Jump encoding: nested {"jumpToNext": {"needed": bool, "feasible": bool}}.
Positions are [x, y, z] integer arrays.
"""

import argparse
import json
import sys
from pathlib import Path


def load_archive(path: str) -> dict:
    p = Path(path)
    if not p.exists():
        print(f"error: archive not found: {path}", file=sys.stderr)
        sys.exit(1)
    try:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        print(f"error: invalid JSON in {path}: {e}", file=sys.stderr)
        sys.exit(1)
    return data


def flatten_steps(archive: dict) -> list:
    """
    Flatten all segments' nodes into an ordered step list.

    Each entry is a dict with:
        seg_idx    — segment index
        node_idx   — node index within segment
        coord      — [x, y, z] from path[node_idx] (or None if path shorter)
        node       — raw node facts dict
        edge       — raw edge dict (edges[node_idx]) or None
    """
    steps = []
    for seg_idx, seg in enumerate(archive.get("segments", [])):
        path = seg.get("path", [])
        edges = seg.get("edges", [])
        nodes = seg.get("nodes", [])
        for node_idx, node in enumerate(nodes):
            coord = path[node_idx] if node_idx < len(path) else None
            edge = edges[node_idx] if node_idx < len(edges) else None
            steps.append(
                {
                    "seg_idx": seg_idx,
                    "node_idx": node_idx,
                    "coord": coord,
                    "node": node,
                    "edge": edge,
                }
            )
    return steps


def jump_summary(node: dict) -> str:
    """Return a human-readable jump string from either encoding."""
    # Authoritative encoding: nested jumpToNext
    jtn = node.get("jumpToNext")
    if isinstance(jtn, dict):
        needed = jtn.get("needed", False)
        feasible = jtn.get("feasible", True)
    else:
        # Flat fallback (defensive, not currently emitted by mod)
        needed = node.get("jumpNeeded", False)
        feasible = node.get("jumpFeasible", True)

    if not needed:
        return "jump:no"
    return f"jump:needed feasible:{feasible}"


def format_step(step_num: int, entry: dict) -> str:
    """Format one step as a single line for --all / --step output."""
    node = entry["node"]
    coord = entry["coord"]
    edge = entry["edge"]

    coord_str = f"[{coord[0]},{coord[1]},{coord[2]}]" if coord else "?"
    move = edge["move"] if edge else "-"
    cost = edge["cost"] if edge else "-"
    has_break = bool(edge and edge.get("break"))
    has_place = bool(edge and edge.get("place"))

    ceiling = node.get("ceilingForces", "none") or "none"
    hazard = node.get("footHazard") or "-"
    jump = jump_summary(node)

    flags = []
    if has_break:
        flags.append("break")
    if has_place:
        flags.append("place")
    if node.get("inWaterFoot"):
        flags.append("water")
    if node.get("submergedEye"):
        flags.append("submerged")
    if not node.get("underfootSolid"):
        flags.append("no-ground")
    flags_str = ",".join(flags) if flags else "-"

    fit = (
        ("S" if node.get("fitStand") else ".")
        + ("C" if node.get("fitCrouch") else ".")
        + ("W" if node.get("fitCrawl") else ".")
    )

    return (
        f"step {step_num:4d}  seg={entry['seg_idx']} node={entry['node_idx']}"
        f"  pos={coord_str}  move={move}  cost={cost}"
        f"  fit={fit}  ceiling={ceiling}  hazard={hazard}"
        f"  {jump}  fall={node.get('fall', 0.0):.1f}"
        f"  flags=[{flags_str}]"
    )


def print_header(archive: dict) -> None:
    h = archive.get("header", {})
    version = archive.get("version", "?")
    kind = archive.get("kind", "?")
    print(
        f"Path archive v{version} kind={kind}"
        f"  goal={h.get('goalDesc', '?')}"
        f"  outcome={h.get('outcome', '?')}"
        f"  segments={len(archive.get('segments', []))}"
    )
    print("-" * 80)


def main():
    parser = argparse.ArgumentParser(
        description="Analyze a path-archive JSON written by the agent-driver mod."
    )
    parser.add_argument("archive", help="Path to the .json archive file.")
    parser.add_argument(
        "--all",
        action="store_true",
        help="Print one line per step for all steps.",
    )
    parser.add_argument(
        "--step",
        type=int,
        metavar="N",
        help="Print facts for a single step number.",
    )
    parser.add_argument(
        "--replay",
        metavar="PATH",
        help="(Reserved for next task) path to output a replay file.",
    )
    parser.add_argument(
        "--deviation-threshold",
        type=float,
        default=1.5,
        metavar="FLOAT",
        help="Deviation threshold for flagging off-path ticks (default 1.5).",
    )
    args = parser.parse_args()

    archive = load_archive(args.archive)
    steps = flatten_steps(archive)

    if args.all:
        print_header(archive)
        for i, entry in enumerate(steps):
            print(format_step(i, entry))
        return

    if args.step is not None:
        if args.step < 0 or args.step >= len(steps):
            print(
                f"error: step {args.step} out of range (0..{len(steps)-1})",
                file=sys.stderr,
            )
            sys.exit(1)
        print_header(archive)
        print(format_step(args.step, steps[args.step]))
        return

    # Default: print summary
    print_header(archive)
    print(f"Total steps: {len(steps)}")
    print("Use --all to see per-step report or --step N for a single step.")


if __name__ == "__main__":
    main()
