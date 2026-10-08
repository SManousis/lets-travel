# Security

How Let's Travel protects accounts, personal data, payments and operations, what is verified by tests, and
the gaps that remain. Personal-data handling is in [privacy](privacy.md); runtime behaviour under failure is in
[operations](operations.md).

## 1. Identity and sessions

| Control | Implementation | Verified by |
| --- | --- | --- |
| Password storage | BCrypt, cost 12 (`auth.password.bcrypt-cost`), a random salt per password; plaintext is never stored or logged | `AuthProvisioningTest.passwordsAreStoredAsSaltedBcryptAndOldHashesAreUpgradedAtSignIn` |
| Upgrade path | A hash made with a lower cost is re-hashed at the current cost on the next successful sign-in | same test |
| Password rules | At least 10 characters, at most 72 bytes (BCrypt's limit) | `AuthProvisioningTest.passwordsLongerThan72BytesAreRejectedNotCrashed`, `AccountFlowTest` |
| Access tokens | HS256 JWT, 15 minutes (`ACCESS_TOKEN_TTL_SECONDS`), issuer, audience, subject, role, session and token IDs; the signing secret has at least 32 characters | `AccessTokenCodecTest` |
| Sessions | Every request is checked against the session in auth-service, so logout, role changes, disabling and deletion end access at once | `AuthProvisioningTest`, `GatewayAuthorizationTest` |
| Refresh | 12-hour refresh token in a `Secure`, `HttpOnly`, `SameSite=Strict` cookie scoped to the auth endpoints, rotated on every use; refresh also needs a matching CSRF token and an allowed `Origin` | `AuthProvisioningTest.refreshRotatesWithCookieCsrfHeaderAndAllowedOrigin`, `refreshRejectsMissingOriginForeignOriginAndBadCsrf` |
| Browser storage | The access token is kept in `sessionStorage` (cleared with the tab and at expiry), never in `localStorage` or a readable cookie | `App.test.tsx` |
| Sign-in throttling | A failed sign-in blocks that username for 10 seconds; unknown users take as long as known ones | not covered by an automated test yet |
| Roles | TRAVELER < TRAVEL_MANAGER < ADMIN, checked at the gateway and again in every service; other people's records answer 404 | `AuthorizationMatrixTest` in every service, `GatewayRoleMatrixTest` |

## 2. Input handling and injection

- **SQL.** Every statement uses `?` parameters. Clauses that are assembled (optional filters) only join fixed
  strings; values are never concatenated. Regression: `FeedbackAndReportApiTest.scriptAndSqlPayloadsAreStoredAndReturnedAsInertText`.
- **Cypher.** Every value is a `$parameter`; only constant labels are concatenated (schema setup).
- **Elasticsearch.** Queries are built as data (`multi_match`, `term`, `range`), never with `query_string`, so
  search text cannot change the query. Regression: `QueryInjectionTest`.
- **Validation.** Bean Validation on every request body (lengths, enums, ranges); pages are limited to 100
  items; unknown enum values answer `400`.
- **Request size.** The TLS proxy and the UI server accept at most 256 KB per request; the gateway rejects a
  larger `Content-Length` with `413` (`gateway.limits.max-body-bytes`). Regression: `RequestLimitsTest`.
- **Rate limits** (gateway, per signed-in user, or per client address for anonymous endpoints):

  | Endpoint | Limit |
  | --- | --- |
  | `GET /api/search/travels` | 120 per minute |
  | `GET /api/search/autocomplete` | 300 per minute |
  | `POST /api/reports` | 20 per hour (and 10 per day in engagement-service) |
  | `POST /api/registrations` | 10 per hour per address |
  | `POST /api/password-setup` | 20 per hour per address |
  | `POST /api/auth/login` | per-username back-off in auth-service |

  Excess requests get `429` with `Retry-After`. Counters live in each gateway replica, so N replicas allow up to
  N times the limit (see [operations](operations.md#scaling-and-its-limits)).

## 3. Browser protections (XSS, framing)

- React renders all user-written text as text. The code uses no HTML-injection API (`innerHTML`,
  `dangerouslySetInnerHTML`, `eval` and the like); `security.test.tsx` fails the build if one appears and renders
  hostile names and comments to check they stay inert.
- The UI server sends `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  object-src 'none'; frame-ancestors 'none'; …`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Frame-Options: DENY` and a restrictive `Permissions-Policy` (`admin-ui/nginx.conf`). The TLS proxy adds
  `Strict-Transport-Security` and hides the server version (`infra/proxy/nginx.conf`).
- Dependency integrity: `package-lock.json` pins every npm package with its hash; Maven downloads are checked
  against repository checksums.

## 4. Transport security

The secure overlay (`infra/compose/compose.secure.yml`) adds:

| Path | Status |
| --- | --- |
| Browser → TLS proxy | TLS 1.2/1.3 with the deployment's certificate, HSTS |
| travel-service → Neo4j | Bolt over TLS (`bolt+ssc`): encrypted, but the certificate is **not verified** yet |
| Services → PostgreSQL | Server has TLS on; clients use the driver default (`sslmode=prefer`): encrypted when offered, **not verified** |
| Services → Vault | TLS |
| Services → OTLP collector | Private network only |
| Service ↔ service, gateway → services, search-service → Elasticsearch | Plain HTTP on the private `backend` network, authenticated with the internal service token |

**Gap:** verified TLS on every internal path needs the development CA in each service's trust store, then
`bolt+s://` for Neo4j, `sslmode=verify-full&sslrootcert=…` for PostgreSQL and HTTPS between services. Until then the
internal network must stay private (no published ports except the proxy).

## 5. Secrets and least privilege

- No secrets in the repository. Local and CI secrets live in ignored files (`.ci-secrets/`, `.env*`); the CI
  pipeline receives them as the Jenkins credential `lets-travel-release-env`. Production values come from Vault
  or the platform's secret store; `infra/vault/policies/` gives each service read access to its own path only.
- Each service has its own database and database user (`infra/postgres/init/001-databases.sh`); `PUBLIC` has no
  rights on any of them.
- Internal endpoints (`/internal/**`) need the internal service token and are not routed by the gateway;
  management endpoints listen on a separate private port.
- Rotation: change the value in the secret store and restart the service. Rotating `AUTH_TOKEN_SECRET` signs
  everyone out (their tokens no longer verify), which is the intended effect after a leak.

## 6. Audit records

Privileged and destructive actions are recorded by `AuditLog` (service-common): actor and role from the signed-in
caller (`SYSTEM` for service calls), action, target, time, request ID and non-sensitive details. Each record goes
to the `AUDIT` log and to the service's store; if the store write fails, the action fails.

| Service | Actions | Store |
| --- | --- | --- |
| user-service | `USER_CREATED`, `USER_UPDATED` (role, status, e-mail change), `USER_DELETED` | `audit_event` table |
| engagement-service | `REPORT_MODERATED`, `SUBSCRIBER_REMOVED`, `ACCOUNT_DATA_CLEANED` | `audit_event` table |
| payment-service | `PAYMENT_GATEWAY_CREATED/UPDATED/DELETED`, `PAYMENT_METHOD_DELETED`, `REFUND_REQUESTED` | `audit_event` table |
| travel-service | `TRAVEL_CREATED` (owner, assigned by an admin), `TRAVEL_DELETED`, `DESTINATION_DELETED` | `AuditEvent` nodes |

On PostgreSQL, triggers reject every `UPDATE`, `DELETE` and `TRUNCATE` of `audit_event`
(`db/vendor/postgresql/*_audit_events_append_only.sql`), even for the service's own database user. Neo4j Community
cannot protect one label, so for travel-service the `AUDIT` log copy (collected centrally) is the tamper-evident
record. Report moderation also keeps its own immutable history with the moderator's note.

## 7. Scans and triage

Run on 2026-10-08 against commit `9570f33` plus this change:

| Scan | Tool | Result | Decision |
| --- | --- | --- | --- |
| Secrets in git history | gitleaks 8.28 | 2 matches | Both false positives: a test-only signing key in `AccessTokenCodecTest` and the Elasticsearch health check, which reads the password from an environment variable. Recorded in `.gitleaksignore`. |
| npm dependencies | `npm audit` | 0 | — |
| Java dependencies | CycloneDX SBOM + osv-scanner 2.2 | 3 critical (Tomcat 11.0.24), 10 high and 4 medium (Jackson 3.1.5 and 2.21.5) | Fixed: Tomcat 11.0.25, Jackson 3.1.7 and 2.21.7 pinned in the root `pom.xml`; rescan shows 0. Remove the pins when Spring Boot ships these versions. |
| Static analysis | SonarQube (CI quality gate) | per pipeline run | New issues block the merge. |
| Container images | — | not yet run | Planned with the CI security stage (Phase 12): Trivy on every built image, failing on fixable high/critical findings. |

Reproduce:

```sh
docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.28.0 git /repo --redact
(cd admin-ui && npm audit)
./mvnw -q org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DoutputName=bom
docker run --rm -v "$PWD/target:/sbom" ghcr.io/google/osv-scanner:v2.2.3 scan source /sbom
```

## 8. Threat scenarios

| Scenario | Defence | Test |
| --- | --- | --- |
| Stolen access token | Expires in 15 minutes; logout, disabling or deletion revokes it at once | `AuthProvisioningTest` |
| Stolen refresh cookie used from another site | `SameSite=Strict`, CSRF token and origin check, rotation | `AuthProvisioningTest` |
| Traveler reads another traveler's data | Ownership checks answer 404 | `AuthorizationMatrixTest`, `TravelerOwnershipTest` |
| Manager edits another manager's travel | Ownership check in travel-service | travel `AuthorizationMatrixTest` |
| Script in a comment or name | Rendered as text, CSP blocks inline script | `security.test.tsx`, `FeedbackAndReportApiTest` |
| SQL or search injection | Parameters and structured queries | `FeedbackAndReportApiTest`, `QueryInjectionTest` |
| Scripted search, sign-up or report flooding | Gateway rate limits | `RequestLimitsTest` |
| Oversized requests | 256 KB limit | `RequestLimitsTest` |
| Payment amount tampering | The browser sends only the subscription and method IDs; the amount comes from the server | `SubscriptionPaymentApiTest.anotherUsersMethodOrSubscriptionIsNotFoundAndNothingIsCharged` |
| Forged payment webhook | Provider signature verified before any change | `SubscriptionPaymentApiTest.onlyAVerifiedFreshWebhookCanResolveAPayment` |
| Admin covers their tracks | Append-only audit table with database triggers | `FeedbackAndReportApiTest` (audit rows), migration triggers |
| Calls to internal endpoints from outside | Not routed by the gateway; service token required | `GatewayRoutingTest`, `AuthorizationMatrixTest` |
