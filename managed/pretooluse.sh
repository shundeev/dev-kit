#!/usr/bin/env bash
# Managed by dev-kit (`{{kit}}/bin/kit init`). Do not edit; change managed/pretooluse.sh in dev-kit.
#
# The project's PreToolUse(Bash) entry point. It lives in the project, not in {{kit}}/, so it
# exists even when the submodule is empty. Settings run it with `onFailure: "block"`: if this
# script cannot start or crashes, the command is blocked, not allowed (ADR-0001, rule 3).
#
# - dev-kit present: hand the call to {{kit}}/claude/hooks/delivery-guard.sh.
# - dev-kit missing: allow only the commands that repair it, refuse everything else with
#   exit 2 and a message naming the repair. The owner can still run anything with `!`.
#
# Known gaps: the repair check matches the start of the raw command text; it does not parse
# shell. That is enough to keep the repair path open; it is not a security boundary.
set -uo pipefail

input="$(cat)"
root="${CLAUDE_PROJECT_DIR:-.}"
guard="$root/{{kit}}/claude/hooks/delivery-guard.sh"

if [ -r "$guard" ]; then
  exec bash "$guard" <<<"$input"
fi

# Extract tool_input.command without jq, which may be what is missing.
cmd="$(printf '%s' "$input" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\(\([^"\\]\|\\.\)*\)".*/\1/p' | head -n1)"

case "$cmd" in
  "git submodule update"*|"git submodule init"*|"git submodule status"*|"git status"*| \
  "{{kit}}/bin/kit init"*|"bb {{kit}}/bin/kit init"*|"{{kit}}/bin/kit doctor"*|"bb {{kit}}/bin/kit doctor"*)
    exit 0 ;;
esac

echo "Refused: dev-kit is not checked out ({{kit}}/ is empty), so the delivery guard cannot run and only repair commands are allowed. Run: git submodule update --init && {{kit}}/bin/kit init" >&2
exit 2
