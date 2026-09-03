#!/usr/bin/env bash
# Drive a real Fabric client through main-menu → world-create → in-world → quit
# under Xvfb 1280x720, leaving a PNG at every step. MC auto-picks GUI scale=2
# at this resolution, so internal (w=640,h=360) maps to window pixel via *2.
#
# Layout assumptions (scale=2, 1280x720 window, ModMenu inserts "Mods" between
# Realms and the Options/Quit row):
#   MainScreen — SP (640,296)  MP (640,344)  Realms (640,392)  Mods (640,440)
#                Options (538,488)  Quit (742,488)
#   SelectWorld — Select (482,636)  Create (798,636)
#                 Edit (404,684) Delete (560,684) Re-create (720,684) Cancel (876,684)
#   CreateWorld — Create (480,684)  Cancel (800,684)
#   PauseScreen — BackToGame (640,248)  Advancements (534,312)  Stats (742,312)
#                 SendFeedback (534,360)  ReportBugs (742,360)
#                 Options (534,408)  ShareToLan (742,408)
#                 SaveAndQuit (640,456)

set -uo pipefail

REPO=/home/gardel/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver
SHOTS=$REPO/fabric/run/smoke
mkdir -p "$SHOTS"
# Keep prior runclient.log for diffing if needed
[ -f "$SHOTS/runclient.log" ] && mv "$SHOTS/runclient.log" "$SHOTS/runclient.prev.log"
rm -f "$SHOTS"/*.png

pkill -f "Xvfb :99" 2>/dev/null
pkill -f "matchbox-window-manager" 2>/dev/null
sleep 1
Xvfb :99 -screen 0 1280x720x24 -ac +extension GLX +render -noreset &>/tmp/xvfb.log &
sleep 1
export DISPLAY=:99
# Without a WM, Xvfb won't route focus / mouse clicks to MC reliably (xdotool
# key works because keys go to whatever holds keyboard focus, but click events
# need the window-under-cursor to actually receive them via SubstructureRedirect
# — that's what matchbox provides as a minimal WM).
matchbox-window-manager -use_titlebar no -use_cursor yes &>/tmp/mbwm.log &
sleep 1
echo "[xvfb] DISPLAY=$DISPLAY (with matchbox WM)"

shot()  { local name=$1; local pre=${2:-0}; sleep "$pre"; \
          import -window root -display :99 "$SHOTS/$name.png"; \
          echo "[shot] $name.png"; }
click() { xdotool mousemove --sync "$1" "$2"; sleep 0.3; xdotool click 1; sleep 0.4; }
key()   { xdotool key "$1"; sleep 0.3; }

echo "[run] gradle :fabric:runClient"
cd "$REPO"
DISPLAY=:99 ./gradlew :fabric:runClient > "$SHOTS/runclient.log" 2>&1 &
GRADLE_PID=$!
echo "[run] gradle PID=$GRADLE_PID"

WIN_ID=""
for i in $(seq 1 240); do
    WIN_ID=$(xdotool search --name "Minecraft" 2>/dev/null | head -1)
    if [ -n "$WIN_ID" ]; then
        echo "[wait] window at poll $i (id=$WIN_ID)"
        break
    fi
    sleep 2
done
if [ -z "$WIN_ID" ]; then
    echo "[wait] window never appeared — tailing log:"
    tail -50 "$SHOTS/runclient.log"
    kill "$GRADLE_PID" 2>/dev/null || true
    exit 1
fi
xdotool windowactivate "$WIN_ID" 2>/dev/null || true

# Welcome dialog → press Escape (or Continue's default focus). ESC closes it
# unconditionally; vanilla TitleScreen handles ESC as no-op.
echo "[ui] settling (8s), then ESC to dismiss first-run welcome"
sleep 8
key Escape
sleep 1
shot 01-main-menu 2

# Singleplayer
click 640 296
shot 02-singleplayer-select 3

# Create New World (right button of bottom-row)
click 798 636
shot 03-world-create-form 4

# Final "Create New World" action button on CreateWorldScreen (bottom-left)
click 480 684
shot 04-loading-world 2

# Software-rendered world load is slow; give it 60s with intermediate shots
sleep 25
shot 04b-loading-still 0
sleep 35
shot 05-in-world 0

# Pause menu
key Escape
shot 06-pause-menu 2

# Save and Quit to Title
click 640 456
echo "[ui] saving + returning to title (10s)"
shot 07-back-to-title 10

# Quit Game (bottom-right of Options/Quit row)
click 742 488
sleep 2
shot 08-after-quit 0

echo "[wind] awaiting gradle exit"
for i in $(seq 1 30); do
    kill -0 "$GRADLE_PID" 2>/dev/null || { echo "[wind] gradle exited"; break; }
    sleep 1
done
kill "$GRADLE_PID" 2>/dev/null || true
pkill -f "fabric.*runClient" 2>/dev/null || true

echo "=== shots ==="
ls -lh "$SHOTS"/*.png
