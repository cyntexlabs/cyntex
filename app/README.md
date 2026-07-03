# app — service assembly root

The single deliverable that brings the whole platform up in one process (`--role=all`). It is the
only module permitted to depend on the adapters ring: the assembly root is where the adapter bridges
are bound into the runtime. It runs on the JVM (Spring Boot), packaged as a fat-jar and a `tar.gz`
distribution (`bin/` launcher + `conf/` external config + `lib/` jar).

## Operational logging

The server has two, deliberately separate, ways of saying something went wrong:

- **Operational logs** — the operator-facing diagnostic stream (process health, lifecycle,
  troubleshooting). Free-form English text, written to the console and a rolling file. This is for
  whoever runs the process.
- **Coded errors** — user-facing, diagnosable errors carried as stable symbolic codes, rendered from
  a message catalog per locale. This is a contract with the user and with tooling.

The two never mix: a code is not logged as the framework's contract, and a free-text log line never
becomes a code. Programmer bugs (invariant violations) stay bare crashes with a stack, not laundered
into either channel.

Logging is configured in [`logback-spring.xml`](src/main/resources/logback-spring.xml): console +
rolling file, with three reserved MDC attribution slots — `role`, `component`, `pipeline_id` —
spliced into the format. The slots are reserved now and render empty until later runtime work
populates them, so the format never has to change to gain them.

Operators tune it from `conf/application.properties` (or the environment); see the `cyntex.log.*`
keys there for the file location and the size/time rolling and retention caps, and Spring Boot's
`logging.level.*` keys for levels.
