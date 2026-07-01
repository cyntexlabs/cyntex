#!/usr/bin/env bash
#
# Dist smoke suite for the Cyntex server (L1 packaging slice).
#
# Verifies the single service deliverable shape as a black box: the Spring Boot fat-jar boots under
# --role=all, rejects an unsupported role with a coded diagnostic (non-zero exit), and the assembled
# dist tar.gz unpacks to bin/conf/lib whose launcher brings the same process up. A JVM unit test
# cannot catch a broken repackage, a missing dist file, or a launcher pointing at the wrong jar —
# only the produced artifacts can — so this script is the executable spec for service packaging (the
# JVM counterpart of the CLI's native-smoke.sh).
#
# "Boots" at L1 means the application context starts and the process exits cleanly (code 0): the
# server has no keep-alive plane yet (Hz / Jet land in later tasks), so a non-web context starts,
# logs "Started Bootstrap", and returns. Long-running + SIGTERM behaviour is a later task.
#
# The store connection is disabled for the boot checks (--cyntex.store.mongo.enabled=false): this is
# a black-box test of packaging + operational logging, not of store connectivity (which has its own
# unit test and a testcontainers integration test), so it must not depend on a reachable replica-set.
#
# Usage:
#   app/dist-smoke.sh [--build]
#     --build   build the app artifacts first (mvn -pl app -am package -DskipTests)
#
# Exit 0 iff every check passes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }

PASS=0
FAIL=0
# check / check_not gate on a command's exit status; the `if` guard keeps `set -e` from aborting the
# suite on an asserted-false condition. check passes when the command succeeds, check_not when it fails.
check() {
  local desc="$1"; shift
  if "$@"; then green "  PASS: $desc"; PASS=$((PASS + 1)); else red "  FAIL: $desc"; FAIL=$((FAIL + 1)); fi
}
check_not() {
  local desc="$1"; shift
  if "$@"; then red "  FAIL: $desc"; FAIL=$((FAIL + 1)); else green "  PASS: $desc"; PASS=$((PASS + 1)); fi
}

# Run a command with a hard timeout (portable; macOS ships no coreutils `timeout`). Writes the child's
# combined stdout+stderr to our stdout and exits with the child's code (124 on timeout).
run_capped() {
  python3 - "$@" <<'PY'
import subprocess, sys
secs = int(sys.argv[1]); cmd = sys.argv[2:]
try:
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=secs)
    sys.stdout.write(p.stdout); sys.stdout.write(p.stderr)
    sys.exit(p.returncode)
except subprocess.TimeoutExpired as e:
    out, err = e.stdout or "", e.stderr or ""
    sys.stdout.write(out.decode("utf-8", "replace") if isinstance(out, bytes) else out)
    sys.stdout.write(err.decode("utf-8", "replace") if isinstance(err, bytes) else err)
    sys.exit(124)
PY
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The server runs on JDK 21 (the build compiles to 21 via the Maven toolchain), so the smoke must
# launch the artifacts with a 21 runtime rather than whatever `java` happens to be on PATH. Discover
# one the same way the build does: an explicit JAVA21_HOME, then macOS java_home, then the current
# JAVA_HOME if it is 21, then the first version-21 jdkHome in the Maven toolchains file.
resolve_java21_home() {
  if [[ -n "${JAVA21_HOME:-}" && -x "$JAVA21_HOME/bin/java" ]]; then echo "$JAVA21_HOME"; return 0; fi
  if [[ -x /usr/libexec/java_home ]]; then
    local h; h="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -n "$h" && -x "$h/bin/java" ]] && { echo "$h"; return 0; }
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] \
     && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then echo "$JAVA_HOME"; return 0; fi
  local tc="$HOME/.m2/toolchains.xml"
  if [[ -f "$tc" ]]; then
    local home
    while IFS= read -r home; do
      [[ -x "$home/bin/java" ]] && "$home/bin/java" -version 2>&1 | grep -q 'version "21' && { echo "$home"; return 0; }
    done < <(grep -oE '<jdkHome>[^<]+</jdkHome>' "$tc" | sed -E 's@</?jdkHome>@@g')
  fi
  return 1
}

JAVA21_HOME_RESOLVED="$(resolve_java21_home || true)"
if [[ -z "$JAVA21_HOME_RESOLVED" ]]; then
  red "no JDK 21 found (the server targets Java 21); set JAVA21_HOME to a 21 JDK"; exit 2
fi
JAVA="$JAVA21_HOME_RESOLVED/bin/java"
# the launcher honours JAVA_HOME; point it at the same 21 runtime
export JAVA_HOME="$JAVA21_HOME_RESOLVED"
bold "using JDK 21 at $JAVA21_HOME_RESOLVED"

if [[ $DO_BUILD -eq 1 ]]; then
  bold "building app artifacts (mvn -pl app -am clean package -DskipTests)…"
  # `clean` so target/ cannot accumulate stale-version artifacts across a ${revision} bump
  ( cd "$REPO_ROOT" && mvn -q -pl app -am -DskipTests clean package )
fi

# Newest match wins (ls -t), so a freshly built artifact is selected even if a stale-version one
# lingers in target/ from an earlier non-clean build — the suite must never go green on a stale jar.
BOOT_JAR="$(ls -t "$REPO_ROOT"/app/target/app-*-boot.jar 2>/dev/null | head -1 || true)"
DIST_TGZ="$(ls -t "$REPO_ROOT"/app/target/app-*-dist.tar.gz 2>/dev/null | head -1 || true)"

bold "1. fat-jar boots under --role=all"
if [[ -z "$BOOT_JAR" || ! -f "$BOOT_JAR" ]]; then
  red "  FAIL: no boot jar at app/target/app-*-boot.jar (repackage goal missing; run with --build)"
  FAIL=$((FAIL + 1))
else
  set +e
  run_capped 90 "$JAVA" -jar "$BOOT_JAR" --role=all --cyntex.store.mongo.enabled=false --cyntex.log.dir="$WORK/logs-boot" >"$WORK/boot-all.txt" 2>&1; RC=$?
  set -e
  check     "exit 0 (clean boot)"             test "$RC" -eq 0
  check     "logged 'Started Bootstrap'"      grep -q "Started Bootstrap" "$WORK/boot-all.txt"
  # Operational logging: the file appender writes to the configured directory, and every line carries
  # the reserved MDC attribution slots ("[] [] []" while unpopulated) — proof the shipped format is
  # live end to end, which a JVM unit test asserting the logback context cannot cover.
  check     "wrote an operational log file"    test -f "$WORK/logs-boot/cyntex-server.log"
  check     "log format carries the MDC slots" grep -qE '\-\-\- \[\] \[\] \[\]' "$WORK/logs-boot/cyntex-server.log"
fi

bold "2. fat-jar rejects an unsupported role with a coded diagnostic"
if [[ -n "$BOOT_JAR" && -f "$BOOT_JAR" ]]; then
  set +e
  run_capped 60 "$JAVA" -jar "$BOOT_JAR" --role=nope >"$WORK/boot-bad.txt" 2>&1; RC=$?
  set -e
  check     "exit 1 (coded ERROR severity)"   test "$RC" -eq 1
  check     "rendered coded message"          grep -qF "Unsupported --role value 'nope'." "$WORK/boot-bad.txt"
  check_not "context never started (fail-fast before run)" grep -q "Started Bootstrap" "$WORK/boot-bad.txt"
fi

bold "3. dist tar.gz unpacks to bin/conf/lib and its launcher boots"
if [[ -z "$DIST_TGZ" || ! -f "$DIST_TGZ" ]]; then
  red "  FAIL: no dist tar.gz at app/target/app-*-dist.tar.gz (assembly missing; run with --build)"
  FAIL=$((FAIL + 1))
else
  tar -xzf "$DIST_TGZ" -C "$WORK"
  ROOT="$(find "$WORK" -maxdepth 1 -mindepth 1 -type d -name 'cyntex-*' | head -1)"
  check     "dist has a versioned root dir"   test -d "$ROOT"
  check     "bin/ conf/ lib/ present"         test -d "$ROOT/bin" -a -d "$ROOT/conf" -a -d "$ROOT/lib"
  check     "bin/cyntex-server is executable" test -x "$ROOT/bin/cyntex-server"
  check     "lib/ holds the server jar"       bash -c 'ls "$1"/lib/*.jar >/dev/null 2>&1' _ "$ROOT"
  if [[ -x "$ROOT/bin/cyntex-server" ]]; then
    set +e
    run_capped 90 "$ROOT/bin/cyntex-server" --role=all --cyntex.store.mongo.enabled=false --cyntex.log.dir="$WORK/logs-dist" >"$WORK/dist-all.txt" 2>&1; RC=$?
    set -e
    check   "launcher exit 0 (clean boot)"    test "$RC" -eq 0
    check   "launcher logged 'Started Bootstrap'" grep -q "Started Bootstrap" "$WORK/dist-all.txt"
    check   "launcher wrote an operational log file" test -f "$WORK/logs-dist/cyntex-server.log"

    # Prove the launcher's conf/ search-path wiring is load-bearing, not decorative: with the shipped
    # comment-only conf the Spring banner prints (control); writing a boot-time property into conf/
    # must change the running context. If conf/ were not actually read, the banner would print
    # regardless and the override check below would fail.
    check   "default conf prints the Spring banner (control)" grep -q ":: Spring Boot ::" "$WORK/dist-all.txt"
    printf 'spring.main.banner-mode=off\n' >"$ROOT/conf/application.properties"
    set +e
    run_capped 90 "$ROOT/bin/cyntex-server" --role=all --cyntex.store.mongo.enabled=false --cyntex.log.dir="$WORK/logs-dist2" >"$WORK/dist-conf.txt" 2>&1; RC=$?
    set -e
    check     "conf override still boots"      grep -q "Started Bootstrap" "$WORK/dist-conf.txt"
    check_not "conf override reaches the context (banner suppressed via conf/)" grep -q ":: Spring Boot ::" "$WORK/dist-conf.txt"
  fi
fi

echo
if [[ $FAIL -eq 0 ]]; then green "dist smoke: $PASS passed"; exit 0; else red "dist smoke: $FAIL failed, $PASS passed"; exit 1; fi
