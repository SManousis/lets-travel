# Jenkins quality and release deployment design

## Scope

Continue work on `build-security-data-fixes` in the current checkout. Jenkins must run backend verification, API contract lint, UI lint and build for each change. SonarQube analysis and its quality gate must be required wherever the selected edition supports them. A reviewed `main` build must publish six application images tagged with the commit SHA, and Ansible must deploy those images without building source on the target.

## SonarQube edition

The checked-in CI stack uses SonarQube Community Build. Its official documentation supports analysis of a project's main branch and says native multibranch/PR analysis is unavailable. With that edition, Jenkins validates PRs through backend, API, and UI checks; Sonar analysis and the gate run on `main`. Native PR analysis requires a licensed edition and remains an explicit audit gap until that choice is made and demonstrated. A missing Sonar connection on `main` must fail the pipeline rather than skip analysis.

## Release flow

The Jenkins release stage runs only after all required checks pass on `main`. It builds images from the already verified backend JARs and UI source, tags them with the full Git commit SHA under a configured registry prefix, and publishes them with Jenkins credentials. Existing SHA tags may be reused only when their revision label and image ID match the candidate image; differing builds fail closed even when the label matches. The Ansible playbook receives that exact tag and registry prefix. A Compose release overlay removes local `build:` contexts and selects the published images. Ansible copies all referenced manifests and initialization/configuration files, renders an environment file from protected variables, verifies required secrets and pre-provisioned certificates, pulls images, and waits for Java health checks plus the HTTPS dashboard/API entry point. The HTTPS probe hostname and CA are configurable to match production certificates.

The dashboard Nginx routes `/api` to the gateway in both local Compose and the secure proxy; the secure proxy serves the dashboard at `/` and forwards `/api` to the gateway. No sandbox or registry credentials are committed.

## Verification and limits

Check UI lint and production build, backend verification where feasible, and base/release/secure Compose resolution. Run Ansible syntax validation only if the tool is installed. A live Jenkins build, registry push, Ansible deployment, Sonar gate, and TLS rehearsal require configured infrastructure and credentials; source checks alone do not establish those results. The secure overlay's remaining internal TLS/Vault integration gaps stay open in the audit.
