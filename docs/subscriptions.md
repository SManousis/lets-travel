# Subscriptions (Phase 3)

How travelers take places on travels, how they cancel, and how managers manage subscriber lists.
API contract: [api/engagement.yaml](api/engagement.yaml).

## Where state lives

| State | Owner | Store |
| --- | --- | --- |
| Subscription, its status, price/date snapshot, cancellation reason | engagement-service | PostgreSQL `subscription` |
| Audit trail of every status change (who, when, why); traveler cancellation history | engagement-service | PostgreSQL `subscription_history` |
| Places taken on a travel (capacity) | travel-service | Neo4j `(:Travel)-[:HAS_PARTICIPANT]->(:Participation)` |
| Lifecycle events for other services | engagement-service | PostgreSQL `outbox_event` (same transaction as the change) |

## Atomic place reservation

travel-service already enforces capacity on the travel aggregate, so the place is reserved there
through a private endpoint, `POST /internal/travels/{travelId}/reservations` (service token, never
routed by the gateway). In one Neo4j write transaction it:

1. write-locks the travel node (`SET t.lockVersion = t.lockVersion + 1`), so concurrent
   reservations for the same travel run one after another;
2. reads the travel with its participations and checks it is `PUBLISHED`, has not started in its
   own time zone, and has a free place (`TRAVEL_NOT_BOOKABLE`, `TRAVEL_FULL`);
3. `MERGE`s a participation whose ID is derived from (travel, traveler), so a retried reservation
   holds the same single place.

The last place can therefore only be given once (`TravelReservationTest` runs 12 concurrent
reservations against 3 places on an in-process Neo4j). `DELETE
/internal/travels/{travelId}/reservations/{travelerId}` gives the place back and is idempotent.

## Subscribe

1. `Idempotency-Key` seen before for this traveler: return that subscription (`201`).
2. An active (`PENDING_PAYMENT`/`CONFIRMED`) subscription exists: `409 ALREADY_SUBSCRIBED`.
3. user-service `GET /internal/users/{id}/summary` must report the account active.
4. Reserve the place in travel-service (above).
5. Insert the `PENDING_PAYMENT` row, its history entry and a `SubscriptionReserved` event in one
   transaction. A unique `active_key` (`travelId:travelerId`, cleared when the subscription ends)
   and a unique `(traveler_id, idempotency_key)` make concurrent duplicates lose with a duplicate
   key; they then return the winner (same key) or `ALREADY_SUBSCRIBED`. Because the place is keyed
   by traveler, the loser did not take a second place. If the insert fails for another reason
   the place is given back.

## Cancellation rule

A traveler may cancel up to and including the travel-local calendar date three days before the
start (`cancellationCutoff = startDate - 3 days`), judged in the travel's IANA `timeZone`
(new `Travel.timeZone`, UTC for older travels). Dates are compared, not durations, so
daylight-saving changes do not move the boundary. The time comes from an injected `Clock`.
Managers (own travels) and admins (any travel) can remove a subscriber at any time with a 3–500
character reason; another manager gets `404`.

Ending a subscription commits the new status first and then releases the place. If travel-service
is down at that moment the place stays held (never oversold) with `seat_released = false`; a retry
of the same request or the 15-minute housekeeping job finishes the release.

## States

```
PENDING_PAYMENT --payment ok--> CONFIRMED --travel ended--> COMPLETED
PENDING_PAYMENT --payment failed--> PAYMENT_FAILED            (place released)
PENDING_PAYMENT | CONFIRMED --traveler, until cutoff--> CANCELLED_BY_TRAVELER (place released)
PENDING_PAYMENT | CONFIRMED --manager/admin + reason--> CANCELLED_BY_MANAGER  (place released)
```

`COMPLETED` is set by the housekeeping job once the travel's end date has passed in its time zone;
the participation stays in Neo4j as travel history.

## Payment hand-off (Phase 4)

Payment itself is described in [payments.md](payments.md#subscription-payments-phase-4). The
engagement side is:

- `GET /internal/subscriptions/{id}` (service token) gives payment-service the owner, the price
  snapshot and `payable` (`PENDING_PAYMENT` and before `reservationExpiresAt`). payment-service
  never takes an amount from the browser.
- `POST /internal/subscriptions/{id}/payment-outcome` with
  `{"outcome": "SUCCEEDED" | "FAILED", "paymentId": "<uuid>"}`. `SUCCEEDED` moves
  `PENDING_PAYMENT` to `CONFIRMED` and stores the payment ID; `FAILED` moves it to
  `PAYMENT_FAILED` and releases the place. Repeating the same outcome returns the same result; a
  different payment for a confirmed subscription is `409 PAYMENT_MISMATCH`, and any outcome for a
  subscription that is no longer pending (expired or cancelled meanwhile) is
  `409 SUBSCRIPTION_NOT_PENDING`, which payment-service answers with a full refund.
- **Reservation expiry.** A job runs every minute (`subscriptions.expiry-interval`, default
  `PT1M`). A `PENDING_PAYMENT` subscription whose `reservationExpiresAt` (30 minutes after
  subscribing) has passed becomes `PAYMENT_FAILED` with history reason `RESERVATION_EXPIRED`, and
  its place is released. So an unpaid place is held at most about 31 minutes.
- **Refund on cancellation.** Cancelling a `CONFIRMED` subscription (traveler before the cutoff,
  or manager/admin at any time) sets `refund_due = TRUE` in the same transaction as the status
  change (Flyway V3). engagement then calls payment-service
  `POST /internal/subscriptions/{id}/refund`. Only a `2xx` answer clears the flag. While
  payment-service is down, the 15-minute housekeeping job asks again. Cancelling an unpaid
  (`PENDING_PAYMENT`) subscription owes nothing.

## Events

Written to `outbox_event` with the change: `SubscriptionReserved`, `SubscriptionConfirmed`,
`SubscriptionPaymentFailed`, `SubscriptionCancelled` (status says by whom) and
`SubscriptionCompleted`. Payloads carry IDs, dates, price, status and payment ID only — no names,
contact details or reason text. The outbox feed publisher is part of the event pipeline work.
