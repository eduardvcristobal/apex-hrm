# M3.1.21 — Pinned Issuer Signature Validation & Multi-Attestor Release Readiness

**Decision: SOFTWARE CONTROLS IMPLEMENTED; PRODUCTION BANK DISBURSEMENT NO-GO.**

## Delivered

### M3.1.21.1 — Append-only issuer-digest verification ledger (Flyway V60)

Added `payroll_final_pay_document_verification` with forced tenant RLS, tenant-specific FKs to M3.1.20 attestations and authorized identity, one accepted claim per attestation, and a PostgreSQL UPDATE/DELETE blocker. Stored evidence includes the issuer key identifier, the signed document fingerprint, detached Ed25519 signature, verification actor and timestamp. Decisions are audit-logged.

### M3.1.21.2 — Pinned Ed25519 verification

**`POST /api/v1/payroll/final-pay/document-verifications`** accepts only an existing attestation ID, a reference to an already-configured trusted issuer key, and a detached signature. The backend **never accepts a public key from an API request**.

For a trusted issuer, the deployment must provision BOTH properties (for example, environment-specific Spring configuration, not the repository):

- `hrm.financial.documents.issuer-key-id`: a trusted issuer-key alias.
- `hrm.financial.documents.issuer-public-key-base64`: Base64 DER SubjectPublicKeyInfo of the corresponding Ed25519 public key. **Never upload or commit the signing private key.**

**No trusted key is configured by default.** Requests fail closed (409) rather than using a user-supplied authority. Only keys separately accepted and controlled by operations should be pinned. Current implementation supports one pinned key only; production key rotation, revocation, multiple banks and certificate governance remain outstanding.

The canonical UTF-8 message being signed is:

```text
APEX-HRM-DOCUMENT-DIGEST-V1
<tenant-uuid>
<attestation-uuid>
<lowercase-document-sha256>
<trusted-issuer-key-id>
```

A trailing newline follows the last line. The verifier checks that signature against the configured public key, the recorded attestation hash and tenant/attestation identity. It requires previous source and period reviews to be complete/current and verifies reviewer independence from snapshot capturer, source requester/reviewer and period requester/reviewer.

A valid signature proves only that the key holder attested **the claimed fingerprint**. It **does not** prove the server obtained the actual document bytes and hashed them itself, that the issuer is a bank, that an actual bank transfer occurred, or that a recipient bank account belongs to the employee.

**`GET /api/v1/payroll/final-pay/document-verifications?settlementId=...`** lists only the tenant's recorded valid claims. The response always contains `documentBytesVerified=false`, `bankConfirmed=false`, `payoutCertified=false`, `disbursementEnabled=false`.

### M3.1.21.3 — Multi-attestor release-readiness preflight

**`GET /api/v1/payroll/final-pay/release-readiness/settlements/{settlementId}`** joins the latest immutable source snapshot, independent source review, independent period attestation, pinned digest signature status and existing manual payment/evidence review. It also recalculates live snapshot freshness.

It returns explicit blockers for missing source completeness, outstanding attestors, missing trusted signature, independently fetched and hashed document bytes, bank account ownership, authoritative bank statement settlement, official final-pay/withholding certification and legal/government signoff.

**The status is always `PRODUCTION_NO_GO`, even when all currently supported internal steps and signature validation succeed. No action posts `PAID` or issues a bank payment.**

### M3.1.21.4 — Personnel UI and CI

- Personnel final-pay review now shows snapshot/source review, period attestation, issuer signature digest status and the outstanding production release blockers.
- Backend unit tests create a real ephemeral Ed25519 key pair and assert valid signature and rejection of altered tenant, attestation digest, alias or malformed signature.
- Authenticated PostgreSQL/HTTP regression requires signed proof to fail closed when a provider key is not trusted, rejects other-tenant and MFA-less views, and preserves payout NO-GO.
- A separate synthetic **positive** E2E fixture exercises complete sample payroll cutoff/compensation/DTR coverage, immutable source capture, independent source approval, independent period acceptance and a detached Ed25519 claim generated using a temporary CI-only key. Tests verify negative/positive signatures, separation of verification actors, duplicate protection and NO-GO after successful cryptographic validation.
- GitHub Actions checks Flyway V60, tenant RLS, immutable verification records, Maven tests, frontend build and real Chromium desktop/mobile finance navigation. Ephemeral key material is generated on the CI runner, never committed to GitHub.
- See the latest green branch-head run in PR #1 for verified evidence.

## Explicit limits and production blockers

1. **No independent external document retrieval.** The backend verifies a signature over the user-entered digest only; it does not fetch a trusted bank statement and hash the file bytes itself.
2. **Issuer trust setup is manual**. A public key configured in server properties must be validated outside the app via actual provider key exchange. The fixture keys in CI are fictional and not evidence of an actual provider integration.
3. **No bank confirmations, bank account ownership, statement reconciliation, reversals or settlement finality**. Previously recorded bank evidence remains human entered, not bank confirmed.
4. **No statutory final-pay approval**: complete legal entitlement (13th-month, DTR, leave, separation, loan deductions, BIR year-end tax, SSS/PhilHealth/Pag-IBIG compliance) needs separate official certification and multi-person HR/finance/operations acceptance.
5. **No externally anchored immutable log**: database triggers and hashes do not resist a privileged DBA without independent external signed/WORM copies.
6. **No production release**: tests include synthetic records only. Never enable bank transfer or `PAID` state based on this internal evidence trail.

**Production: NO-GO. PR #1 must remain DRAFT and UNMERGED.**

## Recommended next milestone

**M3.1.22 — Trusted Document Byte Retrieval, Issuer Key Rotation and Authoritative Bank Reconciliation Adapter.** Implement external trusted storage ingestion, re-hash real received bytes, key provenance/rotation/revocation, verified bank-account and transfer-response matching, negative-path security/DR tests, with the financial release gate still disabled until actual provider certification and human approvals.
