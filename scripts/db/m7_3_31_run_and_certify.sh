#!/usr/bin/env bash
# Start packaged application to apply V60, complete encrypted backfill/rotation,
# then assert only ciphertext remains. Use disposable CI DB.
set -euo pipefail
key_id="${1:?Expected current export key ID required}"
java -jar backend/target/apex-hrm-0.1.0-SNAPSHOT.jar >/tmp/m7331-startup.log 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
trap cleanup EXIT
ready=false
for attempt in $(seq 1 90); do
  if curl --fail --silent localhost:8080/actuator/health >/dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    tail -n 80 /tmp/m7331-startup.log
    exit 1
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  tail -n 80 /tmp/m7331-startup.log
  exit 1
fi
psql -v ON_ERROR_STOP=1 -v key_id="$key_id" -f scripts/db/m7_3_31_export_certification.sql
