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
    jtn = node.get("jumpToNext")
    if isinstance(jtn, dict):
        needed = jtn.get("needed", False)
        feasible = jtn.get("feasible", True)
    else:
        needed = node.get("jumpNeeded", False)
        feasible = node.get("jumpFeasible", True)

    if not needed:
        return "no"
    return f"need+feas:{feasible}"


def fit_summary(node: dict) -> str:
    """Compact fit string: letters present = can fit at that pose."""
    ceiling = node.get("ceilingForces", "none") or "none"
    if ceiling not in ("none",):
        return ceiling  # e.g. "suffocate", "crouch", "crawl"
    return (
        ("S" if node.get("fitStand") else ".")
        + ("C" if node.get("fitCrouch") else ".")
        + ("c" if node.get("fitCrawl") else ".")
    )


def underfoot_summary(node: dict, coord, envelope_index: dict) -> str:
    """Look up the block below the foot coord in the envelope; fall back to bool."""
    if coord:
        below = (coord[0], coord[1] - 1, coord[2])
        cell = envelope_index.get(below)
        if cell:
            block = cell.get("block", "?")
            # strip namespace for brevity
            return block.split(":")[-1] if ":" in block else block
    return "solid" if node.get("underfootSolid") else "void"


def build_envelope_index(archive: dict) -> dict:
    """Return dict mapping (x,y,z) tuple → envelope cell."""
    idx = {}
    for cell in archive.get("envelope", []):
        pos = cell.get("pos")
        if pos and len(pos) == 3:
            idx[(pos[0], pos[1], pos[2])] = cell
    return idx


def build_traj_step_index(trajectory: list) -> dict:
    """Map plan step index → list of trajectory ticks at that step."""
    idx: dict = {}
    for tick in trajectory:
        s = tick.get("step")
        if s is not None:
            idx.setdefault(s, []).append(tick)
    return idx


def nearest_traj_tick(step_num: int, traj_step_index: dict):
    """Return the first trajectory tick matching this step, or None."""
    ticks = traj_step_index.get(step_num)
    if ticks:
        return ticks[0]
    return None


def compute_flags(node: dict, edge, traj_tick, dev_threshold: float) -> list:
    """Return list of uppercase flag tokens for a step."""
    flags = []
    ceiling = node.get("ceilingForces", "none") or "none"
    if ceiling == "suffocate":
        flags.append("SUFFOCATE")
    elif ceiling in ("crouch", "crawl"):
        flags.append(f"CEILING:{ceiling.upper()}")

    if node.get("collidesStanding") or (traj_tick and traj_tick.get("aabbOverlap")):
        flags.append("COLLIDE")

    hazard = node.get("footHazard")
    if hazard:
        flags.append(f"HAZARD({hazard})")  # lowercase hazard name preserved

    fall = node.get("fall", 0.0) or 0.0
    if fall > 0 and not node.get("fallSurvivable", True):
        flags.append("FALL!")

    jtn = node.get("jumpToNext")
    if isinstance(jtn, dict) and jtn.get("needed") and not jtn.get("feasible", True):
        flags.append("JUMP✗")  # JUMP✗

    if traj_tick is not None:
        dev = traj_tick.get("deviation")
        if dev is not None and dev > dev_threshold:
            flags.append("DRIFT")

    return flags


# ─── TABLE RENDERING ────────────────────────────────────────────────────────

# Column widths (fixed)
COL_STEP = 5
COL_POS = 16
COL_MOVE = 10
COL_POSE = 10
COL_FIT = 10
COL_UNDERFOOT = 12
COL_FALL = 6
COL_JUMP = 14
COL_BREAK = 14
COL_PLACE = 14
COL_DEV = 7
COL_FLAGS = 28


def table_header(include_dev: bool) -> str:
    cols = [
        f"{'step':>{COL_STEP}}",
        f"{'pos':<{COL_POS}}",
        f"{'move':<{COL_MOVE}}",
        f"{'pose':<{COL_POSE}}",
        f"{'fit':<{COL_FIT}}",
        f"{'underfoot':<{COL_UNDERFOOT}}",
        f"{'fall':>{COL_FALL}}",
        f"{'jump':<{COL_JUMP}}",
        f"{'break':<{COL_BREAK}}",
        f"{'place':<{COL_PLACE}}",
    ]
    if include_dev:
        cols.append(f"{'dev':>{COL_DEV}}")
    cols.append(f"{'flags':<{COL_FLAGS}}")
    return "  ".join(cols)


def format_cell_list(cells: list) -> str:
    """Format a break/place cell list as a compact string."""
    if not cells:
        return "-"
    if len(cells) == 1:
        c = cells[0]
        return f"[{c[0]},{c[1]},{c[2]}]"
    return f"{len(cells)}x[{cells[0][0]},{cells[0][1]},{cells[0][2]}]"


def format_table_row(
    step_num: int,
    entry: dict,
    traj_tick,
    envelope_index: dict,
    dev_threshold: float,
    include_dev: bool,
) -> str:
    node = entry["node"]
    coord = entry["coord"]
    edge = entry["edge"]

    pos_str = f"[{coord[0]},{coord[1]},{coord[2]}]" if coord else "?"
    move = (edge["move"] if edge else "-")[:COL_MOVE]
    pose = (traj_tick["pose"] if traj_tick else "-")[:COL_POSE]
    fit = fit_summary(node)[:COL_FIT]
    underfoot = underfoot_summary(node, coord, envelope_index)[:COL_UNDERFOOT]
    fall_val = node.get("fall", 0.0) or 0.0
    fall_str = f"{fall_val:.1f}"
    jump = jump_summary(node)[:COL_JUMP]

    break_cells = (edge.get("break") or []) if edge else []
    place_cells = (edge.get("place") or []) if edge else []
    break_str = format_cell_list(break_cells)[:COL_BREAK]
    place_str = format_cell_list(place_cells)[:COL_PLACE]

    flags = compute_flags(node, edge, traj_tick, dev_threshold)
    flags_str = " ".join(flags) if flags else "-"

    dev_str = ""
    if include_dev and traj_tick is not None:
        dev = traj_tick.get("deviation")
        dev_str = f"{dev:.1f}" if dev is not None else "-"
    elif include_dev:
        dev_str = "-"

    cols = [
        f"{step_num:>{COL_STEP}}",
        f"{pos_str:<{COL_POS}}",
        f"{move:<{COL_MOVE}}",
        f"{pose:<{COL_POSE}}",
        f"{fit:<{COL_FIT}}",
        f"{underfoot:<{COL_UNDERFOOT}}",
        f"{fall_str:>{COL_FALL}}",
        f"{jump:<{COL_JUMP}}",
        f"{break_str:<{COL_BREAK}}",
        f"{place_str:<{COL_PLACE}}",
    ]
    if include_dev:
        cols.append(f"{dev_str:>{COL_DEV}}")
    cols.append(f"{flags_str:<{COL_FLAGS}}")
    return "  ".join(cols)


def format_step_verbose(step_num: int, entry: dict, traj_tick, envelope_index: dict, dev_threshold: float) -> str:
    """Detailed multi-line output for --step N."""
    node = entry["node"]
    coord = entry["coord"]
    edge = entry["edge"]

    lines = []
    lines.append(f"=== Step {step_num} (seg={entry['seg_idx']} node={entry['node_idx']}) ===")
    lines.append(f"  pos        : {coord}")
    lines.append(f"  move       : {edge['move'] if edge else '-'}")
    lines.append(f"  cost       : {edge['cost'] if edge else '-'}")

    # Node facts
    lines.append(f"  fitStand   : {node.get('fitStand')}")
    lines.append(f"  fitCrouch  : {node.get('fitCrouch')}")
    lines.append(f"  fitCrawl   : {node.get('fitCrawl')}")
    lines.append(f"  ceilingForces: {node.get('ceilingForces', 'none')}")
    lines.append(f"  collidesStanding: {node.get('collidesStanding')}")
    lines.append(f"  inWaterFoot: {node.get('inWaterFoot')}")
    lines.append(f"  submergedEye: {node.get('submergedEye')}")
    lines.append(f"  underfootSolid: {node.get('underfootSolid')}")
    underfoot = underfoot_summary(node, coord, envelope_index)
    lines.append(f"  underfoot  : {underfoot}")
    lines.append(f"  footHazard : {node.get('footHazard')}")
    lines.append(f"  fall       : {node.get('fall', 0.0)}")
    lines.append(f"  fallSurvivable: {node.get('fallSurvivable')}")
    jtn = node.get("jumpToNext")
    lines.append(f"  jumpToNext : {jtn}")

    # Block ops
    if edge:
        lines.append(f"  break cells: {edge.get('break', [])}")
        lines.append(f"  place cells: {edge.get('place', [])}")
    else:
        lines.append("  break cells: []")
        lines.append("  place cells: []")

    # Trajectory tick
    if traj_tick:
        lines.append(f"  traj tick  : {traj_tick}")
    else:
        lines.append("  traj tick  : (no match)")

    flags = compute_flags(node, edge, traj_tick, dev_threshold)
    lines.append(f"  FLAGS      : {' '.join(flags) if flags else '-'}")
    return "\n".join(lines)


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
        help="Path to a replay-run archive for deviation analysis.",
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
    envelope_index = build_envelope_index(archive)

    # Load trajectory: prefer replay archive if provided, else archive's own trajectory
    replay_archive = None
    traj_step_index: dict = {}
    if args.replay:
        replay_archive = load_archive(args.replay)
        traj = replay_archive.get("trajectory", [])
        traj_step_index = build_traj_step_index(traj)
    else:
        traj = archive.get("trajectory", [])
        traj_step_index = build_traj_step_index(traj)

    include_dev = args.replay is not None
    dev_threshold = args.deviation_threshold

    if args.all:
        print_header(archive)
        header = table_header(include_dev)
        print(header)
        print("-" * len(header))
        deviations = []
        drift_count = 0
        for i, entry in enumerate(steps):
            tick = nearest_traj_tick(i, traj_step_index)
            print(format_table_row(i, entry, tick, envelope_index, dev_threshold, include_dev))
            if include_dev and tick is not None:
                dev = tick.get("deviation")
                if dev is not None:
                    deviations.append(dev)
                    if dev > dev_threshold:
                        drift_count += 1

        if include_dev and deviations:
            max_dev = max(deviations)
            mean_dev = sum(deviations) / len(deviations)
            print()
            print(
                f"Replay deviation summary: max={max_dev:.2f}  mean={mean_dev:.2f}"
                f"  drift_count={drift_count} (threshold={dev_threshold})"
            )
        return

    if args.step is not None:
        if args.step < 0 or args.step >= len(steps):
            print(
                f"error: step {args.step} out of range (0..{len(steps)-1})",
                file=sys.stderr,
            )
            sys.exit(1)
        print_header(archive)
        entry = steps[args.step]
        tick = nearest_traj_tick(args.step, traj_step_index)
        print(format_step_verbose(args.step, entry, tick, envelope_index, dev_threshold))
        return

    # Default: print summary
    print_header(archive)
    print(f"Total steps: {len(steps)}")
    print("Use --all to see per-step report or --step N for a single step.")


if __name__ == "__main__":
    main()
