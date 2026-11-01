# Apex HRM

This repository contains the Apex HRM Spring Boot API and its React frontend.

## Projects

- [`backend/`](backend/README.md): Java 17, Spring Boot, PostgreSQL, and Flyway.
- [`frontend/`](frontend/README.md): Vite, React, and TypeScript.

Run both services together from the repository root with `./run.sh`. It checks ports 8080 and 5173, stops existing Apex HRM listeners, then starts the API and Vite frontend. Unrelated processes are left running, and the script reports a conflict instead. The backend connects to the local PostgreSQL instance configured with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

Set `BACKEND_PORT` or `FRONTEND_PORT` to use different ports. To run either project on its own, see its README.

New workspaces can sign up at the frontend and authenticate with email/username and password. Optional Google sign-in needs `GOOGLE_CLIENT_ID` in `backend/.env` and the matching `VITE_GOOGLE_CLIENT_ID` in `frontend/.env`. Platform administrator Google access is additionally limited to `PLATFORM_ADMIN_EMAILS`. See [backend configuration](backend/README.md) for MFA, email, and backup setup.
