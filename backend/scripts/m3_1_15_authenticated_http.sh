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
http GET "$B" "/api/v1/payroll/final-pay/source-ledger/settlements/$SETTLEMENT"
expect 200 'Source ledger fails closed before verified Personnel-to-Payroll identity'
[[ "$(echo "$LAST_BODY" | jq -r .payrollStatus)" == "IDENTITY_UNVERIFIED" ]] || exit 1
[[ "$(echo "$LAST_BODY" | jq -r .disbursementEnabled)" == "false" ]] || exit 1
http POST "$A" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 404 'Snapshot capture requires approved cross-model identity'
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
http GET "$A" "/api/v1/payroll/final-pay/source-ledger/settlements/$SETTLEMENT"
expect 200 'Source ledger inspects approved employee identity with payroll, loan and leave sources'
[[ "$(echo "$LAST_BODY" | jq -r .payrollStatus)" == "ANNUAL_COVERAGE_AND_ACCRUAL_REVIEW_REQUIRED" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .lockedPayrollLines)" == "3" ]] || { echo "$LAST_BODY"; exit 1; }
echo "$LAST_BODY" | jq -e '.lockedBasicPay == 14400' > /dev/null || { echo "$LAST_BODY"; exit 1; }
echo "$LAST_BODY" | jq -e '.simpleThirteenthAccrualCandidate == 1200' > /dev/null || { echo "$LAST_BODY"; exit 1; }
echo "$LAST_BODY" | jq -e '.residualThirteenthCandidate == 400' > /dev/null || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .thirteenthAccrualStatus)" == "ACCRUAL_CANDIDATE_REQUIRES_CUTOFF_CERTIFICATION" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .withholdingStatus)" == "ANNUAL_TAX_RECOMPUTATION_REQUIRED" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .dtrStatus)" == "DTR_UNFINALIZED" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .openDtrDays)" == "1" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .absentDtrDays)" == "1" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .loanStatus)" == "LOAN_BALANCE_MISMATCH" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .outstandingActiveLoans)" == "700.0000" || "$(echo "$LAST_BODY" | jq -r .outstandingActiveLoans)" == "700.0" || "$(echo "$LAST_BODY" | jq -r .outstandingActiveLoans)" == "700.00" || "$(echo "$LAST_BODY" | jq -r .outstandingActiveLoans)" == "700" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .leaveStatus)" == "CONVERSION_RATE_AND_ELIGIBILITY_REVIEW_REQUIRED" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .convertibleLeaveDays)" != "0" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .certificationStatus)" == "REVIEW_REQUIRED" ]] || exit 1
[[ "$(echo "$LAST_BODY" | jq -r .disbursementEnabled)" == "false" ]] || exit 1
http GET "$D" "/api/v1/payroll/final-pay/source-ledger/settlements/$SETTLEMENT"
expect 404 'Source ledger is tenant isolated'
http POST "$A" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 201 'Capture immutable financial-source snapshot'
snapshotId="$(echo "$LAST_BODY" | jq -r .id)"
snapshotHash="$(echo "$LAST_BODY" | jq -r .sourceSha256)"
[[ "$snapshotHash" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid snapshot digest"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .completenessStatus)" == "INCOMPLETE_SOURCE" ]] || { echo "$LAST_BODY";exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .payoutCertified)" == "false" ]] || exit 1
[[ "$(echo "$LAST_BODY" | jq -r .disbursementEnabled)" == "false" ]] || exit 1
echo "$LAST_BODY" | jq -e '.cutoffGapDays > 0 and .compensationGapDays > 0 and .unfinalizedDtrDays == 1' > /dev/null || { echo "$LAST_BODY";exit 1; }
echo "$LAST_BODY" | jq -e '.evidence.payrollLines | length == 3' > /dev/null || { echo "$LAST_BODY";exit 1; }
http GET "$B" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 200 'Read previously captured financial source lineage'
[[ "$(echo "$LAST_BODY" | jq -r '.[0].sourceSha256')" == "$snapshotHash" ]] || exit 1
http POST "$A" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 409 'Reject duplicate source snapshot capture'
http GET "$D" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 404 'Another tenant cannot see snapshot'
http POST "$D" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 404 'Another tenant cannot capture snapshot'
http GET "$E" "/api/v1/payroll/final-pay/source-snapshots/settlements/$SETTLEMENT"
expect 403 'MFA-less officer cannot access snapshot'
# M3.1.19: two-person source review MUST NEVER turn incomplete cutoffs into payout.
SREV="/api/v1/payroll/final-pay/source-reviews"
http GET "$B" "$SREV/snapshots/$snapshotId/freshness"
expect 200 'Snapshot source remains fresh while records unchanged'
[[ "$(echo "$LAST_BODY" | jq -r .reviewStatus)" == "INCOMPLETE_SOURCE" ]] || { echo "$LAST_BODY"; exit 1; }
[[ "$(echo "$LAST_BODY" | jq -r .current)" == "true" ]] || exit 1
http POST "$A" "$SREV" "{\"snapshotId\":\"$snapshotId\",\"evidenceReference\":\"CI reviewed source and period deficiencies\"}"
expect 201 'Create independent completeness review request'
reviewId="$(echo "$LAST_BODY" | jq -r .id)"
[[ "$(echo "$LAST_BODY" | jq -r .payoutCertified)" == "false" ]] || exit 1
http POST "$A" "$SREV" "{\"snapshotId\":\"$snapshotId\",\"evidenceReference\":\"Duplicated review must be refused\"}"
expect 409 'Duplicate source review rejected'
http POST "$A" "$SREV/$reviewId/approve"
expect 403 'Source review requester cannot self-approve'
http POST "$D" "$SREV/$reviewId/approve"
expect 404 'Other tenant cannot approve source review'
http POST "$B" "$SREV/$reviewId/approve"
expect 409 'Incomplete payroll and compensation periods prevent source approval'
http POST "$B" "$SREV/$reviewId/reject"
expect 200 'Independent reviewer rejects incomplete source evidence'
[[ "$(echo "$LAST_BODY" | jq -r .status)" == "REJECTED" ]] || exit 1
http POST "$C" "$SREV/$reviewId/reject"
expect 409 'Final source review decision is immutable'
http GET "$D" "$SREV?settlementId=$SETTLEMENT"
expect 404 'Other tenant cannot list source reviews'
http GET "$E" "$SREV?settlementId=$SETTLEMENT"
expect 403 'Unverified MFA cannot access source reviews'
http GET "$A" "$SREV?settlementId=$SETTLEMENT"
expect 200 'Read recorded independent source decision'
[[ "$(echo "$LAST_BODY" | jq -r '.[0].status')" == "REJECTED" ]] || exit 1

# M3.1.20: claimed external-document provenance is NEVER authoritative bank confirmation.
ATTEST="/api/v1/payroll/final-pay/period-attestations"
DOC_SHA="$(printf 'f%.0s' {1..64})"
document="{\"sourceReviewId\":\"$reviewId\",\"issuerName\":\"CI synthetic finance office\",\"documentReference\":\"CI payroll period closure review paper 2026\",\"declaredDocumentSha256\":\"$DOC_SHA\"}"
http POST "$A" "$ATTEST" "{\"sourceReviewId\":\"$reviewId\",\"issuerName\":\"CI\",\"documentReference\":\"too short\",\"declaredDocumentSha256\":\"bad\"}"
expect 400 'Malformed provenance cannot be accepted'
http POST "$A" "$ATTEST" "$document"
expect 201 'Record declared external document provenance without independent verification'
attestation="$(echo "$LAST_BODY" | jq -r .id)"
[[ "$attestation" != "null" ]] || exit 1
echo "$LAST_BODY" | jq -e '.status == "PENDING" and .provenanceStatus == "USER_DECLARED_UNVERIFIED" and .issuerIndependentlyVerified == false and .bankConfirmed == false and .payoutCertified == false and .disbursementEnabled == false' > /dev/null || { echo "$LAST_BODY";exit 1; }
http POST "$A" "$ATTEST" "$document"
expect 409 'Duplicate period closure attestation blocked'
http POST "$A" "$ATTEST/$attestation/review"
expect 403 'Requesting source capturer cannot review attestation'
http POST "$B" "$ATTEST/$attestation/review"
expect 403 'Source reviewer cannot attest their own completeness review'
http POST "$D" "$ATTEST/$attestation/review"
expect 404 'Other tenant cannot approve period attestation'
http POST "$C" "$ATTEST/$attestation/review"
expect 409 'Rejected/incomplete source prevents positive period closure review'
http POST "$C" "$ATTEST/$attestation/reject"
expect 200 'Separate finance officer rejects incomplete period evidence'
[[ "$(echo "$LAST_BODY" | jq -r .status)" == "REJECTED" ]] || exit 1
http POST "$C" "$ATTEST/$attestation/reject"
expect 409 'Immutable period attestation decision cannot be repeated'
http GET "$D" "$ATTEST?settlementId=$SETTLEMENT"
expect 404 'Tenant isolation for attestation history'
http GET "$E" "$ATTEST?settlementId=$SETTLEMENT"
expect 403 'MFA required for attestation history'
http GET "$B" "$ATTEST?settlementId=$SETTLEMENT"
expect 200 'Internal attestation history available to authorized officer'
echo "$LAST_BODY" | jq -e '.[0].status == "REJECTED" and .[0].bankConfirmed == false' > /dev/null || exit 1

# M3.1.21: cryptographic claim proof cannot be asserted by untrusted user inputs.
VERIFY="/api/v1/payroll/final-pay/document-verifications"
READINESS="/api/v1/payroll/final-pay/release-readiness/settlements/$SETTLEMENT"
http GET "$B" "$READINESS"
expect 200 'Read multi-attestor production readiness blockers'
echo "$LAST_BODY" | jq -e '.status == "PRODUCTION_NO_GO" and
  .sourceReviewStatus == "REJECTED" and .periodAttestationStatus == "REJECTED" and
  .issuerDigestStatus == "NOT_VERIFIED" and .bankConfirmed == false and
  .actualDocumentBytesVerified == false and .payoutCertified == false and
  .disbursementEnabled == false and (.blockingReasons | length) >= 7' > /dev/null || { echo "$LAST_BODY"; exit 1; }
http GET "$D" "$READINESS"
expect 404 'Readiness cross tenant is hidden'
http GET "$E" "$READINESS"
expect 403 'Readiness requires MFA'
http GET "$B" "$VERIFY?settlementId=$SETTLEMENT"
expect 200 'Read trusted signature verification history'
echo "$LAST_BODY" | jq -e 'length == 0' > /dev/null || exit 1
http GET "$D" "$VERIFY?settlementId=$SETTLEMENT"
expect 404 'Cross tenant signature records forbidden'
http GET "$E" "$VERIFY?settlementId=$SETTLEMENT"
expect 403 'No MFA cannot view signature records'
http POST "$C" "$VERIFY" "{\"attestationId\":\"$attestation\",\"issuerKeyId\":\"CI_FAKE_KEY\",\"signatureBase64\":\"bad\"}"
expect 400 'Malformed detached proof rejected'
FAKE_SIG="$(printf 'A%.0s' {1..90})"
http POST "$C" "$VERIFY" "{\"attestationId\":\"$attestation\",\"issuerKeyId\":\"CI_FAKE_KEY\",\"signatureBase64\":\"$FAKE_SIG\"}"
expect 409 'Without deployment-pinned issuer key, proof cannot become verified'

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
