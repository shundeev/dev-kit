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
# doctor checks the GitHub identity only inside a Claude session; the cases that test it set
# CLAUDECODE themselves, so a selftest run from a session judges nothing about that session.
unset CLAUDECODE GH_TOKEN GITHUB_TOKEN
# The machine's global git config and ignore file stay out, as in CI: Claude Code puts
# settings.local.json into the global ignore file, which would hide what kit itself ignores.
: > "$WORK/gitconfig"
export GIT_CONFIG_GLOBAL="$WORK/gitconfig" XDG_CONFIG_HOME="$WORK/config"
# A home of its own: whether the agent is logged in on this machine must not change the results.
export HOME="$WORK/home"
mkdir -p "$HOME"

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

echo "- code owners"
[ "$(grep -v '^#' .github/CODEOWNERS)" = "* @u473t8" ] && ok "CODEOWNERS makes the owner review every file" || bad "CODEOWNERS" "$(cat .github/CODEOWNERS 2>&1)"
grep -qx '\* @u473t8' "$KIT/.github/CODEOWNERS" && ok "dev-kit itself has the same code owner" || bad "dev-kit's own CODEOWNERS"
echo '* @someone-else' > .github/CODEOWNERS
expect_exit 1 "doctor --ci catches an edited CODEOWNERS" bb $kit doctor --ci
contains "doctor names CODEOWNERS" ".github/CODEOWNERS: differs" "$LAST"
cp kit.edn "$WORK/kit.edn.bak"
owners() { bb -e "(spit \"kit.edn\" (pr-str (assoc (clojure.edn/read-string (slurp \"kit.edn\")) :owners $1)))"; }
owners '["kit-owner" "colleague"]'
bb $kit init >/dev/null
[ "$(grep -v '^#' .github/CODEOWNERS)" = "* @kit-owner @colleague" ] && ok "every code owner from kit.edn is listed; any one approval counts" || bad "owners override" "$(cat .github/CODEOWNERS)"
owners '[]'
expect_exit 2 "init refuses a project without code owners" bb $kit init
contains "and says what to put in kit.edn" ":owners in kit.edn is empty" "$LAST"
mv "$WORK/kit.edn.bak" kit.edn
bb $kit init >/dev/null

echo "- agent identity: per-machine settings"
default_login="$(bb -e '(println (get-in (clojure.edn/read-string (slurp ".shared/kit-defaults.edn")) [:agent :login]))')"
local=.claude/settings.local.json
lenv() { jq -r --arg k "$1" '.env[$k]' "${2:-$local}"; }
[ "$(lenv GH_CONFIG_DIR)" = "$HOME/.config/gh-agent" ] && ok "gh in sessions reads the agent's config" || bad "local GH_CONFIG_DIR" "$(cat $local 2>&1)"
[ "$(lenv GIT_CONFIG_KEY_0)=$(lenv GIT_CONFIG_VALUE_0)" = "url.https://github.com/.insteadOf=git@github.com:" ] \
  && ok "SSH remotes go over HTTPS in sessions" || bad "insteadOf"
jq -e '.env.GIT_CONFIG_COUNT == "3" and .env.GIT_CONFIG_VALUE_1 == ""' $local >/dev/null \
  && ok "inherited github.com credential helpers are reset" || bad "helper reset"
[ "$(lenv GIT_CONFIG_VALUE_2)" = '!gh auth git-credential' ] && ok "git push authenticates through gh" || bad "credential helper"
[ "$(lenv GIT_AUTHOR_NAME)" = "$default_login" ] && [ "$(lenv GIT_COMMITTER_NAME)" = "$default_login" ] \
  && ok "commits are made by the agent" || bad "author"
git check-ignore -q $local && ok "settings.local.json is git-ignored" || bad "settings.local.json is not ignored"
jq '.permissions.allow = ["Bash(ls *)"] | .env.MY_VAR = "1" | .env.GH_CONFIG_DIR = "/elsewhere"' $local > "$WORK/local.json"
mv "$WORK/local.json" $local
expect_exit 1 "doctor catches a changed agent env" bb $kit doctor
contains "doctor names settings.local.json" "$local: env differs" "$LAST"
expect_exit 0 "init restores the agent env" bb $kit init
jq -e '.permissions.allow == ["Bash(ls *)"] and .env.MY_VAR == "1"' $local >/dev/null \
  && ok "init keeps the owner's own local settings" || bad "local settings lost" "$(cat $local)"
[ "$(lenv GH_CONFIG_DIR)" = "$HOME/.config/gh-agent" ] && ok "agent env restored" || bad "agent env not restored"
sed -i '\#^/.claude/settings.local.json$#d' .git/info/exclude
expect_exit 1 "doctor catches settings.local.json that git would commit" bb $kit doctor
contains "doctor says why" "$local: is not git-ignored" "$LAST"
rm $local
expect_exit 0 "doctor --ci skips the per-machine settings" bb $kit doctor --ci
echo '{}' > .claude/settings.json
git config --unset core.hooksPath
expect_exit 0 "init --local succeeds" bb $kit init --local
git check-ignore -q $local && [ -f $local ] && ok "init --local writes and ignores settings.local.json" || bad "not restored"
[ "$(git config core.hooksPath)" = ".shared/githooks" ] && ok "init --local sets the hooks path" || bad "hooksPath not restored"
[ "$(cat .claude/settings.json)" = "{}" ] && ok "init --local leaves committed files alone" || bad "init --local touched settings.json"
bb $kit init >/dev/null
rm $local
git config --unset core.hooksPath
bash .shared/claude/hooks/session-start.sh >/dev/null
[ -f $local ] && [ "$(git config core.hooksPath)" = ".shared/githooks" ] \
  && ok "session start repairs the per-machine items itself" || bad "session start did not repair"

echo "- agent identity: who a Claude session acts as"
AG="$WORK/gh-agent"
mkdir -p "$AG" "$WORK/xdg/gh"
cp kit.edn "$WORK/kit.edn.bak"
bb -e "(spit \"kit.edn\" (pr-str (assoc (clojure.edn/read-string (slurp \"kit.edn\"))
         :agent {:login \"kit-bot\" :email \"kit-bot@example.invalid\" :gh-config-dir \"$AG\"})))"
bb $kit init >/dev/null
printf 'github.com:\n    user: the-owner\n    git_protocol: ssh\n' > "$WORK/xdg/gh/hosts.yml"
as_owner() { env -u GH_CONFIG_DIR XDG_CONFIG_HOME="$WORK/xdg" "$@"; }
expect_exit 0 "outside a Claude session a logged-out agent only warns" as_owner bb $kit doctor
contains "the warning names the agent's config" "WARN  the agent's gh config $AG is not logged in" "$LAST"
contains "the fix is kit init, which logs in" "kit init — it logs kit-bot in" "$LAST"
expect_exit 1 "doctor fails a Claude session that runs gh as the owner" as_owner env CLAUDECODE=1 bb $kit doctor
contains "doctor names the owner's login" "logged in as the-owner" "$LAST"
contains "with settings.local.json in place the fix is a restart" "restart this Claude session: $local is in place" "$LAST"
jq '.env.GH_CONFIG_DIR = "/elsewhere"' $local > "$WORK/local.json" && mv "$WORK/local.json" $local
expect_exit 1 "a session without the agent env fails too" as_owner env CLAUDECODE=1 bb $kit doctor
contains "then the fix is init and a restart" "kit init, then restart this Claude session" "$LAST"
bb $kit init >/dev/null
expect_exit 1 "doctor fails a session whose agent config is logged out" env GH_CONFIG_DIR="$AG" CLAUDECODE=1 bb $kit doctor
contains "inside a session that is a failure" "FAIL  the agent's gh config $AG is not logged in" "$LAST"
printf 'github.com:\n    user: someone-else\n' > "$AG/hosts.yml"
expect_exit 1 "doctor fails when the agent's config holds another login" env GH_CONFIG_DIR="$AG" CLAUDECODE=1 bb $kit doctor
contains "doctor names the wrong login" "logged in as someone-else, not kit-bot" "$LAST"
printf 'github.com:\n    users:\n        kit-bot:\n    git_protocol: https\n    user: kit-bot\n' > "$AG/hosts.yml"
expect_exit 0 "doctor passes a Claude session that acts as the agent" env GH_CONFIG_DIR="$AG" CLAUDECODE=1 bb $kit doctor
expect_exit 1 "doctor fails when GH_TOKEN overrides the agent's config" env GH_CONFIG_DIR="$AG" GH_TOKEN=x CLAUDECODE=1 bb $kit doctor
contains "doctor names the variable" "GH_TOKEN is set" "$LAST"

echo "- agent identity: kit init logs the agent in from a terminal"
if command -v script >/dev/null 2>&1; then
  FB="$WORK/fakebin"
  mkdir -p "$FB"
  cat > "$FB/gh" <<'FAKE'
#!/usr/bin/env bash
# Fake gh for the login cases: `auth login` stores $FAKE_LOGIN in $GH_CONFIG_DIR/hosts.yml.
echo "gh $*" >> "$GH_CONFIG_DIR/calls"
case "$1 $2" in
  "auth login")  printf 'github.com:\n    user: %s\n' "$FAKE_LOGIN" > "$GH_CONFIG_DIR/hosts.yml" ;;
  "auth logout") rm -f "$GH_CONFIG_DIR/hosts.yml" ;;
esac
FAKE
  chmod +x "$FB/gh"
  tty_run() { script -qec "$(printf '%q ' "$@")" /dev/null; }
  rm -f "$AG/hosts.yml" "$AG/calls"
  expect_exit 0 "init in a terminal logs the agent in" tty_run env PATH="$FB:$PATH" FAKE_LOGIN=kit-bot bb $kit init
  contains "init says which account to sign in as" "check that you are signed in as kit-bot" "$LAST"
  grep -qx "gh auth login --hostname github.com --git-protocol https --web --scopes project,workflow" "$AG/calls" \
    && ok "init runs gh's browser login into the agent's config" || bad "login call" "$(cat "$AG/calls" 2>&1)"
  grep -q "user: kit-bot" "$AG/hosts.yml" && ok "the agent is logged in" || bad "not logged in"
  rm -f "$AG/calls"
  expect_exit 0 "init with the agent logged in" tty_run env PATH="$FB:$PATH" bb $kit init
  [ ! -e "$AG/calls" ] && ok "a logged-in agent is left alone" || bad "logged in again" "$(cat "$AG/calls")"
  printf 'github.com:\n    user: the-owner\n' > "$AG/hosts.yml"
  expect_exit 0 "init with the wrong account in the agent's config" tty_run env PATH="$FB:$PATH" FAKE_LOGIN=the-owner bb $kit init
  grep -qx "gh auth logout --hostname github.com --user the-owner" "$AG/calls" && ok "the wrong account is logged out first" || bad "no logout"
  contains "a browser signed in as someone else is reported" "logged in as the-owner, not kit-bot" "$LAST"
  rm -f "$AG/calls"
  expect_exit 0 "init without a terminal" env PATH="$FB:$PATH" FAKE_LOGIN=kit-bot bb $kit init
  [ ! -e "$AG/calls" ] && ok "without a terminal init starts no login" || bad "login without a terminal"
  contains "and says how to log in" "kit init — it logs kit-bot in" "$LAST"
  rm -f "$AG/calls"
  # Inside a session init fails on the session's identity (gh runs as the owner here); it must not log in.
  expect_exit 1 "init inside a Claude session with a terminal" tty_run env PATH="$FB:$PATH" CLAUDECODE=1 FAKE_LOGIN=kit-bot bb $kit init
  [ ! -e "$AG/calls" ] && ok "a Claude session never starts a login" || bad "login inside a session"
else
  echo "  (skipped: no script command for a pseudo-terminal)"
fi
mv "$WORK/kit.edn.bak" kit.edn
bb $kit init >/dev/null

echo "- agent identity: repositories without dev-kit"
O="$WORK/other"
git init -q "$O"
expect_exit 0 "identity writes the agent env into another repository" bb $kit identity --dir "$O"
[ "$(lenv GIT_AUTHOR_NAME "$O/$local")" = "$default_login" ] && ok "other repository gets the default agent" || bad "other repository env"
git -C "$O" check-ignore -q $local && ok "and ignores the file there" || bad "not ignored in the other repository"
git worktree add -q "$WORK/wt" 2>/dev/null
expect_exit 0 "identity works in a linked worktree" bb $kit identity --dir "$WORK/wt"
git -C "$WORK/wt" check-ignore -q $local && ok "ignored in the worktree" || bad "not ignored in the worktree"
expect_exit 2 "identity refuses a directory that is not a repository root" bb $kit identity --dir "$WORK"

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

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
