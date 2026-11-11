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
local git hooks path and the agent's identity, which a clone does not carry).

A new machine:

1. Install `git`, `gh`, `bb` (babashka), `jq`, and Claude Code.
2. Log yourself in for your own terminal: `gh auth login`.
3. Clone with `--recurse-submodules` and run `.shared/bin/kit init` in your terminal. It also
   logs the agent's account in: gh shows a one-time code, you enter it on github.com/login/device
   while signed in as the agent (switch account, or use a private window).

Forgot step 3 and started Claude? The first session writes the per-machine settings itself and
tells you what is left: restart the session, or run `kit init` in your terminal for the login.

| Command | What it does |
|---|---|
| `.shared/bin/kit init` | Lays everything out. Safe to rerun; never overwrites project-owned files. In a terminal it logs the agent's account in when it is not. `--local` touches only the per-machine items; the SessionStart hook runs it. |
| `.shared/bin/kit doctor` | Checks that everything is in place and matches the pinned dev-kit version. Each problem comes with the command that fixes it. `--ci` skips local-only checks, `--quiet` prints problems only. |
| `.shared/bin/kit modules` | `clojure=true/false`, for CI. |
| `.shared/bin/kit identity [--dir PATH]` | Writes only the agent's GitHub identity (below) into a repository's `.claude/settings.local.json`: for dev-kit itself, or a repository that does not mount dev-kit yet. |

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
| `.github/CODEOWNERS` | `* @<login> …` from `:owners` in `kit.edn`: one of them reviews every change |
| git `core.hooksPath` | `githooks/` — zprint check before commit |
| `.zprint.edn` (link), `:config-paths` in `.clj-kondo/config.edn` | `clojure/` (module `clojure`) |
| `env` in `.claude/settings.local.json` — per machine, git-ignored, skipped by `doctor --ci` | `:agent` in `kit.edn` / `kit-defaults.edn` (below) |

Why `AGENTS.md` and not `CLAUDE.md`: one instruction file for every agent (Claude Code reads
`AGENTS.md` when no `CLAUDE.md` exists; Codex and OpenCode read it natively). The shared rules
are written into it as text rather than imported, because only Claude Code understands `@` imports.

Why CI runs from the submodule: the checks always match the dev-kit version the project pins,
and the workflow file in the project never changes.

## The agent's GitHub identity

The agent works under its own GitHub account (`:agent` in `kit-defaults.edn`), so GitHub tells
its actions from the owner's. Each repository's `main` ruleset requires one approving review
and a review from the code owners; a push after the approval dismisses it. `.github/CODEOWNERS`
makes the people in `:owners` the code owners of every file, so a pull request merges only with
the approval of one of them — the agent's approval does not count, and nobody can approve their
own. A colleague joins by being added to `:owners` (and given Write access); then the owner and
the colleague review each other's pull requests and the agent's. The owner is the ruleset's only bypass actor, in pull-request mode: they
can merge a pull request without a review (needed for their own, which nobody else can approve),
and still cannot push to `main` directly.

- Once per machine the account is logged into its own gh config, `~/.config/gh-agent`. `kit init`
  run in a terminal does it: it starts gh's browser login, the owner finishes it signed in as the
  agent, and init checks which account came back (a wrong one is logged out on the next run).
  The token stays in that directory; it never goes into chat, issues or a repository. A Claude
  session never starts the login.
- `kit init` writes the identity into `.claude/settings.local.json` and git-ignores it. Claude
  Code applies that `env` to the session, its hooks and its commands: `gh` reads the agent's
  config, git sends `git@github.com:` remotes over HTTPS with gh as the only credential helper,
  and commits are authored by the agent. The owner's terminal keeps the owner's login and SSH.
  The SessionStart hook writes the file too (`kit init --local`), so a fresh clone or worktree
  is set up by its first session; the env applies from the next one.
- `kit doctor` warns when the agent is not logged in on this machine. Inside a Claude session it
  fails when gh would act as anyone but the agent — a session on another gh config (the owner's),
  a logged-out or foreign agent config, a `GH_TOKEN` / `GITHUB_TOKEN` override — and says what
  is left: restart the session, or `kit init` in a terminal.
- Without the login, a session cannot use GitHub at all (`gh` is logged out, `git push` finds no
  credentials); it never falls back to the owner's account.
- `:agent nil` in a project's `kit.edn` switches this off.

Not a security boundary on its own: the owner's gh config and SSH key stay readable from a
session until the sandbox (ADR-0001, choice 3) denies them.

## kit.edn

```clojure
{:repo     "u473t8/shundeev"
 :showcase "Site"
 :modules  #{}}          ; #{:clojure} for the shared zprint/clj-kondo setup
```

Defaults (board, base branch, code owners, agent account) come from `kit-defaults.edn`; `:board`,
`:base`, `:owners`, `:agent` and `:claude-settings` (merged into the generated settings) can be
overridden per project.

## Developing dev-kit

```sh
test/selftest.sh     # builds a throwaway project, runs init/doctor/repairs, hooks and flow guards
```

The delivery scripts, the guard hook and the subagents were first built for
[Sprecha](https://github.com/the-kind-robots/learning-app) and generalised here.

## License

MIT
