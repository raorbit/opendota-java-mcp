#!/usr/bin/env bash
# Lifecycle wrapper for the shared OpenDota sidecar (bash).
#
#   scripts/sidecar.sh start    # build (if needed) and start the sidecar in the background
#   scripts/sidecar.sh stop     # stop the running sidecar
#   scripts/sidecar.sh status   # query GET /health
#   scripts/sidecar.sh restart  # stop then start
#
# One sidecar per machine: start refuses to launch a second when one is already running.
# Set OPENDOTA_API_KEY in the environment before `start` so the sidecar holds the key.
# Override the port with OPENDOTA_SIDECAR_PORT (default 31337).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT_DIR/sidecar/target/opendota-sidecar-1.0.0.jar"
RUN_DIR="$ROOT_DIR/sidecar/.run"
PID_FILE="$RUN_DIR/sidecar.pid"
LOG_FILE="$RUN_DIR/sidecar.log"
PORT="${OPENDOTA_SIDECAR_PORT:-31337}"

is_running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

start() {
  if is_running; then
    echo "sidecar already running (pid $(cat "$PID_FILE"))"; exit 0
  fi
  if [ ! -f "$JAR" ]; then
    echo "building sidecar jar..."
    mvn -B -f "$ROOT_DIR/sidecar/pom.xml" -q clean package
  fi
  mkdir -p "$RUN_DIR"
  echo "starting sidecar on 127.0.0.1:$PORT (logs: $LOG_FILE)"
  nohup java -jar "$JAR" >"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "started (pid $(cat "$PID_FILE"))"
}

stop() {
  if ! is_running; then
    echo "sidecar not running"; rm -f "$PID_FILE"; return 0
  fi
  local pid; pid="$(cat "$PID_FILE")"
  echo "stopping sidecar (pid $pid)"
  kill "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

status() {
  if is_running; then echo "process: running (pid $(cat "$PID_FILE"))"; else echo "process: not running"; fi
  if command -v curl >/dev/null 2>&1; then
    echo -n "health: "
    curl -fsS "http://127.0.0.1:$PORT/health" || echo "(unreachable)"
    echo
  fi
}

case "${1:-}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  *) echo "usage: $0 {start|stop|restart|status}"; exit 2 ;;
esac
