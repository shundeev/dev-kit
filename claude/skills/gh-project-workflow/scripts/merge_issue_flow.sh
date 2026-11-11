#!/usr/bin/env bash
# Merges an issue's pull request. Run it only when the owner said to merge.
#
# A separate command on purpose: the generated .claude/settings.json puts
# `bash .claude/skills/gh-project-workflow/scripts/merge_issue_flow.sh *` under "ask",
# so an agent cannot start a merge without the owner confirming it. (Permission rules see
# only the command the agent types, never the `gh pr merge` inside this script.)
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: merge_issue_flow.sh [options]

Run from the issue branch. Squash-merges the branch's pull request, confirms GitHub reports
it MERGED, sets the board Status to "Done", deletes the branch locally and on GitHub, and
switches back to the base branch. A pull request that did not merge keeps its branch.

Options:
  --repo <owner/repo>           Default: GHWF_REPO, then the origin remote
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --done-status <option>        Default: Done
  --help                        Show this help
USAGE
}

die() {
  echo "Error: $*" >&2
  exit 1
}

repo="${GHWF_REPO:-}"
owner="${GHWF_OWNER:-}"
project_number="${GHWF_PROJECT_NUMBER:-}"
base_branch="${GHWF_DEFAULT_BASE:-main}"
status_field="${GHWF_STATUS_FIELD:-Status}"
done_status="${GHWF_DONE_STATUS:-Done}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) repo="$2"; shift 2 ;;
    --owner) owner="$2"; shift 2 ;;
    --project-number) project_number="$2"; shift 2 ;;
    --base) base_branch="$2"; shift 2 ;;
    --status-field) status_field="$2"; shift 2 ;;
    --done-status) done_status="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

if [[ -z "$repo" ]]; then
  origin_url="$(git remote get-url origin 2>/dev/null || true)"
  [[ "$origin_url" =~ github.com[:/]([^/]+/[^/.]+)(\.git)?$ ]] && repo="${BASH_REMATCH[1]}"
fi
[[ -n "$repo" ]] || die "--repo or GHWF_REPO is required"

branch_name="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch_name" != "$base_branch" && "$branch_name" != "HEAD" ]] \
  || die "Run from the issue branch, not '$branch_name'"
[[ "$branch_name" =~ ^([0-9]+)- ]] || die "Branch '$branch_name' is not <number>-<slug>"
issue_number="${BASH_REMATCH[1]}"

# Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones.
pr_url="$(gh pr list -R "$repo" --head "$branch_name" --state open --json url --jq '.[0].url // empty')"
[[ -n "$pr_url" ]] || die "No open pull request for branch '$branch_name'"

gh pr merge "$pr_url" --squash

# Only a confirmed merge may delete the branch: cleaning up after a failed merge closes
# the pull request with the work still in it.
pr_state="$(gh pr view "$pr_url" --json state --jq '.state')"
[[ "$pr_state" == "MERGED" ]] || die "Pull request is $pr_state, not MERGED; branch '$branch_name' kept"

if [[ -n "$owner" && -n "$project_number" ]]; then
  issue_url="$(gh issue view "$issue_number" -R "$repo" --json url --jq '.url')"
  gh project item-edit "$project_number" --owner "$owner" --url "$issue_url" \
    --field "$status_field" --value "$done_status" >/dev/null \
    || echo "Warning: could not set $status_field to '$done_status' for #$issue_number" >&2
fi

# Leave the issue branch. When the base branch is checked out in another worktree, this one
# cannot switch to it; it stays detached on the merged base instead.
git fetch -q origin "$base_branch"
if git worktree list --porcelain | grep -qx "branch refs/heads/$base_branch"; then
  git switch -q --detach "origin/$base_branch"
else
  git switch -q "$base_branch"
  git pull -q --ff-only
fi
git branch -D "$branch_name"
if git ls-remote --exit-code --heads origin "$branch_name" >/dev/null 2>&1; then
  git push origin --delete "$branch_name"
fi

echo "Merged: $pr_url"
echo "Status: $done_status"
