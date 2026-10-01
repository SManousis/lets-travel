#!/bin/sh
set -eu

install -d -m 0700 -o deploy -g deploy /home/deploy/.ssh
printf '%s\n' "${DEPLOY_AUTHORIZED_KEY:?Set DEPLOY_AUTHORIZED_KEY}" > /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 0600 /home/deploy/.ssh/authorized_keys

ssh-keygen -A >/dev/null
/usr/sbin/sshd

# Arguments starting with "-" make the dind entrypoint add the unix socket and,
# with DOCKER_TLS_CERTDIR empty, the plain tcp://0.0.0.0:2375 listener.
exec dockerd-entrypoint.sh --insecure-registry=registry:5000
