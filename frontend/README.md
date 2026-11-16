# Apex HRM frontend

Vite + React + TypeScript workspace app with email/username login, optional Google OAuth, MFA/account security, invitations, company organization, workspace audit/billing, and platform-admin operations.

```sh
npm install
npm run dev
```

The Vite dev server proxies `/api` requests to the Spring backend. Copy `.env.example` to `.env` and set `VITE_GOOGLE_CLIENT_ID` to enable Google sign-in. The source prototypes remain available in `public/prototypes/` as visual references.

## Complete owner-to-payroll journey

Start the backend and database, then run the owner journey against a new local tenant. Set a new sample path to force workspace signup instead of reusing the saved owner account:

```sh
E2E_SAMPLE_PATH=/tmp/apex-full-journey-owner.json npx playwright test e2e/owner.journey.spec.cjs --headed --workers=1
```

The journey enrolls owner MFA, sets up organization/pay groups/holiday and an effective-dated leave benefit, hires the primary employee through the UI, then runs attendance, independent leave/overtime approvals, weekly and daily payroll, employee payslip acknowledgement, 13th-month and final pay, and Form 2316 / 1604-C report checks. Keep the sample path private: it stores the local owner login and MFA secret with owner-only file permissions. Choose a new filename for each isolated first-run workspace. To reduce browser pacing while staying headed, prefix the command with `PLAYWRIGHT_SLOW_MO=0`.
