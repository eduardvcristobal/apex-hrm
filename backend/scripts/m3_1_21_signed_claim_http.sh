#!/usr/bin/env bash
# CI-only positive Ed25519 integration against real Spring Boot + PostgreSQL.
# Generated private key is created in ephemeral runner, never committed.
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
A="$(printf 'a%.0s' {1..64})"
B="$(printf 'b%.0s' {1..64})"
C="$(printf 'c%.0s' {1..64})"
F="$(printf 'f%.0s' {1..64})"
G="$(printf 'g%.0s' {1..64})"
H="$(printf 'h%.0s' {1..64})"
TENANT="81000000-0000-4000-8000-000000000001"
SETTLEMENT="a5000000-0000-4000-8000-000000000001"
KEY_ID="CI_PINNED_ISSUER"
DOC_HASH="$(printf 'd%.0s' {1..64})"
ROOT="/api/v1/payroll/final-pay"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
call(){
 local method="$1" token="$2" path="$3" data="${4:-}" code
 local -a args=()
 if [[ -n "$data" ]]; then args+=(--data "$data"); fi
 code="$(curl -sS -X "$method" -H "Authorization: Bearer $token" -H "Content-Type: application/json" "${args[@]}" -o "$tmp/body" -w '%{http_code}' "$BASE$path")"
 LAST_BODY="$(cat "$tmp/body")"
 LAST_STATUS="$code"
}
expect(){
 if [[ "$LAST_STATUS" != "$1" ]]; then
  echo "FAIL: $2 expected $1, got $LAST_STATUS: $LAST_BODY";exit 1;
 fi
 echo "PASS: $2 [$1]"
}
call POST "$A" "$ROOT/source-snapshots/settlements/$SETTLEMENT"
expect 201 'Capture complete synthetic payroll and DTR source'
SNAP_ID="$(echo "$LAST_BODY" | jq -r .id)"
echo "$LAST_BODY" | jq -e '.completenessStatus == "REVIEW_REQUIRED" and
  .cutoffGapDays == 0 and .compensationGapDays == 0 and .unfinalizedDtrDays == 0 and
  .payoutCertified == false' >/dev/null || { echo "$LAST_BODY";exit 1; }
call GET "$B" "$ROOT/source-reviews/snapshots/$SNAP_ID/freshness"
expect 200 'Confirm mechanically complete snapshot before review'
echo "$LAST_BODY" | jq -e '.reviewStatus == "READY_FOR_INDEPENDENT_REVIEW" and .current == true' >/dev/null || exit 1
call POST "$B" "$ROOT/source-reviews" "{\"snapshotId\":\"$SNAP_ID\",\"evidenceReference\":\"CI fully synthetic period review reference\"}"
expect 201 'Separate officer requests complete source review'
REVIEW_ID="$(echo "$LAST_BODY" | jq -r .id)"
call POST "$C" "$ROOT/source-reviews/$REVIEW_ID/approve"
expect 200 'Independent third officer accepts synthetic source completeness'
echo "$LAST_BODY" | jq -e '.status == "SOURCE_REVIEWED" and .payoutCertified == false' >/dev/null || exit 1
call POST "$F" "$ROOT/period-attestations" "{\"sourceReviewId\":\"$REVIEW_ID\",\"issuerName\":\"CI ephemeral trusted issuer\",\"documentReference\":\"CI cryptographic synthetic signed digest claim\",\"declaredDocumentSha256\":\"$DOC_HASH\"}"
expect 201 'Fourth officer registers claimed document hash'
ATTESTATION_ID="$(echo "$LAST_BODY" | jq -r .id)"
call POST "$G" "$ROOT/period-attestations/$ATTESTATION_ID/review"
expect 200 'Fifth officer internally reviews synthetic period evidence'
echo "$LAST_BODY" | jq -e '.status == "INTERNALLY_REVIEWED" and .bankConfirmed == false' >/dev/null || exit 1
printf 'APEX-HRM-DOCUMENT-DIGEST-V1\n%s\n%s\n%s\n%s\n' "$TENANT" "$ATTESTATION_ID" "$DOC_HASH" "$KEY_ID" > "$tmp/claim"
SIG="$(openssl pkeyutl -sign -rawin -inkey /tmp/m321-issuer.pem -in "$tmp/claim" | base64 -w0)"
BAD_SIG="$(printf 'A%.0s' {1..88})"
call POST "$H" "$ROOT/document-verifications" "{\"attestationId\":\"$ATTESTATION_ID\",\"issuerKeyId\":\"$KEY_ID\",\"signatureBase64\":\"$BAD_SIG\"}"
expect 409 'Reject invalid detached signature against pinned issuer key'
call POST "$C" "$ROOT/document-verifications" "{\"attestationId\":\"$ATTESTATION_ID\",\"issuerKeyId\":\"$KEY_ID\",\"signatureBase64\":\"$SIG\"}"
expect 403 'Prior source-reviewer cannot independently verify issuer signature'
call POST "$H" "$ROOT/document-verifications" "{\"attestationId\":\"$ATTESTATION_ID\",\"issuerKeyId\":\"$KEY_ID\",\"signatureBase64\":\"$SIG\"}"
expect 201 'Sixth independent actor verifies issuer Ed25519 digest claim'
echo "$LAST_BODY" | jq -e '.verificationStatus == "ISSUER_SIGNATURE_VALID_FOR_DIGEST" and
  .documentBytesVerified == false and .bankConfirmed == false and
  .payoutCertified == false and .disbursementEnabled == false' >/dev/null || { echo "$LAST_BODY";exit 1; }
call POST "$H" "$ROOT/document-verifications" "{\"attestationId\":\"$ATTESTATION_ID\",\"issuerKeyId\":\"$KEY_ID\",\"signatureBase64\":\"$SIG\"}"
expect 409 'Duplicate signed issuer claim cannot overwrite ledger'
call GET "$H" "$ROOT/release-readiness/settlements/$SETTLEMENT"
expect 200 'Even successful signed claim remains production NO-GO'
echo "$LAST_BODY" | jq -e '.issuerDigestStatus == "ISSUER_SIGNATURE_VALID_FOR_DIGEST" and
  .issuerSignatureForDigestVerified == true and .sourceComplete == true and
  .periodAttestationStatus == "INTERNALLY_REVIEWED" and
  .actualDocumentBytesVerified == false and .bankConfirmed == false and
  .statutoryFinalPayCertified == false and .payoutCertified == false and
  .disbursementEnabled == false and .status == "PRODUCTION_NO_GO"' >/dev/null || { echo "$LAST_BODY";exit 1; }
echo "PASS: end-to-end cryptographic source verification achieved with no bank or PAID state"
