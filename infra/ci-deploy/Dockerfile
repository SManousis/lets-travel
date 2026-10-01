# Local release target for Jenkins: SSH for Ansible plus its own Docker engine.
FROM docker:28-dind

RUN apk add --no-cache openssh-server sudo python3 py3-requests bash openssl \
    && adduser -D -s /bin/sh deploy \
    && sed -i 's/^deploy:!/deploy:*/' /etc/shadow \
    && echo 'deploy ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/deploy \
    && chmod 0440 /etc/sudoers.d/deploy \
    && sed -i -e 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' \
              -e 's/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config

COPY entrypoint.sh /usr/local/bin/deploy-host-entrypoint
RUN chmod 0755 /usr/local/bin/deploy-host-entrypoint

ENTRYPOINT ["deploy-host-entrypoint"]
