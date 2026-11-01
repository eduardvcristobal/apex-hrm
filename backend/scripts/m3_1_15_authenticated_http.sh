#!/usr/bin/env bash
# CI-only authenticated Spring Boot/PostgreSQL financial API contract regression.
# Five synthetic principals; no real banking system, real payroll or production data.
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
A="$(printf 'a%.0s' {1..64})"
B="$(printf 'b%.0s' {1..64})"
C="$(printf 'c%.0s' {1..64})"
D="$(printf 'd%.0s' {1..64})"
E="$(printf 'e%.0s' {1..64})"
PERSONNEL="84000000-0000-4000-8000-000000000001"
PAYROLL="85000000-0000-4000-8000-000000000001"
SETTLEMENT="89000000-0000-4000-8000-000000000001"
OTHER_PERSONNEL="84000000-0000-4000-8000-000000000002"
I="/api/v1/payroll/identity-links"
P="/api/v1/payroll/final-pay/payment-authorizations"
V="/api/v1/payroll/final-pay/bank-evidence"
R="/api/v1/payroll/final-pay/reconciliation/settlements/$SETTLEMENT"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# http method token path [json] => status in LAST_STATUS and body in LAST_BODY.
http() {
  local method="$1" token="$2" route="$3" body="${4:-}" target="$tmp/current"
  local -a headers=(-H 'Content-Type: application/json')
  [[ "$token" == "none" ]] || headers+=(-H "Authorization: Bearer $token")
  local -a args=()
  if [[ -n "$body" ]]; then args+=(--data "$body"); fi
  LAST_STATUS="$(curl -sS -X "$method" "${headers[@]}" "${args[@]}" -o "$target" -w '%{http_code}' "$BASE$route")"
  LAST_BODY="$(cat "$target")"
}
expect() {
  local want="$1" context="$2"
  if [[ "$LAST_STATUS" != "$want" ]]; then
    echo "$context: expected HTTP $want, got $LAST_STATUS" >&2
    echo "$LAST_BODY" | head -c 1000 >&2
    exit 1
  fi
  echo "PASS: $context [$want]"
}

http GET none "$R"; [[ "$LAST_STATUS" == "401" || "$LAST_STATUS" == "403" ]] || { echo "anonymous financial read allowed";exit 1; }
http GET "$E" "$R"; expect 403 'Payroll officer without verified MFA denied'
http GET "$D" "$R"; expect 404 'Cross-tenant settlement hidden'
http GET "$D" "$I?personnelEmployeeId=$PERSONNEL"; expect 200 'Cross-tenant list returns empty'
[[ "$(echo "$LAST_BODY" | jq length)" == "0" ]] || { echo "Tenant B read tenant A identities";exit 1; }
http GET "$B" "$R"; expect 200 'MFA payroll officer can read preflight'
[[ "$(echo "$LAST_BODY" | jq -r .controlStatus)" == "PAYMENT_NOT_PREPARED" ]] || exit 1
[[ "$(echo "$LAST_BODY" | jq -r .bankConfirmed)" == "false" ]] || exit 1
[[ "$(echo "$LAST_BODY" | jq -r .disbursementEnabled)" == "false" ]] || exit 1

http POST "$A" "$I" "{\"personnelEmployeeId\":\"$PERSONNEL\",\"payrollEmployeeId\":\"$PAYROLL\",\"evidenceReference\":\"CI verified personnel and payroll masterfile\"}"
expect 201 'Identity mapping requested'
identity="$(echo "$LAST_BODY"|jq -r .id)"
[[ "$identity" != "null" ]] || exit 1
http POST "$A" "$I" "{\"personnelEmployeeId\":\"$PERSONNEL\",\"payrollEmployeeId\":\"$PAYROLL\",\"evidenceReference\":\"Duplicate identity mapping must be denied\"}"
expect 409 'Duplicate identity mapping blocked'
http POST "$A" "$I/$identity/approve"; expect 403 'Identity requester cannot approve own request'
http POST "$D" "$I/$identity/approve"; expect 404 'Tenant B cannot approve tenant A identity'

# Exercise two independently authenticated approvers against one locked identity row.
curl -sS -X POST -H "Authorization: Bearer $B" "$BASE$I/$identity/approve" -o "$tmp/ident_b" -w '%{http_code}' > "$tmp/ident_b.code" &
p1=$!
curl -sS -X POST -H "Authorization: Bearer $C" "$BASE$I/$identity/approve" -o "$tmp/ident_c" -w '%{http_code}' > "$tmp/ident_c.code" &
p2=$!
wait "$p1";wait "$p2"
sorted="$(printf '%s\n%s\n' "$(cat "$tmp/ident_b.code")" "$(cat "$tmp/ident_c.code")" | sort | paste -sd, -)"
[[ "$sorted" == "200,409" ]] || { echo "Concurrent identity decisions returned $sorted";cat "$tmp/ident_b" "$tmp/ident_c";exit 1; }
echo 'PASS: racing identity approvers produce exactly one successful decision'
http GET "$A" "$P/eligible-accounts?personnelEmployeeId=$PERSONNEL"; expect 200 'Masked primary bank account available'
account="$(echo "$LAST_BODY"|jq -r '.[0].id')"
[[ "$account" != "null" ]] || exit 1
if echo "$LAST_BODY" | grep -q '00000001234'; then echo 'Full bank account leaked';exit 1;fi

http POST "$A" "$P" "{\"settlementId\":\"$SETTLEMENT\",\"bankAccountId\":\"$account\",\"sourceReconciliationReference\":\"CI signed payroll and bank identity reconciliation\"}"
expect 201 'Payment instruction prepared'
payment="$(echo "$LAST_BODY"|jq -r .id)"
http POST "$A" "$P" "{\"settlementId\":\"$SETTLEMENT\",\"bankAccountId\":\"$account\",\"sourceReconciliationReference\":\"CI duplicate payment authorization is rejected\"}"
expect 409 'Duplicate payment preparation blocked'
http POST "$A" "$P/$payment/authorize";expect 403 'Payment preparer cannot authorize own instruction'
http POST "$D" "$P/$payment/authorize";expect 404 'Other tenant cannot authorize payment'

curl -sS -X POST -H "Authorization: Bearer $B" "$BASE$P/$payment/authorize" -o "$tmp/pay_b" -w '%{http_code}' > "$tmp/pay_b.code" &
p1=$!
curl -sS -X POST -H "Authorization: Bearer $C" "$BASE$P/$payment/authorize" -o "$tmp/pay_c" -w '%{http_code}' > "$tmp/pay_c.code" &
p2=$!
wait "$p1";wait "$p2"
sorted="$(printf '%s\n%s\n' "$(cat "$tmp/pay_b.code")" "$(cat "$tmp/pay_c.code")" | sort | paste -sd, -)"
[[ "$sorted" == "200,409" ]] || { echo "Concurrent payment decisions returned $sorted";cat "$tmp/pay_b" "$tmp/pay_c";exit 1; }
echo 'PASS: racing payment authorizers produce exactly one successful authorization'

http GET "$A" "$P?settlementId=$SETTLEMENT"; expect 200 'Read settled payment authorization'
authorizer="$(echo "$LAST_BODY"|jq -r '.[0].authorizedBy')"
userB="83000000-0000-4000-8000-000000000002"
if [[ "$authorizer" == "$userB" ]];then reviewer="$C"; rejectedReviewer="$B";else reviewer="$B";rejectedReviewer="$C";fi
http GET "$A" "$R"; expect 200 'Payment preflight remains blocked pending external evidence'
[[ "$(echo "$LAST_BODY"|jq -r .controlStatus)" == "BANK_EVIDENCE_MISSING" ]] || exit 1

bad="{\"paymentAuthorizationId\":\"$payment\",\"bankCode\":\"DEMO\",\"bankReference\":\"BAD-AMOUNT\",\"bankValueDate\":\"2026-10-01\",\"evidenceDocumentReference\":\"CI received written bank proof\",\"reportedAmount\":11999}"
http POST "$A" "$V" "$bad";expect 409 'Mismatched reported bank amount rejected'
good="{\"paymentAuthorizationId\":\"$payment\",\"bankCode\":\"DEMO\",\"bankReference\":\"CI-M315-REF\",\"bankValueDate\":\"2026-10-01\",\"evidenceDocumentReference\":\"CI fictional bank evidence document\",\"reportedAmount\":12000}"
http POST "$A" "$V" "$good";expect 201 'Synthetic external bank evidence recorded'
evidence="$(echo "$LAST_BODY"|jq -r .id)"
http POST "$A" "$V" "$good";expect 409 'Duplicate external bank evidence blocked'
http POST "$A" "$V/$evidence/review";expect 403 'Recorder and payment preparer cannot approve evidence'
http POST "$rejectedReviewer" "$V/$evidence/review";expect 403 'Payment authorizer cannot review evidence'
http POST "$reviewer" "$V/$evidence/review";expect 200 'Independent bank evidence reviewer accepted'
http POST "$reviewer" "$V/$evidence/review";expect 409 'Repeated bank evidence decision blocked'
http GET "$B" "$R";expect 200 'Read reconciled internal preflight'
[[ "$(echo "$LAST_BODY"|jq -r .controlStatus)" == "MANUAL_EVIDENCE_REVIEWED" ]] || { echo "$LAST_BODY";exit 1; }
[[ "$(echo "$LAST_BODY"|jq -r .internallyConsistent)" == "true" ]] || exit 1
[[ "$(echo "$LAST_BODY"|jq -r .bankConfirmed)" == "false" ]] || exit 1
[[ "$(echo "$LAST_BODY"|jq -r .disbursementEnabled)" == "false" ]] || exit 1
http GET "$D" "$R";expect 404 'Final reconciliation remains tenant isolated'
echo 'PASS: authenticated finance E2E complete; no bank transmission or PAID transition'
