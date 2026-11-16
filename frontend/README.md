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
PLAYWRIGHT_SLOW_MO=0 E2E_SAMPLE_PATH=/tmp/apex-full-journey-owner.json npx playwright test e2e/owner.journey.spec.cjs --headed --workers=1
```

The journey starts at signup/MFA, then maintains company details, branch, position and organization-chart department; configures effective-dated leave entitlements, leave/overtime workflows, weekly/daily pay groups and calendars; hires a named employee through the UI; completes protected profile data, document upload, compensation, department/pay-group/schedule assignments and dependent details; and maintains a schedule and holiday. It then records attendance and files leave/overtime requests in self service, processes independent approvals, runs weekly and daily payroll, completes weekly calculation/review/approval in Payroll, publishes the payslip, acknowledges it in employee self service and closes the run. It finishes with 13th-month/final-pay and Form 2316/1604-C report checks. API calls remain for the personnel-department record (chart node and department master are separate), attendance input and independent approver decisions, dependent/tax-profile maintenance, and year-end calculations/reports. Keep the sample path private: it stores the local owner login and MFA secret with owner-only file permissions. Choose a new filename for each isolated first-run workspace. The command keeps Playwright headed while disabling the configured per-action delay.
