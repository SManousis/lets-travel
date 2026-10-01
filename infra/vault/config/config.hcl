ui = false
api_addr = "https://0.0.0.0:8200"
cluster_addr = "https://0.0.0.0:8201"
storage "file" { path = "/vault/file" }
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_cert_file = "/vault/tls/vault.crt"
  tls_key_file = "/vault/tls/vault.key"
}
disable_mlock = true
