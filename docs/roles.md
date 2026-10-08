# Roles and permissions

Three roles, each including everything below it: **Traveler < Travel Manager < Admin**. Everyone who signs up is a
Traveler; only an Admin creates Travel Managers or changes roles. "Own" means the caller's own record, or for a
manager, travels whose owner is the caller.

## What each role can do

| Capability | Traveler | Travel Manager | Admin | Screen |
| --- | --- | --- | --- | --- |
| Search with autocomplete, browse and open published travels | ✓ | ✓ | ✓* | Find and book |
| Personal recommendations | ✓ | ✓ | ✓* | Home |
| Subscribe, pay (test card or saved method), cancel up to 3 days before the start | ✓ | ✓ | ✓* | Find and book |
| Rate a finished trip once, with an optional comment | ✓ | ✓ | ✓* | My feedback |
| Report a travel, manager or traveler; follow own reports | ✓ | ✓ | ✓* | My reports, travel detail, manager page |
| Public manager page (statistics, ratings without authors) | ✓ | ✓ | ✓ | Travel detail |
| Own statistics and "Download my data" | ✓ | ✓ | ✓ | My statistics |
| Traveler profiles (passport, nationality) | Own | Own | Any | Users (admin) |
| Create, edit, publish, unpublish and delete travels | – | Own | Any (chooses the organiser) | Travels |
| See subscribers and remove one with a reason (refunded if paid) | – | Own travels | Any | Subscribers |
| Read feedback on travels | – | Own travels | All | Travel feedback |
| Manager analytics: income by month, trips, travelers, cancellation rate, rating | – | Own | Any manager | Analytics |
| Platform overview, income check against payments, manager rankings, travel history | – | – | ✓ | Overview, Manager rankings, Travel history |
| Users: create (with a set-password link), change role or status, delete | – | – | ✓ | Users |
| Destinations | – | – | ✓ | Travels |
| Moderation: assign, review, resolve or dismiss reports | – | – | ✓ | Reports |
| Payment gateways and saved payment methods | – | – | ✓ | Payments |
| Event queue status and resending parked events | – | – | ✓ | API (`/api/admin/events`) |

\* Admins can use traveler features through the API; their web navigation shows the administration screens.

Rules that apply to every role:

- Records the caller may not see answer **404**, not 403, so their existence is not revealed. A role below an
  endpoint's minimum gets **403**; no or an expired token gets **401**.
- A manager cannot change a travel's owner, edit another manager's travel or see its subscribers.
- Cancelling within three days of the start is refused for travelers (`CANCELLATION_CUTOFF_PASSED`); a manager or
  admin removing a subscriber is not bound by the cutoff and the traveler is refunded in full.
- Feedback is only accepted after the trip ended and once per trip; reports are limited to 10 a day per person.
- Every privileged action is written to the audit records ([security](security.md#6-audit-records)).

## How it is enforced

1. **Web app:** each role sees only its own navigation; opening another role's address shows "Not available for
   your role" without asking the server for data (`lib/routes.ts`, tested in `App.test.tsx` and `routes.test.ts`).
2. **Gateway:** checks the token, the session (with auth-service) and the minimum role per path
   (`GatewaySecurityConfig.RULES`, tested by `GatewayRoleMatrixTest` for every routed path).
3. **Each service:** checks the role again and ownership, because services must stay safe when called directly.
   Each has an `AuthorizationMatrixTest` that calls every endpoint with no token and as each role, and fails when an
   endpoint is added without an entry.
4. **Internal endpoints** (`/internal/**`) need the internal service token and are not routed by the gateway.

Role changes, disabling and deletion revoke the person's sessions at once, so a demoted manager cannot keep using
an old token.
