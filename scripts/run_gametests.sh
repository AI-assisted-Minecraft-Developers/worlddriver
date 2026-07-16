#!/usr/bin/env bash
# Canonical GameTest entrypoint (task#85 P0). Guarantees, in order:
#   1. no leftover gametest JVM holds world/session.lock (kill by explicit PID — pkill is banned);
#   2. the persistent run-gametest world is deleted (killed-run pollution => processUnloads
#      single-tick livelock that GameTest timeouts cannot catch);
#   3. hard wall-clock cap (same livelock class never self-terminates);
#   4. verdict = BUILD status + vanilla required line + manifest reconciliation.
#      The mod reporter's "TOTAL:" line is never consulted.
set -u
cd "$(dirname "$0")/.."

LOG="${GT_LOG:-../neoforge-gametest-run.log}"
MANIFEST=neoforge/run-gametest/testkit-manifest.jsonl

sweep_jvms() {
  for pid in $(ps -eo pid,args | grep "[n]eoforge.gameTestServer" | awk '{print $1}'); do
    echo "[run_gametests] killing leftover gametest JVM pid=$pid"
    kill -9 "$pid"
  done
}

sweep_jvms
rm -rf neoforge/run-gametest/world
rm -f "$MANIFEST"

timeout --kill-after=30 "${GT_TIMEOUT:-3600}" ./gradlew :neoforge:runGameTestServer 2>&1 | tee "$LOG"
GRADLE_RC=${PIPESTATUS[0]}
if [ "$GRADLE_RC" -eq 124 ]; then
  echo "[run_gametests] WALL-CLOCK TIMEOUT after ${GT_TIMEOUT:-3600}s"
  # timeout killed the gradle wrapper; the game JVM is a child of the gradle
  # DAEMON and survives — sweep again so it cannot poison the next run.
  sweep_jvms
fi

python3 scripts/gt_reconcile.py --manifest "$MANIFEST" --log "$LOG"
RECON_RC=$?

echo "[run_gametests] gradle_rc=$GRADLE_RC reconcile_rc=$RECON_RC"
[ "$GRADLE_RC" -eq 0 ] && [ "$RECON_RC" -eq 0 ]
