# M7.3.31 — Secure Payroll Bank-Export Storage & Retention

**Stacked development branch:** `feature/m7-3-31-secure-bank-export-retention`, based on the head of **draft PR #9** (`feature/m7-3-financial-data-protection`). No change is made to the existing PR #9 branch. Retarget this follow-up PR to `main` **only after PR #9 has been merged**, and then require all checks again.

## Threat and change

Previous `bank_file.content` retained complete beneficiary CSV (including account numbers) as plaintext in PostgreSQL, despite its omission from JSON. CSV was used by both MFA-guarded downloads and bank result reconciliation; encrypting only downloads would break reconciliation.

- **Flyway V60** renames the historical column to `content_legacy` and introduces `content_ciphertext`, `content_sha256`, `purged_at` and `legal_hold`. A trigger rejects new plaintext export inserts and modifications.
- `BankExportCipher` uses independent, externally managed AES-256-GCM keys, randomized 96-bit IVs, an authenticated context of tenant ID + payroll run ID + filename, and version-tagged envelopes.
- `BankExportBackfill` locks export rows in a single transaction on startup, decrypts and validates any existing ciphertext, verifies digests, encrypts historical plaintext, clears `content_legacy`, and rotates old-key ciphertext. Failures roll back and abort startup.
- New bank-file generation encrypts CSV **before saving**. Authenticated payroll download and bank-response reconciliation decrypt and verify the SHA-256 digest. Encrypted contents are never emitted by generic JSON endpoints.
- A manual retention service supports audit-recorded legal holds and irreversible ciphertext purge. No scheduled automatic purge is enabled.

## Secret configuration

Do not reuse the employee bank-account key introduced in PR #9. Provision a separately managed 32-byte key:

```properties
HRM_BANK_EXPORT_KEY=<base64-of-32-secure-random-bytes>
HRM_BANK_EXPORT_KEY_ID=k1
HRM_BANK_EXPORT_RETENTION_DAYS=3650
```

During rotation, configure `HRM_BANK_EXPORT_PREVIOUS_KEY` and `HRM_BANK_EXPORT_PREVIOUS_KEY_ID` alongside a new current key and ID, then restart with a controlled maintenance window. Verify all live rows and encrypted backups before retiring the old key. Do not put keys in source control or export contents in logs.

## Retention policy and endpoints

Default minimum retention is 3650 days. Configuration below 365 days is rejected at startup. This is a **conservative configurable technical default, not a legal assessment** of mandatory retention periods.

- `GET /api/payroll/bank-files/{id}/retention`: read retention metadata and digest
- `POST /api/payroll/bank-files/{id}/legal-hold`: JSON `{"hold":true,"reason":"..."}`
- `POST /api/payroll/bank-files/{id}/purge`: JSON `{"reason":"..."}`

All three require `ROLE_TENANT_ADMIN` and `ROLE_MFA_VERIFIED`, reinforced at the controller. Purge is denied unless payroll is `PAID`, full employee-level bank responses were imported, payroll settlement summary is `RECONCILED`, the file passed the minimum age, and legal hold is absent. An authenticated reason is audited. The CSV ciphertext is set to NULL; the digest and purge timestamp remain for evidence. Purged files cannot be downloaded or imported again.

**Important:** deletion from the live database is not deletion from existing backups, WAL, snapshots, downstream downloads, external bank records or logs. Backup retention and approved destruction procedures remain necessary. There is no dual-person release for purge yet; a future policy requirement may impose it. Legal holds and internal finance rules override the configured retention period.

## Migration drill

Run only on a disposable, empty PostgreSQL 16 instance:

1. Apply real V1–V59 Flyway migrations and seed `scripts/db/m7_3_31_v59_export_fixture.sql`.
2. Run Maven verify and start the V60 application with `HRM_BANK_EXPORT_KEY`.
3. Check `scripts/db/m7_3_31_export_certification.sql`: plaintext legacy NULL, digest present, ciphertext envelope with active key ID, attempts to insert new legacy plaintext rejected.
4. Restart with a new export key and the previous key configuration; verify the active envelope is now k2.
5. Verify tenant substitution and ciphertext tampering fail in `BankExportCipherTest`, and that retention/hold/settlement gates fail closed in `BankExportRetentionServiceTest`.

CI: `.github/workflows/m7-3-31-bank-export.yml` exercises the synthetic V59→V60 upgrade, key rotation, Maven tests and retention unit tests.

## Additional release blockers

- Live two-tenant, multi-role HTTP E2E including payroll import/export/download and actual role/MFA authorization is not certified by unit tests alone.
- Retention needs formal HR, finance, legal, privacy and security sign-off; confirm requirements for Philippine records and current regulations externally before configuring real production deletions.
- Database backup/restore rehearsal should include historical encrypted exports and demonstrate operational key availability.
- Protect and classify downloaded plaintext CSV files, emailed attachments, external bank files and bank-result evidence; clearing the database field does not protect copies.
- A pre-migration backup still holds plaintext until its retention expiry or secure destruction. Do not rewrite or rename migrations already applied to shared databases.

**Production decision: NO-GO** pending the above gates, even if CI passes.
