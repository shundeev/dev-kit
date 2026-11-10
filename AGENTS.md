# dev-kit

This repository is the shared kit itself. It is never mounted into itself, so `kit init` does
not run here.

- Before any work, read `rules/shared.md`: those rules apply here exactly as in every project.
  The flow scripts and the guard are wired for this repository in `.claude/settings.json`.
- What the kit does and how a project uses it: README.md.
- Every change runs `test/selftest.sh` before the PR; CI runs it too. A behaviour change gets a
  selftest case first.
- Changing `rules/`, `claude/`, `managed/` or `scaffold/` changes every project on its next
  `kit init`. Say in the PR body what projects will see.
- Shell scripts pass `shellcheck -S warning`; `bin/kit` passes `clj-kondo`.
