#!/usr/bin/env python3
"""Drive the running Fabric client (via WorldDriver WS RPC) from the title
screen into a SINGLE FIXED, reusable singleplayer world named WORLD_NAME, then
stop — leaving the client running in-world so MCP tools can exercise the bot.
Reuses react_smoke's helpers.

Selection-vs-creation: on the SelectWorld screen we scan the world list for an
entry whose label contains WORLD_NAME. If found, we SELECT+OPEN (play) it so we
reuse the same world every run. Only if it is absent do we create it ONCE — as a
superflat, creative, cheats-enabled world named WORLD_NAME."""
import asyncio
import os
import time

import websockets
import react_smoke as rs

# Fixed, reusable world. Every run plays THIS world instead of piling up a new
# one in the saves list. Created once (superflat/creative/cheats), reused after.
WORLD_NAME = os.environ.get("AGENT_WORLD", "AgentTest")


async def goto_singleplayer(rpc):
    """From TitleScreen → click Singleplayer → land on SelectWorldScreen (or
    CreateWorldScreen if the game jumps straight there when there are no saves)."""
    tree = await rpc.call("mc.client.screen.tree")
    sp = rs.find_widget(tree, rs.by_label("Singleplayer"))
    if sp is None:
        raise RuntimeError("Singleplayer button not found on TitleScreen")
    await rs.click_widget(rpc, sp, why="enter singleplayer flow")
    return await rs.wait_until(
        rpc,
        lambda i: i.get("type") in ("SelectWorldScreen", "CreateWorldScreen"),
        label="SelectWorld or CreateWorld",
    )


def find_existing_world(tree, name):
    """Search the SelectWorld list for an entry whose label mentions ``name``.
    World-list rows are WorldListEntry widgets whose ``message`` carries the
    level name (e.g. "...AgentTest"). Match case-insensitively on the substring."""
    return rs.find_widget(tree, rs.by_label(name))


async def await_world_list(rpc, timeout=8):
    """The SelectWorld list is read off disk asynchronously, so right after the
    screen opens its WorldListEntry rows may not be in the widget tree yet —
    searching too early finds nothing and (used to) create a duplicate world.
    Poll until at least one WorldListEntry appears (or the list is confirmed
    empty after the timeout). Returns the latest tree."""
    start = time.time()
    tree = await rpc.call("mc.client.screen.tree")
    while time.time() - start < timeout:
        if rs.find_widget(tree, lambda n: isinstance(n, dict)
                          and n.get("type") == "WorldListEntry") is not None:
            return tree
        await asyncio.sleep(0.3)
        tree = await rpc.call("mc.client.screen.tree")
    return tree


async def respawn_if_dead(rpc):
    """If a DeathScreen is currently showing, click its Respawn button so the
    player comes back alive in the same world. Returns True if it acted."""
    info = await rpc.call("mc.client.screen.info")
    if info.get("type") != "DeathScreen":
        return False
    tree = await rpc.call("mc.client.screen.tree")
    btn = rs.find_widget(tree, rs.by_label("Respawn"))
    if btn is None:
        raise RuntimeError("DeathScreen has no Respawn button")
    await rs.click_widget(rpc, btn, why="respawn the dead player into the world")
    return True


async def wait_in_world_or_respawn(rpc, timeout=240):
    """Wait until fully in-world (no screen, player spawned). A saved world that
    was left with a DEAD player loads straight onto a DeathScreen (after we click
    Play) — plain wait_in_world would hang forever, so whenever we see a
    DeathScreen we click Respawn and keep waiting. Returns the final screen info."""
    start = time.time()
    while time.time() - start < timeout:
        info = await rpc.call("mc.client.screen.info")
        if info.get("worldOpen") and info.get("hasPlayer") and not info.get("hasScreen"):
            return info
        if info.get("type") == "DeathScreen":
            print("  [act ] dead player on load → Respawn")
            await respawn_if_dead(rpc)
        await asyncio.sleep(0.5)
    raise TimeoutError(f"not in-world within {timeout}s (last={info})")


async def open_existing_world(rpc, entry):
    """Select+open an existing SelectWorld entry: a single click highlights the
    row and enables the Play button; the same row double-click semantics aren't
    guaranteed over RPC, so we click the row then click 'Play Selected World'."""
    await rs.click_widget(rpc, entry, why=f"select existing world '{WORLD_NAME}'")
    await asyncio.sleep(0.3)
    tree = await rpc.call("mc.client.screen.tree")
    play = (rs.find_widget(tree, rs.by_label("Play Selected World"))
            or rs.find_widget(tree, rs.by_label("Play Selected"))
            or rs.find_widget(tree, rs.by_label("Play")))
    if play is None:
        raise RuntimeError("Play Selected World button not found on SelectWorldScreen")
    await rs.click_widget(rpc, play, why=f"open existing world '{WORLD_NAME}'")


async def open_create_form(rpc):
    """From SelectWorldScreen click 'Create New World' → CreateWorldScreen."""
    tree = await rpc.call("mc.client.screen.tree")
    cnw = rs.find_widget(tree, rs.by_label("Create New World"))
    if cnw is None:
        raise RuntimeError("Create New World button not found on SelectWorldScreen")
    await rs.click_widget(rpc, cnw, why="open world-creation form")
    await rs.wait_until(rpc, lambda i: i.get("type") == "CreateWorldScreen",
                        label="CreateWorldScreen")


async def set_world_name(rpc, name):
    """Type the fixed name into the CreateWorld name EditBox. The name field is
    the focused EditBox at the top of the form; click it, clear it, type name."""
    tree = await rpc.call("mc.client.screen.tree")
    # The name field is an EditBox. It usually has no button-style 'message';
    # locate by node type when available, else fall back to the first EditBox.
    box = rs.find_widget(tree, lambda n: isinstance(n, dict)
                         and "EditBox" in str(n.get("type", "")))
    if box is not None:
        cx = box["x"] + box["width"] / 2
        cy = box["y"] + box["height"] / 2
        print(f"  [act ] click name field @({cx:.1f},{cy:.1f})  // focus world-name box")
        rs.trace("act", action="click", label="<name-field>", x=cx, y=cy,
                 reason="focus world-name box")
        await rpc.call("mc.client.input.click", {"x": cx, "y": cy, "button": 0})
        await asyncio.sleep(0.2)
    # Clear the default "New World" then type our name. Ctrl+A select-all proved
    # unreliable over RPC (it deleted only one char, leaving "New Worl", and the
    # typed name was appended → "New WorlAgentTest"), so press END then many
    # BACKSPACE/DELETE to clear from any cursor position regardless of length.
    print(f"  [act ] clear + type world name '{name}'")
    rs.trace("act", action="typeText", text=name, reason="set fixed world name")
    try:
        await rpc.call("mc.client.input.key", {"key": "END"})
        for _ in range(32):
            await rpc.call("mc.client.input.key", {"key": "BACKSPACE"})
        for _ in range(32):
            await rpc.call("mc.client.input.key", {"key": "DELETE"})
    except Exception as e:
        print(f"  [warn] could not clear name field via keys ({e}); typing anyway")
    await rpc.call("mc.client.input.typeText", {"text": name})


async def set_superflat_creative_cheats(rpc):
    """Best-effort: switch the new world to Superflat world-type, Creative game
    mode, and Allow Cheats — all reachable from CreateWorldScreen's UI.

    Game mode + cheats live on the main 'Game' tab as cycle buttons. World type
    (Superflat) lives on the 'World' / 'More' tab as a 'World Type: ...' cycle
    button. Toggling cycle buttons via RPC clicks is best-effort: we click the
    button until its label reads the target, but bail gracefully if the widget
    layout differs so world creation still succeeds (just reusable by name)."""

    async def cycle_to(label_prefix, target_substr, why, max_clicks=6):
        """Click a 'Prefix: Value' cycle button until Value contains target."""
        for _ in range(max_clicks):
            tree = await rpc.call("mc.client.screen.tree")
            btn = rs.find_widget(tree, rs.by_label(label_prefix))
            if btn is None:
                print(f"  [warn] cycle button '{label_prefix}' not found; skipping {why}")
                return False
            msg = (btn.get("message") or "")
            if target_substr.lower() in msg.lower():
                print(f"  [obs ] '{label_prefix}' already '{msg}'  // {why}")
                return True
            await rs.click_widget(rpc, btn, why=f"cycle {why}")
            await asyncio.sleep(0.2)
        print(f"  [warn] could not reach '{target_substr}' for {why}")
        return False

    # --- Game tab: game mode → Creative, then enable cheats (Allow Cheats) ---
    await cycle_to("Game Mode", "Creative", why="set Creative game mode")
    # 'Allow Cheats' is a boolean cycle button (Allow Cheats: ON/OFF).
    await cycle_to("Allow Cheats", "ON", why="enable cheats/commands")

    # --- World tab: switch to it, then set World Type: Superflat ---------------
    tree = await rpc.call("mc.client.screen.tree")
    world_tab = (rs.find_widget(tree, rs.by_label("World"))
                 or rs.find_widget(tree, rs.by_label("More")))
    if world_tab is not None and (world_tab.get("message") or ""):
        # Only click if it's the tab header (avoid re-clicking the name box etc.)
        await rs.click_widget(rpc, world_tab, why="open World/More tab")
        await asyncio.sleep(0.3)
    # Some 'Allow Cheats' toggles live on the World tab in newer versions — try
    # again here in case it wasn't present on the Game tab.
    await cycle_to("Allow Cheats", "ON", why="enable cheats/commands (World tab)")
    # World Type cycle button: Default → Superflat (→ Large Biomes → ...).
    await cycle_to("World Type", "Superflat", why="set Superflat world type")


async def commit_world_creation(rpc):
    """On CreateWorldScreen, click the action 'Create New World' button — fires
    world gen; returns immediately, caller polls with wait_in_world."""
    tree = await rpc.call("mc.client.screen.tree")
    cnw = rs.find_widget(tree, rs.by_label("Create New World"))
    if cnw is None:
        raise RuntimeError("Create New World action button not found")
    await rs.click_widget(rpc, cnw, why="commit world creation")


async def main():
    port = await rs.discover_port()
    uri = f"ws://127.0.0.1:{port}/rpc"
    last = None
    for _ in range(30):
        try:
            ws = await websockets.connect(uri, max_size=8 * 1024 * 1024, ping_interval=None)
            break
        except Exception as e:
            last = e
            await asyncio.sleep(1.0)
    else:
        raise RuntimeError(f"connect failed: {last}")
    print(f"[connect] {uri}")
    async with ws:
        rpc = rs.Rpc(ws)
        for _ in range(120):
            try:
                await rpc.call("mc.client.screen.info", timeout=5)
                break
            except Exception:
                await asyncio.sleep(1.0)
        else:
            raise RuntimeError("client api never reachable")

        # --- early-out: already in a world → nothing to do ---
        info = await rs.observe(rpc)
        if info.get("worldOpen") and info.get("hasPlayer") and not info.get("hasScreen"):
            print("[in-world] already in a world; nothing to do")
            return

        # Edge case (already in-world but DEAD on startup): a DeathScreen sits over
        # an open world. The early-out above misses it (hasScreen is true) and
        # goto_main_menu would hang (it waits for a TitleScreen). Respawn in place.
        if info.get("type") == "DeathScreen":
            print("=== dead player on startup → respawn ===")
            await wait_in_world_or_respawn(rpc)
            print(f"[in-world] respawned into '{WORLD_NAME}'")
            return

        print("=== to title ===")
        await rs.goto_main_menu(rpc)

        print("=== singleplayer / world select ===")
        info = await goto_singleplayer(rpc)

        created = False
        if info.get("type") == "SelectWorldScreen":
            tree = await await_world_list(rpc)
            existing = find_existing_world(tree, WORLD_NAME)
            if existing is not None:
                print(f"=== open existing world '{WORLD_NAME}' ===")
                await open_existing_world(rpc, existing)
            else:
                print(f"=== create world '{WORLD_NAME}' (not found in saves) ===")
                await open_create_form(rpc)
                await set_world_name(rpc, WORLD_NAME)
                await set_superflat_creative_cheats(rpc)
                await commit_world_creation(rpc)
                created = True
        else:
            # Jumped straight into CreateWorldScreen (no saves at all): create once.
            print(f"=== create world '{WORLD_NAME}' (fresh saves dir) ===")
            await set_world_name(rpc, WORLD_NAME)
            await set_superflat_creative_cheats(rpc)
            await commit_world_creation(rpc)
            created = True

        # The reused world may load onto a DeathScreen (saved with a dead player);
        # wait_in_world_or_respawn clicks Respawn as needed instead of hanging.
        await wait_in_world_or_respawn(rpc)
        info = await rs.observe(rpc)
        print(f"[in-world] OK type={info.get('type')} hasPlayer={info['hasPlayer']} "
              f"world='{WORLD_NAME}' created={created}")


if __name__ == "__main__":
    asyncio.run(main())
