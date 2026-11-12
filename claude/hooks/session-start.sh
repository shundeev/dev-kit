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

# A merged task without its retrospective blocks new work (#56): say so before the work starts.
# Silent when GitHub cannot be asked; the start script says it then.
missing="$(bb "$kit_dir/bin/kit" retro --missing 2>/dev/null)"
[ $? -eq 1 ] && echo "dev-kit: $missing"

# A newer dev-kit (#3): say so, and how it reaches this project. Silent offline; a slow network
# gives up after a few seconds instead of holding the session's start.
if git -C "$kit_dir" -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=5 fetch -q origin main 2>/dev/null; then
  behind="$(git -C "$kit_dir" rev-list --count HEAD..FETCH_HEAD 2>/dev/null || echo 0)"
  if [ "${behind:-0}" -gt 0 ]; then
    [ "$behind" -eq 1 ] && unit=commit || unit=commits
    echo "dev-kit is $behind $unit behind its main. Open an XS task to update dev-kit here: move ${kit_dir##*/} to origin/main, run ${kit_dir##*/}/bin/kit init, commit the generated files, open the PR."
  fi
fi

report="$(bb "$kit_dir/bin/kit" doctor --quiet 2>&1)"
if [ -n "$report" ]; then
  echo "dev-kit doctor found problems with this project's setup. Tell the owner before starting work:"
  echo "$report"
fi
exit 0
