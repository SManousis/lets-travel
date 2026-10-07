# Payment sandbox operations

Payment-service stores gateway and payment-method metadata in PostgreSQL when the `infra` profile is active. The default profile keeps a local in-memory store for startup and development. Provider adapters contact the real provider sandbox by default. The opt-in local simulator below lets developers exercise the flow without provider accounts; neither mode accepts a reference merely because it is nonempty.

## Subscription payments (Phase 4)

A traveler pays for their own `PENDING_PAYMENT` subscription with `POST /api/subscription-payments`
([contract](api/payments.yaml)). The body names the subscription and **one** of these:

| Method | Request field | Provider call |
|---|---|---|
| Saved Stripe card | `paymentMethodId` | Off-session PaymentIntent, `confirm=true`, with header `Idempotency-Key` |
| Saved PayPal vaulted card | `paymentMethodId` | Orders v2, `intent=CAPTURE`, `payment_source.card.vault_id`, with header `PayPal-Request-Id` |
| TEST provider (simulation) | `testOutcome`: `APPROVE`, `DECLINE` or `TIMEOUT` | None. Built in, moves no money, needs no account |

`GET /api/payment-options` lists the caller's own `ACTIVE` saved methods (display data only) and
whether the TEST provider is on. `GET /api/subscription-payments` and
`GET /api/subscription-payments/{id}` return payment state. The owner or an ADMIN may read a
payment. Anyone else gets `404`.

### Steps of one payment

1. **Replay check.** If the caller already used this `Idempotency-Key` (a UUID header), the
   service returns that payment with `200`. Reusing the key with a different body returns
   `409 IDEMPOTENCY_KEY_REUSED`.
2. **Ownership and price.** payment-service reads the subscription from engagement-service
   (`GET /internal/subscriptions/{id}`). The subscription must belong to the caller, and so must
   the saved method; otherwise the answer is `404`, exactly as for an unknown ID. The amount and
   currency are engagement's price snapshot, never from the browser. A subscription that is not
   payable (expired, cancelled, already decided) returns `409 SUBSCRIPTION_NOT_PAYABLE`.
3. **Persist first.** The payment row is written as `PROCESSING` **before** the provider is called.
   The table enforces `UNIQUE (subscription_id)` (one payment per subscription, whatever key a
   duplicate uses: `409 PAYMENT_ALREADY_EXISTS`) and `UNIQUE (user_id, idempotency_key)`.
4. **Charge.** The provider idempotency key is `subscription-payment-<paymentId>`.
   - A verified success becomes `SUCCEEDED`. Stripe/PayPal answers count as success only if the
     payment ID, amount and currency match.
   - A definite decline becomes `FAILED` with a safe code such as `PAYMENT_DECLINED` or
     `AUTHENTICATION_REQUIRED`.
   - A timeout, a 5xx, a 429 or an unexpected answer is ambiguous: the provider may have charged.
     It becomes `REQUIRES_RECONCILIATION`. **There is no second charge.**
5. **Tell engagement.** payment-service calls `POST /internal/subscriptions/{id}/payment-outcome`.
   - `SUCCEEDED` confirms the subscription.
   - `FAILED` makes it `PAYMENT_FAILED` and releases the place. A decline is terminal; the
     traveler subscribes again to retry.
   - If engagement refuses a success (the 30-minute window expired, or the traveler cancelled
     meanwhile), the payment goes to `REFUND_PENDING`, then `REFUNDED` with reason
     `SUBSCRIPTION_NOT_PAYABLE`.

```
PROCESSING --verified success--> SUCCEEDED --cancelled / refused--> REFUND_PENDING --> REFUNDED
PROCESSING --decline----------> FAILED
PROCESSING --no answer--------> REQUIRES_RECONCILIATION --replay same key--> SUCCEEDED | FAILED
```

### Reconciliation

`PaymentReconciliationJob` runs every minute (`payments.reconciliation-interval`). It does three
things:

- **Unresolved payments.** It takes every `REQUIRES_RECONCILIATION` payment, and every
  `PROCESSING` row older than two minutes (its request thread died). It sends **the same
  request with the same provider idempotency key** again. Stripe and PayPal answer a repeated key
  with the first result instead of creating a new charge, and the TEST provider does the same.
- **Missed outcomes.** It tells engagement any outcome it has not yet received.
- **Refunds.** It retries `REFUND_PENDING` payments.

Every claim increments `attempts`, which is also the optimistic-lock token. A request, a webhook
and the job can therefore never move the same payment twice. After 10 attempts the payment is
left for an operator and a warning is logged. Stripe keeps idempotency keys for 24 hours, so a
payment unresolved for that long needs manual review.

### Cancellation and refunds

| Situation | Refund |
|---|---|
| Traveler cancels a `CONFIRMED` subscription on or before `cancellationCutoff` (start − 3 days, travel time zone) | Full refund |
| Manager/admin removes a `CONFIRMED` subscriber (any time, with a reason) | Full refund |
| Traveler cancels a `PENDING_PAYMENT` subscription | Nothing was paid |
| Payment succeeds after the reservation expired or was cancelled | Full refund, automatic |
| After the cutoff (the traveler cannot cancel) or `COMPLETED` | No refund |

engagement-service stores `refund_due` with the cancellation and calls
`POST /internal/subscriptions/{id}/refund` until payment-service accepts it. The refund call uses
the provider key `subscription-refund-<paymentId>`, so a repeated request refunds once (Stripe
`POST /v1/refunds`, PayPal `POST /v2/payments/captures/{id}/refund`). The traveler's "My
subscriptions" shows each payment line: Paid, Payment failed, Refund in progress, Refunded in
full, or "a full refund is being arranged" while the request is on its way.

### Provider callbacks (webhooks)

`POST /api/payment-webhooks/stripe` and `/api/payment-webhooks/test` are public at the gateway.
Trust comes only from the signature header, never from the network:

- **Signature.** The header is `Stripe-Signature` (Stripe) or `Test-Signature` (TEST provider).
  It carries `t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + raw body)>`, which is
  Stripe's scheme.
- **Rejections.** A wrong, missing or older-than-5-minutes signature returns
  `400 INVALID_SIGNATURE`, using a constant-time comparison. A provider without a configured
  secret returns `404`.
- **What an event can change.** `payment_intent.succeeded` and `payment_intent.payment_failed`
  only resolve a payment whose outcome is still unknown, and only if provider, amount and
  currency match. A redelivered event changes nothing.

PayPal uses certificate-verified webhooks. Here it is resolved by replaying the request instead
(see Reconciliation), so no PayPal webhook endpoint is exposed.

### Security

- **No card data.** The browser sends IDs only. Card numbers, CVCs and account credentials never
  reach this system: tokenization happens at the provider, and saved methods keep only a provider
  token and a display summary such as `visa •••• 4242`. The `subscription_payment` table has no
  column for card or account data (checked by `SubscriptionPaymentStoreTest`).
- **Redacted logs.** Logs carry payment IDs and states only: never provider references, customer
  IDs or secrets (asserted in `SubscriptionPaymentApiTest`).
- **Secrets from the environment.** Provider secrets come from the environment, supplied from
  Vault/deployment secrets: `STRIPE_SANDBOX_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`,
  `PAYPAL_SANDBOX_CLIENT_ID`, `PAYPAL_SANDBOX_CLIENT_SECRET` and `PAYMENT_TEST_WEBHOOK_SECRET`.
  The repository holds no values.
- **Safe errors.** Error and state messages are fixed, actionable sentences (for example "The
  payment was declined, so your reserved place was released. You can subscribe again and use
  another payment method."). Provider error text is never passed through. `503` answers before a
  payment is recorded say "Nothing was charged".

### Configuration

| Property (env) | Default | Meaning |
|---|---|---|
| `payments.test-provider.enabled` (`PAYMENT_TEST_PROVIDER_ENABLED`) | `false` | Offer the TEST provider. On in the payment-sim overlay only. Never enable it in production |
| `payments.test-provider.webhook-secret` (`PAYMENT_TEST_WEBHOOK_SECRET`) | empty | Enables `/api/payment-webhooks/test` |
| `payments.stripe.webhook-secret` (`STRIPE_WEBHOOK_SECRET`) | empty | Enables `/api/payment-webhooks/stripe` |
| `services.engagement.url` (`ENGAGEMENT_SERVICE_URL`) | `http://127.0.0.1:8085` | Subscription terms and outcomes |
| `payments.reconciliation-interval` | `PT1M` | Reconciliation job delay |

The auditor demo script is in [audit/phase-4-verification.md](audit/phase-4-verification.md).

## Local simulation without provider accounts

The local simulator uses **synthetic credentials and references**. It is separate from Stripe and PayPal and moves no money. Generate the ignored `.env.payment-sim` file from the repository root, then start the isolated Compose project:

```powershell
node scripts/sandbox/create-sim-env.mjs
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml up -d --build --wait admin-ui
.\scripts\sandbox\smoke-payment-sim.ps1
```

The generator refuses to overwrite an existing `.env.payment-sim`. The API is on `http://127.0.0.1:18080` and the dashboard is on `http://127.0.0.1:15173`. The admin username is `sim-admin`; read `ADMIN_PASSWORD` from your local ignored env file. The simulator is private to the Compose network and accepts only the generated fake credentials. It exposes these fixed references and fake customer IDs:

| Provider | Payment-method reference | Simulated customer ID | Display metadata |
|---|---|---|---|
| Stripe | `pm_simulatedvisa001` | `cus_simulated001` | `visa •••• 4242` |
| PayPal | `VAULT_SIM_001` | `PAYPAL_SIM_CUSTOMER_001` | `VISA •••• 1111` |

The simulator also implements Stripe PaymentIntents/refunds and PayPal Orders/capture refunds for these fixed references, idempotent per key like the real APIs, so saved-method subscription payments work end to end in this overlay. The overlay also turns on the built-in TEST provider.

The smoke script creates a local user and both gateways, connects the two references, verifies `ACTIVE`, and deletes them through the application API. Run it with `-KeepMethods` to leave the methods visible in the dashboard. Before running the script again after `-KeepMethods`, delete those retained methods through the application API or reset this isolated project's database; the fixed references must be unique. A deletion changes the simulator's in-memory reference state, so restart `payment-simulator` before reusing the references. Run `npm run test:payment-sim` for the mock-provider and credential-generator tests. To remove its containers and synthetic database volumes, run the same Compose command with `down -v` in place of `up -d --build --wait admin-ui`. The ignored `.env.payment-sim` file remains until you remove it separately.

This simulation checks application wiring, state transitions, and safe display data. It does **not** prove that Stripe or PayPal issued credentials/tokens, that their APIs accepted calls, or that a local user owns a provider customer. Real sandbox credentials and an ownership mapping are still required for those checks.

## Credentials and reference handoff

Set `STRIPE_SANDBOX_SECRET_KEY` to a Stripe `sk_test_...` key. Set `PAYPAL_SANDBOX_CLIENT_ID` and `PAYPAL_SANDBOX_CLIENT_SECRET` to the credentials of a PayPal sandbox app with vault access. Compose passes these environment settings to payment-service. Supply them through the deployment secret manager; never put secret values in source control. The current implementation uses one configured sandbox merchant account per provider, rather than resolving the `vaultSecretRef` field described by the future OpenAPI contract.

The browser or provider SDK completes tokenization and any payer approval. For Stripe, finish a SetupIntent so the `pm_...` PaymentMethod is attached to a sandbox customer, then send that ID to the admin API. The service retrieves the PaymentMethod, rejects live-mode or unattached objects, and stores only a safe display summary. For PayPal, complete the setup-token approval and exchange it for a vault payment token, then send that payment token ID to the admin API. The service obtains a sandbox OAuth token and retrieves the vault token before marking it active. These requests use bounded HTTP timeouts. See [Stripe PaymentMethod retrieval](https://docs.stripe.com/api/payment_methods/retrieve), [Stripe detach](https://docs.stripe.com/api/payment_methods/detach), [PayPal vault tokens](https://developer.paypal.com/api/payment-tokens/v3/), and [PayPal OAuth](https://developer.paypal.com/api/rest/authentication).

Create a gateway through `POST /api/payment-gateways` with `provider` set to `STRIPE` or `PAYPAL` and a `displayName`. Create a method through `POST /api/payment-methods` with `userId`, `gatewayId`, `providerReference`, and `displayLabel`, plus a UUID `Idempotency-Key` header. Payment-service asks user-service whether that local user exists, is active, and has no deletion in progress before accepting a new method. The record is persisted as `PENDING` before the provider call. A verified reference becomes `ACTIVE`; a rejected reference or unavailable provider leaves a readable `FAILED` record with a safe failure code. Retry the same request with the same key after resolving the cause. Reusing a key with different input returns `409`. A disabled/deleting user's exact old key can only retrieve the recorded result; it cannot restart provider work.

The local display label and default preference are editable through `PATCH /api/payment-methods/{id}`. Send `{"displayLabel":"Travel card"}`, `{"isDefault":true}`, or both. The first `ACTIVE` method for a local user becomes the default automatically. A later create request may include `"isDefault":true` to select that method after its provider connection succeeds; this preference is saved with the pending request so retrying the same idempotency key retains it. Exactly one method is default while a user has any `ACTIVE` methods. Selecting another `ACTIVE` method switches the default atomically. Clearing the current default with `"isDefault":false` returns `409 DEFAULT_METHOD_REQUIRED`; selecting a non-active method returns `409 METHOD_NOT_ACTIVE`. When a default enters deletion, the oldest remaining `ACTIVE` method becomes default. A database constraint prevents a non-active default or two defaults for the same local user.

The dashboard shows the current default, offers **Make default** on other active methods, and includes a create-time default checkbox. The API also accepts both preference fields in one patch when an operator needs to update the label and selection together.

The provider, provider reference, gateway, user, and provider-supplied display metadata are immutable after connection. `PATCH` rejects these and other unknown fields with `400 VALIDATION_ERROR`, without applying a simultaneous label change. To change the funding details, connect a replacement provider reference and then delete the old method. The API response includes `isDefault` but does not return the provider reference. Deletion requires a UUID `Idempotency-Key`; the service marks the method `DELETION_PENDING` before calling Stripe detach or PayPal vault-token deletion, then removes the local row after provider success. Concurrent calls return `202` while a provider call owns the 30-second claim; retry after that window if a process crashed. A failure remains visible as `FAILED` for operator review and retry. A provider `404` is not treated as proof that an old token was removed, because it may belong to an account that the current credentials cannot access. Gateway deletion returns `409 GATEWAY_IN_USE` while any method, including a failed method, refers to it.

User deletion marks the local user in deletion progress, revokes their auth identity and sessions, disables their profile, and calls the private payment cleanup endpoint. Payment-service first blocks new connections for that user, then attempts up to two method detachments per call so the synchronous internal request stays bounded. An in-progress connection waits for its 30-second claim to expire before cleanup takes over. The user DELETE returns `202` while payment methods remain; repeat it to resume the next batch. It proceeds to travel participation and traveler cleanup only after all payment methods are gone, then returns `204`. Provider failure keeps the payment method with a safe failure code, and the user remains disabled while cleanup is incomplete. Internal calls use the shared service token and bounded timeouts.

## Current limits

No credentialed sandbox exchange has been run in this repository environment. The active-user check establishes only local eligibility. Provider reference inspection verifies the configured merchant account and a reusable/attached token, but no trusted mapping exists from a local user to a Stripe/PayPal customer, so provider-customer ownership is **not proven**. Do not infer ownership from the supplied `userId` or first use of a reference. Per-gateway Vault secret resolution and the asynchronous operation endpoints/statuses in `docs/api/payments.yaml` remain open. Subscription payments verify Stripe webhooks and the TEST provider's webhooks. PayPal webhooks are not consumed; PayPal outcomes are resolved by replaying the request instead. No credentialed Stripe/PayPal **charge** has been run here either: the charge and refund adapters are exercised against local stand-ins that follow the documented request and response shapes. The implemented API also retains its existing `displayLabel`/`id` naming and unpaginated list shape, which differ from parts of that planned contract. A request that crashes after the provider call may leave a `PENDING` or `DELETION_PENDING` row; retry the same create key or delete request to resume. Do not claim Phase 7's sandbox completion check until both providers have been exercised with credentials and the remaining contract/lifecycle work is complete.
