# Phase 6 verification: feedback, reports and moderation

Verified on 2026-10-07 on branch `phase-5-6`: base commit `1757a07` plus the uncommitted Phase 5
and Phase 6 changes. Environment: macOS with Docker Desktop, OpenJDK 21, Node and the Maven Wrapper.

Design: [feedback and reports](../feedback-and-reports.md). Contracts:
[engagement.yaml](../api/engagement.yaml) and [dashboards.yaml](../api/dashboards.yaml) (manager page).

Subject: travelers *provide feedback on participated travels* and *report Travel Managers or other
travelers*; travelers see a manager page with *statistics, past travel ratings and the number of
reports*; managers *view feedback specific to their organized travels*; admins *access feedbacks*
and *review reports filed by travelers against travels or managers*.

## Design in one paragraph

- **Eligibility.** Feedback needs a subscription to the travel that is `COMPLETED`, or `CONFIRMED`
  with the end date passed in the travel's time zone. One feedback per traveler and travel; no edits.
- **How `COMPLETED` is reached.** The subscription housekeeping job (every 15 minutes) marks
  `CONFIRMED` subscriptions `COMPLETED` once the travel's last local day has passed. It reads the
  injected `Clock`.
- **Reports.** Against a manager, a traveler or a travel. No self-reports, the target must exist
  (user-service or travel-service internal call), one open report per reporter and target, at most
  10 per reporter per 24 hours.
- **Visibility.** Report content is shown only to admins and to the reporter for their own reports.
  Public views (manager page, subscriber summary) show only the count of upheld (`RESOLVED`) reports.
  Public ratings never name their author.
- **Moderation.** `OPEN -> IN_REVIEW -> RESOLVED | DISMISSED`, each step with a note and an optional
  self-assignment, recorded in the append-only `report_moderation` table.

## Automated checks

All commands were run from the repository root. Maven was prefixed with
`env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=...`.
Logs were written to files and grepped.

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS. All nine modules `BUILD SUCCESS`; 327 tests, 0 failures, 0 errors, 0 skipped. engagement-service 43 (was 32), api-gateway 68 (was 56). |
| `npm run lint:api` | PASS (exit 0). All 8 contracts valid; the 1 ignored problem is pre-existing. |
| `npm --prefix admin-ui run test:ci` | PASS. 8 files, 35 tests (7 new in `Feedback.test.tsx` and `Reports.test.tsx`). |
| `npm --prefix admin-ui run lint` | PASS (exit 0). |
| `npm --prefix admin-ui run build` | PASS (exit 0). |
| `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet` | PASS (exit 0). No compose change was needed: the new endpoints live in engagement-service. |

One problem was found and fixed during verification: two moderation steps with the same timestamp
could come back in the wrong order. History rows now carry a per-report `step` number.

## What the tests prove

| Audit question or plan item | Test |
| --- | --- |
| Feedback refused for nonparticipants and cancelled subscriptions; accepted for completed trips; second rating refused; retry with the same key is safe | `FeedbackAndReportApiTest.onlyFinishedParticipationsMayBeRatedAndOnlyOnce` |
| A confirmed trip becomes rateable after its last day in the travel's time zone (23:00 vs 00:30 Athens) | `FeedbackAndReportApiTest.confirmedTravelBecomesEligibleAfterItsLastLocalDay` |
| Housekeeping marks ended confirmed subscriptions `COMPLETED`, with an injected clock | `FeedbackAndReportApiTest.housekeepingMarksEndedConfirmedSubscriptionsCompleted` |
| Rating 1 to 5, comment at most 1000 characters, `Idempotency-Key` required | `FeedbackAndReportApiTest.feedbackInputIsValidated` |
| Managers see only their own travels' feedback (another manager's travel gives 404); admins see all; travelers get 403 on the list; travelers see their own history | `FeedbackAndReportApiTest.managersSeeFeedbackOnTheirOwnTravelsAndAdminsSeeAll` |
| Self-reports, unknown targets, invalid type and short description refused; duplicate open report refused; reports on travels and travelers accepted | `FeedbackAndReportApiTest.reportsRejectSelfInvalidTargetsAndDuplicates` |
| At most 10 reports per 24 hours | `FeedbackAndReportApiTest.travelersMayFileAtMostTenReportsPerDay` |
| Report content only for the reporter and admins | `FeedbackAndReportApiTest.reportContentIsVisibleOnlyToTheReporterAndAdmins` |
| Queue filters and ordering, assignment, notes, immutable history, final states, upheld count in the subscriber summary | `FeedbackAndReportApiTest.adminsWorkTheQueueWithAnImmutableHistory` |
| XSS and SQL-injection payloads stored and returned as inert text; injected query parameters rejected | `FeedbackAndReportApiTest.scriptAndSqlPayloadsAreStoredAndReturnedAsInertText` |
| Manager page: average rating, rating count, recent ratings without authors, only upheld reports counted | `ManagerPageTest.showsRatingsWithoutAuthorsAndCountsOnlyUpheldReports` |
| Every endpoint has a role decision and enforces it when called directly | `AuthorizationMatrixTest` (engagement), `GatewayRoleMatrixTest`, `GatewayRoutingTest` |
| UI: eligible trips only, one idempotency key across retries, manager feedback view, manager page, report form, own reports, moderation | `Feedback.test.tsx`, `Reports.test.tsx` |
| UI renders `<script>` and `<img onerror>` as text; no element is created and nothing runs | `Feedback.test.tsx` |

## XSS and SQL injection: how to prove it to an auditor

1. **SQL injection.** Open `FeedbackStore.java` or `ReportStore.java`: every query is a constant
   string with `?` placeholders, and values are passed separately to `JdbcTemplate`. Run
   `grep -n '" + ' engagement-service/src/main/java/com/travelplan/engagement/*Store.java`: string
   concatenation only joins column lists and fixed clauses, never request values. Then run the
   test `scriptAndSqlPayloadsAreStoredAndReturnedAsInertText`: the text
   `'; DROP TABLE feedback; -- ' OR '1'='1` is stored and read back unchanged, and the table still exists.
2. **XSS.** Run `grep -rn dangerouslySetInnerHTML admin-ui/src`: no results. The UI prints user
   text with `{value}`, which React escapes. `Feedback.test.tsx` renders a comment containing
   `<script>` and `<img onerror=...>` and checks that no `script` or `img` element exists and no
   code ran. The API answers `application/json` with `X-Content-Type-Options: nosniff` (asserted in
   the API test).

## Auditor demo script

Start the stack as described in `docs/development.md` and sign in through the UI. Use three
accounts: an admin, a manager *M* with at least one travel *T*, and a traveler *A*. A second
traveler *B* is optional.

1. **Make a finished trip.** As *A*, subscribe to *T* and pay with the test provider. To avoid
   waiting for the end date, mark the subscription completed in the database:
   `UPDATE subscription SET status = 'COMPLETED' WHERE travel_id = '<T>' AND traveler_id = '<A>';`
   In production the housekeeping job does this after the travel's last day.
2. **Leave feedback (audit: "Can travelers submit feedback easily?").** As *A*, scroll to
   "Feedback on your trips". *T* is listed. Click "Leave feedback", choose 4 stars and type
   `<script>alert(1)</script> great guide`. Click "Send feedback". It appears under "Feedback you
   gave", and the script text is shown as plain characters. No alert opens. Try once more through
   the API: `409 FEEDBACK_ALREADY_SUBMITTED`.
3. **Ineligible feedback.** As *A*, `POST /api/feedback` for a travel *A* has not finished:
   `409 FEEDBACK_NOT_ELIGIBLE`.
4. **Manager page.** As *A*, open *T* in "Available travels" (or from search) and click "About the
   organiser". It shows completed and total travels, the average rating, the recent ratings without
   names, and the upheld report count.
5. **Manager quality control (audit: "Is feedback accessible to managers and admins?").** As *M*,
   "Feedback on your travels" shows *A*'s rating with *A*'s name. Another manager calling
   `GET /api/feedback?travelId=<T>` gets 404.
6. **Report.** As *A*, on the manager page click "Report this manager", choose "Fraud or scam",
   describe it and send. "My reports" shows it as Open. Sending the same report again gives
   `DUPLICATE_REPORT`. Reporting yourself (use your own user ID in "File a report") gives
   `SELF_REPORT`.
7. **Moderation.** As the admin, open "Reports". The report is in the queue. Select it, choose
   "In review", tick "Assign to me", add a note and save. Then choose "Upheld (resolve)" with a
   note. The history shows both steps. The report is now closed.
8. **Count, not content.** As *A*, reopen the manager page: "Upheld reports" is now 1. No
   description or reporter is visible. As the admin, the "Feedback" section lists all feedback.
9. **Traveler profile (audit: past participations, feedback given, reports made).** As *A*: "My
   subscriptions" (participations and cancellations), "Feedback you gave" and "My reports" together
   show the traveler's history. As *M*, "View profile" on a subscriber shows completed trips and the
   upheld report count. The full personal statistics dashboard is Phase 8.

## Left for later phases

- Personal statistics and dashboard projections (Phase 8); public traveler pages do not exist yet.
- Publishing outbox events to consumers (event pipeline), as in Phases 3 to 5.
- Admin removal of abusive feedback, and the restrictive Content Security Policy (Phase 11).
