# Local MongoDB replica-set

A single-node MongoDB replica-set for local development, so the server can be run against a real
store on a developer machine.

A replica-set (not a standalone) is required: the checkpoint compare-and-swap runs inside a
multi-document transaction, which MongoDB only offers on a replica-set.

## Usage

```sh
docker compose -f deploy/local-mongo/docker-compose.yml up -d
# wait until healthy:
docker inspect --format '{{.State.Health.Status}}' cyntex-mongo-rs
```

The set is reachable at:

```
mongodb://localhost:27017/cyntex?replicaSet=rs0
```

which is the server's default store URI. Tear it down with:

```sh
docker compose -f deploy/local-mongo/docker-compose.yml down        # keep data
docker compose -f deploy/local-mongo/docker-compose.yml down -v     # drop data
```

This plaintext set is for convenience only. The store connection makes **TLS mandatory** — pointed at
this set the server refuses to connect unless plaintext is explicitly opted into with
`cyntex.store.mongo.allow-insecure=true`. Prefer the TLS set below for anything resembling a real run.

## TLS set (mandatory-TLS path)

The store connection requires TLS by default; a plaintext connection is refused rather than silently
allowed. `docker-compose.tls.yml` runs the same single-node replica-set with `requireTLS`, presenting
a self-signed chain the client trusts explicitly — the local development analogue of a real TLS store.

```sh
deploy/local-mongo/tls/gen-certs.sh                                     # once: self-signed CN=localhost chain
docker compose -f deploy/local-mongo/docker-compose.tls.yml up -d
docker inspect --format '{{.State.Health.Status}}' cyntex-mongo-rs-tls  # wait until healthy
```

Point the server at it — TLS is on by default (no `allow-insecure`), and the self-signed CA is trusted
via `tls-ca-file`:

```
cyntex.store.mongo.uri=mongodb://localhost:27017/cyntex?replicaSet=rs0
cyntex.store.mongo.tls-ca-file=deploy/local-mongo/tls/ca.pem
```

The generated `ca.pem`/`server.pem` are git-ignored — a private key never belongs in the repo, so each
developer generates their own. Tear down with `down` / `down -v` as above (container `cyntex-mongo-rs-tls`).
