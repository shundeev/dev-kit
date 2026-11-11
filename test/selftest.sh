#!/usr/bin/env bash
# End-to-end check of dev-kit on a throwaway project: init, doctor, repairs, modules,
# the git and Claude Code hooks, and the offline guards of the flow scripts.
#
#   test/selftest.sh            uses the working tree of this dev-kit checkout
#
# Needs: git, bb, jq; zprint for the pre-commit case (skipped when absent).
set -uo pipefail

KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
ok()   { pass=$((pass + 1)); echo "  ok    $1"; }
bad()  { fail=$((fail + 1)); echo "  FAIL  $1"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/        /'; }
expect_exit() { # expect_exit <code> <name> <cmd...>
  local want="$1" name="$2"; shift 2
  local out code
  out="$("$@" 2>&1)"; code=$?
  if [ "$code" -eq "$want" ]; then ok "$name"; else bad "$name (exit $code, want $want)" "$out"; fi
  LAST="$out"
}
contains() { # contains <name> <needle> <haystack>
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else bad "$1" "missing: $2"; fi
}

export GIT_AUTHOR_NAME=selftest GIT_AUTHOR_EMAIL=selftest@example.invalid
export GIT_COMMITTER_NAME=selftest GIT_COMMITTER_EMAIL=selftest@example.invalid

echo "dev-kit selftest ($KIT)"

# A project with dev-kit mounted at .shared. The submodule is cloned from this checkout's
# HEAD, then the working tree is laid over it so uncommitted changes are what gets tested.
P="$WORK/project"
git init -q -b main "$P"
cd "$P" || exit 1
git remote add origin https://github.com/u473t8/kit-selftest.git
git -c protocol.file.allow=always submodule add -q "$KIT" .shared
(cd "$KIT" && tar --exclude=.git -cf - .) | (cd .shared && tar -xf -)
git commit -qm "chore: add dev-kit"
kit=".shared/bin/kit"

echo "- empty project"
expect_exit 1 "doctor fails without kit.edn" bb $kit doctor
contains "doctor names the missing kit.edn" "kit.edn is missing" "$LAST"

echo "- init"
expect_exit 0 "init succeeds" bb $kit init --showcase Site
contains "init reports what it wrote" "wrote .claude/settings.json" "$LAST"
grep -q ':repo     "u473t8/kit-selftest"' kit.edn && ok "kit.edn infers repo from origin" || bad "kit.edn repo" "$(cat kit.edn)"
[ "$(jq -r .env.GHWF_REPO .claude/settings.json)" = "u473t8/kit-selftest" ] && ok "settings env carries the repo" || bad "settings env"
[ "$(jq -r .env.GHWF_PROJECT_NUMBER .claude/settings.json)" = "3" ] && ok "settings env carries the board from defaults" || bad "board number"
[ -f .claude/skills/repo-task-delivery/SKILL.md ] && ok "skill readable through its link" || bad "skill link"
grep -q "Managed by dev-kit" .claude/agents/ops.md && ok "agent copied with the managed marker" || bad "agent copy"
head -1 .claude/agents/ops.md | grep -qx -- '---' && ok "agent copy keeps frontmatter first" || bad "agent frontmatter"
[ -f LICENSE ] && grep -q "MIT License" LICENSE && ok "LICENSE scaffolded" || bad "LICENSE"
[ -f .github/workflows/ci.yml ] && ok "CI workflow written" || bad "ci.yml"
[ "$(git config core.hooksPath)" = ".shared/githooks" ] && ok "hooksPath set" || bad "hooksPath"
grep -q "dev-kit:begin" AGENTS.md && grep -q "# This project" AGENTS.md && ok "AGENTS.md has the block and the project part" || bad "AGENTS.md"
[ ! -e CLAUDE.md ] && ok "no CLAUDE.md" || bad "CLAUDE.md exists"
[ ! -e README.md ] && ok "README not scaffolded without --scaffold" || bad "README scaffolded"

echo "- permissions"
perm() { jq -r --arg list "$1" '.permissions[$list][]' .claude/settings.json; }
has() { perm "$1" | grep -qxF -- "$2"; }
for rule in "Bash(git *)" "Bash(gh *)" "Bash(bb *)"; do
  has allow "$rule" && bad "no blanket allow: $rule" || ok "no blanket allow: $rule"
done
for rule in "Bash(gh pr merge *)" "Bash(bash .claude/skills/gh-project-workflow/scripts/merge_issue_flow.sh *)" \
            "Bash(gh api *)" "Bash(git reset --hard *)" "Bash(git clean *)" "Bash(git branch -D *)"; do
  has ask "$rule" && ok "asks: $rule" || bad "asks: $rule"
done
for rule in "Bash(git push --force *)" "Bash(git push * --force *)" "Bash(git push * --force)" "Bash(git push -f *)" \
            "Bash(git push * -f)" "Bash(git push * +*)" "Bash(gh repo delete *)"; do
  has deny "$rule" && ok "denies: $rule" || bad "denies: $rule"
done
has allow "Bash(.shared/bin/kit *)" && ok "kit path substituted" || bad "kit path substituted"
jq -e '.hooks.PreToolUse and .hooks.SessionStart' .claude/settings.json >/dev/null && ok "hooks survive the settings merge" || bad "hooks lost in the settings merge"
perm allow | grep -q '{{kit}}' && bad "unsubstituted {{kit}}" || ok "no {{kit}} placeholder left"
has allow "Bash(clj-kondo *)" && bad "clojure allows without the module" || ok "no clojure allows without the module"
own="$KIT/.claude/settings.json"; base="$KIT/claude/settings.base.json"
for list in ask deny; do
  [ "$(jq -c ".permissions.$list" "$own")" = "$(jq -c ".permissions.$list" "$base")" ] \
    && ok "dev-kit's own $list rules match the base" || bad "dev-kit's own $list rules drifted from the base"
done

echo "- idempotence"
expect_exit 0 "second init succeeds" bb $kit init
contains "second init changes nothing" "nothing to change" "$LAST"
expect_exit 0 "doctor passes" bb $kit doctor
touch .shared/stray-file
expect_exit 0 "local edits in dev-kit only warn" bb $kit doctor
contains "doctor warns about local edits in .shared" "WARN  .shared has local changes" "$LAST"
rm .shared/stray-file

echo "- repairs"
echo '{}' > .claude/settings.json
expect_exit 1 "doctor catches an edited settings.json" bb $kit doctor
contains "doctor names the file" ".claude/settings.json: differs" "$LAST"
printf '\nProject note that must survive.\n' >> AGENTS.md
sed -i 's/^# Working rules$/# Working rules (edited by hand)/' AGENTS.md
expect_exit 1 "doctor catches an edited dev-kit block" bb $kit doctor
rm .claude/skills/adr
expect_exit 0 "init repairs everything" bb $kit init
grep -q "Project note that must survive." AGENTS.md && ok "project text outside the block survives" || bad "project text lost"
grep -q "^# Working rules$" AGENTS.md && ok "block restored" || bad "block not restored"
[ -L .claude/skills/adr ] && ok "skill link restored" || bad "skill link"
git config --unset core.hooksPath
expect_exit 1 "doctor catches a missing hooksPath" bb $kit doctor
expect_exit 0 "doctor --ci skips local git config" bb $kit doctor --ci
bb $kit init >/dev/null

echo "- clojure module"
sed -i 's/:modules  #{}/:modules  #{:clojure}/' kit.edn
expect_exit 0 "modules reports clojure" bb $kit modules
contains "modules output" "clojure=true" "$LAST"
expect_exit 1 "doctor wants the clojure setup" bb $kit doctor --ci
mkdir -p .clj-kondo
printf '{:lint-as {foo/defthing clojure.core/def}\n :linters {:unused-binding {:level :off}}}\n' > .clj-kondo/config.edn
expect_exit 0 "init adds the clojure setup" bb $kit init
[ -L .zprint.edn ] && [ -f .zprint.edn ] && ok ".zprint.edn linked" || bad ".zprint.edn"
bb -e '(let [m (clojure.edn/read-string (slurp ".clj-kondo/config.edn"))] (assert (= ["../.shared/clojure/clj-kondo"] (:config-paths m))) (assert (:lint-as m)))' \
  && ok "clj-kondo config gains config-paths and keeps its own keys" || bad "clj-kondo config" "$(cat .clj-kondo/config.edn)"
[ -f .claude/agents/smell-reviewer.md ] && ok "smell-reviewer added" || bad "smell-reviewer"
jq -e '.permissions.allow | index("Bash(clj-kondo *)")' .claude/settings.json >/dev/null && ok "clojure module allows clj-kondo" || bad "clojure allows"
grep -q "## Clojure design" AGENTS.md && ok "Clojure rules in the block" || bad "clojure rules"

if command -v zprint >/dev/null 2>&1; then
  echo "- pre-commit"
  mkdir -p src
  printf '(ns demo)\n(defn f [x]     (inc x))\n' > src/demo.clj
  git add -A
  expect_exit 1 "pre-commit refuses unformatted Clojure" git commit -qm "feat: demo"
  .shared/clojure/zprint_check.sh --write >/dev/null 2>&1
  git add -A
  expect_exit 0 "pre-commit accepts formatted Clojure" git commit -qm "feat: demo"
else
  echo "- pre-commit: skipped (zprint not installed)"
fi

sed -i 's/:modules  #{:clojure}/:modules  #{}/' kit.edn
expect_exit 0 "init with the module switched off" bb $kit init
[ ! -e .zprint.edn ] && [ ! -e .claude/agents/smell-reviewer.md ] && ok "module files removed as stale" || bad "stale files kept"
contains "init reports removals" "removed, no longer managed" "$LAST"

echo "- SessionStart hook"
out="$(bash .shared/claude/hooks/session-start.sh)"
[ -z "$(grep -v 'local changes' <<<"$out" | grep FAIL)" ] && ok "silent about FAIL when set up" || bad "session-start noisy" "$out"
rm .claude/agents/ops.md
out="$(bash .shared/claude/hooks/session-start.sh)"
contains "reports a broken setup to the agent" ".claude/agents/ops.md: missing" "$out"
bb $kit init >/dev/null

echo "- delivery guard"
guard() { jq -nc --arg c "$1" '{tool_input:{command:$c}}' | bash .shared/claude/hooks/delivery-guard.sh; }
contains "denies raw gh issue create" '"permissionDecision":"deny"' "$(guard 'gh issue create --title x')"
contains "denies gh pr create from main" '"permissionDecision":"deny"' "$(guard 'gh pr create --fill')"
[ -z "$(guard 'gh pr create --head 12-landing --fill')" ] && ok "allows gh pr create from an issue branch" || bad "issue branch denied"
[ -z "$(guard 'gh issue create --help')" ] && ok "allows --help" || bad "--help denied"
contains "asks on DELIVERY_GUARD=off" '"permissionDecision":"ask"' "$(guard 'DELIVERY_GUARD=off gh issue create -t x')"
[ -z "$(guard 'git status')" ] && ok "ignores unrelated commands" || bad "unrelated command judged"

echo "- fail closed"
jq -e '.hooks.PreToolUse[0].hooks[0].onFailure == "block"' .claude/settings.json >/dev/null && ok "PreToolUse hook fails closed" || bad "PreToolUse onFailure"
[ "$(jq -r .permissions.disableBypassPermissionsMode .claude/settings.json)" = disable ] && ok "bypass mode disabled" || bad "bypass mode"
[ -f .claude/hooks/pretooluse.sh ] && ! grep -q '{{kit}}' .claude/hooks/pretooluse.sh && ok "entry hook in the project, kit path filled in" || bad "entry hook"
entry() { jq -nc --arg c "$1" '{tool_input:{command:$c}}' | CLAUDE_PROJECT_DIR="${2:-$P}" bash .claude/hooks/pretooluse.sh; }
contains "entry hook hands calls to the guard" '"permissionDecision":"deny"' "$(entry 'gh issue create --title x')"
mkdir -p "$WORK/nokit"
for c in "git submodule update --init" ".shared/bin/kit init" "git status"; do
  expect_exit 0 "empty kit allows repair: $c" entry "$c" "$WORK/nokit"
done
expect_exit 2 "empty kit refuses other commands" entry "ls -la" "$WORK/nokit"
contains "refusal names the repair" "git submodule update --init" "$LAST"
nojq="$WORK/nojq"; mkdir -p "$nojq"
for t in bash git awk sed cat head tr; do ln -sf "$(command -v $t)" "$nojq/$t"; done
out="$(jq -nc '{tool_input:{command:"gh issue create -t x"}}' | PATH="$nojq" bash .shared/claude/hooks/delivery-guard.sh 2>&1)"; code=$?
[ "$code" -eq 2 ] && ok "guard without jq refuses (exit 2)" || bad "guard without jq (exit $code)" "$out"
contains "says jq is missing" "needs jq" "$out"
fake="$WORK/fakeclaude"; mkdir -p "$fake"
printf '#!/bin/sh\necho "2.1.200 (Claude Code)"\n' > "$fake/claude"; chmod +x "$fake/claude"
expect_exit 1 "doctor fails on a Claude Code too old for onFailure" env PATH="$fake:$PATH" bb $kit doctor
contains "doctor names the version" "ignores onFailure" "$LAST"
printf '#!/bin/sh\necho "2.1.322 (Claude Code)"\n' > "$fake/claude"
out="$(PATH="$fake:$PATH" bb $kit doctor 2>&1)"
grep -q "ignores onFailure" <<<"$out" && bad "new enough Claude Code flagged" "$out" || ok "new enough Claude Code passes"

echo "- flow scripts, offline guards"
export GHWF_OWNER=u473t8 GHWF_PROJECT_NUMBER=3 GHWF_REPO=u473t8/kit-selftest
expect_exit 1 "start refuses an issue without priority" bash .claude/skills/gh-project-workflow/scripts/start_issue_flow.sh --title x
contains "start names the scale" "Urgent|High|Medium|Low" "$LAST"
expect_exit 1 "start refuses an off-scale priority" bash .claude/skills/gh-project-workflow/scripts/start_issue_flow.sh --title x --priority Critical
expect_exit 1 "finish refuses a non-conventional type" bash .claude/skills/gh-project-workflow/scripts/finish_issue_flow.sh --type update
expect_exit 1 "finish has no merge mode" bash .claude/skills/gh-project-workflow/scripts/finish_issue_flow.sh --merge
contains "finish rejects --merge" "Unknown argument: --merge" "$LAST"
expect_exit 1 "merge refuses to run on main" bash .claude/skills/gh-project-workflow/scripts/merge_issue_flow.sh
contains "merge explains" "Run from the issue branch" "$LAST"
expect_exit 0 "merge has help" bash .claude/skills/gh-project-workflow/scripts/merge_issue_flow.sh --help
expect_exit 1 "finish refuses to run on main" bash .claude/skills/gh-project-workflow/scripts/finish_issue_flow.sh
contains "finish explains" "On the base branch" "$LAST"

echo "- finish and merge scripts (fake gh)"
FAKE="$WORK/fake"; mkdir -p "$FAKE"
export FAKE_GH_DIR="$FAKE" FAKE_GH_LOG="$FAKE/log"
SCR="$KIT/claude/skills/gh-project-workflow/scripts"
REMOTE="$WORK/remote.git"; git init -q --bare -b main "$REMOTE"
T="$WORK/flow"; git init -q -b main "$T"; cd "$T" || exit 1
git remote add origin "$REMOTE"; echo base > f; git add f; git commit -qm "chore: base"; git push -q -u origin main
git switch -q -c 12-landing
flow() { local script="$1"; shift; PATH="$KIT/test/fake-gh:$PATH" GHWF_REPO=u473t8/fake GHWF_OWNER=u473t8 GHWF_PROJECT_NUMBER=3 bash "$SCR/$script" "$@"; }
head_now() { git rev-parse HEAD; }

: > "$FAKE/pr-list"; echo "Add Landing page" > "$FAKE/issue-title"
echo change >> f; git add f; before=$(head_now)
expect_exit 1 "finish: missing body file stops before the commit" flow finish_issue_flow.sh --pr-body-file "$WORK/none.md"
[ "$(head_now)" = "$before" ] && ok "  nothing committed" || bad "  committed anyway"
printf 'text\nADR: \n' > "$WORK/empty-adr.md"
expect_exit 1 "finish: empty ADR line stops before the commit" flow finish_issue_flow.sh --pr-body-file "$WORK/empty-adr.md"
[ "$(head_now)" = "$before" ] && ok "  nothing committed" || bad "  committed anyway"
expect_exit 1 "finish: no open PR and no body file stops before the commit" flow finish_issue_flow.sh
printf 'did it\n' > "$WORK/bad-msg.txt"
expect_exit 1 "finish: commit message without type(GH-n) refused" flow finish_issue_flow.sh --commit-message-file "$WORK/bad-msg.txt" --pr-body-file "$WORK/empty-adr.md"

printf 'What changed\n\nADR: none\n' > "$WORK/body.md"; : > "$FAKE_GH_LOG"
expect_exit 0 "finish: a closed PR does not count; a new one is opened" flow finish_issue_flow.sh --pr-body-file "$WORK/body.md"
grep -q '^pr create' "$FAKE_GH_LOG" && ok "  gh pr create called" || bad "  no pr create" "$(cat "$FAKE_GH_LOG")"
grep -q 'pr list .*--state open' "$FAKE_GH_LOG" && ok "  only open PRs looked up" || bad "  lookup not limited to open PRs"
contains "  subject from the issue title, first letter lowered" "Subject: feat(GH-12): add Landing page" "$LAST"
[ "$(git log -1 --format=%s)" = "feat(GH-12): add Landing page" ] && ok "  commit carries the subject" || bad "  commit subject"
[ "$(git rev-parse origin/12-landing 2>/dev/null)" = "$(head_now)" ] && ok "  branch pushed" || bad "  branch not pushed"

echo "https://github.com/u473t8/fake/pull/99" > "$FAKE/pr-list"; : > "$FAKE_GH_LOG"
echo more >> f; git add f
printf 'fix(GH-12): handle more\n\nbody\n' > "$WORK/msg.txt"
expect_exit 0 "finish: an open PR needs no body file" flow finish_issue_flow.sh --type fix --commit-message-file "$WORK/msg.txt"
grep -q '^pr create' "$FAKE_GH_LOG" && bad "  opened a second PR" || ok "  no second PR"
contains "  subject from the commit message file" "Subject: fix(GH-12): handle more" "$LAST"

echo OPEN > "$FAKE/pr-state"
expect_exit 1 "merge: a PR that did not merge keeps its branch" flow merge_issue_flow.sh
git rev-parse -q --verify refs/heads/12-landing >/dev/null && ok "  branch kept" || bad "  branch deleted"

W="$WORK/main-wt"; git worktree add -q "$W" main
echo MERGED > "$FAKE/pr-state"
expect_exit 0 "merge: works while main is checked out in another worktree" flow merge_issue_flow.sh
git rev-parse -q --verify refs/heads/12-landing >/dev/null && bad "  local branch left" || ok "  local branch deleted"
git ls-remote --exit-code --heads origin 12-landing >/dev/null 2>&1 && bad "  remote branch left" || ok "  remote branch deleted"
[ "$(git rev-parse --abbrev-ref HEAD)" = HEAD ] && ok "  this worktree detached on the base" || bad "  not detached"
cd "$P" || exit 1

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
