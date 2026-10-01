# Build, Security, and Data Fixes — Design

## Scope

This first remediation package fixes the blocked backend build and makes access-token revocation durable and enforceable at every API boundary. It does not implement travel/payment persistence, dashboard CRUD, or CI/CD deployment; those remain later packages from the audit report.

## Build reliability

`user-service` has two top-level `security` mappings, which Spring rejects. Merge their properties into one mapping and retain the existing default and `infra` profiles.

Maven Wrapper 3.3.4 assumes that `DirectoryInfo.Target` is indexable. On PowerShell 7 a normal directory has a null target, making `mvnw.cmd` fail before Maven runs. Replace that condition with a null-safe target comparison. The wrapper keeps its current Maven version and distribution URL.

## Revocation architecture

`auth-service` becomes the owner of issued and revoked token state. Its existing `admin_sessions` table records the issued token ID, subject, expiry, and revocation time. Token issuance writes a session record, while token and subject revocation mark the matching rows revoked. Validation checks signature, claims, expiry, and durable session status. The current in-memory state remains only as a default-profile fallback for tests and local startup without PostgreSQL.

The protected internal endpoint accepts a bearer token and the existing internal-service credential. It returns validated claims only when the token is still active. It is never routed by the gateway and rejects callers without the credential.

Gateway and each business service retain local signature/expiry/role validation, then call the protected endpoint before allowing a business request. A malformed, expired, unknown, or revoked token receives `401`. Failure to contact auth-service receives `503`, so a service does not accept a token whose revocation status cannot be confirmed. This preserves direct-service protection and avoids cross-service database reads.

## Data changes

Add an auth migration only if the current `admin_sessions` table needs an index or constraint to support subject-wide revocation efficiently. No other service reads or writes the auth database. Configuration adds the auth service URL and internal-service token to gateway, travel, and payment services; user-service consolidates its existing values.

## Tests and verification

Tests will first demonstrate the current failures, then cover durable/revoked-session decisions at the token service and boundary behavior at the gateway and a direct business-service path. Backend verification must pass through every Maven module using the documented wrapper command. The verification record will include both the full Maven result and the focused authorization tests.

## Failure handling

Internal validation failures are logged without tokens or credentials. Auth-service unavailability is distinct from denied access: callers receive `503` rather than an incorrect `401`. No positive validation result is cached during this package, so logout and subject revocation take effect on the next request at every boundary.
