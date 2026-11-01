#!/usr/bin/env bash
# CI-only: prove a source mutation invalidates an old hash and prevents approval requests.
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
TOKEN="$(printf 'a%.0s' {1..64})"
SETTLEMENT="89000000-0000-4000-8000-000000000001"
ROOT="/api/v1/payroll/final-pay/source-reviews"
SNAPS="/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
headers=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
# Final source-review decisions must resist direct database modification.
psql -v ON_ERROR_STOP=1 -h localhost -U hrm -d apex_hrm <<'REVIEW_SQL'
BEGIN;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
DO $verify$
DECLARE source_review_id uuid;
BEGIN
 SELECT id INTO source_review_id FROM payroll_final_pay_source_review
 WHERE tenant_id='81000000-0000-4000-8000-000000000001' AND status='REJECTED' LIMIT 1;
 IF source_review_id IS NULL THEN RAISE EXCEPTION 'Missing independent rejected source review'; END IF;
 BEGIN
  UPDATE payroll_final_pay_source_review SET status='SOURCE_REVIEWED'
   WHERE id=source_review_id;
  RAISE EXCEPTION 'Source review overwrite unexpectedly succeeded';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  DELETE FROM payroll_final_pay_source_review WHERE id=source_review_id;
  RAISE EXCEPTION 'Source review removal unexpectedly succeeded';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $verify$;
ROLLBACK;
REVIEW_SQL
echo 'PASS: decided source review resists SQL UPDATE and DELETE'

psql -v ON_ERROR_STOP=1 -h localhost -U hrm -d apex_hrm <<'SQL'
BEGIN;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
UPDATE loan SET balance=balance+100
 WHERE tenant_id='81000000-0000-4000-8000-000000000001'
   AND id='8d000000-0000-4000-8000-000000000001';
COMMIT;
SQL
old="$(curl -fsS "${headers[@]}" "$BASE$SNAPS" | jq -r '.[0].id')"
[[ "$old" != "null" && -n "$old" ]] || { echo 'Missing existing snapshot'; exit 1; }
fresh="$(curl -fsS "${headers[@]}" "$BASE$ROOT/snapshots/$old/freshness")"
echo "$fresh" | jq -e '.reviewStatus == "STALE_SOURCE" and .current == false and .disbursementEnabled == false' >/dev/null
echo 'PASS: existing source snapshot is invalidated by an authentic loan balance change'
tmp="$(mktemp)"
code="$(curl -sS -X POST "${headers[@]}" --data "{\"snapshotId\":\"$old\",\"evidenceReference\":\"CI stale source must never be approved\"}" -o "$tmp" -w '%{http_code}' "$BASE$ROOT")"
[[ "$code" == "409" ]] || { cat "$tmp"; echo "Expected stale request 409, got $code"; rm -f "$tmp";exit 1; }
rm -f "$tmp"
echo 'PASS: stale snapshot cannot be submitted for independent approval'
new="$(curl -fsS -X POST "${headers[@]}" "$BASE$SNAPS")"
newId="$(echo "$new" | jq -r .id)"
newHash="$(echo "$new" | jq -r .sourceSha256)"
[[ "$newId" != "$old" && "$newHash" != "null" ]] || { echo "$new"; exit 1; }
newFresh="$(curl -fsS "${headers[@]}" "$BASE$ROOT/snapshots/$newId/freshness")"
echo "$newFresh" | jq -e '.current == true and .latest == true and .reviewStatus == "INCOMPLETE_SOURCE" and .payoutCertified == false' >/dev/null
echo 'PASS: new snapshot changes SHA-256, remains incomplete, and still forbids payout'
