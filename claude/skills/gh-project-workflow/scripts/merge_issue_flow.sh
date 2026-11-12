#!/usr/bin/env bash
# Merges an issue's pull request once the owner has approved it, or closes out one that
# GitHub's auto-merge already merged.
#
# The owner's Approve of the latest commit is the word to merge (rules/shared.md), and the
# server enforces it: the main ruleset counts only an approval from the owners team (#22) and
# merges only a branch that contains the latest main (#37). So this script runs without a
# permission prompt; its own checks only say what is missing.
set -euo pipefail

board_sh="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/board.sh"
[[ -r "$board_sh" ]] || { echo "Error: $board_sh is missing: run this script from its skill directory, .claude/skills/gh-project-workflow/scripts/, where board.sh sits next to it" >&2; exit 1; }
# shellcheck source=board.sh
source "$board_sh"

usage() {
  cat <<'USAGE'
Usage: merge_issue_flow.sh [options]

Run from the issue branch. When the pull request is open, it needs the owner's Approve of the
latest commit. A branch behind the base is first updated by merging the base into it, which
keeps the approval. The script then turns on auto-merge (squash) and waits until GitHub reports
the pull request MERGED. When GitHub already merged it, only the closeout runs: board Status
"Done", the branch deleted locally and on GitHub, back to the base branch. A pull request that
did not merge keeps its branch.

Options:
  --repo <owner/repo>           Default: GHWF_REPO, then the origin remote
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --done-status <option>        Default: Done
  --help                        Show this help

Environment: GHWF_MERGE_WAIT, seconds to wait for GitHub to merge (default 900);
GHWF_MERGE_POLL, seconds between looks (default 10).
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

merge_wait="${GHWF_MERGE_WAIT:-900}"

# Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones.
pr_url="$(gh pr list -R "$repo" --head "$branch_name" --state open --json url --jq '.[0].url // empty')"
if [[ -z "$pr_url" ]]; then
  # Merged by GitHub's auto-merge after the owner's Approve: only the closeout is left.
  pr_url="$(gh pr list -R "$repo" --head "$branch_name" --state merged --json url --jq '.[0].url // empty')"
  [[ -n "$pr_url" ]] || die "No open or merged pull request for branch '$branch_name'"
  echo "Already merged by GitHub: $pr_url"
else
  read -r review_decision merge_state <<<"$(gh pr view "$pr_url" --json reviewDecision,mergeStateStatus \
    --jq '"\(.reviewDecision // "NONE") \(.mergeStateStatus)"')"
  [[ "$review_decision" == "APPROVED" ]] \
    || die "Waiting for the owner's Approve of the latest commit (review: $review_decision): $pr_url"
  if [[ "$merge_state" == "BEHIND" ]]; then
    echo "The branch is behind $base_branch: merging $base_branch into it; the approval stays."
    gh pr update-branch "$pr_url"
  fi
  # Auto-merge merges at once when everything is in place, or as soon as the checks pass.
  # Without auto-merge in the repository, a plain squash merge works when nothing is pending.
  if ! auto_out="$(gh pr merge "$pr_url" --auto --squash 2>&1)"; then
    echo "Auto-merge not available ($auto_out); trying a plain squash merge." >&2
    gh pr merge "$pr_url" --squash \
      || die "Could not merge $pr_url. If checks are still running, run this again when they pass; for a merge that waits by itself, the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37)."
  fi
fi

# Only a confirmed merge may delete the branch: cleaning up after a failed merge closes
# the pull request with the work still in it.
# A branch can fall behind while it waits, when another pull request merges first; auto-merge
# then waits for ever, since the base requires up-to-date branches. So the loop updates it (#72).
poll="${GHWF_MERGE_POLL:-10}"
read_state() { read -r pr_state merge_state <<<"$(gh pr view "$pr_url" --json state,mergeStateStatus \
  --jq '"\(.state) \(.mergeStateStatus)"')"; }
read_state
waited=0
while [[ "$pr_state" != "MERGED" && "$waited" -lt "$merge_wait" ]]; do
  if [[ "${merge_state:-}" == "BEHIND" ]]; then
    echo "The branch fell behind $base_branch while waiting: merging $base_branch into it; the approval stays."
    gh pr update-branch "$pr_url" || true
  fi
  sleep "$poll"; waited=$((waited + (poll > 0 ? poll : 1)))
  read_state
done
[[ "$pr_state" == "MERGED" ]] \
  || die "Pull request is $pr_state, not merged yet; auto-merge stays on and branch '$branch_name' is kept. Run this again later: $pr_url"

# A failed board update ends the run with an error (#41), after the closeout below: the merge is done.
board_error=""
if [[ -n "$owner" && -n "$project_number" ]]; then
  issue_url="$(gh issue view "$issue_number" -R "$repo" --json url --jq '.url')"
  output="$(board_set_field "$project_number" "$owner" "$issue_url" "$status_field" "$done_status" 2>&1)" \
    || board_error="The board $status_field of #$issue_number is not '$done_status': $(board_reason "$output"). Set it alone: $(printf '%q ' bash "$board_sh" set "$project_number" "$owner" "$issue_url" "$status_field" "$done_status")"
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
[[ -z "$board_error" ]] || die "$board_error"
echo "Status: $done_status"
