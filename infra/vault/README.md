# Vault deployment

Enable the KV v2 engine at `secret/`, load each service's policy file, and issue one AppRole or Kubernetes identity per service. Bind each identity only to its matching policy. Store database credentials and `AUTH_TOKEN_SECRET` under the service paths shown by the policy files. Vault must run with TLS and persistent storage; development mode is intentionally unsupported. Rotate credentials by updating the secret, restarting or refreshing the affected service, and revoking the old identity.
