# Browser walkthrough (Phase 9)

Run the web app through the gateway in current Chrome and Firefox at desktop (1440px), tablet (768px) and phone
(390px) widths, once per role. Record each run in the matrix below. Automated coverage: component tests in
`admin-ui/src` (route guards, navigation per role, listing editor, dashboards, autocomplete keyboard and abort,
payment, feedback, reports); there are no browser E2E tests yet.

## Matrix

| Date | Browser and version | Device / width | Role | Journeys | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| | Chrome | 1440 / 768 / 390 | Traveler | T1–T6 | Pending | |
| | Firefox | 1440 / 768 / 390 | Traveler | T1–T6 | Pending | |
| | Chrome | 1440 / 768 / 390 | Travel manager | M1–M5 | Pending | |
| | Firefox | 1440 / 768 / 390 | Travel manager | M1–M5 | Pending | |
| | Chrome | 1440 / 768 / 390 | Admin | A1–A6 | Pending | |
| | Firefox | 1440 / 768 / 390 | Admin | A1–A6 | Pending | |

## Journeys

Traveler (sign up at `#/register`, or use a seeded traveler):

- **T1** Navigation shows only Home, Find and book, My feedback, My reports and My statistics. Opening
  `#/users` or `#/listings` shows "Not available for your role".
- **T2** Search: type two letters, move through suggestions with the arrow keys, pick one with Enter, close with
  Escape. A screen reader announces the number of suggestions. Filters and paging work.
- **T3** Open a result, subscribe, pay with the test card (approve), see Confirmed. Repeat with decline and timeout
  ("Check payment status"). Double-clicking Subscribe or Pay creates one subscription and one payment.
- **T4** Cancel a confirmed subscription before the cut-off and see the refund message.
- **T5** After a travel has ended, leave feedback once; report a travel and a manager; see the reports under My reports.
- **T6** My statistics shows trips, cancellations, feedback, reports and preferred payment methods. Recommendations
  appear on Home.

Travel manager:

- **M1** Navigation adds Travels, Subscribers, Travel feedback and Analytics; admin screens are refused.
- **M2** Create a travel with price, places, description, stops, activities, stays and transport; publish it; see
  "cannot be published yet" when a required field is missing; edit, unpublish and delete.
- **M3** See subscribers of own travels and remove one with a reason.
- **M4** Analytics: income by month for 3/6/12/24 months, cancellation rate, rating and per-travel figures.
- **M5** Traveler journeys T2–T6 also work for a manager.

Admin:

- **A1** Lands on Overview: totals, income by month, top managers and travels, and the income check.
- **A2** Manager rankings show the score and its components; Travel history pages.
- **A3** Users: create a manager with a set-password link, change roles, disable.
- **A4** Travels: create a travel for a chosen manager; maintain destinations.
- **A5** Reports: moderate a report through IN_REVIEW to RESOLVED or DISMISSED.
- **A6** Payments: gateways and payment methods.

Every width and browser:

- No horizontal page scroll; wide tables scroll inside their own frame; no action is hidden or clipped.
- Tab order follows the page; focus is always visible; "Skip to content" appears on the first Tab; after
  choosing a navigation link, focus moves to the main region.
- Stop the search service and check the "Search is temporarily limited" fallback; stop engagement-service and check
  that dashboards say "temporarily unavailable" with Try again while other screens keep working.
