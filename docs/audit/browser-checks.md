# Browser walkthrough

Run the dashboard through the gateway at desktop (1440px), tablet (768px), and phone (390px) widths in current Chrome and Firefox. Record browser version, date, environment, and result beside each run before the audit review. Focused UI unit tests and a limited local smoke are recorded, but the full browser matrix below remains pending. Use the [manual testing guide](manual-testing-guide.md#3-dashboard-crud-in-chrome-and-firefox) to record results.

- Login with an administrator; reject wrong credentials; sign out; verify an expired or revoked token returns to sign in. Confirm section navigation and keyboard focus.
- Create, page through, edit, disable/delete, and retry deletion of users. Create, edit, and delete traveler profiles for an active user. Check validation, conflicts, and dependency failures.
- Create, edit, and delete destinations. Create a travel with ordered stops, activities, accommodation, and transport; edit the whole itinerary; add/remove travelers; delete the travel. Confirm a destination in use shows a conflict.
- Create, edit, and delete gateways. Connect an externally tokenized sandbox method, edit its label and default preference, inspect pending/failed status, and remove it. Verify retries use the same idempotency key for the same request. Provider sandbox checks require credentials and customer ownership mapping.
- At each width, check readable lists/forms, wrapped controls, no horizontal clipping, required-field messages, and visible keyboard focus. Repeat the workflow in both browsers.
