#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
export BACKEND_PORT

for port in "$BACKEND_PORT" "$FRONTEND_PORT"; do
  if ! [[ "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
    echo "Invalid port '$port'. Choose a port from 1 to 65535." >&2
    exit 2
  fi
done

if ! command -v lsof >/dev/null 2>&1; then
  echo "lsof is required to check for port conflicts." >&2
  exit 1
fi

stop_project_listener() {
  local port="$1" expected_dir="$2" service="$3" pids="" pid process_dir command_line
  while IFS= read -r pid; do
    [[ -z "$pid" ]] || pids+="${pid}"$'\n'
  done < <(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u)

  [[ -n "$pids" ]] || return 0

  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    process_dir="$(lsof -nP -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ "$process_dir" == "$expected_dir" || "$command_line" == *"com.apex.hrm.HrmApplication"* ]]; then
      echo "Stopping existing Apex HRM $service process (PID $pid) on port $port..."
      kill "$pid" 2>/dev/null || true
    else
      echo "Port $port is occupied by an unrelated process (PID $pid)." >&2
      echo "Stop it or choose another free port with BACKEND_PORT=... or FRONTEND_PORT=... ./run.sh" >&2
      return 1
    fi
  done <<< "$pids"

  for _ in {1..10}; do
    if ! lsof -nP -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    process_dir="$(lsof -nP -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    if [[ "$process_dir" == "$expected_dir" ]]; then
      echo "Force stopping Apex HRM $service process (PID $pid)..."
      kill -KILL "$pid" 2>/dev/null || true
    fi
  done < <(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u)

  if lsof -nP -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $port is still occupied; refusing to start another $service process." >&2
    return 1
  fi
}

stop_project_listener "$BACKEND_PORT" "$BACKEND_DIR" backend
stop_project_listener "$FRONTEND_PORT" "$FRONTEND_DIR" frontend

if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo "Frontend dependencies are missing. Run 'cd frontend && npm install' first." >&2
  exit 1
fi

BACKEND_PORT="$BACKEND_PORT" PORT="$BACKEND_PORT" "$BACKEND_DIR/run.sh" &
BACKEND_PID=$!
(cd "$FRONTEND_DIR" && npm run dev -- --host 0.0.0.0 --port "$FRONTEND_PORT" --strictPort) &
FRONTEND_PID=$!

cleanup() {
  trap - INT TERM EXIT
  echo "Stopping Apex HRM backend and frontend..."
  kill "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
  wait "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

echo "Starting Apex HRM backend on http://localhost:$BACKEND_PORT"
echo "Starting Apex HRM frontend on http://localhost:$FRONTEND_PORT"

while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  BACKEND_STATUS=0
  wait "$BACKEND_PID" || BACKEND_STATUS=$?
  echo "Backend stopped${BACKEND_STATUS:+ (exit $BACKEND_STATUS)}." >&2
fi
if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
  FRONTEND_STATUS=0
  wait "$FRONTEND_PID" || FRONTEND_STATUS=$?
  echo "Frontend stopped${FRONTEND_STATUS:+ (exit $FRONTEND_STATUS)}." >&2
fi
exit 1
