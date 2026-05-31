#!/usr/bin/env bash
# Smoke test driven entirely through the AgentDriver WS RPC: no xdotool, no key
# synthesis. Boots Xvfb + matchbox + runClient + a Python ReAct loop that
# observes via mc.client.screen.tree, picks the right widget by label, then
# clicks via mc.client.input.click. Screenshots come from mc.client.screenshot.
#
# Xvfb is sized 1280x720 — matchbox maximizes MC into it so there are no black
# borders around the window.

set -uo pipefail

REPO=/home/coder/AI-assisted-Minecraft-Developers/agent-driver-mod
RUN=$REPO/fabric/run
SHOTS=$RUN/smoke
mkdir -p "$SHOTS"
[ -f "$SHOTS/runclient.log" ] && mv "$SHOTS/runclient.log" "$SHOTS/runclient.prev.log"
rm -f "$SHOTS"/*.png "$RUN/agent-rpc.port" "$RUN/agent-mcp.port"
# Wipe saved worlds so we deterministically go TitleScreen → CreateWorld
# (skipping SelectWorld) — keeps the ReAct trajectory short.
rm -rf "$RUN/saves" 2>/dev/null

pkill -f "Xvfb :99" 2>/dev/null
pkill -f "matchbox-window-manager" 2>/dev/null
sleep 1
Xvfb :99 -screen 0 1280x720x24 -ac +extension GLX +render -noreset &>/tmp/xvfb.log &
sleep 1
export DISPLAY=:99
matchbox-window-manager -use_titlebar no -use_cursor yes &>/tmp/mbwm.log &
sleep 1
echo "[xvfb] DISPLAY=$DISPLAY (matchbox WM up)"

echo "[run] gradle :fabric:runClient"
cd "$REPO"
DISPLAY=:99 ./gradlew :fabric:runClient > "$SHOTS/runclient.log" 2>&1 &
GRADLE_PID=$!
echo "[run] gradle PID=$GRADLE_PID"

# Run the Python driver. It blocks until done (or crash); after click 'Quit
# Game' MC exits so the gradle task wraps up on its own.
echo "[react] starting Python ReAct driver"
python3 "$REPO/scripts/react_smoke.py"
DRIVER_RC=$?
echo "[react] driver exit=$DRIVER_RC"

echo "[wind] awaiting gradle exit"
for i in $(seq 1 30); do
    kill -0 "$GRADLE_PID" 2>/dev/null || { echo "[wind] gradle exited"; break; }
    sleep 1
done
kill "$GRADLE_PID" 2>/dev/null || true
pkill -f "fabric.*runClient" 2>/dev/null || true

echo "=== shots ==="
ls -lh "$SHOTS"/*.png 2>/dev/null
exit $DRIVER_RC
