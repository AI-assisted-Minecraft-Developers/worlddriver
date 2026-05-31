#!/usr/bin/env python3
"""Drive the running Fabric client from the title screen into a SURVIVAL,
cheats-enabled, AMPLIFIED world named "Mountains" (tall terrain everywhere — for
elytra mountain-flight testing), then stop in-world. Reuses into_world.py's
helpers; only the create-form settings differ (Survival / Allow Cheats /
Amplified instead of Creative / Superflat)."""
import asyncio

import websockets
import react_smoke as rs
import into_world as iw

WORLD_NAME = "Mountains"


async def set_survival_cheats_amplified(rpc):
    """Set Game Mode → Survival, Allow Cheats → ON, World Type → Amplified on the
    CreateWorldScreen. Best-effort cycle-button clicks (bail gracefully)."""

    async def cycle_to(label_prefix, target_substr, why, max_clicks=8):
        for _ in range(max_clicks):
            tree = await rpc.call("mc.client.screen.tree")
            btn = rs.find_widget(tree, rs.by_label(label_prefix))
            if btn is None:
                print(f"  [warn] cycle button '{label_prefix}' not found; skipping {why}")
                return False
            msg = (btn.get("message") or "")
            if target_substr.lower() in msg.lower():
                print(f"  [obs ] '{label_prefix}' = '{msg}'  // {why}")
                return True
            await rs.click_widget(rpc, btn, why=f"cycle {why}")
            await asyncio.sleep(0.2)
        print(f"  [warn] could not reach '{target_substr}' for {why}")
        return False

    # Game tab: game mode → Survival, then Allow Cheats → ON.
    await cycle_to("Game Mode", "Survival", why="set Survival game mode")
    await cycle_to("Allow Cheats", "ON", why="enable cheats/commands")

    # World tab: switch, retry cheats there, set World Type → Amplified.
    tree = await rpc.call("mc.client.screen.tree")
    world_tab = (rs.find_widget(tree, rs.by_label("World"))
                 or rs.find_widget(tree, rs.by_label("More")))
    if world_tab is not None and (world_tab.get("message") or ""):
        await rs.click_widget(rpc, world_tab, why="open World/More tab")
        await asyncio.sleep(0.3)
    await cycle_to("Allow Cheats", "ON", why="enable cheats (World tab)")
    await cycle_to("World Type", "Amplified", why="set Amplified world type")


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

        info = await rs.observe(rpc)
        if info.get("worldOpen") and info.get("hasPlayer") and not info.get("hasScreen"):
            print("[in-world] already in a world; nothing to do")
            return
        if info.get("type") == "DeathScreen":
            print("=== dead player on startup → respawn ===")
            await iw.wait_in_world_or_respawn(rpc)
            return

        print("=== to title ===")
        await rs.goto_main_menu(rpc)
        print("=== singleplayer / world select ===")
        info = await iw.goto_singleplayer(rpc)

        created = False
        if info.get("type") == "SelectWorldScreen":
            tree = await iw.await_world_list(rpc)
            existing = iw.find_existing_world(tree, WORLD_NAME)
            if existing is not None:
                print(f"=== open existing world '{WORLD_NAME}' ===")
                await iw.open_existing_world(rpc, existing)
            else:
                print(f"=== create world '{WORLD_NAME}' (survival/cheats/amplified) ===")
                await iw.open_create_form(rpc)
                await iw.set_world_name(rpc, WORLD_NAME)
                await set_survival_cheats_amplified(rpc)
                await iw.commit_world_creation(rpc)
                created = True
        else:
            print(f"=== create world '{WORLD_NAME}' (fresh saves dir) ===")
            await iw.set_world_name(rpc, WORLD_NAME)
            await set_survival_cheats_amplified(rpc)
            await iw.commit_world_creation(rpc)
            created = True

        await iw.wait_in_world_or_respawn(rpc)
        info = await rs.observe(rpc)
        print(f"[in-world] OK type={info.get('type')} hasPlayer={info['hasPlayer']} "
              f"world='{WORLD_NAME}' created={created}")


if __name__ == "__main__":
    asyncio.run(main())
