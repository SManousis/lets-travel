# Feedback, reports and moderation (Phase 6)

How travelers rate travels and report people or travels, and how managers and admins use that
information. API contract: [api/engagement.yaml](api/engagement.yaml) (feedback, reports) and
[api/dashboards.yaml](api/dashboards.yaml) (public manager page). Everything lives in
engagement-service's PostgreSQL database (migration `V4__create_feedback_and_reports.sql`).

## Endpoints

| Method and path | Minimum role | What it does |
| --- | --- | --- |
| `POST /api/feedback` | TRAVELER | Rate a finished travel (1 to 5, optional comment up to 1000 characters) |
| `GET /api/feedback/mine` | TRAVELER | The caller's own feedback history |
| `GET /api/feedback?travelId=&managerId=` | TRAVEL_MANAGER | Managers: feedback on their own travels. Admins: all feedback |
| `POST /api/reports` | TRAVELER | Report a travel manager, a traveler or a travel |
| `GET /api/reports` | TRAVELER | The caller's own reports, with what they wrote and the status |
| `GET /api/admin/reports?status=&targetType=` | ADMIN | Moderation queue, oldest first |
| `GET /api/admin/reports/{id}` | ADMIN | One report with its moderation history |
| `PATCH /api/admin/reports/{id}` | ADMIN | Move to `IN_REVIEW`, `RESOLVED` (upheld) or `DISMISSED`, with a note |
| `GET /api/managers/{id}` | TRAVELER | Public manager page, now with ratings and the upheld report count |

The gateway routes `/api/feedback/**`, `/api/reports/**` and `/api/admin/reports/**` to
engagement-service; `GET /api/feedback` needs TRAVEL_MANAGER there too.

## Feedback

**Eligibility.** A traveler may rate a travel when one of their subscriptions to it shows they
took part and the travel is over:

- the subscription is `COMPLETED`, or
- it is `CONFIRMED` and the travel's end date is before today *in the travel's own time zone*.

The second case covers the gap before the housekeeping job runs. Everything else is refused with
`409 FEEDBACK_NOT_ELIGIBLE`: no subscription, unpaid or failed ones, cancelled ones (by the traveler
or the manager), and travels that have not ended yet.

**How `COMPLETED` is reached.** The existing subscription housekeeping job
(`SubscriptionConfig.SubscriptionHousekeeping`, every 15 minutes) calls
`SubscriptionService.completeFinishedTravels()`. It moves every `CONFIRMED` subscription whose end
date is before the current date in the travel's time zone to `COMPLETED`, writing a history entry
and a `SubscriptionCompleted` event. Both rules read the injected `Clock`, so tests fix the time to
the minute (`FeedbackAndReportApiTest.housekeepingMarksEndedConfirmedSubscriptionsCompleted`: at
23:00 Athens time on the last day it stays `CONFIRMED`, at 00:30 the next day it is `COMPLETED`).

**Once only.** A unique `(traveler_id, travel_id)` constraint allows one feedback per traveler and
travel; a second one is `409 FEEDBACK_ALREADY_SUBMITTED`. A retry with the same `Idempotency-Key`
returns the first result instead.

**No editing.** Feedback cannot be edited or deleted by its author. That keeps ratings honest and
means no moderation-sensitive change can happen without a trace, so no extra history table is
needed. (Admin removal of abusive feedback is not part of Phase 6.)

**Who sees what.**

| Viewer | Sees |
| --- | --- |
| Author | Their own feedback (`/api/feedback/mine`) |
| Owning manager | Feedback on their travels with the author's display name. Another manager's `travelId` is `404` |
| Admin | All feedback, filterable by travel and manager |
| Any signed-in user | The manager page: average rating, rating count and the 10 latest ratings (travel, stars, comment, date), never the author |

## Reports

A report targets a `TRAVEL_MANAGER`, a `TRAVELER` or a `TRAVEL`, with a category (`FRAUD`,
`HARASSMENT`, `SAFETY`, `MISLEADING_LISTING`, `INAPPROPRIATE_CONTENT`, `OTHER`) and a description
of 10 to 1000 characters. Checks, in order:

1. Same `Idempotency-Key` as before: return that report.
2. Reporting yourself: `400 SELF_REPORT`. A manager reporting their own travel is also a self-report.
3. Already an open (`OPEN` or `IN_REVIEW`) report from you on the same target: `409 DUPLICATE_REPORT`.
   A unique `open_key` column (`reporter:type:target`, cleared when the report closes) makes this
   hold under concurrent requests too.
4. More than 10 reports in the last 24 hours: `429 TOO_MANY_REPORTS`.
5. The target must exist: user-service `GET /internal/managers/{id}` (active manager),
   `GET /internal/users/{id}/summary` (any user) or travel-service `GET /internal/travels/{id}`.
   Unknown: `404`. These calls use the internal clients (service token, `X-Request-ID`, `503
   DEPENDENCY_UNAVAILABLE` on outage). The target's display name is stored with the report so the
   moderation queue does not depend on those services.

**Moderation.** `OPEN -> IN_REVIEW -> RESOLVED | DISMISSED`. An admin may jump straight to a final
state. Every step needs a note (3 to 1000 characters), may assign the report to the acting admin,
and appends a row to `report_moderation`, which is only ever inserted into. `RESOLVED` and
`DISMISSED` are final (`409 REPORT_CLOSED`).

**Visibility.** Report content (description, reporter, history) is returned only by the
`/api/admin/reports` endpoints and, for the reporter's own reports, by `GET /api/reports` (which
leaves out the reporter ID and history). Everywhere else only a number appears: the count of
**upheld** (`RESOLVED`) reports against the person, on the public manager page and in the
subscriber summary a manager sees. Open or dismissed allegations are never counted publicly.

## Events

Written to `outbox_event` in the same transaction as the change: `FeedbackSubmitted` (IDs and the
rating), `ReportFiled` and `ReportModerated` (IDs, target type, category and status). Comments,
descriptions and names never leave the service through events.

## Protection against XSS and SQL injection

User text (feedback comments, report descriptions, moderation notes) is treated as data from end to
end. Both protections are proven by automated tests, see the table at the end.

**Stored as plain text with limits.** The API accepts text as a JSON string, validates its length
(`@Size`, matching the `VARCHAR` sizes in the migration) and trims it. It does not try to "clean"
HTML: a comment containing `<script>` is kept exactly as typed, and it stays harmless because
nothing ever interprets it as HTML or SQL.

**SQL injection: parameterised queries only.** Every query in `FeedbackStore`, `ReportStore` and
`SubscriptionStore` uses `JdbcTemplate` with `?` placeholders. The SQL text is fixed in the code and
the user's value travels separately to PostgreSQL, which never parses it as SQL. Optional filters
add fixed clauses such as `"travel_id = ?"`, never user text. Query parameters are also typed:
`status`, `targetType`, `travelId` and `managerId` must parse as an enum or UUID, otherwise the
request is rejected with `400 VALIDATION_ERROR` before any SQL runs.

**XSS: output is JSON, rendered as text.** The services answer `application/json` with
`X-Content-Type-Options: nosniff` (Spring Security default), so a browser never treats a response as
a page. The React UI shows user text with `{value}` in JSX, which React escapes, so `<script>`
appears on screen as the characters `<script>`. The code base never uses `dangerouslySetInnerHTML`
(`grep -rn dangerouslySetInnerHTML admin-ui/src` finds nothing). A restrictive Content Security
Policy for the UI is Phase 11 work.

| What is proven | Test |
| --- | --- |
| `<script>…</script><img onerror=…>` and `'; DROP TABLE feedback; -- ' OR '1'='1` are stored and returned unchanged as JSON strings; the table still exists | `FeedbackAndReportApiTest.scriptAndSqlPayloadsAreStoredAndReturnedAsInertText` |
| SQL-looking query parameters are rejected with 400 | same test |
| The public manager page returns a `<script>` comment as plain JSON text | `ManagerPageTest.showsRatingsWithoutAuthorsAndCountsOnlyUpheldReports` |
| The UI renders the payload as text: no `<script>` or `<img>` element is created, nothing runs | `admin-ui/src/features/feedback/Feedback.test.tsx` |

## UI

- Traveler (every role outside the admin workspace): "Feedback on your trips" lists finished trips
  without feedback and the feedback already given; "My reports" lists their reports and has a form
  to report a traveler, manager or travel by ID. A travel's detail has "About the organiser" (the
  manager page with ratings, report count and "Report this manager") and "Report this travel".
- Manager: "Feedback on your travels", filterable by travel, and a "Report" form on a subscriber's
  profile.
- Admin: "Feedback" (all feedback) and "Reports" (the moderation queue) sections.
