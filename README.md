# dev-kit

How I run engineering work with AI agents across my repositories: one set of agent rules,
skills, hooks, lint and format settings, and CI checks, mounted into every project as a git
submodule and kept in place by two commands.

Every project gets the same delivery flow:

```
issue on the board → branch from the issue → [ADR] → implement → verify → PR → review → squash merge
```

The flow is enforced, not just written down: a Claude Code hook refuses issues filed outside
the board and pull requests from branches with no issue, the flow scripts refuse an issue
without a priority and a PR without an `ADR:` line, and the `main` ruleset accepts pull
requests only.

## Use in a project

```sh
git submodule add https://github.com/u473t8/dev-kit .shared
.shared/bin/kit init --showcase <board Showcase option> [--modules clojure] [--scaffold]
git add -A && git commit -m "chore: add dev-kit"
```

A fresh clone: `git clone --recurse-submodules …`, then `.shared/bin/kit init` (it sets the
local git hooks path, which a clone does not carry).

| Command | What it does |
|---|---|
| `.shared/bin/kit init` | Lays everything out. Safe to rerun; never overwrites project-owned files. |
| `.shared/bin/kit doctor` | Checks that everything is in place and matches the pinned dev-kit version. Each problem comes with the command that fixes it. `--ci` skips local-only checks, `--quiet` prints problems only. |
| `.shared/bin/kit modules` | `clojure=true/false`, for CI. |

Update a project to a newer dev-kit: `git submodule update --remote .shared && .shared/bin/kit init`,
then a pull request "chore: bump dev-kit".

## What a project gets

Two kinds of files:

- **Scaffold** — created once when missing, then owned by the project:
  `kit.edn`, `LICENSE` (MIT), the project part of `AGENTS.md`;
  with `--scaffold` also `README.md` (problem · approach · measurements · what I learned ·
  limitations · reproduce), `adr/`, `docs/experiments/`.
- **Managed** — owned by dev-kit, rewritten by `init`, verified by `doctor` locally, at session
  start and in CI:

| In the project | Comes from |
|---|---|
| `AGENTS.md`, the block between `dev-kit:begin` / `dev-kit:end` | `rules/shared.md` (+ `rules/clojure.md`) |
| `.claude/settings.json` | `claude/settings.base.json` + board settings from `kit.edn` |
| `.claude/skills/*` (links) | `claude/skills/` — `repo-task-delivery`, `gh-project-workflow`, `adr` |
| `.claude/agents/*.md` (copies) | `claude/agents/` — `ops`, `log-reader`; `smell-reviewer` with Clojure |
| `.github/workflows/ci.yml` | `managed/ci.yml`, which runs `ci/action.yml` from the submodule |
| git `core.hooksPath` | `githooks/` — zprint check before commit |
| `.zprint.edn` (link), `:config-paths` in `.clj-kondo/config.edn` | `clojure/` (module `clojure`) |

Why `AGENTS.md` and not `CLAUDE.md`: one instruction file for every agent (Claude Code reads
`AGENTS.md` when no `CLAUDE.md` exists; Codex and OpenCode read it natively). The shared rules
are written into it as text rather than imported, because only Claude Code understands `@` imports.

Why CI runs from the submodule: the checks always match the dev-kit version the project pins,
and the workflow file in the project never changes.

## kit.edn

```clojure
{:repo     "u473t8/shundeev"
 :showcase "Site"
 :modules  #{}}          ; #{:clojure} for the shared zprint/clj-kondo setup
```

Defaults (board, base branch) come from `kit-defaults.edn`; `:board`, `:base` and
`:claude-settings` (merged into the generated settings) can be overridden per project.

## Developing dev-kit

```sh
test/selftest.sh     # builds a throwaway project, runs init/doctor/repairs, hooks and flow guards
```

The delivery scripts, the guard hook and the subagents were first built for
[Sprecha](https://github.com/the-kind-robots/learning-app) and generalised here.

## License

MIT
