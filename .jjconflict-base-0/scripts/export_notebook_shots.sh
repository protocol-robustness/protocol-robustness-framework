#!/usr/bin/env bash
# Ensure the Clerk server is running on :7777, then run the screenshot exporter.
# Usage: scripts/export_notebook_shots.sh [args...]   (args passed to the python script)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${CLERK_URL:-http://localhost:7777}"

is_up() {
  curl -s -o /dev/null -w "%{http_code}" --max-time 4 "$URL/" 2>/dev/null | grep -qE "^(200|302|304)$"
}

ensure_server() {
  if is_up; then
    echo "[shots] Clerk server already running at $URL"
    return
  fi
  echo "[shots] Starting Clerk server (pre-evaluates all notebooks; this can take a few minutes)..."
  setsid nohup clojure -M:with-sew -m notebooks.serve >"$ROOT/target/notebook-server.log" 2>&1 </dev/null &
  for _ in $(seq 1 120); do
    if is_up; then
      echo "[shots] Clerk server is up at $URL"
      return
    fi
    sleep 5
  done
  echo "[shots] ERROR: Clerk server did not become ready in time. See target/notebook-server.log" >&2
  exit 1
}

# --index-only / --flat never need the Clerk server (pure index rebuild / CI gate
# against an already-running server), so skip the ensure step when requested.
if [[ " $* " == *" --index-only "* ]]; then
  echo "[shots] index-only: skipping server ensure"
  exec python3 "$ROOT/scripts/export_notebook_shots.py" --base-url "$URL" "$@"
fi

ensure_server
exec python3 "$ROOT/scripts/export_notebook_shots.py" --base-url "$URL" "$@"
