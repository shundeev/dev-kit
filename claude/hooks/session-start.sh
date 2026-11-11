#!/usr/bin/env bash
# SessionStart(startup). Repairs the per-machine items (`kit init --local`), then runs
# `kit doctor --quiet`; whatever doctor prints lands in the
# session's context, so the agent can tell the owner what is broken before work starts.
# Prints nothing when everything is in place.
set -uo pipefail

kit_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if ! command -v bb >/dev/null 2>&1; then
  echo "dev-kit: babashka (bb) is not installed, so the setup check was skipped. Tell the owner: install bb, then run ${kit_dir##*/}/bin/kit doctor."
  exit 0
fi

# Per-machine items (settings.local.json, local git config) are never committed: repair them
# without asking, so a fresh clone or a new worktree is set up by its first session.
bb "$kit_dir/bin/kit" init --local >/dev/null 2>&1 || true

report="$(bb "$kit_dir/bin/kit" doctor --quiet 2>&1)"
if [ -n "$report" ]; then
  echo "dev-kit doctor found problems with this project's setup. Tell the owner before starting work:"
  echo "$report"
fi
exit 0
