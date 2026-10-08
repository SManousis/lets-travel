# Privacy and personal data

What personal data Let's Travel keeps, why, for how long, and how people see, download and delete it. Technical
controls are in [security](security.md).

## 1. Data inventory

| Data | Where | Purpose | Kept until |
| --- | --- | --- | --- |
| Account: e-mail, first and last name, role, status | user-service | Sign-in, showing who organises or joins a travel | Account deletion |
| Password hash (BCrypt), sessions | auth-service | Sign-in | Account deletion (sessions: 12 hours at most) |
| Traveler profiles: passport number, nationality | user-service | Booking requirements of the organiser | Deleted by the owner, or with the account |
| Subscriptions and their history | engagement-service | Bookings, cancellations, refunds, dashboards | Kept, linked only to the account ID (below) |
| Feedback: rating, comment, author name | engagement-service | Manager ratings and statistics | Name and comment removed at account deletion; rating kept |
| Reports: category, description, reporter | engagement-service | Moderation | Kept for moderation and legal claims |
| Saved payment methods: provider reference, masked label | payment-service | Paying again without re-entering details | Detached at the provider and deleted at account deletion |
| Subscription payments: amount, currency, provider, masked method label, state | payment-service | Accounting, refunds, reconciliation | Kept as financial records |
| Travel history and ratings (graph) | travel-service (Neo4j) | Personal recommendations | Removed at account deletion |
| Search index | search-service | Search | Holds travels only, no personal data except manager display names |
| Audit records | each service | Accountability for privileged actions | Kept; contain IDs and decisions, never free text written by users |
| Logs | all services | Operations and security | Contain request IDs, user IDs and paths; never tokens, passwords, card data or message contents |

**Never stored:** card numbers, CVCs, bank or PayPal credentials (the providers keep them; we keep their
reference and a masked label).

## 2. Minimization

- Managers see subscribers' display names, membership date, trip and report counts, not their contact details.
- The public manager page shows ratings without their authors and the number of upheld reports without their contents.
- Events between services carry IDs, statuses and ratings only; never descriptions, contact details or payment data.
- Dashboards show counts and totals, never individual reports.

## 3. Access and export

Every signed-in person can open **My statistics → Download my data**. The browser gathers, from each service,
everything that service returns to that person (account, traveler profiles, subscriptions, payments, feedback,
reports, statistics) and saves it as one JSON file. Nothing is assembled on a server, so the export can never
include another person's data.

## 4. Account deletion

An administrator deletes an account (`DELETE /api/users/{id}`); the steps run in order, each repeatable, and the
deletion continues where it stopped if a service is unavailable (`deletion_stage` in user-service):

1. **auth-service:** sessions revoked and sign-in disabled at once.
2. **engagement-service:** active subscriptions cancelled (no cancellation cut-off applies), places given back,
   paid ones refunded in full; feedback keeps its rating but loses the author's name and comment
   ("Former traveler"). The deletion waits until every refund request is accepted.
3. **payment-service:** saved payment methods detached at the provider and deleted.
4. **travel-service:** seat reservations and the recommendation history removed.
5. **user-service:** traveler profiles and the account removed.

What remains, and why:

- **Subscriptions, payments and refunds** stay as financial and booking records (accounting obligations and
  possible disputes). They refer to the deleted account's ID only; no name, e-mail or profile is left to connect it
  to a person.
- **Ratings** stay without author or comment, so managers' statistics do not change retroactively.
- **Reports** the person filed stay for moderation decisions and possible legal claims; reports about them stay
  as moderation records.
- **Audit records** stay; they hold IDs and decisions only.

Retention periods for these records are set by the operator's legal obligations (for example, accounting law
for payments); deleting them after that period is an operator task, not automated yet.

## 5. Notice and consent

- Sign-up is the only collection point for account data; the form says the account is for browsing and booking.
- No analytics, advertising or third-party trackers are loaded; the CSP only allows the site's own origin.
- Payment providers receive only what a payment needs (amount, currency, their own method reference).

## 6. Personal-data incidents

1. Contain: revoke affected sessions (disable accounts or rotate `AUTH_TOKEN_SECRET` to sign everyone out),
   rotate exposed credentials in the secret store, close the access path.
2. Assess: use the audit records and logs (request IDs) to find which records and people are affected.
3. Notify: the operator informs the supervisory authority within 72 hours where the law requires it, and the
   affected people when there is a high risk to them.
4. Record what happened, what was affected and what changed, in the operator's incident register.
