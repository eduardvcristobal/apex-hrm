# M7.3 — Financial Data Protection and Bank Account Encryption

**Baseline:** main `8d958e36c4a2dca1b03bde84c88fdef2a12b39f0`.  
**Work branch:** `feature/m7-3-financial-data-protection`.  
**Release decision:** **NO-GO until CI and a realistic data migration drill pass**.

## Implemented protection

- Flyway V59 renames the preexisting `employee_bank_account.account_no` to transition-only `account_no_legacy`, creates `account_no_ciphertext` and blocks **new plaintext inserts** with a database trigger.
- `BankAccountCipher` encrypts account numbers with AES-256-GCM, a randomized 96-bit nonce and tenant/employee authenticated associated data. A separately provisioned key and tagged versioned envelope (`b1:key-id:...`) prevent ciphertext from being safely moved to a different tenant or employee.
- The `BankAccountBackfill` application startup runner holds a table lock inside a single transaction, authenticates existing ciphertext, encrypts legacy rows, scrubs plaintext and re-encrypts values when an explicitly configured previous key is available. An exception rolls back all changes and fails startup.
- The generic employee bank-account controller was replaced with an explicit DTO-based admin API. Reads and writes return only `maskedAccountNo`; ciphertext is not serialized. Employee ownership is validated and existing records are explicitly checked against the active tenant.
- Bank-account decryption is restricted to the payroll bank-export service, which already requires payroll officer/tenant administrator with MFA under Spring Security.
- Bank-file JSON no longer exposes the full CSV content; the existing privileged CSV-download endpoint still allows plaintext bank output as an intentional, restricted finance operation. Treat its content as sensitive.
- Added encryption, tamper, key-rotation, tenant-substitution, mask, tenant-service and role/MFA negative unit tests.

## Required deployment configuration

Provision a unique 32-byte random key using `openssl rand -base64 32` and store **only in secure deployment secrets**, not in Git:

```properties
HRM_BANK_ACCOUNT_KEY=<base64-of-32-random-bytes>
HRM_BANK_ACCOUNT_KEY_ID=k1
```

Optional **rotation window** variables:

```properties
HRM_BANK_ACCOUNT_PREVIOUS_KEY=<old-base64-key>
HRM_BANK_ACCOUNT_PREVIOUS_KEY_ID=k1
HRM_BANK_ACCOUNT_KEY=<new-base64-key>
HRM_BANK_ACCOUNT_KEY_ID=k2
```

On restart, the runner verifies all ciphertext, updates stale-key records transactionally and fails on missing/mismatched keys. **Keep old key securely until verification, backups and restore/replay plans are complete.** Do not remove it solely because a request succeeded. Once rotation is verified across database and backup recovery processes, the old key can be retired via an approved operations process.

## Migration safety and backout

1. Perform encrypted database backup and a timed restore rehearsal; account exports and historical bank files remain sensitive.
2. Stop pre-V59 application writers; old binary versions will not be able to write after V59.
3. Supply the new encryption key before starting V59. The startup runner migrates and scrubs legacy plaintext in one transaction. For very large tables, evaluate lock duration and test on a production-like sanitized snapshot before deploying.
4. Confirm `SELECT count(*) FROM employee_bank_account WHERE account_no_legacy IS NOT NULL OR account_no_ciphertext IS NULL` equals zero; **never log actual account values**.
5. Check tenant A/B separation with different authorized identities, masked GET/POST/PUT responses, and privileged payroll export. Confirm account number decryption fails for switched tenant context or missing key.
6. Restore/rollback using a **pre-migration backup** if migration fails. Do not reverse ciphertext to plaintext or manually rewrite applied Flyway migrations.

**Important:** the legacy column remains in schema for recovery auditing but is set to NULL after successful backfill. A subsequent separately reviewed forward-only migration can DROP `account_no_legacy` after production evidence, including backups and replicas, confirms it is safe.

## Automated verification

`.github/workflows/m7-3-financial-security.yml` runs Maven verification, starts PostgreSQL 16/Spring Boot with an ephemeral test-only key, validates Flyway V59 and checks the old plaintext insert path is denied. Existing M3.4 workflow must also pass.

## Outstanding security gaps

The original `bank_file.content` column stores generated CSV contents and therefore may retain plaintext beneficiary account numbers at rest. JSON exposure has been disabled, but **bank export encryption/retention/purge needs separate implementation** before handling live financial data. Also pending: independent security review, full authenticated cross-tenant HTTP integration tests, historical data/backup purge, secrets management, rate limits and bank settlement certification. This PR must **not** be represented as complete production financial-data certification.
