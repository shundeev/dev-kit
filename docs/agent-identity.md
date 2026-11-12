# The agent's GitHub identity

The agent works under its own GitHub account (`:agent` in `kit-defaults.edn`), so GitHub tells
its actions from the owner's. The repositories live in the organization `shundeev` (ADR-0003).
Each `main` ruleset requires one approving review and names the team `shundeev/owners` as the
required reviewer of every file, so a pull request merges only with the approval of a team
member; the agent's approval never counts, and nobody can approve their own. A push after the
approval dismisses it. The owner is the only bypass actor, in pull-request mode: they merge
their own pull requests that way, and still cannot push to `main` directly. `.github/CODEOWNERS`
(from `:owners`) names the same team. CODEOWNERS alone was not enough: GitHub counts the
author's own code ownership, so the agent's approval completed a pull request the owner had
opened (#22). A colleague joins by being added to the team.

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
- PRs opened by the agent request a review from `:owners` and are assigned to the person behind
  the agent: `kit init` reads that person's own gh login on the machine (`GHWF_HUMAN`), so a
  colleague's agent assigns the colleague.

Not a security boundary on its own: the owner's gh config and SSH key stay readable from a
session until the sandbox (ADR-0001, choice 3) denies them.
