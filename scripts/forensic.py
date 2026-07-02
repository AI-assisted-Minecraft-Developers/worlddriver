#!/usr/bin/env python3
"""Same-tick paired walker forensics — the anti "splice different ticks" tool.

The §55/§56 lesson: reading `[walker] t=` lines and `walk-keys` lines separately and
mentally joining them produces artifacts (a phantom "reversed drive" cost two wrong
autopsies). The two lines for one tick are ADJACENT in the log (same render tick), so
the only safe read is the PAIRED one. This tool emits one row per tick with both
halves joined, plus the [expect] alarms interleaved.

Usage:
  uv run scripts/forensic.py [--log PATH] [--from HH:MM:SS] [--to HH:MM:SS]
                             [--near X,Z --radius N] [--tail N]

Output columns:
  time | move node | pos | bear/yaw/yawErr | cur2 dY | hCol onG inW | keys(up jump sprint attack) | driveYaw
[expect] lines appear inline where they fired.
"""
import argparse
import re
import sys

P_T = re.compile(
    r'\[(?P<ts>\d\d:\d\d:\d\d)\].*\[walker\] t=(?P<t>\d+) step=(?P<step>\S+) move=(?P<move>\S+) '
    r'node=(?P<node>\S+) p=\((?P<pos>[^)]*)\).*?bear=(?P<bear>-?\d+) yawErr=(?P<yawErr>-?\d+).*?'
    r'cur2=(?P<cur2>\S+) \(gate[^)]*\) \|dY\|=(?P<dy>\S+)')
P_K = re.compile(
    r'\[(?P<ts>\d\d:\d\d:\d\d)\].*walk-keys yaw=(?P<yaw>-?\d+) wp=(?P<wp>\S+) up=(?P<up>\w+) '
    r'jump=(?P<jump>\w+) sprint=(?P<sprint>\w+).*?hCol=(?P<hcol>\w+).*?hSpd=(?P<hspd>\S+) '
    r'pos=(?P<kpos>\S+) onG=(?P<ong>\w+) attack=(?P<attack>\w+).*?driveYaw=(?P<dyaw>-?\d+)')
P_E = re.compile(r'\[(?P<ts>\d\d:\d\d:\d\d)\].*(?P<line>\[expect\].*)$')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--log', default='fabric/run/logs/latest.log')
    ap.add_argument('--from', dest='t_from', default=None, help='HH:MM:SS window start')
    ap.add_argument('--to', dest='t_to', default=None, help='HH:MM:SS window end')
    ap.add_argument('--near', default=None, help='X,Z — only ticks within --radius of this point')
    ap.add_argument('--radius', type=float, default=8.0)
    ap.add_argument('--tail', type=int, default=0, help='only the last N paired rows')
    args = ap.parse_args()

    nx = nz = None
    if args.near:
        nx, nz = (float(v) for v in args.near.split(','))

    rows = []
    pend = None   # the t= half waiting for its walk-keys twin (adjacent lines, same tick)
    with open(args.log, errors='ignore') as fh:
        for line in fh:
            # Only timestamped lines participate in the window filter — a banner or
            # stack-trace line would win the string comparison and false-trigger the
            # early break (the bug that made the first windowed run return 0 rows).
            if len(line) < 10 or line[0] != '[' or line[3] != ':' or line[9] != ']':
                continue
            ts = line[1:9]
            if args.t_from and ts < args.t_from:
                continue
            if args.t_to and ts > args.t_to:
                continue
            m = P_E.search(line)
            if m:
                rows.append(('EXPECT', m.group('ts'), m.group('line').rstrip()))
                continue
            m = P_T.search(line)
            if m:
                if pend is not None:
                    # previous t= line never got a walk-keys twin (an early-return tick:
                    # dig/pillar/recovery branches skip walk-keys) — emit it unpaired
                    rows.append(('TICK', pend['ts'], {**pend, 'yaw': '?', 'wp': '?', 'up': '?',
                                 'jump': '?', 'sprint': '?', 'hcol': '?', 'hspd': '?', 'kpos': '?',
                                 'ong': '?', 'attack': 'DIG?', 'dyaw': '?'}))
                pend = m.groupdict()
                continue
            m = P_K.search(line)
            if m and pend and m.group('ts') >= pend['ts']:
                d = {**pend, **m.groupdict()}
                pend = None
                if nx is not None:
                    try:
                        px, _, pz = (float(v) for v in d['pos'].split(','))
                        if (px - nx) ** 2 + (pz - nz) ** 2 > args.radius ** 2:
                            continue
                    except ValueError:
                        continue
                rows.append(('TICK', d['ts'], d))

    if args.tail:
        rows = rows[-args.tail:]
    for kind, ts, d in rows:
        if kind == 'EXPECT':
            print(f'{ts} {d}')
            continue
        print(f"{ts} t={d['t']:>5} {d['move']:<14} node={d['node']:<14} p=({d['pos']}) "
              f"bear={d['bear']:>4} yaw={d['yaw']:>4} err={d['yawErr']:>4} drive={d['dyaw']:>4} "
              f"cur2={d['cur2']:<6} dY={d['dy']:<5} hCol={d['hcol']:<5} onG={d['ong']:<5} "
              f"keys[up={d['up']} j={d['jump']} spr={d['sprint']} atk={d['attack']}] hSpd={d['hspd']}")
    print(f'-- {sum(1 for k, _, _ in rows if k == "TICK")} paired ticks, '
          f'{sum(1 for k, _, _ in rows if k == "EXPECT")} [expect] alarms', file=sys.stderr)


if __name__ == '__main__':
    main()
