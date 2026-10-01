# Let’s Travel Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish Let’s Travel as a clean, standalone Git repository based on the local Travel Plan baseline.

**Architecture:** Copy only repository-owned inputs from `../travel-plan` into the empty destination. Preserve application structure and build tooling, exclude history and generated or sensitive state, then initialize a new Git history in the destination.

**Tech Stack:** Java 21, Maven Wrapper, Spring Boot services, React/npm frontend, Git.

**Spec:** `docs/superpowers/specs/2026-10-01-lets-travel-bootstrap-design.md`

## Global Constraints

- Source is `../travel-plan`; destination is this `lets-travel` directory.
- Do not copy source Git metadata, generated build output, dependency caches, IDE state, credentials, or private environment files.
- Preserve source, tests, documentation, build descriptors/wrappers, CI configuration, and safe configuration templates.
- Initialize a new Git repository and do not configure a remote without a supplied URL.

## Review Focus

- Source Git history must not appear in the new repository.
- Tracked files must not contain dependency/build output such as `target/`, `node_modules/`, or `.gradle/`.
- Tracked files must not contain credentials or private `.env` files.
- Maven wrapper and root `pom.xml` must remain available so the baseline can build.
- All service and frontend source directories must be copied so the starting system is complete.

### Task 1: Copy a sanitized Travel Plan baseline

**Files:**

- Create: the source-controlled files from `../travel-plan`, retaining their relative paths.
- Create: `.gitignore` only if the copied baseline does not already exclude generated and private local state.

**Interfaces:**

- Consumes: the local `../travel-plan` working tree.
- Produces: a source-only Let’s Travel baseline without inherited repository metadata.

- [ ] **Step 1: Inventory source-owned files and excluded local/generated paths**

Run: `git -C ../travel-plan ls-files` and compare against known generated/local paths.
Expected: a deterministic tracked-file set to copy, with no need to infer from build output.

- [ ] **Step 2: Copy the tracked source-owned files into the destination**

Use Git's tracked-file list or an equivalent exact-copy mechanism. Preserve executable mode on wrapper scripts.

- [ ] **Step 3: Verify the copied structure**

Run: `test -f pom.xml && test -f mvnw && test -d user-service && test -d travel-service && test -d admin-ui`
Expected: exit code 0.

### Task 2: Initialize a clean Git repository

**Files:**

- Create: `.git/` metadata for a new repository.

**Interfaces:**

- Consumes: the sanitized baseline from Task 1.
- Produces: a clean initial commit on a new local Git repository.

- [ ] **Step 1: Initialize Git in `lets-travel`**

Run: `git init -b main`
Expected: Git recognizes this directory as a repository on `main`.

- [ ] **Step 2: Stage and inspect the initial repository contents**

Run: `git add --all && git diff --cached --check && git status --short`
Expected: no whitespace errors and no excluded generated/private paths staged.

- [ ] **Step 3: Create the initial baseline commit**

Run: `git commit -m "chore: initialize lets-travel baseline"`
Expected: one root commit containing the sanitized baseline.

- [ ] **Step 4: Verify repository state and build baseline**

Run: `git status --short --branch && ./mvnw -B -ntp verify`
Expected: a clean `main` branch; Maven completes successfully when dependencies are available.
