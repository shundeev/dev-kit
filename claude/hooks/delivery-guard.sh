#!/usr/bin/env bash
# PreToolUse(Bash). The delivery flow is not optional — see AGENTS.md, "Delivery".
#
# Refuses the commands that create work GitHub can see but the project board cannot.
# The gh-project-workflow scripts pass untouched by construction: this hook sees only
# the command string the agent itself submitted, never the `gh` calls a script makes in
# a subprocess. Running the wrapper is the escape hatch, and it needs no marker.
#
# Gate on the invariant ("an issue exists for this work"), not on the verb: `gh pr create`
# is correct from an issue branch and wrong from the base branch.
#
# Ported from the Sprecha repository (the-kind-robots/learning-app).
set -uo pipefail
set -f  # no globbing when statements are word-split below

decide() { # decide <allow|deny|ask> <reason>
  jq -nc --arg d "$1" --arg r "$2" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",
                          permissionDecision:$d,
                          permissionDecisionReason:$r}}'
  exit 0
}

# Fail closed: without jq the guard cannot judge anything (ADR-0001, rule 3).
command -v jq >/dev/null 2>&1 || {
  echo "Refused: the delivery guard needs jq and it is not installed. Ask the owner to install jq." >&2
  exit 2
}

# `gh pr create` may name its source branch explicitly. Judge the branch the command
# actually pushes from, not the one this hook's working directory happens to be on.
#
# Words arrive already split (heredoc bodies stripped, `set -f` so no globbing), so quoting
# is gone by this point: a `--head` inside a quoted title would look like a flag. A candidate
# carrying a quote character is therefore not treated as a branch name.
head_branch() { # head_branch <words...> -> branch named by --head/-H, empty when absent
  local w cand
  while [ "$#" -gt 0 ]; do
    w=$1; shift
    case "$w" in
      --head=*) cand=${w#--head=} ;;
      -H=*)     cand=${w#-H=} ;;
      --head|-H) cand=${1:-} ;;
      *) continue ;;
    esac
    case "$cand" in ''|-*|*[\"\']*) continue ;; esac
    printf '%s' "${cand#*:}"   # a fork's head is `owner:branch`
    return
  done
}

cmd=$(jq -r '.tool_input.command // empty' 2>/dev/null || true)
[ -n "$cmd" ] || exit 0

BASE=${GHWF_DEFAULT_BASE:-main}
REPO=${GHWF_REPO:-<owner/repo>}
START='bash .claude/skills/gh-project-workflow/scripts/start_issue_flow.sh'
FINISH='bash .claude/skills/gh-project-workflow/scripts/finish_issue_flow.sh'

# Drop heredoc bodies. The opener line is kept — it carries the real command — and
# everything up to the terminator is skipped, so prose inside an issue or PR body can
# never be mistaken for a command.
stripped=$(printf '%s\n' "$cmd" | awk '
  skip { if ($0 ~ term) skip = 0; next }
  { print
    if (match($0, /<<-?[ \t]*[\047"]?[A-Za-z_][A-Za-z0-9_]*[\047"]?/)) {
      d = substr($0, RSTART, RLENGTH)
      sub(/^<<-?[ \t]*/, "", d); gsub(/[\047"]/, "", d)
      term = "^[ \t]*" d "[ \t]*$"; skip = 1 } }')

# One statement per line, so only statement heads are ever inspected. The bodies of $(…) run
# too, so they are judged as statements of their own.
inner=$(printf '%s\n' "$stripped" | grep -o '\$([^()]*)' | sed -E 's/^\$\(//; s/\)$//' || true)
statements=$(printf '%s\n%s\n' "$stripped" "$inner" | sed -E 's/(&&|\|\||[;|&])/\n/g')

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)

# `gh api` reads or writes depending on its flags (#54). Prints what the call would change and
# succeeds when it can change GitHub: a method other than GET; fields without a method, which
# make gh send POST; a GraphQL mutation; a GraphQL query read from a file, which this hook
# cannot see. A read prints nothing and fails.
api_write() { # api_write <statement> <words…>
  local st=$1 w method="" fields="" endpoint="" skip=""
  shift
  for w in "$@"; do
    if [ -n "$skip" ]; then
      [ "$skip" = method ] && method=$w
      skip=""; continue
    fi
    case "$w" in
      --method=*) method=${w#--method=} ;;
      -X|--method) skip=method ;;
      -X?*) method=${w#-X} ;;
      -f|-F|--field|--raw-field|--input) fields=yes; skip=value ;;
      -H|--header|-q|--jq|-t|--template|--hostname|--cache|-p|--preview) skip=value ;;
      -*|gh|*/gh|api) ;;
      *) [ -n "$endpoint" ] || endpoint=$w ;;
    esac
  done
  method=$(printf '%s' "$method" | tr '[:lower:]' '[:upper:]')
  if [ -n "$method" ]; then
    [ "$method" != GET ] && { printf '%s %s' "$method" "$endpoint"; return 0; }
    return 1
  fi
  if [ "$endpoint" = graphql ]; then
    case "$st" in
      *mutation*) printf 'a GraphQL mutation'; return 0 ;;
      *query=@*) printf 'a GraphQL query read from a file'; return 0 ;;
    esac
    return 1
  fi
  [ -n "$fields" ] && { printf 'POST %s: fields make gh send POST' "$endpoint"; return 0; }
  return 1
}

while IFS= read -r st; do
  case "$st" in
    *DELIVERY_GUARD=off*)
      decide ask "DELIVERY_GUARD=off is set: the delivery guard is being bypassed on purpose. Approve only if you asked for this raw command." ;;
  esac

  # Strip leading environment assignments and benign wrappers.
  st=$(printf '%s' "$st" | sed -E 's/^[[:space:]]+//
    :a
    s/^[A-Za-z_][A-Za-z0-9_]*=("[^"]*"|'\''[^'\'']*'\''|[^[:space:]]*)[[:space:]]+//
    ta
    s/^(sudo|env|command|nohup|time|exec)[[:space:]]+//')

  # shellcheck disable=SC2086
  set -- $st
  head=${1:-}; sub=${2:-}; verb=${3:-}

  # Reading a command's documentation is not running it. Matched as a whole word, and only
  # when the statement carries no quote character (a quoted `--help` looks the same).
  case "$st" in
    *[\"\']*) ;;
    *) for w in "$@"; do
         case "$w" in --help|-h) continue 2 ;; esac
       done ;;
  esac

  case "$head" in
    gh|*/gh) ;;
    *) continue ;;
  esac

  if [ "$sub" = api ]; then
    if why=$(api_write "$st" "$@"); then
      decide ask "This gh api call can change GitHub ($why). Approve it only if it should. A gh api call that only reads runs without asking (#54)."
    fi
    continue
  fi

  case "$sub $verb" in
    "issue create")
      decide deny "Refused: a raw \`gh issue create\` produces an issue that is on no board, with no Status and no Priority.

Use the flow — invoke the \`repo-task-delivery\` skill, or run its GitHub step directly:
  $START --title \"...\" --body-file <path> --priority <Urgent|High|Medium|Low> --status \"In progress\"

That one call creates the issue (or reuses one with the same title), puts it on the board,
sets Status, Priority and Showcase, and creates the issue branch with \`gh issue develop\`.

If the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off." ;;

    "pr create")
      named=$(head_branch "$@")
      source_branch=${named:-$branch}
      if [ -n "$named" ]; then
        where="--head names '$named'"
      else
        where="HEAD is '${branch:-unknown}'"
      fi
      case "$source_branch" in
        [0-9]*-*) ;;  # issue-linked branch from `gh issue develop` — allowed
        *)
          decide deny "Refused: $where, which carries no issue number, so this pull request would have no issue and no board entry behind it.

Get the issue and its branch first:
  $START --title \"...\" --priority High --status \"In progress\"
or, when the issue already exists:
  gh issue develop <n> -R $REPO --checkout --base $BASE

From a <number>-<slug> branch, commit, push, open the PR and set Status in one step:
  $FINISH --add <path> --pr-body-file <path>

If the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off." ;;
      esac ;;
  esac
done <<EOF
$statements
EOF

exit 0
