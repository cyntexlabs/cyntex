# Cyntex

Cyntex is a next-generation data-integration and real-time data-processing
engine. You describe your integration resources — sources, pipelines, transforms,
views and publish surfaces — as small declarative `.cyn.yml` documents, and Cyntex
takes care of moving and reshaping the data.

This repository ships the **offline authoring CLI** — a single native binary that
lets you create, validate and explore `.cyn.yml` resources on a machine with no
server, no database, and no network — plus an early **preview runtime**: a server
that executes those resources as live pipelines. This README covers the offline
CLI; for the runtime (build → start a server → run a real sync) see
[docs/quickstart-online.md](docs/quickstart-online.md).

## Requirements

- **To run the CLI:** nothing — `cyntex` is a GraalVM native binary (starts in
  ~30 ms).
- **To build it from source:** **Oracle GraalVM for JDK 21** (includes
  `native-image`) and **Maven 3.6+**. A plain JDK 21 is enough to build and run
  the test suite; GraalVM is only needed for the native image.

## Build

From the repository root:

```sh
# Full build + unit tests (needs a JDK 21 toolchain)
mvn verify

# Native CLI binary (needs GraalVM for JDK 21 on JAVA_HOME)
mvn -Pnative -pl cli -am -DskipTests package
```

The native binary lands at **`cli/target/cyntex`** (~36 MB; the connector catalog,
grammar schema and message text are embedded). Put it on your `PATH`:

```sh
export PATH="$PWD/cli/target:$PATH"      # this shell
# or
ln -s "$PWD/cli/target/cyntex" ~/bin/cyntex
```

Verify:

```console
$ cyntex --version
cyntex 0.1.0
```

> The examples below write `cyntex`. If it isn't on your `PATH`, use
> `./cli/target/cyntex` instead.

## Quick start

Cyntex's offline workspace is an ordinary folder, partitioned by resource kind.
The workspace root is chosen by `--workdir` / `-w`, the `CYNTEX_WORKDIR`
environment variable, or the default `./cyn-work` (flag > env > default).

```
cyn-work/
├── source/      <id>.cyn.yml   — connection + read resource
├── pipeline/    <id>.cyn.yml   — data flow (source → transforms → output)
├── transform/   <id>.cyn.yml   — reusable transform
├── view/        <id>.cyn.yml   — materialized view / MDM sink
└── serve/       <id>.cyn.yml   — output / publish surface
```

The directory a file sits in must match the `kind:` it declares — Cyntex treats
the structure as the source of truth and `validate` enforces it.

### 1. Create a source

`new` is an interactive wizard (pick a connector → read mode → per-field config).
Add `-y` to run it non-interactively from flags:

```console
$ cyntex new -y --kind source --connector mysql --id orders_src -m cdc
created cyn-work/source/orders_src.cyn.yml
```

The result is a deterministic, canonical `.cyn.yml`:

```yaml
version: cyntex/v1
kind: source
id: orders_src
connector: mysql
mode: cdc
```

Connectors come from the embedded catalog; the read modes a connector supports
are enforced by its capability matrix (an unsupported `-m` is rejected at
`validate`). Add `--dry-run` to print the canonical YAML to stdout instead of
writing a file. Create a second source to act as the target:

```console
$ cyntex new -y --kind source --connector postgres --id warehouse -m cdc
created cyn-work/source/warehouse.cyn.yml
```

### 2. Create a pipeline and validate it

```console
$ cyntex new -y --kind pipeline --id orders_sync --source orders_src --sync-to warehouse
created cyn-work/pipeline/orders_sync.cyn.yml

$ cyntex validate
valid: 3 resources in cyn-work
```

`validate` (no path = the whole workspace) runs three offline checks: **structure**
(schema: unknown fields / types / enums), **reference closure** (referenced ids
exist in the workspace), and **capability matrix** (mode × connector legality,
config field types/enums). Every error carries a code, a location and a fix
suggestion. Exit codes: `0` clean, `1` coded diagnostics, `2` usage error,
`3` the verb needs a server connection.

### 3. Browse and explain

```console
$ cyntex ls
source (2)
  orders_src  mysql, cdc
  warehouse   postgres, cdc
pipeline (1)
  orders_sync  1 source, serve

$ cyntex desc orders_sync
pipeline  orders_sync
  path          pipeline/orders_sync.cyn.yml
  sources       orders_src
  validation    valid
  references    orders_src (source), warehouse (source)

$ cyntex explain source.mode
source.mode
Read mode; may be omitted when the source is only a connection supplier.
VALUES:
  cdc       Change data capture — an unbounded stream of inserts, updates and deletes.
  snapshot  One-shot bounded read of current rows; no change capture.
  stream    Unbounded push stream from a message system.
  file      Bounded read from files.
  api       Pull from an API or SaaS endpoint.
```

`explain` documents any field of the `cyntex/v1` grammar from the embedded schema;
`desc` describes a concrete resource in your workspace — they are different things.

### REPL and structured output

Run `cyntex` with no arguments for an offline REPL with Tab completion (verbs,
field paths, file paths) and `help` / `pwd` / `cd` / `exit`. All five offline
verbs (`new` / `validate` / `ls` / `desc` / `explain`) accept `-o json|yaml` for a
machine-readable envelope:

```console
$ cyntex validate -o json
{
  "status": "valid",
  "resourceCount": 3,
  "diagnostics": []
}
```

On failure each `diagnostics` entry carries `code` / `severity` / `message` /
`solution` and a location — the same error codes drive the human and machine
output.

## Editor integration

The CLI's JSON Schema (standard draft 2020-12) ships in the tree at:

```
core/core-schema/src/main/resources/schema/cyntex-v1.schema.json
```

Associate `*.cyn.yml` with it for live validation, completion and hover docs in any
editor backed by [yaml-language-server](https://github.com/redhat-developer/yaml-language-server)
(VS Code with the Red Hat YAML extension, IntelliJ, etc.):

```jsonc
// VS Code settings.json
{
  "yaml.schemas": {
    "/abs/path/to/cyntex/core/core-schema/src/main/resources/schema/cyntex-v1.schema.json": "*.cyn.yml"
  }
}
```

Or add a modeline as the first line of a file instead of editing global settings:

```yaml
# yaml-language-server: $schema=/abs/path/to/.../cyntex-v1.schema.json
version: cyntex/v1
kind: source
```

## Scope of this release

`cyntex --help` lists every verb. Beyond the five offline verbs above,
`connect` / `login` / `register` / `apply` / `discover-schema` / `start` /
`status` / `metrics` / `logs` (and friends) drive a running Cyntex server — see
[docs/quickstart-online.md](docs/quickstart-online.md) for the end-to-end runtime
flow (build → start a server → run a real sync). That runtime is an early
**preview** (single-node, in-memory; see the doc's limitations); offline, without a
connection, those verbs exit with code `3`. The offline loop is still the fastest
way to author: **create (`new`) → edit → browse (`ls` / `desc` / `explain`) →
validate**, producing `.cyn.yml` resources that the runtime then executes.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

TBD.
