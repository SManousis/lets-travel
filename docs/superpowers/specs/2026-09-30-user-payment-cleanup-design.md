# User payment cleanup design

## Scope

Complete the next payment lifecycle slice on `build-security-data-fixes`. User deletion must revoke access first, then request provider-backed payment cleanup. It must not return `204` while a method remains pending or failed. The operation must be repeatable after an interruption, and provider failure must remain visible on the payment method. Payment creation must reject a nonexistent or disabled local user before contacting a provider.

No trusted local-user to provider-customer mapping exists. Stripe and PayPal reference inspection therefore remains merchant-account verification, not proof that the provider customer belongs to the requested local user. The API and audit must state this limit; no automatic first-use binding will be inferred from a supplied `userId`.

## Design

- Payment-service exposes a service-token-protected internal user cleanup endpoint. It uses the same deletion logic as the admin method endpoint, attempts at most two methods per request to stay within the caller's timeout, and returns `204` only when no method remains. It returns `202` when work remains. A failed detach retains its method and failure code. Per-method claims and deterministic idempotency keys make retries safe.
- User-service calls the internal payment endpoint after auth revocation and local disablement. It then calls travel-service to remove participation for that user's traveler IDs, and finally deletes those traveler rows. A small persisted deletion-stage record in the users database records progress and lets repeated DELETE calls resume. The user remains disabled if cleanup is incomplete. Only completed cleanup returns `204`; incomplete work returns `202`, and unavailable dependencies return `503`.
- User-service exposes a service-token-protected internal active-user lookup. Payment-service checks this before accepting a new method. The check establishes local user existence and active state only.
- Travel-service receives an authenticated list of traveler IDs and removes all matching participation nodes in one graph transaction (or all matching local memberships in default mode). Repeating that call is safe. User-service does not remove traveler rows before travel confirms cleanup.

## Error and concurrency policy

Internal requests use bounded connection/read timeouts and carry the configured service token. Invalid internal tokens return `403`. Payment cleanup never logs provider references or credentials. A `PENDING` connect with a live 30-second claim, or another active delete claim, remains visible and yields an incomplete cleanup result. After that claim expires, cleanup may fence the connector and attempt provider detachment. A syntactically invalid reference can be removed locally because no provider request could have accepted it. Provider `404` is ambiguous and never authorizes deleting the local row. Repeated calls may redo idempotent steps; no cross-database transaction is assumed.

## Verification

Compile all modules and validate Compose manifests. Do not add or run tests under the current task instructions. Live Stripe/PayPal verification waits for sandbox credentials.
