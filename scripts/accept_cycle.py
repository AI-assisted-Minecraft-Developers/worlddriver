"""One complete acceptance cycle: 3x [random XZ journey + replay x3], chained automatically.
Usage: accept_cycle.py <cycle_label>
Each journey: an XZ goal 110-170 blocks away in a random direction -> live tracking -> find the
newest archive -> replay x3.
Prints result lines as it goes; after a death it respawns, re-gives the items and continues.
"""
import asyncio, sys, math, random, time, os, json
sys.path.insert(0, '.')
from scripts.pmcs.run_case import _rpc, run_case

CYC = sys.argv[1] if len(sys.argv) > 1 else 'C?'
RP_DIR = 'fabric/run/config/worlddriver/replays'
FLAGS = {'pathArchive': False, 'allowBreak': True, 'allowPlace': True, 'allowWaterBucketFall': True, 'walkerStepUpBackoffRetry': True, 'walkerCarrotBodyLos': True, 'walkerBankDigGroundBlip': True, 'walkerExpectAlarm': True, 'walkerStuckStepMonotonic': True, 'walkerPillarSurfacePlace': True, 'walkerAboveNodeStallRecover': True, 'walkerDigAimPriority': True, 'walkerWallDigFallback': True, 'pathfinderBreakCostMultiplier': 2.5,
         'walkerRamNodeAimRelease': True, 'pathfinderFloatingBreakTax': True,
         'pathfinderLogBreakTax': 3.0, 'walkerBridgeHoldRepath': True, 'walkerPhysicalStallClock': True, 'walkerDigCommitHoldRepath': True, 'walkerRouteHysteresis': True,
         'walkerDryReanchor': True, 'walkerBuoyantSearchFromSurface': True, 'walkerBankDigSkipWhenCwpSwims': True,
         'walkerBankDigSkipOverhang': True, 'walkerVineDescentDrop': True, 'walkerAscentRamBobBreak': True,
         'walkerPillarReachGoalNoSnap': True, 'pathfinderForbidParkourFromFloatingWater': True,
         'walkerDeepWaterFloatBeeline': True, 'walkerWaterWalkReach': True, 'walkerWaterStepDownFloat': True,
         'walkerWallCornerFastChurn': True, 'walkerSwimAshorePillarDespiteDeepDig': True,
         'walkerFutileBankDigRelease': True, 'walkerBankDigForwardExit': True, 'walkerFloatingBankBobFreeze': True,
         'walkerDrowningEscape': True, 'walkerClimbGaveUpSticky': True}

def rpc(m, p): return asyncio.run(_rpc(m, p))


def click_button(labels, gone_type=None, timeout=6.0):
    """Click a screen button found by LABEL, and confirm the click took.

    This replaces `click({'x': 318, 'y': 169})` + `sleep(3)` on the death screen.
    That coordinate was only the Respawn button at one window size and one GUI
    scale, and — worse — nothing checked the result: a miss went unnoticed and the
    cycle carried on issuing /clear and /give to a player still lying on the death
    screen, so the next journey started from a corpse and its verdict was garbage.

    Nothing new is needed on the mod side. `mc.client.screen.tree` already reports
    every widget's bbox, label, visible and active — its own comment says it exists
    so agents can pick a widget "by label/index without resorting to pixel-
    counting". Widget x/y are in the Screen's coordinate space, which is exactly
    what `mc.client.input.click` feeds to `Screen.mouseClicked`, so the centre of
    a reported bbox is the right place to click at any scale. `click` even returns
    `handled` — whether a widget accepted it — which the old call discarded.

    Raises rather than guessing. On the death screen the OTHER button is "Title
    Screen": a fallback that clicked the first button it found would quit to the
    main menu and take the whole acceptance run with it, so an unrecognised label
    set is reported with the labels actually on screen, for the operator to add.

    This is the sync sibling of the retired `guidrive.py`'s `click_widget` +
    `wait_until` — same centre-of-bbox, same poll-then-raise — kept separate only
    because guidrive is async over its own RPC session. Every other script in here
    already clicked widget centres; accept_cycle was the last pixel-counter. The
    one thing added over guidrive is the `handled` check: guidrive catches a missed
    click too, but only later and as a generic timeout.
    """
    tree = rpc('mc.client.screen.tree', {})
    kids = tree.get('children') or []
    want = {s.strip().lower() for s in labels}
    hit = next((c for c in kids
                if str(c.get('message', '')).strip().lower() in want
                and c.get('visible') and c.get('active') and 'width' in c), None)
    if hit is None:
        seen = [c.get('message') for c in kids if c.get('message')]
        raise RuntimeError(f'no active button matching {sorted(want)} on '
                           f'{tree.get("type")}; labels present: {seen}')
    r = rpc('mc.client.input.click', {'x': hit['x'] + hit['width'] // 2,
                                      'y': hit['y'] + hit['height'] // 2})
    if not r.get('handled'):
        raise RuntimeError(f'click on {hit["message"]!r} at '
                           f'({hit["x"]},{hit["y"]},{hit["width"]}x{hit["height"]}) '
                           f'was not handled by any widget: {r}')
    if gone_type is None:
        return
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(0.25)
        if rpc('mc.client.screen.info', {}).get('type') != gone_type:
            return
    raise RuntimeError(f'clicked {hit["message"]!r} but {gone_type} is still up '
                       f'after {timeout}s')


# Vanilla's respawn button, by locale. Add yours if click_button reports it. The second entry is
# the zh_cn label, spelled by code point so the source stays ASCII.
RESPAWN_LABELS = ('Respawn', chr(0x91CD) + chr(0x751F), 'deathScreen.respawn')


def ensure_alive():
    p = rpc('mc.client.player', {})
    if p['health'] <= 0:
        r = rpc('mc.client.screen.info', {})
        if r.get('type') == 'DeathScreen':
            click_button(RESPAWN_LABELS, gone_type='DeathScreen')
        rpc('mc.client.chat.send', {'text': '/clear'}); time.sleep(0.3)   # full inventory silently drops gives AND buries the water bucket out of the hotbar (MLG scans hotbar only)
        for c in ['/give @p water_bucket', '/give @p diamond_pickaxe', '/give @p diamond_shovel', '/give @p cobblestone 192']:
            rpc('mc.client.chat.send', {'text': c}); time.sleep(0.3)
        rpc('mc.client.chat.send', {'text': '/effect give @p minecraft:night_vision infinite 0 true'}); time.sleep(0.2)
        print(f'[{CYC}] (respawned + re-equipped)', flush=True)
        return False
    return True


LOG_PATH = 'fabric/run/logs/latest.log'
def expect_counts(since_epoch):
    """Tally [expect] alarm classes logged since the journey started — the causal
    fingerprint attached to every journey verdict (observability-first directive)."""
    import re, collections, datetime
    since = datetime.datetime.fromtimestamp(since_epoch).strftime('%H:%M:%S')
    counts = collections.Counter()
    try:
        with open(LOG_PATH, errors='ignore') as fh:
            for line in fh:
                if '[expect]' not in line: continue
                ts = line[1:9]
                if ts >= since:
                    m = re.search(r'\[expect\] (\S+?):', line)
                    if m: counts[m.group(1)] += 1
    except OSError: pass
    return dict(counts) if counts else 'clean'


def preflight(label):
    """Refuse to run a journey in a degraded state — flags actually ON (snapshot check),
    gear actually in the hotbar. Every past silent failure mode, checked up front."""
    snap = None
    for _ in range(3):                      # rpc can transiently return None (C45 crash)
        r = rpc('mc.bot.setting', {})
        if r is not None: snap = r.get('settings', {}); break
        time.sleep(2)
    if snap is None:
        print(f'[{label}] PREFLIGHT FAIL: setting rpc unavailable', flush=True)
        return False
    missing = [k for k, v in FLAGS.items() if k != 'pathArchive' and snap.get(k) != v]
    if missing:
        print(f'[{label}] PREFLIGHT FAIL: flags not live: {missing}', flush=True)
        return False
    # hotbar gear check via script_eval-free player snapshot (client inventory list)
    inv = rpc('mc.client.player', {}).get('inventory') or []
    hot = {i.get('id') for i in inv if isinstance(i, dict) and i.get('slot', 99) < 9}
    need = {'minecraft:water_bucket', 'minecraft:diamond_pickaxe'}
    lack = need - hot
    if lack:
        print(f'[{label}] PREFLIGHT FAIL: hotbar missing {lack}', flush=True)
        return False
    return True

def live_journey(label):
    ensure_alive()
    # §89 rig fix: the previous journey's 14-block arrive circle can end ON a jungle
    # canopy, so the next leg starts treetop-airborne — jump-ram bounce there hops
    # 3-4 blocks and resets the physical stall anchor, burning the whole timeout at
    # the start (C106-J3: churn 12 blocks from start). A real journey never starts
    # on a treetop; step down to solid ground before goto.
    for _ in range(30):
        y0 = rpc('mc.client.player', {})['pos']['y']
        rpc('mc.client.chat.send', {'text': '/execute as @p at @p if block ~ ~-1 ~ #minecraft:leaves run tp @p ~ ~-1 ~'})
        rpc('mc.client.chat.send', {'text': '/execute as @p at @p if block ~ ~-1 ~ minecraft:air run tp @p ~ ~-1 ~'})
        time.sleep(0.25)
        if rpc('mc.client.player', {})['pos']['y'] >= y0 - 0.01: break
    # Set the FULL flag set every journey: a fresh client boots with all-default flags,
    # and run_case (replay) is the only other setter — the first journey of a chain ran
    # flag-naked otherwise (C21-J1). pathArchive back ON for recording.
    rpc('mc.bot.setting', {**FLAGS, 'pathArchive': True, 'walkerDebug': True})
    if not preflight(label):
        # one repair attempt: re-clear + re-give + re-set, then re-check
        rpc('mc.client.chat.send', {'text': '/clear'}); time.sleep(0.3)
        for c in ['/give @p water_bucket', '/give @p diamond_pickaxe', '/give @p diamond_shovel', '/give @p cobblestone 192']:
            rpc('mc.client.chat.send', {'text': c}); time.sleep(0.3)
        rpc('mc.bot.setting', {**FLAGS, 'pathArchive': True, 'walkerDebug': True})
        if not preflight(label):
            print(f'[{label}] ABORT journey (preflight failed twice)', flush=True)
            return None
        print(f'[{label}] (preflight repaired)', flush=True)
    # Mined-drop pickups crowd the hotbar mid-journey and push the pickaxe/bucket out
    # (observed live: 9/9 hotbar slots junk, no pickaxe -> hand-mining, no bucket -> MLG dead).
    rpc('mc.client.chat.send', {'text': '/clear'}); time.sleep(0.3)
    for c in ['/give @p water_bucket', '/give @p diamond_pickaxe', '/give @p diamond_shovel', '/give @p cobblestone 192']:
        rpc('mc.client.chat.send', {'text': c}); time.sleep(0.3)
    rpc('mc.client.chat.send', {'text': '/effect clear @p'}); time.sleep(0.2)   # live legs stay mortal (§76)
    rpc('mc.client.chat.send', {'text': '/effect give @p minecraft:night_vision infinite 0 true'}); time.sleep(0.2)   # keep night vision through the clear (user directive; also de-noises the dark-cave video watcher)
    p = rpc('mc.client.player', {})['pos']
    sx, sz = p['x'], p['z']
    ang = random.uniform(0, 2 * math.pi)
    dist = random.uniform(110, 170)
    gx, gz = round(sx + dist * math.cos(ang)), round(sz + dist * math.sin(ang))
    print(f'[{label}] start=({sx:.0f},{sz:.0f}) goal=XZ({gx},{gz}) dist={dist:.0f}', flush=True)
    rpc('mc.bot.goto', {'xz': {'x': gx, 'z': gz}, 'near': 3})
    mind = 1e9; lastprog = 0.0; worst = 0.0; gearCheck = 0.0
    # worst = longest MOVEMENT stall (visual hesitation, the #47 bar), not longest
    # goal-distance plateau: a legit canopy/ridge detour holds mind flat for a minute
    # while the bot is visibly walking (C64-J1 "73s worst" was 10 scattered 1-3s wall
    # taps plus detours, no single long freeze). Track the last time the bot was >2
    # blocks from its anchor; churn (90s NO GOAL PROGRESS) still uses mind.
    apos = None; asince = 0.0
    t0 = time.time()
    while time.time() - t0 < 420:
        try: r = rpc('mc.client.player', {})
        except Exception: time.sleep(3); continue
        x, z = r['pos']['x'], r['pos']['z']; el = time.time() - t0
        # Mid-journey gear re-supply (§64): live journeys CONSUME gear (MLG spends the
        # bucket; pickups crowd the pickaxe out) while replays re-give every round —
        # the prime suspect for live-only mountain churn (GEAR-degraded x52 before the
        # C26-J2 churn). Top up in place (no /clear — don't disturb the run) every ~20s.
        if el - gearCheck > 20:
            gearCheck = el
            try:
                inv = r.get('inventory') or []
                hot = {i.get('id') for i in inv if isinstance(i, dict) and i.get('slot', 99) < 9}
                if 'minecraft:water_bucket' not in hot:
                    rpc('mc.client.chat.send', {'text': '/give @p water_bucket'})
                if 'minecraft:diamond_pickaxe' not in hot:
                    rpc('mc.client.chat.send', {'text': '/give @p diamond_pickaxe'})
                # cobble starves too (C98-J1: 30-block cliff pillarUp jump-spun with the
                # block supply consumed by earlier digs/bridges — top-up only covered
                # bucket/pickaxe, so the pillar had nothing to place)
                if 'minecraft:cobblestone' not in hot:
                    rpc('mc.client.chat.send', {'text': '/give @p cobblestone 64'})
            except Exception:
                pass
        d = math.dist((x, z), (gx, gz))
        if d < mind - 1.5: mind = d; lastprog = el
        noProg = el - lastprog
        if apos is None or math.dist((x, z), apos) > 2.0:
            apos = (x, z); asince = el
        worst = max(worst, el - asince)
        if r['health'] <= 0:
            print(f'[{label}] LIVE DIED @({x:.0f},{z:.0f})', flush=True); return None
        if d < 8:
            verdict = 'ARRIVED' if worst <= 30 else 'ARRIVED-SLOW'   # >30s single stall breaks the "no hesitation" bar (#47) — the cycle is NOT green even if replays pass
            print(f'[{label}] LIVE {verdict} {el:.0f}s worst={worst:.0f}s expect={expect_counts(t0)}', flush=True)
            return (gx, gz, x, sx, sz)
        if noProg >= 90:
            print(f'[{label}] LIVE CHURN @({x:.0f},{r["pos"]["y"]:.0f},{z:.0f}) worst={worst:.0f}s expect={expect_counts(t0)}', flush=True)
            # A churn verdict cancels the goto and idles the bot WHERE IT STUCK — with the
            # walker (and its DrowningEscape) stopped, an underwater stall drowns the idle
            # bot before the next journey's ensure_alive (observed live: hp0 post-C24-J3).
            rpc('mc.bot.cancel', {})
            if r.get('underWater') or r.get('inWater'):
                rpc('mc.client.chat.send', {'text': f'/tp @p {x:.0f} {r["pos"]["y"]+12:.0f} {z:.0f}'})
                time.sleep(2)
                rpc('mc.client.chat.send', {'text': '/tp @p ~ ~ ~'})
            return None
        time.sleep(3)
    print(f'[{label}] LIVE TIMEOUT mind={mind:.0f}', flush=True); return None

def archive_for(sx, sz, gx=None, gz=None):
    """Newest archive whose header start AND goal match the journey (start alone
    mis-matched C58-J1 to a day-old archive from the same spread area; the fresh
    goto archive flushes late, so also retry a few seconds for it to appear)."""
    for _wait in range(5):
        fs = [f for f in os.listdir(RP_DIR) if f.startswith('replay-') and f.endswith('.json')]
        for f in sorted(fs, key=lambda f: os.path.getmtime(os.path.join(RP_DIR, f)), reverse=True):
            try: h = json.load(open(os.path.join(RP_DIR, f))).get('header', {})
            except Exception: continue
            st = h.get('start', [9e9, 0, 9e9]); gl = h.get('goal', [9e9, 0, 9e9])
            if abs(st[0] - sx) > 6 or abs(st[2] - sz) > 6: continue
            if gx is not None and (abs(gl[0] - gx) > 6 or abs(gl[2] - gz) > 6): continue
            return f
        time.sleep(2)
    return None

def _self_test():
    """Exercise click_button against a scripted RPC, no game needed.

    The point of this fix is that a failed click stops being silent, so the tests
    that matter are the failing ones: every path below must RAISE. Run with
    `python scripts/accept_cycle.py --self-test`.
    """
    global rpc
    real_rpc, failures = rpc, []

    def fake(tree, handled=True, after='InventoryScreen', calls=None):
        def _rpc_stub(m, p):
            if calls is not None:
                calls.append((m, p))
            if m == 'mc.client.screen.tree':
                return tree
            if m == 'mc.client.input.click':
                return {'ok': True, 'handled': handled}
            if m == 'mc.client.screen.info':
                return {'type': after}
            raise AssertionError('unexpected rpc ' + m)
        return _rpc_stub

    def button(msg, x=100, y=150, w=200, h=20, visible=True, active=True):
        return {'type': 'Button', 'message': msg, 'x': x, 'y': y, 'width': w,
                'height': h, 'visible': visible, 'active': active}

    def check(name, fn, want_err=None):
        try:
            fn()
        except Exception as e:                                  # noqa: BLE001
            if want_err is None:
                failures.append(f'{name}: unexpected {type(e).__name__}: {e}')
            elif want_err not in str(e):
                failures.append(f'{name}: wrong error, wanted {want_err!r}, got {e}')
            return
        if want_err is not None:
            failures.append(f'{name}: expected a raise ({want_err!r}), got none')

    death = {'type': 'DeathScreen',
             'children': [button('Respawn'), button('Title Screen', y=175)]}

    # 1. happy path — clicks the CENTRE of the matched bbox, not a fixed pixel
    calls = []
    rpc = fake(death, calls=calls)
    check('respawn clicked', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen'))
    clicked = [p for m, p in calls if m == 'mc.client.input.click']
    if clicked != [{'x': 200, 'y': 160}]:
        failures.append(f'centre of (100,150,200x20) should be (200,160); got {clicked}')

    # 2. the label is not on screen — must name what IS, never guess a neighbour
    rpc = fake({'type': 'DeathScreen', 'children': [button('Title Screen')]})
    check('unknown label', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen'),
          'labels present')

    # 3. the click landed on nothing — the signal the old code discarded
    rpc = fake(death, handled=False)
    check('unhandled click', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen'),
          'not handled')

    # 4. clicked, handled, screen still up: the exact case sleep(3) walked past
    rpc = fake(death, after='DeathScreen')
    check('screen stayed', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen',
                                                timeout=0.5), 'still up')

    # 5. a disabled or hidden widget with the right label is not a target
    for why, kw in (('inactive', {'active': False}), ('invisible', {'visible': False})):
        rpc = fake({'type': 'DeathScreen', 'children': [button('Respawn', **kw)]})
        check(why + ' button', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen'),
              'no active button')

    # 6. a screen with no children at all (tree arrived before the widgets did)
    rpc = fake({'type': 'DeathScreen'})
    check('empty tree', lambda: click_button(RESPAWN_LABELS, gone_type='DeathScreen'),
          'labels present')

    rpc = real_rpc
    if failures:
        print('accept_cycle self-test FAILED:')
        for f in failures:
            print('   ', f)
        sys.exit(1)
    print('accept_cycle self-test OK: 8 checks (click_button raises on every miss)')
    sys.exit(0)


if '--self-test' in sys.argv:
    _self_test()

# Cycle start: spread to a FRESH area (escape any replay-restored corridor from
# the previous cycle — restoreBlocks snapshots can desync planned climb blocks
# from the live world, the suspected C2-J1 vine-detach cause).
# spreadplayers can drop the bot INSIDE a cave/ravine opening (C18: y37 start,
# journey churned at y8 in the cave network) — retry until surfaced (y>=60).
# PEACEFUL for the whole cycle (§75): pathfinding acceptance, not combat — hostiles
# pinned the C63-J2 replay bot (spider, maxStuck 1200) and have bled hp in live legs.
rpc('mc.client.chat.send', {'text': '/difficulty peaceful'}); time.sleep(0.3)
for _try in range(4):
    cx, cz = random.randint(-400, 400), random.randint(-400, 400)
    rpc('mc.client.chat.send', {'text': f'/spreadplayers {cx} {cz} 0 60 false @p'})
    time.sleep(5)
    py = rpc('mc.client.player', {})['pos']['y']
    if py >= 60: break
    print(f'[{CYC}] spread landed underground (y={py:.0f}) — retry', flush=True)
ensure_alive()
rpc('mc.client.chat.send', {'text': '/clear'}); time.sleep(0.3)
for c in ['/give @p water_bucket', '/give @p diamond_pickaxe', '/give @p diamond_shovel', '/give @p cobblestone 192']:
    rpc('mc.client.chat.send', {'text': c}); time.sleep(0.3)
p0 = rpc('mc.client.player', {})['pos']
print(f'[{CYC}] spread to ({p0["x"]:.0f},{p0["y"]:.0f},{p0["z"]:.0f})', flush=True)

for j in range(1, 4):
    label = f'{CYC}-J{j}'
    res = live_journey(label)
    if res is None:
        ensure_alive()
        print(f'[{label}] SKIP replays (live not clean)', flush=True)
        continue
    gx, gz, endx, jsx, jsz = res
    arc = None
    for wait in range(6):          # the archive flushes a few seconds AFTER ARRIVED — retry up to ~18s
        time.sleep(3)
        arc = archive_for(jsx, jsz, gx, gz)
        if arc: break
    if arc is None:
        print(f'[{label}] NO matching archive — skip replays', flush=True); continue
    # Arrival criterion: the single-axis finish line uses the journey's main axis of travel. A
    # north-south journey measured on the x axis crosses the line early in the run, which cuts the
    # replay short and produces a false failure (maxStuck 9-10, atGoal=False).
    if abs(gx - jsx) >= abs(gz - jsz):
        axis, end_v, start_v = 'x', endx, jsx
    else:
        axis, end_v, start_v = 'z', gz, jsz     # the live end z is not recorded; goal z approximates it (within radius 3)
    cmp = 'ge' if end_v >= start_v else 'le'
    ax = round(end_v - 6) if cmp == 'ge' else round(end_v + 6)
    print(f'[{label}] archive={arc} arrive_{axis}={ax}({cmp})', flush=True)
    # envelope-exit detection (§66): replan may route OUTSIDE the archived corridor —
    # unarchived terrain there wedges the bot as a rig ARTIFACT, not a walker regression
    # (C29-J2 replay#1: stuck at x=-414, 7 blocks west of the live bbox x[-407,-313]).
    traj = json.load(open(os.path.join(RP_DIR, arc))).get('trajectory') or []
    if traj:
        bx = [min(t['x'] for t in traj) - 8, max(t['x'] for t in traj) + 8]
        bz = [min(t['z'] for t in traj) - 8, max(t['z'] for t in traj) + 8]
    else:
        bx = bz = None
    # Replays run PEACEFUL (§75): the replay world restores blocks but not mobs, so
    # live-world hostiles wander in and pin the replay bot (C63-J2: a spider wrapped
    # the bot at the start cell -> replay#1/#2 maxStuck 1196/1200 while live passed
    # at 19s worst). Mob combat is not what a replay verifies; kill the noise source.
    rpc('mc.client.chat.send', {'text': '/difficulty peaceful'}); time.sleep(0.3)
    # Replays also run damage-immune (§76): a replan drifting off the archived corridor
    # walks the bot off a cliff / into lava (C70: three replay deaths — fall, lava x2),
    # which is rig noise, not a pathfinding regression. Live legs stay mortal.
    rpc('mc.client.chat.send', {'text': '/effect give @p minecraft:resistance infinite 255 true'}); time.sleep(0.2)
    for i in range(3):
        ensure_alive()
        r = run_case(arc, FLAGS, arrive_x=ax, cmp=cmp, timeout=240, axis=axis)
        # arrive_x is a single-axis proxy that misjudges XZ-near-circle arrivals (§49);
        # the real criterion is the end position inside the goal circle. The axis line
        # trips up to ~9 blocks short (6 tolerance + goal radius) while the goto is
        # still closing — give it a beat to finish, then judge a circle that admits
        # the axis-line geometry (C37-J1: replays cruised at maxStuck 19-69 yet read
        # "False" because the end pos was sampled the instant the line tripped).
        time.sleep(4)
        ep = rpc('mc.client.player', {})['pos']
        at_goal = math.dist((ep['x'], ep['z']), (gx, gz)) < 14 and rpc('mc.client.player', {})['health'] > 0
        # Between-replay placement: the walker stops at the replay verdict and the idle
        # driver is deliberately passive (user directive), so a bot left submerged here
        # drowns before the next round starts (C41-J3 death, C44-J3 near-death).
        rpc('mc.bot.cancel', {}); time.sleep(0.3)
        # Surface AND keep rising until the head is out — a bot floated to the surface
        # sinks again while idle (passive driver), so stepping just past underWater is
        # not enough; push 2 extra blocks clear so it lands/breathes while idle.
        rose = 0
        for _ in range(12):
            if not rpc('mc.client.player', {}).get('underWater'):
                if rose == 0: break
                rpc('mc.client.chat.send', {'text': '/execute as @p at @p run tp @p ~ ~2 ~'}); time.sleep(0.4)
                rose -= 1
            else:
                rpc('mc.client.chat.send', {'text': '/execute as @p at @p run tp @p ~ ~2 ~'}); time.sleep(0.4)
                rose = 1
        env_exit = (not at_goal and bx is not None
                    and not (bx[0] <= ep['x'] <= bx[1] and bz[0] <= ep['z'] <= bz[1]))
        tag = ' envExit=True (rig artifact — replan left the archived corridor)' if env_exit else ''
        print(f'[{label}] replay#{i+1}: maxStuck={r.max_stuck} atGoal={at_goal}{tag}', flush=True)
# Final placement: the last replay can leave the bot idle UNDERWATER (post-C31 drowning
# rescue at y57) — cancel any residual goto and stand it somewhere breathable.
try:
    rpc('mc.bot.cancel', {}); time.sleep(0.5)
    for _ in range(10):                      # step up 2 blocks at a time until the head clears water
        if not rpc('mc.client.player', {}).get('underWater'): break
        rpc('mc.client.chat.send', {'text': '/execute as @p at @p run tp @p ~ ~2 ~'}); time.sleep(0.5)
except Exception:
    pass
print(f'[{CYC}] CYCLE_DONE', flush=True)
