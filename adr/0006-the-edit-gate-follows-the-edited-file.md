# 0006. The design approval gate judges the repository and branch of the edited file

- Status: accepted
- Date: 2026-11-14

## Context

ADR-0002 made the owner's `design-approved` (G1) a gate before code on an M issue, and the edit hook enforces it. The hook asked about the folder the Claude session was opened in, not about the file being edited. In #93 the session was in the site, on `main`, and the agent edited files in a dev-kit worktree on the branch of an M issue without the approval. The hook looked at the site's `main`, found nothing to gate, and let the edits through; only CI caught it, on the PR (#99).

Claude Code's hooks documentation (https://code.claude.com/docs/en/hooks, PreToolUse) says `tool_input.file_path` of `Write` and `Edit` is always absolute, so the hook can tell which file is meant. Its `NotebookEdit` and `MultiEdit` inputs are not documented; the gate keeps reading `notebook_path` as an assumption.

## Options

- **Judge the file's repository.** Chosen.
- **Judge both the session's folder and the file's repository, refuse if either fails.** A harmless edit elsewhere would be refused because of the session's own branch: a new false refusal.
- **Only warn when the file lies outside the session's repository.** A warning stops nothing, and ADR-0001 enforces must-hold rules below the agent.

## Decision

- The edit hook takes the real path of the nearest existing directory of the edited file (symlinks resolved, a missing directory walked up) and asks git there for the repository root and branch. The session's folder plays no part.
- Only a repository on dev-kit is gated: its root holds `kit.edn`, or `kit-defaults.edn` (dev-kit itself). A file outside any repository, or in another repository, is allowed and GitHub is not asked.
- In a gated repository the rule of ADR-0002 is unchanged: on an issue branch `<n>-<slug>`, the edit is refused while the issue's design approval is missing. The issue number comes from the branch, the repository from its `origin`, the verdict is cached for 10 minutes in that repository's git dir, and GitHub unreachable means refused, with `kit gate --g1 <n>` as the fix.
- An input with no path (an unknown field name, an empty input) is judged by the session's folder, the input's `cwd` or else `.`, as before: it fails closed on an unapproved session branch.
- The repository asked about is the file's own: its GitHub `origin`, else `:repo` of its `kit.edn`. Never the session's, and `GITHUB_REPOSITORY` does not override it. A gated repository on an issue branch with neither is refused, with the fix: set `:repo` in its `kit.edn`, or add its GitHub origin.
- The `adr/` exception is counted from the edited file's own repository root.
- An edit inside the `.shared` submodule, or through a `.claude/skills/*` symlink into it, is not gated: its HEAD is detached, so it is on no issue branch. Changes to dev-kit belong in a dev-kit worktree, which is gated.
- **Supersedes:** nothing. It refines how ADR-0002's G1 is applied at edit time; CI's gate is unchanged.

## Known gaps

Shared with the hook before this change, and left open:
- An edit inside `<repo>/.git/` is not gated.
- A git failure (an unborn branch, "dubious ownership") allows the edit.
- A dangling symlink from a repository that is not gated into another one is judged by the link's directory.

## Consequences

- An edit in any worktree is held to that worktree's issue, wherever the session was opened.
- Edits in the session's own repository get the same verdicts as before.
- The hook adds about 1 ms of git per edit.
- A dev-kit change made through `.shared` is no longer judged by the session's branch; `kit doctor` still reports a locally modified submodule, and CI still gates the PR.
