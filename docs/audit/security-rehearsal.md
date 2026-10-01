# Security rehearsal

1. Obtain an admin token through `/api/auth/login`.
2. Call `/api/users` without credentials and record `401`.
3. Call `/api/users` with a tampered token and record `401`.
4. Confirm the admin token returns `200` and that logout rejects it afterward.
5. Inspect logs for the request ID and confirm passwords, tokens, and payment references are absent.

Run this against the loopback gateway only; internal service and management ports are not published by the deployment Compose file.
