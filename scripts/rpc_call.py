#!/usr/bin/env python3
"""Call one worlddriver RPC method directly over the websocket.

Bypasses the MCP tool layer — useful when the harness's MCP tool schema is
frozen from an earlier build and strips newly-added params (e.g. a freshly
added mc.bot.setting key). Usage:

    python3 -u rpc_call.py mc.bot.setting '{"allowParkourPlace": true}'
    python3 -u rpc_call.py mc.bot.status
    python3 -u rpc_call.py mc.observe.player

Port defaults to 39801 (override with AGENT_RPC_PORT).
"""
import asyncio, json, os, sys
import websockets


async def main():
    method = sys.argv[1]
    params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    port = int(os.environ.get("AGENT_RPC_PORT", "39801"))
    uri = f"ws://127.0.0.1:{port}/rpc"
    async with websockets.connect(uri, max_size=8 * 1024 * 1024, ping_interval=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
            if msg.get("id") != 1:
                continue
            if "error" in msg:
                print(json.dumps({"error": msg["error"]}))
                sys.exit(1)
            print(json.dumps(msg.get("result")))
            return


asyncio.run(main())
