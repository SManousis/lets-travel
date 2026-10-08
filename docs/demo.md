# Demo data

`scripts/demo/seed.mjs` creates the same demo world on any deployment, through the public API only. Nothing personal
or secret is committed: every address is under `example.test`, and the password is whatever you pass in
`DEMO_PASSWORD`.

```sh
DEMO_PASSWORD='choose-a-password' ADMIN_EMAIL=<admin e-mail> ADMIN_PASSWORD=<admin password> \
  BASE_URL=https://localhost:8443 INSECURE_TLS=1 node scripts/demo/seed.mjs
```

`INSECURE_TLS=1` accepts the local development certificate; leave it out for a deployment with a trusted
certificate. Running the script again finds what exists and changes nothing.

## Accounts

| Role | Sign in with | Purpose |
| --- | --- | --- |
| Admin | `ADMIN_USERNAME` of the deployment | Overview, rankings, moderation, users, payments |
| Travel manager | `maria.manager@example.test` | Owns Florence, Rome and Lisbon |
| Travel manager | `nikos.manager@example.test` | Owns Rhodes, Crete and the Swiss Alps |
| Traveler | `anna.culture@example.test` | Booked and paid Florence (culture, food, hotel, train) |
| Traveler | `ben.beach@example.test` | Booked and paid Rhodes (beach, sport, resort, plane) |

All demo accounts use `DEMO_PASSWORD`.

## Travels

Six published travels starting 40 to 65 days after the seed date (so they stay bookable and inside the
cancellation window), each with a destination stop, two categorised activities, a categorised stay and a transport:
Florence art and food, Rome museums by rail, Rhodes beach week, Crete sun and sea, Swiss Alps hiking, Lisbon city
lights.

## The recommendation comparison

Anna and Ben have opposite histories. Signed in as each (Home screen, or `GET /api/recommendations`):

- **Anna** gets Rome first, explained by culture activities, train travel and hotels from her Florence trip.
- **Ben** gets Crete first, explained by beach activities, plane travel and resorts from his Rhodes trip.
- A new account with no trips gets **popular** travels instead.

Bookings are paid with the simulated test card, so the deployment needs `PAYMENT_TEST_PROVIDER_ENABLED=true`
(an Ansible setting; off unless the release environment sets it). Without it the bookings stay unpaid and do not
count as history.

## Feedback

Feedback needs a finished trip, and the API never creates past trips. To show it, a manager creates a short travel
starting tomorrow, a traveler books it, and the feedback form appears the day after it ends; or use the
`FeedbackAndReportApiTest` fixtures, which set dates directly in the test database.
