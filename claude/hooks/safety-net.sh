#!/usr/bin/env bash
# PreToolUse for Bash, file edits and reads: destructive commands and secret reads (dev-kit#20, ADR-0001
# choice 4). It hands the call to the vendored cc-safety-net (vendor/cc-safety-net, the version in its PIN),
# which parses commands instead of matching text. Its refusal is printed as it comes: JSON with
# "permissionDecision":"deny" and the rule. Nothing printed means allowed.
#
# Fails closed (ADR-0001, rule 3): when Node 22 or later is missing, or the guard cannot run, it exits 2 with
# what to do, which Claude Code turns into a refusal; that needs no jq. The project's policy is
# .cc-safety-net/policy.json in the session's folder.
set -uo pipefail

input="$(cat)"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
net="$here/../../vendor/cc-safety-net/dist/bin/cc-safety-net.js"

refuse() { echo "Refused: $1" >&2; exit 2; }

if ! command -v node >/dev/null 2>&1; then
  refuse "the safety guard needs Node 22 or later, and node is not on PATH. Install it (e.g. nvm install 22), then restart the session."
fi
major="$(node --version 2>/dev/null | sed -n 's/^v\([0-9]*\)\..*/\1/p')"
if [ -z "$major" ] || [ "$major" -lt 22 ]; then
  refuse "the safety guard needs Node 22 or later; this machine has $(node --version 2>&1 | head -n1). Install it (e.g. nvm install 22), then restart the session."
fi
[ -r "$net" ] || refuse "the vendored safety guard is missing ($net). Run: git submodule update --init && .shared/bin/kit init"

# stderr apart: a Node warning on stdout would not be the JSON Claude Code expects
err="$(mktemp)"; trap 'rm -f "$err"' EXIT
out="$(node "$net" hook --coding-cli <<<"$input" 2>"$err")"; rc=$?
if [ "$rc" -ne 0 ]; then
  refuse "the safety guard could not check this call (exit $rc: $(head -c 300 "$err")). Retry; if it persists, run .shared/bin/kit doctor."
fi
[ -z "$out" ] || printf '%s\n' "$out"
exit 0
