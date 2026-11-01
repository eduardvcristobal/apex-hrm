#!/usr/bin/env bash
set -euo pipefail
expected_id="${1:?Expected key ID required}"
java -jar backend/target/apex-hrm-0.1.0-SNAPSHOT.jar > /tmp/m73-upgrade-app.log 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; tail -n 60 /tmp/m73-upgrade-app.log' EXIT
ready=false
for n in $(seq 1 90); do
  if curl --fail --silent http://localhost:8080/actuator/health >/dev/null; then ready=true; break; fi
  if ! kill -0 "$pid" 2>/dev/null; then exit 1; fi
  sleep 2
done
test "$ready" = true
psql -v ON_ERROR_STOP=1 -v expected_key_id="$expected_id" -f scripts/db/m7_3_backfill_assertions.sql
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true
trap - EXIT
