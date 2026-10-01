# Admin Workflows Implementation Plan

> **For agentic workers:** Implement in the current `build-security-data-fixes` checkout. The user explicitly prohibited worktrees.

**Goal:** Deliver usable admin workflows for all existing CRUD endpoints.

**Architecture:** A shared authenticated fetch helper supports three independent feature panels. Each panel owns its forms, list refreshes, pending state, and error presentation. The shell owns session expiry and navigation.

**Tech Stack:** React 19, TypeScript 7, Vite 8, existing CSS.

**Spec:** `docs/superpowers/specs/2026-09-30-admin-workflows-design.md`

## Global constraints

- Keep the current branch and folder; no worktree or merge.
- Match implemented JSON fields and statuses from the Java controllers, rather than planned OpenAPI fields that are not yet implemented.
- No tests are added or run unless the user asks.

## Tasks

### 1. API and session foundation

- [x] Add typed API request handling for JSON, empty responses, error codes, and revoked sessions.
- [x] Wire login, expiry, refresh of visible data, and logout in the shell.

### 2. Users and travelers

- [x] Add paginated lists, create/edit forms, detail selection, deletion confirmation, and pending cleanup messaging.
- [x] Associate traveler forms with a selected active user and retain recoverable input on errors.

### 3. Travel and itinerary

- [x] Add destination CRUD and travel list/create/edit/delete.
- [x] Add ordered stops, activities, accommodation, transport, and traveler participation controls matching current backend payloads.

### 4. Payments

- [x] Add gateway list/create/edit/delete and method list/create/label edit/delete.
- [x] Preserve method idempotency keys across retries and surface provider pending/failed states without exposing raw funding details.

### 5. Presentation and handoff

- [x] Extend the existing responsive styles and accessible labels/focus/error patterns.
- [x] Update the audit follow-up with implemented scope and limits; build the UI without tests, review, commit, and push the current branch.
