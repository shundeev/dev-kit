# 0003. An owners team in an organization must approve every change

- Status: accepted
- Date: 2026-11-11

## Context

ADR-0001, choice 1, gave the agent its own GitHub account with Write and required one approving review on `main`, so that "a merge needs the owner". A test on 2026-11-11 (#22) showed a gap. The agent's approval counts toward that review. On a pull request opened by the owner, it is enough to merge.

Requiring a review from the code owners does not close it. With `* @u473t8` in CODEOWNERS and the rule active, GitHub treats the code-owner requirement as met when the author owns every changed file. The agent's approval then makes the owner's PR `APPROVED` and `CLEAN`. A ruleset in a user-owned repository has no other way to say whose approval counts: "required reviewers" takes teams only, and teams exist only in organizations.

## Options

- **CODEOWNERS with code-owner review** — tested, does not close the gap (above). Kept anyway: harmless, and needed once there are several owners.
- **The agent without Write (Triage), working from a fork** — server-enforced: its approvals never count and it cannot merge. Lost to the next option because the agent could no longer merge after the owner's Approve, and the flow scripts would move to forks.
- **Rules on the agent's side only** (deny `gh pr review --approve`, a merge script that checks who approved) — cheap, but enforced by the agent's own tooling, not by the server; ADR-0001 asks for the server.
- **An organization with a team `owners` as required reviewers** — chosen, see below.

## Decision

The owner's repositories move into an organization they own. A team `owners` (the owner, later colleagues) has Write. The `main` ruleset requires 1 approving review and lists `owners` as required reviewers for every file; the owner stays the only bypass actor, in pull-request mode. The agent keeps Write: it pushes branches and merges after an approval, but its approval never satisfies the team requirement.

This decision holds only if a test in a throwaway organization shows that the agent's approval on a PR authored by a team member leaves it blocked. The pull request that carries this ADR stays a draft until that test passes; if it fails, this ADR is rewritten for the fork option before it is merged.

## Consequences

- Repository URLs change to the organization. GitHub redirects the old ones, but remotes, the `.shared` submodule URL, `kit-defaults.edn`, the board and the profile pins are updated in the migration.
- A colleague joins by being added to the team, with no ruleset edit. CODEOWNERS names the team.
- The owner's own PRs merge through the bypass, as before. The agent's PRs merge on the owner's Approve.
- The organization adds an account to keep: owner 2FA and membership. It costs nothing on the free plan for public repositories.
