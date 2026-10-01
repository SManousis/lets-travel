# Jenkins Quality and Release Implementation Plan

> **For agentic workers:** Work in the current `build-security-data-fixes` checkout. The user explicitly prohibited worktrees. Track each task with its checkbox.

**Goal:** Make CI checks explicit and connect a verified main build to image publication and secure Compose deployment.

**Architecture:** Jenkins runs required checks before release, publishes SHA-tagged application images, and invokes Ansible. Ansible uses a release-only Compose overlay with image references and no build contexts.

**Tech Stack:** Jenkins Pipeline, Maven Wrapper, npm, Oxlint, SonarQube Community Build, Docker Compose, Ansible.

**Spec:** `docs/superpowers/specs/2026-09-30-ci-release-design.md`

## Global constraints

- Keep this branch and checkout; do not merge or create a worktree.
- Do not commit registry, provider, database, or administrator secrets.
- Record source verification separately from live Jenkins/deployment evidence.

## Tasks

### 1. Required project checks

- [x] Add a real UI lint command and pinned dependency compatible with TypeScript 7.
- [x] Make Jenkins execute backend verification, API lint, UI lint/build, and a main-branch Sonar quality gate without silent skips.
- [x] Document Community Build's PR-analysis limitation and required Jenkins setup.

### 2. Immutable release configuration

- [x] Implement Jenkins stages to build and push the six application images with a commit-SHA tag after the quality gate on `main`.
- [x] Add a Compose release overlay that uses those image tags and removes all local application build contexts.
- [x] Route dashboard and secure-proxy requests to the proper UI and API services.

### 3. Ansible deployment

- [x] Copy every configuration/init file referenced by the Compose stack and render the release environment from protected values.
- [x] Require image and secret inputs, select the release and secure overlays, pull published images, and check HTTPS readiness.
- [x] Update deployment and audit notes with required external setup and unverified live steps.

### 4. Verification and handoff

- [x] Run available static/configuration checks and inspect the staged diff.
- [x] Commit and push on `build-security-data-fixes`; leave it unmerged.
