#!/usr/bin/env python3
"""
ReAct-style smoke driver for the WorldDriver client RPC.

Loop shape per step:
  Observe → screenshot + screen.info + screen.tree
  Think   → match current screen.type against the goal, find a widget by label
  Act     → mc.client.input.click on the widget's (x+w/2, y+h/2) in MC internal coords
            (or call mc.client.screen.{openPause,close} for non-button transitions)
  Observe → re-fetch screen.info, confirm we transitioned

No xdotool, no OS-level input. All navigation goes through the WebSocket RPC.

Discovery: reads ``worlddriver-rpc.port`` from the JVM cwd (fabric/run when invoked from
``./gradlew :fabric:runClient``). Polls until the file appears and the WS endpoint
is reachable.
"""

import asyncio
import base64
import json
import os
import pathlib
import sys
import time

import websockets

REPO = pathlib.Path(__file__).resolve().parents[1]
RUN_DIR = REPO / "fabric" / "run"
PORT_FILE = RUN_DIR / "worlddriver-rpc.port"
SHOTS = RUN_DIR / "smoke"
SHOTS.mkdir(parents=True, exist_ok=True)
TRACE_FILE = SHOTS / "react-trace.json"

# Append-only log of every ReAct step. Flushed at the end so JSON stays valid
# even if the driver crashes; entries are buffered in TRACE.
TRACE: list[dict] = []
T0 = time.time()


def trace(kind: str, **fields):
    fields["t"] = round(time.time() - T0, 3)
    fields["kind"] = kind
    TRACE.append(fields)


# --------------------------------------------------------------------- RPC --
class Rpc:
    def __init__(self, ws):
        self.ws = ws
        self.next_id = 1

    async def call(self, method, params=None, timeout=30):
        rid = self.next_id; self.next_id += 1
        await self.ws.send(json.dumps({"id": rid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"rpc {method} timed out after {timeout}s")
            raw = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            msg = json.loads(raw)
            if msg.get("id") != rid:
                continue
            if "error" in msg:
                raise RuntimeError(f"rpc {method} error: {msg['error']}")
            return msg.get("result")


# ------------------------------------------------------------------ Helpers --
def find_widget(node, predicate):
    """Depth-first search for the first widget node where predicate(node) is True."""
    if predicate(node):
        return node
    for child in node.get("children", []) if isinstance(node, dict) else []:
        hit = find_widget(child, predicate)
        if hit is not None:
            return hit
    return None


def by_label(substring):
    s = substring.lower()
    def pred(n):
        msg = n.get("message")
        return isinstance(msg, str) and s in msg.lower()
    return pred


async def wait_for_clear_overlay(rpc, timeout=15):
    """Block until no Overlay is on top of the Screen. LoadingOverlay fades over
    ~1.5s after MC's resource manager finishes; without this guard, a fast
    Observe→Act loop can capture the Mojang splash while the underlying Screen
    is already TitleScreen."""
    start = time.time()
    while time.time() - start < timeout:
        info = await rpc.call("mc.client.screen.info")
        if not info.get("overlayActive"):
            return
        await asyncio.sleep(0.2)


async def shot(rpc, name, clear_overlay=True, settle=0.8):
    """Capture a screenshot. ``settle`` lets transient animations (TitleScreen
    panorama fade-in, click ripple, tooltip-clear after cursor move) finish
    before the frame is grabbed."""
    if clear_overlay:
        await wait_for_clear_overlay(rpc)
    if settle > 0:
        await asyncio.sleep(settle)
    result = await rpc.call("mc.client.screenshot", timeout=60)
    png = base64.b64decode(result["base64"])
    out = SHOTS / f"{name}.png"
    out.write_bytes(png)
    print(f"  [shot] {out.name} ({result['width']}x{result['height']}, {len(png)//1024} KB)")
    trace("shot", file=out.name, w=result["width"], h=result["height"], bytes=len(png))
    return result


async def observe(rpc):
    info = await rpc.call("mc.client.screen.info")
    print(f"  [obs ] type={info.get('type')} hasScreen={info['hasScreen']} "
          f"worldOpen={info['worldOpen']} hasPlayer={info['hasPlayer']} "
          f"overlay={info.get('overlayType') or '-'}")
    trace("observe", info=info)
    return info


async def click_widget(rpc, widget, why):
    cx = widget["x"] + widget["width"] / 2
    cy = widget["y"] + widget["height"] / 2
    label = widget.get("message", "<no-label>")
    print(f"  [act ] click '{label}' @({cx:.1f},{cy:.1f})  // {why}")
    trace("act", action="click", label=label, x=cx, y=cy, reason=why)
    return await rpc.call("mc.client.input.click", {"x": cx, "y": cy, "button": 0})


async def wait_until(rpc, predicate, *, label, timeout=120, poll=1.0):
    """Spin observing screen.info until predicate(info) is True."""
    start = time.time()
    last = None
    while time.time() - start < timeout:
        info = await rpc.call("mc.client.screen.info")
        last = info
        if predicate(info):
            print(f"  [wait] '{label}' reached after {time.time()-start:.1f}s "
                  f"(type={info.get('type')})")
            return info
        await asyncio.sleep(poll)
    raise TimeoutError(f"'{label}' not reached in {timeout}s; last info={last}")


# ------------------------------------------------------------ Goal handlers --
async def goto_main_menu(rpc):
    """Dismiss any startup-only screens (welcome dialog) → land on TitleScreen."""
    info = await observe(rpc)
    if info.get("type") == "TitleScreen":
        return
    if info.get("type") and "Onboarding" in info["type"]:
        tree = await rpc.call("mc.client.screen.tree")
        btn = find_widget(tree, by_label("Continue"))
        if btn is None:
            raise RuntimeError("AccessibilityOnboardingScreen has no Continue widget")
        await click_widget(rpc, btn, why="dismiss welcome dialog")
    # Wait for TitleScreen even if we didn't recognize the intermediate
    await wait_until(rpc, lambda i: i.get("type") == "TitleScreen", label="TitleScreen")


async def park_cursor(rpc, x=2, y=2):
    """Move the screen-hover cursor away from buttons so tooltips don't render
    over the next screenshot. (2,2) sits in the top-left corner where neither
    title nor buttons live."""
    try:
        r = await rpc.call("mc.client.input.mouseMove", {"x": x, "y": y})
        print(f"  [park] cursor → ({x},{y}) wx={r['wx']} wy={r['wy']}")
        trace("park", x=x, y=y, wx=r.get('wx'), wy=r.get('wy'))
    except Exception as e:
        print(f"  [warn] park_cursor: {e}")


async def goto_create_world(rpc):
    """From TitleScreen → click Singleplayer; if SelectWorld appears, click
    Create New World once to advance into CreateWorldScreen."""
    tree = await rpc.call("mc.client.screen.tree")
    sp = find_widget(tree, by_label("Singleplayer"))
    if sp is None:
        raise RuntimeError("Singleplayer button not found on TitleScreen")
    await click_widget(rpc, sp, why="enter singleplayer flow")
    info = await wait_until(rpc, lambda i: i.get("type") in
                           ("SelectWorldScreen", "CreateWorldScreen"),
                           label="SelectWorld or CreateWorld")
    if info["type"] == "SelectWorldScreen":
        tree = await rpc.call("mc.client.screen.tree")
        cnw = find_widget(tree, by_label("Create New World"))
        await click_widget(rpc, cnw, why="open world-creation form (no saves yet)")
        await wait_until(rpc, lambda i: i.get("type") == "CreateWorldScreen",
                         label="CreateWorldScreen")


async def commit_world_creation(rpc):
    """On CreateWorldScreen, click the action 'Create New World' button — fires
    the world gen; returns immediately, caller polls with wait_in_world."""
    tree = await rpc.call("mc.client.screen.tree")
    cnw = find_widget(tree, by_label("Create New World"))
    if cnw is None:
        raise RuntimeError("Create New World action button not found")
    await click_widget(rpc, cnw, why="commit world creation")


async def wait_in_world(rpc, timeout=240):
    await wait_until(rpc,
                     lambda i: not i["hasScreen"] and i["worldOpen"] and i["hasPlayer"],
                     label="in-world (screen=null, player spawned)",
                     timeout=timeout, poll=2.0)


async def save_and_quit(rpc):
    """Click Save and Quit (or Quit to Title) on PauseScreen → wait TitleScreen."""
    tree = await rpc.call("mc.client.screen.tree")
    saveq = find_widget(tree, by_label("Save and Quit"))
    if saveq is None:
        saveq = find_widget(tree, by_label("Quit to Title"))
    if saveq is None:
        raise RuntimeError("Save and Quit / Quit to Title button not found")
    await click_widget(rpc, saveq, why="leave world to TitleScreen")
    await wait_until(rpc, lambda i: i.get("type") == "TitleScreen",
                     label="back to TitleScreen", timeout=120, poll=2.0)


async def quit_game(rpc):
    """Click Quit Game on TitleScreen → JVM exits, RPC call usually fails on way out."""
    tree = await rpc.call("mc.client.screen.tree")
    quit_btn = find_widget(tree, by_label("Quit Game"))
    if quit_btn is None:
        raise RuntimeError("Quit Game button not found on TitleScreen")
    try:
        await click_widget(rpc, quit_btn, why="exit minecraft")
    except Exception as e:
        # WebSocket may die mid-call as MC shuts down; that's the success signal here
        print(f"  [act ] click sent; connection closed during exit ({e.__class__.__name__})")


# ------------------------------------------------------------------- Driver --
async def discover_port(timeout=300):
    """Wait until worlddriver-rpc.port file exists and contains a valid port."""
    start = time.time()
    while time.time() - start < timeout:
        if PORT_FILE.exists():
            try:
                p = int(PORT_FILE.read_text().strip())
                if p > 0:
                    return p
            except ValueError:
                pass
        await asyncio.sleep(1.0)
    raise TimeoutError(f"{PORT_FILE} never appeared")


async def main():
    print(f"[discover] looking for {PORT_FILE}")
    port = await discover_port()
    print(f"[discover] port={port}")
    uri = f"ws://127.0.0.1:{port}/rpc"
    # Connect with retries — RPC may bind before the websocket handler is fully wired
    last_err = None
    for attempt in range(30):
        try:
            ws = await websockets.connect(uri, max_size=8 * 1024 * 1024, ping_interval=None)
            break
        except Exception as e:
            last_err = e
            await asyncio.sleep(1.0)
    else:
        raise RuntimeError(f"could not connect to {uri}: {last_err}")
    print(f"[connect] {uri}")

    async with ws:
        rpc = Rpc(ws)
        # Wait until client api is registered (post-FMLClientSetupEvent)
        for _ in range(60):
            try:
                await rpc.call("mc.client.screen.info", timeout=5)
                break
            except Exception:
                await asyncio.sleep(1.0)
        else:
            raise RuntimeError("client api never became reachable")

        print("\n=== Step 1: land on TitleScreen ===")
        await goto_main_menu(rpc)
        # Extra settle so the panorama fade-in + button fade-in fully render
        await shot(rpc, "01-title-screen", settle=2.5)

        print("\n=== Step 2: navigate to world creation form ===")
        await goto_create_world(rpc)
        # Park cursor off-button so the Difficulty tooltip doesn't render over
        # the form (cursor stays at last-click pos otherwise).
        await park_cursor(rpc)
        await shot(rpc, "02-create-world-form", settle=1.0)

        print("\n=== Step 3: commit world creation + spawn ===")
        # Capture the loading-progress screen on the way in
        await commit_world_creation(rpc)
        try:
            await shot(rpc, "03-world-loading", settle=0.2, clear_overlay=False)
        except Exception:
            pass  # ProgressScreen window is tiny; if missed, no harm
        await wait_in_world(rpc)
        await shot(rpc, "04-in-world", settle=0.5)

        print("\n=== Step 4: open pause menu via RPC ===")
        await rpc.call("mc.client.screen.openPause")
        await wait_until(rpc, lambda i: i.get("type") == "PauseScreen", label="PauseScreen")
        await park_cursor(rpc)
        await shot(rpc, "05-pause-menu", settle=0.8)

        print("\n=== Step 5: Save and Quit → TitleScreen ===")
        await save_and_quit(rpc)
        await park_cursor(rpc)
        await shot(rpc, "06-back-at-title", settle=1.5)

        print("\n=== Step 6: click Quit Game (terminal) ===")
        await quit_game(rpc)
        trace("done", elapsed=round(time.time() - T0, 2))

    # ---- summary footer ----
    shots = sorted(SHOTS.glob("*.png"))
    total_bytes = sum(p.stat().st_size for p in shots)
    print("\n" + "=" * 64)
    print(f"  smoke complete in {time.time()-T0:.1f}s")
    print(f"  screenshots: {len(shots)} files, {total_bytes/1024:.0f} KB total")
    for p in shots:
        print(f"    {p.name:32s}  {p.stat().st_size/1024:6.0f} KB")
    print(f"  trace: {TRACE_FILE.relative_to(REPO)} ({len(TRACE)} entries)")
    print("=" * 64)

    print("\n=== done; saved 6 screenshots to", SHOTS, "===")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    finally:
        TRACE_FILE.write_text(json.dumps(TRACE, indent=2))
