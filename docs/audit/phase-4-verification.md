# Phase 4 verification: secure subscription payments

Verified on 2026-10-07 on branch `phase-3-4` (base commit `675af88` plus the uncommitted Phase 3
and Phase 4 changes). Environment: macOS, OpenJDK 21, Node 24 and the Maven Wrapper.
Design: [payments](../payments.md#subscription-payments-phase-4) and
[subscriptions](../subscriptions.md#payment-hand-off-phase-4).

Audit question: *Is the payment process secure and accommodating of various payment methods?*

- **Payment methods.** A traveler can pay with a saved Stripe card (PaymentIntents), a saved
  PayPal vaulted card (Orders v2 capture), or the built-in TEST provider, which needs no external
  account.
- **Security.**
  - Only the owner can pay, with their own subscription and their own method; anyone else gets
    `404`.
  - The amount comes from the server.
  - No card data is sent or stored, logs are redacted, and secrets come from the environment.
  - Webhooks are HMAC-verified.
  - Every payment is idempotent and reconciled, never charged twice.

## Automated checks

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS. All nine Maven modules `BUILD SUCCESS`, 269 tests, zero failures and zero errors (payment-service 48, engagement-service 32) |
| `npm run lint:api` | PASS (exit 0). All 8 contracts valid; the 1 pre-existing ignored problem is unchanged |
| `npm --prefix admin-ui run test:ci` | PASS. 5 files, 19 tests |
| `npm --prefix admin-ui run lint` | PASS (exit 0) |
| `npm --prefix admin-ui run build` | PASS (exit 0) |
| `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet` | PASS (exit 0). payment-service gets `ENGAGEMENT_SERVICE_URL`, the webhook secrets and `PAYMENT_TEST_PROVIDER_ENABLED`; engagement-service gets `PAYMENT_SERVICE_URL` |
| `npm run test:payment-sim` (extra) | PASS. 5 tests, including the new idempotent PaymentIntent/refund and PayPal order/refund simulator routes |

## What the tests prove

### `SubscriptionPaymentApiTest` (payment-service, HTTP)

The test runs the real TEST provider. engagement, Stripe, PayPal and auth are stand-ins with the
same contracts.

| Required behaviour | Test |
| --- | --- |
| Successful confirmation | `approvedPaymentConfirmsTheSubscriptionAndRetriesReturnTheSamePayment`: `201 SUCCEEDED`, engagement told `SUCCEEDED` once, subscription `CONFIRMED` |
| Duplicate request (browser retry, gateway retry, second tab) | The same test: the same key returns `200` with the same payment. Another key gives `409 PAYMENT_ALREADY_EXISTS`; a reused key with another body gives `409 IDEMPOTENCY_KEY_REUSED`. The provider charged once. `concurrentDuplicateRequestsProduceExactlyOnePayment`: 12 concurrent requests (8 with one key, 4 with new keys) give one `201`, one payment and one charge |
| Declined payment | `declinedPaymentFailsTerminallyAndReleasesThePlace`: `FAILED` / `PAYMENT_DECLINED`, a safe message, engagement told `FAILED` (subscription `PAYMENT_FAILED`, place released) |
| Provider timeout + reconciliation | `providerTimeoutIsReconciledWithoutASecondCharge` (TEST provider) and `stripeAnswerLostAfterChargingIsReconciledToExactlyOnePaymentIntent` (Stripe adapter, answer lost after the intent was created): the payment is `REQUIRES_RECONCILIATION`, a retry does not call the provider, and reconciliation replays the same key to `SUCCEEDED` with exactly one charge/PaymentIntent and one outcome |
| Wrong-owner method | `anotherUsersMethodOrSubscriptionIsNotFoundAndNothingIsCharged`: another user's method gives the same `404` body as an unknown one, with no reference or user ID in it. Another user's subscription also gives `404`. Nothing is recorded and no PaymentIntent is created |
| Callback verification | `onlyAVerifiedFreshWebhookCanResolveAPayment`: a wrong HMAC, a missing header, a timestamp 10 minutes old or a wrong secret gives `400 INVALID_SIGNATURE`. A tampered amount is ignored. An unconfigured provider gives `404`. A valid event resolves the payment once; a redelivery changes nothing |
| Saved methods, both providers | `savedStripeMethodPays…` and `savedPayPalMethodPaysThroughTheOrdersApi` (JPY, zero decimals) |
| Refund on cancellation | `savedStripeMethodPaysAndIsRefundedWhenThePaidSubscriptionIsCancelled`: the internal refund gives `REFUNDED`. A repeat creates no second provider refund. Without the token the answer is `403`; with nothing paid it is `204` |
| Late success after expiry | `successForAReservationThatExpiredMeanwhileIsRefunded`: engagement refuses (`409`), so the payment is automatically `REFUNDED` |
| Outcome not lost | `outcomeIsDeliveredLaterWhenEngagementIsDown`: `SUCCEEDED` with "Confirming your place…"; reconciliation then delivers it |
| Redacted logs | The Stripe test captures the log output: it contains the payment ID but not the `pm_…` reference, the `cus_…` customer or the secret key |
| Safe refusals | `unpayableOrInvalidRequestsAreRefusedBeforeAnythingIsRecorded`: expired reservation `409`, missing key / both or neither method / unknown scenario `400`, engagement outage `503` "Nothing was charged" |
| Options are owner-only | `optionsListOnlyTheCallersActiveMethodsWithSafeDisplayData` |

### Other tests

- **`SubscriptionPaymentStoreTest`.** Runs the real Flyway V5 SQL on H2 in PostgreSQL mode. The
  database itself enforces one payment per subscription and one per (user, key). Updates are
  optimistic. The reconciliation query finds lost answers, stale `PROCESSING` rows and
  undelivered outcomes. The table has no card, secret or account column.
- **`WebhookSignatureTest`.** Fresh and correct signatures are accepted. Tampered, replayed
  (more than 5 minutes), wrong-secret and malformed headers are rejected.
- **`SubscriptionApiTest` (engagement).**
  - `unpaidReservationsExpireAfterThirtyMinutesAndReleaseThePlace`: still `PENDING_PAYMENT` at
    29:59; at 30:01 it becomes `PAYMENT_FAILED` with history reason `RESERVATION_EXPIRED`, the
    place is released, a late `SUCCEEDED` gets `409`, and the traveler can subscribe again. The
    internal terms endpoint needs the service token.
  - `cancellingAPaidSubscriptionRequestsARefundUntilPaymentServiceAcceptsIt`: `refund_due`
    survives a payment-service outage, and housekeeping requests the refund exactly once.
    Removing a paid subscriber (manager) also refunds; an unpaid cancellation does not.
- **Authorization and routing.**
  - The `AuthorizationMatrixTest` of payment-service and engagement-service lists every new
    endpoint: `TRAVELER` for payments and options, `PUBLIC` for webhooks, `INTERNAL` for terms and
    refund.
  - `GatewayRoleMatrixTest` and `GatewayRoutingTest` cover the new routes. The `/internal/**`
    paths and `/api/payment-webhooks/paypal` are not routed.
- **`Payments.test.tsx` (admin-ui).**
  - Pays with the default saved method, sending only IDs and an Idempotency-Key.
  - Shows a declined TEST payment with the safe message and the "Payment failed" state.
  - Keeps one key across a network retry, shows the "being confirmed" state, and resolves it with
    **Check payment status**.
  - "My subscriptions" shows Refunded / refund being arranged / no payment taken.

## Auditor demo: paying with the TEST provider (no external accounts)

1. **Start the payment-sim stack** from the repository root. It enables the TEST provider and the
   local Stripe/PayPal simulator:

   ```bash
   node scripts/sandbox/create-sim-env.mjs
   docker compose --env-file .env.payment-sim -p travel-plan-payment-sim \
     -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml up -d --build --wait admin-ui
   ```

   The dashboard is on `http://127.0.0.1:15173` and the API on `http://127.0.0.1:18080`. The
   admin user is `sim-admin`; its password is `ADMIN_PASSWORD` in `.env.payment-sim`.
2. **Publish a travel.** As admin, create a travel manager in **Users and travelers**. Sign in as
   that manager and create and publish a travel with a price, as in Phase 2.
3. **Create a traveler.** Use **Create an account** on the sign-in page, then sign in as that
   traveler.
4. **Approve.**
   1. Under **Available travels**, select the travel and press **Subscribe**. "My subscriptions"
      shows *Awaiting payment* and "Pay by …".
   2. Press **Pay now** and choose **Test card (simulation, no real money)** with
      *Approve the payment*.
   3. Press **Pay €…**. Expected: "Payment received. Your place is confirmed.", status
      *Confirmed*, and the line "Test card (simulated approve): Paid".
5. **Refund.** Press **Cancel** on that subscription (before the cutoff) and confirm. Expected:
   status *Cancelled by you*. After **Refresh**, the line reads "Refunded in full".
6. **Decline.** Subscribe to the travel again, **Pay now**, and choose *Decline the payment*.
   Expected: "The payment was declined, so your reserved place was released…", status *Payment
   failed*, and the place count goes back up.
7. **Timeout.** Subscribe again and choose *Provider timeout*. Expected: "We are confirming this
   payment with the provider. You will not be charged twice…". Within about a minute the
   reconciliation job resolves it. **Check payment status** then shows "Payment received. Your
   place is confirmed.", and there is exactly one payment for the subscription.
8. **Expiry (optional).** Subscribe and do not pay. About 31 minutes later the subscription shows
   *Payment failed* with "No payment was taken. The reservation expired or the payment failed.",
   and the place is free again.
9. **API checks (optional).** Use a traveler access token in `$TOKEN` and a subscription ID in
   `$SUB`.
   1. Send the same request twice. Both answers carry the same `paymentId`: first `201`, then
      `200`.

      ```bash
      KEY=$(uuidgen)
      for i in 1 2; do curl -s -w ' %{http_code}\n' -X POST http://127.0.0.1:18080/api/subscription-payments \
        -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
        -d "{\"subscriptionId\":\"$SUB\",\"testOutcome\":\"APPROVE\"}"; done
      ```

   2. Send a forged webhook:

      ```bash
      curl -s -X POST http://127.0.0.1:18080/api/payment-webhooks/test \
        -H 'Test-Signature: t=1,v1=bad' -H 'Content-Type: application/json' -d '{}'
      ```

      This returns `400 INVALID_SIGNATURE` when `PAYMENT_TEST_WEBHOOK_SECRET` is set, and `404`
      when webhooks are not configured.
   3. To see the redacted logs, run
      `docker compose -p travel-plan-payment-sim logs payment-service | grep -E 'pm_|cus_|sk_test'`.
      It prints nothing.

Saved-method payments in the same stack: as admin, connect `pm_simulatedvisa001` (Stripe) or
`VAULT_SIM_001` (PayPal) for the traveler under **Payments and gateways**, as in the
[simulation section](../payments.md#local-simulation-without-provider-accounts). The traveler then
sees that card under **Pay now**.

## Not covered here

- The Compose demo above was **not** run for this verification. The evidence is the automated
  checks.
- No credentialed Stripe/PayPal sandbox charge was made. The adapters follow the documented APIs
  and are exercised against stand-ins and the local simulator. PayPal webhooks are not consumed;
  PayPal is reconciled by replaying the request.
- Outbox events are still not published to consumers (event pipeline work).
