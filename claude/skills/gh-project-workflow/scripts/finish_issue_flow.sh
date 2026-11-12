#!/usr/bin/env bash
set -euo pipefail

board_sh="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/board.sh"
[[ -r "$board_sh" ]] || { echo "Error: $board_sh is missing: run this script from its skill directory, .claude/skills/gh-project-workflow/scripts/, where board.sh sits next to it" >&2; exit 1; }
# shellcheck source=board.sh
source "$board_sh"

usage() {
  cat <<'USAGE'
Usage: finish_issue_flow.sh [options]

Run from the issue branch (<number>-<slug>, made by start_issue_flow.sh).

Commits what is staged (or the --add paths), pushes, opens the pull request and sets the
board Status to "In review". Never merges: merging is merge_issue_flow.sh, a separate
command so that a permission rule can make it ask the owner.

Subjects follow Conventional Commits with the issue in the scope:
  feat(GH-12): add landing page
GitHub appends the PR number on squash: "feat(GH-12): add landing page (#13)".

Options:
  --type <type>                 feat|fix|docs|refactor|test|perf|build|ci|chore (default: the type of
                                the branch's first commit, else feat)
  --summary <text>              Subject text after "type(GH-n): " (default: the issue title)
  --commit-message-file <path>  Full commit message (subject, body, trailers); overrides --summary for the commit
  --pr-body-file <path>         PR description. Required when the PR is opened. Must contain a line
                                "ADR: none" or "ADR: adr/NNNN-....md". "Closes #<issue>" is added if missing.
  --add <path>                  Stage this path before committing (repeatable)
  --no-commit                   Commit nothing; push and open the PR for commits already on the branch
  --repo <owner/repo>           Default: GHWF_REPO, then the origin remote
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --review-status <option>      Default: In review
  --help                        Show this help
USAGE
}

die() {
  echo "Error: $*" >&2
  exit 1
}

infer_repo_from_origin() {
  local origin_url
  origin_url="$(git remote get-url origin 2>/dev/null || true)"
  if [[ "$origin_url" =~ github.com[:/]([^/]+/[^/.]+)(\.git)?$ ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

# board.sh resolves the item, the field and its option through the project.
set_status() {
  local status="$1"
  [[ -n "$owner" && -n "$project_number" && -n "$status" ]] || return 0

  local issue_url output
  issue_url="$(gh issue view "$issue_number" -R "$repo" --json url --jq '.url' 2>/dev/null || true)"
  [[ -n "$issue_url" ]] || { echo "Warning: issue #$issue_number not found, Status not set" >&2; return 0; }

  # A failed board update ends the run (#41). The PR is open by now, so only the status is left to set.
  output="$(board_set_field "$project_number" "$owner" "$issue_url" "$status_field" "$status" 2>&1)" \
    || die "The pull request is open: $pr_url. But the board $status_field of #$issue_number is not '$status': $(board_reason "$output"). Set it alone: $(printf '%q ' bash "$board_sh" set "$project_number" "$owner" "$issue_url" "$status_field" "$status")"
}

repo="${GHWF_REPO:-}"
owner="${GHWF_OWNER:-}"
project_number="${GHWF_PROJECT_NUMBER:-}"
base_branch="${GHWF_DEFAULT_BASE:-main}"
status_field="${GHWF_STATUS_FIELD:-Status}"
review_status="${GHWF_REVIEW_STATUS:-In review}"

commit_type=""
summary=""
commit_message_file=""
pr_body_file=""
no_commit=0
add_paths=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) commit_type="$2"; shift 2 ;;
    --summary) summary="$2"; shift 2 ;;
    --commit-message-file) commit_message_file="$2"; shift 2 ;;
    --pr-body-file) pr_body_file="$2"; shift 2 ;;
    --add) add_paths+=("$2"); shift 2 ;;
    --no-commit) no_commit=1; shift ;;
    --repo) repo="$2"; shift 2 ;;
    --owner) owner="$2"; shift 2 ;;
    --project-number) project_number="$2"; shift 2 ;;
    --base) base_branch="$2"; shift 2 ;;
    --status-field) status_field="$2"; shift 2 ;;
    --review-status) review_status="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

# Without --type the PR follows the branch: the type of its first `type(GH-n): …` commit,
# else feat. (`--no-commit --summary` used to title a branch of fixes `feat`.)
if [[ -z "$commit_type" ]]; then
  first_subject="$(git log --reverse --format=%s "origin/$base_branch..HEAD" 2>/dev/null | head -n1 || true)"
  [[ "$first_subject" =~ ^([a-z]+)\(GH-[0-9]+\):\  ]] && commit_type="${BASH_REMATCH[1]}"
  commit_type="${commit_type:-feat}"
fi

[[ -n "$repo" ]] || repo="$(infer_repo_from_origin)"
[[ -n "$repo" ]] || die "--repo or GHWF_REPO is required"

branch_name="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch_name" != "HEAD" ]] || die "Detached HEAD is not supported"
[[ "$branch_name" != "$base_branch" ]] || die "On the base branch '$base_branch': run from the issue branch"

# The issue is the branch's number: CI's pull request gate accepts no other (#21).
[[ "$branch_name" =~ ^([0-9]+)- ]] \
  || die "The branch '$branch_name' is not <number>-<slug>: open the issue with start_issue_flow.sh and move the work to its branch"
issue_number="${BASH_REMATCH[1]}"

# Everything that can be wrong with the inputs is checked before the commit and the push:
# a run that dies after the push leaves a branch with no pull request and a confusing rerun.
if [[ -n "$pr_body_file" ]]; then
  [[ -r "$pr_body_file" ]] || die "PR body file '$pr_body_file' not found"
fi
if [[ -n "$commit_message_file" ]]; then
  [[ -r "$commit_message_file" ]] || die "Commit message file '$commit_message_file' not found"
fi

# Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones.
pr_url="$(gh pr list -R "$repo" --head "$branch_name" --state open --json url --jq '.[0].url // empty')"
[[ -n "$pr_url" || -n "$pr_body_file" ]] || die "--pr-body-file is required to open the pull request"

# The subject: --summary, else the commit message file's first line, else the issue title.
if [[ -n "$summary" ]]; then
  subject="$commit_type(GH-$issue_number): $summary"
elif [[ -n "$commit_message_file" ]]; then
  subject="$(head -n1 "$commit_message_file")"
else
  issue_title="$(gh issue view "$issue_number" -R "$repo" --json title --jq '.title')"
  subject="$commit_type(GH-$issue_number): ${issue_title,}"
fi
# The title and the body pass the rules CI checks on the pull request (#21): one implementation, in kit.
kit="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../../../.." && pwd)/bin/kit"
shape_args=(--shape --branch "$branch_name" --title "$subject")
# The body file is checked only when it becomes a new PR's body; an open PR keeps its own.
[[ -n "$pr_url" || -z "$pr_body_file" ]] || shape_args+=(--body-file "$pr_body_file")
shape_out="$(bb "$kit" gate "${shape_args[@]}" 2>&1)" \
  || die "The pull request would fail CI's shape check; nothing was committed or pushed:"$'\n'"$shape_out"

if [[ "$no_commit" -eq 0 ]]; then
  [[ "${#add_paths[@]}" -eq 0 ]] || git add -- "${add_paths[@]}"
  git diff --cached --quiet && die "Nothing staged to commit (use --add <path>, or --no-commit)"
  if [[ -n "$commit_message_file" ]]; then
    git commit -F "$commit_message_file"
  else
    git commit -m "$subject"
  fi
fi

git push -u origin "$branch_name"

if [[ -z "$pr_url" ]]; then
  body="$(cat "$pr_body_file")"
  grep -Eqi "(close[sd]?|fix(e[sd])?|resolve[sd]?) #$issue_number\b" <<<"$body" \
    || body="Closes #$issue_number"$'\n\n'"$body"

  pr_url="$(gh pr create -R "$repo" --base "$base_branch" --head "$branch_name" \
              --title "$subject" --body "$body" | tail -n1 | tr -d '\r')"
fi

# On the reviewers' desk, assigned to the person behind the agent (#52). Repeating it is harmless.
edit_args=()
[[ -n "${GHWF_REVIEWERS:-}" ]] && edit_args+=(--add-reviewer "$GHWF_REVIEWERS")
if [[ -n "${GHWF_HUMAN:-}" ]]; then
  edit_args+=(--add-assignee "$GHWF_HUMAN")
else
  echo "Warning: no GHWF_HUMAN, so the PR has no assignee. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login." >&2
fi
if [[ "${#edit_args[@]}" -gt 0 ]] && ! edit_out="$(gh pr edit "$pr_url" "${edit_args[@]}" 2>&1)"; then
  echo "Warning: could not request the review or set the assignee: $edit_out" >&2
fi

# The owner's Approve is the word to merge: with auto-merge on, GitHub merges once the owner
# approved the latest commit, the checks passed and the branch contains the latest base.
if ! auto_out="$(gh pr merge "$pr_url" --auto --squash 2>&1)"; then
  echo "Warning: auto-merge could not be turned on: $auto_out" >&2
  echo "  Fix: the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37); until then merge_issue_flow.sh merges after the Approve." >&2
fi

set_status "$review_status"

echo "Subject: $subject"
echo "PR URL: $pr_url"
echo "Status: $review_status"
