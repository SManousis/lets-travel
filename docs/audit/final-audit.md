# Final audit

Every mandatory audit question, where it is explained, how it is checked, and the latest result. Automated checks
come from `scripts/audit/audit.mjs` (results in [results/](results/)), the CI pipeline ([CI](../ci.md)) and the test
suites; the rest are walked through by hand and recorded in [evidence](evidence.md).

## Preparing a run

1. Merge everything into `main` and let Jenkins deploy it (the deploy host serves `https://localhost:8443`).
   Avoid running builds during the audit: they share the machine with the deployment and slow it down.
2. Seed the demo data ([demo](../demo.md)).
3. Run `scripts/audit/audit.mjs` with `RELEASE=<deployed commit>`; it writes `results/<date>-<commit>.md`.
4. Walk the manual journeys below and record them in [evidence](evidence.md).

## Questions

| # | Audit question | Explained in | How it is checked | Kind |
| --- | --- | --- | --- | --- |
| 1 | How Elasticsearch implements search and autocomplete | [search](../search.md) | Walkthrough of the index mapping, query tiers and suggestions | Explain |
| 2 | How Neo4j personalises recommendations from at least three travel fields | [recommendations](../recommendations.md) | Destination, activity category, transport and accommodation weights; `RecommendationEngineTest` | Explain |
| 3 | How services run and scale independently | [architecture](../architecture.md), [operations](../operations.md#scaling-and-its-limits) | `compose.scale.yml`, `drill.mjs --only replica` | Explain + manual |
| 4 | How PostgreSQL, Neo4j and Elasticsearch stay consistent and recover | [events](../events.md), [operations](../operations.md#parked-events) | Outbox/inbox, versioned writes, rebuilds, reconciliations; `EventRelayTest`, `drill.mjs --only restart` | Explain + manual |
| 5 | Role functionality and permissions | [roles](../roles.md) | `AuthorizationMatrixTest` in every service, `GatewayRoleMatrixTest`; audit.mjs "Role-based access" | Explain + automated |
| 6 | Accurate search results and prompt autocomplete | [search](../search.md) | audit.mjs "Search", "Autocomplete" (exact, typo, under 1 s); `TravelSearchIntegrationTest` | Automated |
| 7 | Two accounts get different personalised recommendations | [demo](../demo.md#the-recommendation-comparison) | audit.mjs "Recommendations" (Anna vs Ben) | Automated |
| 8 | Complete admin overview | [dashboards](../dashboards.md) | audit.mjs "Admin overview"; `DashboardCalculatorTest` | Automated + manual (Overview screen) |
| 9 | Helpful travel manager statistics | [dashboards](../dashboards.md) | audit.mjs "Manager statistics"; Analytics screen | Automated + manual |
| 10 | Traveler recommendations and history | [recommendations](../recommendations.md) | Home and My statistics screens; audit.mjs "Traveler statistics" | Automated + manual |
| 11 | Easy travel navigation and subscription | [subscriptions](../subscriptions.md) | audit.mjs "Subscription"; Find and book screen | Automated + manual |
| 12 | Three-day cancellation cutoff | [subscriptions](../subscriptions.md) | `CancellationPolicyTest` (boundaries in the travel's time zone), `SubscriptionApiTest` | Automated (tests) |
| 13 | Secure, user-friendly, multi-method payments | [payments](../payments.md) | audit.mjs "Payments"; `SubscriptionPaymentApiTest` (Stripe, PayPal, test card, timeouts, webhooks); payment-sim stack for Stripe/PayPal | Automated + manual |
| 14 | Traveler feedback submission and visibility | [feedback and reports](../feedback-and-reports.md) | audit.mjs "Feedback" (refused before the trip ends); `FeedbackAndReportApiTest`; manager page | Automated + manual |
| 15 | Manager travel-listing management | [roles](../roles.md) | audit.mjs "Listing management"; Travels screen | Automated + manual |
| 16 | Manager subscriber-list interaction | [subscriptions](../subscriptions.md) | audit.mjs "Subscriber list"; Subscribers screen (remove with reason) | Automated + manual |
| 17 | Manager listing analytics | [dashboards](../dashboards.md) | Analytics screen per travel | Manual |
| 18 | Detailed traveler profile and statistics | [dashboards](../dashboards.md), [privacy](../privacy.md#3-access-and-export) | My statistics, Download my data | Manual |
| 19 | Secure, straightforward login | [security](../security.md#1-identity-and-sessions) | audit.mjs "Secure login"; `AuthProvisioningTest` | Automated |
| 20 | Correct role-based access control | [roles](../roles.md) | audit.mjs "Role-based access"; matrix tests | Automated |
| 21 | TLS-secured transmission | [security](../security.md#4-transport-security) | audit.mjs "TLS"; known gaps on internal links are documented | Automated + explain |
| 22 | Secure credentials and sensitive data | [security](../security.md#5-secrets-and-least-privilege), [payments](../payments.md) | CI secret scan (gitleaks), no card data stored, Vault policies | Automated (CI) |
| 23 | High traffic, actions under five seconds | [operations](../operations.md#load-tests) | audit.mjs "Under five seconds"; CI post-deploy smoke; `load.mjs --scenario all` on a quiet machine | Automated + manual |
| 24 | Fallback when a service fails | [operations](../operations.md#fallbacks-when-a-dependency-fails) | `drill.mjs` (search, Neo4j, payments); `SearchUnavailableTest`, `ManagerPageOutageTest`, `GatewayResilienceTest` | Manual + tests |
| 25 | Responsive UI and easy navigation per role | [browser checks](browser-checks.md) | Chrome and Firefox at 1440/768/390 px for each role | Manual |
| 26 | Data protection and privacy | [privacy](../privacy.md) | Account deletion (`UserDeletionServiceTest`, `FeedbackAndReportApiTest`), data export (`MyData.test.tsx`) | Automated + explain |
| 27 | Readable, well-separated code | [architecture](../architecture.md), README conventions | SonarQube quality gate, Maven Enforcer module rules, reviewer reading of random code paths | CI + review |
| 28 | SQL injection and XSS protection | [security](../security.md#2-input-handling-and-injection) | audit.mjs "SQL/query injection", "XSS protection"; `QueryInjectionTest`, `security.test.tsx` | Automated |
| 29 | Password hashing | [security](../security.md#1-identity-and-sessions) | `AuthProvisioningTest.passwordsAreStoredAsSaltedBcryptAndOldHashesAreUpgradedAtSignIn` | Automated (tests) |

Bonus items (PWA, multilingual, innovative feature) are not implemented.

## Known limitations to state during the audit

- Databases run as single instances; application replicas add capacity, not database availability.
- Internal TLS is encrypted but not certificate-verified on the Neo4j and PostgreSQL links; service-to-service
  HTTP is plain inside the private network.
- The demo deployment shares one machine with Jenkins and SonarQube; response times there depend on what else runs.
