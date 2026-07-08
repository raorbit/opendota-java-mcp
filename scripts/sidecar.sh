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
JAR="$ROOT_DIR/sidecar/target/opendota-sidecar-1.2.0.jar"
RUN_DIR="$ROOT_DIR/sidecar/.run"
PID_FILE="$RUN_DIR/sidecar.pid"
LOG_FILE="$RUN_DIR/sidecar.log"
PORT="${OPENDOTA_SIDECAR_PORT:-31337}"

# True only if the recorded PID is a LIVE process that is actually our sidecar JVM — not a stale PID the
# OS has recycled for an unrelated program after a reboot or an unclean exit. Without the identity check,
# `stop` could SIGKILL that innocent process and `start`/`status` would falsely report "already running".
# The identity check needs `ps -p <pid> -o args=`, which MSYS/Git Bash and busybox `ps` don't support:
# there, "ps can't REPORT the command line" must not be read as "the command line doesn't match" — that
# misread returned not-running for a live sidecar, so `stop` deleted the pid file and orphaned the JVM
# (port held, key loaded) and the next start died on BindException. When ps can't report, degrade to the
# liveness check alone; when it can, a non-matching command line still counts as not ours (never signal
# a process we can verify isn't ours).
is_running() {
  [ -f "$PID_FILE" ] || return 1
  local pid; pid="$(cat "$PID_FILE" 2>/dev/null)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  local args
  if args="$(ps -p "$pid" -o args= 2>/dev/null)" && [ -n "$args" ]; then
    printf '%s\n' "$args" | grep -q 'opendota-sidecar'
  else
    return 0   # alive, and ps can't report the command line here (MSYS/busybox) — trust liveness
  fi
}

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
  local pid=$!
  echo "$pid" > "$PID_FILE"
  # Verify it actually came up rather than reporting a stale 'started' for a JVM that died at once
  # (e.g. BindException because the port is taken). Poll /health for ~5s; bail if the process exits.
  if command -v curl >/dev/null 2>&1; then
    for _ in $(seq 1 25); do
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "sidecar exited during startup — see $LOG_FILE"; rm -f "$PID_FILE"; exit 1
      fi
      if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
        echo "started (pid $pid); /health ok on 127.0.0.1:$PORT"; return 0
      fi
      sleep 0.2
    done
    echo "started (pid $pid) but /health did not answer within 5s — check $LOG_FILE"
  else
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
      echo "started (pid $pid)"
    else
      echo "sidecar exited during startup — see $LOG_FILE"; rm -f "$PID_FILE"; exit 1
    fi
  fi
}

stop() {
  if ! is_running; then
    echo "sidecar not running"; rm -f "$PID_FILE"; return 0
  fi
  local pid; pid="$(cat "$PID_FILE")"
  echo "stopping sidecar (pid $pid)"
  kill "$pid" 2>/dev/null || true
  # Wait for the JVM to actually exit (and release the port) before returning, so a following
  # `restart` start() doesn't lose the bind race and silently die on BindException. SIGKILL as
  # a last resort after ~5s.
  for _ in $(seq 1 50); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "sidecar (pid $pid) did not exit in time, sending SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
  fi
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
