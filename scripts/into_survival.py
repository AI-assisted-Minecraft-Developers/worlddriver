#!/usr/bin/env python3
"""Switch the running client from whatever world it's in (or the title screen)
into the existing SurvivalTest save. Infrastructure only (GUI navigation) — does
NOT touch gameplay. Reuses react_smoke + into_world helpers."""
import asyncio
import websockets
import react_smoke as rs
import into_world as iw

WORLD_NAME = "SurvivalTest"
iw.WORLD_NAME = WORLD_NAME  # so reused helpers' log lines say the right name


async def main():
    port = await rs.discover_port()
    uri = f"ws://127.0.0.1:{port}/rpc"
    ws = None
    for _ in range(30):
        try:
            ws = await websockets.connect(uri, max_size=8 * 1024 * 1024, ping_interval=None)
            break
        except Exception:
            await asyncio.sleep(1.0)
    if ws is None:
        raise RuntimeError("connect failed")
    print(f"[connect] {uri}")
    async with ws:
        rpc = rs.Rpc(ws)
        for _ in range(120):
            try:
                await rpc.call("mc.client.screen.info", timeout=5)
                break
            except Exception:
                await asyncio.sleep(1.0)

        info = await rs.observe(rpc)
        # If already in SurvivalTest and alive, nothing to do. We can't cheaply tell
        # WHICH world, so always route via title to be deterministic — unless dead.
        if info.get("type") == "DeathScreen":
            print("=== dead on a DeathScreen → respawn first ===")
            await iw.wait_in_world_or_respawn(rpc)
            info = await rs.observe(rpc)

        print("=== quit to title ===")
        info = await rs.observe(rpc)
        if info.get("worldOpen") and not info.get("hasScreen"):
            # In-world: open the pause menu (Escape) then click Save and Quit.
            await rpc.call("mc.client.input.key", {"key": "ESCAPE"})
            await asyncio.sleep(0.5)
            tree = await rpc.call("mc.client.screen.tree")
            quit_btn = rs.find_widget(tree, rs.by_label("Save and Quit to Title")) \
                or rs.find_widget(tree, rs.by_label("Quit to Title")) \
                or rs.find_widget(tree, rs.by_label("Disconnect"))
            if quit_btn is None:
                raise RuntimeError(f"no quit button on pause menu; tree types around")
            await rs.click_widget(rpc, quit_btn, why="save and quit to title")
        await rs.goto_main_menu(rpc)

        print("=== singleplayer / world select ===")
        info = await iw.goto_singleplayer(rpc)
        if info.get("type") != "SelectWorldScreen":
            raise RuntimeError(f"expected SelectWorldScreen, got {info.get('type')}")

        tree = await iw.await_world_list(rpc)
        entry = iw.find_existing_world(tree, WORLD_NAME)
        if entry is None:
            raise RuntimeError(f"world '{WORLD_NAME}' not found in saves")
        print(f"=== open existing world '{WORLD_NAME}' ===")
        await iw.open_existing_world(rpc, entry)

        await iw.wait_in_world_or_respawn(rpc)
        info = await rs.observe(rpc)
        print(f"[in-world] OK type={info.get('type')} hasPlayer={info.get('hasPlayer')} world='{WORLD_NAME}'")


if __name__ == "__main__":
    asyncio.run(main())
