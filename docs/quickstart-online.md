# Quick start: the online runtime (preview)

> **Preview / POC.** Cyntex's runtime is an early slice: a single-node, in-memory
> engine that executes your `.cyn.yml` resources as live pipelines. It is enough to
> run a real end-to-end sync, but it is **not** production-hardened — see
> [Limitations](#limitations) before you rely on it. The offline authoring CLI is
> covered in the [main README](../README.md); this page is the runtime.

What you'll do: build the server, start it, author a source → pipeline → target,
and drive it online from the CLI so rows move from one datastore to another —
snapshot first, then live change-data-capture (CDC).

The worked example below is **MySQL → MongoDB**, but the runtime is
connector-agnostic: any PDK connector works the same way.

## Prerequisites

- **JDK 21** to run the server and CLI. The code targets Java 21; launching it
  on an older JVM fails with `UnsupportedClassVersionError`. Use a GraalVM or plain
  JDK 21 explicitly (e.g. `JAVA_HOME=/path/to/jdk-21`).
- **A source database** you can read (this example: a MySQL reachable at
  `localhost:3306`). For CDC, MySQL needs binary logging on (`log_bin=ON`,
  `binlog_format=ROW`) and an account with replication privileges.
- **A control/target MongoDB replica set.** Cyntex stores its own state there, and
  this example also writes the synced rows there. A single-node replica set is fine.
- **Connector plugin jars** for the datastores you use (here: a MySQL and a MongoDB
  connector jar). Put them somewhere you can reference by path.

## 1. Build the server and CLI

From the repository root (a JDK 21 toolchain is required — see the README):

```sh
mvn -DskipTests install          # builds every module; server fat-jar included
# → app/target/app-<version>-boot.jar   (the runtime server)
```

Build the native CLI as the README describes (`mvn -Pnative -pl cli -am -DskipTests
package` → `cli/target/cyntex`), and put it on your `PATH`. The CLI drives both the
offline authoring loop and the online verbs used below.

> Full unit tests: `mvn verify` (slower). Container-backed integration tests need
> Docker.

## 2. Start the server

```sh
mkdir -p ./plugins       # a writable cache the server unpacks registered connectors into

java -jar app/target/app-<version>-boot.jar --role=all \
  --cyntex.store.mongo.uri="mongodb://localhost:27017/cyntex?replicaSet=rs0" \
  --cyntex.connectors.plugins-dir=./plugins
```

- Run this with **JDK 21** (`java -version`).
- `replicaSet=` must match your MongoDB's actual replica-set name.
- The server listens on port **8080**. Wait for `Started ... in N seconds` /
  `Tomcat started on port 8080`, then leave it running and open a second terminal.
- A Hazelcast `--add-opens` warning at startup is harmless.

## 3. Create the first admin

The server allows a one-time, localhost-only bootstrap of the first user. There is
**no CLI verb for this yet** (a known preview gap), so use `curl`:

```sh
curl -X POST http://localhost:8080/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<choose-a-password>"}'
```

A `204 No Content` means success.

## 4. Author the resources

A workspace is a folder partitioned by resource kind. Create three resources — the
read source, the write target, and the pipeline that connects them:

```sh
mkdir -p work/source work/pipeline
cd work
```

`source/db_src.cyn.yml` — the read source (your MySQL):

```yaml
version: cyntex/v1
kind: source
id: db_src
connector: mysql
config: { host: localhost, port: 3306, database: appdb, username: app, password: <password> }
mode: cdc
tables: [ orders ]
```

`source/warehouse.cyn.yml` — the write target (also `kind: source`):

```yaml
version: cyntex/v1
kind: source
id: warehouse
connector: mongodb
config: { isUri: true, uri: "mongodb://localhost:27017/warehouse" }
```

`pipeline/sync_orders.cyn.yml` — the data flow:

```yaml
version: cyntex/v1
kind: pipeline
id: sync_orders
source: db_src
settings: { read_mode: snapshot_and_cdc }
transforms:
  - { id: keep_writes, from: [ orders ], type: filter, expr: "op != 'd'" }
serve:
  from: keep_writes
  sync:
    - source: warehouse
```

> `serve.from` must name a transform `id` (here `keep_writes`) or a concrete
> resource — **not** a regex such as `/.*/`.

Validate offline before going online (no server needed):

```sh
cyntex validate            # expects: valid: 3 resources in work
```

## 5. Go online and run

Start the interactive REPL and drive it. The connection is session state, so these
run inside one REPL session:

```console
$ cyntex
cyntex(offline:work)> connect http://localhost:8080
cyntex(localhost:8080)> login admin
Password:                       # type the password from step 3 (not echoed)
cyntex(admin@localhost:8080)> register /path/to/mysql-connector.jar
cyntex(admin@localhost:8080)> register /path/to/mongodb-connector.jar
cyntex(admin@localhost:8080)> apply
cyntex(admin@localhost:8080)> discover-schema db_src
cyntex(admin@localhost:8080)> start sync_orders
```

- **`register`** uploads a connector jar to the server (content-addressed and
  idempotent; re-registering the same jar is a no-op).
- **`apply`** with no argument applies the whole workspace as one batch. The
  batch is the reference closure — a pipeline and the sources it names must be
  applied together, so apply the workspace, not one file at a time.
- **`discover-schema db_src`** reads the source schema and derives the target model
  and primary key. Run it **before** `start`.
- **`start sync_orders`** submits the pipeline: it reads the current rows
  (snapshot), then tails changes (CDC).

## 6. Observe and verify

```console
cyntex(admin@localhost:8080)> status sync_orders --watch    # live state; Ctrl-C to stop
cyntex(admin@localhost:8080)> metrics sync_orders           # recordCount / errorCount / per-table offset
cyntex(admin@localhost:8080)> logs sync_orders              # node-local operational log tail
```

- Immediately after `start`, the first `status`/`metrics` may report no
  observation yet — use `--watch` or retry after a second.
- `metrics` is the signal for progress: `recordCount` climbing, `errorCount` at 0.

Verify the rows actually landed, straight from the target:

```sh
mongosh --quiet "mongodb://localhost:27017/warehouse" \
  --eval "print(db.orders.countDocuments())"    # should reach the source row count
```

To see CDC, insert a row into the source table and watch the target count grow. The
sink upserts on the discovered key, so re-reads and the snapshot→CDC overlap do not
produce duplicates.

## 7. Tear down

```console
cyntex(admin@localhost:8080)> stop sync_orders
cyntex(admin@localhost:8080)> exit
```

Stop the server with `Ctrl-C` in its terminal.

## Limitations

This runtime is a preview. Known constraints in this slice:

- **Single node, in-memory.** No multi-node HA. A server restart does **not** resume
  from a persisted offset — it replays from the source (idempotent upsert absorbs
  the overlap). Durable resume / exactly-once are not in this preview.
- **`logs` is thin.** The per-pipeline `logs` face is a node-local operational tail
  and is often sparse; full runtime detail is in the server process log.
- **No CLI bootstrap verb.** The first admin is created with the `curl` in step 3.
- **One-shot online verbs don't persist a session.** The online verbs are driven
  from the REPL, where the connection is session state.
