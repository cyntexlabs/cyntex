#!/usr/bin/env bash
# Generate a throwaway self-signed TLS chain for the local development MongoDB.
#
# Store TLS is opt-in; this chain is used only when the store URI asks for it (ssl=true), letting the
# local replica-set speak TLS for that case. This produces a
# single self-signed certificate (CN=localhost, SAN localhost/127.0.0.1) valid for ten years:
#   ca.pem      the certificate, trusted by the client via cyntex.store.mongo.tls-ca-file
#   server.pem  the private key concatenated with the certificate, presented by mongod
#
# The generated files are git-ignored (see .gitignore) — a private key never belongs in the repo.
# Run this once before `docker compose -f ../docker-compose.tls.yml up`. Development only.
set -euo pipefail
cd "$(dirname "$0")"

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout server-key.pem -out ca.pem -days 3650 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

cat server-key.pem ca.pem > server.pem
rm -f server-key.pem
# Readable by the container's mongod user (a different uid across the read-only bind mount), not
# owner-only — otherwise mongod cannot read its own TLS key and the set never starts. This is a
# throwaway self-signed development key with no security value.
chmod 644 server.pem

echo "Generated ca.pem and server.pem in $(pwd)"
