#!/usr/bin/env bash
# End-to-end run of the flow scripts against GitHub itself (#40). The selftest fakes GitHub; this run shows
# what only GitHub can: that the board queries, the mutations and the gh commands still work.
#
# It acts on a sandbox repository and its own board, owned like the real board (kit-defaults.edn :board :owner),
# never on anything else, and checks every
# step against GitHub:
#   1. kit start files an issue: Status, Priority, Size and Showcase on the board, the branch on GitHub
#   2. a draft card with a title is converted to an issue by a second start
#   3. a commit; kit finish opens the pull request as a draft and the card is In review. When CI has passed, the
#      sandbox's ready workflow (.github/workflows/ready-when-green.yml, ADR-0007) marks it ready: the owners are asked
#      only then, and auto-merge is on. Each wait ends on its event (a run completing), never on a timer
#   4. the merge waits: without the owner's Approve the PR stays open (ADR-0003; the run never approves)
#   5. cleanup, also after a failure: the PR closed, the branches deleted, the issues closed as not planned and
#      their cards deleted, so the next run starts clean
# A failing step names itself and GitHub's answer.
#
#   test/e2e.sh        as the gh login in use: the agent's in a Claude session; in CI, the E2E_GH_TOKEN secret
#   test/e2e.sh --clean   removes the leftovers of runs that have ended, decided by state, not by age (#98, #109):
#                      each run writes who runs it into its first issue ("e2e-runner: ..."): in CI the run's URL,
#                      locally the host and process id. A CI run that is completed, or a process that is gone from
#                      this host, has ended. A live run, or one on another host, is left alone and named.
# Environment: E2E_REPO (default shundeev/flow-sandbox), E2E_BOARD (the board's title, default "Flow sandbox").
set -uo pipefail

KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${E2E_REPO:-shundeev/flow-sandbox}"
BOARD="${E2E_BOARD:-Flow sandbox}"
# The sandbox board is owned where the real board is, a user's board with an organization's issues: the #22
# failure needs exactly that, and an organization's board would not show it.
read -r BOWNER REAL_NUM < <(bb -e '(let [b (:board (clojure.edn/read-string (slurp (str (first *command-line-args*)))))]
                                   (println (:owner b) (:number b)))' "$KIT/kit-defaults.edn")
RUN="e2e $(date -u +%Y-%m-%dT%H%M%SZ)-$$"
WORK="$(mktemp -d)"
STEP="setup"
issues=() branches=() pr=""

say() { echo "$*"; }
fail() { echo "FAIL  step $STEP: $*" >&2; exit 1; }
CLEAN=0
case "${1:-}" in
  "") ;;
  --clean) CLEAN=1 ;;
  *) fail "unknown argument $1: run test/e2e.sh, or test/e2e.sh --clean to remove old leftovers" ;;
esac

# Who runs this: what --clean needs to tell a run that has ended from one in progress.
if [ -n "${GITHUB_RUN_ID:-}" ]; then WHO="ci $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
else WHO="local $(hostname) $$"; fi
runner_state() { # runner_state <who>: "ended|live|left <why>"; only an ended run is cleaned
  local kind a b st repo; read -r kind a b <<<"$1"
  case "$kind" in
    ci) repo="${a#*://*/}"; repo="${repo%/actions/*}"
        st="$(gh run view "${a##*/}" -R "$repo" --json status --jq .status 2>&1)" \
          || { echo "left CI run $a could not be read: $st"; return; }
        if [ "$st" = completed ]; then echo "ended CI run $a is completed"; else echo "live CI run $a is $st"; fi ;;
    local) if [ "$a" != "$(hostname)" ]; then echo "left process $b runs on host $a, not on this host"
           elif kill -0 "$b" 2>/dev/null; then echo "live process $b is running on this host"
           else echo "ended process $b is gone from this host"; fi ;;
    *) echo "left no runner is written for it: it may have just started; if no run is going, remove it by hand" ;;
  esac
}

# Only the sandbox, never another repository or the real board (#40, invariant 2). The real board is
# recognised by its number, so that number must be known before anything else.
[[ -n "${BOWNER:-}" && "${REAL_NUM:-}" =~ ^[0-9]+$ ]] \
  || fail "the real board (:board :owner and :number) could not be read from $KIT/kit-defaults.edn with bb: install bb, or fix the file"
[[ "$REPO" == */flow-sandbox ]] || fail "E2E_REPO is $REPO: this run acts only on a */flow-sandbox repository"
[[ "$BOARD" != "Portfolio" ]] || fail "E2E_BOARD is the Portfolio board: this run acts only on the sandbox's own board"

gh api "repos/$REPO" --jq .full_name >/dev/null 2>&1 \
  || fail "$REPO cannot be read as $(gh api user --jq .login 2>/dev/null || echo 'nobody'): the owner creates it and runs kit github on it (steps on dev-kit#40's PR)"
NUM="$(gh project list --owner "$BOWNER" --format json --jq ".projects[] | select(.title == \"$BOARD\") | .number" 2>&1 | head -1)"
[[ "$NUM" =~ ^[0-9]+$ ]] \
  || fail "no board \"$BOARD\" of $BOWNER visible ($NUM): the owner copies the Portfolio board for the sandbox (steps on dev-kit#40's PR)"
[ "$NUM" != "$REAL_NUM" ] || fail "board #$NUM of $BOWNER is the real board of kit-defaults.edn: this run never touches it"
SHOWCASE="$(gh project field-list "$NUM" --owner "$BOWNER" --format json --jq '.fields[] | select(.name == "Showcase") | .options[0].name // empty')" \
  || fail "the fields of board #$NUM could not be read (gh's answer above)"
[ -n "$SHOWCASE" ] || fail "the board \"$BOARD\" has no Showcase options: copy the Portfolio board, which has them"

export GHWF_OWNER="$BOWNER" GHWF_PROJECT_NUMBER="$NUM" GHWF_REPO="$REPO"

# One board item per issue: Status|Priority|Size|Showcase, or the draft's title for a draft.
# Every page of the board: a card just added is not among the last 100 of a long board.
all_items() {
  gh api graphql --paginate -f query='query($o:String!,$n:Int!,$endCursor:String){repositoryOwner(login:$o){... on ProjectV2Owner{projectV2(number:$n){
      items(first:100,after:$endCursor){pageInfo{hasNextPage endCursor} nodes{
      id isArchived content{__typename ... on Issue{number title} ... on DraftIssue{title}}
      s:fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}}
      p:fieldValueByName(name:"Priority"){... on ProjectV2ItemFieldSingleSelectValue{name}}
      z:fieldValueByName(name:"Size"){... on ProjectV2ItemFieldSingleSelectValue{name}}
      w:fieldValueByName(name:"Showcase"){... on ProjectV2ItemFieldSingleSelectValue{name}}}}}}}}' \
    -f o="$BOWNER" -F n="$NUM" --jq '.data.repositoryOwner.projectV2.items.nodes[]'
} # gh's error goes to stderr, into the log: the step that needed the board names GitHub's answer there
item() { all_items | jq -c 'select(.isArchived | not)'; }
branch_in() { sed -n 's/^\(Checked Out \)\{0,1\}Branch: //p' <<<"$1"; } # the start script's "Branch:" line
fields_of() { # fields_of <issue number>
  item | jq -r --argjson n "$1" 'select(.content.number == $n) | "\(.s.name)|\(.p.name)|\(.z.name)|\(.w.name)"' 2>/dev/null
}

cleanup() {
  local rc=$? problems=""
  STEP="5 cleanup"
  # What this run made is found by its label too, not only by what it noted: a step that dies halfway, after
  # GitHub made an issue, a branch or a PR, leaves nothing behind either.
  local slug prs heads open cards
  slug="$(tr '[:upper:] ' '[:lower:]-' <<<"$RUN" | tr -d ':')"
  # A list that cannot be read is a problem too: what it would have found stays behind.
  prs="$(gh pr list --repo "$REPO" --state open --json url,headRefName \
           --jq ".[] | select(.headRefName | contains(\"$slug\")) | .url")" || problems+=" (the open PRs could not be listed)"
  for p in $pr $prs; do
    gh pr close "$p" --repo "$REPO" --delete-branch >/dev/null 2>&1 || problems+=" PR $p;"
  done
  heads="$(gh api --paginate "repos/$REPO/branches?per_page=100" --jq ".[].name | select(contains(\"$slug\"))")" \
    || problems+=" (the branches could not be listed)"
  for b in "${branches[@]}" $heads; do
    gh api "repos/$REPO/git/refs/heads/$b" >/dev/null 2>&1 \
      && { gh api -X DELETE "repos/$REPO/git/refs/heads/$b" >/dev/null 2>&1 || problems+=" branch $b;"; }
  done
  open="$(gh issue list --repo "$REPO" --state open --limit 200 --json number,title \
            --jq ".[] | select(.title | startswith(\"$RUN\")) | .number")" || problems+=" (the open issues could not be listed)"
  for n in "${issues[@]}" $open; do
    [ "$(gh issue view "$n" --repo "$REPO" --json state --jq .state 2>/dev/null)" = OPEN ] || continue
    gh issue close "$n" --repo "$REPO" --reason "not planned" >/dev/null 2>&1 || problems+=" issue #$n;"
  done
  # Every card of this run, an issue's or a draft that was never converted.
  # Every card of this run, an issue's or a draft never converted, and cards of earlier runs left archived: deleted,
  # so the board stays short.
  cards="$(all_items | jq -r --arg r "$RUN" 'select((.content.title // "") as $t | ($t | startswith($r)) or (.isArchived and ($t | startswith("e2e ")))) | .id')" \
    || problems+=" (the board's cards could not be listed)"
  for id in $cards; do
    gh project item-delete "$NUM" --owner "$BOWNER" --id "$id" >/dev/null 2>&1 || problems+=" card $id;"
  done
  rm -rf "$WORK"
  if [ -n "$problems" ]; then
    echo "FAIL  step 5 cleanup: left in $REPO:$problems remove them by hand, then run again" >&2
    exit 1
  fi
  say "ok    5 cleanup: the PR closed, branches deleted, issues closed as not planned, cards deleted"
  exit "$rc"
}
# The labels ("e2e <time>-<pid>") of the open issues, each with the runner its issue names (tab-separated, empty if none).
# Only an open issue holds a leftover: cleanup closes the issues of a run that got that far, and nothing else is left.
found_labels() {
  local l='e2e [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{6}Z-[0-9]+'
  gh issue list --repo "$REPO" --state open --limit 200 --json title,body \
    --jq ".[] | (.title | capture(\"^(?<l>$l)\")? | .l) as \$l | select(\$l) | \"\(\$l)\\t\" + ((.body | capture(\"e2e-runner: (?<w>[^\\n]*)\")? | .w) // \"\")" \
    | sort -t$'\t' -k1,1 -k2,2r | awk -F'\t' '!seen[$1]++' \
    || fail "the issues of $REPO could not be listed (gh's answer above): check gh auth status"
}

if [ "$CLEAN" = 1 ]; then # each run that has ended is cleaned as the run itself cleans up after a failure
  STEP="clean"; rc=0; left=0
  labels="$(found_labels)" || exit 1
  while IFS=$'\t' read -r l who; do
    [ -n "$l" ] || continue
    st="$(runner_state "$who")"
    if [ "${st%% *}" = ended ]; then say "clean: $l (${st#* })"; (RUN="$l"; cleanup) || rc=1
    else say "left alone: $l (${st#* })"; left=$((left + 1)); fi
  done <<<"$labels"
  [ "$rc" = 0 ] && say "ok    clean: $REPO has no leftovers of ended runs ($left left alone)"
  rm -rf "$WORK"; exit "$rc"
fi
trap cleanup EXIT

say "e2e: $REPO, board \"$BOARD\" (#$NUM), as $(gh api user --jq .login), run \"$RUN\""

STEP="setup"
gh repo clone "$REPO" "$WORK/repo" -- -q 2>&1 || fail "cloning $REPO failed"
cd "$WORK/repo" || fail "no clone"
git config user.name "dev-kit e2e"
git config user.email "e2e@users.noreply.github.com"
git config credential.helper '!gh auth git-credential'

STEP="1 start files an issue"
printf 'e2e-runner: %s\n' "$WHO" > "$WORK/runner.md"
out="$(bb "$KIT/bin/kit" start --title "$RUN: task" --body-file "$WORK/runner.md" --priority Low --size XS --showcase "$SHOWCASE" \
         --status "In progress" 2>&1)" || fail "$out"
n1="$(sed -n 's/^Issue Number: //p' <<<"$out")"; b1="$(branch_in "$out")"
[ -n "$n1" ] && issues+=("$n1"); [ -n "$b1" ] && branches+=("$b1") # cleaned up whatever follows
[ -n "$n1" ] && [ -n "$b1" ] || fail "no issue number or branch in: $out"
got="$(fields_of "$n1")"
[ "$got" = "In progress|Low|XS|$SHOWCASE" ] || fail "the card of #$n1 reads \"$got\", not \"In progress|Low|XS|$SHOWCASE\""
gh api "repos/$REPO/branches/$b1" --jq .name >/dev/null 2>&1 || fail "branch $b1 is not on GitHub"
[ "$(git branch --show-current)" = "$b1" ] || fail "the clone is on $(git branch --show-current), not $b1"
say "ok    1 start: #$n1 on the board as In progress|Low|XS|$SHOWCASE; branch $b1 on GitHub and checked out"

STEP="2 a draft card is converted"
card="$(gh project item-create "$NUM" --owner "$BOWNER" --title "$RUN: draft" --format json --jq .id 2>&1)" \
  || fail "the draft card could not be created: $card"
card_kind() { # the card's content type and title, read by its id: a read by id does not lag behind a write
  gh api graphql -f query='query($id:ID!){node(id:$id){... on ProjectV2Item{content{__typename ... on DraftIssue{title}}}}}' -f id="$card" \
    --jq '.data.node.content | "\(.__typename) \(.title // "")"' 2>&1
}
[ "$(card_kind)" = "DraftIssue $RUN: draft" ] || fail "the new card $card reads \"$(card_kind)\", not a draft titled \"$RUN: draft\""
# kit start finds the draft in the board's item list, which shows a new card a moment after the write. No event says
# when the list has caught up, so this looks every 2 s; it ends when the list shows the card, and fails when the card
# is gone by id (the list could then never show it).
until [ -n "$(item | jq -r --arg t "$RUN: draft" 'select(.content.title == $t) | .id' 2>/dev/null)" ]; do
  [ "$(card_kind)" = "DraftIssue $RUN: draft" ] || fail "the new card $card is gone before the board's list showed it: $(card_kind)"
  sleep 2
done
out="$(bb "$KIT/bin/kit" start --title "$RUN: draft" --priority Low --size XS --showcase "$SHOWCASE" \
         --no-checkout 2>&1)" || fail "$out"
n2="$(sed -n 's/^Issue Number: //p' <<<"$out")"; b2="$(branch_in "$out")"
[ -n "$n2" ] && issues+=("$n2"); [ -n "$b2" ] && branches+=("$b2")
[ -n "$n2" ] || fail "no issue number in: $out"
[[ "$(card_kind)" == Issue* ]] || fail "the draft card $card reads \"$(card_kind)\" after the start, not an issue"
[ -n "$(fields_of "$n2")" ] || fail "#$n2 has no card on the board"
say "ok    2 draft: the card became issue #$n2, the draft is gone"

STEP="3 finish opens the pull request"
# The sandbox must hold what this run tests: dev-kit's managed ready workflow, and a CI workflow for it to follow.
# Without them no run would come, and the waits below would wait for ever.
for w in ci.yml ready-when-green.yml; do
  gh api "repos/$REPO/contents/.github/workflows/$w" --jq .name >/dev/null 2>&1 \
    || fail "$REPO has no .github/workflows/$w on its main: the owner merges the sandbox PR that adds it (steps on dev-kit#104's PR)"
done
gh api "repos/$REPO/contents/.github/workflows/ready-when-green.yml" --jq .content | base64 -d | cmp -s - "$KIT/.github/workflows/ready-when-green.yml" \
  || fail "the sandbox's ready-when-green.yml is not dev-kit's .github/workflows/ready-when-green.yml: the owner merges the sandbox PR that replaces it (steps on dev-kit#104's PR)"
# The waits below end on events; an inactive workflow would never produce one, so it fails here, with the reason.
for w in ci.yml ready-when-green.yml; do
  st="$(gh api "repos/$REPO/actions/workflows/$w" --jq .state 2>&1)" || fail "the state of $w in $REPO could not be read: $st"
  [ "$st" = active ] || fail "$w in $REPO is $st, so no run will come: the owner enables it (Actions > $w > Enable workflow; steps on dev-kit#104's PR)"
done
echo "$RUN" > e2e.txt
cat > "$WORK/body.md" <<EOF
## What changed
- e2e.txt, written by dev-kit's end-to-end run.

## Why
dev-kit#40: the flow scripts, run against GitHub.

## Root causes
none beyond the issue

## Review
none: a sandbox run.

## User-level test
The run itself.

## Verified
- the run's own checks

## Not verified
- the merge, which waits for the owner

## Owner steps
none

ADR: none
EOF
out="$(bb "$KIT/bin/kit" finish --type test --summary "end-to-end run $RUN" --add e2e.txt \
         --pr-body-file "$WORK/body.md" 2>&1)" || fail "$out"
pr="$(sed -n 's/^PR URL: //p' <<<"$out")"
[ -n "$pr" ] || fail "no PR URL in: $out"
[ "$(gh pr view "$pr" --json state --jq .state 2>&1)" = OPEN ] || fail "the PR $pr is not open"
got="$(fields_of "$n1")"
[ "${got%%|*}" = "In review" ] || fail "the card of #$n1 reads \"$got\", not In review"
say "ok    3 finish: $pr open, the card In review"

# ADR-0007: the PR was opened as a draft, and the ready workflow marks it ready when CI has passed. Every wait below
# ends when what it waits for happens, or when that has become impossible, and says which.
prn="${pr##*/}"; sha="$(git rev-parse HEAD)"
pr_open() { [ "$(gh pr view "$pr" --json state --jq .state 2>/dev/null)" = OPEN ]; }
STEP="3 CI runs on the PR's head"
ci=""
while [ -z "$ci" ]; do # the run appears a moment after the push; the latest one for this head is the one
  ci="$(gh run list --repo "$REPO" --workflow ci.yml --commit "$sha" --json databaseId,createdAt \
          --jq 'sort_by(.createdAt) | last | .databaseId // empty' 2>/dev/null)"
  [ -n "$ci" ] && break
  pr_open || fail "$pr is no longer open and CI never started on $sha: nothing is left to wait for; look at $pr"
  sleep 2
done
gh run watch "$ci" --repo "$REPO" --exit-status >/dev/null 2>&1 \
  || fail "CI failed on $sha, so the PR stays a draft by design, but this run needs it green: gh run view $ci --repo $REPO --log-failed"
STEP="3 the ready workflow decides for that CI run"
# The script's own text is in the log too (the step echoes its source), so only lines that were logged count: they
# have a timestamp before "decision:".
# A workflow_run run belongs to main's commit, not to the PR's, so the run that decided for this head is the one whose
# log names the CI run that triggered it ("CI run id: <id>,"). Each candidate is watched until it ends (its log
# exists only then). This ends when that run completed, or when it has become impossible, and says which: the PR
# closed. When GitHub never delivers the workflow_run event no event exists to wait for, so the e2e job's own limit
# (.github/workflows/e2e.yml) is the only end there; the CI workflow being active was checked before the start.
ci_started="$(gh run view "$ci" --repo "$REPO" --json createdAt --jq .createdAt)"
ours=""
while [ -z "$ours" ]; do
  for r in $(gh run list --repo "$REPO" --workflow ready-when-green.yml --event workflow_run --json databaseId,createdAt \
               --jq ".[] | select(.createdAt >= \"$ci_started\") | .databaseId" 2>/dev/null); do
    gh run watch "$r" --repo "$REPO" >/dev/null 2>&1 # returns when that run has ended
    if gh run view "$r" --repo "$REPO" --log 2>/dev/null | grep -qE "CI run id: $ci,"; then ours="$r"; break; fi
  done
  [ -n "$ours" ] && break
  pr_open || fail "$pr is no longer open and the ready workflow never ran for CI run $ci: nothing is left to wait for; look at $pr"
  sleep 2
done
log="$(gh run view "$ours" --repo "$REPO" --log 2>&1 | grep -E 'Z decision: ' | sed 's/^.*Z decision: /decision: /' | cut -c1-300)"
[ "$(gh run view "$ours" --repo "$REPO" --json conclusion --jq .conclusion)" = success ] \
  || fail "the ready workflow's run $ours failed: gh run view $ours --repo $REPO --log"
grep -q 'auto-merge could not be turned on' <<<"$log" \
  && fail "auto-merge cannot be turned on in $REPO, so the PR can never reach it (run $ours): the owner turns on Settings > General > 'Allow auto-merge' there. $log"
# The run has ended, so the PR is read once, after both of its steps (ready, then auto-merge).
STEP="3 the PR is ready, with auto-merge on and the owners asked"
state="$(gh pr view "$pr" --json isDraft,autoMergeRequest,reviewRequests 2>&1)" || fail "$state"
[ "$(jq -r .isDraft <<<"$state")" = false ] || fail "run $ours ended and $pr is still a draft; its decisions: $log"
[ "$(jq -r '.autoMergeRequest != null' <<<"$state")" = true ] || fail "$pr is ready but auto-merge is not on; run $ours: $log"
jq -e 'any(.reviewRequests[]?; (.slug // .login // .name) | endswith("owners"))' <<<"$state" >/dev/null \
  || fail "$pr is ready but the owners team is not asked for a review ($(jq -c .reviewRequests <<<"$state")); run $ours: $log"
# Invariant 1: nobody was asked before it became ready. The events come in the order they happened.
events="$(gh api --paginate "repos/$REPO/issues/$prn/events" --jq '.[].event' 2>&1)" || fail "the events of $pr could not be read: $events"
first_ready="$(grep -n '^ready_for_review$' <<<"$events" | head -1 | cut -d: -f1)"
first_ask="$(grep -n '^review_requested$' <<<"$events" | head -1 | cut -d: -f1)"
[ -n "$first_ready" ] || fail "$pr has no ready_for_review event: it was not a draft when it opened. Its events: $(tr '\n' ' ' <<<"$events")"
[ -z "$first_ask" ] || [ "$first_ask" -gt "$first_ready" ] || fail "the owners were asked before $pr was ready. Its events: $(tr '\n' ' ' <<<"$events")"
say "ok    3 draft: $pr opened as a draft, marked ready by run $ours after CI passed, auto-merge on, the owners asked after that"

STEP="4 the merge waits for the owner"
out="$(bb "$KIT/bin/kit" merge 2>&1)" && fail "kit merge succeeded without the owner's Approve: $out"
grep -q "Waiting for the owner's Approve" <<<"$out" || fail "the merge stopped for another reason: $out"
[ "$(gh pr view "$pr" --json state --jq .state 2>&1)" = OPEN ] || fail "the PR $pr is no longer open"
say "ok    4 merge: refused without the owner's Approve, $pr still open"
