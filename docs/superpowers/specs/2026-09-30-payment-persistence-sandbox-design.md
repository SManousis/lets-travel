# Durable Payment Records and Sandbox Reference Verification

## Scope

Make PostgreSQL authoritative for gateway and payment-method records in the `infra` profile. Replace the adapters' unconditional `ACTIVE` result with Stripe and PayPal sandbox API calls. Retain the current synchronous HTTP response shape for this package; the richer asynchronous operation contract in `docs/api/payments.yaml` remains a separate gap and must not be described as complete.

The default profile uses an in-memory repository so local startup remains independent of PostgreSQL. It still uses the real provider adapters for method creation and deletion, so missing credentials cannot create a fake active method.

## Storage and lifecycle

Add a payment migration for gateways, method-to-gateway references, provider reference uniqueness, safe display metadata, and method lifecycle state. The existing V1 table can contain rows without a gateway ID; preserve those rows and reject reads as `LEGACY_METHOD_REQUIRES_GATEWAY` until an operator reconciles them. Document the SQL inspection and reconciliation procedure. Every `infra` read and write uses PostgreSQL directly, with no process-local cache.

Gateway creation and update validate the provider (`STRIPE` or `PAYPAL`), preserve creation time, and keep the provider immutable as the published contract requires. A gateway cannot be deleted while any method refers to it. Method creation persists `PENDING` before the provider request, then records `ACTIVE` with safe metadata or `FAILED` with a safe code. Provider errors never return a false `ACTIVE` status. The same idempotency key and same input can retry a failed create; reuse with different input is a conflict. Method label edits remain local. A 30-second claim window keeps concurrent retries from performing the same provider work while allowing recovery after a crashed request. Deletion marks `DELETION_PENDING` before contacting the provider, removes the local row only after provider success, and retains `FAILED` for an operator retry when provider cleanup fails. A provider `404` is ambiguous across credential/account changes and does not prove cleanup. A failed method still blocks gateway deletion until reconciled.

## Provider calls

Stripe accepts only a `pm_...` PaymentMethod reference. With a sandbox secret key, it retrieves the PaymentMethod, checks the returned ID and `livemode=false`, and derives safe brand/last-four metadata. Delete calls Stripe's detach endpoint. PayPal accepts an opaque vault payment-token ID, obtains a sandbox OAuth access token from client credentials, retrieves the payment token, checks the returned ID, and derives safe display metadata; delete calls the payment-token endpoint. Both adapters have bounded connect/request timeouts, fixed sandbox base URLs by default, and controlled errors. Credentials are read from runtime environment settings and never logged or included in API responses. Live sandbox calls await operator-provided credentials.

This package verifies the provider reference under the configured merchant account. A separate user-to-provider-customer ownership mapping is still required before claiming end-to-end user ownership validation. User-deletion cleanup, webhooks (if selected flow requires them), Vault secret resolution, and the asynchronous operation API remain open audit items.

## Evidence and sources

Compile the payment module and inspect the final diff. Update the audit with exact code-level evidence and note that no credentialed sandbox run occurred. Source references: [Stripe retrieve](https://docs.stripe.com/api/payment_methods/retrieve), [Stripe detach](https://docs.stripe.com/api/payment_methods/detach), [PayPal vault API](https://developer.paypal.com/api/payment-tokens/v3/), [PayPal OAuth](https://developer.paypal.com/api/rest/authentication).
