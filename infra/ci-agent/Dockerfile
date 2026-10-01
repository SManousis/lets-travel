FROM node:22-bookworm-slim AS node-runtime
FROM sonarsource/sonar-scanner-cli:12.2.0.4256_8.1.0 AS sonar-runtime
FROM docker:28-cli AS docker-runtime

FROM jenkins/inbound-agent:jdk21

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git openssh-client python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=sonar-runtime /opt/sonar-scanner /opt/sonar-scanner
COPY --from=docker-runtime /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-runtime /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins

RUN ln -sf /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && python3 -m venv /opt/ansible \
    && /opt/ansible/bin/pip install --no-cache-dir 'ansible-core>=2.17,<2.20' 'ansible-lint>=24,<26' \
    && /opt/ansible/bin/ansible-galaxy collection install -p /opt/ansible/collections community.docker \
    && chmod -R a+rX /opt/ansible /opt/sonar-scanner

ENV PATH="/opt/sonar-scanner/bin:/opt/ansible/bin:${PATH}" \
    ANSIBLE_COLLECTIONS_PATH=/opt/ansible/collections

USER jenkins
