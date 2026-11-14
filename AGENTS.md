# dev-kit

This repository is the shared kit itself. It is never mounted into itself, so `kit init` does
not run here.

- Before any work, read `rules/shared.md`: those rules apply here exactly as in every project.
  The flow scripts and the guard are wired for this repository in `.claude/settings.json`.
- What the kit does and how a project uses it: README.md.
- Every change runs `test/selftest.sh` before the PR; CI runs it too. A behaviour change gets a
  selftest case first.
- The rules block `rules/shared.md` stays within 100 lines (selftest checks it). When it is full,
  a rule moves to the skill stage where it is used; it is not compressed.
- Changing `rules/`, `claude/`, `managed/` or `scaffold/` changes every project on its next
  `kit init`. Say in the PR body what projects will see.
- Logic is written in Babashka (ADR-0005). bash only for the bootstrap that must run when dev-kit
  or bb is missing (`managed/pretooluse.sh`, `claude/hooks/session-start.sh`, `githooks/pre-commit`)
  and for the outside drivers `test/selftest.sh` and `test/e2e.sh`. A hook that runs before every
  call is a small bb file of its own, not a `kit` subcommand: loading `kit` costs 55 ms.
- Shell scripts pass `shellcheck -S warning`; `bin/kit` and `claude/hooks/*.clj` pass `clj-kondo`.
- Claude sessions here act as the agent's GitHub account once `bb bin/kit identity --dir .` has
  written the git-ignored `.claude/settings.local.json` (per clone and per worktree; run in a
  terminal it also logs the agent in), then the session is restarted. Check:
  `gh api user --jq .login` names the agent, not the owner.
