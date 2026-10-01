# User Payment Cleanup Implementation Plan

> **For agentic workers:** Execute this plan on the existing `build-security-data-fixes` checkout. The user explicitly prohibited worktrees.

**Goal:** Make payment and travel participation cleanup repeatable parts of user deletion and reject payment creation for inactive local users.

**Architecture:** Payment-service owns provider detach and method state. User-service owns deletion progress and invokes payment-service through authenticated internal HTTP. No provider customer ownership claim is made without a trusted binding.

**Tech Stack:** Spring Boot, Java 21, JDBC/Flyway, PostgreSQL, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-30-user-payment-cleanup-design.md`

## Global constraints

- Work directly on `build-security-data-fixes`; do not use worktrees or merge.
- No Stripe or PayPal credentials are available.
- Do not add or run tests unless the user asks.

## Tasks

### Task 1: Internal payment cleanup

- [x] Move method-detach orchestration into a shared service used by admin and internal cleanup.
- [x] Add an authenticated internal endpoint that processes bounded batches for one user and returns `204` only when none remain.
- [x] Keep provider failures on the method record for safe retry.

### Task 2: Local user validation

- [x] Add an authenticated active-user lookup in user-service.
- [x] Have payment-service require that lookup before first provider inspection.
- [x] Preserve read-only idempotent retries for a previously persisted payment attempt after user disablement.

### Task 3: Durable deletion progress

- [x] Add a Flyway migration for per-user deletion progress.
- [x] Add authenticated travel participation cleanup by traveler ID.
- [x] Revoke and disable first, invoke payment cleanup and travel cleanup, then remove traveler rows; preserve incomplete state for retry.
- [x] Return `202` for unfinished cleanup and `204` only when this workflow is complete.

### Task 4: Documentation and verification

- [x] Update the payment runbook, operations notes, audit follow-up, and mark the OpenAPI target/current difference.
- [x] Compile the Maven reactor without tests, validate Compose configuration, and inspect the diff.
