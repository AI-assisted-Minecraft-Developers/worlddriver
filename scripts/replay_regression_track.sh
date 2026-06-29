#!/usr/bin/env bash
# replay_regression_track.sh — assertion half of the replay-corpus regression harness.
#
# The trigger half (set flags + mc.debug.replay <archive>) must be issued via the MCP
# (script_eval / mc.debug.replay) BEFORE calling this — it cannot be driven from bash.
# Procedure per regression case (see replays/REGRESSION.md):
#   1) MCP: mc.bot.setting { <flags for this case> }
#   2) MCP: mc.debug.replay { file:"<archive>", restoreBlocks:true }
#   3) bash: scripts/replay_regression_track.sh <maxStuckThreshold> <arriveX> <arriveCmp>
#
# Tracks the live [walker] telemetry, extracts the peak totStuck and whether the bot
# reached the goal, and prints PASS/FAIL against the threshold. Deterministic because the
# replay restores the exact block envelope + start (so the same archive+flags reproduce
# byte-for-byte). This is the oracle the project was missing: a real recorded failure,
# replayed faithfully, asserted automatically.
#
# Args:
#   $1 maxStuckThreshold  — FAIL if peak totStuck exceeds this (ticks; /20 ≈ seconds)
#   $2 arriveX            — goal x (the bot arrives when its x crosses this)
#   $3 arriveCmp          — "ge" (x >= arriveX, eastward goal) or "le" (x <= arriveX)
#   $4 maxSeconds         — optional wall-clock cap (default 220)
set -u
LOG=fabric/run/logs/latest.log
THRESH=${1:?maxStuckThreshold}; ARRIVEX=${2:?arriveX}; CMP=${3:?ge|le}; MAXS=${4:-220}
MAXSTUCK=0; PREV=""; FROZEN=0; ARRIVED=0; ITERS=$((MAXS/6))
for i in $(seq 1 "$ITERS"); do
  sleep 6
  LINE=$(grep -E "\[walker\] t=" "$LOG" 2>/dev/null | tail -1)
  POS=$(echo "$LINE" | grep -oE "p=\(-?[0-9.]+,[0-9.]+,-?[0-9.]+\)")
  TS=$(echo "$LINE" | grep -oE "totStuck=[0-9]+" | grep -oE "[0-9]+")
  X=$(echo "$POS" | grep -oE "\(-?[0-9]+" | tr -d '(')
  [ -n "${TS:-}" ] && [ "$TS" -gt "$MAXSTUCK" ] && MAXSTUCK=$TS
  echo "$((i*6))s $POS ts=${TS:-?} peak=$MAXSTUCK"
  if [ -n "${X:-}" ]; then
    if [ "$CMP" = "ge" ] && [ "$X" -ge "$ARRIVEX" ] 2>/dev/null; then ARRIVED=1; break; fi
    if [ "$CMP" = "le" ] && [ "$X" -le "$ARRIVEX" ] 2>/dev/null; then ARRIVED=1; break; fi
  fi
  if [ "$POS" = "$PREV" ]; then FROZEN=$((FROZEN+1)); else FROZEN=0; fi
  [ "$FROZEN" -ge 16 ] && { echo "FROZEN $POS"; break; }
  PREV="$POS"
done
echo "---"
VERDICT="PASS"
[ "$MAXSTUCK" -gt "$THRESH" ] && VERDICT="FAIL(maxStuck $MAXSTUCK > $THRESH)"
[ "$ARRIVED" -ne 1 ] && VERDICT="FAIL(did not ARRIVE; $VERDICT)"
echo "peakStuck=$MAXSTUCK threshold=$THRESH arrived=$ARRIVED → $VERDICT"
[ "$VERDICT" = "PASS" ]
