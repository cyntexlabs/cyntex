#!/usr/bin/env bash
#
# Native smoke suite for the Cyntex CLI (poc1 D5).
#
# Exercises the GraalVM native-image binary as a black box: it must do the same offline work the
# JVM build does, with every bundled resource (connector catalog / grammar schema / message catalog)
# reachable inside the image and startup under the acceptance budget. JVM unit tests cannot catch a
# missing resource or a reflection gap — only the produced binary can — so this script is the
# executable spec for native packaging.
#
# Usage:
#   cli/native-smoke.sh [--build] [path-to-binary]
#     --build           build the native image first (mvn -Pnative ...), discovering GraalVM 21
#     path-to-binary    the cyntex binary to test (default: cli/target/cyntex)
#
# Exit 0 iff every check passes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY=""
DO_BUILD=0
STARTUP_BUDGET_MS=200

for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    *) BINARY="$arg" ;;
  esac
done
[[ -z "$BINARY" ]] && BINARY="$REPO_ROOT/cli/target/cyntex"

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }
# strip CSI escape sequences: a pty makes the binary emit colour, so matches must run on clean text
strip_ansi() { sed $'s/\033\\[[0-9;]*[a-zA-Z]//g'; }

# Drive the native binary under a real pty (JLine needs a terminal): feed $1 to its stdin, run it with
# the remaining args, capture all output into PTY_OUT and set PTY_RC=0 only on a clean child exit. A
# child that wedges past the deadline is SIGKILLed and always reaped, so the suite never orphans a
# 36MB process or hangs. PTY_RC, not just output greps, is what callers gate on.
pty_session() {
  local input="$1"; shift
  set +e
  PTY_OUT=$(CYNTEX_BIN="$BINARY" CYNTEX_PTY_INPUT="$input" python3 - "$@" <<'PY'
import os, pty, sys, select, time, signal

binary = os.environ["CYNTEX_BIN"]
data = os.environ["CYNTEX_PTY_INPUT"].encode()
argv = [binary] + sys.argv[1:]

pid, fd = pty.fork()
if pid == 0:                              # child: the binary on a controlling terminal
    try:
        os.execv(binary, argv)
    except Exception:
        os._exit(127)
else:
    out = bytearray()
    os.write(fd, data)
    deadline = time.time() + 15
    timed_out = True                     # cleared when the child closes the pty (clean EOF)
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.5)
        if r:
            try:
                chunk = os.read(fd, 4096)
            except OSError:
                timed_out = False
                break
            if not chunk:
                timed_out = False
                break
            out += chunk
    if timed_out:                        # never leave the native binary running
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    status = 0
    try:
        _, status = os.waitpid(pid, 0)   # blocking reap (no zombie)
    except ChildProcessError:
        pass
    try:
        os.close(fd)
    except OSError:
        pass
    sys.stdout.write(out.decode("utf-8", "replace"))
    sys.stdout.flush()
    clean = (not timed_out) and os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0
    sys.exit(0 if clean else 1)
PY
)
  PTY_RC=$?
  set -e
}

PASS=0
FAIL=0
ok()   { green "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { red   "  FAIL  $1"; FAIL=$((FAIL+1)); }

# --- discover GraalVM 21 (only needed for --build) ---------------------------------------------
discover_graalvm() {
  if [[ -n "${GRAALVM_HOME:-}" && -x "$GRAALVM_HOME/bin/native-image" ]]; then
    echo "$GRAALVM_HOME"; return 0
  fi
  # ask the macOS java_home registry for a GraalVM 21
  if [[ -x /usr/libexec/java_home ]]; then
    local home
    home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$home" && -x "$home/bin/native-image" ]]; then echo "$home"; return 0; fi
  fi
  # fall back to the jdkHome of the toolchain block that is both vendor=graalvm AND version 21.
  # Parse per <toolchain> block (not by line proximity) so reordering / extra elements do not break
  # discovery, and require version 21 so a graalvm 17 toolchain is not picked up by mistake.
  if [[ -f "$HOME/.m2/toolchains.xml" ]]; then
    local home
    home="$(awk '
      /<toolchain>/   { isg=0; v21=0; jh="" }
      /graalvm/       { isg=1 }
      /<version>21</  { v21=1 }
      /<jdkHome>/     { line=$0; sub(/.*<jdkHome>/, "", line); sub(/<\/jdkHome>.*/, "", line); jh=line }
      /<\/toolchain>/ { if (isg && v21 && jh != "") { print jh; exit } }
    ' "$HOME/.m2/toolchains.xml")"
    if [[ -n "$home" && -x "$home/bin/native-image" ]]; then echo "$home"; return 0; fi
  fi
  return 1
}

if [[ "$DO_BUILD" == 1 ]]; then
  bold "Building native image (mvn -Pnative)…"
  if ! GVM="$(discover_graalvm)"; then
    red "No GraalVM 21 with native-image found. Set GRAALVM_HOME or install Oracle GraalVM for JDK 21."
    exit 1
  fi
  echo "  GraalVM: $GVM"
  ( cd "$REPO_ROOT" && JAVA_HOME="$GVM" mvn -q -Pnative -pl cli -am -DskipTests package )
fi

if [[ ! -x "$BINARY" ]]; then
  red "native binary not found or not executable: $BINARY"
  red "build it first:  cli/native-smoke.sh --build"
  exit 1
fi
bold "Native smoke — binary: $BINARY"

CORPUS="$REPO_ROOT/core/core-dsl/src/test/resources/corpus"

# --- timing helper: median of N runs of a command, in milliseconds -----------------------------
median_ms() {
  local n=5 i t samples=()
  for ((i=0; i<n; i++)); do
    local start end
    start=$(python3 -c 'import time; print(int(time.time()*1000))')
    "$@" >/dev/null 2>&1 || true
    end=$(python3 -c 'import time; print(int(time.time()*1000))')
    samples+=( $((end-start)) )
  done
  printf '%s\n' "${samples[@]}" | sort -n | sed -n '3p'
}

# --- 1. startup budget --------------------------------------------------------------------------
bold "[1] startup time (<${STARTUP_BUDGET_MS}ms)"
VERSION_MS=$(median_ms "$BINARY" --version)
if (( VERSION_MS < STARTUP_BUDGET_MS )); then
  ok "cyntex --version  median ${VERSION_MS}ms"
else
  bad "cyntex --version  median ${VERSION_MS}ms  (budget ${STARTUP_BUDGET_MS}ms)"
fi
# a resource-touching command (validate loads catalog + messages) must also stay in budget
VALID_DIR="$CORPUS/valid/s01-mirror-rename-ddl"
VALIDATE_MS=$(median_ms "$BINARY" validate "$VALID_DIR")
if (( VALIDATE_MS < STARTUP_BUDGET_MS )); then
  ok "cyntex validate <dir>  median ${VALIDATE_MS}ms (catalog + schema + messages loaded)"
else
  bad "cyntex validate <dir>  median ${VALIDATE_MS}ms  (budget ${STARTUP_BUDGET_MS}ms)"
fi

# --- 2. validate over the full corpus -----------------------------------------------------------
bold "[2] validate — full corpus (valid → exit 0, invalid → exit 1)"
valid_fail=0
for dir in "$CORPUS"/valid/*/; do
  if "$BINARY" validate "$dir" >/dev/null 2>&1; then :; else
    bad "valid corpus rejected: $(basename "$dir")"; valid_fail=$((valid_fail+1))
  fi
done
(( valid_fail == 0 )) && ok "all $(ls -d "$CORPUS"/valid/*/ | wc -l | tr -d ' ') valid scenarios accepted (exit 0)"

invalid_fail=0
for dir in "$CORPUS"/invalid/*/; do
  base=$(basename "$dir")
  # capture output, not just the exit code: an uncaught native fault (a missing bundled resource, a
  # reflection gap) also exits 1, so exit-1-alone cannot tell a coded rejection from a crash — exactly
  # the regression class this suite exists to catch. Require the coded `invalid:` line and no stack frame.
  set +e; out=$("$BINARY" validate "$dir" 2>&1); code=$?; set -e
  if (( code != 1 )); then
    bad "invalid corpus not rejected with exit 1: $base (got exit $code)"; invalid_fail=$((invalid_fail+1)); continue
  fi
  if echo "$out" | grep -qE '\.java:[0-9]+\)'; then
    bad "invalid corpus exit 1 but emitted a Java stack trace — uncaught native fault, not a coded rejection: $base"
    invalid_fail=$((invalid_fail+1)); continue
  fi
  if ! echo "$out" | grep -q 'invalid:'; then
    bad "invalid corpus exit 1 but no coded diagnostic rendered — possible native crash: $base"
    invalid_fail=$((invalid_fail+1)); continue
  fi
done
(( invalid_fail == 0 )) && ok "all $(ls -d "$CORPUS"/invalid/*/ | wc -l | tr -d ' ') invalid scenarios rejected (exit 1, coded diagnostic, no stack trace)"

# --- 3. new — non-interactive scaffolding (catalog-driven) --------------------------------------
# --dry-run previews the canonical artifact on stdout (no file written); it proves the connector
# catalog resource is reachable in the image (mysql resolved) and the canonical writer runs. The
# -o json result-envelope path is exercised separately by [4] explain (shared JsonOut writer).
bold "[3] new — non-interactive (catalog read + canonical render)"
NEW_OUT=$("$BINARY" new --kind source --connector mysql --id smoke_src -m cdc --dry-run 2>&1) || true
if echo "$NEW_OUT" | grep -q 'id: smoke_src' \
   && echo "$NEW_OUT" | grep -q 'connector: mysql' \
   && echo "$NEW_OUT" | grep -q 'mode: cdc'; then
  ok "new --kind source --connector mysql -m cdc --dry-run rendered the canonical artifact"
else
  bad "new non-interactive did not render the expected artifact; output: $NEW_OUT"
fi

# --- 4. explain — schema navigation (schema resource) ------------------------------------------
bold "[4] explain — field documentation (schema resource)"
EXPLAIN_OUT=$("$BINARY" explain source -o json 2>&1) || true
if echo "$EXPLAIN_OUT" | grep -q '"description"'; then
  ok "explain source -o json returned a documented node"
else
  bad "explain did not return a documented node; output: $EXPLAIN_OUT"
fi

# --- 5. REPL under a real pty (JLine interactive loop) ------------------------------------------
bold "[5] REPL — interactive loop under a pty (JLine)"
# printf -v (not $(...)) so the trailing newline that submits `exit` survives — command substitution
# would strip it, leaving the REPL waiting for Enter until the deadline.
printf -v repl_in 'help\nvalidate %s\nexit\n' "$VALID_DIR"
pty_session "$repl_in"
# match on ANSI-stripped text: anchor `valid:` so it cannot be satisfied by the `valid:` inside
# `invalid:` (a rejected validate must not pass as a success), and require a clean child exit.
REPL_CLEAN=$(printf '%s' "$PTY_OUT" | strip_ansi)
if (( PTY_RC == 0 )) \
   && printf '%s' "$REPL_CLEAN" | grep -q "Cyntex offline CLI" \
   && printf '%s' "$REPL_CLEAN" | grep -qE '(^|[^[:alpha:]])valid:' \
   && printf '%s' "$REPL_CLEAN" | grep -q "bye"; then
  ok "REPL banner + successful validate + clean exit (rc 0) observed over a pty"
else
  bad "REPL pty session failed (rc=$PTY_RC) or missing expected markers; output:"; echo "$PTY_OUT"
fi

# --- 6. new wizard under a real pty (JLinePrompter interactive flow) ----------------------------
# Drive the interactive `new` source wizard (the JLinePrompter path, distinct from [3]'s flag path):
# connector, read mode, blank tables, an id, then blank lines so every connector config field is
# skipped (collect() asks each once; a blank reply omits it — no re-prompt, so surplus blanks are
# harmless and discarded when the wizard finishes). --dry-run previews the artifact, writing no file.
bold "[6] new — interactive wizard under a pty (JLinePrompter)"
# printf -v preserves the trailing newlines (command substitution would strip them); the 50 blank
# lines submit a skip for each connector config field so the wizard runs to completion.
printf -v wizard_in 'mysql\ncdc\n\nsmoke_iface\n'
printf -v wizard_pad '\n%.0s' {1..50}
pty_session "${wizard_in}${wizard_pad}" new --dry-run
WIZARD_CLEAN=$(printf '%s' "$PTY_OUT" | strip_ansi)
if (( PTY_RC == 0 )) \
   && printf '%s' "$WIZARD_CLEAN" | grep -q 'connector: mysql' \
   && printf '%s' "$WIZARD_CLEAN" | grep -q 'id: smoke_iface' \
   && printf '%s' "$WIZARD_CLEAN" | grep -q 'mode: cdc'; then
  ok "interactive new wizard rendered the canonical artifact + clean exit (rc 0) over a pty"
else
  bad "new wizard pty session failed (rc=$PTY_RC) or missing expected artifact; output:"; echo "$PTY_OUT"
fi

# --- 7. structured YAML output (the YAML writer + its resources reachable in the image) ----------
# [4] exercises -o json; the YAML writer is a separate code path and the acceptance bar promises
# both json|yaml. Run explain -o yaml (schema-backed node envelope) and validate -o yaml (diagnostics
# envelope) through the native binary; require real block-mapping output and no uncaught native fault
# (a missing resource / reflection gap would surface as a stack frame, not a clean mapping).
bold "[7] -o yaml — structured YAML output (YAML writer reachable in the image)"
YAML_EXPLAIN=$("$BINARY" explain source -o yaml 2>&1) || true
YAML_VALIDATE=$("$BINARY" validate "$VALID_DIR" -o yaml 2>&1) || true
if echo "$YAML_EXPLAIN" | grep -qE '^path: source$' \
   && echo "$YAML_EXPLAIN" | grep -qE '^description:' \
   && echo "$YAML_VALIDATE" | grep -qE '^status: valid$' \
   && ! printf '%s\n%s' "$YAML_EXPLAIN" "$YAML_VALIDATE" | grep -qE '\.java:[0-9]+\)'; then
  ok "explain + validate -o yaml rendered block mappings (no native fault)"
else
  bad "-o yaml did not render the expected block mapping; explain: $YAML_EXPLAIN | validate: $YAML_VALIDATE"
fi

# --- 8. Tab completion under a pty (the JLine completer reachable in the image) ------------------
# Feed `va` + TAB so the verb completer resolves it to `validate`, then a valid corpus dir + Enter.
# `va` on its own is not a verb (it draws an "Unmatched argument" usage error), so a `valid:` result
# can only mean the native JLine completer fired and completed `va`→`validate`. This is the only
# native exercise of completion; the JVM unit suite covers the candidate logic itself.
bold "[8] Tab completion — verb completer under a pty (JLine)"
printf -v comp_in 'va\t %s\nexit\n' "$VALID_DIR"
pty_session "$comp_in"
COMP_CLEAN=$(printf '%s' "$PTY_OUT" | strip_ansi)
if (( PTY_RC == 0 )) \
   && printf '%s' "$COMP_CLEAN" | grep -qE '(^|[^[:alpha:]])valid:' \
   && ! printf '%s' "$COMP_CLEAN" | grep -q 'Unmatched argument'; then
  ok "Tab completed 'va'→'validate' and ran it over a pty (completer reachable in the image)"
else
  bad "Tab completion pty session failed (rc=$PTY_RC) or did not complete 'va'→'validate'; output:"; echo "$PTY_OUT"
fi

# --- summary ------------------------------------------------------------------------------------
echo
bold "native smoke: ${PASS} passed, ${FAIL} failed"
(( FAIL == 0 ))
