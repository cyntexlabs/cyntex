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
