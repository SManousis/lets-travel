# Deployment roles

The current playbooks use task blocks rather than Ansible roles. `prepare.yml` provisions Docker Engine and the Compose plugin on Debian or Ubuntu; `deploy.yml` owns release reconciliation and readiness. `site.yml` runs both in that order. Certificates, Vault initialization, CI agents, and observability storage still require environment-specific setup. Release secrets come from protected Ansible variables or controller environment lookups and are not printed by secret-bearing tasks.
