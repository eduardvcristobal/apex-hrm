# Apex HRM frontend

Vite + React + TypeScript workspace app with email/username login, optional Google OAuth, MFA/account security, invitations, company organization, workspace audit/billing, and platform-admin operations.

```sh
npm install
npm run dev
```

The Vite dev server proxies `/api` requests to the Spring backend. Copy `.env.example` to `.env` and set `VITE_GOOGLE_CLIENT_ID` to enable Google sign-in. The source prototypes remain available in `public/prototypes/` as visual references.
