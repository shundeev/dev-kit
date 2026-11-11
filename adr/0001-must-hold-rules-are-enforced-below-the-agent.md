# 0001. Rules that must hold are enforced below the agent; agent-side layers give early feedback

- Status: accepted
- Date: 2026-11-11

## Context

dev-kit's rules were spread over instruction text, Claude Code permissions, a PreToolUse hook,
a git pre-commit hook, CI and GitHub rulesets, with no stated design for which rule lives where.
Research for #1 (summary in its comments) and a /code-review of dev-kit found:

- Vendor docs draw the same line: instruction text is advice; "if something has to happen
  every time, make it a hook, a deny rule, or a managed setting" (code.claude.com/docs/en/best-practices).
- Every agent-side layer is string-level and leaks. Permission rules match command text and are
  "not a security boundary around the program" (`sh -c`, other spellings). Our delivery guard
  misses `$(gh issue create …)`, `gh issue new`, `bash -c` and more (#2).
- Hooks fail open by default: a crash, a missing script or a timeout lets the call through
  unless the hook sets `onFailure: "block"` (Claude Code v2.1.295+, code.claude.com/docs/en/hooks).
  Ours exit 0 when jq or `.shared` is missing.
- The agent works with the owner's own GitHub credentials, so the server cannot tell agent from
  owner: "merge only on the owner's word" rests on a permission prompt alone. GitHub's own coding
  agent works under a separate identity that cannot merge or approve.
- A pull request can bump `.shared` or edit workflows and thereby weaken the checks that judge it.
- Codex and OpenCode now have their own hooks and permission systems; policies that live on the
  server or in CI hold for every agent, agent-side ones need an adapter per agent.

## Options

- **Instructions plus local hooks only.** Cheapest; every guarantee leaks, as shown above.
- **Sandbox everything (container per session).** Strong locally, heavy day to day, and does not
  stop a merge or a weakened CI.
- **Layers with a server-side backstop (chosen).** Each must-hold rule gets a check that the agent
  cannot edit locally; agent-side layers stay because they fail early and cheaply.

## Decision

Seven layers, bottom = hardest to bypass:

| # | Layer | What belongs here | Known leaks |
|---|---|---|---|
| L1 | GitHub server: rulesets, required checks, required review, CODEOWNERS | Everything that must hold for `main`: PR-only, squash, green checks, the owner's approval | Admin bypass; sees only pushed state |
| L2 | CI on every PR | Re-check every local claim: `kit doctor --ci`, lint, format, tests, PR shape (issue link, title, `ADR:` line), ADR immutability | Only as strong as L1 protecting the CI files |
| L3 | OS sandbox (Claude Code `sandbox.*`) | File and network confinement of shell commands | Shell commands only; hooks and MCP run outside |
| L4 | Agent harness: permission rules and PreToolUse hooks | Fast refusal of destructive or off-process commands | String-level; per agent |
| L5 | git hooks | Format before commit | `--no-verify`, fresh clones |
| L6 | SessionStart `kit doctor` | Make drift visible at session start | Reports only |
| L7 | AGENTS.md | Preferences and the reasons behind the rules | Advice only |

Rules for using the stack:

1. Every rule that must hold has a check in L1 or L2 (or L3 for local confinement). L4–L6 are
   early feedback, never the only guarantee.
2. A rule the agent keeps breaking moves down the stack — into a mechanism, not into emphasis.
3. Agent-side layers fail closed: every PreToolUse hook sets `onFailure: "block"`; the hook
   wrapper lets repair commands (`git submodule update`, `kit init`) through when `.shared` is
   missing, so a broken kit cannot lock itself out.
4. Escape hatches are configured off: `permissions.disableBypassPermissionsMode: "disable"`;
   with the sandbox on, `allowUnsandboxedCommands: false` and `failIfUnavailable: true`.

Five concrete choices (agreed with the owner on 2026-11-11):

1. **The agent gets its own GitHub identity.** It pushes branches, opens PRs and comments under
   that identity. The `main` ruleset requires one approving review; the agent cannot approve
   its own PR, so a merge needs the owner. The owner keeps a pull-request-only bypass for PRs
   of their own. (Machine account vs GitHub App is settled in the implementation issue.)
2. **Hooks fail closed** as in rule 3.
3. **Sandbox: pilot first.** Enable Claude Code's sandbox with a network allowlist in one
   project; adopt in the base settings only after it has been used day to day.
4. **Destructive commands are guarded by cc-safety-net** (kenryu42/cc-safety-net, MIT): it parses
   commands instead of matching text, covers secrets reads, and has adapters for Codex and
   OpenCode. dev-kit's own `delivery-guard.sh` keeps only process rules (board, branches).
5. **CODEOWNERS protects the checks themselves:** `.gitmodules`, `.shared`, `.github/`,
   `.claude/settings.json` and `adr/` require the owner's review (effective with choice 1).

Catalogue of forbidden behaviours and the layers that stop them (✓ = in place today):

| Behaviour | Backstop (L1–L3) | Early feedback (L4–L6) |
|---|---|---|
| Push to `main` | L1 PR-only ruleset ✓ | — |
| Merge without the owner | L1 required review + agent identity | L4 ask on `gh pr merge`, `merge_issue_flow.sh` ✓ |
| Force push to `main` | L1 non-fast-forward ✓ | L4 deny rules ✓, cc-safety-net |
| Force push of an issue branch without lease | — (own branch, accepted) | L4 deny rules ✓, cc-safety-net |
| Destructive local git (`reset --hard`, `clean`, `branch -D`, `checkout --`) | L3 sandbox limits the reach | L4 ask rules ✓, cc-safety-net |
| Reading secrets (`.env`, `~/.ssh`) | L3 sandbox read deny | L4 cc-safety-net, Read deny rules |
| Issue outside the board, PR without an issue | L2 PR must link an open issue and come from `<n>-<slug>` | L4 delivery-guard ✓ |
| PR without an `ADR:` line or a malformed title | L2 PR-shape check | L4 finish script ✓ |
| Editing an accepted ADR | L2 files in `adr/` on the base branch are unchanged | L7 adr skill ✓ |
| Editing dev-kit managed files in a project | L2 `kit doctor --ci` ✓ | L6 doctor ✓ |
| Weakening CI, hooks or the kit pin in a PR | L1 CODEOWNERS + required review | — |
| Skipping git hooks (`--no-verify`) | L2 re-runs the same checks ✓ | — |
| Self-applied `DELIVERY_GUARD=off` | — (becomes an owner prompt) ✓ | L4 ask ✓ |
| Bypass-permissions mode | — | L4 `disableBypassPermissionsMode` |
| A guard hook that crashes or is missing | — | L4 `onFailure: "block"` |

## Consequences

- "Merge only on the owner's word" and "no weakened checks" become server-enforced instead of
  depending on the agent's prompt; the cost is a second GitHub identity and an approval click
  per PR.
- CI grows a PR-shape job (issue link, branch name, title, `ADR:` line, ADR immutability), which
  holds for every agent, not just Claude Code.
- A broken hook now stops work instead of silently allowing it; the repair path must stay open
  and is covered by selftest.
- cc-safety-net becomes a dependency of every project; its install and version are checked by
  `kit doctor`.
- The sandbox is not part of the base yet; its absence is a known gap until the pilot ends.
- Implementation is tracked in #2 and follow-up issues; each catalogue row without ✓ gets a
  check and a selftest case.
