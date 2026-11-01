#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_PORT="${PORT:-8080}"

if ! [[ "$APP_PORT" =~ ^[0-9]+$ ]] || (( APP_PORT < 1 || APP_PORT > 65535 )); then
  echo "Invalid PORT '$APP_PORT'. Choose a port from 1 to 65535." >&2
  exit 2
fi

if ! command -v lsof >/dev/null 2>&1; then
  echo "lsof is required to check whether port $APP_PORT is in use." >&2
  exit 1
fi

LISTENER_PIDS=""
APEX_BACKEND_PIDS=""
while IFS= read -r pid; do
  if [[ -n "$pid" ]]; then
    LISTENER_PIDS+="${pid}"$'\n'
  fi
done < <(lsof -nP -tiTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | sort -u)

while IFS= read -r pid; do
  [[ -z "$pid" ]] && continue
  COMMAND_LINE="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  PROCESS_NAME="$(ps -p "$pid" -o comm= 2>/dev/null || true)"
  PROCESS_CWD="$(lsof -nP -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  if [[ "$COMMAND_LINE" == *"com.apex.hrm.HrmApplication"* || \
        ( "$COMMAND_LINE" == *"org.codehaus.plexus.classworlds.launcher.Launcher"* && "$COMMAND_LINE" == *"spring-boot:run"* && "$COMMAND_LINE" == *"apex-hrm"* ) ]]; then
    APEX_BACKEND_PIDS+="${pid}"$'\n'
    echo "Stopping existing Apex HRM backend (PID $pid) on port $APP_PORT..."
    kill "$pid" 2>/dev/null || true
  elif [[ "${PROCESS_NAME##*/}" == "java" && "$PROCESS_CWD" == "$SCRIPT_DIR" ]]; then
    APEX_BACKEND_PIDS+="${pid}"$'\n'
    echo "Stopping Java process (PID $pid) running from this Apex HRM project on port $APP_PORT..."
    kill "$pid" 2>/dev/null || true
  else
    echo "Port $APP_PORT is occupied by another process (PID $pid)." >&2
    echo "Stop it or choose a different port with PORT=... ./run.sh" >&2
    exit 1
  fi
done <<< "$LISTENER_PIDS"

if [[ -n "$LISTENER_PIDS" ]]; then
  for _ in {1..30}; do
    if ! lsof -nP -tiTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if lsof -nP -tiTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    while IFS= read -r pid; do
      [[ -z "$pid" ]] && continue
      if kill -0 "$pid" 2>/dev/null; then
        echo "Force stopping Apex HRM (PID $pid) so port $APP_PORT is available..."
        kill -KILL "$pid" 2>/dev/null || true
      fi
    done <<< "$APEX_BACKEND_PIDS"
    if lsof -nP -tiTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Port $APP_PORT is still occupied; refusing to start a second backend." >&2
      exit 1
    fi
  fi
fi

echo "Starting Apex HRM on port $APP_PORT..."
exec mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=$APP_PORT"
