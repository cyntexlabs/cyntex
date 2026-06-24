# Contributing to Cyntex

Thanks for your interest in Cyntex! Contributions are welcome.

## Workflow

1. **Fork** the repository and clone your fork.
2. **Branch** off `main` for your change: `git checkout -b my-change`.
3. **Build and test** locally before opening a PR:
   ```sh
   mvn verify
   ```
   This compiles every module and runs the unit tests, enforcer rules and the
   architecture (ArchUnit) checks. If you change the CLI and want to exercise the
   native binary, also build it with `mvn -Pnative -pl cli -am -DskipTests package`
   (requires GraalVM for JDK 21).
4. **Open a pull request** against `main`. Describe what changed and why. CI runs
   the build and a few repository checks on every PR — make sure it's green.

## Guidelines

- **Java 21.** The build targets JDK 21; the native CLI requires GraalVM for JDK 21.
- **Comments and identifiers in English.** Keep code comments, Javadoc, test names
  and messages in English.
- **Keep commit messages clean.** Plain, descriptive messages; please don't paste
  automated-tool signature footers into commits or PR descriptions (a CI check
  rejects them).
- **Match the surrounding code.** Follow the conventions and structure of the
  module you're editing; the architecture tests enforce the dependency rules
  between modules.
- **Add tests** for behavior changes, and keep the existing ones green.

## Reporting issues

Open a GitHub issue with a clear description and, where relevant, a minimal
reproduction (for the CLI, the `.cyn.yml` input and the exact command).
