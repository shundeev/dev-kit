#!/usr/bin/env bash
set -euo pipefail

board_sh="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/board.sh"
[[ -r "$board_sh" ]] || { echo "Error: $board_sh is missing: run this script from its skill directory, .claude/skills/gh-project-workflow/scripts/, where board.sh sits next to it" >&2; exit 1; }
# shellcheck source=board.sh
source "$board_sh"

usage() {
  cat <<'USAGE'
Usage: start_issue_flow.sh --title <title> [options]

Create work in GitHub Project, assign fields, ensure issue exists, and create/checkout dev branch.

Options:
  --title <text>                Required issue or draft title
  --body <text>                 Body text
  --body-file <path>            Body file path
  --labels <csv>                Comma-separated labels
  --assignees <csv>             Comma-separated assignees (supports @me)
  --issue <number>              Reuse existing issue number instead of creating one
  --showcase <option>           Showcase field option (default: GHWF_SHOWCASE)
  --priority <option>           Required. Priority field option: Urgent|High|Medium|Low
  --size <option>               Size field option to set
  --milestone <title>           Open milestone to put the issue in (must already exist)
  --status <option>             Status field option to set
  --mode <issue|draft-convert>  Default: issue
  --owner <login>               Project owner (default: GHWF_OWNER)
  --repo <owner/repo>           Repo (default: GHWF_REPO)
  --project-number <number>     Project number (default: GHWF_PROJECT_NUMBER)
  --showcase-field <name>       Field name (default: GHWF_SHOWCASE_FIELD or Showcase)
  --priority-field <name>       Field name (default: GHWF_PRIORITY_FIELD or Priority)
  --size-field <name>           Field name (default: GHWF_SIZE_FIELD or Size)
  --status-field <name>         Field name (default: GHWF_STATUS_FIELD or Status)
  --base <branch>               Base branch for dev branch (default: GHWF_DEFAULT_BASE or main)
  --branch-name <name>          Override branch name
  --no-reuse-existing           Do not reuse exact-title project item or issue
  --skip-branch                 Do not create the development branch at all
  --no-checkout                 Create and link the branch, leave the calling worktree alone
                                (mutually exclusive with --skip-branch)
  --skip-retro                  Start although a merged issue lacks its retrospective. Only the owner
                                waives this: a permission rule asks them first (#56)
  --help                        Show this help
USAGE
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

die() {
  echo "Error: $*" >&2
  exit 1
}

graphql_remaining() {
  gh api rate_limit --jq '.resources.graphql.remaining' 2>/dev/null || true
}

# The field and its option are named; board.sh resolves them through the project.
# The board's Size, mirrored as a `size:*` label: CI's design approval gate reads labels, it
# cannot read a user's board (dev-kit#26). Other size labels are removed.
set_size_label() {
  [[ -n "$size_option" && -n "$issue_number" ]] || return 0
  gh label create "size:$size_option" -R "$repo" --color BFD4F2 \
    --description "Task size (ADR-0002), mirrors the board field" --force >/dev/null 2>&1 || true
  local old args=()
  while IFS= read -r old; do
    [[ -n "$old" && "$old" != "size:$size_option" ]] && args+=(--remove-label "$old")
  done < <(gh issue view "$issue_number" -R "$repo" --json labels --jq '.labels[].name | select(startswith("size:"))' 2>/dev/null)
  gh issue edit "$issue_number" -R "$repo" --add-label "size:$size_option" "${args[@]}" >/dev/null \
    || echo "Warning: could not set the label size:$size_option on #$issue_number" >&2
}

# A failed board update ends the run (#41): the issue exists, the board does not show it as asked.
apply_project_field() {
  local field_name="$1" option_name="$2" output
  [[ -n "$option_name" ]] || return 0
  output="$(board_set_field "$project_number" "$owner" "$issue_url" "$field_name" "$option_name" 2>&1)" && return 0
  die "#$issue_number is filed, but its $field_name on the board is not '$option_name': $(board_reason "$output"). Then run this script again with --issue $issue_number"
}

# `gh project item-list` asks for every field value of every item — 203 points of the 5000
# hourly GraphQL budget on a board this size. Matching an item needs four fields, so ask for
# those. Output keeps the shape the CLI produced, so the jq matchers below are unchanged.
project_items_json() {
  local query='
    query($owner: String!, $number: Int!, $status: String!, $after: String) {
      owner: repositoryOwner(login: $owner) {
        ... on ProjectV2Owner {
          projectV2(number: $number) {
            items(first: 100, after: $after) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                isArchived
                status: fieldValueByName(name: $status) { ... on ProjectV2ItemFieldSingleSelectValue { name } }
                content {
                  __typename
                  ... on Issue { number url title state }
                  ... on PullRequest { number url title }
                  ... on DraftIssue { title }
                }
              }
            }
          }
        }
      }
    }'

  # Pages are gathered as lines and joined by jq reading stdin: a board of ~650 items no longer
  # fits into one command-line argument (128 KiB), which --argjson needed.
  local after="null" page pages="" has_next
  while :; do
    if [[ "$after" == "null" ]]; then
      page="$(gh api graphql -f query="$query" -F owner="$owner" -F number="$project_number" -f status="$status_field")"
    else
      page="$(gh api graphql -f query="$query" -F owner="$owner" -F number="$project_number" -f status="$status_field" -F after="$after")"
    fi

    # Archived items are out of the CLI's listing too; reusing one would resurrect
    # work the owner had put away.
    pages+="$(jq -c '[.data.owner.projectV2.items.nodes[]
                      | select(.isArchived | not)
                      | {id: .id,
                         title: (.content.title // ""),
                         status: (.status.name // ""),
                         content: {type: .content.__typename,
                                   number: .content.number,
                                   url: .content.url,
                                   state: .content.state}}]' <<<"$page")"$'\n'

    has_next="$(jq -r '.data.owner.projectV2.items.pageInfo.hasNextPage' <<<"$page")"
    [[ "$has_next" == "true" ]] || break
    after="$(jq -r '.data.owner.projectV2.items.pageInfo.endCursor' <<<"$page")"
  done

  jq -cs '{items: add}' <<<"${pages:-[]}"
}

# One board serves several repositories, so a title only matches an issue of this repo
# (or a draft, which belongs to no repo yet). Same-title work in another repo is not ours.
# Only open work counts (#12): a closed issue or a Done card with a recurring title is history,
# and reusing it would move it back to In progress and drop the new body.
find_project_item_by_title() {
  local items_json="$1"
  local item_title="$2"
  jq -c --arg item_title "$item_title" --arg prefix "https://github.com/$repo/" --arg done_status "${GHWF_DONE_STATUS:-Done}" \
    '.items[] | select(.title == $item_title)
              | select(.content.type == "DraftIssue" or (.content.type == "Issue" and ((.content.url // "") | startswith($prefix))))
              | select(.content.state != "CLOSED" and .status != $done_status)' \
    <<<"$items_json" | head -n1
}

find_issue_by_title() {
  local issue_title="$1"
  gh issue list -R "$repo" --state open --search "$issue_title in:title" --limit 20 --json number,title,url \
    | jq -c --arg issue_title "$issue_title" '.[] | select(.title == $issue_title)' \
    | head -n1
}

label_exists() {
  local label="$1"

  if [[ -z "${labels_json:-}" ]]; then
    labels_json="$(gh label list -R "$repo" --limit 200 --json name 2>/dev/null || true)"
  fi

  [[ -n "$labels_json" ]] || return 1
  jq -e --arg label "$label" '.[] | select(.name == $label)' <<<"$labels_json" >/dev/null
}

append_existing_labels() {
  # shellcheck disable=SC2178 # nameref to the caller's array
  local -n args_ref=$1
  local csv="$2"
  local flag="${3:---label}"

  [[ -n "$csv" ]] || return 0

  IFS=',' read -r -a labels <<<"$csv"
  for label in "${labels[@]}"; do
    label="$(trim "$label")"
    [[ -n "$label" ]] || continue
    # A missing label is created, as size:* labels are (#41)
    label_exists "$label" || gh label create "$label" -R "$repo" >/dev/null 2>&1 \
      || die "Label '$label' does not exist in $repo and could not be created: create it on GitHub (Issues > Labels), or drop it from --labels"
    args_ref+=("$flag" "$label")
  done
}

append_assignees() {
  # shellcheck disable=SC2178 # nameref to the caller's array
  local -n args_ref=$1
  local csv="$2"
  local flag="${3:---assignee}"

  [[ -n "$csv" ]] || return 0

  IFS=',' read -r -a assignees <<<"$csv"
  for assignee in "${assignees[@]}"; do
    assignee="$(trim "$assignee")"
    [[ -n "$assignee" ]] && args_ref+=("$flag" "$assignee")
  done
}

convert_project_draft_to_issue() {
  local draft_item_id="$1"

  local repo_id
  repo_id="$(gh repo view "$repo" --json id --jq '.id')"
  [[ -n "$repo_id" ]] || die "Failed to resolve repository id"

  # The schema (introspected, #12): input {itemId, repositoryId}, payload {item: ProjectV2Item}.
  local convert_query convert_json
  convert_query='mutation($itemId:ID!,$repositoryId:ID!){ convertProjectV2DraftIssueItemToIssue(input:{itemId:$itemId,repositoryId:$repositoryId}) { item { content { ... on Issue { number url } } } } }'
  convert_json="$(gh api graphql -f query="$convert_query" -F itemId="$draft_item_id" -F repositoryId="$repo_id")"

  issue_number="$(jq -r '.data.convertProjectV2DraftIssueItemToIssue.item.content.number // empty' <<<"$convert_json")"
  issue_url="$(jq -r '.data.convertProjectV2DraftIssueItemToIssue.item.content.url // empty' <<<"$convert_json")"
  [[ -n "$issue_number" && -n "$issue_url" ]] || die "Draft item conversion to issue failed"
}

use_project_item() {
  local item="$1"

  item_id="$(jq -r '.id // empty' <<<"$item")"
  [[ -n "$item_id" ]] || die "Existing project item has no id"

  local content_type
  content_type="$(jq -r '.content.type // empty' <<<"$item")"

  case "$content_type" in
    Issue)
      issue_number="$(jq -r '.content.number // empty' <<<"$item")"
      issue_url="$(jq -r '.content.url // empty' <<<"$item")"
      [[ -n "$issue_number" && -n "$issue_url" ]] || die "Existing issue item is missing issue data"
      ;;
    DraftIssue)
      convert_project_draft_to_issue "$item_id"
      ;;
    *)
      die "Existing project item '$title' is not an issue or draft issue"
      ;;
  esac
}

owner="${GHWF_OWNER:-}"
repo="${GHWF_REPO:-}"
project_number="${GHWF_PROJECT_NUMBER:-}"
showcase_field="${GHWF_SHOWCASE_FIELD:-Showcase}"
priority_field="${GHWF_PRIORITY_FIELD:-Priority}"
size_field="${GHWF_SIZE_FIELD:-Size}"
status_field="${GHWF_STATUS_FIELD:-Status}"
base_branch="${GHWF_DEFAULT_BASE:-main}"

title=""
body=""
body_file=""
labels_csv=""
assignees_csv="${GHWF_DEFAULT_ASSIGNEES:-@me}"
issue_number_override=""
showcase_option="${GHWF_SHOWCASE:-}"
priority_option="${GHWF_DEFAULT_PRIORITY:-}"
size_option="${GHWF_DEFAULT_SIZE:-}"
milestone_title=""
status_option="${GHWF_DEFAULT_STATUS:-Backlog}"
mode="issue"
branch_name=""
reuse_existing=1
skip_branch=0
no_checkout=0
skip_retro=0
labels_json=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --title)
      title="$2"
      shift 2
      ;;
    --body)
      body="$2"
      shift 2
      ;;
    --body-file)
      body_file="$2"
      shift 2
      ;;
    --labels)
      labels_csv="$2"
      shift 2
      ;;
    --assignees)
      assignees_csv="$2"
      shift 2
      ;;
    --issue)
      issue_number_override="$2"
      shift 2
      ;;
    --showcase)
      showcase_option="$2"
      shift 2
      ;;
    --priority)
      priority_option="$2"
      shift 2
      ;;
    --size)
      size_option="$2"
      shift 2
      ;;
    --milestone)
      milestone_title="$2"
      shift 2
      ;;
    --status)
      status_option="$2"
      shift 2
      ;;
    --mode)
      mode="$2"
      shift 2
      ;;
    --owner)
      owner="$2"
      shift 2
      ;;
    --repo)
      repo="$2"
      shift 2
      ;;
    --project-number)
      project_number="$2"
      shift 2
      ;;
    --showcase-field)
      showcase_field="$2"
      shift 2
      ;;
    --priority-field)
      priority_field="$2"
      shift 2
      ;;
    --size-field)
      size_field="$2"
      shift 2
      ;;
    --status-field)
      status_field="$2"
      shift 2
      ;;
    --base)
      base_branch="$2"
      shift 2
      ;;
    --branch-name)
      branch_name="$2"
      shift 2
      ;;
    --no-reuse-existing)
      reuse_existing=0
      shift
      ;;
    --skip-branch)
      skip_branch=1
      shift
      ;;
    --no-checkout)
      no_checkout=1
      shift
      ;;
    --skip-retro)
      skip_retro=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$title" ]] || die "--title is required"
[[ -n "$owner" ]] || die "--owner or GHWF_OWNER is required"
[[ -n "$repo" ]] || die "--repo or GHWF_REPO is required"
[[ -n "$project_number" ]] || die "--project-number or GHWF_PROJECT_NUMBER is required"
[[ "$mode" == "issue" || "$mode" == "draft-convert" ]] || die "--mode must be 'issue' or 'draft-convert'"
[[ "$skip_branch" -eq 0 || "$no_checkout" -eq 0 ]] || die "--skip-branch and --no-checkout are mutually exclusive: one creates no branch, the other creates one without touching the working tree"

# Checked before anything is created: a run that files the issue and then dies on the
# priority leaves half-tracked work behind. An issue without a priority is not filed at all.
case "$priority_option" in
  Urgent|High|Medium|Low) ;;
  '') die "--priority is required: Urgent|High|Medium|Low" ;;
  *)  die "Priority '$priority_option' is not on the scale Urgent|High|Medium|Low" ;;
esac

# Same reason: an unknown milestone must stop the run before an issue exists, not after,
# when `gh issue edit --milestone` refuses it. Milestones are never created here.
if [[ -n "$milestone_title" ]]; then
  open_milestones="$(gh api "repos/$repo/milestones?state=open" --paginate --jq '.[].title')"
  grep -Fxq -- "$milestone_title" <<<"$open_milestones" \
    || die "Milestone '$milestone_title' is not an open milestone of '$repo'. Open: $(paste -sd '/' <<<"$open_milestones")"
fi

# A merged task's retrospective comes before new work (#28, #56). Checked before anything is
# created. When GitHub cannot be asked, say so and go on: the check guards the process, not data.
if [[ "$skip_retro" -eq 0 ]]; then
  kit="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../../../.." && pwd)/bin/kit"
  retro_out="$(bb "$kit" retro --missing --repo "$repo" 2>&1)" && rc=0 || rc=$? # set -e must not end the run here
  case $rc in
    0) ;;
    1) die "$retro_out" ;;
    *) echo "Warning: could not check for merged issues without a retrospective: $retro_out" >&2 ;;
  esac
fi

graphql_budget_before="$(graphql_remaining)"

issue_url=""
issue_number=""
item_id=""

# --issue N names the work: no title is matched, with reuse on or off (#12).
if [[ -n "$issue_number_override" ]]; then
  issue_number="$issue_number_override"
  issue_url="$(gh issue view "$issue_number" -R "$repo" --json url --jq '.url' 2>&1)" \
    || die "Issue #$issue_number not found in $repo ($issue_url): check the number, or start without --issue"
  item_id="$(board_gh project item-add "$project_number" --owner "$owner" --url "$issue_url" --format json --jq '.id' 2>&1)" \
    || die "#$issue_number could not be put on the board: $(board_reason "$item_id")"
elif [[ "$reuse_existing" -eq 1 ]]; then
  items_json="$(project_items_json)"
  existing_item="$(find_project_item_by_title "$items_json" "$title")"

  if [[ -n "$existing_item" ]]; then
    use_project_item "$existing_item"
  else
    existing_issue="$(find_issue_by_title "$title")"
    if [[ -n "$existing_issue" ]]; then
      issue_number="$(jq -r '.number // empty' <<<"$existing_issue")"
      issue_url="$(jq -r '.url // empty' <<<"$existing_issue")"
      [[ -n "$issue_number" && -n "$issue_url" ]] || die "Existing issue is missing issue data"

      item_id="$(board_gh project item-add "$project_number" --owner "$owner" --url "$issue_url" --format json --jq '.id' 2>&1)" \
        || die "#$issue_number could not be put on the board: $(board_reason "$item_id")"
    fi
  fi
fi

if [[ -z "$issue_number" && "$mode" == "issue" ]]; then
  issue_args=(issue create -R "$repo" --title "$title")

  # gh issue create without a body asks for one, and without a terminal it fails (#12)
  if [[ -n "$body_file" ]]; then
    issue_args+=(--body-file "$body_file")
  else
    issue_args+=(--body "$body")
  fi

  append_existing_labels issue_args "$labels_csv"
  append_assignees issue_args "$assignees_csv"

  issue_url="$(gh "${issue_args[@]}" | tail -n1 | tr -d '\r')"
  [[ -n "$issue_url" ]] || die "Issue creation failed"

  issue_number="${issue_url##*/}"
  item_id="$(board_gh project item-add "$project_number" --owner "$owner" --url "$issue_url" --format json --jq '.id' 2>&1)" \
    || die "#$issue_number is filed but could not be put on the board: $(board_reason "$item_id"). Then run this script again with --issue $issue_number"
elif [[ -z "$issue_number" ]]; then
  draft_args=(project item-create "$project_number" --owner "$owner" --title "$title" --format json)

  if [[ -n "$body_file" ]]; then
    draft_args+=(--body "$(cat "$body_file")")
  elif [[ -n "$body" ]]; then
    draft_args+=(--body "$body")
  fi

  item_json="$(gh "${draft_args[@]}")"
  item_id="$(jq -r '.id // empty' <<<"$item_json")"
  [[ -n "$item_id" ]] || die "Draft item creation failed"

  convert_project_draft_to_issue "$item_id"
fi

if [[ -n "$issue_number" ]]; then
  edit_args=(issue edit "$issue_number" -R "$repo")
  append_existing_labels edit_args "$labels_csv" "--add-label"
  append_assignees edit_args "$assignees_csv" "--add-assignee"
  [[ -z "$milestone_title" ]] || edit_args+=(--milestone "$milestone_title")
  if [[ "${#edit_args[@]}" -gt 5 ]]; then
    gh "${edit_args[@]}" >/dev/null
  fi
fi

apply_project_field "$showcase_field" "$showcase_option"
apply_project_field "$priority_field" "$priority_option"
apply_project_field "$size_field" "$size_option"
set_size_label
apply_project_field "$status_field" "$status_option"

development_branch=""
if [[ "$skip_branch" -eq 0 ]]; then
  # `gh issue develop` with no --name appends -1 to a name already taken, so a rerun forks a
  # duplicate branch off the same issue. Handing it the branch already linked to the issue
  # makes the run idempotent: gh reuses that branch and creates nothing.
  if [[ -z "$branch_name" ]]; then
    branch_name="$(gh issue develop --list "$issue_number" -R "$repo" 2>/dev/null | head -n1 | cut -f1 || true)"
  fi

  develop_args=(issue develop "$issue_number" -R "$repo")
  [[ "$no_checkout" -eq 1 ]] || develop_args+=(--checkout)
  [[ -n "$base_branch" ]] && develop_args+=(--base "$base_branch")
  [[ -n "$branch_name" ]] && develop_args+=(--name "$branch_name")

  # --no-checkout leaves the working tree alone, so the branch name cannot be read back from
  # it. gh prints the branch URL; everything after /tree/ is the name, slashes included.
  develop_url="$(gh "${develop_args[@]}" | tail -n1 | tr -d '\r')"
  if [[ "$no_checkout" -eq 1 ]]; then
    development_branch="${develop_url##*/tree/}"
  else
    development_branch="$(git branch --show-current || true)"
  fi
fi

echo "Issue URL: $issue_url"
echo "Issue Number: $issue_number"
echo "Project Item ID: $item_id"
if [[ -n "$development_branch" ]]; then
  if [[ "$no_checkout" -eq 1 ]]; then
    echo "Branch: $development_branch"
  else
    echo "Checked Out Branch: $development_branch"
  fi
fi

# A run that quietly eats a tenth of the hourly budget is only noticed an hour later, when
# the next run is refused. Print the price.
if [[ -n "$graphql_budget_before" ]]; then
  graphql_budget_after="$(graphql_remaining)"
  if [[ -n "$graphql_budget_after" ]]; then
    echo "GraphQL Cost: $(( graphql_budget_before - graphql_budget_after )) points (${graphql_budget_after} left this hour)"
  fi
fi
