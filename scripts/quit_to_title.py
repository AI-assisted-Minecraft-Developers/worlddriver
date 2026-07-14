#!/usr/bin/env python3
"""From an in-world PauseScreen (or in-world), Save-and-Quit to the TitleScreen."""
import asyncio, os, websockets
import react_smoke as rs

async def main():
    port = int(os.environ.get("AGENT_RPC_PORT", "39801"))
    async with websockets.connect(f"ws://127.0.0.1:{port}/rpc", max_size=8*1024*1024, ping_interval=None) as ws:
        rpc = rs.Rpc(ws)
        info = await rs.observe(rpc)
        if info.get("type") != "PauseScreen":
            # ensure pause menu open
            await rpc.call("mc.client.input.key", {"key": "escape"})
            await asyncio.sleep(1)
            info = await rs.observe(rpc)
        tree = await rpc.call("mc.client.screen.tree")
        btn = rs.find_widget(tree, rs.by_label("Save and Quit"))
        if btn is None:
            btn = rs.find_widget(tree, rs.by_label("Quit"))
        if btn is None:
            raise RuntimeError("Save-and-Quit button not found on PauseScreen")
        await rs.click_widget(rpc, btn, why="save and quit to title")
        await rs.wait_until(rpc, lambda i: i.get("type") == "TitleScreen",
                            label="TitleScreen", timeout=60)
        print("[quit] at TitleScreen OK")

asyncio.run(main())
