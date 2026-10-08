# Dashboards and statistics (Phase 8)

Which figures each role sees, the exact rule behind every figure, and how the totals are checked against
payment-service. API contract: [api/dashboards.yaml](api/dashboards.yaml).

## 1. Endpoints and scope

| Endpoint | Who | Scope |
| --- | --- | --- |
| `GET /api/dashboards/me` | Every signed-in role | Only the caller's own figures |
| `GET /api/dashboards/manager?months=` | Travel manager, admin | A manager always gets their own; `managerId=` is honoured for admins only |
| `GET /api/dashboards/admin?months=&top=` | Admin | Whole platform |
| `GET /api/dashboards/admin/managers` | Admin | Ranked managers with score components |
| `GET /api/dashboards/admin/travels?managerId=` | Admin | Travel history with satisfaction figures |
| `GET /api/dashboards/admin/reconciliation` | Admin | Dashboard income against payment-service |
| `GET /api/managers/{id}` | Every signed-in role | Public manager page (Phase 6): no reporters, report contents or rating authors |

Scope is enforced in engagement-service as well as at the gateway; each endpoint is in the authorization
matrix (`AuthorizationMatrixTest`), and `DashboardApiTest` checks that a manager asking for another manager's
dashboard still receives their own.

## 2. Where the numbers come from

Every figure is computed on request from engagement-service's own tables: `subscription`,
`subscription_history`, `feedback` and `report`. There is no separate copy that could drift. A subscription's
**payment time** is when its history first records `CONFIRMED` (payment-service only confirms after a verified
payment); its **refund time** is when it moves from `CONFIRMED` to a cancelled status, because cancelling a paid
subscription always triggers a full refund (see [payments](payments.md#cancellation-and-refunds)).

The rules live in one class, `DashboardCalculator`, and `DashboardCalculatorTest` checks every figure against a
small fixture whose expected values are worked out by hand in the test's comments.

## 3. Rules for every metric

| Metric | Rule |
| --- | --- |
| Time zone and months | Calendar months in UTC. `months=n` covers the current month and the n-1 before it; every month is listed, empty months with no amounts. |
| Currency | Amounts are integer minor units per currency. Currencies are never added together or converted. |
| Income | The price counts in the month the subscription was paid and is subtracted in the month it was refunded. Unpaid and expired reservations never count. |
| Travelers | Subscriptions that are CONFIRMED or COMPLETED now. |
| Cancellations (per travel) | Paid subscriptions that were later cancelled (refunded). Cancelling an unpaid reservation is not counted. |
| Cancellation rate | Cancellations / paid subscriptions; 0 when nothing was paid. |
| Trips (manager) | The manager's travels with at least one traveler; upcoming when they start after today. |
| Organized travels (admin) | Travels with at least one traveler that started within the selected months (and not after today). |
| Active travelers (admin) | Distinct travelers with a CONFIRMED or COMPLETED subscription on such a travel. |
| Ratings | Average of feedback ratings, one decimal, with the count. The admin average covers feedback written in the selected months. |
| Travel history | Travels that have started (start date today or earlier), most recent first, with travelers, cancellations, net income and rating. Travels nobody subscribed to have no records here. |
| Top travels | Started travels by average rating, then travelers, then most recent start. |
| Reports | `reportsFiled` counts the caller's reports; `upheldReportsAgainstMe` counts RESOLVED reports against them; `openReports` counts OPEN and IN_REVIEW. Report contents are never part of a dashboard. |
| Traveler statistics | Completed trips (COMPLETED), upcoming trips (CONFIRMED, starting after today), cancellations (cancelled by the traveler), feedback given. |
| Preferred payment methods | Providers of the traveler's payments that went through (SUCCEEDED, refund pending or refunded), most used first, from payment-service. If payment-service is down the list is empty and the rest of the dashboard still loads. |

## 4. Manager performance score

Computed over the last 12 calendar months:

```
score = 0.40 x rating + 0.30 x income + 0.20 x completed travels
      + 0.10 x (1 - cancellation rate) - 0.15 x upheld reports
```

- **rating**: the manager's average rating of the period.
- **income**: net income in the platform currency (`dashboards.platform-currency`, EUR by default).
- **completed travels**: distinct travels with a COMPLETED subscription that ended in the period.
- **cancellation rate**: of the subscriptions paid in the period, the share later refunded.
- **upheld reports**: RESOLVED reports against the manager filed in the period.

Rating, income, completed travels and upheld reports are divided by the highest value among the ranked managers,
so each is between 0 and 1 (0 when nobody has any). A manager is ranked when they had a paid subscription, a rating
or an upheld report in the period. Ties break on completed travels, then manager ID. Each ranking row returns these
normalized `components`, so the score can be recomputed by hand.

Worked example (from `DashboardCalculatorTest`): Ana has the best rating, income and completed-travel count, one of
four paid subscriptions refunded and no upheld reports: `0.40 + 0.30 + 0.20 + 0.10 x 0.75 - 0 = 0.975`. Bo rated
2.0 against Ana's 4.5, earned 400.00 against 1300.00, completed one travel, had one of two subscriptions refunded
and one upheld report: `0.40 x 0.444 + 0.30 x 0.308 + 0.20 x 1 + 0.10 x 0.5 - 0.15 x 1 = 0.370`.

## 5. Checking the totals against payments

`GET /api/dashboards/admin/reconciliation` compares, per currency, the net income derived from subscriptions with
the money payment-service holds as SUCCEEDED payments (`GET /internal/payment-totals`). Cancelling a paid
subscription moves its payment out of SUCCEEDED, so the two are equal once every refund request and payment
outcome has been delivered: status `MATCH`. `DIFFERENT` is expected for a moment while those are on their way
(payment-service and engagement-service retry them); a difference that persists points to a payment or refund
that needs attention, and `refundPendingMinor` shows refunds still being processed.

The dashboards themselves are not stored, so there is nothing to repair: fixing the source record fixes every
figure on the next request.
