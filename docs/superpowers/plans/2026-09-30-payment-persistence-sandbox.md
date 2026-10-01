# Durable Payment Persistence and Sandbox Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist payment metadata and validate sandbox provider references instead of echoing them as active.

**Architecture:** `PaymentStore` delegates to JDBC in the `infra` profile and to local maps in the default profile. `PaymentController` handles lifecycle transitions around provider calls. Stripe and PayPal adapters use fixed sandbox APIs with runtime credentials and bounded HTTP requests.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC/Flyway, PostgreSQL, Java HttpClient, Jackson, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-09-30-payment-persistence-sandbox-design.md`

## Global Constraints

- Work on `build-security-data-fixes` in the current checkout; do not create a worktree or merge.
- No credentials exist for a live Stripe/PayPal run. Do not claim a sandbox demonstration.
- Keep the existing synchronous payment HTTP shape for this package; record the OpenAPI operation mismatch.
- Do not add or run tests unless the user specifically requests verification. Compile and inspect the diff.

---

## File structure

| File | Responsibility |
|---|---|
| `payment-service/src/main/java/com/travelplan/payments/PaymentDomain.java` | Gateway and method records, state/error types. |
| `payment-service/src/main/java/com/travelplan/payments/PaymentStore.java` | JDBC and in-memory persistence, duplicate and in-use decisions. |
| `payment-service/src/main/java/com/travelplan/payments/PaymentController.java` | HTTP operations and provider lifecycle transitions. |
| `payment-service/src/main/java/com/travelplan/payments/{StripeAdapter,PayPalAdapter}.java` | Real sandbox reference inspection and detach/delete. |
| `payment-service/src/main/java/com/travelplan/payments/ProviderAdapter.java` | Shared provider interface and safe result/error shape. |
| `payment-service/src/main/resources/db/migration/V2__payment_gateway_lifecycle.sql` | Gateway table and method lifecycle columns/constraints. |
| `payment-service/src/main/resources/application.yml`, `infra/compose/compose.yml` | Runtime sandbox credential and timeout configuration. |
| `docs/payments.md`, `docs/operations.md`, `docs/audit/project-audit-2026-09-29.md` | Setup, migration, audit evidence and limits. |

### Task 1: Durable payment storage

- [ ] Add V2 migration that preserves V1 rows, creates gateway records, adds gateway references and lifecycle columns, and enforces unique provider references and idempotency keys.
- [ ] Add domain records and a `PaymentStore` whose `infra` path directly queries JDBC and whose default path uses maps.
- [ ] Implement gateway and method CRUD with atomic database checks, stable creation times, duplicate detection, and referenced-gateway protection.
- [ ] Compile payment-service and inspect the storage diff; commit the storage slice.

### Task 2: Sandbox provider adapters

- [ ] Expand `ProviderAdapter` to inspect a reference and detach/delete it, returning only safe display metadata.
- [ ] Implement Stripe PaymentMethod GET and detach with a sandbox secret key, fixed API host, bounded timeouts, ID/live-mode checks, and controlled error mapping.
- [ ] Implement PayPal OAuth, vault token GET and DELETE with sandbox client credentials, fixed API host, bounded timeouts, ID checks, and controlled error mapping.
- [ ] Compile payment-service and inspect the adapter diff; commit the provider slice.

### Task 3: Wire payment lifecycle and document limits

- [ ] Persist `PENDING` before provider inspection; record `ACTIVE` or `FAILED` with safe metadata/status. Handle idempotent retry and duplicate references.
- [ ] Mark `DELETION_PENDING` before provider detach/delete; delete locally on success and retain `FAILED` on provider failure. Keep local label edits separate from provider fields.
- [ ] Wire environment configuration in Compose, document credentials and legacy-row reconciliation, and update the audit without claiming live sandbox verification or completion of the future async contract.
- [ ] Compile and inspect the entire diff; request a read-only review, address important findings, commit, and push the same branch to Gitea.
