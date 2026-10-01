# Build, Security, and Data Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Restore the documented backend build and deny revoked access tokens at the gateway and every business-service boundary.

**Architecture:** Auth service owns each issued session in PostgreSQL and exposes a protected internal validation endpoint. Gateway and the three business services validate token structure locally, then use that endpoint for the revocation decision. A failed validation call is a `503`; invalid or revoked tokens produce `401`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC/WebFlux, Spring JDBC/Flyway, JUnit 5, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-09-30-build-security-data-fixes-design.md`

**Execution note (2026-09-30):** The steps below are complete on `build-security-data-fixes`. The servlet validation clients use JDK `HttpClient` with explicit timeouts rather than the planned `RestClient`. Identity UUID/role persistence and lifecycle synchronization were added after tests exposed a deletion-revocation gap. Review then added transactional login/identity changes, current-role validation, and an upgrade reset procedure for existing users. Auth session persistence and the internal validation endpoint were committed together. Verification evidence is in `docs/audit/project-audit-2026-09-29.md`.

## Global Constraints

- Retain separate service databases; gateway and business services must never query the auth database.
- Do not log bearer tokens, internal credentials, passwords, or session IDs.
- Perform local signature, expiry, and role validation before remote revocation checks.
- Return `401` for invalid/revoked tokens and `503` when auth-service validation is unavailable.
- Retain an in-memory default-profile session store for unit tests without a database.

---

## File structure

| File | Responsibility |
|---|---|
| `mvnw.cmd` | Starts Maven under PowerShell 7 when `.m2` is a normal directory. |
| `user-service/src/main/resources/application.yml` | Supplies one valid default user-service mapping. |
| `auth-service/.../TokenSessionStore.java` | Persists issued/revoked sessions using JDBC, with an in-memory fallback. |
| `auth-service/.../TokenService.java` | Issues and verifies signed tokens through the store. |
| `auth-service/.../InternalTokenValidationController.java` | Validates tokens for internal callers. |
| `*/.../security/AuthTokenValidator.java` | Calls auth-service for the authoritative revocation decision. |
| `api-gateway/.../GatewaySecurityConfig.java` | Applies the same decision before gateway routing. |

### Task 1: Restore the documented backend build

**Files:**
- Modify: `mvnw.cmd`
- Modify: `user-service/src/main/resources/application.yml`
- Test: `user-service/src/test/java/com/travelplan/users/StartupTest.java`

**Produces:** valid `spring.autoconfigure`, `security.token-secret`, and `security.internal-service-token` configuration properties.

- [x] **Step 1: Capture the failures**

Run `./mvnw.cmd -B -ntp -pl user-service test`. Confirm the PowerShell wrapper fails before Maven runs. Run the cached Maven command directly and confirm Spring rejects the duplicate top-level `security` key.

- [x] **Step 2: Repair the wrapper**

Replace:

```powershell
if ((Get-Item $MAVEN_M2_PATH).Target[0] -eq $null) {
```

with:

```powershell
if ($null -eq (Get-Item $MAVEN_M2_PATH).Target) {
```

- [x] **Step 3: Repair user-service YAML**

Move the datasource exclusions under the existing top-level `spring` mapping and retain one `security` mapping:

```yaml
spring:
  application:
    name: user-service
  autoconfigure:
    exclude: org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
security:
  token-secret: ${AUTH_TOKEN_SECRET:replace-with-at-least-32-random-characters}
  internal-service-token: ${AUTH_INTERNAL_SERVICE_TOKEN:change-me-internal}
```

- [x] **Step 4: Verify and commit**

Run `./mvnw.cmd -B -ntp -pl user-service test`; expect both startup tests to pass. Commit `mvnw.cmd` and the user-service YAML with message `fix: restore user service build`.

### Task 2: Persist auth session state

**Files:**
- Create: `auth-service/src/main/java/com/travelplan/auth/security/TokenSessionStore.java`
- Modify: `auth-service/src/main/java/com/travelplan/auth/security/TokenService.java`
- Create: `auth-service/src/main/resources/db/migration/V2__add_admin_session_subject_index.sql`
- Test: `auth-service/src/test/java/com/travelplan/auth/security/TokenServiceTest.java`

**Consumes:** `TokenService.Claims(subject, role, jti, expiresAt)`.

**Produces:** `recordIssued(UUID, String, Instant)`, `isActive(UUID, Instant)`, `revoke(UUID, Instant)`, and `revokeSubject(String, Instant)`.

- [x] **Step 1: Write failing session-state tests**

Add a two-token subject-revocation case:

```java
TokenService service = new TokenService(SECRET, 60);
String first = service.issue("user-1", "ADMIN").value();
String second = service.issue("user-1", "ADMIN").value();
service.revokeSubject("user-1");
assertNull(service.verify(first));
assertNull(service.verify(second));
```

- [x] **Step 2: Implement `TokenSessionStore`**

Use an optional `JdbcTemplate`. The JDBC path inserts `jti`, `subject`, and `expires_at` in `admin_sessions`; checks `revoked_at`/expiry on validation; and updates active rows during token/subject revocation. The fallback path uses `ConcurrentHashMap<UUID, Session>`. A missing database session must be denied.

- [x] **Step 3: Connect `TokenService` to the store**

After signing an issued token, call:

```java
sessions.recordIssued(UUID.fromString(jti), subject, Instant.ofEpochSecond(expiresAt));
```

Require `sessions.isActive` in `verify`; route `revoke` and `revokeSubject` through the store. Keep `verifyIgnoringRevocation` only to identify a token-specific session during logout.

- [x] **Step 4: Add the index, verify, and commit**

Add:

```sql
CREATE INDEX admin_sessions_subject_expiry_idx
    ON admin_sessions (subject, expires_at);
```

Run `./mvnw.cmd -B -ntp -pl auth-service test`; expect all auth tests to pass. Commit auth source, migration, and tests with `feat: persist auth session revocation`.

### Task 3: Add protected internal token validation

**Files:**
- Create: `auth-service/src/main/java/com/travelplan/auth/InternalTokenValidationController.java`
- Modify: `auth-service/src/main/java/com/travelplan/auth/security/AuthSecurityConfig.java`
- Test: `auth-service/src/test/java/com/travelplan/auth/InternalTokenValidationControllerTest.java`

**Consumes:** `Authorization: Bearer <token>` and `X-Internal-Service-Token`.

**Produces:** `200 {subject, role, expiresAt}`, `401` for invalid/revoked tokens, and `403` for an incorrect service credential.

- [x] **Step 1: Write failing controller tests**

Use a known token secret and internal credential. Cover valid issued token, revoked token, and invalid internal credential.

- [x] **Step 2: Implement the endpoint**

```java
@PostMapping("/internal/token-validation")
ResponseEntity<?> validate(@RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
    if (!serviceToken.equals(presented)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    TokenService.Claims claims = bearer(authorization);
    if (claims == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    return ResponseEntity.ok(new ValidationResponse(claims.subject(), claims.role(), claims.expiresAt()));
}
```

- [x] **Step 3: Verify and commit**

Keep `/internal/**` excluded from normal bearer handling and do not add a gateway route. Run `./mvnw.cmd -B -ntp -pl auth-service test`; commit with `feat: expose internal token validation`.

### Task 4: Enforce the authoritative decision at every boundary

**Files:**
- Create: `user-service/src/main/java/com/travelplan/users/security/AuthTokenValidator.java`
- Create: `travel-service/src/main/java/com/travelplan/travel/security/AuthTokenValidator.java`
- Create: `payment-service/src/main/java/com/travelplan/payments/security/AuthTokenValidator.java`
- Modify: all three `AdminTokenFilter.java` files
- Modify: `api-gateway/src/main/java/com/travelplan/gateway/GatewaySecurityConfig.java`
- Modify: service application YAML files and `infra/compose/compose.yml`
- Test: direct-service and gateway authorization tests

**Consumes:** a locally verified bearer token.

**Produces:** `ACTIVE`, `DENIED`, or `UNAVAILABLE` without logging the token.

- [x] **Step 1: Write failing boundary tests**

For a direct business-service request and a gateway request, use a local fake auth endpoint that responds `200` for active tokens, `401` for revoked tokens, and fails for unavailable auth. Assert `200`, `401`, and `503` respectively.

- [x] **Step 2: Implement servlet clients**

Each MVC service uses an HTTP client to post to `/internal/token-validation` after local HMAC/expiry/role checks:

```java
client.post().uri("/internal/token-validation")
    .header("Authorization", authorization)
    .header("X-Internal-Service-Token", internalToken)
    .retrieve().toBodilessEntity();
```

Map service `401/403` to `DENIED` and transport/5xx errors to `UNAVAILABLE`.

- [x] **Step 3: Implement the gateway client**

Use `WebClient` in the gateway `WebFilter` to call the same endpoint. Map denied responses to `401`, auth transport/5xx errors to `503`, and call `chain.filter(exchange)` only after `ACTIVE`.

- [x] **Step 4: Add configuration**

Add to gateway, travel, and payment application YAML files:

```yaml
auth:
  service-url: ${AUTH_SERVICE_URL:http://auth-service:8081}
security:
  internal-service-token: ${AUTH_INTERNAL_SERVICE_TOKEN:change-me-internal}
```

Keep user-service’s equivalent keys singular. Add `AUTH_INTERNAL_SERVICE_TOKEN` to the gateway, travel, and payment Compose service environments.

- [x] **Step 5: Verify and commit**

Run `./mvnw.cmd -B -ntp -pl api-gateway,user-service,travel-service,payment-service test`; expect every focused test to pass. Commit with `fix: enforce revoked tokens at api boundaries`.

### Task 5: Full verification and audit update

**Files:**
- Modify: `docs/audit/project-audit-2026-09-29.md`

**Consumes:** Tasks 1–4.

**Produces:** dated evidence replacing only the build and revocation findings.

- [x] **Step 1: Run backend verification**

Run `./mvnw.cmd -B -ntp verify`; expect all six reactor modules to finish with zero failures or errors.

- [x] **Step 2: Validate Compose overlays**

Run:

```bash
docker compose -f infra/compose/compose.yml config --quiet
docker compose -f infra/compose/compose.yml -f infra/compose/compose.secure.yml config --quiet
docker compose -f infra/compose/compose.yml -f infra/compose/compose.scale.yml config --quiet
```

Expect all commands to exit successfully.

- [x] **Step 3: Update and commit audit evidence**

Record exact verification output, retain all unrelated findings, and commit with `docs: record foundation remediation verification`.

## Plan self-review

- Build restoration: Task 1.
- Durable session/revocation state: Task 2.
- Protected authoritative validation API: Task 3.
- Gateway/direct-service `401` and `503` enforcement: Task 4.
- Verification and evidence: Task 5.
- Placeholder scan: no incomplete task instructions remain.
- Interface consistency: every boundary uses `/internal/token-validation` and the same three-state result.
