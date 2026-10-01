# Let’s Travel Bootstrap Design

## Intent

Create a standalone repository for the Let’s Travel exercise by using the local
Travel Plan implementation as its baseline. The new repository must preserve the
predecessor's source, tests, documentation, build tooling, and configuration
templates while deliberately starting with no inherited Git history or local
generated state.

## Source and destination

- Source: `../travel-plan`
- Destination: this repository (`lets-travel`)

The official Let’s Travel brief describes an expansion of the Travel Management
System, while the audit evaluates behavior that depends on that existing system.
`travel-plan` is therefore the baseline, not an optional reference.

## Migration rules

- Copy repository-owned project files, including Maven wrapper files, backend
  services, frontend, tests, documentation, CI files, and configuration templates.
- Do not copy `.git` or any Git metadata from the source repository.
- Do not copy generated or machine-local directories such as Maven `target/`,
  Gradle caches, Node dependencies, editor settings, or temporary build output.
- Do not copy credentials, private environment files, deployment state, or cloud
  configuration. Retain safe example/template files only.
- Retain or create a `.gitignore` that excludes generated artifacts and local
  secrets.

## Repository initialization

Initialize `lets-travel` as a fresh Git repository after the copy, stage the
baseline contents, and create one initial commit. No remote is configured because
the destination repository URL has not been supplied; the user can add it before
pushing.

## Verification

Verify the copied file set contains the expected build descriptors and source
directories, confirm `git status` shows a clean repository after the initial
commit, and run the baseline Maven verification if the available environment can
resolve its dependencies.
